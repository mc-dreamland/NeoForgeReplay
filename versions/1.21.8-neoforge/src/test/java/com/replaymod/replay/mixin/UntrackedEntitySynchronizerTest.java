package com.replaymod.replay.mixin;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class UntrackedEntitySynchronizerTest {
    private final FakeAccess access = new FakeAccess();

    @Test
    public void normalPassengerPacketLeavesTrackedEntitiesToVanilla() {
        FakeEntity vehicle = tracked("vehicle", position(0.0, 64.0, 0.0));
        FakeEntity passenger = tracked("passenger", position(0.0, 64.0, 0.0));
        vehicle.passengers.add(passenger);

        UntrackedEntitySynchronizer.afterPassengersUpdate(vehicle, access);

        assertSame(passenger, vehicle.passengers.get(0));
        assertEquals(0, passenger.positionUpdates);
        assertEquals(0, passenger.behaviourTicks);
    }

    @Test
    public void untrackedOrdinaryEntityCompletesInterpolationWithoutTicking() {
        FakeEntity entity = untracked("ordinary", position(0.0, 64.0, 0.0));
        entity.interpolation = new UntrackedEntitySynchronizer.Target<>(position(12.0, 70.0, -5.0), 90.0F, 10.0F);

        UntrackedEntitySynchronizer.afterPositionUpdate(entity, access);

        assertEquals(position(12.0, 70.0, -5.0), entity.position);
        assertEquals(1, entity.interpolationClears);
        assertEquals(1, entity.snaps);
        assertEquals(0, entity.behaviourTicks);
    }

    @Test
    public void trackedVehiclePositionUpdateStillSynchronizesUntrackedPassenger() {
        FakeEntity vehicle = tracked("vehicle", position(10.0, 64.0, 10.0));
        FakeEntity passenger = untracked("passenger", position(0.0, 0.0, 0.0));
        passenger.mountOffset = position(0.0, 1.0, 0.0);
        vehicle.passengers.add(passenger);

        UntrackedEntitySynchronizer.afterPositionUpdate(vehicle, access);

        assertEquals(position(10.0, 65.0, 10.0), passenger.position);
        assertEquals(1, passenger.positionUpdates);
        assertEquals(0, passenger.behaviourTicks);
    }

    @Test
    public void passengerPacketCompletesUntrackedVehicleInterpolationWithoutTicking() {
        FakeEntity vehicle = untracked("vehicle", position(0.0, 64.0, 0.0));
        FakeEntity passenger = untracked("passenger", position(0.0, 0.0, 0.0));
        vehicle.interpolation = new UntrackedEntitySynchronizer.Target<>(position(10.0, 70.0, -4.0), 45.0F, 5.0F);
        passenger.mountOffset = position(0.0, 1.0, 0.0);
        vehicle.passengers.add(passenger);

        UntrackedEntitySynchronizer.afterPassengersUpdate(vehicle, access);

        assertEquals(position(10.0, 70.0, -4.0), vehicle.position);
        assertEquals(1, vehicle.interpolationClears);
        assertEquals(1, vehicle.snaps);
        assertEquals(position(10.0, 71.0, -4.0), passenger.position);
        assertEquals(1, passenger.positionUpdates);
        assertEquals(0, vehicle.behaviourTicks + passenger.behaviourTicks);
    }

    @Test
    public void fireworkPassengerAtExtremePositionIsNeverTicked() {
        FakeEntity vehicle = tracked("vehicle", position(0.0, 64.0, 0.0));
        FakeEntity firework = untracked("firework", position(33_554_416.0, 64.0, 0.0));
        firework.mountOffset = position(33_554_416.0, 0.0, 0.0);
        vehicle.passengers.add(firework);

        UntrackedEntitySynchronizer.afterPassengersUpdate(vehicle, access);

        assertEquals(position(33_554_416.0, 64.0, 0.0), firework.position);
        assertEquals(0, firework.positionUpdates);
        assertEquals(0, firework.behaviourTicks);
    }

    @Test
    public void invalidInterpolationIsClearedWithoutMovingOrTickingEntity() {
        FakeEntity entity = untracked("firework", position(0.0, 64.0, 0.0));
        entity.interpolation = new UntrackedEntitySynchronizer.Target<>(position(Double.NaN, 64.0, 0.0), 0.0F, 0.0F);

        UntrackedEntitySynchronizer.afterPositionUpdate(entity, access);

        assertEquals(position(0.0, 64.0, 0.0), entity.position);
        assertEquals(1, entity.interpolationClears);
        assertEquals(0, entity.snaps);
        assertEquals(0, entity.behaviourTicks);
    }

    @Test
    public void passengerHierarchyIsPositionedDirectlyWithoutBehaviourTicks() {
        FakeEntity vehicle = tracked("vehicle", position(10.0, 64.0, 10.0));
        FakeEntity passenger = untracked("passenger", position(0.0, 0.0, 0.0));
        FakeEntity nestedPassenger = untracked("nested", position(0.0, 0.0, 0.0));
        passenger.mountOffset = position(0.0, 1.0, 0.0);
        nestedPassenger.mountOffset = position(0.0, 1.0, 0.0);
        vehicle.passengers.add(passenger);
        passenger.passengers.add(nestedPassenger);

        UntrackedEntitySynchronizer.afterPassengersUpdate(vehicle, access);

        assertEquals(position(10.0, 65.0, 10.0), passenger.position);
        assertEquals(position(10.0, 66.0, 10.0), nestedPassenger.position);
        assertEquals(1, passenger.positionUpdates);
        assertEquals(1, nestedPassenger.positionUpdates);
        assertEquals(0, passenger.behaviourTicks + nestedPassenger.behaviourTicks);
    }

    @Test
    public void invalidPassengerDoesNotPreventSafeSiblingSynchronization() {
        FakeEntity vehicle = tracked("vehicle", position(0.0, 64.0, 0.0));
        FakeEntity invalidFirework = untracked("firework", position(33_554_416.0, 64.0, 0.0));
        FakeEntity safePassenger = untracked("passenger", position(0.0, 0.0, 0.0));
        invalidFirework.mountOffset = position(33_554_416.0, 0.0, 0.0);
        safePassenger.mountOffset = position(0.0, 1.0, 0.0);
        vehicle.passengers.add(invalidFirework);
        vehicle.passengers.add(safePassenger);

        UntrackedEntitySynchronizer.afterPassengersUpdate(vehicle, access);

        assertEquals(2, vehicle.passengers.size());
        assertSame(invalidFirework, vehicle.passengers.get(0));
        assertSame(safePassenger, vehicle.passengers.get(1));
        assertEquals(0, invalidFirework.positionUpdates);
        assertEquals(position(0.0, 65.0, 0.0), safePassenger.position);
        assertEquals(1, safePassenger.positionUpdates);
        assertEquals(0, invalidFirework.behaviourTicks + safePassenger.behaviourTicks);
    }

    private static FakeEntity tracked(String name, Position position) {
        return new FakeEntity(name, position, true);
    }

    private static FakeEntity untracked(String name, Position position) {
        return new FakeEntity(name, position, false);
    }

    private static Position position(double x, double y, double z) {
        return new Position(x, y, z);
    }

    private static final class FakeAccess implements UntrackedEntitySynchronizer.Access<FakeEntity, Position> {
        @Override
        public boolean isTracked(FakeEntity entity) {
            return entity.tracked;
        }

        @Override
        public boolean isLocallyControlled(FakeEntity entity) {
            return entity.locallyControlled;
        }

        @Override
        public UntrackedEntitySynchronizer.Target<Position> interpolationTarget(FakeEntity entity) {
            return entity.interpolation;
        }

        @Override
        public void clearInterpolation(FakeEntity entity) {
            entity.interpolation = null;
            entity.interpolationClears++;
        }

        @Override
        public boolean isSafePosition(FakeEntity entity, Position position) {
            return EntityPositionSafety.isSafe(
                    position.x, position.y, position.z,
                    position.x - 0.5, position.y, position.z - 0.5,
                    position.x + 0.5, position.y + 1.0, position.z + 0.5
            );
        }

        @Override
        public void snap(FakeEntity entity, UntrackedEntitySynchronizer.Target<Position> target) {
            entity.position = target.position;
            entity.snaps++;
        }

        @Override
        public Position position(FakeEntity entity) {
            return entity.position;
        }

        @Override
        public Iterable<FakeEntity> passengers(FakeEntity entity) {
            return entity.passengers;
        }

        @Override
        public Position passengerPosition(FakeEntity vehicle, FakeEntity passenger) {
            return vehicle.position.add(passenger.mountOffset);
        }

        @Override
        public void setPosition(FakeEntity entity, Position position) {
            entity.position = position;
            entity.positionUpdates++;
        }
    }

    private static final class FakeEntity {
        final String name;
        final boolean tracked;
        final List<FakeEntity> passengers = new ArrayList<>();
        Position position;
        Position mountOffset = position(0.0, 0.0, 0.0);
        boolean locallyControlled;
        UntrackedEntitySynchronizer.Target<Position> interpolation;
        int interpolationClears;
        int snaps;
        int positionUpdates;
        int behaviourTicks;

        FakeEntity(String name, Position position, boolean tracked) {
            this.name = name;
            this.position = position;
            this.tracked = tracked;
        }
    }

    private static final class Position {
        final double x;
        final double y;
        final double z;

        Position(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        Position add(Position other) {
            return new Position(this.x + other.x, this.y + other.y, this.z + other.z);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Position position)) {
                return false;
            }
            return Double.compare(this.x, position.x) == 0
                    && Double.compare(this.y, position.y) == 0
                    && Double.compare(this.z, position.z) == 0;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(this.x, this.y, this.z);
        }

        @Override
        public String toString() {
            return "Position{" + this.x + ", " + this.y + ", " + this.z + '}';
        }
    }
}
