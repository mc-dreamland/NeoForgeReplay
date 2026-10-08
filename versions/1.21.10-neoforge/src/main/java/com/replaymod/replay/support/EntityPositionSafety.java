package com.replaymod.replay.support;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public final class EntityPositionSafety {
    private static final double MIN_HORIZONTAL_POSITION = -30_000_000.0;
    private static final double MAX_HORIZONTAL_POSITION = 30_000_000.0;
    private static final int MIN_HORIZONTAL_SECTION = -(1 << 21);
    private static final int MAX_HORIZONTAL_SECTION = (1 << 21) - 1;
    private static final int MIN_VERTICAL_SECTION = -(1 << 19);
    private static final int MAX_VERTICAL_SECTION = (1 << 19) - 1;

    // EntitySectionStorage expands entity lookup boxes by these values before packing section keys.
    private static final double HORIZONTAL_SEARCH_MARGIN = 2.0;
    private static final double MIN_VERTICAL_SEARCH_MARGIN = 4.0;

    private EntityPositionSafety() {
    }

    public static boolean isSafe(Vec3d position, Box bounds) {
        return isSafe(
                position.x,
                position.y,
                position.z,
                bounds.minX,
                bounds.minY,
                bounds.minZ,
                bounds.maxX,
                bounds.maxY,
                bounds.maxZ
        );
    }

    public static boolean isSafe(
            double x,
            double y,
            double z,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ
    ) {
        if (!areFinite(x, y, z, minX, minY, minZ, maxX, maxY, maxZ)) {
            return false;
        }
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return false;
        }
        if (x < MIN_HORIZONTAL_POSITION || x >= MAX_HORIZONTAL_POSITION
                || z < MIN_HORIZONTAL_POSITION || z >= MAX_HORIZONTAL_POSITION) {
            return false;
        }

        int minSectionX = sectionCoord(minX - HORIZONTAL_SEARCH_MARGIN);
        int maxSectionX = sectionCoord(maxX + HORIZONTAL_SEARCH_MARGIN);
        int minSectionY = sectionCoord(minY - MIN_VERTICAL_SEARCH_MARGIN);
        int maxSectionY = sectionCoord(maxY);
        int minSectionZ = sectionCoord(minZ - HORIZONTAL_SEARCH_MARGIN);
        int maxSectionZ = sectionCoord(maxZ + HORIZONTAL_SEARCH_MARGIN);

        return isQueryableXSection(minSectionX)
                && isQueryableXSection(maxSectionX)
                && isVerticalSection(minSectionY)
                && isVerticalSection(maxSectionY)
                && isHorizontalSection(minSectionZ)
                && isHorizontalSection(maxSectionZ);
    }

    private static boolean areFinite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static int sectionCoord(double coordinate) {
        double section = Math.floor(coordinate / 16.0);
        if (section < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (section > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) section;
    }

    private static boolean isHorizontalSection(int section) {
        return section >= MIN_HORIZONTAL_SECTION && section <= MAX_HORIZONTAL_SECTION;
    }

    private static boolean isQueryableXSection(int section) {
        // EntitySectionStorage uses an exclusive upper key. At the maximum packed X section that key is
        // Long.MAX_VALUE + 1, which wraps to Long.MIN_VALUE and makes LongAVLTreeSet.subSet reject the range.
        return section >= MIN_HORIZONTAL_SECTION && section < MAX_HORIZONTAL_SECTION;
    }

    private static boolean isVerticalSection(int section) {
        return section >= MIN_VERTICAL_SECTION && section <= MAX_VERTICAL_SECTION;
    }
}
