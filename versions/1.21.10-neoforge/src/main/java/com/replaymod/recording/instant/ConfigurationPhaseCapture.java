package com.replaymod.recording.instant;

import com.mojang.serialization.Codec;
import com.replaymod.core.versions.MCVer;
import com.replaymod.recording.mixin.DecoderHandlerAccessor;
import com.replaymod.recording.mixin.NetworkManagerAccessor;
import com.replaymod.recording.packet.PacketListener;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State;
import com.replaymod.replaystudio.protocol.Packet;
import com.replaymod.replaystudio.protocol.PacketType;
import com.replaymod.replaystudio.protocol.PacketTypeRegistry;
import com.replaymod.replaystudio.protocol.packets.PacketEnabledPacksData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkPhase;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.handler.DecoderHandler;
import net.minecraft.network.handler.NetworkStateTransitions;
import net.minecraft.network.packet.s2c.common.ResourcePackRemoveS2CPacket;
import net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket;
import net.minecraft.network.packet.s2c.config.ReadyS2CPacket;
import net.minecraft.network.packet.s2c.login.LoginCompressionS2CPacket;
import net.minecraft.network.packet.s2c.login.LoginHelloS2CPacket;
import net.minecraft.network.packet.s2c.login.LoginSuccessS2CPacket;
import net.minecraft.network.state.ConfigurationStates;
import net.minecraft.network.state.LoginStates;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryOps;
import net.minecraft.world.dimension.DimensionType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Keeps the login and configuration phase packets of a connection, so a recording started later in the play phase can
 * begin with exactly what the server sent (registries, tags, feature flags, NeoForge channel negotiation, ...).
 * The client cannot faithfully rebuild that data from its own state, unlike the play phase.
 *
 * <p>Its network handlers only exist while the connection is in the login or configuration phase; once the client
 * enters the play phase they are removed, so nothing runs per play packet while not recording.</p>
 */
public final class ConfigurationPhaseCapture {
    private static final Logger LOGGER = LogManager.getLogger("ReplayMod/InstantRecording");
    private static final String RAW_KEY = "replay_config_capture_raw";
    private static final String DECODED_KEY = "replay_config_capture_decoded";
    private static final AttributeKey<ConfigurationPhaseCapture> ATTR =
            AttributeKey.valueOf("replaymod_configuration_capture");

    private final Channel channel;
    private final List<Packet> loginPhase = new ArrayList<>();
    private Packet loginSuccess;
    private final List<Packet> configurationPhase = new ArrayList<>();
    /** Snapshot of the last finished configuration phase, {@code null} while (re-)configuring. */
    private List<Packet> completedConfiguration;
    private Packet currentRawPacket; // only accessed on the channel's event loop

    private ConfigurationPhaseCapture(Channel channel) {
        this.channel = channel;
    }

    public static ConfigurationPhaseCapture get(ClientConnection connection) {
        Channel channel = ((NetworkManagerAccessor) connection).getChannel();
        return channel == null ? null : channel.attr(ATTR).get();
    }

    /**
     * Starts capturing a connection which is in the login phase. Called on the channel's event loop.
     */
    public static void beginLogin(ClientConnection connection) {
        Channel channel = ((NetworkManagerAccessor) connection).getChannel();
        if (channel == null || channel.attr(ATTR).get() != null) {
            return;
        }
        ConfigurationPhaseCapture capture = new ConfigurationPhaseCapture(channel);
        channel.attr(ATTR).set(capture);
        capture.attach();
    }

    /**
     * Records the login success packet, which is handled before our handlers see it. Called on the event loop.
     */
    public static void onLoginSuccess(ClientConnection connection, LoginSuccessS2CPacket packet) {
        ConfigurationPhaseCapture capture = get(connection);
        if (capture == null) {
            return;
        }
        ByteBuf buf = Unpooled.buffer();
        try {
            LoginStates.S2C.codec().encode(buf, packet);
            Packet encoded = PacketListener.decodePacket(NetworkPhase.LOGIN, buf);
            synchronized (capture) {
                capture.loginSuccess = encoded;
            }
        } finally {
            buf.release();
        }
    }

    /**
     * Re-arms the capture when the server moves the client back into the configuration phase. Called on the main
     * thread before the client switches its decoder, so our handlers are in place for the first configuration packet.
     */
    public static void beginReconfiguration(ClientConnection connection) {
        ConfigurationPhaseCapture capture = get(connection);
        if (capture == null) {
            return;
        }
        synchronized (capture) {
            capture.configurationPhase.clear();
            capture.completedConfiguration = null;
        }
        capture.channel.eventLoop().execute(capture::attach);
    }

    /**
     * Completes the configuration phase. Called on the main thread right before the client switches to the play
     * phase, mirroring what the regular recorder stores at this point (see MixinNetHandlerConfigClient).
     */
    public static void finishConfiguration(ClientConnection connection, DynamicRegistryManager.Immutable registries) {
        ConfigurationPhaseCapture capture = get(connection);
        if (capture == null) {
            return;
        }
        synchronized (capture) {
            capture.configurationPhase.add(enabledPacksDataPacket(registries));
            capture.configurationPhase.add(new Packet(registry(), configurationPacketId(ReadyS2CPacket.INSTANCE),
                    PacketType.ConfigFinish, com.github.steveice10.netty.buffer.Unpooled.buffer()));
            capture.completedConfiguration = new ArrayList<>(capture.configurationPhase);
        }
        capture.channel.eventLoop().execute(capture::detach);
        LOGGER.debug("Kept {} configuration packets for instant recordings; capture handlers removed",
                capture.completedConfiguration.size());
    }

    /**
     * Returns copies of all login and configuration packets which lead into the current play phase, in order, or
     * {@code null} if the connection is currently (re-)configuring or its login was not observed.
     */
    public synchronized List<Packet> copyPacketsBeforePlay() {
        if (loginSuccess == null || completedConfiguration == null) {
            return null;
        }
        List<Packet> packets = new ArrayList<>(loginPhase.size() + 1 + completedConfiguration.size());
        for (Packet packet : loginPhase) {
            packets.add(packet.copy());
        }
        packets.add(loginSuccess.copy());
        for (Packet packet : completedConfiguration) {
            packets.add(packet.copy());
        }
        return packets;
    }

    private void attach() {
        if (!channel.isOpen() || channel.pipeline().get(RAW_KEY) != null) {
            return;
        }
        String target = channel.pipeline().get("inbound_config") != null ? "inbound_config" : PacketListener.DECODER_KEY;
        if (channel.pipeline().get(target) == null) {
            LOGGER.warn("Cannot capture configuration phase, pipeline has no {} handler", target);
            return;
        }
        channel.pipeline().addBefore(target, RAW_KEY, new RawHandler());
        channel.pipeline().addAfter(target, DECODED_KEY, new DecodedHandler());
    }

    private void detach() {
        if (channel.pipeline().get(DECODED_KEY) != null) {
            channel.pipeline().remove(DECODED_KEY);
        }
        if (channel.pipeline().get(RAW_KEY) != null) {
            channel.pipeline().remove(RAW_KEY);
        }
        currentRawPacket = null;
    }

    private NetworkPhase currentPhase() {
        DecoderHandlerAccessor<?> decoder = (DecoderHandlerAccessor<?>) channel.pipeline().get(DecoderHandler.class);
        return decoder == null ? NetworkPhase.LOGIN : decoder.getState().id();
    }

    private static PacketTypeRegistry registry() {
        return MCVer.getPacketTypeRegistry(State.CONFIGURATION);
    }

    private static int configurationPacketId(net.minecraft.network.packet.Packet<?> packet) {
        ByteBuf buf = Unpooled.buffer();
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            net.minecraft.network.packet.Packet raw = packet;
            ConfigurationStates.S2C.codec().encode(buf, raw);
            return new PacketByteBuf(buf).readVarInt();
        } finally {
            buf.release();
        }
    }

    /**
     * Same payload as written by MixinNetHandlerConfigClient: ReplayStudio needs the dimension types of the data
     * packs the server and client agreed on.
     */
    private static Packet enabledPacksDataPacket(DynamicRegistryManager.Immutable registryManager) {
        ByteBuf byteBuf = Unpooled.buffer();
        try {
            PacketByteBuf buf = new PacketByteBuf(byteBuf);
            buf.writeString(PacketEnabledPacksData.ID);
            RegistryOps<NbtElement> ops = registryManager.getOps(NbtOps.INSTANCE);
            buf.writeVarInt(1);
            writeRegistry(buf, registryManager.getOrThrow(RegistryKeys.DIMENSION_TYPE), DimensionType.CODEC, ops);
            byte[] bytes = new byte[byteBuf.readableBytes()];
            byteBuf.readBytes(bytes);
            int packetId = configurationPacketId(new net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket(
                    new net.minecraft.network.packet.BrandCustomPayload("")));
            return new Packet(registry(), packetId, PacketType.ConfigCustomPayload,
                    com.github.steveice10.netty.buffer.Unpooled.wrappedBuffer(bytes));
        } finally {
            byteBuf.release();
        }
    }

    private static <T> void writeRegistry(PacketByteBuf buf, Registry<T> registry, Codec<T> codec,
                                          RegistryOps<NbtElement> ops) {
        buf.writeString(registry.getKey().getValue().toString());
        buf.writeVarInt(registry.size());
        for (Map.Entry<RegistryKey<T>, T> entry : registry.getEntrySet()) {
            buf.writeString(entry.getKey().getValue().toString());
            buf.writeNbt(codec.encodeStart(ops, entry.getValue()).getOrThrow());
        }
    }

    @ChannelHandler.Sharable
    private final class RawHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            currentRawPacket = null;
            if (msg instanceof ByteBuf buf && buf.readableBytes() > 0) {
                NetworkPhase phase = currentPhase();
                if (phase == NetworkPhase.LOGIN || phase == NetworkPhase.CONFIGURATION) {
                    currentRawPacket = PacketListener.decodePacket(phase, buf);
                }
            }
            super.channelRead(ctx, msg);
        }
    }

    private final class DecodedHandler extends ChannelDuplexHandler {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            Packet raw = currentRawPacket;
            currentRawPacket = null;
            // Login handshake internals are never part of a replay (see PacketListener). Resource packs are recorded
            // from the client's applied packs when the recording starts, and the finish packet is added once the
            // registries are known (finishConfiguration).
            boolean skip = msg instanceof LoginHelloS2CPacket
                    || msg instanceof LoginCompressionS2CPacket
                    || msg instanceof ReadyS2CPacket
                    || msg instanceof ResourcePackSendS2CPacket
                    || msg instanceof ResourcePackRemoveS2CPacket;
            if (raw != null && !skip) {
                synchronized (ConfigurationPhaseCapture.this) {
                    if (raw.getRegistry().getState() == State.LOGIN) {
                        loginPhase.add(raw);
                    } else {
                        configurationPhase.add(raw);
                    }
                }
            }
            super.channelRead(ctx, msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof NetworkStateTransitions.DecoderTransitioner transitioner) {
                // Stay right behind the decoder when MC inserts the bundler on phase transitions (see PacketListener)
                msg = transitioner.andThen(context -> {
                    context.pipeline().remove(this);
                    context.pipeline().addAfter(PacketListener.DECODER_KEY, DECODED_KEY, new DecodedHandler());
                });
            }
            super.write(ctx, msg, promise);
        }
    }
}
