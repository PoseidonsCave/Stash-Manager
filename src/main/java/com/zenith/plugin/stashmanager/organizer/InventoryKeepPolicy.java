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

    record SlotStack(int slot, String itemId, int amount) {}

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
        List<SlotStack> ordered = new ArrayList<>();
        if (inventory != null) inventory.forEach(ordered::add);
        ordered.sort(java.util.Comparator.comparingInt(SlotStack::slot));

        Map<String, Integer> keptByBaseItemId = new HashMap<>();
        Set<Integer> protectedSlots = new TreeSet<>();
        for (SlotStack stack : ordered) {
            if (stack == null || stack.slot() < 0 || stack.amount() <= 0) continue;
            String baseItemId = ItemIdentifier.baseItemId(stack.itemId());
            if (!limitsByBaseItemId.containsKey(baseItemId)) continue;

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
}
