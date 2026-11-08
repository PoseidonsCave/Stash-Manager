package com.zenith.plugin.stashmanager.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldNbtAuditTest {
    @Test
    void includesOnlyRelevantDroppedItems() {
        assertTrue(WorldNbtAudit.isRelevantItem("black_shulker_box"));
        assertTrue(WorldNbtAudit.isRelevantItem("orange_shulker_box"));
        assertTrue(WorldNbtAudit.isRelevantItem("golden_carrot"));
        assertTrue(WorldNbtAudit.isRelevantItem("enchanted_golden_apple"));
        assertFalse(WorldNbtAudit.isRelevantItem("diamond_sword"));
        assertFalse(WorldNbtAudit.isRelevantItem(null));
    }

    @Test
    void refusesUnboundedSearch() {
        assertThrows(IllegalArgumentException.class, () -> WorldNbtAudit.capture(0));
        assertThrows(IllegalArgumentException.class, () -> WorldNbtAudit.capture(13));
    }
}
