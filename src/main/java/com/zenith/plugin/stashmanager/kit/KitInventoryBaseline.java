package com.zenith.plugin.stashmanager.kit;

import java.util.HashMap;
import java.util.Map;

/** Preserve pre-existing loose items by identity and total, even when slots are rearranged. */
public final class KitInventoryBaseline {
    private KitInventoryBaseline() { }

    public record Stack(String itemId, int amount) { }

    public record Result(Map<String, Integer> expected, Map<String, Integer> observed) {
        public Result {
            expected = Map.copyOf(expected);
            observed = Map.copyOf(observed);
        }

        public boolean preserved() {
            return expected.equals(observed);
        }
    }

    /** Shulkers are verified separately by their full content-identity ledger. */
    public static Result compare(Map<Integer, Stack> before, Map<Integer, Stack> after,
                                 String retrievedItem, int retrievedQuantity) {
        Map<String, Integer> expected = totals(before);
        Map<String, Integer> observed = totals(after);
        if (retrievedItem != null && retrievedQuantity > 0) {
            expected.merge(retrievedItem, retrievedQuantity, Integer::sum);
        }
        return new Result(expected, observed);
    }

    private static Map<String, Integer> totals(Map<Integer, Stack> slots) {
        Map<String, Integer> totals = new HashMap<>();
        if (slots == null) return totals;
        for (Stack stack : slots.values()) {
            if (stack == null || stack.itemId() == null || stack.itemId().contains("shulker_box")
                    || stack.amount() <= 0) continue;
            totals.merge(stack.itemId(), stack.amount(), Integer::sum);
        }
        return totals;
    }
}
