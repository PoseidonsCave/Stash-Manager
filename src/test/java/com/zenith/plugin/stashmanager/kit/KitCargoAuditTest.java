package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitCargoAuditTest {
    @Test void pinnedBatchMustAccountForEveryMatchingLooseStack() {
        Map<Integer, KitCargoAudit.Stack> exact = Map.of(
                13, new KitCargoAudit.Stack("obsidian", 31),
                14, new KitCargoAudit.Stack("obsidian", 64),
                44, new KitCargoAudit.Stack("obsidian", 33),
                37, new KitCargoAudit.Stack("red_shulker_box", 1));
        Map<Integer, Integer> pinned = Map.of(13, 31, 14, 64, 44, 33);
        assertTrue(KitCargoAudit.verifiedBatch(exact, "obsidian", 128, 37, pinned));
        assertFalse(KitCargoAudit.verifiedBatch(Map.of(
                13, exact.get(13), 14, exact.get(14), 44, exact.get(44),
                35, new KitCargoAudit.Stack("obsidian", 1)),
                "obsidian", 128, 37, pinned));
        assertFalse(KitCargoAudit.verifiedBatch(exact, "obsidian", 128, 37,
                Map.of(13, 31, 14, 64)));
    }

    @Test void explicitKeptAppleAuditProtectsUnpinnedStock() {
        Map<Integer, KitCargoAudit.Stack> slots = Map.of(
                13, new KitCargoAudit.Stack("enchanted_golden_apple", 56),
                17, new KitCargoAudit.Stack("enchanted_golden_apple", 8),
                28, new KitCargoAudit.Stack("enchanted_golden_apple", 64),
                38, new KitCargoAudit.Stack("enchanted_golden_apple", 64));
        Map<Integer, Integer> pinned = Map.of(13, 56, 17, 8, 28, 64);
        assertFalse(KitCargoAudit.verifiedBatch(slots, "enchanted_golden_apple", 128,
                37, pinned));
        assertTrue(KitCargoAudit.verifiedBatch(slots, "enchanted_golden_apple", 128,
                37, pinned, true));
        assertFalse(KitCargoAudit.verifiedBatch(slots, "enchanted_golden_apple", 128,
                37, Map.of(13, 56, 17, 8, 28, 63), true));
    }
}
