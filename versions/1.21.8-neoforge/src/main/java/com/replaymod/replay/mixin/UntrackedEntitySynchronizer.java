package com.replaymod.replay.mixin;

final class UntrackedEntitySynchronizer {
    private UntrackedEntitySynchronizer() {
    }

    static <E, P> void afterPositionUpdate(E entity, Access<E, P> access) {
        if (entity == null) {
            return;
        }

        if (shouldSynchronize(entity, access)) {
            Target<P> target = access.interpolationTarget(entity);
            if (target != null) {
                // Never leave an invalid target queued for a later normal client tick.
                access.clearInterpolation(entity);
                if (Float.isFinite(target.yaw)
                        && Float.isFinite(target.pitch)
                        && access.isSafePosition(entity, target.position)) {
                    access.snap(entity, target);
                }
            }
        }

        synchronizePassengerHierarchy(entity, access);
    }

    static <E, P> void afterPassengersUpdate(E vehicle, Access<E, P> access) {
        // Preserve the old workaround's immediate vehicle update, but finish only an already queued interpolation
        // target. afterPositionUpdate also places the complete passenger hierarchy without behaviour ticks.
        afterPositionUpdate(vehicle, access);
    }

    private static <E, P> boolean shouldSynchronize(E entity, Access<E, P> access) {
        return !access.isTracked(entity) && !access.isLocallyControlled(entity);
    }

    private static <E, P> void synchronizePassengerHierarchy(E vehicle, Access<E, P> access) {
        if (!access.isSafePosition(vehicle, access.position(vehicle))) {
            return;
        }

        for (E passenger : access.passengers(vehicle)) {
            if (shouldSynchronize(passenger, access)) {
                P target = access.passengerPosition(vehicle, passenger);
                if (access.isSafePosition(passenger, target)) {
                    access.setPosition(passenger, target);
                }
            }
            synchronizePassengerHierarchy(passenger, access);
        }
    }

    interface Access<E, P> {
        boolean isTracked(E entity);

        boolean isLocallyControlled(E entity);

        Target<P> interpolationTarget(E entity);

        void clearInterpolation(E entity);

        boolean isSafePosition(E entity, P position);

        void snap(E entity, Target<P> target);

        P position(E entity);

        Iterable<E> passengers(E entity);

        P passengerPosition(E vehicle, E passenger);

        void setPosition(E entity, P position);
    }

    static final class Target<P> {
        final P position;
        final float yaw;
        final float pitch;

        Target(P position, float yaw, float pitch) {
            this.position = position;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }
}
