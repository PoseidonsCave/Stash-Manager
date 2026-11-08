package com.zenith.plugin.stashmanager.orchestration;

/** Keep a long scan walk alive while the bot is moving, but bound idle and total time. */
public final class ScanWalkProgress {
    public static final int IDLE_LIMIT_TICKS = 300;
    public static final int TRIP_LIMIT_TICKS = 1800;
    private static final double MIN_MOVEMENT = 0.5;

    private boolean active;
    private int elapsedTicks;
    private int idleTicks;
    private double anchorX, anchorY, anchorZ;

    public boolean stalled(double x, double y, double z) {
        if (!active) {
            active = true;
            anchorX = x;
            anchorY = y;
            anchorZ = z;
            return false;
        }
        elapsedTicks++;
        double dx = x - anchorX, dy = y - anchorY, dz = z - anchorZ;
        if (dx * dx + dy * dy + dz * dz >= MIN_MOVEMENT * MIN_MOVEMENT) {
            anchorX = x;
            anchorY = y;
            anchorZ = z;
            idleTicks = 0;
        } else {
            idleTicks++;
        }
        return idleTicks >= IDLE_LIMIT_TICKS || elapsedTicks >= TRIP_LIMIT_TICKS;
    }

    public int elapsedTicks() { return elapsedTicks; }
    public int idleTicks() { return idleTicks; }

    public void reset() {
        active = false;
        elapsedTicks = 0;
        idleTicks = 0;
    }
}
