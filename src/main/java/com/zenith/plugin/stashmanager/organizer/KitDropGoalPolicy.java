package com.zenith.plugin.stashmanager.organizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Selects a safe pickup goal from newly observed drops without claiming item ownership. */
final class KitDropGoalPolicy {
    private static final double MAX_WORKSITE_DISTANCE_SQ = 36.0;
    private static final int[][] STANDING_OFFSETS = {
            {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    record Drop(int entityId, int itemType, Map<String, Integer> contents,
                double x, double y, double z) {
        Drop {
            contents = contents == null ? Map.of() : Map.copyOf(contents);
        }
    }

    private KitDropGoalPolicy() { }

    static int[] target(List<Drop> observed, Set<Integer> beforeBreak,
                        int expectedItemType, Map<String, Integer> expectedContents,
                        int[] worksite, Predicate<int[]> safeStandingCell) {
        if (observed == null || beforeBreak == null || expectedItemType < 0
                || expectedContents == null || worksite == null || worksite.length != 3
                || safeStandingCell == null) return null;

        List<Drop> exact = new ArrayList<>();
        List<Drop> staleEmpty = new ArrayList<>();
        for (Drop drop : observed) {
            if (drop == null || beforeBreak.contains(drop.entityId())
                    || drop.itemType() != expectedItemType
                    || !Double.isFinite(drop.x()) || !Double.isFinite(drop.y())
                    || !Double.isFinite(drop.z())) continue;
            double dx = drop.x() - (worksite[0] + 0.5);
            double dy = drop.y() - (worksite[1] + 0.5);
            double dz = drop.z() - (worksite[2] + 0.5);
            if (dx * dx + dy * dy + dz * dz > MAX_WORKSITE_DISTANCE_SQ) continue;
            if (drop.contents().equals(expectedContents)) exact.add(drop);
            else if (drop.contents().isEmpty()) staleEmpty.add(drop);
        }

        // Metadata on a mined box can still describe its pre-fill empty state. That is
        // useful for navigation, but never for confirming the pickup or its contents.
        Drop candidate = exact.size() == 1 ? exact.getFirst()
                : exact.isEmpty() && staleEmpty.size() == 1 ? staleEmpty.getFirst() : null;
        if (candidate == null) return null;

        int x = (int) Math.floor(candidate.x());
        int y = (int) Math.floor(candidate.y());
        int z = (int) Math.floor(candidate.z());
        for (int level : new int[]{y, y - 1, y + 1}) {
            for (int[] offset : STANDING_OFFSETS) {
                int[] goal = {x + offset[0], level, z + offset[1]};
                if (safeStandingCell.test(goal)) return goal;
            }
        }
        return null;
    }
}
