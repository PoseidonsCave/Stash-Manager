package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.orchestration.LaneStorageCapacity;
import com.zenith.plugin.stashmanager.profile.ItemProfiles;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Conservative, item-exact capacity and stock checks before any kit cargo moves. */
public final class KitFulfillmentPlan {
    private KitFulfillmentPlan() { }

    public record Assessment(int copies, int slotsPerBox, Map<String, Long> shortages,
                             int missingEmptyBoxes, int missingExportSlots) {
        public Assessment {
            shortages = Collections.unmodifiableMap(new LinkedHashMap<>(shortages));
        }

        public boolean feasible() {
            return copies > 0 && slotsPerBox > 0 && slotsPerBox <= KitCapacity.MAX_SLOTS
                    && shortages.isEmpty() && missingEmptyBoxes == 0 && missingExportSlots == 0;
        }
    }

    public static Assessment assess(Map<String, Integer> recipe, int copies,
                                    Map<String, Long> indexedStock,
                                    int emptyBoxes, int freeExportSlots) {
        int slots = KitCapacity.requiredSlots(recipe);
        Map<String, Long> shortages = new LinkedHashMap<>();
        if (recipe != null && copies > 0) {
            for (var entry : recipe.entrySet()) {
                int quantity = entry.getValue() == null ? 0 : entry.getValue();
                if (quantity <= 0) continue;
                long required = (long) quantity * copies;
                long available = indexedStock == null ? 0L
                        : Math.max(0L, indexedStock.getOrDefault(entry.getKey(), 0L));
                if (available < required) shortages.put(entry.getKey(), required - available);
            }
        }
        return new Assessment(copies, slots, shortages,
                Math.max(0, copies - Math.max(0, emptyBoxes)),
                Math.max(0, copies - Math.max(0, freeExportSlots)));
    }

    /** Check shared stock once; separate kit checks can each spend the same indexed item. */
    public static Map<String, Long> sharedShortages(
            Map<String, Map<String, Integer>> recipes,
            Map<String, Integer> copiesByKit,
            Map<String, Long> indexedStock) {
        Map<String, Long> demand = new LinkedHashMap<>();
        if (recipes != null && copiesByKit != null) {
            for (var kit : recipes.entrySet()) {
                int copies = Math.max(0, copiesByKit.getOrDefault(kit.getKey(), 0));
                if (copies == 0 || kit.getValue() == null) continue;
                for (var item : kit.getValue().entrySet()) {
                    int quantity = item.getValue() == null ? 0 : Math.max(0, item.getValue());
                    if (quantity > 0) {
                        demand.merge(item.getKey(), (long) quantity * copies, Long::sum);
                    }
                }
            }
        }
        Map<String, Long> shortages = new LinkedHashMap<>();
        for (var item : demand.entrySet()) {
            long available = indexedStock == null ? 0L
                    : Math.max(0L, indexedStock.getOrDefault(item.getKey(), 0L));
            if (item.getValue() > available) {
                shortages.put(item.getKey(), item.getValue() - available);
            }
        }
        return Collections.unmodifiableMap(shortages);
    }

    /** Pick a bounded retrieval batch; never assume the bot can carry a full 27-slot kit. */
    public static Map<String, Integer> nextBatch(Map<String, Integer> remaining,
                                                  int freePlayerSlots) {
        if (remaining == null || remaining.isEmpty() || freePlayerSlots <= 0) return Map.of();
        Map<String, Integer> batch = new LinkedHashMap<>();
        int slots = freePlayerSlots;
        for (var entry : remaining.entrySet()) {
            int quantity = entry.getValue() == null ? 0 : entry.getValue();
            if (quantity <= 0) continue;
            int stack = Math.max(1, LaneStorageCapacity.itemCapacityFor(
                    ItemProfiles.baseItemId(entry.getKey())).maxStackSize());
            int taken = (int) Math.min((long) quantity, (long) slots * stack);
            if (taken <= 0) break;
            batch.put(entry.getKey(), taken);
            slots -= 1 + (taken - 1) / stack;
            if (slots <= 0) break;
        }
        return Collections.unmodifiableMap(batch);
    }

    /** A packed box must not be borrowed again to satisfy a second slice of the same item. */
    public static Map<String, Integer> nextWholeItemBatch(Map<String, Integer> remaining,
                                                           int freePlayerSlots) {
        if (remaining == null || remaining.isEmpty() || freePlayerSlots <= 0) return Map.of();
        Map<String, Integer> batch = new LinkedHashMap<>();
        int slots = freePlayerSlots;
        for (var entry : remaining.entrySet()) {
            int quantity = entry.getValue() == null ? 0 : entry.getValue();
            if (quantity <= 0) continue;
            int stack = Math.max(1, LaneStorageCapacity.itemCapacityFor(
                    ItemProfiles.baseItemId(entry.getKey())).maxStackSize());
            int neededSlots = 1 + (quantity - 1) / stack;
            if (neededSlots > slots) continue;
            batch.put(entry.getKey(), quantity);
            slots -= neededSlots;
            if (slots <= 0) break;
        }
        return Collections.unmodifiableMap(batch);
    }
}
