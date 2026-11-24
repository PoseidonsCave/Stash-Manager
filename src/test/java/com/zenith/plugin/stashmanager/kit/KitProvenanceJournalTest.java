package com.zenith.plugin.stashmanager.kit;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitProvenanceJournalTest {
    @TempDir Path temporary;

    @Test
    void recordsPreTransferOwnershipAndBoxEvidenceAcrossRestartWithoutContentsLeak()
            throws IOException {
        Path path = temporary.resolve("kit.jsonl");
        String request = "kit_fulfillment:private-name:19:obsidian";
        Map<String, Integer> before = Map.of("obsidian", 351,
                "private named sword", 1);
        var journal = new KitProvenanceJournal(path);
        journal.record("cpvp", 20, request, "WAIT_CARGO", "kit_retrieval_baseline",
                "obsidian", 128, Map.of(44, 33), List.of(37, 39, 44), null, null, null);
        journal.record("cpvp", 20, request, "WAIT_CARGO",
                "retrieve_borrowed_shell_placement_planned", "obsidian", 128,
                Map.of(44, 33), List.of(37, 39, 44),
                new KitProvenanceJournal.Source("shulker_box",
                        KitProvenanceJournal.contentsHash(before), 351,
                        "100, 70, 200", "108, 70, 206", null),
                new KitProvenanceJournal.Step("UNLOADING_SHULKER",
                        "100, 70, 200", 128, 1, 1, true), null);
        new KitProvenanceJournal(path).record("cpvp", 20, request, "WAIT_CARGO",
                "retrieve_borrowed_shell_recovered", "obsidian", 128,
                Map.of(13, 31, 14, 64, 44, 33), List.of(13, 14, 37, 39, 44),
                new KitProvenanceJournal.Source("shulker_box",
                        KitProvenanceJournal.contentsHash(Map.of("obsidian", 223,
                                "private named sword", 1)), 223,
                        "100, 70, 200", "108, 70, 206", null),
                null, null);

        List<String> lines = Files.readAllLines(path);
        assertEquals(3, lines.size());
        assertEquals(1, JsonParser.parseString(lines.get(0)).getAsJsonObject()
                .get("sequence").getAsInt());
        assertEquals(3, JsonParser.parseString(lines.get(0)).getAsJsonObject()
                .getAsJsonArray("occupiedInventorySlots").size());
        var recovered = JsonParser.parseString(lines.get(2)).getAsJsonObject();
        assertEquals(3, recovered.get("sequence").getAsInt());
        assertEquals(128, recovered.get("inventoryItemTotal").getAsInt());
        assertEquals(223, recovered.getAsJsonObject("source")
                .get("requestedItemCount").getAsInt());
        assertEquals("UNLOADING_SHULKER", JsonParser.parseString(lines.get(1))
                .getAsJsonObject().getAsJsonObject("step").get("retrieverState").getAsString());
        String file = Files.readString(path);
        assertFalse(file.contains("private named sword"));
        assertFalse(file.contains("private-name"));
        assertTrue(file.contains("pre_retrieval_client_inventory"));
    }

    @Test
    void retainsSequenceThroughRotationAndSeparatesPartialCrashLine() throws IOException {
        Path path = temporary.resolve("kit.jsonl");
        var journal = new KitProvenanceJournal(path, 8192);
        for (int i = 0; i < 80; i++) {
            journal.record("cpvp", 20, "request", "WAIT_CARGO", "retrieve_progress",
                    "obsidian", 128, Map.of(13, i), List.of(13), null, null, null);
        }
        assertTrue(Files.exists(path.resolveSibling("kit.jsonl.previous")));
        Files.writeString(path, "{\"incomplete\":", java.nio.file.StandardOpenOption.APPEND);
        new KitProvenanceJournal(path, 8192).record("cpvp", 20, "request",
                "BLOCKED", "kit_fulfillment_blocked", "obsidian", 128,
                Map.of(13, 31, 14, 64, 44, 33), List.of(13, 14, 44), null, null,
                "kit_retrieved_cargo_unverified:95/128");
        List<String> lines = Files.readAllLines(path);
        var last = JsonParser.parseString(lines.getLast()).getAsJsonObject();
        assertEquals(81, last.get("sequence").getAsInt());
        assertEquals("kit_fulfillment_blocked", last.get("event").getAsString());
    }

    @Test
    void readsOnlyMatchingBlockedRetrievalAndItsOriginalInventory() throws IOException {
        var journal = new KitProvenanceJournal(temporary.resolve("recovery.jsonl"));
        String request = "kit:37:apples";
        journal.record("cpvp", 37, request, "WAIT_CARGO", "kit_retrieval_baseline",
                "enchanted_golden_apple", 128, Map.of(10, 55), List.of(10, 36),
                null, null, null);
        journal.record("cpvp", 37, request, "WAIT_CARGO", "retrieve_incomplete",
                "enchanted_golden_apple", 128, Map.of(9, 14, 10, 55),
                List.of(9, 10, 33, 36), null,
                new KitProvenanceJournal.Step("DONE", "100, 70, 200",
                        45, 4, 1, true), "shulker_hotbar_transfer_rejected");
        journal.record("cpvp", 37, request, "BLOCKED", "kit_fulfillment_blocked",
                "enchanted_golden_apple", 128, Map.of(9, 14, 10, 55),
                List.of(9, 10, 33, 36), null, null,
                "kit_cargo_retrieval_incomplete");
        var evidence = new KitProvenanceJournal(temporary.resolve("recovery.jsonl"))
                .blockedRetrieval("cpvp", 37, "enchanted_golden_apple", 128,
                        "kit_cargo_retrieval_incomplete").orElseThrow();
        assertEquals(Map.of(10, 55), evidence.beforeItemSlots());
        assertEquals("100, 70, 200", evidence.sourcePosition());
        assertTrue(journal.blockedRetrieval("cpvp", 38, "enchanted_golden_apple", 128,
                "kit_cargo_retrieval_incomplete").isEmpty());
    }
}
