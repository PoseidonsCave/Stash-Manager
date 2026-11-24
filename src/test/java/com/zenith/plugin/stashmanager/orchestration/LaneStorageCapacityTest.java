package com.zenith.plugin.stashmanager.orchestration;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaneStorageCapacityTest {
    @Test
    void calculatesNewBoxesAfterReusingPartialBulkShulkers() {
        var demand = LaneStorageCapacity.Demand.calculate("end_crystal", 2_000,
                List.of(1_000, 1_728), 1_728);

        assertEquals(2, demand.existingBulkShulkers());
        assertEquals(728, demand.reusableSpaceInExistingShulkers());
        assertEquals(3, demand.requiredShulkerSlots());
        assertEquals(3, demand.compactedShulkerSlots());
        assertEquals(0, demand.reclaimableShulkerSlots());
    }

    @Test
    void reportsCompactedMinimumWithoutUnderstatingExecutableDemand() {
        var demand = LaneStorageCapacity.Demand.calculate("end_crystal", 0,
                List.of(100, 100), 1_728);

        assertEquals(2, demand.requiredShulkerSlots());
        assertEquals(1, demand.compactedShulkerSlots());
        assertEquals(1, demand.reclaimableShulkerSlots());
        assertEquals(200, demand.itemsInExistingShulkers());
    }

    @Test
    void resolvesVanillaStackSizesAndUsesSafeFallback() {
        var crystals = LaneStorageCapacity.itemCapacityFor("end_crystal");
        var pickaxes = LaneStorageCapacity.itemCapacityFor("diamond_pickaxe[fortune]");
        var unknown = LaneStorageCapacity.itemCapacityFor("unknown_custom_tool");

        assertEquals(64, crystals.maxStackSize());
        assertEquals(1_728, crystals.itemsPerShulker());
        assertEquals(93_312, crystals.itemsPerDoubleChest());
        assertEquals(1, pickaxes.maxStackSize());
        assertEquals(27, pickaxes.itemsPerShulker());
        assertEquals(1, unknown.maxStackSize());
        assertFalse(unknown.registryResolved());
    }

    @Test
    void differentlyNamedStacksShareAClassButNeedTheirOwnPhysicalSlots() {
        var demand = LaneStorageCapacity.Demand.calculateWithObservedSlots(
                "iron_block", 30, 30, List.of());
        assertEquals("iron_block", demand.storageClass());
        assertEquals(2, demand.requiredShulkerSlots());
        assertEquals(2, demand.compactedShulkerSlots());
        assertEquals(64, demand.maxStackSize());
    }

    @Test
    void fullBoxOfNonMergingVariantsCannotAbsorbAnotherStack() {
        var demand = LaneStorageCapacity.Demand.calculateWithObservedSlots(
                "enchanted_golden_apple", 1, 1,
                List.of(new LaneStorageCapacity.ShulkerStock(27, 27)));
        assertEquals(2, demand.requiredShulkerSlots());
        assertEquals(2, demand.compactedShulkerSlots());
    }

    @Test
    void matchingStacksStillUseTheNormalStackCapacity() {
        var demand = LaneStorageCapacity.Demand.calculateWithObservedSlots(
                "iron_block", 1_728, 27, List.of());
        assertEquals(1, demand.requiredShulkerSlots());
    }

    @Test
    void preservesLargeLaneForLargeDemand() {
        var small = LaneStorageCapacity.Demand.calculate("small", 1, List.of(), 1);
        var large = LaneStorageCapacity.Demand.calculate("large", 8, List.of(), 1);
        var report = LaneStorageCapacity.assess(
                List.of(small, large),
                List.of(new LaneStorageCapacity.Lane(1, 0, 0, 0, 4),
                        new LaneStorageCapacity.Lane(2, 0, 0, 0, 10)));

        assertTrue(report.feasible());
        assertEquals(2, report.allocations().stream()
                .filter(allocation -> allocation.demand().storageClass().equals("large"))
                .findFirst().orElseThrow().lane().id());
    }

    @Test
    void rejectsPreferenceWhenPreferredLaneIsTooSmall() {
        var demand = LaneStorageCapacity.Demand.calculate("rockets", 7, List.of(), 1);
        var report = LaneStorageCapacity.assess(
                List.of(demand),
                List.of(new LaneStorageCapacity.Lane(1, 0, 0, 0, 5),
                        new LaneStorageCapacity.Lane(2, 0, 0, 0, 10)),
                Map.of("rockets", 1));

        assertTrue(report.feasible());
        assertEquals(2, report.allocations().get(0).lane().id());
    }

    @Test
    void reportsClassThatCannotFitAnyLaneEvenWhenTotalSlotsAreSufficient() {
        var demand = LaneStorageCapacity.Demand.calculate("obsidian", 7, List.of(), 1);
        var report = LaneStorageCapacity.assess(
                List.of(demand),
                List.of(new LaneStorageCapacity.Lane(1, 0, 0, 0, 4),
                        new LaneStorageCapacity.Lane(2, 0, 0, 0, 4)));

        assertFalse(report.feasible());
        assertEquals("obsidian", report.unassigned().get(0).storageClass());
        assertEquals(7, report.unassignedRequiredShulkerSlots());
    }

    @Test
    void duplicateDemandCannotClaimTwoLanes() {
        var smaller = LaneStorageCapacity.Demand.calculate("obsidian", 4, List.of(), 1);
        var larger = LaneStorageCapacity.Demand.calculate("obsidian", 7, List.of(), 1);
        var report = LaneStorageCapacity.assess(
                List.of(smaller, larger),
                List.of(new LaneStorageCapacity.Lane(1, 0, 64, 0, 8),
                        new LaneStorageCapacity.Lane(2, 1, 64, 0, 8)));

        assertEquals(1, report.allocations().size());
        assertEquals(7, report.allocations().get(0).demand().requiredShulkerSlots());
        assertEquals(1, report.unallocatedLanes().size());
    }

    @Test
    void duplicateLaneIdentityIsAllocatedOnlyOnce() {
        var first = LaneStorageCapacity.Demand.calculate("obsidian", 2, List.of(), 1);
        var second = LaneStorageCapacity.Demand.calculate("end_crystal", 2, List.of(), 1);
        var report = LaneStorageCapacity.assess(
                List.of(first, second),
                List.of(new LaneStorageCapacity.Lane(1, 10, 64, 20, 4),
                        new LaneStorageCapacity.Lane(1, 11, 64, 20, 4)));

        assertEquals(1, report.allocations().size());
        assertEquals(1, report.unassigned().size());
        assertEquals(4, report.totalAssignableShulkerSlots());
    }

    @Test
    void importStagingPrefersTheInventoryThatSuppliedTheLooseItem() {
        var source = new ImportStagingPolicy.Candidate(1L, 10, 64, 10, 2);
        var roomier = new ImportStagingPolicy.Candidate(2L, 20, 64, 20, 40);

        var selected = ImportStagingPolicy.choose(List.of(roomier, source), Map.of(1L, 64));

        assertEquals(source, selected.orElseThrow());
    }

    @Test
    void importStagingFallsBackToTheRoomiestInventory() {
        var full = new ImportStagingPolicy.Candidate(1L, 10, 64, 10, 0);
        var roomier = new ImportStagingPolicy.Candidate(2L, 20, 64, 20, 8);

        var selected = ImportStagingPolicy.choose(List.of(full, roomier), Map.of());

        assertEquals(roomier, selected.orElseThrow());
        assertTrue(ImportStagingPolicy.choose(List.of(), Map.of()).isEmpty());
    }

    @Test
    void importSlotEstimateDoesNotCountNestedContentsTwice() {
        var entry = new ContainerEntry(
                10, 64, 10, "minecraft:chest", true,
                Map.of("shulker_box", 1, "end_crystal", 1_728, "totem_of_undying", 2),
                1,
                List.of(new ContainerEntry.ShulkerDetail(0, "unknown", Map.of("end_crystal", 1_728))),
                1L,
                null,
                null,
                10, 64, 10,
                true);

        assertEquals(51, ImportStagingPolicy.estimatedFreeSlots(entry));
        assertEquals(0, ImportStagingPolicy.looseItemCount(entry, "end_crystal"));
        assertEquals(2, ImportStagingPolicy.looseItemCount(entry, "totem_of_undying"));
    }
}
