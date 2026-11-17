package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.orchestration.LaneStorageCapacity;
import com.zenith.plugin.stashmanager.profile.ItemProfiles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact per-slot working-stock box demand. This is a plan, not a reservation. */
public final class KitWorkingManifestPlan {
    private KitWorkingManifestPlan() { }

    public record Slot(String kit, String itemId, int units,
                       KitWorkingLaneAllocation.Chest chest, int chestSlot) { }

    public record Report(KitWorkingLaneAllocation.Plan allocation,
                         Map<String, Long> indexedShortages, List<Slot> slots) {
        public Report {
            indexedShortages = Map.copyOf(indexedShortages);
            slots = List.copyOf(slots);
        }
        public boolean ready() {
            return allocation.ready() && indexedShortages.isEmpty()
                    && slots.size() == allocation.requestedSlots();
        }
    }

    public static Report assess(Map<String, Map<String, Integer>> recipes,
                                Map<String, Integer> copiesByKit,
                                Map<String, Long> indexedExactStock,
                                List<KitWorkingLaneAllocation.Chest> chests,
                                int spareSlotsPerChest) {
        if (recipes == null || copiesByKit == null || copiesByKit.isEmpty()) {
            throw new IllegalArgumentException("kit recipes and copy targets are required");
        }
        var shared = KitConsolidationPlan.assess(recipes, copiesByKit, indexedExactStock,
                java.util.Set.of(), java.util.Set.of());
        Map<String, Long> slotDemand = new LinkedHashMap<>();
        Map<String, List<Box>> boxesByKit = new LinkedHashMap<>();
        for (var target : copiesByKit.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            if (recipes.get(target.getKey()).keySet().stream().anyMatch(id -> {
                String base = ItemProfiles.baseItemId(id);
                return id.startsWith("profile:") || "filled_map".equals(base)
                        || "potion".equals(base) || "splash_potion".equals(base)
                        || "lingering_potion".equals(base);
            })) {
                throw new IllegalArgumentException("kit has an item without safe physical identity: "
                        + target.getKey());
            }
            var working = KitWorkingLanePlan.assess(recipes.get(target.getKey()),
                    target.getValue(), indexedExactStock);
            if (!working.recipeFits() || !working.stackSizesResolved()) {
                throw new IllegalArgumentException("kit working stock cannot be sized: "
                        + target.getKey());
            }
            List<Box> boxes = new ArrayList<>();
            working.items().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(item -> {
                        long remaining = item.getValue().units();
                        long perBox = Math.multiplyExact(
                                (long) LaneStorageCapacity.SLOTS_PER_SHULKER,
                                item.getValue().maxStackSize());
                        while (remaining > 0) {
                            int taken = Math.toIntExact(Math.min(remaining, perBox));
                            boxes.add(new Box(item.getKey(), taken));
                            remaining -= taken;
                        }
                    });
            slotDemand.put(target.getKey(), (long) boxes.size());
            boxesByKit.put(target.getKey(), boxes);
        }
        var allocation = KitWorkingLaneAllocation.assess(slotDemand, chests,
                spareSlotsPerChest);
        if (!allocation.ready() || !shared.indexedMaterialsSuffice()) {
            return new Report(allocation, shared.exactShortages(), List.of());
        }
        List<Slot> slots = new ArrayList<>();
        Map<String, Integer> nextBox = new LinkedHashMap<>();
        for (var range : allocation.ranges()) {
            List<Box> boxes = boxesByKit.get(range.kit());
            int next = nextBox.getOrDefault(range.kit(), 0);
            for (int slot = range.firstSlot(); slot <= range.lastSlot(); slot++) {
                Box box = boxes.get(next++);
                slots.add(new Slot(range.kit(), box.itemId(), box.units(),
                        range.chest(), slot));
            }
            nextBox.put(range.kit(), next);
        }
        return new Report(allocation, Map.of(), slots);
    }

    private record Box(String itemId, int units) { }
}
