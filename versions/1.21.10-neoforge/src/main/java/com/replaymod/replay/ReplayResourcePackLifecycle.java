package com.replaymod.replay;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ProgressScreen;

final class ReplayResourcePackLifecycle {
    private ReplayResourcePackLifecycle() {
    }

    static void disconnectForInternalRestart(MinecraftClient minecraft, boolean preserveServerPacks) {
        if (preserveServerPacks) {
            minecraft.disconnect(new ProgressScreen(true), true);
        } else {
            minecraft.disconnectWithProgressScreen();
        }
    }
}
