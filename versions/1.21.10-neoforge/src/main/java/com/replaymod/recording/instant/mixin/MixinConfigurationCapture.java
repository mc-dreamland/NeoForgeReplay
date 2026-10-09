package com.replaymod.recording.instant.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.replaymod.recording.instant.ConfigurationPhaseCapture;
import net.minecraft.client.network.ClientConfigurationNetworkHandler;
import net.minecraft.registry.DynamicRegistryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConfigurationNetworkHandler.class)
public abstract class MixinConfigurationCapture {
    @Inject(method = "onReady", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/ClientConnection;transitionInbound(Lnet/minecraft/network/state/NetworkState;Lnet/minecraft/network/listener/PacketListener;)V"))
    private void replaymod$finishConfigurationCapture(CallbackInfo ci, @Local DynamicRegistryManager.Immutable registryManager) {
        ConfigurationPhaseCapture.finishConfiguration(
                ((ClientCommonNetworkHandlerAccessor) this).replaymod$getConnection(), registryManager);
    }
}
