package com.replaymod.recording.instant.mixin;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.map.MapState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(ClientWorld.class)
public interface ClientWorldAccessor {
    @Accessor("mapStates")
    Map<MapIdComponent, MapState> replaymod$getMapStates();

    @Accessor("shouldTickTimeOfDay")
    boolean replaymod$shouldTickTimeOfDay();
}
