package com.zenith.plugin.stashmanager.organizer;

import java.util.ArrayList;
import java.util.List;

/** Exact 3×3 grid moves for one vanilla shulker box. */
final class ShulkerCraftingPlan {
    record Source(int windowSlot, String itemId, int amount) {}
    record Placement(int sourceWindowSlot, List<Integer> gridSlots, boolean returnRemainder) {
        Placement {
            gridSlots = List.copyOf(gridSlots);
        }
    }

    private ShulkerCraftingPlan() {}

    static List<Placement> plan(List<Source> sources) {
        int shells = 0;
        boolean chest = false;
        List<Placement> moves = new ArrayList<>();
        for (Source source : sources) {
            if (source == null || source.amount() <= 0) continue;
            List<Integer> targets;
            if ("shulker_shell".equals(source.itemId()) && shells < 2) {
                targets = shells == 0 ? List.of(2, 8) : List.of(8);
            } else if ("chest".equals(source.itemId()) && !chest) {
                targets = List.of(5);
            } else continue;
            int placing = Math.min(source.amount(), targets.size());
            moves.add(new Placement(source.windowSlot(), targets.subList(0, placing),
                    source.amount() > placing));
            if ("shulker_shell".equals(source.itemId())) shells += placing;
            else chest = true;
            if (shells == 2 && chest) return List.copyOf(moves);
        }
        return List.of();
    }
}
