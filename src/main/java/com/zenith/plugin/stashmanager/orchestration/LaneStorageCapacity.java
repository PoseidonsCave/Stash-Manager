package com.zenith.plugin.stashmanager.orchestration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Calculates shulker capacity and lane assignments. */
public final class LaneStorageCapacity {
    private LaneStorageCapacity() {}

    public record Demand(
            String storageClass,
            long looseItems,
            int existingBulkShulkers,
            long reusableSpaceInExistingShulkers,
            int itemsPerShulker,
            int requiredShulkerSlots) {

        public static Demand calculate(
                String storageClass,
                long looseItems,
                Collection<Integer> existingShulkerItemCounts,
                int itemsPerShulker) {
            int capacity = Math.max(1, itemsPerShulker);
            long loose = Math.max(0, looseItems);
            int existing = 0;
            long reusable = 0;
            if (existingShulkerItemCounts != null) {
                for (Integer rawCount : existingShulkerItemCounts) {
                    int count = rawCount == null ? 0 : Math.max(0, rawCount);
                    existing++;
                    reusable += Math.max(0, capacity - count);
                }
            }
            long itemsNeedingNewBoxes = Math.max(0, loose - reusable);
            long additional = (itemsNeedingNewBoxes + capacity - 1L) / capacity;
            long required = existing + additional;
            return new Demand(storageClass, loose, existing, reusable, capacity,
                    required > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) required);
        }
    }

    public record Lane(int id, int topX, int topY, int topZ, int shulkerSlots) {
        public Lane {
            shulkerSlots = Math.max(0, shulkerSlots);
        }
    }

    public record Allocation(Demand demand, Lane lane, int spareShulkerSlots) {}

    public record Report(
            List<Allocation> allocations,
            List<Demand> unassigned,
            List<Lane> unallocatedLanes,
            int totalAssignableShulkerSlots,
            int totalRequiredShulkerSlots,
            int unassignedRequiredShulkerSlots) {
        public Report {
            allocations = List.copyOf(allocations);
            unassigned = List.copyOf(unassigned);
            unallocatedLanes = List.copyOf(unallocatedLanes);
        }

        public boolean feasible() {
            return unassigned.isEmpty();
        }
    }

    /** Assign largest demands first; prefer stable assignments and the smallest lane that fits. */
    public static Report assess(
            Collection<Demand> rawDemands,
            Collection<Lane> rawLanes,
            Map<String, Integer> preferredLaneIds) {
        List<Demand> demands = rawDemands == null ? new ArrayList<>() : new ArrayList<>(rawDemands);
        demands.removeIf(demand -> demand == null || demand.storageClass() == null
                || demand.requiredShulkerSlots() <= 0);
        demands.sort(Comparator.comparingInt(Demand::requiredShulkerSlots).reversed()
                .thenComparing(Demand::storageClass));

        List<Lane> lanes = rawLanes == null ? new ArrayList<>() : new ArrayList<>(rawLanes);
        lanes.removeIf(lane -> lane == null || lane.shulkerSlots() <= 0);
        lanes.sort(Comparator.comparingInt(Lane::shulkerSlots).thenComparingInt(Lane::id));

        Map<Integer, Lane> availableById = new HashMap<>();
        for (Lane lane : lanes) availableById.put(lane.id(), lane);

        Map<String, Integer> preferences = preferredLaneIds == null
                ? Map.of()
                : new LinkedHashMap<>(preferredLaneIds);
        List<Allocation> allocations = new ArrayList<>();
        List<Demand> unassigned = new ArrayList<>();

        for (Demand demand : demands) {
            Lane selected = null;
            Integer preferredId = preferences.get(demand.storageClass());
            if (preferredId != null) {
                Lane preferred = availableById.get(preferredId);
                if (preferred != null && preferred.shulkerSlots() >= demand.requiredShulkerSlots()) {
                    selected = preferred;
                }
            }
            if (selected == null) {
                for (Lane lane : lanes) {
                    if (availableById.containsKey(lane.id())
                            && lane.shulkerSlots() >= demand.requiredShulkerSlots()) {
                        selected = lane;
                        break;
                    }
                }
            }
            if (selected == null) {
                unassigned.add(demand);
                continue;
            }
            availableById.remove(selected.id());
            allocations.add(new Allocation(demand, selected,
                    selected.shulkerSlots() - demand.requiredShulkerSlots()));
        }

        int totalSlots = saturatingSum(lanes.stream().map(Lane::shulkerSlots).toList());
        int requiredSlots = saturatingSum(demands.stream().map(Demand::requiredShulkerSlots).toList());
        int unassignedSlots = saturatingSum(unassigned.stream().map(Demand::requiredShulkerSlots).toList());
        List<Lane> unallocatedLanes = availableById.values().stream()
                .sorted(Comparator.comparingInt(Lane::id))
                .toList();
        return new Report(allocations, unassigned, unallocatedLanes,
                totalSlots, requiredSlots, unassignedSlots);
    }

    public static Report assess(Collection<Demand> demands, Collection<Lane> lanes) {
        return assess(demands, lanes, Map.of());
    }

    private static int saturatingSum(Collection<Integer> values) {
        long total = 0;
        for (Integer value : values) total += value == null ? 0 : Math.max(0, value);
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }
}
