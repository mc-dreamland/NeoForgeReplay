package com.replaymod.replay.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.PositionInterpolator;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ClientPlayNetworkHandler.class)
public class Mixin_FixEntityNotTracking {
    private static final UntrackedEntitySynchronizer.Access<Entity, Vec3d> ENTITY_ACCESS = new EntityAccess();

    @ModifyVariable(method = { "onEntityPosition", "onEntity" }, at = @At("RETURN"), ordinal = 0)
    private Entity finishPositionIfNotTracked(Entity entity) {
        UntrackedEntitySynchronizer.afterPositionUpdate(entity, ENTITY_ACCESS);
        return entity;
    }

    @ModifyVariable(method = "onEntityPassengersSet", at = @At("RETURN"), ordinal = 0)
    private Entity positionPassengersIfNotTracked(Entity entity) {
        // The vanilla handler has already applied every passenger relationship. Only synchronize positions here;
        // ticking arbitrary entities from inside packet handling can run unrelated projectile/world behaviour.
        UntrackedEntitySynchronizer.afterPassengersUpdate(entity, ENTITY_ACCESS);
        return entity;
    }

    private static final class EntityAccess implements UntrackedEntitySynchronizer.Access<Entity, Vec3d> {
        @Override
        public boolean isTracked(Entity entity) {
            return !(entity.getWorld() instanceof ClientWorldAccessor world) || world.getEntityList().has(entity);
        }

        @Override
        public boolean isLocallyControlled(Entity entity) {
            return entity.isLogicalSideForUpdatingMovement();
        }

        @Override
        public UntrackedEntitySynchronizer.Target<Vec3d> interpolationTarget(Entity entity) {
            PositionInterpolator interpolator = entity.getInterpolator();
            if (interpolator == null || !interpolator.isInterpolating()) {
                return null;
            }
            return new UntrackedEntitySynchronizer.Target<>(
                    interpolator.getLerpedPos(),
                    interpolator.getLerpedYaw(),
                    interpolator.getLerpedPitch()
            );
        }

        @Override
        public void clearInterpolation(Entity entity) {
            PositionInterpolator interpolator = entity.getInterpolator();
            if (interpolator != null) {
                interpolator.clear();
            }
        }

        @Override
        public boolean isSafePosition(Entity entity, Vec3d position) {
            Box bounds = entity.getDimensions(entity.getPose()).getBoxAt(position);
            return EntityPositionSafety.isSafe(position, bounds);
        }

        @Override
        public void snap(Entity entity, UntrackedEntitySynchronizer.Target<Vec3d> target) {
            entity.refreshPositionAndAngles(target.position, target.yaw, target.pitch);
        }

        @Override
        public Vec3d position(Entity entity) {
            return entity.getPos();
        }

        @Override
        public Iterable<Entity> passengers(Entity entity) {
            return entity.getPassengerList();
        }

        @Override
        public Vec3d passengerPosition(Entity vehicle, Entity passenger) {
            return vehicle.getPassengerRidingPos(passenger).subtract(passenger.getVehicleAttachmentPos(vehicle));
        }

        @Override
        public void setPosition(Entity entity, Vec3d position) {
            entity.setPosition(position);
        }
    }
}
