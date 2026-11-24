package com.zenith.plugin.stashmanager.kit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Conservative, one-level planning for explicitly allowed kit crafting recipes. */
public final class KitCraftingPlan {
    private static final Map<String, Map<String, Integer>> RECIPES = Map.of(
            "respawn_anchor", Map.of("crying_obsidian", 6, "glowstone", 3));

    private KitCraftingPlan() { }

    public record Assessment(Map<String, Long> toCraft,
                             Map<String, Long> totalDemand,
                             Map<String, Long> shortages) {
        public Assessment {
            toCraft = Collections.unmodifiableMap(new LinkedHashMap<>(toCraft));
            totalDemand = Collections.unmodifiableMap(new LinkedHashMap<>(totalDemand));
            shortages = Collections.unmodifiableMap(new LinkedHashMap<>(shortages));
        }

        public boolean feasible() { return shortages.isEmpty(); }
    }

    /** Missing outputs may be crafted only when enabled; direct kit use of inputs is reserved first. */
    public static Assessment assess(Map<String, Integer> recipe, int boxes,
                                    Map<String, Long> indexedStock, boolean enabled) {
        Map<String, Long> totalDemand = new LinkedHashMap<>();
        if (recipe != null && boxes > 0) {
            recipe.forEach((itemId, perBox) -> {
                if (itemId != null && perBox != null && perBox > 0) {
                    totalDemand.merge(itemId, (long) perBox * boxes, Long::sum);
                }
            });
        }

        Map<String, Long> toCraft = new LinkedHashMap<>();
        if (enabled) {
            for (var output : RECIPES.entrySet()) {
                long needed = totalDemand.getOrDefault(output.getKey(), 0L);
                long available = stock(indexedStock, output.getKey());
                long missing = Math.max(0L, needed - available);
                if (missing == 0) continue;
                toCraft.put(output.getKey(), missing);
                totalDemand.put(output.getKey(), needed - missing);
                output.getValue().forEach((input, amount) ->
                        totalDemand.merge(input, Math.multiplyExact(missing, amount), Math::addExact));
            }
        }

        Map<String, Long> shortages = new LinkedHashMap<>();
        totalDemand.forEach((itemId, needed) -> {
            long missing = Math.max(0L, needed - stock(indexedStock, itemId));
            if (missing > 0) shortages.put(itemId, missing);
        });
        return new Assessment(toCraft, totalDemand, shortages);
    }

    public static Map<String, Integer> recipeFor(String outputItemId) {
        return RECIPES.getOrDefault(outputItemId, Map.of());
    }

    private static long stock(Map<String, Long> indexedStock, String itemId) {
        return indexedStock == null ? 0L : Math.max(0L, indexedStock.getOrDefault(itemId, 0L));
    }
}
