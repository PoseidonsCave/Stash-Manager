package com.zenith.plugin.stashmanager.orchestration;

/** Shared handoff policy between pathing and Zenith's block-interaction process. */
public final class ContainerApproach {
    private static final double STANDING_EYE_HEIGHT = 1.62;
    // Vanilla/Zenith block reach is normally 4.5 blocks. Keep half a block of margin so
    // this predicate only hands off targets that the interaction process can plausibly hit.
    private static final double CONSERVATIVE_BLOCK_REACH = 4.0;

    private ContainerApproach() { }

    public static boolean isAtAccessPosition(
            double playerX,
            double playerY,
            double playerZ,
            int targetX,
            int targetY,
            int targetZ) {
        int feetX = (int) Math.floor(playerX);
        int feetY = (int) Math.floor(playerY);
        int feetZ = (int) Math.floor(playerZ);
        int xDiff = feetX - targetX;
        int yDiff = feetY - targetY;
        int zDiff = feetZ - targetZ;
        boolean goalGetToBlockReached = Math.abs(xDiff)
            + Math.abs(yDiff < 0 ? yDiff + 1 : yDiff)
            + Math.abs(zDiff) <= 1;
        if (goalGetToBlockReached) {
            return true;
        }

        // GoalGetToBlock can finish at a valid vanilla interaction distance without landing
        // in its exact-adjacency goal. At that point Zenith's InteractWithProcess should own
        // the final rotation, raycast, and (when necessary) short approach. Measure to the
        // target block's unit AABB rather than its origin so the check matches physical reach.
        double eyeY = playerY + STANDING_EYE_HEIGHT;
        double dxToBlock = distanceToInterval(playerX, targetX, targetX + 1.0);
        double dyToBlock = distanceToInterval(eyeY, targetY, targetY + 1.0);
        double dzToBlock = distanceToInterval(playerZ, targetZ, targetZ + 1.0);
        return dxToBlock * dxToBlock
                + dyToBlock * dyToBlock
                + dzToBlock * dzToBlock
                <= CONSERVATIVE_BLOCK_REACH * CONSERVATIVE_BLOCK_REACH;
    }

    private static double distanceToInterval(double value, double min, double max) {
        if (value < min) return min - value;
        if (value > max) return value - max;
        return 0.0;
    }
}
