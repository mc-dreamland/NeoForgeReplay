package com.replaymod.replay.resourcepack.mixin;

import com.replaymod.replay.ReplayResourcePackReloadBridge;
import net.minecraft.client.resource.server.ServerResourcePackLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerResourcePackLoader.class)
abstract class MixinServerResourcePackLoaderReplay {
    @Inject(method = "onReloadSuccess", at = @At("RETURN"))
    private void replayMod$onReloadSuccess(CallbackInfo ci) {
        ReplayResourcePackReloadBridge.success();
    }

    @Inject(method = "onReloadFailure", at = @At("RETURN"))
    private void replayMod$onReloadFailure(CallbackInfo ci) {
        ReplayResourcePackReloadBridge.failure("reload-failure");
    }

    @Inject(method = "onForcedReloadFailure", at = @At("RETURN"))
    private void replayMod$onForcedReloadFailure(CallbackInfo ci) {
        ReplayResourcePackReloadBridge.failure("forced-reload-failure");
    }
}
