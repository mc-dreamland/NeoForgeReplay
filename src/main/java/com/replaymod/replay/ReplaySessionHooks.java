package com.replaymod.replay;

import java.io.IOException;

/**
 * Optional integration point for launchers which keep the client inside a managed server session.
 */
public final class ReplaySessionHooks {
    @FunctionalInterface
    public interface ReplayLaunch {
        void run() throws IOException;
    }

    public interface Handler {
        boolean prepareReplayLaunch(ReplayLaunch launch);

        boolean isManagedReplaySession();

        void onReplayClosed();

        boolean completeReplaySession();
    }

    private static Handler handler;

    private ReplaySessionHooks() {
    }

    public static void install(Handler newHandler) {
        handler = newHandler;
    }

    public static boolean prepareReplayLaunch(ReplayLaunch launch) {
        return handler != null && handler.prepareReplayLaunch(launch);
    }

    public static boolean isManagedReplaySession() {
        return handler != null && handler.isManagedReplaySession();
    }

    public static void onReplayClosed() {
        if (handler != null) {
            handler.onReplayClosed();
        }
    }

    public static boolean completeReplaySession() {
        return handler != null && handler.completeReplaySession();
    }
}
