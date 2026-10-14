package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.orchestration.LaneStorageCapacity;
import com.zenith.plugin.stashmanager.profile.ItemProfiles;

import java.util.LinkedHashMap;
import java.util.Map;

/** Calculates the physical inventory slots used by a one-shulker kit. */
public final class KitCapacity {
    public static final int MAX_SLOTS = 27;

    private KitCapacity() {}

    public static int requiredSlots(Map<String, Integer> items) {
        long slots = 0;
        if (items != null) {
            for (var entry : items.entrySet()) {
                int quantity = entry.getValue() == null ? 0 : Math.max(0, entry.getValue());
                if (quantity == 0) continue;
                int stackSize = LaneStorageCapacity.itemCapacityFor(
                        ItemProfiles.baseItemId(entry.getKey())).maxStackSize();
                slots += 1L + (quantity - 1L) / Math.max(1, stackSize);
                if (slots > Integer.MAX_VALUE) return Integer.MAX_VALUE;
            }
        }
        return (int) slots;
    }

    public static boolean fitsOneShulker(Map<String, Integer> items) {
        return requiredSlots(items) <= MAX_SLOTS;
    }

    /** Keeps insertion order and trims only the tail that cannot fit physically. */
    public static Map<String, Integer> fitOneShulker(Map<String, Integer> items) {
        Map<String, Integer> fitted = new LinkedHashMap<>();
        if (items == null || items.isEmpty()) return fitted;

        int remainingSlots = MAX_SLOTS;
        for (var entry : items.entrySet()) {
            int quantity = entry.getValue() == null ? 0 : Math.max(0, entry.getValue());
            if (quantity == 0 || remainingSlots == 0) continue;
            int stackSize = LaneStorageCapacity.itemCapacityFor(
                    ItemProfiles.baseItemId(entry.getKey())).maxStackSize();
            long capacity = (long) remainingSlots * Math.max(1, stackSize);
            int kept = (int) Math.min(quantity, capacity);
            if (kept <= 0) continue;
            fitted.put(entry.getKey(), kept);
            remainingSlots -= 1 + (kept - 1) / Math.max(1, stackSize);
        }
        return fitted;
    }
}
