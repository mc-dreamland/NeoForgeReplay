package com.replaymod.replay.mixin;

import com.replaymod.replay.support.ClientEntitySynchronizationAccess;
import com.replaymod.replay.support.UntrackedEntitySynchronizer;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ClientPlayNetworkHandler.class)
public class Mixin_FixEntityNotTracking {
    @ModifyVariable(method = { "onEntityPosition", "onEntity" }, at = @At("RETURN"), ordinal = 0)
    private Entity finishPositionIfNotTracked(Entity entity) {
        UntrackedEntitySynchronizer.afterPositionUpdate(entity, ClientEntitySynchronizationAccess.INSTANCE);
        return entity;
    }

    @ModifyVariable(method = "onEntityPassengersSet", at = @At("RETURN"), ordinal = 0)
    private Entity positionPassengersIfNotTracked(Entity entity) {
        // The vanilla handler has already applied every passenger relationship. Only synchronize positions here;
        // ticking arbitrary entities from inside packet handling can run unrelated projectile/world behaviour.
        UntrackedEntitySynchronizer.afterPassengersUpdate(entity, ClientEntitySynchronizationAccess.INSTANCE);
        return entity;
    }
}
