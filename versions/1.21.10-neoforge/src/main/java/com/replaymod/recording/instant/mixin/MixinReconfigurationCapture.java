package com.replaymod.recording.instant.mixin;

import com.replaymod.recording.instant.ConfigurationPhaseCapture;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.NetworkThreadUtils;
import net.minecraft.network.packet.s2c.play.EnterReconfigurationS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class MixinReconfigurationCapture {
    // After forceMainThread, so this only runs once, on the main thread, before the client switches its decoder
    @Inject(method = "onEnterReconfiguration", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/network/PacketApplyBatcher;)V", shift = At.Shift.AFTER))
    private void replaymod$rearmConfigurationCapture(EnterReconfigurationS2CPacket packet, CallbackInfo ci) {
        ConfigurationPhaseCapture.beginReconfiguration(
                ((ClientCommonNetworkHandlerAccessor) this).replaymod$getConnection());
    }
}
