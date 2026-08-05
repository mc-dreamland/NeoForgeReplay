package com.replaymod.core.mixin;

import com.replaymod.replay.production.ProductionReplaySessionController;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.packet.s2c.common.ServerTransferS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonNetworkHandler.class)
public abstract class MixinClientCommonNetworkHandlerProductionRecording {
    @Inject(
            method = "onServerTransfer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/ClientConnection;disconnect(Lnet/minecraft/text/Text;)V"
            )
    )
    private void replaymod$prepareRecordingContinuation(ServerTransferS2CPacket packet, CallbackInfo ci) {
        ProductionReplaySessionController.getInstance()
                .prepareRecordingContinuation(packet.host(), packet.port());
    }
}
