package com.replaymod.replay.production;

import com.replaymod.core.ReplayMod;
import com.replaymod.recording.ReplayModRecording;
import com.replaymod.recording.mixin.ClientLoginNetworkHandlerAccessor;
import com.replaymod.recording.mixin.NetworkManagerAccessor;
import com.replaymod.recording.packet.PacketListener;
import com.replaymod.recording.packet.RecordingResult;
import com.replaymod.replay.ReplayModReplay;
import com.replaymod.replay.ReplaySessionHooks;
import io.netty.channel.Channel;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.s2c.common.ServerTransferS2CPacket;
import net.minecraft.text.Text;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletionException;

public final class ProductionReplaySessionController implements ReplaySessionHooks.Handler {
    private static final Logger LOGGER = LogManager.getLogger("ReplayMod/ProductionSession");
    private static final long ARM_TIMEOUT_MS = 30_000L;
    private static final long TRANSITION_TIMEOUT_MS = 15_000L;
    private static final SystemToast.Type RECORDING_SAVE_TOAST = new SystemToast.Type();
    private static final ProductionReplaySessionController INSTANCE = new ProductionReplaySessionController();

    private ReplayMod core;
    private ReplaySessionState state = ReplaySessionState.LIVE_UNARMED;
    private RecordingArm recordingArm;
    private ReturnTarget returnTarget;
    private ControlledTransitionToken transitionToken;
    private boolean initialized;

    public static ProductionReplaySessionController getInstance() {
        return INSTANCE;
    }

    public void initialize(ReplayMod core) {
        if (initialized) {
            return;
        }
        initialized = true;
        this.core = core;
        ReplaySessionHooks.install(this);
        NeoForge.EVENT_BUS.addListener(this::onLoggingIn);
        LOGGER.info("Production replay session controller initialized; on-demand recording is enabled");
    }

    public ReplaySessionState getState() {
        return state;
    }

    public boolean isLiveServer() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) {
            return false;
        }
        return mc.getNetworkHandler() != null
                && mc.getCurrentServerEntry() != null
                && ReplayModReplay.instance.getReplayHandler() == null;
    }

    public boolean canOpenReplayCenter() {
        return isLiveServer() && (state == ReplaySessionState.LIVE_UNARMED
                || state == ReplaySessionState.LIVE_RECORDING);
    }

    public void requestStartRecording(Screen returnScreen) {
        if (state != ReplaySessionState.LIVE_UNARMED || !isLiveServer()) {
            return;
        }
        if (captureCurrentTarget() == null) {
            warn("replaymod.production.invalid_server");
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        mc.setScreen(new ConfirmScreen(confirmed -> {
            if (!confirmed) {
                if (state == ReplaySessionState.LIVE_UNARMED && isLiveServer()) {
                    mc.setScreen(returnScreen);
                }
                return;
            }
            if (state != ReplaySessionState.LIVE_UNARMED || !isLiveServer()) {
                LOGGER.warn("Recording confirmation became stale before it was accepted");
                return;
            }
            ReturnTarget confirmedTarget = captureCurrentTarget();
            if (confirmedTarget == null) {
                warn("replaymod.production.invalid_server");
                mc.setScreen(returnScreen);
                return;
            }
            beginStartRecording(confirmedTarget);
        }, Text.translatable("replaymod.production.start_recording.confirm.title"),
                Text.translatable("replaymod.production.start_recording.confirm.detail"),
                Text.translatable("replaymod.production.start_recording.confirm.accept"),
                Text.translatable("gui.cancel")));
    }

    private void beginStartRecording(ReturnTarget target) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientConnection source = mc.getNetworkHandler().getConnection();
        recordingArm = new RecordingArm(target, source, System.currentTimeMillis() + ARM_TIMEOUT_MS, false);
        LOGGER.info("Created recording arm {} target={} sourceConnection={}", recordingArm.id,
                target.address, connectionId(source));
        setState(ReplaySessionState.ARMING_TRANSFER, "recording requested");
        try {
            mc.getNetworkHandler().onServerTransfer(new ServerTransferS2CPacket(
                    target.address.getAddress(), target.address.getPort()));
        } catch (Throwable throwable) {
            LOGGER.error("Failed to invoke vanilla self-transfer", throwable);
            recordingArm = null;
            setState(ReplaySessionState.LIVE_UNARMED, "self-transfer failed");
            showRecordingRetry(target);
        }
    }

    public boolean claimRecordingArm(ClientConnection connection) {
        RecordingArm arm = recordingArm;
        if (state != ReplaySessionState.ARMING_TRANSFER || arm == null) {
            return false;
        }
        if (System.currentTimeMillis() > arm.deadline || arm.sourceConnection == connection) {
            LOGGER.warn("Rejecting recording arm {} for connection {} because it expired or matched its source",
                    arm.id, connectionId(connection));
            recordingArm = null;
            setState(ReplaySessionState.LIVE_UNARMED, "recording arm expired or matched source connection");
            return false;
        }
        Object packetListener = connection.getPacketListener();
        ServerInfo current = packetListener instanceof ClientLoginNetworkHandlerAccessor loginHandler
                ? loginHandler.getServerInfo()
                : null;
        if (current == null) {
            LOGGER.warn("Recording arm {} did not match login target {} listener={}", arm.id,
                    "none",
                    packetListener == null ? "none" : packetListener.getClass().getName());
            return false;
        }
        if (!sameAddress(arm.target.address, ServerAddress.parse(current.address))) {
            if (!arm.nativeTransferContinuation || !arm.targetConnectStarted) {
                LOGGER.warn("Recording arm {} did not match login target {} listener={}", arm.id,
                        current.address, packetListener.getClass().getName());
                return false;
            }
            // Vanilla carries the source ServerInfo through Transfer while connecting to
            // the packet-provided target address. The controlled ConnectScreen phase binds
            // this one-shot arm to that next login connection.
            LOGGER.info("Recording arm {} accepted native Transfer target {}; login metadata retained source {}",
                    arm.id, arm.target.address, current.address);
        }
        recordingArm = null;
        LOGGER.info("Consumed recording arm {} for connection {} continuation={}",
                arm.id, connectionId(connection), arm.nativeTransferContinuation);
        return true;
    }

    public void prepareRecordingContinuation(String host, int port) {
        if (state != ReplaySessionState.LIVE_RECORDING) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        ServerInfo sourceInfo = mc.getCurrentServerEntry();
        if (sourceInfo == null || mc.getNetworkHandler() == null) {
            LOGGER.warn("Cannot continue recording across native Transfer because the source server is unavailable");
            return;
        }

        ServerAddress address = new ServerAddress(host, port);
        ServerInfo targetInfo = new ServerInfo(sourceInfo.name, address.toString(), sourceInfo.getServerType());
        targetInfo.copyFrom(sourceInfo);
        targetInfo.address = address.toString();
        ClientConnection source = mc.getNetworkHandler().getConnection();
        recordingArm = new RecordingArm(
                new ReturnTarget(address, targetInfo), source,
                System.currentTimeMillis() + ARM_TIMEOUT_MS, true);
        LOGGER.info("Created native Transfer continuation arm {} target={} sourceConnection={}",
                recordingArm.id, address, connectionId(source));
        setState(ReplaySessionState.ARMING_TRANSFER, "continuing recording across native transfer");
        PacketListener listener = ReplayModRecording.instance.getConnectionEventHandler().getPacketListener();
        if (listener != null) {
            LOGGER.info("Finalizing current recording before native Transfer arm {} connects", recordingArm.id);
            listener.finishRecording(false);
        }
    }

    public void onRecorderInstalled(PacketListener listener) {
        if (listener == null) {
            setState(ReplaySessionState.LIVE_UNARMED, "recorder installation failed");
            warn("replaymod.chat.recordingfailed");
            return;
        }
        setState(ReplaySessionState.LIVE_RECORDING, "recorder installed");
    }

    public void requestStopRecording() {
        if (state != ReplaySessionState.LIVE_RECORDING) {
            return;
        }
        PacketListener listener = ReplayModRecording.instance.getConnectionEventHandler().getPacketListener();
        if (listener == null) {
            setState(ReplaySessionState.LIVE_UNARMED, "recorder was already absent");
            showRecordingSaveFailureToast();
            return;
        }
        setState(ReplaySessionState.FINALIZING_RECORDING, "manual stop");
        listener.finishRecording(false).whenComplete((result, throwable) -> core.runLater(() -> {
            if (throwable != null) {
                LOGGER.error("Failed to finalize recording", unwrap(throwable));
                setState(ReplaySessionState.LIVE_UNARMED, "manual recording finalization failed");
                showRecordingSaveFailureToast();
            } else {
                setState(ReplaySessionState.LIVE_UNARMED, "manual recording finalization complete");
                showRecordingSavedToast(result);
            }
        }));
    }

    private void showRecordingSavedToast(RecordingResult result) {
        int outputCount = result == null ? 0 : result.getOutputPaths().size();
        Text detail;
        if (outputCount == 1) {
            String fileName = result.getOutputPaths().get(0).getKey().getFileName().toString();
            detail = Text.translatable("replaymod.production.recording_saved.file", fileName);
        } else {
            detail = Text.translatable("replaymod.production.recording_saved.multiple", outputCount);
        }
        SystemToast.show(MinecraftClient.getInstance().getToastManager(), RECORDING_SAVE_TOAST,
                Text.translatable("replaymod.production.recording_saved"), detail);
    }

    private void showRecordingSaveFailureToast() {
        SystemToast.show(MinecraftClient.getInstance().getToastManager(), RECORDING_SAVE_TOAST,
                Text.translatable("replaymod.production.save_failed"),
                Text.translatable("replaymod.production.save_failed.detail"));
    }

    @Override
    public boolean prepareReplayLaunch(ReplaySessionHooks.ReplayLaunch launch) {
        if (!isLiveServer() || (state != ReplaySessionState.LIVE_UNARMED
                && state != ReplaySessionState.LIVE_RECORDING)) {
            return false;
        }
        if (returnTarget == null) {
            returnTarget = captureCurrentTarget();
        }
        if (returnTarget == null) {
            warn("replaymod.production.invalid_server");
            return true;
        }

        if (state == ReplaySessionState.LIVE_RECORDING) {
            PacketListener listener = ReplayModRecording.instance.getConnectionEventHandler().getPacketListener();
            if (listener == null) {
                setState(ReplaySessionState.LIVE_UNARMED, "missing recorder before replay launch");
                beginReplayTransition(launch);
                return true;
            }
            setState(ReplaySessionState.FINALIZING_RECORDING, "finalizing before replay launch");
            listener.finishRecording(false).whenComplete((result, throwable) -> core.runLater(() -> {
                if (throwable != null) {
                    LOGGER.error("Cannot enter replay because recording finalization failed", unwrap(throwable));
                    returnTarget = null;
                    setState(ReplaySessionState.LIVE_UNARMED, "pre-replay finalization failed");
                    warn("replaymod.production.save_failed");
                    return;
                }
                beginReplayTransition(launch);
            }));
        } else {
            beginReplayTransition(launch);
        }
        return true;
    }

    private void beginReplayTransition(ReplaySessionHooks.ReplayLaunch launch) {
        MinecraftClient mc = MinecraftClient.getInstance();
        setState(ReplaySessionState.REPLAY_TRANSITION, "opening replay world");
        ControlledTransitionToken token = beginControlledTransition(TransitionDirection.TO_REPLAY);
        mc.setScreen(transitionScreen());
        try {
            // ReplayMod normally starts from the title screen, so its handler does not
            // close a live server connection before installing the virtual replay one.
            mc.disconnectWithProgressScreen();
            launch.run();
            if (ReplayModReplay.instance.getReplayHandler() != null) {
                setState(ReplaySessionState.REPLAY, "replay world opened");
            } else if (isLiveServer()) {
                returnTarget = null;
                setState(ReplaySessionState.LIVE_UNARMED, "replay launch cancelled before disconnect");
            } else {
                beginReturnToServer("replay launch ended without a replay world");
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed to open replay", e);
            if (isLiveServer()) {
                returnTarget = null;
                setState(ReplaySessionState.LIVE_UNARMED, "replay launch failed before disconnect");
                warn("replaymod.production.replay_failed");
            } else {
                beginReturnToServer("replay launch failed");
            }
        } finally {
            clearControlledTransition(token);
        }
    }

    public void requestExitReplay() {
        if (state != ReplaySessionState.REPLAY || ReplayModReplay.instance.getReplayHandler() == null) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        setState(ReplaySessionState.RETURNING, "explicit replay exit");
        ControlledTransitionToken token = beginControlledTransition(TransitionDirection.FROM_REPLAY);
        mc.setScreen(transitionScreen());
        try {
            ReplayModReplay.instance.getReplayHandler().endReplay();
        } catch (IOException e) {
            LOGGER.error("Failed to close replay cleanly", e);
            ReplayModReplay.instance.forcefullyStopReplay();
            if (mc.world != null) {
                mc.disconnectWithProgressScreen();
            }
            connectToReturnTarget();
        } finally {
            clearControlledTransition(token);
        }
    }

    @Override
    public boolean isManagedReplaySession() {
        return returnTarget != null && (state == ReplaySessionState.REPLAY_TRANSITION
                || state == ReplaySessionState.REPLAY || state == ReplaySessionState.RETURNING);
    }

    @Override
    public void onReplayClosed() {
        if (state == ReplaySessionState.RETURNING) {
            transitionToken = null;
            connectToReturnTarget();
        }
    }

    @Override
    public boolean completeReplaySession() {
        if (returnTarget == null || (state != ReplaySessionState.REPLAY
                && state != ReplaySessionState.REPLAY_TRANSITION)) {
            return false;
        }
        beginReturnToServer("managed replay task completed");
        return true;
    }

    private void beginReturnToServer(String reason) {
        MinecraftClient mc = MinecraftClient.getInstance();
        setState(ReplaySessionState.RETURNING, reason);
        ControlledTransitionToken token = beginControlledTransition(TransitionDirection.FROM_REPLAY);
        mc.setScreen(transitionScreen());
        if (ReplayModReplay.instance.getReplayHandler() != null) {
            try {
                ReplayModReplay.instance.getReplayHandler().endReplay();
                return;
            } catch (IOException e) {
                LOGGER.error("Failed to close replay before reconnect", e);
                ReplayModReplay.instance.forcefullyStopReplay();
                if (mc.world != null) {
                    mc.disconnectWithProgressScreen();
                }
            } finally {
                clearControlledTransition(token);
            }
        } else {
            clearControlledTransition(token);
        }
        connectToReturnTarget();
    }

    private void connectToReturnTarget() {
        ReturnTarget target = returnTarget;
        if (target == null) {
            setState(ReplaySessionState.LIVE_UNARMED, "return target missing");
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        LOGGER.info("Reconnecting managed replay session to {}", target.address);
        net.minecraft.client.gui.screen.multiplayer.ConnectScreen.connect(
                reconnectScreen(target), mc, target.address, target.serverInfo, false, null);
    }

    public void tick() {
        if (recordingArm != null && System.currentTimeMillis() > recordingArm.deadline) {
            UUID armId = recordingArm.id;
            ReturnTarget target = recordingArm.target;
            boolean continuation = recordingArm.nativeTransferContinuation;
            recordingArm = null;
            LOGGER.warn("Recording arm {} timed out before recorder installation", armId);
            setState(ReplaySessionState.LIVE_UNARMED, "recording arm timed out");
            MinecraftClient.getInstance().setScreen(recordingRetryScreen(target, continuation));
        }
        if (transitionToken != null && System.currentTimeMillis() > transitionToken.deadline) {
            LOGGER.warn("Controlled replay transition {} expired", transitionToken.id);
            transitionToken = null;
        }
    }

    public boolean shouldSuppressLogoutEvent() {
        ControlledTransitionToken token = transitionToken;
        if (!isControlledTransitionActive(token)) {
            return false;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientConnection currentConnection = mc.getNetworkHandler() == null
                ? null : mc.getNetworkHandler().getConnection();
        return token.expectedConnection == currentConnection;
    }

    public Screen replaceTransitionScreen(Screen requested) {
        return isControlledTransitionActive(transitionToken) ? transitionScreen() : requested;
    }

    public Screen filterOpeningScreen(Screen requested) {
        if (isControlledTransitionActive(transitionToken) && (requested instanceof TitleScreen
                || requested instanceof MultiplayerScreen || requested instanceof DisconnectedScreen)) {
            return transitionScreen();
        }
        boolean leavingMultiplayerFlow = requested instanceof TitleScreen || requested instanceof MultiplayerScreen;
        if (state == ReplaySessionState.ARMING_TRANSFER && recordingArm != null && leavingMultiplayerFlow) {
            LOGGER.info("Cancelled recording arm {} after leaving the multiplayer connection flow for {}",
                    recordingArm.id, requested.getClass().getSimpleName());
            recordingArm = null;
            setState(ReplaySessionState.LIVE_UNARMED, "left multiplayer connection flow");
        } else if (state == ReplaySessionState.LIVE_RECORDING && leavingMultiplayerFlow) {
            PacketListener listener = ReplayModRecording.instance.getConnectionEventHandler().getPacketListener();
            if (listener != null) {
                listener.finishRecording(false);
            }
            setState(ReplaySessionState.LIVE_UNARMED, "left multiplayer connection flow while recording");
        }
        if (state == ReplaySessionState.ARMING_TRANSFER && recordingArm != null
                && requested instanceof net.minecraft.client.gui.screen.multiplayer.ConnectScreen) {
            recordingArm.targetConnectStarted = true;
            LOGGER.info("Recording arm {} is connecting to its target server", recordingArm.id);
        }
        if (state == ReplaySessionState.ARMING_TRANSFER && requested instanceof DisconnectedScreen
                && recordingArm != null) {
            if (!recordingArm.targetConnectStarted) {
                LOGGER.info("Keeping recording arm {} across expected source transfer disconnect connection={}",
                        recordingArm.id,
                        recordingArm.sourceConnection == null
                                ? "none" : connectionId(recordingArm.sourceConnection));
                return requested;
            }
            ReturnTarget target = recordingArm.target;
            boolean continuation = recordingArm.nativeTransferContinuation;
            LOGGER.warn("Recording arm {} target connection failed", recordingArm.id);
            recordingArm = null;
            setState(ReplaySessionState.LIVE_UNARMED, "recording transfer connection failed");
            return recordingRetryScreen(target, continuation);
        }
        if (state == ReplaySessionState.RETURNING && requested instanceof DisconnectedScreen && returnTarget != null) {
            return reconnectScreen(returnTarget);
        }
        return requested;
    }

    private void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (state == ReplaySessionState.RETURNING) {
            transitionToken = null;
            returnTarget = null;
            setState(ReplaySessionState.LIVE_UNARMED, "server rejoin completed");
        }
    }

    private ReturnTarget captureCurrentTarget() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ServerInfo info = mc.getCurrentServerEntry();
        if (info == null || !ServerAddress.isValid(info.address)) {
            return null;
        }
        ServerInfo copy = new ServerInfo(info.name, info.address, info.getServerType());
        copy.copyFrom(info);
        return new ReturnTarget(ServerAddress.parse(info.address), copy);
    }

    private void showRecordingRetry(ReturnTarget target) {
        MinecraftClient.getInstance().setScreen(recordingRetryScreen(target, false));
    }

    private Screen recordingRetryScreen(ReturnTarget target, boolean continuation) {
        return new ConfirmScreen(retry -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (retry) {
                recordingArm = new RecordingArm(
                        target, null, System.currentTimeMillis() + ARM_TIMEOUT_MS, continuation);
                LOGGER.info("Created retry recording arm {} target={}", recordingArm.id, target.address);
                setState(ReplaySessionState.ARMING_TRANSFER, "retrying recording connection");
                net.minecraft.client.gui.screen.multiplayer.ConnectScreen.connect(
                        reconnectScreen(target), mc, target.address, target.serverInfo, false, null);
            } else {
                recordingArm = null;
                setState(ReplaySessionState.LIVE_UNARMED, "reconnecting without recording");
                net.minecraft.client.gui.screen.multiplayer.ConnectScreen.connect(
                        reconnectScreen(target), mc, target.address, target.serverInfo, false, null);
            }
        }, Text.translatable("replaymod.production.transfer_failed"),
                Text.translatable("replaymod.production.transfer_failed.detail"),
                Text.translatable("replaymod.production.retry_recording"),
                Text.translatable("replaymod.production.reconnect_without_recording"));
    }

    private Screen reconnectScreen(ReturnTarget target) {
        return new ConfirmScreen(retry -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (retry) {
                setState(ReplaySessionState.RETURNING, "retrying server rejoin");
                net.minecraft.client.gui.screen.multiplayer.ConnectScreen.connect(
                        reconnectScreen(target), mc, target.address, target.serverInfo, false, null);
            } else {
                mc.stop();
            }
        }, Text.translatable("replaymod.production.reconnect_failed"),
                Text.translatable("replaymod.production.reconnect_failed.detail"),
                Text.translatable("replaymod.production.retry"),
                Text.translatable("replaymod.production.exit_game"));
    }

    private Screen transitionScreen() {
        return new MessageScreen(Text.translatable("replaymod.production.transition"));
    }

    private void warn(String translationKey) {
        if (core != null) {
            core.printWarningToChat(translationKey);
        }
    }

    private void setState(ReplaySessionState newState, String reason) {
        ReplaySessionState oldState = state;
        state = newState;
        LOGGER.info("Replay session state {} -> {} ({})", oldState, newState, reason);
    }

    private ControlledTransitionToken beginControlledTransition(TransitionDirection direction) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientConnection connection = mc.getNetworkHandler() == null
                ? null : mc.getNetworkHandler().getConnection();
        ControlledTransitionToken token = new ControlledTransitionToken(
                direction, connection, System.currentTimeMillis() + TRANSITION_TIMEOUT_MS);
        transitionToken = token;
        LOGGER.info("Created controlled transition {} direction={} connection={}",
                token.id, direction, connection == null ? "none" : connectionId(connection));
        return token;
    }

    private void clearControlledTransition(ControlledTransitionToken token) {
        if (transitionToken == token) {
            transitionToken = null;
            LOGGER.info("Cleared controlled transition {}", token.id);
        }
    }

    private boolean isControlledTransitionActive(ControlledTransitionToken token) {
        if (token == null || System.currentTimeMillis() > token.deadline) {
            return false;
        }
        return token.direction == TransitionDirection.TO_REPLAY
                ? state == ReplaySessionState.REPLAY_TRANSITION
                : state == ReplaySessionState.RETURNING;
    }

    private static boolean sameAddress(ServerAddress first, ServerAddress second) {
        return first.getAddress().equalsIgnoreCase(second.getAddress()) && first.getPort() == second.getPort();
    }

    private static String connectionId(ClientConnection connection) {
        Channel channel = ((NetworkManagerAccessor) connection).getChannel();
        return channel == null ? "unbound" : channel.id().asShortText();
    }

    private static Throwable unwrap(Throwable throwable) {
        return throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause() : throwable;
    }

    private static final class RecordingArm {
        private final UUID id = UUID.randomUUID();
        private final ReturnTarget target;
        private final ClientConnection sourceConnection;
        private final long deadline;
        private final boolean nativeTransferContinuation;
        private boolean targetConnectStarted;

        private RecordingArm(ReturnTarget target, ClientConnection sourceConnection, long deadline,
                             boolean nativeTransferContinuation) {
            this.target = target;
            this.sourceConnection = sourceConnection;
            this.deadline = deadline;
            this.nativeTransferContinuation = nativeTransferContinuation;
            this.targetConnectStarted = sourceConnection == null;
        }
    }

    private static final class ReturnTarget {
        private final ServerAddress address;
        private final ServerInfo serverInfo;

        private ReturnTarget(ServerAddress address, ServerInfo serverInfo) {
            this.address = address;
            this.serverInfo = serverInfo;
        }
    }

    private static final class ControlledTransitionToken {
        private final UUID id = UUID.randomUUID();
        private final TransitionDirection direction;
        private final ClientConnection expectedConnection;
        private final long deadline;

        private ControlledTransitionToken(TransitionDirection direction, ClientConnection expectedConnection,
                                          long deadline) {
            this.direction = direction;
            this.expectedConnection = expectedConnection;
            this.deadline = deadline;
        }
    }

    private enum TransitionDirection {
        TO_REPLAY,
        FROM_REPLAY
    }
}
