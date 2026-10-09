package com.replaymod.recording.instant.mixin;

import com.replaymod.recording.instant.ConfigurationPhaseCapture;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.s2c.login.LoginSuccessS2CPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stores the login success packet for instant recordings. The capture itself is installed together with the
 * regular recorder decision (ReplayModRecording#initiateRecording) at the head of this method.
 */
@Mixin(ClientLoginNetworkHandler.class)
public abstract class MixinLoginCapture {
    @Final
    @Shadow
    private ClientConnection connection;

    @Inject(method = "onSuccess", at = @At("RETURN"))
    private void replaymod$captureLoginSuccess(LoginSuccessS2CPacket packet, CallbackInfo ci) {
        ConfigurationPhaseCapture.onLoginSuccess(connection, packet);
    }
}
