package com.zenith.plugin.stashmanager.orchestration;

/** Keeps scan travel on the configured stash level and detects displaced bots. */
public final class ScanNavigationPolicy {
    private ScanNavigationPolicy() { }

    public static int waypointY(
            double scanStartY,
            double currentY,
            int[] pos1,
            int[] pos2) {
        int minY = Math.min(pos1[1], pos2[1]);
        int maxY = Math.max(pos1[1], pos2[1]);
        double anchorY = Double.isFinite(scanStartY) ? scanStartY : currentY;
        int candidate = (int) Math.floor(anchorY);
        return Math.max(minY, Math.min(maxY, candidate));
    }

    public static boolean isOutsideRegionEnvelope(
            double playerX,
            double playerY,
            double playerZ,
            int[] pos1,
            int[] pos2,
            double horizontalMargin,
            double verticalMargin) {
        if (pos1 == null || pos2 == null || pos1.length < 3 || pos2.length < 3) {
            return false;
        }

        double minX = Math.min(pos1[0], pos2[0]) - horizontalMargin;
        double maxX = Math.max(pos1[0], pos2[0]) + 1.0 + horizontalMargin;
        double minY = Math.min(pos1[1], pos2[1]) - verticalMargin;
        double maxY = Math.max(pos1[1], pos2[1]) + 1.0 + verticalMargin;
        double minZ = Math.min(pos1[2], pos2[2]) - horizontalMargin;
        double maxZ = Math.max(pos1[2], pos2[2]) + 1.0 + horizontalMargin;

        return playerX < minX || playerX > maxX
                || playerY < minY || playerY > maxY
                || playerZ < minZ || playerZ > maxZ;
    }
}
