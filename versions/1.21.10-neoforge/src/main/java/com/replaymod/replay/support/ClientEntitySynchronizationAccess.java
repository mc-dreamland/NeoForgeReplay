package com.replaymod.replay.support;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.PositionInterpolator;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public final class ClientEntitySynchronizationAccess
        implements UntrackedEntitySynchronizer.Access<Entity, Vec3d> {
    public static final ClientEntitySynchronizationAccess INSTANCE = new ClientEntitySynchronizationAccess();

    private ClientEntitySynchronizationAccess() {
    }

    @Override
    public boolean isTracked(Entity entity) {
        return !(entity.getEntityWorld() instanceof ClientWorld world) || world.getEntityById(entity.getId()) == entity;
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
        return entity.getEntityPos();
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
