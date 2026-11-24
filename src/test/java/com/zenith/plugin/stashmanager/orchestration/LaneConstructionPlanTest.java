package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LaneConstructionPlanTest {
    @Test
    void expandsLargestUnusedLaneBeforeRecommendingNewConstruction() {
        var large = LaneStorageCapacity.Demand.calculate("end_crystal", 200, List.of(), 1);
        var medium = LaneStorageCapacity.Demand.calculate("obsidian", 100, List.of(), 1);
        var shortLane = new LaneStorageCapacity.Lane(7, 0, 0, 0, 80);
        var report = new LaneStorageCapacity.Report(
                List.of(), List.of(large, medium), List.of(shortLane),
                80, 300, 300);

        LaneConstructionPlan plan = LaneConstructionPlan.assess(report);

        assertEquals(1, plan.existingLanesToExpand());
        assertEquals(1, plan.newLanesToBuild());
        assertEquals(7, plan.requirements().get(0).lane().id());
        assertEquals(3, plan.requirements().get(0).doubleChestsToAdd());
        assertEquals(LaneConstructionPlan.Action.BUILD_NEW_LANE,
                plan.requirements().get(1).action());
        assertEquals(2, plan.requirements().get(1).doubleChestsToAdd());
        assertEquals(5, plan.doubleChestsToAdd());
    }

    @Test
    void roundsPartialCapacityUpToBuildableDoubleChests() {
        assertEquals(0, LaneConstructionPlan.doubleChestsForSlots(0));
        assertEquals(1, LaneConstructionPlan.doubleChestsForSlots(1));
        assertEquals(1, LaneConstructionPlan.doubleChestsForSlots(54));
        assertEquals(2, LaneConstructionPlan.doubleChestsForSlots(55));
    }
}
