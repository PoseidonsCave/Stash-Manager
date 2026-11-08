package com.zenith.plugin.stashmanager.retriever;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Bounded navigation to a newly spawned borrowed-source box; never proves ownership. */
final class BorrowedShulkerDropGoal {
    private static final double MAX_DISTANCE_SQ = 36.0;
    private static final int[][] STANDING_OFFSETS = {
            {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    record Drop(int entityId, String itemId, Map<String, Integer> contents,
                double x, double y, double z) {
        Drop {
            contents = contents == null ? Map.of() : Map.copyOf(contents);
        }
    }

    record Target(int entityId, int[] standing, String metadataKind) { }

    private BorrowedShulkerDropGoal() { }

    static Target select(List<Drop> observed, Set<Integer> beforeBreak,
                         String expectedItemId, Map<String, Integer> expectedContents,
                         Map<String, Integer> contentsBeforeTake, int[] worksite,
                         Predicate<int[]> safeStandingCell) {
        if (observed == null || beforeBreak == null || expectedItemId == null
                || expectedContents == null || contentsBeforeTake == null
                || worksite == null || worksite.length != 3 || safeStandingCell == null) return null;

        List<Drop> exact = new ArrayList<>();
        List<Drop> stale = new ArrayList<>();
        for (Drop drop : observed) {
            if (drop == null || beforeBreak.contains(drop.entityId())
                    || !expectedItemId.equals(drop.itemId())
                    || !Double.isFinite(drop.x()) || !Double.isFinite(drop.y())
                    || !Double.isFinite(drop.z())) continue;
            double dx = drop.x() - (worksite[0] + 0.5);
            double dy = drop.y() - (worksite[1] + 0.5);
            double dz = drop.z() - (worksite[2] + 0.5);
            if (dx * dx + dy * dy + dz * dz > MAX_DISTANCE_SQ) continue;
            if (drop.contents().equals(expectedContents)) exact.add(drop);
            else if (drop.contents().isEmpty()
                    || drop.contents().equals(contentsBeforeTake)) stale.add(drop);
        }
        // Empty or pre-take metadata can guide movement, but only an exact inventory box
        // can complete the handoff. Ambiguous drops are never selected.
        Drop selected = exact.size() == 1 ? exact.getFirst()
                : exact.isEmpty() && stale.size() == 1 ? stale.getFirst() : null;
        if (selected == null) return null;

        int x = (int) Math.floor(selected.x());
        int y = (int) Math.floor(selected.y());
        int z = (int) Math.floor(selected.z());
        for (int level : new int[]{y, y - 1, y + 1}) {
            for (int[] offset : STANDING_OFFSETS) {
                int[] goal = {x + offset[0], level, z + offset[1]};
                if (safeStandingCell.test(goal)) {
                    return new Target(selected.entityId(), goal,
                            selected.contents().equals(expectedContents) ? "exact" : "stale");
                }
            }
        }
        return null;
    }
}
