package com.replaymod.recording.instant.mixin;

import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientWorld.Properties.class)
public interface ClientWorldPropertiesAccessor {
    @Accessor("flatWorld")
    boolean replaymod$isFlatWorld();
}
