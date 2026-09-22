package com.zenith.plugin.stashmanager.organizer;

import com.zenith.plugin.stashmanager.util.ItemIdentifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Selects the original player-inventory slots that an organization job may not move. */
final class InventoryKeepPolicy {

    record SlotStack(int slot, String itemId, int amount, boolean customPresentation) {
        SlotStack(int slot, String itemId, int amount) {
            this(slot, itemId, amount, false);
        }
    }

    private final Map<String, Integer> limitsByBaseItemId;

    private InventoryKeepPolicy(Map<String, Integer> limitsByBaseItemId) {
        this.limitsByBaseItemId = Collections.unmodifiableMap(limitsByBaseItemId);
    }

    static InventoryKeepPolicy from(Map<String, Integer> configuredLimits) {
        Map<String, Integer> normalized = new LinkedHashMap<>();
        if (configuredLimits != null) {
            configuredLimits.forEach((itemId, limit) -> {
                String baseItemId = ItemIdentifier.baseItemId(itemId);
                if (baseItemId == null || baseItemId.isBlank()) return;
                Integer safeLimit = limit == null ? null : Math.max(0, limit);
                if (!normalized.containsKey(baseItemId)) {
                    normalized.put(baseItemId, safeLimit);
                    return;
                }
                Integer existing = normalized.get(baseItemId);
                // Multiple variant rules collapse conservatively onto the base item. An
                // unlimited rule wins; otherwise keep the larger configured quantity.
                normalized.put(baseItemId,
                        existing == null || safeLimit == null
                                ? null
                                : Math.max(existing, safeLimit));
            });
        }
        return new InventoryKeepPolicy(normalized);
    }

    static InventoryKeepPolicy empty() {
        return new InventoryKeepPolicy(Map.of());
    }

    boolean isEmpty() {
        return limitsByBaseItemId.isEmpty();
    }

    Set<Integer> protectedSlots(Iterable<SlotStack> inventory) {
        return protectedSlots(inventory, null, 0);
    }

    /**
     * Protect the configured inventory while leaving whole stacks owned by the active
     * organizer transaction movable. Cargo ownership wins only for the exact task item;
     * enchanted/tool variants that share a base keep rule remain protected.
     */
    Set<Integer> protectedSlots(
            Iterable<SlotStack> inventory,
            String cargoItemId,
            int cargoUnits) {
        List<SlotStack> ordered = new ArrayList<>();
        if (inventory != null) inventory.forEach(ordered::add);
        ordered.sort(java.util.Comparator.comparingInt(SlotStack::slot));

        String cargoBaseItemId = ItemIdentifier.baseItemId(cargoItemId);
        Set<Integer> movableCargoSlots = selectMovableCargoSlots(
                ordered, cargoItemId, Math.max(0, cargoUnits));

        // A finite rule such as `diamond_sword = 1` applies to every sword regardless of
        // display name. Prefer the personalized stack when deciding which whole stack fills
        // that quota; otherwise a generic sword in a lower slot can leave the named sword
        // exposed to an inventory-recovery sweep.
        List<SlotStack> keepPriority = new ArrayList<>(ordered);
        keepPriority.sort(java.util.Comparator
                .comparing(SlotStack::customPresentation).reversed()
                .thenComparingInt(SlotStack::slot));

        Map<String, Integer> keptByBaseItemId = new HashMap<>();
        Set<Integer> protectedSlots = new TreeSet<>();
        for (SlotStack stack : keepPriority) {
            if (stack == null || stack.slot() < 0 || stack.amount() <= 0) continue;
            String baseItemId = ItemIdentifier.baseItemId(stack.itemId());
            if (!limitsByBaseItemId.containsKey(baseItemId)) continue;

            if (cargoUnits > 0 && baseItemId.equals(cargoBaseItemId)) {
                // During a cargo handoff, protect every matching keep-list stack except the
                // exact whole stacks selected to satisfy the transaction ledger. This avoids
                // moving a pre-existing surplus merely because the keep limit is lower.
                if (!movableCargoSlots.contains(stack.slot())) protectedSlots.add(stack.slot());
                continue;
            }

            Integer limit = limitsByBaseItemId.get(baseItemId);
            if (limit == null) {
                protectedSlots.add(stack.slot());
                continue;
            }

            int kept = keptByBaseItemId.getOrDefault(baseItemId, 0);
            if (kept >= limit) continue;

            // Inventory shift-clicks move whole stacks. If this stack crosses the requested
            // quantity, retain the whole stack instead of moving protected items with it.
            protectedSlots.add(stack.slot());
            keptByBaseItemId.put(baseItemId, kept + stack.amount());
        }
        return Set.copyOf(protectedSlots);
    }

    /** Infer cargo from a legacy checkpoint that predates persisted transaction counts. */
    int inferredMovableUnits(String cargoItemId, Iterable<SlotStack> inventory) {
        String cargoBaseItemId = ItemIdentifier.baseItemId(cargoItemId);
        if (cargoBaseItemId == null || !limitsByBaseItemId.containsKey(cargoBaseItemId)) return 0;
        Integer limit = limitsByBaseItemId.get(cargoBaseItemId);
        if (limit == null) return 0;

        int matchingBaseUnits = 0;
        int exactCargoUnits = 0;
        if (inventory != null) {
            for (SlotStack stack : inventory) {
                if (stack == null || stack.amount() <= 0) continue;
                if (cargoBaseItemId.equals(ItemIdentifier.baseItemId(stack.itemId()))) {
                    matchingBaseUnits += stack.amount();
                }
                if (cargoItemId != null && cargoItemId.equals(stack.itemId())) {
                    exactCargoUnits += stack.amount();
                }
            }
        }
        return Math.min(exactCargoUnits, Math.max(0, matchingBaseUnits - limit));
    }

    private static Set<Integer> selectMovableCargoSlots(
            List<SlotStack> inventory,
            String cargoItemId,
            int cargoUnits) {
        if (cargoItemId == null || cargoUnits <= 0) return Set.of();

        // Shift-click moves whole stacks. Pick the largest deterministic subset that does not
        // exceed the owned cargo count; any unsplittable remainder stays protected and causes
        // a safe, explicit handoff failure instead of consuming bot-kit inventory.
        record Selection(List<Integer> slots, int customStacks) {}
        Map<Integer, Selection> byUnits = new HashMap<>();
        byUnits.put(0, new Selection(List.of(), 0));
        for (SlotStack stack : inventory) {
            if (stack == null || stack.amount() <= 0 || !cargoItemId.equals(stack.itemId())) continue;
            Map<Integer, Selection> additions = new HashMap<>();
            for (var candidate : byUnits.entrySet()) {
                int total = candidate.getKey() + stack.amount();
                if (total > cargoUnits) continue;
                List<Integer> slots = new ArrayList<>(candidate.getValue().slots());
                slots.add(stack.slot());
                Selection proposed = new Selection(
                        List.copyOf(slots),
                        candidate.getValue().customStacks() + (stack.customPresentation() ? 1 : 0));
                Selection current = additions.getOrDefault(total, byUnits.get(total));
                if (current == null || proposed.customStacks() < current.customStacks()) {
                    additions.put(total, proposed);
                }
            }
            additions.forEach((units, selection) -> {
                Selection current = byUnits.get(units);
                if (current == null || selection.customStacks() < current.customStacks()) {
                    byUnits.put(units, selection);
                }
            });
        }
        int selectedUnits = byUnits.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        Selection selected = byUnits.get(selectedUnits);
        return selected == null ? Set.of() : Set.copyOf(selected.slots());
    }
}
