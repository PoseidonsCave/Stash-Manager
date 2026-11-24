package com.zenith.plugin.stashmanager.orchestration;

/** Bounded handoff from pathing to a container-open request. */
public final class ContainerOpenReach {
    private static final double EYE_HEIGHT = 1.62;
    private static final double CLOSE_REACH = 3.0;
    private static final double EDGE_REACH = 4.0;

    public enum Band { CLOSE, EDGE, OUT_OF_REACH }

    private ContainerOpenReach() { }

    public static Band band(double playerX, double playerY, double playerZ,
                            int targetX, int targetY, int targetZ) {
        double dx = distanceToInterval(playerX, targetX, targetX + 1.0);
        double dy = distanceToInterval(playerY + EYE_HEIGHT, targetY, targetY + 1.0);
        double dz = distanceToInterval(playerZ, targetZ, targetZ + 1.0);
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared <= CLOSE_REACH * CLOSE_REACH) return Band.CLOSE;
        if (distanceSquared <= EDGE_REACH * EDGE_REACH) return Band.EDGE;
        return Band.OUT_OF_REACH;
    }

    public static int maxAttempts(Band band) {
        return switch (band) {
            case CLOSE -> 3;
            case EDGE -> 1;
            case OUT_OF_REACH -> 0;
        };
    }

    public static long stanceKey(double playerX, double playerY, double playerZ) {
        long x = (long) Math.floor(playerX);
        long y = (long) Math.floor(playerY);
        long z = (long) Math.floor(playerZ);
        return (x & 0x3ffffffL) << 38 | (z & 0x3ffffffL) << 12 | y & 0xfffL;
    }

    private static double distanceToInterval(double value, double min, double max) {
        if (value < min) return min - value;
        if (value > max) return value - max;
        return 0.0;
    }
}
