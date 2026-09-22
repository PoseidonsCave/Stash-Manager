package com.zenith.plugin.stashmanager.orchestration;

import com.zenith.mc.food.FoodData;
import com.zenith.mc.food.FoodRegistry;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;

import java.util.LinkedHashMap;
import java.util.Map;

/** Converts finite keep-list food rules into safe inventory refill requests. */
public final class FoodContingencyPolicy {
    private FoodContingencyPolicy() {}

    public record Plan(
            Map<String, Integer> requested,
            int configuredFoodTypes,
            int currentFoodUnits,
            int targetFoodUnits) {
        public Plan {
            requested = requested == null ? Map.of() : Map.copyOf(requested);
        }

        public boolean configured() {
            return configuredFoodTypes > 0;
        }

        public boolean hasFood() {
            return currentFoodUnits > 0;
        }

        public boolean needsRefill() {
            return !requested.isEmpty();
        }
    }

    public static Plan plan(
            Map<String, Integer> keepItems,
            Map<String, Integer> inventoryItems) {
        Map<String, Integer> inventory = normalizeCounts(inventoryItems);
        Map<String, Integer> requested = new LinkedHashMap<>();
        int configuredTypes = 0;
        int currentUnits = 0;
        int targetUnits = 0;

        Map<String, Integer> normalizedRules = normalizeRules(keepItems);
        for (var entry : normalizedRules.entrySet()) {
            String itemId = entry.getKey();
            FoodData food = FoodRegistry.REGISTRY.get(itemId);
            if (food == null || !food.isSafeFood()) continue;

            configuredTypes++;
            int current = Math.max(0, inventory.getOrDefault(itemId, 0));
            currentUnits += current;
            Integer target = entry.getValue();
            // An unlimited keep rule protects existing food but cannot safely define how
            // much stock an automatic retrieval should remove from the stash.
            if (target == null || target <= 0) continue;
            targetUnits += target;
            int missing = Math.max(0, target - current);
            if (missing > 0) requested.put(itemId, missing);
        }
        return new Plan(requested, configuredTypes, currentUnits, targetUnits);
    }

    private static Map<String, Integer> normalizeRules(Map<String, Integer> rules) {
        Map<String, Integer> normalized = new LinkedHashMap<>();
        if (rules == null) return normalized;
        rules.forEach((rawItemId, rawLimit) -> {
            String itemId = ItemIdentifier.baseItemId(rawItemId);
            if (itemId == null || itemId.isBlank()) return;
            Integer limit = rawLimit == null ? null : Math.max(0, rawLimit);
            if (!normalized.containsKey(itemId)) {
                normalized.put(itemId, limit);
                return;
            }
            Integer existing = normalized.get(itemId);
            normalized.put(itemId, existing == null || limit == null
                    ? null
                    : Math.max(existing, limit));
        });
        return normalized;
    }

    private static Map<String, Integer> normalizeCounts(Map<String, Integer> counts) {
        Map<String, Integer> normalized = new LinkedHashMap<>();
        if (counts == null) return normalized;
        counts.forEach((rawItemId, rawCount) -> {
            String itemId = ItemIdentifier.baseItemId(rawItemId);
            if (itemId == null || itemId.isBlank() || rawCount == null || rawCount <= 0) return;
            normalized.merge(itemId, rawCount, Integer::sum);
        });
        return normalized;
    }
}
