package com.replaymod.recording.mixin;

import com.replaymod.core.versions.MCVer;
import com.replaymod.recording.handler.RecordingEventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.UUID;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class MixinNetHandlerPlayClient {
    private static final MinecraftClient mcStatic = MCVer.getMinecraft();

    @Shadow
    private Map<UUID, PlayerListEntry> playerListEntries;

    private RecordingEventHandler getRecordingEventHandler() {
        return ((RecordingEventHandler.RecordingEventSender) mcStatic.worldRenderer).getRecordingEventHandler();
    }

    @Inject(method = "onPlayerList", at = @At("HEAD"))
    private void recordOwnJoin(PlayerListS2CPacket packet, CallbackInfo ci) {
        if (!mcStatic.isOnThread() || mcStatic.player == null) {
            return;
        }

        RecordingEventHandler handler = getRecordingEventHandler();
        if (handler == null || !packet.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) {
            return;
        }

        for (PlayerListS2CPacket.Entry entry : packet.getEntries()) {
            UUID uuid = entry.profileId();
            if (uuid.equals(mcStatic.player.getGameProfile().id()) && !playerListEntries.containsKey(uuid)) {
                handler.spawnRecordingPlayer();
            }
        }
    }

    @Inject(method = "onPlayerRespawn", at = @At("RETURN"))
    private void recordOwnRespawn(PlayerRespawnS2CPacket packet, CallbackInfo ci) {
        RecordingEventHandler handler = getRecordingEventHandler();
        if (handler != null) {
            handler.spawnRecordingPlayer();
        }
    }
}
