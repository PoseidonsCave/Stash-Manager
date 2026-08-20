package com.zenith.plugin.stashmanager.orchestration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Calculates required lane and double-chest construction. */
public record LaneConstructionPlan(
        List<Requirement> requirements,
        int newLanesToBuild,
        int existingLanesToExpand,
        int doubleChestsToAdd,
        double existingAssignableDoubleChestEquivalent,
        int requiredDedicatedDoubleChests,
        int compactedRequiredDedicatedDoubleChests) {

    public static final int SHULKER_SLOTS_PER_DOUBLE_CHEST =
            LaneStorageCapacity.SHULKERS_PER_DOUBLE_CHEST;

    public enum Action {
        BUILD_NEW_LANE,
        EXPAND_EXISTING_LANE
    }

    public record Requirement(
            LaneStorageCapacity.Demand demand,
            Action action,
            LaneStorageCapacity.Lane lane,
            int currentShulkerSlots,
            int targetShulkerSlots,
            int requiredDoubleChests,
            int doubleChestsToAdd) {}

    public LaneConstructionPlan {
        requirements = List.copyOf(requirements);
    }

    public static LaneConstructionPlan assess(LaneStorageCapacity.Report report) {
        if (report == null) {
            return new LaneConstructionPlan(List.of(), 0, 0, 0, 0, 0, 0);
        }

        List<LaneStorageCapacity.Demand> unassigned = new ArrayList<>(report.unassigned());
        unassigned.sort(Comparator.comparingInt(LaneStorageCapacity.Demand::requiredShulkerSlots)
                .reversed().thenComparing(LaneStorageCapacity.Demand::storageClass));
        List<LaneStorageCapacity.Lane> expandable = new ArrayList<>(report.unallocatedLanes());
        expandable.sort(Comparator.comparingInt(LaneStorageCapacity.Lane::shulkerSlots).reversed()
                .thenComparingInt(LaneStorageCapacity.Lane::id));

        List<Requirement> requirements = new ArrayList<>();
        int newLanes = 0;
        int expansions = 0;
        int chestsToAdd = 0;
        for (int i = 0; i < unassigned.size(); i++) {
            LaneStorageCapacity.Demand demand = unassigned.get(i);
            int targetSlots = demand.requiredShulkerSlots();
            int requiredDoubleChests = doubleChestsForSlots(targetSlots);
            if (i < expandable.size()) {
                LaneStorageCapacity.Lane lane = expandable.get(i);
                int additions = doubleChestsForSlots(
                        Math.max(0, targetSlots - lane.shulkerSlots()));
                requirements.add(new Requirement(
                        demand, Action.EXPAND_EXISTING_LANE, lane,
                        lane.shulkerSlots(), targetSlots, requiredDoubleChests, additions));
                expansions++;
                chestsToAdd += additions;
            } else {
                requirements.add(new Requirement(
                        demand, Action.BUILD_NEW_LANE, null,
                        0, targetSlots, requiredDoubleChests, requiredDoubleChests));
                newLanes++;
                chestsToAdd += requiredDoubleChests;
            }
        }

        Set<String> countedClasses = new HashSet<>();
        int requiredChests = 0;
        int compactedRequiredChests = 0;
        for (LaneStorageCapacity.Allocation allocation : report.allocations()) {
            if (countedClasses.add(allocation.demand().storageClass())) {
                requiredChests += doubleChestsForSlots(
                        allocation.demand().requiredShulkerSlots());
                compactedRequiredChests += doubleChestsForSlots(
                        allocation.demand().compactedShulkerSlots());
            }
        }
        for (LaneStorageCapacity.Demand demand : report.unassigned()) {
            if (countedClasses.add(demand.storageClass())) {
                requiredChests += doubleChestsForSlots(demand.requiredShulkerSlots());
                compactedRequiredChests += doubleChestsForSlots(
                        demand.compactedShulkerSlots());
            }
        }

        return new LaneConstructionPlan(
                requirements,
                newLanes,
                expansions,
                chestsToAdd,
                report.totalAssignableShulkerSlots() / (double) SHULKER_SLOTS_PER_DOUBLE_CHEST,
                requiredChests,
                compactedRequiredChests);
    }

    public static int doubleChestsForSlots(int shulkerSlots) {
        int slots = Math.max(0, shulkerSlots);
        return (slots + SHULKER_SLOTS_PER_DOUBLE_CHEST - 1) / SHULKER_SLOTS_PER_DOUBLE_CHEST;
    }
}
