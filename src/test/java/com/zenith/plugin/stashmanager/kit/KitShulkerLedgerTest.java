package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitShulkerLedgerTest {
    private static final KitShulkerLedger.Identity OWNED =
            new KitShulkerLedger.Identity("white_shulker_box", Map.of("obsidian", 128));
    private static final KitShulkerLedger.Identity EMPTY =
            new KitShulkerLedger.Identity("lime_shulker_box", Map.of());
    private static final KitShulkerLedger.Identity STOCK =
            new KitShulkerLedger.Identity("black_shulker_box", Map.of("ender_chest", 1714));

    @Test
    void acceptsTwoIndependentlyRecoveredBoxesAndNoExtras() {
        Map<KitShulkerLedger.Identity, Integer> before = Map.of(OWNED, 1);
        Map<KitShulkerLedger.Identity, Integer> recovered = Map.of(EMPTY, 1, STOCK, 1);
        assertTrue(KitShulkerLedger.matchesAfterRetrieval(before,
                Map.of(OWNED, 1, EMPTY, 1, STOCK, 1), recovered));
        assertFalse(KitShulkerLedger.matchesAfterRetrieval(before,
                Map.of(OWNED, 1, STOCK, 1), recovered));
        assertFalse(KitShulkerLedger.matchesAfterRetrieval(before,
                Map.of(OWNED, 1, EMPTY, 1, STOCK, 1,
                        new KitShulkerLedger.Identity("red_shulker_box", Map.of()), 1), recovered));
    }

    @Test
    void countsIdenticalRecoveredBoxesAndRejectsInvalidCounts() {
        assertTrue(KitShulkerLedger.matchesAfterRetrieval(Map.of(OWNED, 1),
                Map.of(OWNED, 1, EMPTY, 2), Map.of(EMPTY, 2)));
        assertFalse(KitShulkerLedger.matchesAfterRetrieval(Map.of(OWNED, 1),
                Map.of(OWNED, 1, EMPTY, 1), Map.of(EMPTY, 2)));
        assertFalse(KitShulkerLedger.matchesAfterRetrieval(Map.of(OWNED, 1),
                Map.of(OWNED, 1), Map.of(EMPTY, 0)));
    }
}
