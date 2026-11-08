package com.zenith.plugin.stashmanager.organizer;

import com.google.gson.JsonParser;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OrganizerJournalStoreTest {
    @Test
    void missingOrDeathLostCargoCannotBeTreatedAsResumable() {
        assertTrue(StashOrganizer.isCargoIntegrityFailure("player_died_cargo_unverified"));
        assertTrue(StashOrganizer.isCargoIntegrityFailure("overflow_cargo_not_found"));
        assertTrue(StashOrganizer.isCargoIntegrityFailure("task_cargo_not_found_during_deposit"));
        assertFalse(StashOrganizer.isCargoIntegrityFailure("destination_unreachable_with_cargo"));
    }

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsAPlanAndSmallQueueCheckpoint() throws Exception {
        OrganizerJournalStore store = store();
        var task = new OrganizerJournalStore.TaskSnapshot(
                1, new int[]{1, 2, 3}, new int[]{4, 5, 6},
                "diamond_pickaxe[fortune]", null, false);
        var plan = new OrganizerJournalStore.Plan(
                OrganizerJournalStore.SCHEMA_VERSION,
                "job-1",
                123L,
                "OVERWORLD",
                new int[]{0, 1, 2},
                new int[]{9, 8, 7},
                List.of(task),
                Map.of("diamond_pickaxe[fortune]",
                        new OrganizerJournalStore.ColumnSnapshot(
                                4, List.of(new int[]{4, 5, 6}))),
                List.of(99L));
        var checkpoint = checkpoint("job-1", List.of(), List.of(1));

        store.savePlan(plan);
        store.saveCheckpoint(checkpoint);

        var loaded = store.load().orElseThrow();
        assertEquals("job-1", loaded.plan().jobId());
        assertEquals("diamond_pickaxe[fortune]", loaded.plan().tasks().getFirst().itemId());
        assertEquals(List.of(1), loaded.checkpoint().consolidationQueue());
        assertEquals(List.of(9, 42), loaded.checkpoint().protectedInventorySlots());
        assertEquals(64, loaded.checkpoint().packingProgress().movedUnits());
        assertTrue(loaded.checkpoint().packingProgress().atMaximumCapacity());
        assertEquals("SHULKER_FETCH_TAKE", loaded.checkpoint().lastFailure().state());
        assertEquals(checkpoint.mixedStagingLedger(), loaded.checkpoint().mixedStagingLedger());
        assertNull(loaded.checkpoint().inventoryRecovery(), "Older journals have no recovery guard");
        assertNull(loaded.checkpoint().stationWalkRecovery(), "Older journals start with a fresh walking budget");
        assertEquals(List.of(101L, 202L), loaded.checkpoint().packedImportTriedDestinationKeys());
        assertTrue(store.exists());

        store.clear();
        assertFalse(store.exists());
        assertTrue(store.load().isEmpty());
    }

    @Test
    void rejectsAPlanCheckpointJobMismatch() throws Exception {
        OrganizerJournalStore store = store();
        store.savePlan(new OrganizerJournalStore.Plan(
                OrganizerJournalStore.SCHEMA_VERSION,
                "plan-job", 1L, "OVERWORLD",
                new int[]{0, 0, 0}, new int[]{1, 1, 1},
                List.of(), Map.of(), List.of()));
        store.saveCheckpoint(checkpoint("checkpoint-job", List.of(), List.of()));

        IOException error = assertThrows(IOException.class, store::load);
        assertTrue(error.getMessage().contains("different jobs"));
    }

    @Test
    void restartPlanPinsImportsFromEveryIntakeRegion() throws Exception {
        OrganizerJournalStore store = store();
        var first = new StashOrganizer.IntakeRegion("farm",
                new int[]{0, 60, 0}, new int[]{9, 70, 9},
                List.of(new int[]{1, 64, 1}, new int[]{2, 64, 1}));
        var second = new StashOrganizer.IntakeRegion("trading",
                new int[]{20, 60, 0}, new int[]{29, 70, 9},
                List.of(new int[]{21, 64, 1}));
        store.savePlan(new OrganizerJournalStore.Plan(
                OrganizerJournalStore.SCHEMA_VERSION, "routed-job", 1L, "OVERWORLD",
                new int[]{100, 60, 0}, new int[]{109, 70, 9},
                List.of(), Map.of(), List.of(), List.of(first, second)));
        store.saveCheckpoint(checkpoint("routed-job", List.of(), List.of()));

        var intakes = store.load().orElseThrow().plan().intakeRegions();
        assertEquals(List.of("farm", "trading"), intakes.stream().map(
                StashOrganizer.IntakeRegion::name).toList());
        assertArrayEquals(new int[]{21, 64, 1}, intakes.get(1).importBlocks().getFirst());
    }

    @Test
    void inventoryOnlyPlanPersistsItsKitOwnedSlotAcrossRestart() throws Exception {
        OrganizerJournalStore store = store();
        store.savePlan(new OrganizerJournalStore.Plan(
                OrganizerJournalStore.INVENTORY_RECOVERY_SCHEMA_VERSION,
                "kit-handoff", 1L, "OVERWORLD",
                new int[]{0, 60, 0}, new int[]{9, 70, 9},
                List.of(), Map.of(), List.of(), List.of(), true, List.of(37)));
        store.saveCheckpoint(inventoryCheckpoint("kit-handoff"));

        var loaded = store.load().orElseThrow().plan();
        assertTrue(loaded.inventoryRecoveryOnly());
        assertEquals(List.of(37), loaded.pinnedInventorySlots());
    }

    @Test
    void inventoryOnlyPlanRejectsNonPlayerPinnedSlot() {
        OrganizerJournalStore store = store();
        var invalid = new OrganizerJournalStore.Plan(
                OrganizerJournalStore.INVENTORY_RECOVERY_SCHEMA_VERSION,
                "kit-handoff", 1L, "OVERWORLD",
                new int[]{0, 60, 0}, new int[]{9, 70, 9},
                List.of(), Map.of(), List.of(), List.of(), true, List.of(5));
        assertThrows(IOException.class, () -> store.savePlan(invalid));
    }

    private OrganizerJournalStore.Checkpoint inventoryCheckpoint(String jobId) {
        var ordinary = checkpoint(jobId, List.of(), List.of());
        // Gson round-trip keeps this test focused on the schema field while preserving the
        // complete checkpoint fixture used by the ordinary-plan tests below.
        var json = com.google.gson.JsonParser.parseString(
                new com.google.gson.Gson().toJson(ordinary)).getAsJsonObject();
        json.addProperty("schemaVersion", OrganizerJournalStore.INVENTORY_RECOVERY_SCHEMA_VERSION);
        return new com.google.gson.Gson().fromJson(json,
                OrganizerJournalStore.Checkpoint.class);
    }

    @Test
    void rejectsAnIncompleteAtomicPair() throws Exception {
        OrganizerJournalStore store = store();
        Files.writeString(temporaryDirectory.resolve("checkpoint.json"), "{}");

        IOException error = assertThrows(IOException.class, store::load);
        assertTrue(error.getMessage().contains("incomplete"));
    }

    @Test
    void roundTripsMixedDecompositionTaskMetadata() throws Exception {
        OrganizerJournalStore store = store();
        var task = new OrganizerJournalStore.TaskSnapshot(
                1, new int[]{1, 2, 3}, new int[]{4, 5, 6},
                "purple_shulker_box", "fingerprint", false,
                true, false, Map.of("brick_stairs", 64, "brick_slab", 32));
        store.savePlan(new OrganizerJournalStore.Plan(
                OrganizerJournalStore.SCHEMA_VERSION,
                "mixed-job", 1L, "OVERWORLD",
                new int[]{0, 0, 0}, new int[]{9, 9, 9},
                List.of(task), Map.of(), List.of()));
        store.saveCheckpoint(checkpoint("mixed-job", List.of(1), List.of()));

        var loadedTask = store.load().orElseThrow().plan().tasks().getFirst();
        assertTrue(loadedTask.mixedDecomposition());
        assertEquals(Map.of("brick_stairs", 64, "brick_slab", 32),
                loadedTask.mixedContents());
    }

    @Test
    void acceptsLegacyTaskWithoutMandatoryDepositIntent() throws Exception {
        OrganizerJournalStore store = store();
        var task = new OrganizerJournalStore.TaskSnapshot(
                1, new int[]{1,2,3}, new int[]{4,5,6}, "yellow_shulker_box", "@empty", true);
        store.savePlan(new OrganizerJournalStore.Plan(
                OrganizerJournalStore.SCHEMA_VERSION, "legacy-task", 1L, "OVERWORLD",
                new int[]{0,0,0}, new int[]{9,9,9}, List.of(task), Map.of(), List.of()));
        store.saveCheckpoint(checkpoint("legacy-task", List.of(1), List.of()));
        Path path = temporaryDirectory.resolve("plan.json");
        var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        json.getAsJsonArray("tasks").get(0).getAsJsonObject().remove("mandatoryInventoryDeposit");
        Files.writeString(path,json.toString());
        var loaded = store.load().orElseThrow().plan().tasks().getFirst();
        assertFalse(loaded.mandatoryInventoryDeposit());
        assertTrue(loaded.alreadyInInventory());
        assertEquals("@empty",loaded.shulkerContentFilter());
    }

    @Test
    void acceptsLegacyCheckpointWithoutProtectedSlotSnapshot() throws Exception {
        OrganizerJournalStore store = store();
        store.savePlan(new OrganizerJournalStore.Plan(
                OrganizerJournalStore.SCHEMA_VERSION,
                "legacy-job", 1L, "OVERWORLD",
                new int[]{0, 0, 0}, new int[]{9, 9, 9},
                List.of(), Map.of(), List.of()));
        store.saveCheckpoint(checkpoint("legacy-job", List.of(), List.of()));

        Path checkpointPath = temporaryDirectory.resolve("checkpoint.json");
        String json = Files.readString(checkpointPath);
        int fieldStart = json.lastIndexOf(",\n  \"protectedInventorySlots\"");
        assertTrue(fieldStart > 0);
        Files.writeString(checkpointPath, json.substring(0, fieldStart) + "\n}");

        assertNull(store.load().orElseThrow().checkpoint().protectedInventorySlots());
        assertNull(store.load().orElseThrow().checkpoint().packingProgress());
        assertNull(store.load().orElseThrow().checkpoint().lastFailure());
        assertNull(store.load().orElseThrow().checkpoint().mixedStagingLedger());
    }

    @Test
    void organizerRehydratesTheQueueAsAYieldedRestartCheckpoint() throws Exception {
        OrganizerJournalStore store = store();
        var task = new OrganizerJournalStore.TaskSnapshot(
                1, new int[]{1, 2, 3}, new int[]{4, 5, 6},
                "obsidian", null, false);
        store.savePlan(new OrganizerJournalStore.Plan(
                OrganizerJournalStore.SCHEMA_VERSION,
                "resume-job", 1L, "OVERWORLD",
                new int[]{0, 0, 0}, new int[]{9, 9, 9},
                List.of(task), Map.of(), List.of()));
        var checkpoint = checkpoint("resume-job", List.of(), List.of());
        checkpoint = new OrganizerJournalStore.Checkpoint(
                checkpoint.schemaVersion(), checkpoint.jobId(), checkpoint.updatedAtEpochMilli(),
                checkpoint.interruptedState(), checkpoint.currentRole(), 1,
                checkpoint.taskQueue(), checkpoint.consolidationQueue(),
                checkpoint.consolidationMode(), checkpoint.consolidationSourcesInBatch(),
                checkpoint.movedThisVisit(), checkpoint.sourceVisitFailed(),
                checkpoint.taskCargoAcquired(), checkpoint.taskCargoDeposited(), 1, 0, 25,
                checkpoint.reconciliationStation(), checkpoint.reconciliationWorksite(),
                checkpoint.packItemId(), checkpoint.packDestination(), checkpoint.shulkerPlacePos(),
                checkpoint.fetchedPackingShulker(), checkpoint.shulkerInventoryCountBeforePlacement(),
                checkpoint.compatibleShulkerCountBeforePlacement(),
                checkpoint.temporaryShulkerOutstanding(), checkpoint.stagingImportDestinations(),
                checkpoint.stagingStorageClassesPlanned(), checkpoint.stagedStorageClasses(),
                checkpoint.stagedShulkers(), checkpoint.permanentLaneGaps(),
                checkpoint.stagingReason(), checkpoint.overflowItems(),
                checkpoint.mixedDecompositionMode(), checkpoint.mixedBatchConsolidationMode(),
                checkpoint.mixedBoxDrained(), checkpoint.decomposedMixedShulkers(),
                12,
                checkpoint.mixedPendingSourceSlot(),
                checkpoint.mixedPendingCargoSlot(), checkpoint.mixedCargoSlots(),
                checkpoint.mixedStagingUsedDestinations(),
                checkpoint.protectedInventorySlots(),
                checkpoint.stopAfterShulkerRecovery(),
                checkpoint.shulkerRecoveryTrigger(), checkpoint.packingProgress(), checkpoint.lastFailure(),
                checkpoint.mixedStagingLedger(), checkpoint.packingSupply(), checkpoint.inventoryRecovery(),
                checkpoint.stationWalkRecovery(), checkpoint.mixedShellVerification(),
                checkpoint.packedImportTriedDestinationKeys(),
                new OrganizerJournalStore.ShulkerPickupEvidence(
                        true, "bulk:iron_block", "iron_block", 2));
        store.saveCheckpoint(checkpoint);

        StashManagerConfig config = new StashManagerConfig();
        config.pos1 = new int[]{0, 0, 0};
        config.pos2 = new int[]{9, 9, 9};
        StashOrganizer organizer = new StashOrganizer(config, new ContainerIndex(), store);

        assertEquals(StashOrganizer.DurableRestoreResult.RESTORED,
                organizer.restoreDurableCheckpoint());
        assertTrue(organizer.isYielded());
        assertTrue(organizer.isDurableRecoveryLoaded());
        assertEquals(StashOrganizer.State.WALKING, organizer.getYieldedFromState());
        assertEquals(1, organizer.getTotalTasks());
        assertEquals(12, organizer.getGeneratedLooseTasks());
        var ledgerField = StashOrganizer.class.getDeclaredField("mixedStagingLedger");
        ledgerField.setAccessible(true);
        assertEquals(checkpoint.mixedStagingLedger(),
                ((MixedStagingLedger) ledgerField.get(organizer)).snapshot());
        var pickupField = StashOrganizer.class.getDeclaredField("temporaryShulkerPickupConfirmed");
        pickupField.setAccessible(true);
        assertTrue(pickupField.getBoolean(organizer));
        var storageKeyField = StashOrganizer.class.getDeclaredField(
                "temporaryShulkerPickupStorageKey");
        storageKeyField.setAccessible(true);
        assertEquals("iron_block", storageKeyField.get(organizer));
        var recoveryField = StashOrganizer.class.getDeclaredField(
                "packedShulkerInventorySyncRecoveries");
        recoveryField.setAccessible(true);
        assertEquals(2, recoveryField.getInt(organizer));
        var keepRefreshField = StashOrganizer.class.getDeclaredField(
                "keepProtectionNeedsRefresh");
        keepRefreshField.setAccessible(true);
        assertTrue(keepRefreshField.getBoolean(organizer));
        var importProbeField = StashOrganizer.class.getDeclaredField(
                "packStoreTriedDestinations");
        importProbeField.setAccessible(true);
        assertEquals(Set.of(101L, 202L), importProbeField.get(organizer));
    }

    private OrganizerJournalStore store() {
        return new OrganizerJournalStore(
                temporaryDirectory.resolve("plan.json"),
                temporaryDirectory.resolve("checkpoint.json"));
    }

    private static OrganizerJournalStore.Checkpoint checkpoint(
            String jobId, List<Integer> normal, List<Integer> consolidation) {
        return new OrganizerJournalStore.Checkpoint(
                OrganizerJournalStore.SCHEMA_VERSION,
                jobId,
                456L,
                "WALKING",
                "SOURCE",
                null,
                normal,
                consolidation,
                false,
                0,
                0,
                false,
                0,
                0,
                normal.size() + consolidation.size(),
                0,
                25,
                new int[]{1, 2, 3},
                new int[]{1, 2, 4},
                null,
                null,
                null,
                false,
                0,
                0,
                false,
                List.of(),
                List.of(),
                List.of(),
                0,
                0,
                null,
                Map.of(),
                false,
                false,
                false,
                0,
                0,
                -1,
                -1,
                List.of(),
                List.of(),
                List.of(9, 42),
                false,
                null,
                new OrganizerJournalStore.PackingProgress(64, 0, true, false),
                new OrganizerJournalStore.Failure("inventory_transfer_unavailable_with_cargo", "SHULKER_FETCH_TAKE", 123),
                new MixedStagingLedger.Snapshot(List.of(
                        new MixedStagingLedger.Source("obsidian", 4, 5, 6)), List.of(
                        new MixedStagingLedger.Source("end_crystal", 7, 8, 9)), true), null, null, null, null,
                List.of(101L, 202L), null);
    }
}
