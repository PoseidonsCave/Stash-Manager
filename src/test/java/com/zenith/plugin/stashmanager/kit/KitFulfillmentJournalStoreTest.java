package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KitFulfillmentJournalStoreTest {
    @TempDir Path temporary;

    @Test
    void variantRepairSurvivesRestartAndCannotBeSilentlyDropped() throws IOException {
        var path = temporary.resolve("variant-repair.json");
        var store = new KitFulfillmentJournalStore(path);
        var base = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("diamond_helmet", 1, "end_crystal", 256),
                Map.of("end_crystal", 256), 25, 7, "diamond_helmet", 1, 37,
                new int[]{1, 130, 2}, null, null, null, Map.of(),
                "resume_variant_exchange", 100L, "VARIANT_NEED_SOURCE", 300_000L,
                List.of());
        var repair = new KitFulfillmentJournalStore.VariantRepair(
                new int[]{3, 130, 4}, 54, 13, "a".repeat(64), "end_crystal", 15,
                23, 21, "b".repeat(64), "c".repeat(64), "d".repeat(64),
                -1, null, null);
        store.save(base.withVariantRepair(repair));
        var loaded = store.load().orElseThrow();
        assertEquals(repair.amount(), loaded.variantRepair().amount());
        assertEquals(repair.shellHash(), loaded.variantRepair().shellHash());
        assertEquals(repair.importPosition()[0], loaded.variantRepair().importPosition()[0]);
        assertEquals("VARIANT_NEED_SOURCE", loaded.resumeState());
        Files.writeString(path, Files.readString(path).replace(
                "\"schemaVersion\":12", "\"schemaVersion\":11"));
        assertEquals(KitFulfillmentJournalStore.SCHEMA_VERSION,
                store.load().orElseThrow().schemaVersion());
        store.save(base.withVariantRepair(repair.withAuditedIncoming(14)));
        assertEquals(14, store.load().orElseThrow().variantRepair().auditedIncomingRawSlot());
        var canonical = repair.withCanonicalEvidence("e".repeat(64), "f".repeat(64),
                "1".repeat(64), "2".repeat(64)).withAuditedIncoming(14);
        store.save(base.withVariantRepair(canonical));
        var restoredCanonical = store.load().orElseThrow().variantRepair();
        assertEquals(2, restoredCanonical.fingerprintVersion());
        assertEquals(canonical.cargoHash(), restoredCanonical.cargoHash());
        assertEquals(14, restoredCanonical.auditedIncomingRawSlot());
        assertThrows(IOException.class, () -> store.save(base));
    }

    @Test
    void retainedShellSurvivesRoundTripAndCannotBeDroppedBeforeConsumption()
            throws IOException {
        var store = new KitFulfillmentJournalStore(temporary.resolve("retained.json"));
        var base = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("end_crystal", 256), Map.of("end_crystal", 256), 25, 7,
                "end_crystal", 256, 37, new int[]{1, 64, 2},
                null, null, null, Map.of(), "resume_verified_recycled_shell",
                100L, "NEED_CARGO", 300_000L, List.of());
        var shell = new KitFulfillmentJournalStore.RetainedShell(
                44, "white_shulker_box", "a".repeat(64));
        store.save(base.withRetainedShell(shell));
        assertEquals(shell, store.load().orElseThrow().retainedShell());
        assertThrows(IOException.class, () -> store.save(base));
    }

    @Test
    void pinnedMultiStackCargoSurvivesRoundTrip() throws IOException {
        var store = new KitFulfillmentJournalStore(temporary.resolve("batch.json"));
        store.save(new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("experience_bottle", 128), Map.of(), 10, 1,
                "experience_bottle", 128, 37, new int[]{1, 64, 2},
                null, null, null, Map.of(), "resume_audited_inventory_cargo",
                100L, "WAIT_CARGO", 300_000L, List.of(), -1, List.of(),
                Map.of(26, 64, 33, 64)));
        assertEquals(Map.of(26, 64, 33, 64),
                store.load().orElseThrow().auditedCargoSlots());
    }

    @Test
    void inventoryBorrowedSourceWithoutChestOriginSurvivesReturnCheckpoint()
            throws IOException {
        var store = new KitFulfillmentJournalStore(temporary.resolve("inventory-return.json"));
        var source = new KitFulfillmentJournalStore.BorrowedBox(
                null, "shulker_box", Map.of("obsidian", 128),
                new int[]{3, 64, 4});
        store.save(new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("diamond_sword", 1, "obsidian", 128),
                Map.of("diamond_sword", 1), 25, 7,
                "diamond_sword", 1, 39, new int[]{1, 64, 2},
                null, null, null, Map.of(), "resume_source_return_exact_import",
                100L, "RETURNING_SOURCE", 300_000L, List.of(source)));
        var restored = store.load().orElseThrow().sourceBoxesToReturn().getFirst();
        assertEquals(null, restored.sourcePosition());
        assertEquals(Map.of("obsidian", 128), restored.contents());
        assertEquals(3, restored.targetPosition()[0]);
    }

    @Test
    void incompleteBoxAndMissingAmountsSurviveRoundTrip() throws IOException {
        var store = new KitFulfillmentJournalStore(temporary.resolve("incomplete.json"));
        store.save(new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "EXPORTING",
                Map.of("enchanted_golden_apple", 128),
                Map.of("enchanted_golden_apple", 110), 10, 2,
                null, 0, 38, new int[]{1, 64, 2},
                null, null, null, Map.of(), null, 100L,
                null, 0L, List.of(), -1, List.of(), Map.of(),
                1, Map.of("enchanted_golden_apple", 12),
                Map.of("enchanted_golden_apple", 18)));
        var loaded = store.load().orElseThrow();
        assertEquals(2, loaded.completedBoxes());
        assertEquals(1, loaded.incompleteBoxes());
        assertEquals(Map.of("enchanted_golden_apple", 12), loaded.missingTotals());
        assertEquals(Map.of("enchanted_golden_apple", 18), loaded.currentBoxMissing());
    }

    @Test
    void versionNineMigrationKeepsPartialBoxAccounting() throws IOException {
        Path path = temporary.resolve("partial-v9.json");
        Files.writeString(path, """
                {"schemaVersion":9,"kitName":"cpvp","state":"BLOCKED",
                 "recipe":{"enchanted_golden_apple":128},
                 "packedContents":{"enchanted_golden_apple":110},
                 "requestedBoxes":10,"completedBoxes":2,"incompleteBoxes":1,
                 "missingTotals":{"enchanted_golden_apple":12},
                 "currentBoxMissing":{"enchanted_golden_apple":18},
                 "ownedShellSlot":38,"auditedCargoSlot":-1,"exportPosition":[1,64,2],
                 "borrowedSourceContents":{},"blocker":"manual_review"}
                """);
        var loaded = new KitFulfillmentJournalStore(path).load().orElseThrow();
        assertEquals(1, loaded.incompleteBoxes());
        assertEquals(Map.of("enchanted_golden_apple", 12), loaded.missingTotals());
        assertEquals(Map.of("enchanted_golden_apple", 18), loaded.currentBoxMissing());
        assertEquals(Map.of(), loaded.displacedAppleSlots());
    }

    @Test
    void displacedAppleOwnershipSurvivesRoundTripAndCannotBeSilentlyDropped()
            throws IOException {
        var store = new KitFulfillmentJournalStore(temporary.resolve("apple-overflow.json"));
        var base = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("enchanted_golden_apple", 128),
                Map.of("enchanted_golden_apple", 128), 25, 6,
                "enchanted_golden_apple", 128, 37, new int[]{1, 64, 2},
                null, null, null, Map.of(), "resume_verified_apple_overflow",
                100L, "NEED_CARGO", 300_000L, List.of(), -1, List.of(), Map.of(),
                0, Map.of(), Map.of());
        var slot = new KitInventoryAppleOwnership.Slot(54, true, "a".repeat(64));
        store.save(base.withDisplacedAppleSlots(Map.of(16, slot)));
        assertEquals(Map.of(16, slot), store.load().orElseThrow().displacedAppleSlots());
        assertThrows(IOException.class, () -> store.save(base));
    }

    @Test
    void versionEightCheckpointDefaultsIncompleteCountsButKeepsAuditedSlots()
            throws IOException {
        Path path = temporary.resolve("blocked-v8.json");
        Files.writeString(path, """
                {"schemaVersion":8,"kitName":"cpvp","state":"BLOCKED",
                 "recipe":{"enchanted_golden_apple":128},"packedContents":{},
                 "requestedBoxes":10,"completedBoxes":2,"ownedShellSlot":38,
                 "currentItem":"enchanted_golden_apple","currentQuantity":128,
                 "auditedCargoSlot":-1,
                 "exportPosition":[1,64,2],"borrowedSourceContents":{},
                 "blocker":"resume_audited_inventory_cargo",
                 "auditedCargoSlots":{"26":64,"33":64}}
                """);
        var loaded = new KitFulfillmentJournalStore(path).load().orElseThrow();
        assertEquals(0, loaded.incompleteBoxes());
        assertEquals(Map.of(), loaded.missingTotals());
        assertEquals(Map.of(26, 64, 33, 64), loaded.auditedCargoSlots());
    }

    @Test
    void writesAndReadsCheckpointAtomically() throws IOException {
        var store = new KitFulfillmentJournalStore(temporary.resolve("checkpoint.json"));
        var snapshot = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "travel", "PACKING",
                Map.of("experience_bottle", 576), Map.of(), 5, 0,
                "experience_bottle", 576, 44, new int[]{1, 64, 2},
                new int[]{3, 64, 4}, null, null, Map.of(), null, 100L,
                "PACKING", 300_000L);
        store.save(snapshot);
        assertEquals("PACKING", store.load().orElseThrow().state());
        assertEquals(3, store.load().orElseThrow().kitWorksite()[0]);
        store.clear();
        assertFalse(store.exists());
    }

    @Test
    void rejectsIncompleteCheckpoint() {
        var store = new KitFulfillmentJournalStore(temporary.resolve("checkpoint.json"));
        var invalid = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "travel", "PACKING",
                Map.of(), Map.of(), 5, 0, null, 0, -1,
                new int[]{1, 64, 2}, null, null, null, Map.of(), null, 100L,
                null, 0);
        assertThrows(IOException.class, () -> store.save(invalid));
    }

    @Test
    void oldCheckpointRemainsManualReviewOnly() throws IOException {
        Path path = temporary.resolve("legacy.json");
        Files.writeString(path, """
                {"schemaVersion":2,"kitName":"cpvp","state":"BLOCKED",
                 "recipe":{"obsidian":64},"packedContents":{},"requestedBoxes":1,
                 "completedBoxes":0,"ownedShellSlot":-1,"exportPosition":[1,64,2],
                 "borrowedSourceContents":{},"blocker":"old_checkpoint"}
                """);
        var snapshot = new KitFulfillmentJournalStore(path).load().orElseThrow();
        assertEquals(KitFulfillmentJournalStore.SCHEMA_VERSION, snapshot.schemaVersion());
        assertEquals(null, snapshot.resumeState());
        assertEquals(0, snapshot.resumeNotBeforeEpochMilli());
    }

    @Test
    void versionThreeScheduledCheckpointKeepsItsResumeWindow() throws IOException {
        Path path = temporary.resolve("scheduled-v3.json");
        Files.writeString(path, """
                {"schemaVersion":3,"kitName":"cpvp","state":"BLOCKED",
                 "recipe":{"respawn_anchor":32},"packedContents":{},"requestedBoxes":1,
                 "completedBoxes":0,"currentItem":"respawn_anchor","currentQuantity":32,
                 "ownedShellSlot":41,"exportPosition":[1,64,2],
                 "borrowedSourceContents":{},"blocker":"resume_audited_inventory_cargo",
                 "resumeState":"WAIT_CARGO","resumeNotBeforeEpochMilli":300000}
                """);
        var snapshot = new KitFulfillmentJournalStore(path).load().orElseThrow();
        assertEquals(KitFulfillmentJournalStore.SCHEMA_VERSION, snapshot.schemaVersion());
        assertEquals("WAIT_CARGO", snapshot.resumeState());
        assertEquals(300_000, snapshot.resumeNotBeforeEpochMilli());
        assertEquals(List.of(), snapshot.sourceBoxesToReturn());
    }

    @Test
    void borrowedSourceReturnSurvivesCheckpointRoundTrip() throws IOException {
        var store = new KitFulfillmentJournalStore(temporary.resolve("return.json"));
        var source = new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{3, 64, 4}, "cyan_shulker_box", Map.of("respawn_anchor", 16),
                new int[]{8, 64, 9});
        store.save(new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "RETURNING_SOURCE",
                Map.of("respawn_anchor", 32), Map.of("respawn_anchor", 32), 1, 0,
                "respawn_anchor", 32, 41, new int[]{1, 64, 2},
                null, null, null, Map.of(), null, 100L, null, 0L, List.of(source)));
        var loaded = store.load().orElseThrow();
        assertEquals(1, loaded.sourceBoxesToReturn().size());
        assertEquals(Map.of("respawn_anchor", 16), loaded.sourceBoxesToReturn().getFirst().contents());
        assertEquals(3, loaded.sourceBoxesToReturn().getFirst().sourcePosition()[0]);
        assertEquals(8, loaded.sourceBoxesToReturn().getFirst().targetPosition()[0]);
    }

    @Test
    void versionFourKeepsBorrowedReturnsAndDefaultsCargoAudit() throws IOException {
        Path path = temporary.resolve("borrowed-v4.json");
        Files.writeString(path, """
                {"schemaVersion":4,"kitName":"cpvp","state":"BLOCKED",
                 "recipe":{"obsidian":128},"packedContents":{},"requestedBoxes":1,
                 "completedBoxes":0,"currentItem":"obsidian","currentQuantity":128,
                 "ownedShellSlot":37,"exportPosition":[1,64,2],
                 "borrowedSourceContents":{},"blocker":"kit_cargo_retrieval_incomplete",
                 "sourceBoxesToReturn":[{"sourcePosition":[3,64,4],
                   "itemId":"cyan_shulker_box","contents":{}}]}
                """);
        var snapshot = new KitFulfillmentJournalStore(path).load().orElseThrow();
        assertEquals(KitFulfillmentJournalStore.SCHEMA_VERSION, snapshot.schemaVersion());
        assertEquals(-1, snapshot.auditedCargoSlot());
        assertEquals(1, snapshot.sourceBoxesToReturn().size());
    }

    @Test
    void auditedCargoSlotSurvivesCheckpointRoundTrip() throws IOException {
        var store = new KitFulfillmentJournalStore(temporary.resolve("audited.json"));
        store.save(new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("obsidian", 128), Map.of(), 1, 0,
                "obsidian", 64, 37, new int[]{1, 64, 2},
                null, null, null, Map.of(), "resume_audited_inventory_cargo",
                100L, "WAIT_CARGO", 300_000L, List.of(), 26));
        assertEquals(26, store.load().orElseThrow().auditedCargoSlot());
    }

    @Test
    void operatorAttestedSourceLossSurvivesCheckpointRoundTrip() throws IOException {
        var store = new KitFulfillmentJournalStore(temporary.resolve("loss.json"));
        var lost = new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{3, 64, 4}, "shulker_box", Map.of("diamond_boots", 22));
        store.save(new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("diamond_boots", 1), Map.of(), 10, 0,
                "diamond_boots", 1, 40, new int[]{1, 64, 2},
                null, null, null, Map.of(), "lost_borrowed_source_operator_attested",
                100L, null, 0L, List.of(), 26, List.of(lost)));
        var loaded = store.load().orElseThrow();
        assertEquals(List.of(), loaded.sourceBoxesToReturn());
        assertEquals(1, loaded.writtenOffSources().size());
        assertEquals(Map.of("diamond_boots", 22), loaded.writtenOffSources().getFirst().contents());
    }

    @Test
    void versionSixCheckpointMigratesWithoutInventingAWriteOff() throws IOException {
        Path path = temporary.resolve("blocked-v6.json");
        Files.writeString(path, """
                {"schemaVersion":6,"kitName":"cpvp","state":"BLOCKED",
                 "recipe":{"diamond_boots":1},"packedContents":{},"requestedBoxes":10,
                 "completedBoxes":0,"currentItem":"diamond_boots","currentQuantity":1,
                 "ownedShellSlot":40,"exportPosition":[1,64,2],"kitWorksite":[3,64,4],
                 "borrowedSourceContents":{},"blocker":"kit_pack_failed:shell_contents_changed",
                 "sourceBoxesToReturn":[{"sourcePosition":[5,64,6],
                   "itemId":"shulker_box","contents":{"diamond_boots":22}}],
                 "auditedCargoSlot":26}
                """);
        var loaded = new KitFulfillmentJournalStore(path).load().orElseThrow();
        assertEquals(KitFulfillmentJournalStore.SCHEMA_VERSION, loaded.schemaVersion());
        assertEquals(1, loaded.sourceBoxesToReturn().size());
        assertEquals(List.of(), loaded.writtenOffSources());
    }

    @Test
    void versionFiveBlockedReturnMigratesWithoutLosingBorrowedBox() throws IOException {
        Path path = temporary.resolve("blocked-v5.json");
        Files.writeString(path, """
                {"schemaVersion":5,"kitName":"armor_regear","state":"BLOCKED",
                 "recipe":{"diamond_axe":1},"packedContents":{"diamond_axe":1},
                 "requestedBoxes":1,"completedBoxes":0,"currentItem":"diamond_axe",
                 "currentQuantity":1,"ownedShellSlot":40,
                 "exportPosition":[1,64,2],"blocker":"kit_source_return_failed:export_chest_full",
                 "sourceBoxesToReturn":[{"sourcePosition":[3,64,4],
                   "itemId":"light_blue_shulker_box","contents":{"diamond_axe":26}}],
                 "auditedCargoSlot":26}
                """);
        var snapshot = new KitFulfillmentJournalStore(path).load().orElseThrow();
        assertEquals(KitFulfillmentJournalStore.SCHEMA_VERSION, snapshot.schemaVersion());
        assertEquals(26, snapshot.auditedCargoSlot());
        assertEquals(1, snapshot.sourceBoxesToReturn().size());
        assertEquals(3, snapshot.sourceBoxesToReturn().getFirst().targetPosition()[0]);
        assertEquals(null, snapshot.sourceBoxesToReturn().getFirst().returnPosition());
    }

    @Test
    void serializesConcurrentCheckpointWrites() throws Exception {
        var store = new KitFulfillmentJournalStore(temporary.resolve("concurrent.json"));
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> saveRepeatedly(store, "cpvp"));
            var second = workers.submit(() -> saveRepeatedly(store, "travel"));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            var saved = store.load().orElseThrow();
            assertEquals(1, saved.requestedBoxes());
            assertFalse(saved.recipe().isEmpty());
        } finally {
            workers.shutdownNow();
        }
    }

    private static void saveRepeatedly(KitFulfillmentJournalStore store, String kitName) {
        for (int i = 0; i < 10; i++) {
            try {
                store.save(new KitFulfillmentJournalStore.Snapshot(
                        KitFulfillmentJournalStore.SCHEMA_VERSION, kitName, "WAIT_SHELL",
                        Map.of("obsidian", 64), Map.of(), 1, 0, null, 0, -1,
                        new int[]{1, 64, 2}, null, null, null, Map.of(), null,
                        i, null, 0));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
