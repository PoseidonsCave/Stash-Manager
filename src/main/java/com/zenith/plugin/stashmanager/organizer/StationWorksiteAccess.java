package com.zenith.plugin.stashmanager.organizer;

import com.zenith.feature.player.World;
import com.zenith.feature.player.raycast.RaycastHelper;
import com.zenith.mc.block.LocalizedCollisionBox;
import com.zenith.mc.block.Direction;
import com.zenith.plugin.stashmanager.util.BlockCompat;

import java.util.ArrayList;
import java.util.List;

import static com.zenith.plugin.stashmanager.organizer.StationWalkRecovery.*;

/** Validate standing and interaction geometry without moving the fixed packing pad. */
final class StationWorksiteAccess {
    private static final double HALF_WIDTH = 0.3;
    private static final double EYE_HEIGHT = 1.62;
    private static final double REACH = 4.0;
    private static final double EPSILON = 0.001;

    private StationWorksiteAccess() { }

    static boolean loaded(Point worksite) {
        return worksite != null && known(worksite.x(), worksite.y() - 1, worksite.z())
                && known(worksite.x(), worksite.y() + 1, worksite.z());
    }

    static List<Point> candidates(Point worksite) {
        try {
            return findCandidates(worksite);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static List<Point> findCandidates(Point worksite) {
        if (!loaded(worksite)) return List.of();
        List<Point> candidates = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int x = worksite.x() + dx, y = worksite.y() + dy, z = worksite.z() + dz;
                    if (!known(x, y - 1, z)) continue;
                    for (var box : World.getBlockState(x, y - 1, z).getLocalizedCollisionBoxes()) {
                        Position feet = new Position(x + 0.5, box.maxY(), z + 0.5);
                        if (canWorkFrom(feet, worksite)) {
                            Point goal = navigationCell(feet);
                            if (!candidates.contains(goal)) candidates.add(goal);
                        }
                    }
                }
            }
        }
        return candidates;
    }

    static Point navigationCell(Position feet) {
        // Match Zenith's PlayerContext, including chest tops and bottom slabs.
        Point cell = new Point((int) Math.floor(feet.x()), (int) Math.floor(feet.y() + 0.1251),
                (int) Math.floor(feet.z()));
        if (known(cell.x(), cell.y(), cell.z())
                && World.getBlock(cell.x(), cell.y(), cell.z()).name().endsWith("_slab")) {
            return new Point(cell.x(), cell.y() + 1, cell.z());
        }
        return cell;
    }

    static boolean canWorkFrom(Position feet, Point worksite) {
        try {
            return verifiedAccess(feet, worksite);
        } catch (RuntimeException ignored) {
            // A chunk update or incomplete palette cannot prove a safe standing position.
            return false;
        }
    }

    private static boolean verifiedAccess(Position feet, Point worksite) {
        if (feet == null || !feet.finite() || !loaded(worksite)) return false;
        if (feet.distance(worksite) > 5.0) return false;
        // Stay clear of the box and its lid, including before placement.
        if (feet.x() + HALF_WIDTH > worksite.x() && feet.x() - HALF_WIDTH < worksite.x() + 1
                && feet.z() + HALF_WIDTH > worksite.z() && feet.z() - HALF_WIDTH < worksite.z() + 1) return false;
        if (!standingSafe(feet)) return false;
        var block = World.getBlock(worksite.x(), worksite.y(), worksite.z());
        if (block.name().contains("shulker_box")) {
            return visible(feet, worksite, worksite.y() + 0.5, false, false);
        }
        if (!BlockCompat.isAir(block)) return false;
        Point support = new Point(worksite.x(), worksite.y() - 1, worksite.z());
        // Check both the placement face and the future box's interaction line.
        return visible(feet, support, worksite.y() - EPSILON, false, true)
                && visible(feet, worksite, worksite.y() + 0.5, true, false);
    }

    private static boolean standingSafe(Position feet) {
        var body = new LocalizedCollisionBox(feet.x() - HALF_WIDTH, feet.x() + HALF_WIDTH,
                feet.y() + EPSILON, feet.y() + 1.8,
                feet.z() - HALF_WIDTH, feet.z() + HALF_WIDTH, feet.x(), feet.y(), feet.z());
        for (int x = (int) Math.floor(body.minX()); x <= Math.floor(body.maxX()); x++) {
            for (int z = (int) Math.floor(body.minZ()); z <= Math.floor(body.maxZ()); z++) {
                for (int y = (int) Math.floor(feet.y() - EPSILON); y <= Math.floor(body.maxY()); y++) {
                    if (!known(x, y, z)) return false;
                    var block = World.getBlock(x, y, z);
                    if (!safeBlock(block.name()) || World.getFluidState(x, y, z) != null) return false;
                    for (var box : World.getBlockState(x, y, z).getLocalizedCollisionBoxes()) {
                        if (box.intersects(body)) return false;
                    }
                }
            }
        }
        int x = (int) Math.floor(feet.x()), y = (int) Math.floor(feet.y() - EPSILON),
                z = (int) Math.floor(feet.z());
        if (World.getBlock(x, y, z).name().contains("shulker_box")) return false;
        for (var floor : World.getBlockState(x, y, z).getLocalizedCollisionBoxes()) {
            if (Math.abs(floor.maxY() - feet.y()) <= EPSILON
                    // Vanilla can stand across two chest halves or overhang an edge. Require
                    // support under the center, not one box covering the entire player width.
                    && floor.minX() + EPSILON < feet.x() && floor.maxX() - EPSILON > feet.x()
                    && floor.minZ() + EPSILON < feet.z() && floor.maxZ() - EPSILON > feet.z()) return true;
        }
        return false;
    }

    private static boolean safeBlock(String name) {
        return StationWalkRecovery.safeFloor(name) && !name.contains("fire")
                && !name.contains("berry_bush") && !name.contains("wither_rose")
                && !name.contains("cobweb") && !name.contains("pointed_dripstone");
    }

    private static boolean visible(Position feet, Point target, double hitY, boolean expectAir, boolean requireTop) {
        double eyeY = feet.y() + EYE_HEIGHT, hitX = target.x() + 0.5, hitZ = target.z() + 0.5;
        double distanceSq = Math.pow(feet.x() - hitX, 2) + Math.pow(eyeY - hitY, 2)
                + Math.pow(feet.z() - hitZ, 2);
        if (distanceSq > REACH * REACH) return false;
        // Raycasts treat missing chunks as air; never authorize a handoff through them.
        for (int x = (int) Math.floor(Math.min(feet.x(), hitX)); x <= Math.floor(Math.max(feet.x(), hitX)); x++) {
            for (int z = (int) Math.floor(Math.min(feet.z(), hitZ)); z <= Math.floor(Math.max(feet.z(), hitZ)); z++) {
                for (int y = (int) Math.floor(Math.min(eyeY, hitY)); y <= Math.floor(Math.max(eyeY, hitY)); y++) {
                    if (!known(x, y, z)) return false;
                }
            }
        }
        var hit = RaycastHelper.blockRaycast(feet.x(), eyeY, feet.z(), hitX, hitY, hitZ, true);
        return expectAir ? !hit.hit()
                : hit.hit() && hit.x() == target.x() && hit.y() == target.y() && hit.z() == target.z()
                        && (!requireTop || hit.direction() == Direction.UP);
    }

    private static boolean known(int x, int y, int z) {
        return World.isInWorldBounds(x, y, z) && World.isChunkLoadedBlockPos(x, z)
                && World.getChunkSection(x, y, z) != null;
    }
}
