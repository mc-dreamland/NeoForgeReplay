package com.replaymod.replay.production;

public enum ReplaySessionState {
    LIVE_ENGINE_DISABLED,
    LIVE_READY,
    LIVE_RECORDING,
    LIVE_PAUSED,
    RECORDING_TRANSFER,
    FINALIZING_RECORDING,
    REPLAY_TRANSITION,
    REPLAY,
    RETURNING
}
