package com.replaymod.core.mixin;

import com.replaymod.replay.production.ProductionReplaySessionController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.neoforged.neoforge.client.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MinecraftClient.class)
public abstract class MixinMinecraftProductionTransition {
    @ModifyVariable(
            method = "disconnect(Lnet/minecraft/client/gui/screen/Screen;Z)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private Screen replaymod$replaceManagedDisconnectScreen(Screen requested) {
        return ProductionReplaySessionController.getInstance().replaceTransitionScreen(requested);
    }

    @Redirect(
            method = "disconnect(Lnet/minecraft/client/gui/screen/Screen;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/client/ClientHooks;firePlayerLogout(Lnet/minecraft/client/network/ClientPlayerInteractionManager;Lnet/minecraft/client/network/ClientPlayerEntity;)V"
            )
    )
    private void replaymod$skipManagedLogoutEvent(ClientPlayerInteractionManager interactionManager,
                                                   ClientPlayerEntity player) {
        if (!ProductionReplaySessionController.getInstance().shouldSuppressLogoutEvent()) {
            ClientHooks.firePlayerLogout(interactionManager, player);
        }
    }
}
