package com.zenith.plugin.stashmanager.orchestration;

import com.zenith.feature.player.World;
import com.zenith.feature.player.raycast.RaycastHelper;
import com.zenith.feature.pathfinder.movement.MovementHelper;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.properties.SlabType;
import com.zenith.mc.block.properties.api.BlockStateProperties;
import com.zenith.plugin.stashmanager.util.BlockCompat;
import com.zenith.plugin.stashmanager.util.DoubleChestIdentity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Chooses a new, loaded standing cell with a visible container face. */
public final class ContainerAccessPlanner {
    private ContainerAccessPlanner() { }

    /**
     * A top slab can hide a chest's center from the advisory raycast even while
     * Zenith's block interaction can open it from a nearby face. Permit one
     * bounded interaction attempt only for this loaded, specific geometry.
     */
    public static boolean coveredChestProbe(int targetX, int targetY, int targetZ,
                                            ContainerOpenReach.Band band) {
        if (band == ContainerOpenReach.Band.OUT_OF_REACH
                || !known(targetX, targetY, targetZ)
                || !known(targetX, targetY + 1, targetZ)) return false;
        try {
            String targetName = World.getBlock(targetX, targetY, targetZ).name();
            SlabType overhead = World.getBlockState(targetX, targetY + 1, targetZ)
                    .getProperty(BlockStateProperties.SLAB_TYPE);
            return coveredChestProbeGeometry(targetName, overhead, band);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean coveredChestProbeGeometry(String targetName, SlabType overhead,
                                             ContainerOpenReach.Band band) {
        return ("chest".equals(targetName) || "trapped_chest".equals(targetName))
                && overhead == SlabType.TOP && band != ContainerOpenReach.Band.OUT_OF_REACH;
    }

    public static boolean visible(double playerX, double playerY, double playerZ,
                                  int targetX, int targetY, int targetZ) {
        if (ContainerOpenReach.band(playerX, playerY, playerZ,
                targetX, targetY, targetZ) == ContainerOpenReach.Band.OUT_OF_REACH
                || !known(targetX, targetY, targetZ)) return false;
        double eyeY = playerY + 1.62;
        try {
            // Match Zenith's aim point when ranking approaches. This cached raycast is
            // advisory; the scanner still probes reachable halves and confirms the GUI.
            var center = World.blockInteractionCenter(targetX, targetY, targetZ);
            if (!knownLine(playerX, eyeY, playerZ, center.x(), center.y(), center.z())) {
                return false;
            }
            var hit = RaycastHelper.blockRaycast(
                    playerX, eyeY, playerZ, center.x(), center.y(), center.z(), true);
            return hit.hit() && hit.x() == targetX && hit.y() == targetY && hit.z() == targetZ;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** A double chest may be opened through either verified half of the same inventory. */
    public static BlockPos visibleInteractionHalf(double playerX, double playerY, double playerZ,
                                                  int targetX, int targetY, int targetZ) {
        if (visible(playerX, playerY, playerZ, targetX, targetY, targetZ)) {
            return new BlockPos(targetX, targetY, targetZ);
        }
        if (!known(targetX, targetY, targetZ)) return null;
        try {
            if (!DoubleChestIdentity.isDoubleChest(targetX, targetY, targetZ)) return null;
            var identity = DoubleChestIdentity.resolve(targetX, targetY, targetZ, true);
            if (!identity.identityKnown()) return null;
            for (int[] half : identity.blocks()) {
                if (half[0] == targetX && half[1] == targetY && half[2] == targetZ) continue;
                if (visible(playerX, playerY, playerZ, half[0], half[1], half[2])) {
                    return new BlockPos(half[0], half[1], half[2]);
                }
            }
        } catch (RuntimeException ignored) {
            // Unknown or changing chest geometry is not a safe substitute for the target.
        }
        return null;
    }

    public static List<BlockPos> alternateStances(double playerX, double playerY, double playerZ,
                                                   int targetX, int targetY, int targetZ,
                                                   Set<Long> rejectedStances) {
        List<BlockPos> result = new ArrayList<>();
        for (int y = targetY - 2; y <= targetY + 2; y++) {
            for (int x = targetX - 3; x <= targetX + 3; x++) {
                for (int z = targetZ - 3; z <= targetZ + 3; z++) {
                    long key = ContainerOpenReach.stanceKey(x + 0.5, y, z + 0.5);
                    if (rejectedStances.contains(key) || !safeStandingCell(x, y, z, targetY)) continue;
                    BlockPos half = visibleInteractionHalf(x + 0.5, y, z + 0.5,
                            targetX, targetY, targetZ);
                    if (half == null || ContainerOpenReach.band(x + 0.5, y, z + 0.5,
                            half.x(), half.y(), half.z()) != ContainerOpenReach.Band.CLOSE) continue;
                    result.add(new BlockPos(x, y, z));
                }
            }
        }
        result.sort(Comparator.comparingDouble(pos ->
                distanceSquared(playerX, playerY, playerZ,
                        pos.x() + 0.5, pos.y(), pos.z() + 0.5)));
        return result;
    }

    private static boolean safeStandingCell(int x, int y, int z, int targetY) {
        if (!known(x, y - 1, z) || !known(x, y, z) || !known(x, y + 1, z)) return false;
        try {
            var floor = World.getBlock(x, y - 1, z);
            boolean ordinaryFloor = BlockCompat.isSolid(x, y - 1, z)
                    && !BlockCompat.isInteractable(floor);
            // Baritone explicitly permits walking on chest tops. In stacked storage,
            // excluding every chest floor can leave no reachable view of the next row.
            boolean lowerChestFloor = lowerChestFloor(floor.name(), y - 1, targetY)
                    && MovementHelper.canWalkOn(x, y - 1, z);
            return (ordinaryFloor || lowerChestFloor)
                    && BlockCompat.isAir(World.getBlock(x, y, z))
                    && BlockCompat.isAir(World.getBlock(x, y + 1, z))
                    && World.getFluidState(x, y, z) == null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean lowerChestFloor(String blockName, int floorY, int targetY) {
        return floorY < targetY
                && (blockName.equals("chest") || blockName.equals("trapped_chest"));
    }

    private static boolean knownLine(double x1, double y1, double z1,
                                     double x2, double y2, double z2) {
        for (int x = (int) Math.floor(Math.min(x1, x2)); x <= Math.floor(Math.max(x1, x2)); x++) {
            for (int y = (int) Math.floor(Math.min(y1, y2)); y <= Math.floor(Math.max(y1, y2)); y++) {
                for (int z = (int) Math.floor(Math.min(z1, z2)); z <= Math.floor(Math.max(z1, z2)); z++) {
                    if (!known(x, y, z)) return false;
                }
            }
        }
        return true;
    }

    private static boolean known(int x, int y, int z) {
        return World.isInWorldBounds(x, y, z) && World.isChunkLoadedBlockPos(x, z)
                && World.getChunkSection(x, y, z) != null;
    }

    private static double distanceSquared(double x1, double y1, double z1,
                                          double x2, double y2, double z2) {
        double dx = x1 - x2, dy = y1 - y2, dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }
}
