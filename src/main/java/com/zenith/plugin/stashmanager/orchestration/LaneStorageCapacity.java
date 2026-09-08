package com.zenith.plugin.stashmanager.orchestration;

import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Calculates shulker capacity and lane assignments. */
public final class LaneStorageCapacity {
    public static final int SLOTS_PER_SHULKER = 27;
    public static final int SHULKERS_PER_DOUBLE_CHEST = 54;

    private LaneStorageCapacity() {}

    public record ItemCapacity(
            int maxStackSize,
            int itemsPerShulker,
            long itemsPerDoubleChest,
            boolean registryResolved) {}

    public record Demand(
            String storageClass,
            long looseItems,
            int existingBulkShulkers,
            long itemsInExistingShulkers,
            long reusableSpaceInExistingShulkers,
            int maxStackSize,
            boolean stackSizeResolved,
            int itemsPerShulker,
            int requiredShulkerSlots,
            int compactedShulkerSlots) {

        public long totalItems() {
            return saturatingAdd(looseItems, itemsInExistingShulkers);
        }

        public int reclaimableShulkerSlots() {
            return Math.max(0, requiredShulkerSlots - compactedShulkerSlots);
        }

        public static Demand calculate(
                String storageClass,
                long looseItems,
                Collection<Integer> existingShulkerItemCounts) {
            ItemCapacity itemCapacity = itemCapacityFor(storageClass);
            return calculate(storageClass, looseItems, existingShulkerItemCounts,
                    itemCapacity.itemsPerShulker(), itemCapacity.maxStackSize(),
                    itemCapacity.registryResolved());
        }

        public static Demand calculate(
                String storageClass,
                long looseItems,
                Collection<Integer> existingShulkerItemCounts,
                int itemsPerShulker) {
            int capacity = Math.max(1, itemsPerShulker);
            int inferredStackSize = capacity >= SLOTS_PER_SHULKER
                    ? Math.max(1, capacity / SLOTS_PER_SHULKER)
                    : 1;
            return calculate(storageClass, looseItems, existingShulkerItemCounts,
                    capacity, inferredStackSize, true);
        }

        private static Demand calculate(
                String storageClass,
                long looseItems,
                Collection<Integer> existingShulkerItemCounts,
                int itemsPerShulker,
                int maxStackSize,
                boolean stackSizeResolved) {
            int capacity = Math.max(1, itemsPerShulker);
            long loose = Math.max(0, looseItems);
            int existing = 0;
            long boxedItems = 0;
            long reusable = 0;
            if (existingShulkerItemCounts != null) {
                for (Integer rawCount : existingShulkerItemCounts) {
                    int count = rawCount == null ? 0 : Math.max(0, rawCount);
                    existing++;
                    boxedItems = saturatingAdd(boxedItems, count);
                    reusable += Math.max(0, capacity - count);
                }
            }
            long itemsNeedingNewBoxes = Math.max(0, loose - reusable);
            long additional = ceilingDivision(itemsNeedingNewBoxes, capacity);
            long required = saturatingAdd(existing, additional);
            long compacted = ceilingDivision(saturatingAdd(loose, boxedItems), capacity);
            return new Demand(storageClass, loose, existing, boxedItems, reusable,
                    Math.max(1, maxStackSize), stackSizeResolved, capacity,
                    boundedInt(required), boundedInt(compacted));
        }
    }

    public static ItemCapacity itemCapacityFor(String storageClass) {
        String baseId = ItemIdentifier.baseItemId(storageClass);
        ItemData data = null;
        if (baseId != null && !baseId.isBlank()) {
            data = ItemRegistry.REGISTRY.get(baseId);
            if (data == null && baseId.contains(":")) {
                data = ItemRegistry.REGISTRY.get(baseId.substring(baseId.indexOf(':') + 1));
            } else if (data == null) {
                data = ItemRegistry.REGISTRY.get("minecraft:" + baseId);
            }
        }
        // Unknown IDs are treated as non-stackable so capacity is never understated.
        int stackSize = data == null ? 1 : Math.max(1, data.stackSize());
        int perShulker = boundedInt((long) SLOTS_PER_SHULKER * stackSize);
        long perDoubleChest = (long) perShulker * SHULKERS_PER_DOUBLE_CHEST;
        return new ItemCapacity(stackSize, perShulker, perDoubleChest, data != null);
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

        public int totalCompactedShulkerSlots() {
            return saturatingSum(allDemands().stream()
                    .map(Demand::compactedShulkerSlots).toList());
        }

        public int totalReclaimableShulkerSlots() {
            return saturatingSum(allDemands().stream()
                    .map(Demand::reclaimableShulkerSlots).toList());
        }

        public List<String> unresolvedStackSizeClasses() {
            return allDemands().stream()
                    .filter(demand -> !demand.stackSizeResolved())
                    .map(Demand::storageClass)
                    .sorted()
                    .toList();
        }

        private List<Demand> allDemands() {
            List<Demand> demands = new ArrayList<>(allocations.size() + unassigned.size());
            allocations.forEach(allocation -> demands.add(allocation.demand()));
            demands.addAll(unassigned);
            return demands;
        }
    }

    /** Assign largest demands first; prefer stable assignments and the smallest lane that fits. */
    public static Report assess(
            Collection<Demand> rawDemands,
            Collection<Lane> rawLanes,
            Map<String, Integer> preferredLaneIds) {
        Map<String, Demand> uniqueDemands = new LinkedHashMap<>();
        if (rawDemands != null) {
            for (Demand demand : rawDemands) {
                if (demand == null || demand.storageClass() == null
                        || demand.requiredShulkerSlots() <= 0) continue;
                // A class is one organization contract. Repeated scan evidence must not create
                // another assignment for the same class; keep the conservative larger demand.
                uniqueDemands.merge(demand.storageClass(), demand,
                        LaneStorageCapacity::largerDemand);
            }
        }
        List<Demand> demands = new ArrayList<>(uniqueDemands.values());
        demands.sort(Comparator.comparingInt(Demand::requiredShulkerSlots).reversed()
                .thenComparing(Demand::storageClass));

        List<Lane> lanes = deduplicateLanes(rawLanes);
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

    private static List<Lane> deduplicateLanes(Collection<Lane> rawLanes) {
        if (rawLanes == null || rawLanes.isEmpty()) return new ArrayList<>();
        List<Lane> candidates = rawLanes.stream()
                .filter(lane -> lane != null && lane.shulkerSlots() > 0)
                .sorted(Comparator.comparingInt(Lane::shulkerSlots)
                        .thenComparingInt(Lane::id)
                        .thenComparingInt(Lane::topX)
                        .thenComparingInt(Lane::topY)
                        .thenComparingInt(Lane::topZ))
                .toList();
        Set<Integer> ids = new java.util.HashSet<>();
        List<Lane> unique = new ArrayList<>();
        for (Lane lane : candidates) {
            if (!ids.add(lane.id())) continue;
            unique.add(lane);
        }
        return unique;
    }

    private static Demand largerDemand(Demand left, Demand right) {
        int bySlots = Integer.compare(
                left.requiredShulkerSlots(), right.requiredShulkerSlots());
        if (bySlots != 0) return bySlots >= 0 ? left : right;
        return left.totalItems() >= right.totalItems() ? left : right;
    }

    private static int saturatingSum(Collection<Integer> values) {
        long total = 0;
        for (Integer value : values) {
            total = saturatingAdd(total, value == null ? 0 : Math.max(0, value));
        }
        return boundedInt(total);
    }

    private static long ceilingDivision(long value, int divisor) {
        if (value <= 0) return 0;
        return 1L + (value - 1L) / Math.max(1, divisor);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static int boundedInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, value);
    }
}
