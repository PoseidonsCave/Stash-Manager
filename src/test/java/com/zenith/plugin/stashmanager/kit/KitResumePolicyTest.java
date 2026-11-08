package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitResumePolicyTest {
    @Test void unconfirmedSourceDepositRequiresFullChestAuditBeforeReroute() {
        var source = new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{1, 130, 2}, "shulker_box",
                Map.of("diamond_helmet[unbreaking=3]", 26));
        var checkpoint = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("diamond_helmet[unbreaking=3]", 1),
                Map.of("diamond_helmet[unbreaking=3]", 1), 25, 10,
                "diamond_helmet[unbreaking=3]", 1, 37, new int[]{5, 132, 6},
                null, null, null, Map.of(),
                "kit_source_return_failed:export_deposit_unconfirmed",
                100L, null, 0L, List.of(source));
        assertTrue(KitResumePolicy.canAuditFailedSourceReturn(checkpoint));
        assertFalse(KitResumePolicy.canRerouteSourceReturn(checkpoint));
        assertEquals("uncertain_failed_source_return",
                KitResumePolicy.blocker(checkpoint, "VERIFY_FAILED_RETURN"));
        var selected = new KitFulfillmentJournalStore.BorrowedBox(
                source.sourcePosition(), source.itemId(), source.contents(),
                new int[]{3, 130, 4});
        var scheduled = new KitFulfillmentJournalStore.Snapshot(
                checkpoint.schemaVersion(), checkpoint.kitName(), checkpoint.state(),
                checkpoint.recipe(), checkpoint.packedContents(), checkpoint.requestedBoxes(),
                checkpoint.completedBoxes(), checkpoint.currentItem(), checkpoint.currentQuantity(),
                checkpoint.ownedShellSlot(), checkpoint.exportPosition(), null, null, null,
                Map.of(), "resume_audit_failed_source_return", 101L,
                "VERIFY_FAILED_RETURN", 200L, List.of(selected));
        assertNull(KitResumePolicy.blocker(scheduled));
        assertFalse(KitResumePolicy.canAuditFailedSourceReturn(scheduled));
    }

    @Test void postcloseSourceReturnRequiresReadOnlyAuditNotDepositReplay() {
        var source = new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{1, 130, 2}, "pink_shulker_box",
                Map.of("end_crystal", 1024), new int[]{3, 130, 4});
        var checkpoint = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("end_crystal", 256, "ender_chest", 16),
                Map.of("end_crystal", 256), 25, 10, "end_crystal", 256,
                37, new int[]{5, 132, 6}, null, null, null, Map.of(),
                "kit_source_return_failed:export_postclose_verification_mismatch",
                100L, null, 0L, List.of(source));
        assertTrue(KitResumePolicy.canAuditCompletedSourceReturn(checkpoint));
        assertFalse(KitResumePolicy.canRerouteSourceReturn(checkpoint));
        assertEquals("uncertain_completed_source_return",
                KitResumePolicy.blocker(checkpoint, "VERIFY_RETURN"));

        var scheduled = new KitFulfillmentJournalStore.Snapshot(
                checkpoint.schemaVersion(), checkpoint.kitName(), checkpoint.state(),
                checkpoint.recipe(), checkpoint.packedContents(), checkpoint.requestedBoxes(),
                checkpoint.completedBoxes(), checkpoint.currentItem(), checkpoint.currentQuantity(),
                checkpoint.ownedShellSlot(), checkpoint.exportPosition(), null, null, null,
                Map.of(), "resume_audit_completed_source_return", 101L, "VERIFY_RETURN",
                200L, List.of(source));
        assertNull(KitResumePolicy.blocker(scheduled));
        assertFalse(KitResumePolicy.canAuditCompletedSourceReturn(scheduled));
        assertFalse(KitResumePolicy.canAuditCompletedSourceReturn(
                new KitFulfillmentJournalStore.Snapshot(
                        checkpoint.schemaVersion(), checkpoint.kitName(), checkpoint.state(),
                        checkpoint.recipe(), checkpoint.packedContents(), checkpoint.requestedBoxes(),
                        checkpoint.completedBoxes(), checkpoint.currentItem(),
                        checkpoint.currentQuantity(), checkpoint.ownedShellSlot(),
                        checkpoint.exportPosition(), null, null, null, Map.of(),
                        checkpoint.blocker(), 102L, null, 0L,
                        List.of(new KitFulfillmentJournalStore.BorrowedBox(
                                source.sourcePosition(), source.itemId(), source.contents())))));
    }

    @Test void recoveredBorrowedBoxBatchRequiresOneUnauditedPendingSource() {
        var source = new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{3, 130, 4}, "purple_shulker_box",
                Map.of("firework_rocket", 1600));
        var blocked = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("totem_of_undying", 6, "firework_rocket", 128),
                Map.of("totem_of_undying", 6), 25, 10, "firework_rocket", 128,
                39, new int[]{1, 132, 2}, null, null, null, Map.of(),
                "recovered_box_requires_cargo_audit", 100L, null, 0L,
                List.of(source));
        assertTrue(KitResumePolicy.canAuditRecoveredBorrowedCargo(blocked));
        assertFalse(KitResumePolicy.canAuditRecoveredBorrowedCargo(
                new KitFulfillmentJournalStore.Snapshot(
                        blocked.schemaVersion(), blocked.kitName(), blocked.state(),
                        blocked.recipe(), blocked.packedContents(), blocked.requestedBoxes(),
                        blocked.completedBoxes(), blocked.currentItem(), blocked.currentQuantity(),
                        blocked.ownedShellSlot(), blocked.exportPosition(), null, null,
                        null, Map.of(), blocked.blocker(), 101L, null, 0L,
                        List.of(source, source))));
        assertFalse(KitResumePolicy.canAuditRecoveredBorrowedCargo(
                new KitFulfillmentJournalStore.Snapshot(
                        blocked.schemaVersion(), blocked.kitName(), blocked.state(),
                        blocked.recipe(), blocked.packedContents(), blocked.requestedBoxes(),
                        blocked.completedBoxes(), blocked.currentItem(), blocked.currentQuantity(),
                        blocked.ownedShellSlot(), blocked.exportPosition(), null, null,
                        null, Map.of(), blocked.blocker(), 101L, "WAIT_CARGO", 0L,
                        List.of(source), -1, List.of(), Map.of(13, 64, 14, 64))));
    }

    @Test void auditedVariantSourceResumesOnlyItsPackOnlyPhase() {
        var repair = new KitFulfillmentJournalStore.VariantRepair(
                new int[]{3, 130, 4}, 54, 13, "a".repeat(64), "end_crystal", 15,
                24, 21, "b".repeat(64), "c".repeat(64), "d".repeat(64),
                -1, null, null).withAuditedIncoming(14);
        var checkpoint = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("diamond_helmet", 1, "end_crystal", 256),
                Map.of("end_crystal", 256), 25, 7, "diamond_helmet", 1, 37,
                new int[]{1, 130, 2}, null, null, null, Map.of(),
                "resume_variant_source_cargo", 100L, "VARIANT_WAIT_SOURCE", 300_000L,
                List.of()).withVariantRepair(repair);
        assertNull(KitResumePolicy.blocker(checkpoint));
        assertEquals("uncertain_variant_source_cargo",
                KitResumePolicy.blocker(checkpoint.withVariantRepair(
                        repair.withAuditedIncoming(0))));
        assertEquals("uncertain_variant_exchange",
                KitResumePolicy.blocker(checkpoint, "VARIANT_NEED_SOURCE"));
    }

    @Test void exactMultiSourcePreflightMayRearmOnlyItsSavedAuditedBatch() {
        var empty = new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{1, 132, 2}, "white_shulker_box", Map.of());
        var crystals = new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{1, 130, 4}, "shulker_box", Map.of("end_crystal", 1585));
        var checkpoint = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("obsidian", 128, "end_crystal", 256),
                Map.of("obsidian", 128), 25, 7, "end_crystal", 256,
                44, new int[]{1, 130, 2}, null, null, null, Map.of(),
                "borrowed_source_return_ambiguous", 100L, "WAIT_CARGO", 0L,
                List.of(empty, crystals), -1, List.of(),
                Map.of(13, 64, 14, 49, 21, 64, 22, 64, 23, 15),
                0, Map.of(), Map.of());
        assertTrue(KitResumePolicy.canRetryAuditedMultiSourcePreflight(checkpoint));
        assertFalse(KitResumePolicy.canRetryAuditedMultiSourcePreflight(
                new KitFulfillmentJournalStore.Snapshot(
                        checkpoint.schemaVersion(), checkpoint.kitName(), checkpoint.state(),
                        checkpoint.recipe(), checkpoint.packedContents(), checkpoint.requestedBoxes(),
                        checkpoint.completedBoxes(), checkpoint.currentItem(), 255,
                        checkpoint.ownedShellSlot(), checkpoint.exportPosition(), null, null,
                        null, Map.of(), checkpoint.blocker(), 101L, checkpoint.resumeState(),
                        0L, checkpoint.sourceBoxesToReturn(), -1, List.of(),
                        checkpoint.auditedCargoSlots(), 0, Map.of(), Map.of())));
        assertFalse(KitResumePolicy.canRetryAuditedMultiSourcePreflight(
                new KitFulfillmentJournalStore.Snapshot(
                        checkpoint.schemaVersion(), checkpoint.kitName(), checkpoint.state(),
                        checkpoint.recipe(), checkpoint.packedContents(), checkpoint.requestedBoxes(),
                        checkpoint.completedBoxes(), checkpoint.currentItem(),
                        checkpoint.currentQuantity(), checkpoint.ownedShellSlot(),
                        checkpoint.exportPosition(), null, null, null, Map.of(),
                        checkpoint.blocker(), 101L, checkpoint.resumeState(), 0L,
                        List.of(crystals), -1, List.of(), checkpoint.auditedCargoSlots(),
                        0, Map.of(), Map.of())));
    }

    @Test void unjournaledInventoryBorrowedBoxAuditRequiresPackedBoundary() {
        var blocked = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("diamond_sword", 1, "obsidian", 128),
                Map.of("diamond_sword", 1), 25, 7,
                "diamond_sword", 1, 39, new int[]{1, 64, 2},
                null, null, null, Map.of(), "kit_inventory_unexpected_items",
                100L, null, 0L, List.of());
        assertTrue(KitResumePolicy.canAuditUnjournaledBorrowedSource(blocked));
        var packedCrystals = new KitFulfillmentJournalStore.Snapshot(
                blocked.schemaVersion(), blocked.kitName(), blocked.state(),
                Map.of("end_crystal", 256), Map.of("end_crystal", 256),
                blocked.requestedBoxes(), blocked.completedBoxes(), "end_crystal", 256,
                blocked.ownedShellSlot(), blocked.exportPosition(), null, null,
                null, Map.of(), blocked.blocker(), 100L, null, 0L, List.of());
        assertTrue(KitResumePolicy.canAuditUnjournaledBorrowedSource(packedCrystals));
        var beforePacking = new KitFulfillmentJournalStore.Snapshot(
                blocked.schemaVersion(), blocked.kitName(), blocked.state(),
                blocked.recipe(), Map.of(), blocked.requestedBoxes(),
                blocked.completedBoxes(), blocked.currentItem(), blocked.currentQuantity(),
                blocked.ownedShellSlot(), blocked.exportPosition(), null, null,
                null, Map.of(), blocked.blocker(), 100L, null, 0L, List.of());
        assertFalse(KitResumePolicy.canAuditUnjournaledBorrowedSource(beforePacking));
    }

    @Test void attestedMissingBorrowedBoxCanAuditOnlyItsExactLooseCargoBoundary() {
        var loss = new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{3, 64, 4}, "blue_shulker_box",
                Map.of("diamond_sword[sharpness=5]", 26));
        var checkpoint = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("diamond_sword[sharpness=5]", 1), Map.of(), 25, 6,
                "diamond_sword[sharpness=5]", 1, 37, new int[]{1, 64, 2},
                null, null, null, Map.of(),
                "lost_borrowed_source_operator_attested_needs_cargo_audit",
                100, null, 0, List.of(), -1, List.of(loss), Map.of(),
                0, Map.of(), Map.of());
        assertTrue(KitResumePolicy.canAuditLostBorrowedCargo(checkpoint, 13, 1));
        assertFalse(KitResumePolicy.canAuditLostBorrowedCargo(checkpoint, 13, 2));
        assertFalse(KitResumePolicy.canAuditLostBorrowedCargo(checkpoint, 37, 1));
        assertFalse(KitResumePolicy.canAuditLostBorrowedCargo(checkpoint, 45, 1));
        var unacknowledged = new KitFulfillmentJournalStore.Snapshot(
                checkpoint.schemaVersion(), checkpoint.kitName(), checkpoint.state(),
                checkpoint.recipe(), checkpoint.packedContents(), checkpoint.requestedBoxes(),
                checkpoint.completedBoxes(), checkpoint.currentItem(), checkpoint.currentQuantity(),
                checkpoint.ownedShellSlot(), checkpoint.exportPosition(), null, null,
                null, Map.of(), checkpoint.blocker(), 100, null, 0, List.of(), -1,
                List.of(), Map.of(), 0, Map.of(), Map.of());
        assertFalse(KitResumePolicy.canAuditLostBorrowedCargo(unacknowledged, 13, 1));
    }

    @Test void appleOverflowAuditRequiresVerifiedPackedBoundary() {
        var blocked = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("enchanted_golden_apple", 128, "obsidian", 128),
                Map.of("enchanted_golden_apple", 128), 25, 6,
                "enchanted_golden_apple", 128, 37, new int[]{1, 64, 2},
                null, null, null, Map.of(), "kit_inventory_unexpected_items",
                100L, null, 0L, List.of(), -1, List.of(), Map.of(),
                0, Map.of(), Map.of());
        assertTrue(KitResumePolicy.canAuditAppleOverflow(blocked));
        assertFalse(KitResumePolicy.canAuditAppleOverflow(
                blocked.withDisplacedAppleSlots(Map.of(16,
                        new KitInventoryAppleOwnership.Slot(54, true, "a".repeat(64))))));
        var worksite = new KitFulfillmentJournalStore.Snapshot(
                blocked.schemaVersion(), blocked.kitName(), blocked.state(),
                blocked.recipe(), blocked.packedContents(), blocked.requestedBoxes(),
                blocked.completedBoxes(), blocked.currentItem(), blocked.currentQuantity(),
                blocked.ownedShellSlot(), blocked.exportPosition(), new int[]{3, 64, 4},
                null, null, Map.of(), blocked.blocker(), 100L, null, 0L,
                List.of(), -1, List.of(), Map.of(), 0, Map.of(), Map.of());
        assertFalse(KitResumePolicy.canAuditAppleOverflow(worksite));
    }

    @Test void writtenOffBorrowedSourceNeedsExplicitNoCargoBoundary() {
        var loss = new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{3, 64, 4}, "black_shulker_box",
                Map.of("enchanted_golden_apple", 1728));
        var checkpoint = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("obsidian", 128, "enchanted_golden_apple", 128),
                Map.of("obsidian", 128), 10, 8, "enchanted_golden_apple", 64,
                37, new int[]{1, 64, 2}, null, null, null, Map.of(),
                "lost_borrowed_source_operator_attested_needs_cargo_audit",
                100, null, 0, List.of(), -1, List.of(loss), Map.of(),
                0, Map.of(), Map.of());
        assertTrue(KitResumePolicy.canAuditLostBorrowedNoCargo(checkpoint));
        assertFalse(KitResumePolicy.canAuditLostBorrowedNoCargo(
                new KitFulfillmentJournalStore.Snapshot(
                        checkpoint.schemaVersion(), checkpoint.kitName(), checkpoint.state(),
                        checkpoint.recipe(), checkpoint.packedContents(), checkpoint.requestedBoxes(),
                        checkpoint.completedBoxes(), checkpoint.currentItem(), checkpoint.currentQuantity(),
                        checkpoint.ownedShellSlot(), checkpoint.exportPosition(), null,
                        new int[]{4, 64, 4}, "black_shulker_box", loss.contents(),
                        checkpoint.blocker(), 100, null, 0, List.of(), -1,
                        List.of(loss), Map.of(), 0, Map.of(), Map.of())));
        assertFalse(KitResumePolicy.canAuditLostBorrowedNoCargo(
                new KitFulfillmentJournalStore.Snapshot(
                        checkpoint.schemaVersion(), checkpoint.kitName(), checkpoint.state(),
                        checkpoint.recipe(), checkpoint.packedContents(), checkpoint.requestedBoxes(),
                        checkpoint.completedBoxes(), checkpoint.currentItem(), checkpoint.currentQuantity(),
                        checkpoint.ownedShellSlot(), checkpoint.exportPosition(), null, null,
                        null, Map.of(), checkpoint.blocker(), 100, null, 0,
                        List.of(), -1, List.of(), Map.of(), 0, Map.of(), Map.of())));
        assertNull(KitResumePolicy.blocker(checkpoint, "NEED_CARGO"));
    }

    @Test void blockedBeforeBorrowingMayRetryWithOneCargoSlot() {
        var checkpoint = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("diamond_sword[sharpness=5]", 1, "obsidian", 128),
                Map.of("obsidian", 128), 10, 8, "diamond_sword[sharpness=5]",
                0, 37, new int[]{1, 64, 2}, null, null, null, Map.of(),
                "kit_main_inventory_batch_headroom_insufficient", 100, null, 0);
        assertTrue(KitResumePolicy.canRetryAfterMainHeadroom(checkpoint));
        assertNull(KitResumePolicy.blocker(checkpoint, "NEED_CARGO"));
        var attempted = new KitFulfillmentJournalStore.Snapshot(
                checkpoint.schemaVersion(), checkpoint.kitName(), checkpoint.state(),
                checkpoint.recipe(), checkpoint.packedContents(), checkpoint.requestedBoxes(),
                checkpoint.completedBoxes(), checkpoint.currentItem(), 1,
                checkpoint.ownedShellSlot(), checkpoint.exportPosition(), null, null,
                null, Map.of(), checkpoint.blocker(), 100, null, 0);
        assertFalse(KitResumePolicy.canRetryAfterMainHeadroom(attempted));
    }

    @Test void uniquelyAuditedUnverifiedCargoResumesAsExistingCargo() {
        var checkpoint = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("diamond_leggings[mending=1,protection=4,unbreaking=3]", 1),
                Map.of(), 10, 2, "diamond_leggings[mending=1,protection=4,unbreaking=3]",
                1, 38, new int[]{1, 64, 2}, null, null, null, Map.of(),
                "kit_retrieved_cargo_unverified:0/1", 100, null, 0);
        assertTrue(KitResumePolicy.canResumeAuditedCargo(checkpoint));
        assertNull(KitResumePolicy.blocker(new KitFulfillmentJournalStore.Snapshot(
                checkpoint.schemaVersion(), checkpoint.kitName(), checkpoint.state(),
                checkpoint.recipe(), checkpoint.packedContents(), checkpoint.requestedBoxes(),
                checkpoint.completedBoxes(), checkpoint.currentItem(), checkpoint.currentQuantity(),
                checkpoint.ownedShellSlot(), checkpoint.exportPosition(), checkpoint.kitWorksite(),
                checkpoint.borrowedSourceWorksite(), checkpoint.borrowedSourceItemId(),
                checkpoint.borrowedSourceContents(), "resume_audited_inventory_cargo", 100,
                "WAIT_CARGO", 300_000)));
    }

    @Test void borrowedUnverifiedBatchRequiresPinnedAuditBeforeResume() {
        var source = new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{3, 64, 4}, "shulker_box", Map.of("obsidian", 927));
        var blocked = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("obsidian", 128), Map.of(), 25, 0, "obsidian", 128,
                37, new int[]{1, 64, 2}, null, null, null, Map.of(),
                "kit_retrieved_cargo_unverified:95/128", 100, null, 0,
                java.util.List.of(source));
        assertTrue(KitResumePolicy.canAuditUnverifiedBorrowedCargo(blocked));
        assertFalse(KitResumePolicy.canResumeAuditedCargo(blocked));
        var admissionRejected = new KitFulfillmentJournalStore.Snapshot(
                blocked.schemaVersion(), blocked.kitName(), blocked.state(),
                blocked.recipe(), blocked.packedContents(), blocked.requestedBoxes(),
                blocked.completedBoxes(), blocked.currentItem(), blocked.currentQuantity(),
                blocked.ownedShellSlot(), blocked.exportPosition(), null, null,
                null, Map.of(), "unexpected_inventory_slots:[39]", 101,
                "WAIT_CARGO", 0, java.util.List.of(source), -1, java.util.List.of(),
                Map.of(13, 31, 14, 64, 44, 33));
        assertTrue(KitResumePolicy.canAuditUnverifiedBorrowedCargo(admissionRejected));
        assertFalse(KitResumePolicy.canAuditUnverifiedBorrowedCargo(
                new KitFulfillmentJournalStore.Snapshot(
                        blocked.schemaVersion(), blocked.kitName(), blocked.state(),
                        blocked.recipe(), blocked.packedContents(), blocked.requestedBoxes(),
                        blocked.completedBoxes(), blocked.currentItem(), blocked.currentQuantity(),
                        blocked.ownedShellSlot(), blocked.exportPosition(), null, null,
                        null, Map.of(), blocked.blocker(), 100, null, 0)));
    }

    @Test void recoveredExactPackedBatchCanContinueWithoutReplayingCargo() {
        var verified = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("experience_bottle", 128, "obsidian", 128),
                Map.of("experience_bottle", 128), 10, 1, null, 0,
                26, new int[]{1, 64, 2}, null, null, null, Map.of(),
                "recovered_packed_batch_verified", 100, null, 0);
        assertTrue(KitResumePolicy.canResumeRecoveredPackedBatch(verified));
        assertNull(KitResumePolicy.blocker(verified, "NEED_CARGO"));
        var unverified = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                verified.recipe(), Map.of("experience_bottle", 129), 10, 1, null, 0,
                26, verified.exportPosition(), null, null, null, Map.of(),
                "recovered_packed_batch_verified", 100, null, 0);
        assertFalse(KitResumePolicy.canResumeRecoveredPackedBatch(unverified));
    }

    @Test void failedAppleFetchCanAuditOnlyAtCleanUnpackedBoundary() {
        var blocked = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("obsidian", 128, "enchanted_golden_apple", 128),
                Map.of("obsidian", 128), 25, 0, "enchanted_golden_apple", 128,
                37, new int[]{1, 64, 2}, null, null, null, Map.of(),
                "kit_cargo_retrieval_incomplete", 100, null, 0);
        assertTrue(KitResumePolicy.canAuditKeptAppleCargo(blocked));
        var scheduled = new KitFulfillmentJournalStore.Snapshot(
                blocked.schemaVersion(), blocked.kitName(), blocked.state(),
                blocked.recipe(), blocked.packedContents(), blocked.requestedBoxes(),
                blocked.completedBoxes(), blocked.currentItem(), blocked.currentQuantity(),
                blocked.ownedShellSlot(), blocked.exportPosition(), null, null,
                null, Map.of(), "resume_audited_kept_apple_cargo", 101,
                "WAIT_CARGO", 300_000, List.of(), -1, List.of(),
                Map.of(13, 56, 17, 8, 28, 64));
        assertNull(KitResumePolicy.blocker(scheduled));
        assertFalse(KitResumePolicy.canAuditKeptAppleCargo(scheduled));
    }

    @Test void partialBorrowedAppleCargoRequiresOneRecoveredSourceAndNoWorldBox() {
        var source = new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{3, 64, 4}, "shulker_box",
                Map.of("enchanted_golden_apple", 1664));
        var blocked = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("enchanted_golden_apple", 128), Map.of(), 25, 4,
                "enchanted_golden_apple", 128, 37, new int[]{1, 64, 2},
                null, null, null, Map.of(), "kit_cargo_retrieval_incomplete",
                100, null, 0, List.of(source));
        assertTrue(KitResumePolicy.canAuditPartialBorrowedAppleCargo(blocked));
        assertFalse(KitResumePolicy.canAuditKeptAppleCargo(blocked));
        var worldBox = new KitFulfillmentJournalStore.Snapshot(
                blocked.schemaVersion(), blocked.kitName(), blocked.state(),
                blocked.recipe(), blocked.packedContents(), blocked.requestedBoxes(),
                blocked.completedBoxes(), blocked.currentItem(), blocked.currentQuantity(),
                blocked.ownedShellSlot(), blocked.exportPosition(), null,
                new int[]{3, 64, 5}, "shulker_box", source.contents(),
                blocked.blocker(), 100, null, 0, List.of(source));
        assertFalse(KitResumePolicy.canAuditPartialBorrowedAppleCargo(worldBox));
    }

    @Test void rejectedPackerPreflightMayOnlyReauditItsPinnedUnplacedBatch() {
        var source = new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{3, 64, 4}, "shulker_box",
                Map.of("enchanted_golden_apple", 1664));
        var checkpoint = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("enchanted_golden_apple", 128), Map.of("obsidian", 128),
                25, 4, "enchanted_golden_apple", 54, 37,
                new int[]{1, 64, 2}, null, null, null, Map.of(),
                "kit_packer_start_failed", 100, null, 0, List.of(source),
                -1, List.of(), Map.of(13, 35, 14, 19), 0, Map.of(), Map.of());
        assertTrue(KitResumePolicy.canRetryPackerPreflight(checkpoint));
        assertFalse(KitResumePolicy.canRetryPackerPreflight(
                new KitFulfillmentJournalStore.Snapshot(
                        checkpoint.schemaVersion(), checkpoint.kitName(), checkpoint.state(),
                        checkpoint.recipe(), checkpoint.packedContents(), checkpoint.requestedBoxes(),
                        checkpoint.completedBoxes(), checkpoint.currentItem(), checkpoint.currentQuantity(),
                        checkpoint.ownedShellSlot(), checkpoint.exportPosition(),
                        new int[]{3, 64, 5}, null, null, Map.of(), checkpoint.blocker(),
                        100, null, 0, List.of(source), -1, List.of(),
                        checkpoint.auditedCargoSlots(), 0, Map.of(), Map.of())));
    }
    private static KitFulfillmentJournalStore.Snapshot snapshot(
            String phase, int shellSlot, int[] worksite) {
        return new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("obsidian", 64), Map.of(), 5, 0, null, 0,
                shellSlot, new int[]{1, 64, 2}, worksite, null, null, Map.of(),
                "interrupted", 100, phase, 300_000);
    }

    @Test void cleanBoundariesMayResume() {
        assertNull(KitResumePolicy.blocker(snapshot("NEED_SHELL", -1, null)));
        assertNull(KitResumePolicy.blocker(snapshot("NEED_CARGO", 20, null)));
    }

    @Test void uncertainTransactionsRequireReview() {
        assertEquals("uncertain_wait_cargo",
                KitResumePolicy.blocker(snapshot("WAIT_CARGO", 20, null)));
        assertEquals("world_box_recovery_pending",
                KitResumePolicy.blocker(snapshot("PACKING", 20, new int[]{2, 64, 2})));
    }

    @Test void onlyUnverifiedCargoCanRetryAtTheVerifiedShellBoundary() {
        var unverified = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("end_crystal", 128), Map.of("enchanted_golden_apple", 16),
                1, 0, "end_crystal", 128, 41, new int[]{1, 64, 2},
                null, null, null, Map.of(),
                "kit_retrieved_cargo_unverified:0/128", 100, null, 0);
        assertTrue(KitResumePolicy.canRetryUnverifiedCargo(unverified));
        assertNull(KitResumePolicy.blocker(unverified, "NEED_CARGO"));

        var unresolved = new KitFulfillmentJournalStore.Snapshot(
                unverified.schemaVersion(), unverified.kitName(), unverified.state(),
                unverified.recipe(), unverified.packedContents(), unverified.requestedBoxes(),
                unverified.completedBoxes(), unverified.currentItem(), unverified.currentQuantity(),
                unverified.ownedShellSlot(), unverified.exportPosition(), null,
                new int[]{3, 64, 2}, "light_blue_shulker_box", Map.of(),
                unverified.blocker(), 100, null, 0);
        assertFalse(KitResumePolicy.canRetryUnverifiedCargo(unresolved));
        assertEquals("world_box_recovery_pending",
                KitResumePolicy.blocker(unresolved, "NEED_CARGO"));
    }

    @Test void packedBatchMayRetryAfterHotbarSpaceIsRestored() {
        var headroom = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("glowstone", 64, "end_crystal", 128),
                Map.of("glowstone", 64, "end_crystal", 128),
                1, 0, "glowstone", 64, 41, new int[]{1, 64, 2},
                null, null, null, Map.of(), "kit_hotbar_slots_unavailable",
                100, null, 0);
        assertTrue(KitResumePolicy.canRetryAfterHotbarClear(headroom));
        assertNull(KitResumePolicy.blocker(headroom, "NEED_CARGO"));

        var notPacked = new KitFulfillmentJournalStore.Snapshot(
                headroom.schemaVersion(), headroom.kitName(), headroom.state(),
                headroom.recipe(), Map.of("end_crystal", 128),
                headroom.requestedBoxes(), headroom.completedBoxes(),
                headroom.currentItem(), headroom.currentQuantity(),
                headroom.ownedShellSlot(), headroom.exportPosition(),
                null, null, null, Map.of(), headroom.blocker(), 100, null, 0);
        assertFalse(KitResumePolicy.canRetryAfterHotbarClear(notPacked));
    }

    @Test void incompleteCargoMayRetryOnlyFromAnUnambiguousShellBoundary() {
        var incomplete = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("respawn_anchor", 32, "obsidian", 128), Map.of("obsidian", 128),
                1, 0, "respawn_anchor", 32, 41, new int[]{1, 64, 2},
                null, null, null, Map.of(), "kit_cargo_retrieval_incomplete",
                100, null, 0);
        assertTrue(KitResumePolicy.canRetryBorrowedInventoryCargo(incomplete));
        assertTrue(KitResumePolicy.canRetryNoCargoRetrieval(incomplete));
        assertTrue(KitResumePolicy.canAuditKeptNoCargoRetrieval(incomplete));
        assertFalse(KitResumePolicy.canRetryBorrowedInventoryCargo(new KitFulfillmentJournalStore.Snapshot(
                incomplete.schemaVersion(), incomplete.kitName(), incomplete.state(),
                incomplete.recipe(), incomplete.packedContents(), incomplete.requestedBoxes(),
                incomplete.completedBoxes(), incomplete.currentItem(), incomplete.currentQuantity(),
                incomplete.ownedShellSlot(), incomplete.exportPosition(), incomplete.kitWorksite(),
                new int[]{2, 64, 2}, "shulker_box", Map.of("respawn_anchor", 32),
                incomplete.blocker(), 100, null, 0)));

        var pendingReturn = new KitFulfillmentJournalStore.Snapshot(
                incomplete.schemaVersion(), incomplete.kitName(), incomplete.state(),
                incomplete.recipe(), incomplete.packedContents(), incomplete.requestedBoxes(),
                incomplete.completedBoxes(), incomplete.currentItem(), incomplete.currentQuantity(),
                incomplete.ownedShellSlot(), incomplete.exportPosition(), null, null, null,
                Map.of(), incomplete.blocker(), 100, null, 0,
                List.of(new KitFulfillmentJournalStore.BorrowedBox(
                        new int[]{3, 64, 4}, "shulker_box", Map.of("respawn_anchor", 32))));
        assertFalse(KitResumePolicy.canRetryNoCargoRetrieval(pendingReturn));
        assertFalse(KitResumePolicy.canAuditKeptNoCargoRetrieval(pendingReturn));
    }

    @Test void recoveredLooseCargoMayResumeOnlyThroughAuditedWaitCargo() {
        var recovered = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("respawn_anchor", 32, "obsidian", 128), Map.of("obsidian", 128),
                1, 0, "respawn_anchor", 32, 41, new int[]{1, 64, 2},
                null, null, null, Map.of(), "recovered_box_requires_cargo_audit",
                100, null, 0);
        assertTrue(KitResumePolicy.canResumeAuditedCargo(recovered));

        var scheduled = new KitFulfillmentJournalStore.Snapshot(
                recovered.schemaVersion(), recovered.kitName(), recovered.state(),
                recovered.recipe(), recovered.packedContents(), recovered.requestedBoxes(),
                recovered.completedBoxes(), recovered.currentItem(), recovered.currentQuantity(),
                recovered.ownedShellSlot(), recovered.exportPosition(), null, null, null,
                Map.of(), "resume_audited_inventory_cargo", 101, "WAIT_CARGO", 300_000);
        assertNull(KitResumePolicy.blocker(scheduled));
        assertEquals("uncertain_wait_cargo", KitResumePolicy.blocker(
                new KitFulfillmentJournalStore.Snapshot(
                        scheduled.schemaVersion(), scheduled.kitName(), scheduled.state(),
                        scheduled.recipe(), scheduled.packedContents(), scheduled.requestedBoxes(),
                        scheduled.completedBoxes(), scheduled.currentItem(), scheduled.currentQuantity(),
                        scheduled.ownedShellSlot(), scheduled.exportPosition(), null, null, null,
                        Map.of(), "resume_requested", 101, "WAIT_CARGO", 300_000)));
        assertFalse(KitResumePolicy.canResumeAuditedCargo(new KitFulfillmentJournalStore.Snapshot(
                recovered.schemaVersion(), recovered.kitName(), recovered.state(),
                recovered.recipe(), recovered.packedContents(), recovered.requestedBoxes(),
                recovered.completedBoxes(), recovered.currentItem(), recovered.currentQuantity(),
                recovered.ownedShellSlot(), recovered.exportPosition(), null,
                new int[]{2, 64, 2}, "shulker_box", Map.of(),
                recovered.blocker(), 100, null, 0)));
    }

    @Test void partialItemBatchCanRecoverWithoutReplayingPackedUnits() {
        var partial = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("totem_of_undying", 4), Map.of("totem_of_undying", 2),
                1, 0, "totem_of_undying", 2, 41, new int[]{1, 64, 2},
                null, null, null, Map.of(), "recovered_box_requires_cargo_audit",
                100, null, 0);
        assertTrue(KitResumePolicy.canResumeAuditedCargo(partial));

        var exceedsRemaining = new KitFulfillmentJournalStore.Snapshot(
                partial.schemaVersion(), partial.kitName(), partial.state(),
                partial.recipe(), partial.packedContents(), partial.requestedBoxes(),
                partial.completedBoxes(), partial.currentItem(), 3,
                partial.ownedShellSlot(), partial.exportPosition(), null,
                null, null, Map.of(), partial.blocker(), 100, null, 0);
        assertFalse(KitResumePolicy.canResumeAuditedCargo(exceedsRemaining));
    }

    @Test void rejectedSourceReturnRequiresAnExactPredepositCheckpoint() {
        var source = new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{3, 64, 4}, "light_blue_shulker_box", Map.of("diamond_axe", 26));
        var blocked = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "armor_regear", "BLOCKED",
                Map.of("diamond_axe", 1), Map.of("diamond_axe", 1),
                1, 0, "diamond_axe", 1, 40, new int[]{1, 64, 2},
                null, null, null, Map.of(), "kit_source_return_failed:export_chest_full",
                100, null, 0, List.of(source));
        assertTrue(KitResumePolicy.canRerouteSourceReturn(blocked));
        var wrongWindow = new KitFulfillmentJournalStore.Snapshot(
                blocked.schemaVersion(), blocked.kitName(), blocked.state(),
                blocked.recipe(), blocked.packedContents(), blocked.requestedBoxes(),
                blocked.completedBoxes(), blocked.currentItem(), blocked.currentQuantity(),
                blocked.ownedShellSlot(), blocked.exportPosition(), null, null, null,
                Map.of(), "kit_source_return_failed:wrong_export_container_opened",
                100, null, 0, List.of(source));
        assertTrue(KitResumePolicy.canRerouteSourceReturn(wrongWindow));
        var preTransferHandoff = new KitFulfillmentJournalStore.Snapshot(
                blocked.schemaVersion(), blocked.kitName(), blocked.state(),
                blocked.recipe(), blocked.packedContents(), blocked.requestedBoxes(),
                blocked.completedBoxes(), blocked.currentItem(), blocked.currentQuantity(),
                blocked.ownedShellSlot(), blocked.exportPosition(), null, null, null,
                Map.of(), "kit_source_return_failed:export_window_lost_before_transfer",
                100, null, 0, List.of(source));
        assertTrue(KitResumePolicy.canRerouteSourceReturn(preTransferHandoff));
        var legacyHandoff = new KitFulfillmentJournalStore.Snapshot(
                blocked.schemaVersion(), blocked.kitName(), blocked.state(),
                blocked.recipe(), blocked.packedContents(), blocked.requestedBoxes(),
                blocked.completedBoxes(), blocked.currentItem(), blocked.currentQuantity(),
                blocked.ownedShellSlot(), blocked.exportPosition(), null, null, null,
                Map.of(), "kit_source_return_failed:export_window_lost",
                100, null, 0, List.of(source));
        assertTrue(KitResumePolicy.canRerouteSourceReturn(legacyHandoff));
        var afterTransferHandoff = new KitFulfillmentJournalStore.Snapshot(
                blocked.schemaVersion(), blocked.kitName(), blocked.state(),
                blocked.recipe(), blocked.packedContents(), blocked.requestedBoxes(),
                blocked.completedBoxes(), blocked.currentItem(), blocked.currentQuantity(),
                blocked.ownedShellSlot(), blocked.exportPosition(), null, null, null,
                Map.of(), "kit_source_return_failed:export_window_lost_after_transfer",
                100, null, 0, List.of(source));
        assertFalse(KitResumePolicy.canRerouteSourceReturn(afterTransferHandoff));
        var uncertainDeposit = new KitFulfillmentJournalStore.Snapshot(
                blocked.schemaVersion(), blocked.kitName(), blocked.state(),
                blocked.recipe(), blocked.packedContents(), blocked.requestedBoxes(),
                blocked.completedBoxes(), blocked.currentItem(), blocked.currentQuantity(),
                blocked.ownedShellSlot(), blocked.exportPosition(), null, null, null,
                Map.of(), "kit_source_return_failed:export_deposit_unconfirmed",
                100, null, 0, List.of(source));
        assertFalse(KitResumePolicy.canRerouteSourceReturn(uncertainDeposit));
        assertEquals("uncertain_source_return",
                KitResumePolicy.blocker(blocked, "RETURNING_SOURCE"));

        var scheduled = new KitFulfillmentJournalStore.Snapshot(
                blocked.schemaVersion(), blocked.kitName(), blocked.state(),
                blocked.recipe(), blocked.packedContents(), blocked.requestedBoxes(),
                blocked.completedBoxes(), blocked.currentItem(), blocked.currentQuantity(),
                blocked.ownedShellSlot(), blocked.exportPosition(), null, null, null,
                Map.of(), "resume_source_return", 101, "RETURNING_SOURCE", 300_000,
                List.of(source));
        assertFalse(KitResumePolicy.canRerouteSourceReturn(scheduled));
        assertNull(KitResumePolicy.blocker(scheduled));
    }

    @Test void onlyPreplacementWorksiteFailureCanUseUnplacedShellRecovery() {
        var beforePlace = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "armor_regear", "BLOCKED",
                Map.of("diamond_sword", 1, "diamond_axe", 1),
                Map.of("diamond_axe", 1), 1, 0, "diamond_sword", 1,
                37, new int[]{1, 64, 2}, new int[]{3, 64, 4}, null, null,
                Map.of(), "kit_pack_failed:worksite_changed_before_place", 100,
                null, 0);
        assertTrue(KitResumePolicy.canRecoverUnplacedKitShell(beforePlace));
        var uncertain = new KitFulfillmentJournalStore.Snapshot(
                beforePlace.schemaVersion(), beforePlace.kitName(), beforePlace.state(),
                beforePlace.recipe(), beforePlace.packedContents(),
                beforePlace.requestedBoxes(), beforePlace.completedBoxes(),
                beforePlace.currentItem(), beforePlace.currentQuantity(),
                beforePlace.ownedShellSlot(), beforePlace.exportPosition(),
                beforePlace.kitWorksite(), null, null, Map.of(),
                "kit_pack_failed:shell_place_timeout", 100, null, 0);
        assertFalse(KitResumePolicy.canRecoverUnplacedKitShell(uncertain));
    }

    @Test void noCapacityImportReturnRequiresOneExactGuardedSourceAndScheduledTarget() {
        var source = new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{3, 64, 4}, "shulker_box", Map.of("diamond_boots", 23));
        var blocked = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("diamond_boots", 1, "diamond_axe", 1),
                Map.of("diamond_boots", 1, "diamond_axe", 1),
                25, 0, null, 0, 39, new int[]{1, 64, 2},
                null, null, null, Map.of(), "kit_source_return_no_empty_import",
                100, null, 0, List.of(source));
        assertTrue(KitResumePolicy.canScheduleLiveCapacityImportReturn(blocked));
        assertTrue(KitResumePolicy.canRerouteSourceReturn(blocked));
        assertEquals("not_scheduled", KitResumePolicy.blocker(blocked));
        var selected = new KitFulfillmentJournalStore.BorrowedBox(
                source.sourcePosition(), source.itemId(), source.contents(),
                new int[]{5, 64, 4});
        var scheduled = new KitFulfillmentJournalStore.Snapshot(
                blocked.schemaVersion(), blocked.kitName(), blocked.state(),
                blocked.recipe(), blocked.packedContents(), blocked.requestedBoxes(),
                blocked.completedBoxes(), blocked.currentItem(), blocked.currentQuantity(),
                blocked.ownedShellSlot(), blocked.exportPosition(), null, null, null,
                Map.of(), "resume_source_return_exact_import", 101,
                "RETURNING_SOURCE", 300_000, List.of(selected));
        assertFalse(KitResumePolicy.canScheduleLiveCapacityImportReturn(scheduled));
        assertNull(KitResumePolicy.blocker(scheduled));
        var uncertain = new KitFulfillmentJournalStore.Snapshot(
                blocked.schemaVersion(), blocked.kitName(), blocked.state(),
                blocked.recipe(), blocked.packedContents(), blocked.requestedBoxes(),
                blocked.completedBoxes(), blocked.currentItem(), blocked.currentQuantity(),
                blocked.ownedShellSlot(), blocked.exportPosition(), null,
                new int[]{3, 64, 5}, "shulker_box", source.contents(),
                blocked.blocker(), 100, null, 0, List.of(source));
        assertFalse(KitResumePolicy.canScheduleLiveCapacityImportReturn(uncertain));
        var capacityBlocked = new KitFulfillmentJournalStore.Snapshot(
                blocked.schemaVersion(), blocked.kitName(), blocked.state(),
                blocked.recipe(), blocked.packedContents(), blocked.requestedBoxes(),
                blocked.completedBoxes(), blocked.currentItem(), blocked.currentQuantity(),
                blocked.ownedShellSlot(), blocked.exportPosition(), null, null, null,
                Map.of(), "kit_source_return_no_capacity_import", 100,
                null, 0, List.of(source));
        assertTrue(KitResumePolicy.canScheduleLiveCapacityImportReturn(capacityBlocked));
        assertTrue(KitResumePolicy.canRerouteSourceReturn(capacityBlocked));
    }

    @Test void changedBaselineAuditRequiresStoppedUnpackedBatchAndPendingSources() {
        var empty = new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{3, 64, 4}, "white_shulker_box", Map.of());
        var crystals = new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{4, 64, 4}, "shulker_box", Map.of("end_crystal", 1585));
        var blocked = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("end_crystal", 256, "obsidian", 128), Map.of("obsidian", 128),
                25, 7, "end_crystal", 256, 39, new int[]{1, 64, 2},
                null, null, null, Map.of(), "kit_preexisting_inventory_changed",
                100, null, 0, List.of(empty, crystals));
        assertTrue(KitResumePolicy.canAuditChangedBaselineCargo(blocked));
        assertEquals("not_scheduled", KitResumePolicy.blocker(blocked));
        var scheduled = new KitFulfillmentJournalStore.Snapshot(
                blocked.schemaVersion(), blocked.kitName(), blocked.state(),
                blocked.recipe(), blocked.packedContents(), blocked.requestedBoxes(),
                blocked.completedBoxes(), blocked.currentItem(), blocked.currentQuantity(),
                44, blocked.exportPosition(), null, null, null, Map.of(),
                "resume_audited_inventory_cargo", 101, "WAIT_CARGO", 300_000,
                blocked.sourceBoxesToReturn());
        assertNull(KitResumePolicy.blocker(scheduled));
        assertFalse(KitResumePolicy.canAuditChangedBaselineCargo(scheduled));
        var noSources = new KitFulfillmentJournalStore.Snapshot(
                blocked.schemaVersion(), blocked.kitName(), blocked.state(),
                blocked.recipe(), blocked.packedContents(), blocked.requestedBoxes(),
                blocked.completedBoxes(), blocked.currentItem(), blocked.currentQuantity(),
                blocked.ownedShellSlot(), blocked.exportPosition(), null, null, null,
                Map.of(), blocked.blocker(), 100, null, 0, List.of());
        assertFalse(KitResumePolicy.canAuditChangedBaselineCargo(noSources));
    }
}
