package com.replaymod.recording;

import com.replaymod.core.KeyBindingRegistry;
import com.replaymod.core.Module;
import com.replaymod.core.ReplayMod;
import com.replaymod.core.utils.Restrictions;
import com.replaymod.core.versions.MCVer.Keyboard;
import com.replaymod.recording.handler.ConnectionEventHandler;
import com.replaymod.recording.handler.GuiHandler;
import com.replaymod.recording.mixin.NetworkManagerAccessor;
import com.replaymod.recording.packet.PacketListener;
import com.replaymod.recording.instant.ConfigurationPhaseCapture;
import com.replaymod.replay.ReplayHandler;
import com.replaymod.replay.production.ProductionReplaySessionController;
import com.replaymod.replay.production.ProductionReplaySessionController.RecordingDecision;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import net.minecraft.network.ClientConnection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ReplayModRecording implements Module {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final AttributeKey<Void> ATTR_CHECKED = AttributeKey.newInstance("ReplayModRecording_checked");

    { instance = this; }
    public static ReplayModRecording instance;

    private ReplayMod core;

    private ConnectionEventHandler connectionEventHandler;

    public ReplayModRecording(ReplayMod mod) {
        core = mod;

        core.getSettingsRegistry().register(Setting.class);
    }

    @Override
    public void registerKeyBindings(KeyBindingRegistry registry) {
        registry.registerKeyBinding("replaymod.input.marker", Keyboard.KEY_M, new Runnable() {
            @Override
            public void run() {
                PacketListener packetListener = connectionEventHandler.getPacketListener();
                if (packetListener != null) {
                    packetListener.addMarker(null);
                    core.printInfoToChat("replaymod.chat.addedmarker");
                }
            }
        }, false);
        // Unbound by default; starts an instant recording or finishes the current one
        registry.registerKeyBinding("replaymod.input.togglerecording", 0,
                () -> ProductionReplaySessionController.getInstance().toggleRecording(), false);
    }

    @Override
    public void initClient() {
        connectionEventHandler = new ConnectionEventHandler(LOGGER, core);
        ProductionReplaySessionController.getInstance().initialize(core);

        new GuiHandler(core).register();
    }

    public void initiateRecording(ClientConnection networkManager) {
        Channel channel = ((NetworkManagerAccessor) networkManager).getChannel();
        if (channel.pipeline().get(ReplayHandler.PACKET_HANDLER_NAME) != null) return;
        // Keeps login/configuration packets until the play phase starts, so recordings can begin at any time later
        ConfigurationPhaseCapture.beginLogin(networkManager);
        if (channel.hasAttr(ATTR_CHECKED)) return;
        ProductionReplaySessionController sessionController = ProductionReplaySessionController.getInstance();
        RecordingDecision decision = sessionController.decideRecording(networkManager);
        if (decision.shouldDefer()) {
            return;
        }
        channel.attr(ATTR_CHECKED).set(null);
        if (!decision.shouldInstallRecorder()) {
            sessionController.onRecordingDecisionApplied(decision, null);
            return;
        }
        PacketListener previousListener = connectionEventHandler.getPacketListener();
        if (previousListener != null) {
            LOGGER.info("Finalizing previous recorder before installing its native Transfer successor");
            previousListener.finishRecording(false);
            connectionEventHandler.reset();
        }
        PacketListener listener = connectionEventHandler.onConnectedToServerEvent(
                networkManager, decision.shouldAutoStart(), false, false);
        sessionController.onRecordingDecisionApplied(decision, listener);
    }

    public ConnectionEventHandler getConnectionEventHandler() {
        return connectionEventHandler;
    }
}
