package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.orchestration.LaneStorageCapacity;
import com.zenith.plugin.stashmanager.profile.ItemProfiles;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only working-stock sizing for a kit-specific chest group. Bulk stock is not allocated. */
public final class KitWorkingLanePlan {
    private KitWorkingLanePlan() { }

    public record ItemDemand(long units, int maxStackSize, long shulkerSlots,
                             long indexedUnits, long shortage, boolean stackSizeResolved) { }

    public record Report(int copies, int kitSlots, Map<String, ItemDemand> items,
                         long shulkerSlots, long doubleChests) {
        public Report {
            items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
        }

        public boolean recipeFits() {
            return kitSlots > 0 && kitSlots <= KitCapacity.MAX_SLOTS;
        }

        public boolean indexedStockSuffices() {
            return items.values().stream().allMatch(item -> item.shortage() == 0);
        }

        public boolean stackSizesResolved() {
            return items.values().stream().allMatch(ItemDemand::stackSizeResolved);
        }
    }

    public static Report assess(Map<String, Integer> recipe, int copies,
                                Map<String, Long> indexedStock) {
        if (copies < 1 || copies > 64) throw new IllegalArgumentException("copies must be 1..64");
        if (recipe == null || recipe.isEmpty()) throw new IllegalArgumentException("recipe is empty");
        Map<String, ItemDemand> items = new LinkedHashMap<>();
        long shulkerSlots = 0;
        for (var entry : recipe.entrySet()) {
            String id = entry.getKey();
            Integer quantity = entry.getValue();
            if (id == null || id.isBlank() || quantity == null || quantity < 1) {
                throw new IllegalArgumentException("recipe has a nonpositive or unnamed item");
            }
            var capacity = LaneStorageCapacity.itemCapacityFor(ItemProfiles.baseItemId(id));
            long units = (long) quantity * copies;
            long perBox = (long) LaneStorageCapacity.SLOTS_PER_SHULKER * capacity.maxStackSize();
            long boxes = 1 + (units - 1) / perBox;
            long indexed = indexedStock == null ? 0L : Math.max(0L, indexedStock.getOrDefault(id, 0L));
            items.put(id, new ItemDemand(units, capacity.maxStackSize(), boxes, indexed,
                    Math.max(0L, units - indexed), capacity.registryResolved()));
            shulkerSlots = Math.addExact(shulkerSlots, boxes);
        }
        long doubleChests = 1 + (shulkerSlots - 1) / LaneStorageCapacity.SHULKERS_PER_DOUBLE_CHEST;
        return new Report(copies, KitCapacity.requiredSlots(recipe), items,
                shulkerSlots, doubleChests);
    }
}
