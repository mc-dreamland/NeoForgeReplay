package com.replaymod.replay;

import com.replaymod.core.ReplayMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.server.ServerResourcePackLoader;
import net.minecraft.text.Text;

final class MinecraftReplayResourcePackRuntime implements ReplayResourcePackController.RuntimeAdapter {
    private final MinecraftClient minecraft = MinecraftClient.getInstance();

    @Override
    public void apply(long generation, PackTransition transition, ReplayResourcePackController.Completion completion) {
        ReplayMod.instance.runLaterWithoutLock(() -> {
            try {
                ReplayResourcePackReloadBridge.arm(generation, completion);
                ServerResourcePackLoader loader = minecraft.getServerResourcePackProvider();
                for (PackDescriptor removal : transition.removals()) {
                    loader.remove(removal.id());
                }
                for (PackDescriptor addition : transition.additions()) {
                    loader.addResourcePack(addition.id(), addition.extractedPath());
                }
            } catch (RuntimeException exception) {
                ReplayResourcePackReloadBridge.cancel();
                completion.failure("loader-operation");
            }
        });
    }

    @Override
    public void cancelPending() {
        ReplayResourcePackReloadBridge.cancel();
    }

    @Override
    public void warnUser(String reason) {
        ReplayMod.instance.runLaterWithoutLock(() -> minecraft.inGameHud.getChatHud().addMessage(Text.literal(
                "[Replay Mod] Resource pack reuse was disabled for this replay (" + reason
                        + "). The next seek will use the compatibility path.")));
    }
}
