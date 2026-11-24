package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.orchestration.StorageClassPolicy;
import com.zenith.plugin.stashmanager.orchestration.LaneStorageCapacity;
import com.zenith.plugin.stashmanager.profile.ItemProfiles;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Read-only multi-kit material balance. No projected zero is permission to retire a lane. */
public final class KitConsolidationPlan {
    private KitConsolidationPlan() { }

    public record ClassBalance(long indexedUnits, long plannedUnits, long residualUnits,
                               long exactShortageUnits, long residualShulkerSlots,
                               boolean assignedLane, boolean protectedClass) {
        public boolean projectedEmpty() {
            return assignedLane && !protectedClass && indexedUnits > 0
                    && exactShortageUnits == 0 && plannedUnits == indexedUnits;
        }

        /** A planning candidate only. Physical handoff and lane retirement need a later audit. */
        public boolean residualHandoffCandidate() {
            return assignedLane && !protectedClass && indexedUnits > 0
                    && exactShortageUnits == 0 && residualUnits > 0;
        }
    }

    public record Report(int outputBoxes, Map<String, Long> exactShortages,
                         Map<String, ClassBalance> classes) {
        public Report {
            exactShortages = Collections.unmodifiableMap(new LinkedHashMap<>(exactShortages));
            classes = Collections.unmodifiableMap(new LinkedHashMap<>(classes));
        }

        public boolean indexedMaterialsSuffice() {
            return exactShortages.isEmpty();
        }

        public long projectedEmptyLaneCount() {
            return classes.values().stream().filter(ClassBalance::projectedEmpty).count();
        }

        public long residualHandoffCandidateCount() {
            return classes.values().stream().filter(ClassBalance::residualHandoffCandidate).count();
        }

        public long residualHandoffShulkerSlots() {
            return classes.values().stream().filter(ClassBalance::residualHandoffCandidate)
                    .mapToLong(ClassBalance::residualShulkerSlots).sum();
        }
    }

    public static Report assess(Map<String, Map<String, Integer>> recipes,
                                Map<String, Integer> copiesByKit,
                                Map<String, Long> indexedExactStock,
                                Set<String> assignedClasses,
                                Set<String> additionalProtectedClasses) {
        if (recipes == null || copiesByKit == null || indexedExactStock == null) {
            throw new IllegalArgumentException("recipes, copies, and stock are required");
        }
        Map<String, Long> exactDemand = new LinkedHashMap<>();
        int outputBoxes = 0;
        for (var target : copiesByKit.entrySet()) {
            String kit = target.getKey();
            Integer copies = target.getValue();
            Map<String, Integer> recipe = recipes.get(kit);
            if (kit == null || kit.isBlank() || copies == null || copies < 1 || copies > 64
                    || recipe == null || recipe.isEmpty() || !KitCapacity.fitsOneShulker(recipe)) {
                throw new IllegalArgumentException("invalid kit target or recipe: " + kit);
            }
            outputBoxes = Math.addExact(outputBoxes, copies);
            for (var item : recipe.entrySet()) {
                if (item.getKey() == null || item.getKey().isBlank()
                        || item.getValue() == null || item.getValue() < 1) {
                    throw new IllegalArgumentException("invalid item in kit: " + kit);
                }
                exactDemand.merge(item.getKey(), (long) item.getValue() * copies, Math::addExact);
            }
        }
        Map<String, Long> shortages = new LinkedHashMap<>();
        exactDemand.forEach((item, units) -> {
            long available = Math.max(0L, indexedExactStock.getOrDefault(item, 0L));
            if (units > available) shortages.put(item, units - available);
        });

        Map<String, Long> classStock = new LinkedHashMap<>();
        indexedExactStock.forEach((item, units) -> {
            String storageClass = StorageClassPolicy.route(item);
            if (storageClass != null) {
                classStock.merge(storageClass, Math.max(0L, units), Math::addExact);
            }
        });
        Map<String, Long> classDemand = new LinkedHashMap<>();
        exactDemand.forEach((item, units) -> {
            String storageClass = StorageClassPolicy.route(item);
            if (storageClass != null) classDemand.merge(storageClass, units, Math::addExact);
        });
        Map<String, Long> classShortages = new LinkedHashMap<>();
        shortages.forEach((item, units) -> {
            String storageClass = StorageClassPolicy.route(item);
            if (storageClass != null) classShortages.merge(storageClass, units, Math::addExact);
        });
        Map<String, Long> residualSlots = new LinkedHashMap<>();
        indexedExactStock.forEach((item, units) -> {
            String storageClass = StorageClassPolicy.route(item);
            if (storageClass == null) return;
            long residual = Math.max(0L, units - exactDemand.getOrDefault(item, 0L));
            if (residual == 0) return;
            var capacity = LaneStorageCapacity.itemCapacityFor(ItemProfiles.baseItemId(item));
            long perBox = Math.multiplyExact((long) LaneStorageCapacity.SLOTS_PER_SHULKER,
                    capacity.maxStackSize());
            residualSlots.merge(storageClass, 1L + (residual - 1) / perBox, Math::addExact);
        });
        Map<String, ClassBalance> balances = new LinkedHashMap<>();
        Set<String> assigned = assignedClasses == null ? Set.of() : assignedClasses;
        Set<String> protectedExtra = additionalProtectedClasses == null
                ? Set.of() : additionalProtectedClasses;
        for (var demand : classDemand.entrySet()) {
            String storageClass = demand.getKey();
            long indexed = classStock.getOrDefault(storageClass, 0L);
            long planned = demand.getValue();
            balances.put(storageClass, new ClassBalance(indexed, planned,
                    Math.max(0L, indexed - planned),
                    classShortages.getOrDefault(storageClass, 0L),
                    residualSlots.getOrDefault(storageClass, 0L), assigned.contains(storageClass),
                    protectedExtra.contains(storageClass) || protectedByDefault(storageClass)));
        }
        return new Report(outputBoxes, shortages, balances);
    }

    /** Identity-bearing and structural families retain their own storage by default. */
    public static boolean protectedByDefault(String storageClass) {
        if (storageClass == null || storageClass.isBlank()) return true;
        String base = storageClass.split("\\[", 2)[0];
        return base.equals("petrified_oak_slab") || base.equals("filled_map")
                || base.equals("map") || base.equals("written_book")
                || base.equals("enchanted_book")
                || base.endsWith("shulker_box")
                || base.equals("bamboo")
                || java.util.List.of("oak_", "spruce_", "birch_", "jungle_",
                        "acacia_", "dark_oak_", "mangrove_", "cherry_",
                        "crimson_", "warped_", "bamboo_", "pale_oak_").stream()
                        .anyMatch(base::startsWith)
                || base.endsWith("_log") || base.endsWith("_wood")
                || base.endsWith("_stem") || base.endsWith("_hyphae")
                || base.endsWith("_planks") || base.endsWith("_leaves")
                || base.endsWith("_sapling") || base.endsWith("_propagule");
    }
}
