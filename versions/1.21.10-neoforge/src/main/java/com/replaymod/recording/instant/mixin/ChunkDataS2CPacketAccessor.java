package com.replaymod.recording.instant.mixin;

import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.LightData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkDataS2CPacket.class)
public interface ChunkDataS2CPacketAccessor {
    @Mutable
    @Accessor("lightData")
    void replaymod$setLightData(LightData lightData);
}
