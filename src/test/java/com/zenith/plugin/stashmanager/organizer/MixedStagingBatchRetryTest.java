package com.zenith.plugin.stashmanager.organizer;

import com.zenith.plugin.stashmanager.orchestration.ShulkerClassification;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixedStagingBatchRetryTest {

    @Test
    void aNewBatchRetriesImportsRejectedByThePreviousBatch() {
        Set<Long> rejectedImports = new HashSet<>(Set.of(11L, 22L, 33L));

        StashOrganizer.resetRejectedImportsForNewBatch(rejectedImports);

        assertTrue(rejectedImports.isEmpty());
    }

    @Test
    void emptyShellRecoveryPullsOneExactGeneratedStorageClass() {
        var ordinary = new StashOrganizer.MoveTask(
                new int[]{1, 2, 3}, new int[]{4, 5, 6}, "stone");
        var tntA = StashOrganizer.MoveTask.mixedBatch(
                new int[]{7, 8, 9}, new int[]{10, 11, 12}, "tnt");
        var shells = StashOrganizer.MoveTask.mixedBatch(
                new int[]{13, 14, 15}, new int[]{16, 17, 18}, "shulker_shell");
        var tntB = StashOrganizer.MoveTask.mixedBatch(
                new int[]{19, 20, 21}, new int[]{22, 23, 24}, "tnt");
        var queue = new ArrayDeque<>(List.of(ordinary, tntA, shells, tntB));

        List<StashOrganizer.MoveTask> selected =
                StashOrganizer.extractFirstMixedBatchGroup(queue);

        assertEquals(List.of(tntA, tntB), selected);
        assertEquals(List.of(ordinary, shells), List.copyOf(queue));
    }

    @Test
    void drainedMixedShellIsReusedOnlyWhenCargoAndShellAreBothPresent() {
        assertTrue(StashOrganizer.shouldReuseRecoveredMixedShell(true, true, true));

        assertFalse(StashOrganizer.shouldReuseRecoveredMixedShell(false, true, true));
        assertFalse(StashOrganizer.shouldReuseRecoveredMixedShell(true, false, true));
        assertFalse(StashOrganizer.shouldReuseRecoveredMixedShell(true, true, false));
    }

    @Test
    void authoritativeEmptyPickupFingerprintSurvivesAStaleInventorySnapshot() {
        String emptyFingerprint = ShulkerClassification.classify(Map.of()).fingerprint();

        assertTrue(StashOrganizer.isEmptyShulkerFingerprint(emptyFingerprint));
        assertFalse(StashOrganizer.isEmptyShulkerFingerprint("not-an-empty-shulker"));
    }

    @Test
    void partialRecoverySelectsInventoryCargoAndItsMatchingStagedSources() {
        var stagedBirch = StashOrganizer.MoveTask.mixedBatch(
                new int[]{1, 1, 1}, new int[]{9, 9, 9}, "birch_log");
        var inventoryJungle = StashOrganizer.MoveTask.mixedBatchInInventory(
                new int[]{2, 2, 2}, new int[]{9, 9, 9}, "jungle_log");
        var stagedJungle = StashOrganizer.MoveTask.mixedBatch(
                new int[]{3, 3, 3}, new int[]{9, 9, 9}, "jungle_log");
        var queue = new ArrayDeque<>(List.of(
                stagedBirch, inventoryJungle, stagedJungle));

        List<StashOrganizer.MoveTask> selected =
                StashOrganizer.extractFirstInventoryMixedBatchGroup(queue);

        assertEquals(List.of(inventoryJungle, stagedJungle), selected);
        assertEquals(List.of(stagedBirch), List.copyOf(queue));
    }

    @Test
    void partialRecoveryOnlySchedulesStorageClassesRemovedFromTheSourceBox() {
        assertEquals(
                List.of("birch_log", "jungle_log"),
                StashOrganizer.removedStorageClasses(
                        Map.of("birch_log", 64, "dark_oak_log", 64, "jungle_log", 128),
                        Map.of("dark_oak_log", 64, "jungle_log", 64)));
    }

    @Test
    void recoveredBulkClassIsPackedBeforeOtherInventoryCargo() {
        var birch = StashOrganizer.MoveTask.mixedBatchInInventory(
                new int[]{1, 1, 1}, new int[]{9, 9, 9}, "birch_log");
        var jungle = StashOrganizer.MoveTask.mixedBatchInInventory(
                new int[]{2, 2, 2}, new int[]{9, 9, 9}, "jungle_log");

        assertEquals(
                List.of(jungle, birch),
                StashOrganizer.preferStorageClass(List.of(birch, jungle), "jungle_log"));
    }

    @Test
    void laneBackedInventoryCargoRunsBeforeImportOnlyCargo() {
        var totems = StashOrganizer.MoveTask.mixedBatchInInventory(
                new int[]{1, 1, 1}, new int[]{9, 9, 9}, "totem_of_undying");
        var shells = StashOrganizer.MoveTask.mixedBatchInInventory(
                new int[]{2, 2, 2}, new int[]{9, 9, 9}, "shulker_shell");

        assertEquals(
                List.of(shells, totems),
                StashOrganizer.preferAssignedStorageClasses(
                        List.of(totems, shells), Set.of("shulker_shell")));
    }

    @Test
    void laneBackedStagedCargoCanBePulledForwardToFreeImportSpace() {
        var stagedTotems = StashOrganizer.MoveTask.mixedBatch(
                new int[]{1, 1, 1}, new int[]{9, 9, 9}, "totem_of_undying");
        var stagedShells = StashOrganizer.MoveTask.mixedBatch(
                new int[]{2, 2, 2}, new int[]{8, 8, 8}, "shulker_shell");
        var queue = new ArrayDeque<>(List.of(stagedTotems, stagedShells));

        assertEquals(
                stagedShells,
                StashOrganizer.extractFirstAssignedMixedBatchTask(
                        queue, Set.of("shulker_shell")));
        assertEquals(List.of(stagedTotems), List.copyOf(queue));
    }

    @Test
    void absentInventoryOnlyCargoIsRetiredBeforePackingStarts() {
        assertTrue(StashOrganizer.shouldRetireMissingInventoryPackingCargo(
                true, true, 0, true, false));
        assertTrue(StashOrganizer.shouldRetireMissingInventoryPackingCargo(
                true, true, 0, false, true));
        assertTrue(StashOrganizer.shouldRetireMissingInventoryPackingCargo(
                true, true, 1, true, true));
        assertFalse(StashOrganizer.shouldRetireMissingInventoryPackingCargo(
                false, true, 0, true, true));
        assertFalse(StashOrganizer.shouldRetireMissingInventoryPackingCargo(
                true, false, 0, true, true));
        assertFalse(StashOrganizer.shouldRetireMissingInventoryPackingCargo(
                true, true, 0, false, false));
    }
}
