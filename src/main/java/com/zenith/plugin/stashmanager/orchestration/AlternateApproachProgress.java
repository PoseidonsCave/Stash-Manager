package com.zenith.plugin.stashmanager.orchestration;

/** Bounded progress check for a selected alternate container access cell. */
public final class AlternateApproachProgress {
    public static final int IDLE_LIMIT_TICKS = 80;
    public static final int TRIP_LIMIT_TICKS = 300;
    private static final double MIN_MOVEMENT = 0.25;
    private static final double MIN_GOAL_IMPROVEMENT = 0.2;

    private boolean active;
    private int goalX, goalY, goalZ;
    private int elapsedTicks, idleTicks;
    private double anchorX, anchorY, anchorZ, bestDistance;

    public boolean stalled(int x, int y, int z, double playerX, double playerY, double playerZ) {
        if (!active || x != goalX || y != goalY || z != goalZ) {
            active = true;
            goalX = x;
            goalY = y;
            goalZ = z;
            elapsedTicks = 0;
            idleTicks = 0;
            anchorX = playerX;
            anchorY = playerY;
            anchorZ = playerZ;
            bestDistance = distance(playerX, playerY, playerZ, x + 0.5, y, z + 0.5);
        }
        elapsedTicks++;
        double currentDistance = distance(playerX, playerY, playerZ, x + 0.5, y, z + 0.5);
        boolean moved = distance(playerX, playerY, playerZ, anchorX, anchorY, anchorZ) >= MIN_MOVEMENT;
        boolean improved = currentDistance + MIN_GOAL_IMPROVEMENT <= bestDistance;
        if (moved || improved) {
            anchorX = playerX;
            anchorY = playerY;
            anchorZ = playerZ;
            bestDistance = Math.min(bestDistance, currentDistance);
            idleTicks = 0;
        } else {
            idleTicks++;
        }
        return idleTicks >= IDLE_LIMIT_TICKS || elapsedTicks >= TRIP_LIMIT_TICKS;
    }

    public int idleTicks() { return idleTicks; }
    public int elapsedTicks() { return elapsedTicks; }

    public void reset() {
        active = false;
        elapsedTicks = 0;
        idleTicks = 0;
    }

    private static double distance(double x1, double y1, double z1,
                                   double x2, double y2, double z2) {
        double dx = x1 - x2, dy = y1 - y2, dz = z1 - z2;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
