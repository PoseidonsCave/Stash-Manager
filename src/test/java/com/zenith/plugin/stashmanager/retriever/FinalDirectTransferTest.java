package com.zenith.plugin.stashmanager.retriever;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalDirectTransferTest {
    @Test
    void closesOnlyWhenTheClickCanFinishEveryOutstandingItem() {
        assertTrue(StashRetriever.completesDirectRequest(Map.of("obsidian", 64),
                "obsidian", 64));
        assertFalse(StashRetriever.completesDirectRequest(Map.of("obsidian", 65),
                "obsidian", 64));
        assertFalse(StashRetriever.completesDirectRequest(
                Map.of("obsidian", 64, "ender_pearl", 1), "obsidian", 64));
        assertFalse(StashRetriever.completesDirectRequest(Map.of("obsidian", 64),
                "ender_pearl", 64));
    }
}
