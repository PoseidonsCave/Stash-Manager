package com.zenith.plugin.stashmanager.organizer;

/** Small search pattern for collecting a mined temporary shulker. */
final class ShulkerPickupSweep {
    static final int PATH_RETRY_TICKS = 20;

    private static final int[][] OFFSETS = {
            {0, 0},
            {1, 0}, {0, 1}, {-1, 0}, {0, -1},
            {1, 1}, {-1, 1}, {-1, -1}, {1, -1},
            {2, 0}, {0, 2}, {-2, 0}, {0, -2}
    };

    private ShulkerPickupSweep() { }

    static boolean shouldIssuePath(int tick, int lastPathTick, boolean pathActive) {
        return !pathActive
                && (lastPathTick < 0 || tick - lastPathTick >= PATH_RETRY_TICKS);
    }

    static int[] target(int[] origin, int attempt) {
        int[] offset = OFFSETS[Math.floorMod(attempt, OFFSETS.length)];
        return new int[]{origin[0] + offset[0], origin[1], origin[2] + offset[1]};
    }

    static int targetCount() {
        return OFFSETS.length;
    }
}
