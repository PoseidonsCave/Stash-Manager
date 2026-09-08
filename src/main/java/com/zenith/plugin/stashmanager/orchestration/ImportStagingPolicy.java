package com.zenith.plugin.stashmanager.orchestration;

import com.zenith.plugin.stashmanager.index.ContainerEntry;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Chooses temporary import storage for reconciliation cargo and completed bulk shulkers. */
public final class ImportStagingPolicy {
    private ImportStagingPolicy() {}

    public record Candidate(long inventoryKey, int x, int y, int z, int estimatedFreeSlots) {
        public int[] position() {
            return new int[]{x, y, z};
        }
    }

    /** Prefer the import currently supplying this item, then the roomiest import. */
    public static Optional<Candidate> choose(
            Collection<Candidate> candidates,
            Map<Long, Integer> looseItemsByInventory) {
        if (candidates == null || candidates.isEmpty()) return Optional.empty();
        Map<Long, Integer> sourceItems = looseItemsByInventory == null
                ? Map.of()
                : looseItemsByInventory;
        Comparator<Candidate> order = Comparator
                .comparingInt((Candidate candidate) ->
                        sourceItems.getOrDefault(candidate.inventoryKey(), 0))
                .thenComparingInt(Candidate::estimatedFreeSlots)
                .thenComparingInt(Candidate::x)
                .thenComparingInt(Candidate::y)
                .thenComparingInt(Candidate::z);
        return candidates.stream().max(order);
    }

    public static Candidate from(ContainerEntry entry) {
        return new Candidate(entry.inventoryKey(), entry.x(), entry.y(), entry.z(),
                estimatedFreeSlots(entry));
    }

    /** Uses scanned loose stacks only; contents nested inside shulkers do not occupy chest slots. */
    static int estimatedFreeSlots(ContainerEntry entry) {
        if (entry == null) return 0;
        Map<String, Integer> loose = new LinkedHashMap<>(entry.items());
        for (ContainerEntry.ShulkerDetail detail : entry.shulkerDetails()) {
            for (var item : detail.items().entrySet()) {
                loose.computeIfPresent(item.getKey(), (key, count) -> {
                    int remaining = count - item.getValue();
                    return remaining > 0 ? remaining : null;
                });
            }
        }

        long occupied = 0;
        for (var item : loose.entrySet()) {
            int count = Math.max(0, item.getValue());
            int stackSize = LaneStorageCapacity.itemCapacityFor(item.getKey()).maxStackSize();
            occupied += count == 0 ? 0 : 1L + (count - 1L) / Math.max(1, stackSize);
        }
        return Math.max(0, containerSlots(entry) - (int) Math.min(Integer.MAX_VALUE, occupied));
    }

    /** Exact loose quantity only; nested shulker contents do not help chest stack merging. */
    public static int looseItemCount(ContainerEntry entry, String itemId) {
        if (entry == null || itemId == null || itemId.isBlank()) return 0;
        int loose = Math.max(0, entry.items().getOrDefault(itemId, 0));
        for (ContainerEntry.ShulkerDetail detail : entry.shulkerDetails()) {
            loose -= Math.max(0, detail.items().getOrDefault(itemId, 0));
        }
        return Math.max(0, loose);
    }

    private static int containerSlots(ContainerEntry entry) {
        if (entry.isDouble()) return 54;
        return switch (entry.blockType()) {
            case "minecraft:hopper" -> 5;
            case "minecraft:dispenser", "minecraft:dropper" -> 9;
            default -> 27;
        };
    }

    /** Converts exact loose item locations into physical import-inventory quantities. */
    public static Map<Long, Integer> sourceQuantities(
            Collection<ContainerEntry> imports,
            Map<Long, Integer> looseItemsByPosition) {
        if (imports == null || imports.isEmpty() || looseItemsByPosition == null) return Map.of();
        Map<Long, Integer> result = new HashMap<>();
        for (ContainerEntry entry : imports) {
            int quantity = Math.max(0, looseItemsByPosition.getOrDefault(entry.posKey(), 0));
            if (quantity > 0) result.merge(entry.inventoryKey(), quantity, Integer::sum);
        }
        return result;
    }
}
