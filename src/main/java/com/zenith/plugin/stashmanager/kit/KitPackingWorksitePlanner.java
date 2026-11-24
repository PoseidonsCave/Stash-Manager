package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.orchestration.ContainerApproach;

/** Bounded discovery of a same-floor standing cell beside a safe packing worksite. */
public final class KitPackingWorksitePlanner {
    public interface WorldView {
        boolean replaceable(int x, int y, int z);
        boolean solid(int x, int y, int z);
        boolean interactable(int x, int y, int z);
    }

    public record Plan(int[] stance, int[] worksite) { }

    private static final int MAX_RADIUS = 12;
    private static final int[][] NEIGHBORS = {
            {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };

    private KitPackingWorksitePlanner() { }

    public static Plan discover(int playerX, int playerY, int playerZ, WorldView world) {
        if (world == null) return null;
        for (int radius = 4; radius <= MAX_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = playerX + dx, z = playerZ + dz;
                    if (!safeWorksite(x, playerY, z, world)) continue;
                    for (int sx = -2; sx <= 2; sx++) {
                        for (int sz = -2; sz <= 2; sz++) {
                            if (sx == 0 && sz == 0) continue;
                            int standX = x + sx, standZ = z + sz;
                            if (!safeStance(standX, playerY, standZ, world)
                                    || !ContainerApproach.isWithinVanillaReach(
                                            standX + 0.5, playerY, standZ + 0.5,
                                            x, playerY, z)) continue;
                            return new Plan(new int[]{standX, playerY, standZ},
                                    new int[]{x, playerY, z});
                        }
                    }
                }
            }
        }
        return null;
    }

    public static boolean safeWorksite(int x, int y, int z, WorldView world) {
        if (!world.replaceable(x, y, z) || !world.replaceable(x, y + 1, z)
                || !world.solid(x, y - 1, z)) return false;
        for (int[] offset : NEIGHBORS) {
            if (world.interactable(x + offset[0], y + offset[1], z + offset[2])) {
                return false;
            }
        }
        return true;
    }

    private static boolean safeStance(int x, int y, int z, WorldView world) {
        return world.replaceable(x, y, z) && world.replaceable(x, y + 1, z)
                && world.solid(x, y - 1, z);
    }
}
