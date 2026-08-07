package com.replaymod.replay;

import com.replaymod.core.ReplayMod;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class ReplayResourcePackReloadBridge {
    private static final long RELOAD_TIMEOUT_SECONDS = 120;

    private static long activeGeneration = -1;
    private static ReplayResourcePackController.Completion activeCompletion;

    private ReplayResourcePackReloadBridge() {
    }

    static synchronized void arm(long generation, ReplayResourcePackController.Completion completion) {
        if (activeCompletion != null) {
            throw new IllegalStateException("A replay resource pack reload is already active");
        }
        activeGeneration = generation;
        activeCompletion = completion;
        CompletableFuture.delayedExecutor(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS).execute(() ->
                ReplayMod.instance.runLaterWithoutLock(() -> timeout(generation)));
    }

    public static void success() {
        ReplayResourcePackController.Completion completion = takeActive();
        if (completion != null) completion.success();
    }

    public static void failure(String reason) {
        ReplayResourcePackController.Completion completion = takeActive();
        if (completion != null) completion.failure(reason);
    }

    static synchronized void cancel() {
        activeGeneration = -1;
        activeCompletion = null;
    }

    private static void timeout(long generation) {
        ReplayResourcePackController.Completion completion;
        synchronized (ReplayResourcePackReloadBridge.class) {
            if (activeCompletion == null || activeGeneration != generation) return;
            completion = activeCompletion;
            activeCompletion = null;
            activeGeneration = -1;
        }
        completion.failure("reload-timeout");
    }

    private static ReplayResourcePackController.Completion takeActive() {
        synchronized (ReplayResourcePackReloadBridge.class) {
            ReplayResourcePackController.Completion completion = activeCompletion;
            activeCompletion = null;
            activeGeneration = -1;
            return completion;
        }
    }
}
