package com.replaymod.recording.instant;

import com.replaymod.core.ReplayMod;
import com.replaymod.recording.Setting;
import com.replaymod.recording.handler.ConnectionEventHandler;
import com.replaymod.recording.handler.RecordingEventHandler;
import com.replaymod.recording.mixin.DecoderHandlerAccessor;
import com.replaymod.recording.packet.PacketListener;
import com.replaymod.replay.ReplayModReplay;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.handler.DecoderHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.state.NetworkState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.function.Consumer;

/**
 * Starts a recording in the middle of a play session without reconnecting.
 *
 * <p>The replay begins with the login and configuration packets kept by {@link ConfigurationPhaseCapture}, followed by
 * a {@link PlayStateSnapshot} of the client's current state; from then on the regular recorder captures the network
 * traffic. Nothing is hooked into the connection before this is called.</p>
 */
public final class InstantRecording {
    private static final Logger LOGGER = LogManager.getLogger("ReplayMod/InstantRecording");

    public enum StartResult {
        STARTED,
        NOT_IN_WORLD,
        ALREADY_RECORDING,
        /** The connection is (re-)configuring or its login was not observed. */
        CONFIGURATION_UNAVAILABLE,
        FAILED,
    }

    private InstantRecording() {
    }

    /**
     * Must be called on the client thread. {@code onStarted} is called on the client thread once the initial state has
     * been written and the recorder is live, or with {@code null} if starting failed after this method returned.
     */
    public static StartResult start(ReplayMod core, ConnectionEventHandler connectionEventHandler,
                                    Consumer<PacketListener> onStarted) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null || mc.getNetworkHandler() == null
                || ReplayModReplay.instance.getReplayHandler() != null) {
            return StartResult.NOT_IN_WORLD;
        }
        if (connectionEventHandler.getPacketListener() != null) {
            return StartResult.ALREADY_RECORDING;
        }
        ClientConnection connection = mc.getNetworkHandler().getConnection();
        ConfigurationPhaseCapture capture = ConfigurationPhaseCapture.get(connection);
        List<com.replaymod.replaystudio.protocol.Packet> packetsBeforePlay =
                capture == null ? null : capture.copyPacketsBeforePlay();
        if (packetsBeforePlay == null) {
            return StartResult.CONFIGURATION_UNAVAILABLE;
        }

        PacketListener listener;
        try {
            boolean local = connection.isLocal();
            String worldName;
            String serverName = null;
            if (local) {
                worldName = mc.getServer().getSaveProperties().getLevelName();
                serverName = worldName;
            } else {
                ServerInfo serverInfo = mc.getCurrentServerEntry();
                if (serverInfo != null && serverInfo.isRealm()) {
                    worldName = "A Realms Server";
                } else if (serverInfo != null) {
                    worldName = serverInfo.address;
                    if (!I18n.translate("selectServer.defaultName").equals(serverInfo.name)) {
                        serverName = serverInfo.name;
                    }
                } else {
                    worldName = "Unknown Server";
                }
            }
            listener = connectionEventHandler.createRecorder(connection, local, worldName, serverName);
        } catch (Exception e) {
            LOGGER.error("Failed to create instant recording", e);
            packetsBeforePlay.forEach(com.replaymod.replaystudio.protocol.Packet::release);
            return StartResult.FAILED;
        }
        packetsBeforePlay.forEach(listener::save);

        Channel channel = listener.getChannel();
        channel.eventLoop().execute(() -> {
            try {
                connectionEventHandler.attachRecorder(listener);
                // Queued behind every packet decoded before the recorder was attached, so the snapshot sees their
                // effects, and ahead of every packet the recorder will save, so those come after the snapshot.
                mc.getPacketApplyBatcher().scheduleIfPossible(() -> writeSnapshotAndGoLive(
                        core, connectionEventHandler, listener, onStarted));
            } catch (Exception e) {
                LOGGER.error("Failed to attach instant recorder", e);
                core.runLater(() -> abort(listener, onStarted));
            }
        });
        return StartResult.STARTED;
    }

    private static void writeSnapshotAndGoLive(ReplayMod core, ConnectionEventHandler connectionEventHandler,
                                               PacketListener listener, Consumer<PacketListener> onStarted) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (connectionEventHandler.getPacketListener() != listener || mc.world == null || mc.player == null) {
            LOGGER.warn("World went away before the instant recording could start");
            abort(listener, onStarted);
            return;
        }
        long start = System.nanoTime();
        try {
            Encoder encoder = new Encoder(listener);
            PlayStateSnapshot snapshot = new PlayStateSnapshot(mc);
            snapshot.world(encoder);
            snapshot.resourcePacks(listener.getResourcePackRecorder(), encoder);

            connectionEventHandler.activateRecorder(true, false, false);
            RecordingEventHandler recordingEventHandler = connectionEventHandler.getRecordingEventHandler();
            recordingEventHandler.spawnRecordingPlayer();

            snapshot.entities(encoder, core.getSettingsRegistry().get(Setting.RECORD_PLAYER_STATUS));
            LOGGER.info("Instant recording started, initial state written in {} ms ({} packets)",
                    (System.nanoTime() - start) / 1_000_000, encoder.count);
        } catch (Exception e) {
            LOGGER.error("Failed to write the initial state of the instant recording", e);
            abort(listener, onStarted);
            return;
        }
        onStarted.accept(listener);
    }

    private static void abort(PacketListener listener, Consumer<PacketListener> onStarted) {
        listener.finishRecording(false);
        ReplayMod.instance.printWarningToChat("replaymod.chat.recordingfailed");
        onStarted.accept(null);
    }

    /**
     * Encodes snapshot packets with the connection's current (play) codec, like packets saved while recording.
     */
    private static final class Encoder implements Consumer<Packet<?>> {
        private final PacketListener listener;
        private final NetworkState<?> state;
        private int count;

        private Encoder(PacketListener listener) {
            this.listener = listener;
            this.state = ((DecoderHandlerAccessor<?>) listener.getChannel().pipeline().get(DecoderHandler.class))
                    .getState();
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void accept(Packet<?> packet) {
            ByteBuf buf = Unpooled.buffer();
            try {
                ((NetworkState) state).codec().encode(buf, packet);
                listener.save(PacketListener.decodePacket(state.id(), buf));
                count++;
            } catch (Exception e) {
                // A single unencodable packet (e.g. broken modded entity data) must not lose the whole recording
                LOGGER.warn("Skipping snapshot packet {}", packet.getClass().getName(), e);
            } finally {
                buf.release();
            }
        }
    }
}
