package com.zenith.plugin.stashmanager.orchestration;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Pure planning rules for decomposing mixed/kit shulkers into exact-item bulk storage. */
public final class MixedShulkerPlaybook {
    private MixedShulkerPlaybook() {}

    /** Counts every mixed-box item as loose demand that reconciliation must repack. */
    public static Map<String, Long> aggregateDemand(
            Collection<? extends Map<String, Integer>> mixedContents) {
        Map<String, Long> demand = new TreeMap<>();
        if (mixedContents == null) return Map.of();
        for (Map<String, Integer> contents : mixedContents) {
            if (contents == null) continue;
            contents.forEach((rawItemId, rawQuantity) -> {
                String storageClass = StorageClassPolicy.exact(rawItemId);
                int quantity = rawQuantity == null ? 0 : Math.max(0, rawQuantity);
                if (storageClass != null && quantity > 0) {
                    demand.merge(storageClass, (long) quantity, MixedShulkerPlaybook::saturatingAdd);
                }
            });
        }
        return new LinkedHashMap<>(demand);
    }

    /** Minimum slots needed after normal stack compaction; live staging may use more. */
    public static int minimumStagingSlots(Map<String, Integer> contents) {
        long slots = 0;
        for (var entry : aggregateDemand(contents == null ? null : java.util.List.of(contents)).entrySet()) {
            int stackSize = LaneStorageCapacity.itemCapacityFor(entry.getKey()).maxStackSize();
            slots = saturatingAdd(slots, ceilingDivision(entry.getValue(), stackSize));
        }
        return slots > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) slots;
    }

    private static long ceilingDivision(long value, int divisor) {
        if (value <= 0) return 0;
        return 1L + (value - 1L) / Math.max(1, divisor);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
