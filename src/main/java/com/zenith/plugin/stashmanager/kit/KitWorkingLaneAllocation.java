package com.zenith.plugin.stashmanager.kit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Slot-range plan for verified empty double chests; never persists or moves stock. */
public final class KitWorkingLaneAllocation {
    private KitWorkingLaneAllocation() { }

    public static final int DOUBLE_CHEST_SLOTS = 54;

    public record Chest(int x, int y, int z, boolean footprintKnown,
                        boolean verifiedEmpty, boolean roleFree) { }

    public record Range(String kit, Chest chest, int firstSlot, int slotCount) {
        public int lastSlot() { return firstSlot + slotCount - 1; }
    }

    public record Plan(long requestedSlots, int usableSlotsPerChest,
                       int eligibleChests, long missingSlots, List<Range> ranges) {
        public Plan { ranges = List.copyOf(ranges); }
        public boolean ready() { return requestedSlots > 0 && missingSlots == 0; }
    }

    public static Plan assess(Map<String, Long> slotsByKit, List<Chest> candidates,
                              int spareSlotsPerChest) {
        if (slotsByKit == null || slotsByKit.isEmpty() || candidates == null) {
            throw new IllegalArgumentException("kit demand and candidate chests are required");
        }
        if (spareSlotsPerChest < 0 || spareSlotsPerChest >= DOUBLE_CHEST_SLOTS) {
            throw new IllegalArgumentException("invalid per-chest headroom");
        }
        int usable = DOUBLE_CHEST_SLOTS - spareSlotsPerChest;
        long requested = 0;
        for (var demand : slotsByKit.entrySet()) {
            if (demand.getKey() == null || demand.getKey().isBlank()
                    || demand.getValue() == null || demand.getValue() < 1) {
                throw new IllegalArgumentException("invalid kit slot demand");
            }
            requested = Math.addExact(requested, demand.getValue());
        }
        Set<String> seen = new HashSet<>();
        List<Chest> eligible = new ArrayList<>();
        for (Chest chest : candidates) {
            if (chest == null) throw new IllegalArgumentException("null chest candidate");
            String key = chest.x() + ":" + chest.y() + ":" + chest.z();
            if (!seen.add(key)) throw new IllegalArgumentException("duplicate chest footprint");
            if (chest.footprintKnown() && chest.verifiedEmpty() && chest.roleFree()) {
                eligible.add(chest);
            }
        }
        long capacity = Math.multiplyExact((long) usable, eligible.size());
        if (capacity < requested) {
            return new Plan(requested, usable, eligible.size(), requested - capacity, List.of());
        }

        List<Range> ranges = new ArrayList<>();
        int[] used = new int[eligible.size()];
        for (var kit : slotsByKit.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder())).toList()) {
            long remaining = kit.getValue();
            while (remaining > 0) {
                int chosen = -1;
                // Keep a kit together if one chest can fit all its remaining boxes.
                if (remaining <= usable) {
                    for (int i = 0; i < eligible.size(); i++) {
                        if (usable - used[i] >= remaining) { chosen = i; break; }
                    }
                }
                if (chosen < 0) {
                    for (int i = 0; i < eligible.size(); i++) {
                        if (used[i] < usable) { chosen = i; break; }
                    }
                }
                if (chosen < 0) throw new IllegalStateException("capacity accounting changed");
                int taken = (int) Math.min(remaining, usable - used[chosen]);
                ranges.add(new Range(kit.getKey(), eligible.get(chosen), used[chosen], taken));
                used[chosen] += taken;
                remaining -= taken;
            }
        }
        return new Plan(requested, usable, eligible.size(), 0, ranges);
    }
}
