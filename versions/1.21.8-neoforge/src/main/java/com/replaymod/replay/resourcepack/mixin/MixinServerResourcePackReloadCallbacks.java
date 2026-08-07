package com.replaymod.replay.resourcepack.mixin;

import com.replaymod.replay.ReplayResourcePackReloadBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.resource.server.ServerResourcePackManager$1")
abstract class MixinServerResourcePackReloadCallbacks {
    @Inject(method = "onFailure", at = @At("HEAD"))
    private void replayMod$onFailure(boolean forced, CallbackInfo ci) {
        ReplayResourcePackReloadBridge.failure(forced ? "forced-reload-failure" : "reload-failure");
    }
}
