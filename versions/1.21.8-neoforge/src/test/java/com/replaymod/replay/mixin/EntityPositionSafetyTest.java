package com.replaymod.replay.mixin;

import com.replaymod.replay.support.EntityPositionSafety;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EntityPositionSafetyTest {
    @Test
    public void acceptsNormalAndHardWorldBoundaryPositions() {
        assertTrue(safe(0.0, 64.0, 0.0));
        assertTrue(safe(-30_000_000.0, 64.0, -30_000_000.0));
        assertTrue(safe(29_999_999.999, 64.0, 29_999_999.999));
    }

    @Test
    public void rejectsPositionsOutsideHardWorldBoundary() {
        assertFalse(safe(30_000_000.0, 64.0, 0.0));
        assertFalse(safe(-30_000_000.001, 64.0, 0.0));
        assertFalse(safe(0.0, 64.0, 30_000_000.0));
        assertFalse(safe(0.0, 64.0, -30_000_000.001));
    }

    @Test
    public void rejectsCoordinatesThatReachSectionKeyLimits() {
        // The positive value is the section whose packed range ended at Long.MAX_VALUE in ECD-20260805-41827f47bad39de2fe3f62e3.
        assertFalse(safe(33_554_416.0, 64.0, 0.0));
        assertFalse(safe(-33_554_432.0, 64.0, 0.0));
        assertFalse(safe(0.0, 8_388_608.0, 0.0));
        assertFalse(safe(0.0, -8_388_608.0, 0.0));
        assertFalse(EntityPositionSafety.isSafe(
                0.0, 64.0, 0.0,
                33_554_416.0, 64.0, 0.0,
                33_554_416.5, 65.0, 0.5
        ));
    }

    @Test
    public void rejectsNonFinitePositionsAndBounds() {
        assertFalse(safe(Double.NaN, 64.0, 0.0));
        assertFalse(safe(Double.POSITIVE_INFINITY, 64.0, 0.0));
        assertFalse(safe(0.0, Double.NEGATIVE_INFINITY, 0.0));
        assertFalse(EntityPositionSafety.isSafe(
                0.0, 64.0, 0.0,
                Double.NaN, 64.0, 0.0,
                1.0, 65.0, 1.0
        ));
    }

    private static boolean safe(double x, double y, double z) {
        return EntityPositionSafety.isSafe(
                x, y, z,
                x - 0.5, y, z - 0.5,
                x + 0.5, y + 1.0, z + 0.5
        );
    }
}
