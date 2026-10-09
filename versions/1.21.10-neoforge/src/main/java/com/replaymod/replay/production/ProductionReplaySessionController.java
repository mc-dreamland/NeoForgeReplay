package com.replaymod.replay.production;

import com.replaymod.core.ReplayMod;
import com.replaymod.editor.gui.MarkerProcessor;
import com.replaymod.recording.ReplayModRecording;
import com.replaymod.recording.ServerInfoExt;
import com.replaymod.recording.Setting;
import com.replaymod.recording.mixin.ClientLoginNetworkHandlerAccessor;
import com.replaymod.recording.mixin.NetworkManagerAccessor;
import com.replaymod.recording.packet.PacketListener;
import com.replaymod.recording.instant.InstantRecording;
import com.replaymod.recording.packet.RecordingResult;
import com.replaymod.replay.ReplayModReplay;
import com.replaymod.replay.ReplaySessionHooks;
import io.netty.channel.Channel;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
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
    private static final long INTENT_TIMEOUT_MS = 30_000L;
    private static final long TRANSITION_TIMEOUT_MS = 15_000L;
    private static final SystemToast.Type RECORDING_SAVE_TOAST = new SystemToast.Type();
    private static final SystemToast.Type RECORDING_STATE_TOAST = new SystemToast.Type();
    private static final ProductionReplaySessionController INSTANCE = new ProductionReplaySessionController();

    private ReplayMod core;
    private ReplaySessionState state = ReplaySessionState.LIVE_ENGINE_DISABLED;
    private RecordingTransferIntent recordingIntent;
    private ReturnTarget returnTarget;
    private ControlledTransitionToken transitionToken;
    private boolean initialized;
    /** Whether the current recording was started mid-session (see {@link InstantRecording}) instead of at login. */
    private boolean instantRecording;
    /** Start a new instant recording once the server transfer which ended the previous one has completed. */
    private boolean resumeInstantAfterTransfer;

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
        LOGGER.info("Production replay session controller initialized; vanilla recording settings are active");
    }

    public ReplaySessionState getState() {
        return state;
    }

    public boolean isLiveServer() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null
                && mc.getNetworkHandler() != null
                && mc.getCurrentServerEntry() != null
                && ReplayModReplay.instance.getReplayHandler() == null;
    }

    /** In a live world (server or singleplayer), where an instant recording can be started. */
    public boolean isInWorld() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null
                && mc.getNetworkHandler() != null
                && mc.world != null
                && ReplayModReplay.instance.getReplayHandler() == null;
    }

    public boolean isInstantRecording() {
        return instantRecording;
    }

    public boolean isEngineCheckboxChecked() {
        if (state == ReplaySessionState.RECORDING_TRANSFER && recordingIntent != null) {
            return recordingIntent.sourceEngineEnabled;
        }
        return isEngineState(state) && !instantRecording;
    }

    public boolean canToggleEngine() {
        return isLiveServer() && isStableLiveState(state) && !instantRecording;
    }

    public boolean canOpenReplayCenter() {
        return isLiveServer() && isStableLiveState(state);
    }

    public RecordingDecision decideRecording(ClientConnection connection) {
        if (state == ReplaySessionState.RECORDING_TRANSFER && recordingIntent != null) {
            RecordingTransferIntent intent = claimRecordingIntent(connection);
            if (intent == null) {
                return RecordingDecision.defer();
            }
            LOGGER.info("Applying recording transfer intent {} targetState={} connection={}",
                    intent.id, intent.targetState, connectionId(connection));
            return RecordingDecision.forState(intent.targetState);
        }

        if (!core.getSettingsRegistry().get(Setting.RECORD_SERVER)) {
            LOGGER.info("Recorder not installed for connection {} because recordServer is disabled",
                    connectionId(connection));
            return RecordingDecision.forState(RecordingTargetState.DISABLED);
        }

        Object packetListener = connection.getPacketListener();
        ServerInfo serverInfo;
        if (packetListener instanceof ClientLoginNetworkHandlerAccessor loginHandler) {
            serverInfo = loginHandler.getServerInfo();
            if (serverInfo == null) {
                LOGGER.debug("Deferring configured recorder decision until login metadata is available connection={}",
                        connectionId(connection));
                return RecordingDecision.defer();
            }
        } else {
            serverInfo = MinecraftClient.getInstance().getCurrentServerEntry();
        }

        boolean autoStart = core.getSettingsRegistry().get(Setting.AUTO_START_RECORDING);
        if (serverInfo != null) {
            Boolean serverOverride = ServerInfoExt.from(serverInfo).getAutoRecording();
            if (serverOverride != null) {
                autoStart = serverOverride;
            }
        }
        RecordingTargetState target = autoStart
                ? RecordingTargetState.RECORDING : RecordingTargetState.READY;
        LOGGER.info("Configured recorder decision connection={} targetState={} autoStart={}",
                connectionId(connection), target, autoStart);
        return RecordingDecision.forState(target);
    }

    public void onRecordingDecisionApplied(RecordingDecision decision, PacketListener listener) {
        boolean controlledTransfer = state == ReplaySessionState.RECORDING_TRANSFER;
        if (!decision.installRecorder) {
            setState(ReplaySessionState.LIVE_ENGINE_DISABLED, "recorder disabled for connection");
            if (controlledTransfer) {
                showStateToast("replaymod.production.engine_disabled",
                        "replaymod.production.engine_disabled.detail");
            }
            completeManagedReturnIfNeeded();
            return;
        }
        if (listener == null) {
            setState(ReplaySessionState.LIVE_ENGINE_DISABLED, "recorder installation failed");
            warn("replaymod.chat.recordingfailed");
            return;
        }
        setState(switch (decision.targetState) {
            case READY -> ReplaySessionState.LIVE_READY;
            case RECORDING -> ReplaySessionState.LIVE_RECORDING;
            case PAUSED -> ReplaySessionState.LIVE_PAUSED;
            case DISABLED -> ReplaySessionState.LIVE_ENGINE_DISABLED;
        }, "recorder installed with target state " + decision.targetState);
        if (controlledTransfer && decision.targetState == RecordingTargetState.READY) {
            showStateToast("replaymod.production.engine_enabled",
                    "replaymod.production.engine_enabled.detail");
        } else if (controlledTransfer && decision.targetState == RecordingTargetState.RECORDING) {
            showStateToast("replaymod.production.recording_started",
                    "replaymod.production.recording_started.detail");
        }
        completeManagedReturnIfNeeded();
    }

    public void requestSetEngineEnabled(boolean enabled, Screen returnScreen) {
        if (!canToggleEngine() || enabled == isEngineState(state)) {
            return;
        }
        ReturnTarget target = captureCurrentTarget();
        if (target == null) {
            warn("replaymod.production.invalid_server");
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        String prefix = enabled ? "replaymod.production.engine_enable" : "replaymod.production.engine_disable";
        mc.setScreen(new ConfirmScreen(confirmed -> {
            if (!confirmed) {
                if (isLiveServer()) {
                    mc.setScreen(returnScreen);
                }
                return;
            }
            if (!isLiveServer() || !isStableLiveState(state)) {
                LOGGER.warn("Engine toggle confirmation became stale enabled={}", enabled);
                return;
            }
            if (enabled) {
                setServerRecordingSetting(true);
                beginRecordingTransfer(target, RecordingTargetState.READY, false);
            } else {
                showEngineDisableSavingScreen();
                finishAndDisableEngine(target, returnScreen);
            }
        }, Text.translatable(prefix + ".title"), Text.translatable(prefix + ".detail"),
                Text.translatable(prefix + ".accept"), Text.translatable("gui.cancel")));
    }

    public void requestStartRecording(Screen returnScreen) {
        if (state == ReplaySessionState.LIVE_READY && isLiveServer()) {
            addMarker(MarkerProcessor.MARKER_NAME_END_CUT);
            setState(ReplaySessionState.LIVE_RECORDING, "recording started from ready engine");
            showStateToast("replaymod.production.recording_started",
                    "replaymod.production.recording_started.detail");
            return;
        }
        if (state != ReplaySessionState.LIVE_ENGINE_DISABLED || !isInWorld()) {
            return;
        }
        startInstantRecording();
    }

    /**
     * Start/finish shortcut: starts recording (instantly unless the engine is ready) or finishes the current one.
     */
    public void toggleRecording() {
        if (state == ReplaySessionState.LIVE_RECORDING || state == ReplaySessionState.LIVE_PAUSED) {
            requestFinishSegment();
        } else {
            requestStartRecording(null);
        }
    }

    private void startInstantRecording() {
        InstantRecording.StartResult result = InstantRecording.start(core,
                ReplayModRecording.instance.getConnectionEventHandler(), listener -> {
                    if (listener == null) {
                        setState(ReplaySessionState.LIVE_ENGINE_DISABLED, "instant recording failed to start");
                        return;
                    }
                    instantRecording = true;
                    setState(ReplaySessionState.LIVE_RECORDING, "instant recording started");
                    showStateToast("replaymod.production.recording_started",
                            "replaymod.production.recording_started.detail");
                });
        LOGGER.info("Instant recording start requested: {}", result);
        switch (result) {
            case CONFIGURATION_UNAVAILABLE -> warn("replaymod.production.instant_unavailable");
            case FAILED -> warn("replaymod.chat.recordingfailed");
            default -> {
            }
        }
    }

    private void finishInstantRecording() {
        PacketListener listener = currentPacketListener();
        instantRecording = false;
        if (listener == null) {
            setState(ReplaySessionState.LIVE_ENGINE_DISABLED, "instant recorder missing while finishing");
            return;
        }
        setState(ReplaySessionState.FINALIZING_RECORDING, "finishing instant recording");
        listener.finishRecording(false).whenComplete((result, throwable) -> core.runLater(() -> {
            if (state == ReplaySessionState.FINALIZING_RECORDING) {
                setState(ReplaySessionState.LIVE_ENGINE_DISABLED, "instant recording finished");
            }
            if (throwable != null) {
                LOGGER.error("Failed to save instant recording", unwrap(throwable));
                showRecordingSaveFailureToast();
            } else {
                showRecordingSavedToast(result);
            }
        }));
    }

    public void requestRecordingActions(Screen returnScreen) {
        if (state != ReplaySessionState.LIVE_RECORDING && state != ReplaySessionState.LIVE_PAUSED) {
            return;
        }
        MinecraftClient.getInstance().setScreen(new RecordingActionsScreen(returnScreen));
    }

    public void requestPauseRecording() {
        if (state != ReplaySessionState.LIVE_RECORDING || !hasRecorder()) {
            return;
        }
        addMarker(MarkerProcessor.MARKER_NAME_START_CUT);
        setState(ReplaySessionState.LIVE_PAUSED, "recording paused");
        showStateToast("replaymod.production.recording_paused",
                "replaymod.production.recording_paused.detail");
    }

    public void requestResumeRecording() {
        if (state != ReplaySessionState.LIVE_PAUSED || !hasRecorder()) {
            return;
        }
        addMarker(MarkerProcessor.MARKER_NAME_END_CUT);
        setState(ReplaySessionState.LIVE_RECORDING, "recording resumed");
        showStateToast("replaymod.production.recording_resumed",
                "replaymod.production.recording_resumed.detail");
    }

    public void requestFinishSegment() {
        if ((state != ReplaySessionState.LIVE_RECORDING && state != ReplaySessionState.LIVE_PAUSED)
                || !hasRecorder()) {
            return;
        }
        if (instantRecording) {
            // Nothing is kept running after an instant recording, so finishing it ends the recording
            finishInstantRecording();
            return;
        }
        finishCurrentSegment(false);
        showStateToast("replaymod.production.segment_completed",
                "replaymod.production.segment_completed.detail");
    }

    private void finishCurrentSegment(boolean quiet) {
        PacketListener listener = currentPacketListener();
        if (listener == null) {
            setState(ReplaySessionState.LIVE_ENGINE_DISABLED, "recorder missing while finishing segment");
            return;
        }
        int timestamp = (int) listener.getCurrentDuration();
        if (state == ReplaySessionState.LIVE_RECORDING) {
            listener.addMarker(MarkerProcessor.MARKER_NAME_START_CUT, timestamp);
        }
        listener.addMarker(MarkerProcessor.MARKER_NAME_SPLIT, timestamp + 1);
        LOGGER.info("Completed recording segment at {}ms previousState={}", timestamp, state);
        setState(ReplaySessionState.LIVE_READY, "recording segment completed");
        if (!quiet) {
            LOGGER.info("Recorder remains installed and ready after segment completion");
        }
    }

    private void finishAndDisableEngine(ReturnTarget target, Screen returnScreen) {
        if (state == ReplaySessionState.LIVE_RECORDING || state == ReplaySessionState.LIVE_PAUSED) {
            finishCurrentSegment(true);
        }
        PacketListener listener = currentPacketListener();
        if (listener == null) {
            setServerRecordingSetting(false);
            beginRecordingTransfer(target, RecordingTargetState.DISABLED, false);
            return;
        }
        setState(ReplaySessionState.FINALIZING_RECORDING, "disabling recording engine");
        listener.finishRecording(false).whenComplete((result, throwable) -> core.runLater(() -> {
            if (throwable != null) {
                LOGGER.error("Failed to finalize recorder while disabling engine", unwrap(throwable));
                setState(ReplaySessionState.LIVE_ENGINE_DISABLED, "engine finalization failed while disabling");
                showRecordingSaveFailureToast();
                if (isLiveServer()) {
                    MinecraftClient.getInstance().setScreen(returnScreen);
                }
                return;
            }
            showRecordingSavedToast(result);
            setServerRecordingSetting(false);
            beginRecordingTransfer(target, RecordingTargetState.DISABLED, false);
        }));
    }

    private void showEngineDisableSavingScreen() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ConfirmScreen savingScreen = new ConfirmScreen(ignored -> {
        }, Text.translatable("replaymod.production.engine_disable.title"),
                Text.translatable("replaymod.production.engine_disable.saving"),
                Text.translatable("replaymod.production.saving_recording"),
                Text.translatable("gui.cancel"));
        mc.setScreen(savingScreen);
        savingScreen.children().forEach(child -> {
            if (child instanceof ButtonWidget button) {
                button.active = false;
            }
        });
    }

    private void beginRecordingTransfer(ReturnTarget target, RecordingTargetState targetState,
                                        boolean nativeContinuation) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientConnection source = mc.getNetworkHandler() == null
                ? null : mc.getNetworkHandler().getConnection();
        boolean sourceEngineEnabled = isEngineState(state)
                || state == ReplaySessionState.FINALIZING_RECORDING;
        recordingIntent = new RecordingTransferIntent(target, source,
                System.currentTimeMillis() + INTENT_TIMEOUT_MS, targetState,
                nativeContinuation, sourceEngineEnabled);
        LOGGER.info("Created recording transfer intent {} target={} targetState={} sourceConnection={} continuation={}",
                recordingIntent.id, target.address, targetState,
                source == null ? "none" : connectionId(source), nativeContinuation);
        setState(ReplaySessionState.RECORDING_TRANSFER, "recording engine transfer requested");
        try {
            mc.getNetworkHandler().onServerTransfer(new ServerTransferS2CPacket(
                    target.address.getAddress(), target.address.getPort()));
        } catch (Throwable throwable) {
            LOGGER.error("Failed to invoke vanilla self-transfer", throwable);
            RecordingTransferIntent failed = recordingIntent;
            recordingIntent = null;
            setState(sourceEngineEnabled ? ReplaySessionState.LIVE_READY
                    : ReplaySessionState.LIVE_ENGINE_DISABLED, "self-transfer failed");
            showRecordingRetry(failed);
        }
    }

    private RecordingTransferIntent claimRecordingIntent(ClientConnection connection) {
        RecordingTransferIntent intent = recordingIntent;
        if (state != ReplaySessionState.RECORDING_TRANSFER || intent == null) {
            return null;
        }
        if (System.currentTimeMillis() > intent.deadline || intent.sourceConnection == connection) {
            LOGGER.warn("Rejecting recording transfer intent {} for connection {} because it expired or matched source",
                    intent.id, connectionId(connection));
            recordingIntent = null;
            setState(ReplaySessionState.LIVE_ENGINE_DISABLED, "recording transfer intent rejected");
            return null;
        }
        Object packetListener = connection.getPacketListener();
        ServerInfo current = packetListener instanceof ClientLoginNetworkHandlerAccessor loginHandler
                ? loginHandler.getServerInfo() : null;
        if (current == null) {
            return null;
        }
        if (!sameAddress(intent.target.address, ServerAddress.parse(current.address))) {
            if (!intent.nativeTransferContinuation || !intent.targetConnectStarted) {
                LOGGER.warn("Recording transfer intent {} did not match login target {}",
                        intent.id, current.address);
                return null;
            }
            LOGGER.info("Recording transfer intent {} accepted native target {}; login metadata retained source {}",
                    intent.id, intent.target.address, current.address);
        }
        recordingIntent = null;
        LOGGER.info("Consumed recording transfer intent {} connection={} targetState={}",
                intent.id, connectionId(connection), intent.targetState);
        return intent;
    }

    public void prepareRecordingContinuation(String host, int port) {
        if (instantRecording) {
            // The new server's login is not part of this recording; save it and start a new one once joined
            boolean wasRecording = state == ReplaySessionState.LIVE_RECORDING;
            PacketListener listener = currentPacketListener();
            instantRecording = false;
            resumeInstantAfterTransfer = wasRecording;
            setState(ReplaySessionState.LIVE_ENGINE_DISABLED, "instant recording finished for server transfer");
            if (listener != null) {
                listener.finishRecording(false).whenComplete((result, throwable) -> core.runLater(() -> {
                    if (throwable != null) {
                        LOGGER.error("Failed to save instant recording before transfer", unwrap(throwable));
                        showRecordingSaveFailureToast();
                    } else {
                        showRecordingSavedToast(result);
                    }
                }));
            }
            return;
        }
        if (!isEngineState(state)) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        ServerInfo sourceInfo = mc.getCurrentServerEntry();
        if (sourceInfo == null || mc.getNetworkHandler() == null) {
            LOGGER.warn("Cannot continue recorder across native Transfer because source server is unavailable");
            return;
        }
        RecordingTargetState targetState = targetStateFor(state);
        ServerAddress address = new ServerAddress(host, port);
        ServerInfo targetInfo = new ServerInfo(sourceInfo.name, address.toString(), sourceInfo.getServerType());
        targetInfo.copyFrom(sourceInfo);
        targetInfo.address = address.toString();
        ClientConnection source = mc.getNetworkHandler().getConnection();
        recordingIntent = new RecordingTransferIntent(new ReturnTarget(address, targetInfo), source,
                System.currentTimeMillis() + INTENT_TIMEOUT_MS, targetState, true, true);
        LOGGER.info("Created native Transfer continuation intent {} target={} targetState={} sourceConnection={}",
                recordingIntent.id, address, targetState, connectionId(source));
        setState(ReplaySessionState.RECORDING_TRANSFER, "continuing recorder across native transfer");
        PacketListener listener = currentPacketListener();
        if (listener != null) {
            listener.finishRecording(false);
        }
    }

    private void showRecordingSavedToast(RecordingResult result) {
        int outputCount = result == null ? 0 : result.getOutputPaths().size();
        if (outputCount <= 0) {
            return;
        }
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

    private void showStateToast(String title, String detail) {
        SystemToast.show(MinecraftClient.getInstance().getToastManager(), RECORDING_STATE_TOAST,
                Text.translatable(title), Text.translatable(detail));
    }

    @Override
    public boolean prepareReplayLaunch(ReplaySessionHooks.ReplayLaunch launch) {
        if (!isLiveServer() || !isStableLiveState(state)) {
            return false;
        }
        if (returnTarget == null) {
            returnTarget = captureCurrentTarget();
        }
        if (returnTarget == null) {
            warn("replaymod.production.invalid_server");
            return true;
        }

        if (isEngineState(state)) {
            if ((state == ReplaySessionState.LIVE_RECORDING || state == ReplaySessionState.LIVE_PAUSED)
                    && !instantRecording) {
                finishCurrentSegment(true);
            }
            instantRecording = false;
            PacketListener listener = currentPacketListener();
            if (listener == null) {
                setState(ReplaySessionState.LIVE_ENGINE_DISABLED, "missing recorder before replay launch");
                beginReplayTransition(launch);
                return true;
            }
            setState(ReplaySessionState.FINALIZING_RECORDING, "finalizing recorder before replay launch");
            listener.finishRecording(false).whenComplete((result, throwable) -> core.runLater(() -> {
                if (throwable != null) {
                    LOGGER.error("Cannot enter replay because recording finalization failed", unwrap(throwable));
                    returnTarget = null;
                    setState(ReplaySessionState.LIVE_ENGINE_DISABLED, "pre-replay finalization failed");
                    showRecordingSaveFailureToast();
                    return;
                }
                showRecordingSavedToast(result);
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
            mc.disconnectWithProgressScreen();
            launch.run();
            if (ReplayModReplay.instance.getReplayHandler() != null) {
                setState(ReplaySessionState.REPLAY, "replay world opened");
            } else if (isLiveServer()) {
                returnTarget = null;
                setState(ReplaySessionState.LIVE_ENGINE_DISABLED, "replay launch cancelled before disconnect");
            } else {
                beginReturnToServer("replay launch ended without a replay world");
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed to open replay", e);
            if (isLiveServer()) {
                returnTarget = null;
                setState(ReplaySessionState.LIVE_ENGINE_DISABLED, "replay launch failed before disconnect");
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
            setState(ReplaySessionState.LIVE_ENGINE_DISABLED, "return target missing");
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        LOGGER.info("Reconnecting managed replay session to {}", target.address);
        net.minecraft.client.gui.screen.multiplayer.ConnectScreen.connect(
                reconnectScreen(target), mc, target.address, target.serverInfo, false, null);
    }

    public void tick() {
        if (recordingIntent != null && System.currentTimeMillis() > recordingIntent.deadline) {
            RecordingTransferIntent expired = recordingIntent;
            recordingIntent = null;
            LOGGER.warn("Recording transfer intent {} timed out targetState={}", expired.id, expired.targetState);
            setState(ReplaySessionState.LIVE_ENGINE_DISABLED, "recording transfer intent timed out");
            MinecraftClient.getInstance().setScreen(recordingRetryScreen(expired));
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
        if (state == ReplaySessionState.RECORDING_TRANSFER && recordingIntent != null && leavingMultiplayerFlow) {
            LOGGER.info("Cleared recording transfer intent {} after leaving multiplayer flow for {}",
                    recordingIntent.id, requested.getClass().getSimpleName());
            recordingIntent = null;
            finishRecorderOnExit();
            setState(ReplaySessionState.LIVE_ENGINE_DISABLED, "left multiplayer connection flow");
        } else if (isEngineState(state) && leavingMultiplayerFlow) {
            finishRecorderOnExit();
            setState(ReplaySessionState.LIVE_ENGINE_DISABLED, "left multiplayer flow with recorder installed");
        }
        if (leavingMultiplayerFlow) {
            instantRecording = false;
            resumeInstantAfterTransfer = false;
        }
        if (state == ReplaySessionState.RECORDING_TRANSFER && recordingIntent != null
                && requested instanceof net.minecraft.client.gui.screen.multiplayer.ConnectScreen) {
            recordingIntent.targetConnectStarted = true;
            LOGGER.info("Recording transfer intent {} is connecting to target", recordingIntent.id);
        }
        if (state == ReplaySessionState.RECORDING_TRANSFER && requested instanceof DisconnectedScreen
                && recordingIntent != null) {
            if (!recordingIntent.targetConnectStarted) {
                return requested;
            }
            RecordingTransferIntent failed = recordingIntent;
            recordingIntent = null;
            setState(ReplaySessionState.LIVE_ENGINE_DISABLED, "recording transfer target connection failed");
            return recordingRetryScreen(failed);
        }
        if (state == ReplaySessionState.RETURNING && requested instanceof DisconnectedScreen && returnTarget != null) {
            return reconnectScreen(returnTarget);
        }
        return requested;
    }

    private void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (state == ReplaySessionState.RETURNING) {
            completeManagedReturnIfNeeded();
            setState(ReplaySessionState.LIVE_ENGINE_DISABLED, "server rejoin completed without recorder");
        }
        if (resumeInstantAfterTransfer) {
            resumeInstantAfterTransfer = false;
            core.runLater(() -> {
                if (state == ReplaySessionState.LIVE_ENGINE_DISABLED) {
                    startInstantRecording();
                }
            });
        }
    }

    private void completeManagedReturnIfNeeded() {
        if (returnTarget != null) {
            transitionToken = null;
            returnTarget = null;
            LOGGER.info("Managed replay return completed with state {}", state);
        }
    }

    private void finishRecorderOnExit() {
        PacketListener listener = currentPacketListener();
        if (listener != null) {
            listener.finishRecording(false);
        }
    }

    private PacketListener currentPacketListener() {
        return ReplayModRecording.instance.getConnectionEventHandler().getPacketListener();
    }

    private boolean hasRecorder() {
        return currentPacketListener() != null;
    }

    private void addMarker(String markerName) {
        PacketListener listener = currentPacketListener();
        if (listener != null) {
            listener.addMarker(markerName);
            LOGGER.info("Added recording control marker {} state={}", markerName, state);
        }
    }

    private void setServerRecordingSetting(boolean enabled) {
        core.getSettingsRegistry().set(Setting.RECORD_SERVER, enabled);
        core.getSettingsRegistry().save();
        LOGGER.info("Persisted recordServer={}", enabled);
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

    private void showRecordingRetry(RecordingTransferIntent failed) {
        MinecraftClient.getInstance().setScreen(recordingRetryScreen(failed));
    }

    private Screen recordingRetryScreen(RecordingTransferIntent failed) {
        return new ConfirmScreen(retry -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (retry) {
                recordingIntent = failed.retry();
                LOGGER.info("Created retry recording transfer intent {} target={} targetState={}",
                        recordingIntent.id, failed.target.address, failed.targetState);
                setState(ReplaySessionState.RECORDING_TRANSFER, "retrying recording engine connection");
                net.minecraft.client.gui.screen.multiplayer.ConnectScreen.connect(
                        reconnectScreen(failed.target), mc, failed.target.address, failed.target.serverInfo,
                        false, null);
            } else {
                recordingIntent = null;
                setServerRecordingSetting(false);
                setState(ReplaySessionState.RECORDING_TRANSFER, "reconnecting with recorder disabled");
                RecordingTransferIntent disabled = new RecordingTransferIntent(failed.target, null,
                        System.currentTimeMillis() + INTENT_TIMEOUT_MS, RecordingTargetState.DISABLED,
                        false, false);
                disabled.targetConnectStarted = true;
                recordingIntent = disabled;
                net.minecraft.client.gui.screen.multiplayer.ConnectScreen.connect(
                        reconnectScreen(failed.target), mc, failed.target.address, failed.target.serverInfo,
                        false, null);
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

    private static boolean isEngineState(ReplaySessionState value) {
        return value == ReplaySessionState.LIVE_READY
                || value == ReplaySessionState.LIVE_RECORDING
                || value == ReplaySessionState.LIVE_PAUSED;
    }

    private static boolean isStableLiveState(ReplaySessionState value) {
        return value == ReplaySessionState.LIVE_ENGINE_DISABLED || isEngineState(value);
    }

    private static RecordingTargetState targetStateFor(ReplaySessionState value) {
        return switch (value) {
            case LIVE_READY -> RecordingTargetState.READY;
            case LIVE_RECORDING -> RecordingTargetState.RECORDING;
            case LIVE_PAUSED -> RecordingTargetState.PAUSED;
            default -> RecordingTargetState.DISABLED;
        };
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

    public enum RecordingTargetState {
        DISABLED,
        READY,
        RECORDING,
        PAUSED
    }

    public static final class RecordingDecision {
        private final RecordingTargetState targetState;
        private final boolean installRecorder;
        private final boolean autoStart;
        private final boolean defer;

        private RecordingDecision(RecordingTargetState targetState, boolean defer) {
            this.targetState = targetState;
            this.installRecorder = targetState != RecordingTargetState.DISABLED;
            this.autoStart = targetState == RecordingTargetState.RECORDING;
            this.defer = defer;
        }

        public static RecordingDecision forState(RecordingTargetState targetState) {
            return new RecordingDecision(targetState, false);
        }

        public static RecordingDecision defer() {
            return new RecordingDecision(RecordingTargetState.DISABLED, true);
        }

        public boolean shouldInstallRecorder() {
            return installRecorder;
        }

        public boolean shouldAutoStart() {
            return autoStart;
        }

        public boolean shouldDefer() {
            return defer;
        }
    }

    private static final class RecordingTransferIntent {
        private final UUID id = UUID.randomUUID();
        private final ReturnTarget target;
        private final ClientConnection sourceConnection;
        private final long deadline;
        private final RecordingTargetState targetState;
        private final boolean nativeTransferContinuation;
        private final boolean sourceEngineEnabled;
        private boolean targetConnectStarted;

        private RecordingTransferIntent(ReturnTarget target, ClientConnection sourceConnection, long deadline,
                                        RecordingTargetState targetState, boolean nativeTransferContinuation,
                                        boolean sourceEngineEnabled) {
            this.target = target;
            this.sourceConnection = sourceConnection;
            this.deadline = deadline;
            this.targetState = targetState;
            this.nativeTransferContinuation = nativeTransferContinuation;
            this.sourceEngineEnabled = sourceEngineEnabled;
            this.targetConnectStarted = sourceConnection == null;
        }

        private RecordingTransferIntent retry() {
            return new RecordingTransferIntent(target, null, System.currentTimeMillis() + INTENT_TIMEOUT_MS,
                    targetState, nativeTransferContinuation, false);
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

    private final class RecordingActionsScreen extends Screen {
        private final Screen parent;

        private RecordingActionsScreen(Screen parent) {
            super(Text.translatable("replaymod.production.recording_actions.title"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int centerX = width / 2;
            boolean paused = state == ReplaySessionState.LIVE_PAUSED;
            addDrawableChild(ButtonWidget.builder(
                    Text.translatable(paused
                            ? "replaymod.production.resume_recording"
                            : "replaymod.production.pause_recording"), button -> {
                        MinecraftClient.getInstance().setScreen(parent);
                        if (paused) {
                            requestResumeRecording();
                        } else {
                            requestPauseRecording();
                        }
                    }).dimensions(centerX - 102, height / 2 - 10, 100, 20).build());
            addDrawableChild(ButtonWidget.builder(
                    Text.translatable(instantRecording
                            ? "replaymod.production.finish_recording"
                            : "replaymod.production.finish_segment"), button -> {
                        MinecraftClient.getInstance().setScreen(parent);
                        requestFinishSegment();
                    }).dimensions(centerX + 2, height / 2 - 10, 100, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), button -> close())
                    .dimensions(centerX - 100, height / 2 + 18, 200, 20).build());
        }

        @Override
        public void close() {
            MinecraftClient.getInstance().setScreen(parent);
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            // The background is drawn by super.render, and text colors need an opaque alpha channel since 1.21.6
            super.render(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 42, 0xffffffff);
        }
    }
}
