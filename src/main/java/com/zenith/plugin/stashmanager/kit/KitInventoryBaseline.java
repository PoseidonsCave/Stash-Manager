package com.zenith.plugin.stashmanager.kit;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

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

    public record IncomingCargo(Set<Integer> slots, int units,
                                Set<Integer> relocatedBaselineSlots) {
        public IncomingCargo {
            slots = Set.copyOf(slots);
            relocatedBaselineSlots = Set.copyOf(relocatedBaselineSlots);
        }

        public IncomingCargo(Set<Integer> slots, int units) {
            this(slots, units, Set.of());
        }
    }

    /**
     * A retrieved item absent from the baseline owns every live stack of that item, even
     * when an unrelated kept stack was moved out of the slot during shulker pickup.
     * If the item was already present, retain the slot boundary except for a
     * uniquely identifiable moved baseline stack.
     */
    public static IncomingCargo incomingCargo(Map<Integer, Stack> before,
                                              Map<Integer, Stack> after, String itemId) {
        if (itemId == null || after == null) return new IncomingCargo(Set.of(), 0);
        Map<Integer, Stack> baseline = before == null ? Map.of() : before;
        boolean itemAlreadyHeld = baseline.values().stream().anyMatch(stack ->
                stack != null && itemId.equals(stack.itemId()) && stack.amount() > 0);
        Set<Integer> slots = new HashSet<>();
        int units = 0;
        for (var entry : after.entrySet()) {
            Stack stack = entry.getValue();
            if (stack == null || stack.amount() <= 0
                    || !itemId.equals(stack.itemId())
                    || itemAlreadyHeld && baseline.containsKey(entry.getKey())) continue;
            slots.add(entry.getKey());
            units = Math.addExact(units, stack.amount());
        }
        // A protected stack can be shifted into an empty slot while a borrowed shulker
        // occupies its old slot. Rebind only a unique, exact-quantity match. Ambiguous
        // candidates remain unverified and the caller's exact-quantity gate will stop.
        Set<Integer> relocated = new HashSet<>();
        for (var entry : baseline.entrySet()) {
            Stack previous = entry.getValue();
            Stack originalSlotNow = after.get(entry.getKey());
            if (previous == null || !itemId.equals(previous.itemId())
                    || previous.amount() <= 0
                    || (originalSlotNow != null && itemId.equals(originalSlotNow.itemId()))) continue;
            int match = -1;
            for (int raw : slots) {
                if (relocated.contains(raw)) continue;
                Stack candidate = after.get(raw);
                if (candidate.amount() != previous.amount()) continue;
                if (match >= 0) { match = -1; break; }
                match = raw;
            }
            if (match >= 0) {
                relocated.add(match);
                units -= previous.amount();
            }
        }
        slots.removeAll(relocated);
        return new IncomingCargo(slots, units, relocated);
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
