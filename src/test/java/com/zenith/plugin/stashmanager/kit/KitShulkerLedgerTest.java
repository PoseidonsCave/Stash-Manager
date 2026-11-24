package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void distinguishesAnEmptyOwnedShellFromADifferentEmptyRecoveredSource() {
        var grayShell = new KitShulkerLedger.Identity("gray_shulker_box", Map.of());
        var orangeSource = new KitShulkerLedger.Identity("orange_shulker_box", Map.of());
        var whiteSource = new KitShulkerLedger.Identity("white_shulker_box",
                Map.of("experience_bottle", 1408));
        var observed = Map.of(44, grayShell, 36, orangeSource, 40, whiteSource);
        var recovered = Map.of(orangeSource, 1, whiteSource, 1);
        assertEquals(44, KitShulkerLedger.ownedSlotAfterRetrieval(
                observed, Map.of(), recovered));
        assertEquals(-1, KitShulkerLedger.ownedSlotAfterRetrieval(
                Map.of(44, grayShell, 36, grayShell, 40, whiteSource), Map.of(),
                Map.of(grayShell, 1, whiteSource, 1)));
        assertEquals(-1, KitShulkerLedger.ownedSlotAfterRetrieval(
                Map.of(36, orangeSource, 40, whiteSource), Map.of(), recovered));
    }

    @Test
    void recoversOnlyFullyIdenticalShellAndNewlyEmptiedSource() {
        var plainEmpty = new KitShulkerLedger.Identity("shulker_box", Map.of());
        var otherSource = new KitShulkerLedger.Identity("orange_shulker_box",
                Map.of("experience_bottle", 192));
        var before = Map.of(plainEmpty, 1);
        var recovered = Map.of(plainEmpty, 1, otherSource, 1);
        var observed = Map.of(40, plainEmpty, 44, plainEmpty, 42, otherSource);
        var matchingHashes = Map.of(40, "exact-components", 44, "exact-components",
                42, "orange-components");
        assertEquals(-1, KitShulkerLedger.ownedSlotAfterRetrieval(
                observed, Map.of(), recovered));
        assertEquals(40, KitShulkerLedger.interchangeableOwnedSlotAfterRetrieval(
                observed, 40, Map.of(), before, recovered, matchingHashes));
        assertEquals(40, KitShulkerLedger.interchangeableOwnedSlotAfterRetrieval(
                observed, 36, Map.of(), before, recovered, matchingHashes));
        assertEquals(-1, KitShulkerLedger.interchangeableOwnedSlotAfterRetrieval(
                observed, 40, Map.of(), before, recovered,
                Map.of(40, "kit-components", 44, "source-components")));
        assertEquals(-1, KitShulkerLedger.interchangeableOwnedSlotAfterRetrieval(
                observed, 40, Map.of(), before, recovered,
                Map.of(40, "exact-components")));
        assertEquals(-1, KitShulkerLedger.interchangeableOwnedSlotAfterRetrieval(
                observed, 40, Map.of(), before, Map.of(plainEmpty, 1), matchingHashes));
    }

    @Test
    void refusesExtraOrPreexistingDuplicateShells() {
        var empty = new KitShulkerLedger.Identity("shulker_box", Map.of());
        var observed = Map.of(40, empty, 41, empty, 44, empty);
        var hashes = Map.of(40, "same", 41, "same", 44, "same");
        assertEquals(-1, KitShulkerLedger.interchangeableOwnedSlotAfterRetrieval(
                observed, 40, Map.of(), Map.of(empty, 1), Map.of(empty, 1), hashes));
        assertEquals(-1, KitShulkerLedger.interchangeableOwnedSlotAfterRetrieval(
                observed, 40, Map.of(), Map.of(empty, 2), Map.of(empty, 1), hashes));
    }

    @Test
    void identifiesPackedShellBesideAnExactlyMatchingDifferentColorSource() {
        var packed = Map.of("totem_of_undying", 9);
        var owned = new KitShulkerLedger.Identity("shulker_box", packed);
        var source = new KitShulkerLedger.Identity("yellow_shulker_box", packed);
        assertEquals(40, KitShulkerLedger.ownedSlot(
                Map.of(40, owned, 36, source), 40, packed, source));
        assertEquals(-1, KitShulkerLedger.ownedSlot(
                Map.of(40, source, 36, source), 40, packed, source));
        assertEquals(-1, KitShulkerLedger.ownedSlot(
                Map.of(40, owned, 36, source, 44, owned), 40, packed, source));
        assertEquals(40, KitShulkerLedger.ownedSlot(
                Map.of(40, owned), 40, packed, source));
    }

    @Test
    void reconcilesOnlyPhysicallyIdenticalOwnedAndSourceBoxes() {
        var sameContents = new KitShulkerLedger.Identity("shulker_box",
                Map.of("totem_of_undying", 9));
        var observed = Map.of(44, sameContents, 36, sameContents);
        assertEquals(-1, KitShulkerLedger.ownedSlot(observed, 44,
                sameContents.contents(), sameContents));
        assertEquals(44, KitShulkerLedger.interchangeableOwnedSourceSlot(
                observed, 44, sameContents, sameContents,
                Map.of(44, "same-components", 36, "same-components")));
        assertEquals(-1, KitShulkerLedger.interchangeableOwnedSourceSlot(
                observed, 44, sameContents, sameContents,
                Map.of(44, "kit-components", 36, "source-components")));
        assertEquals(-1, KitShulkerLedger.interchangeableOwnedSourceSlot(
                observed, 44, sameContents, sameContents, Map.of(44, "known")));
        assertEquals(-1, KitShulkerLedger.interchangeableOwnedSourceSlot(
                Map.of(44, sameContents), 44, sameContents, sameContents,
                Map.of(44, "same-components")));
        assertEquals(-1, KitShulkerLedger.interchangeableOwnedSourceSlot(
                Map.of(44, sameContents, 36, sameContents, 40, sameContents),
                44, sameContents, sameContents,
                Map.of(44, "same-components", 36, "same-components",
                        40, "same-components")));
    }

    @Test
    void selectsOnlyFullyAccountedPlainDuplicateReturnBoxes() {
        var packed = new KitShulkerLedger.Identity("shulker_box",
                Map.of("experience_bottle", 576, "totem_of_undying", 9,
                        "firework_rocket", 576));
        var empty = new KitShulkerLedger.Identity("purple_shulker_box", Map.of());
        var stock = new KitShulkerLedger.Identity("magenta_shulker_box",
                Map.of("firework_rocket", 1258));
        var expected = List.of(empty, empty, stock);
        var observed = Map.of(36, packed, 40, empty, 42, stock, 44, empty);
        var fingerprints = Map.of(40, "same-components", 44, "same-components");
        assertEquals(40, KitShulkerLedger.interchangeablePendingSourceSlot(
                observed, 36, packed, expected, empty, fingerprints));
        assertEquals(-1, KitShulkerLedger.interchangeablePendingSourceSlot(
                observed, 36, packed, expected, empty,
                Map.of(40, "left", 44, "right")));
        assertEquals(-1, KitShulkerLedger.interchangeablePendingSourceSlot(
                observed, 36, packed, expected, empty, Map.of(40, "same-components")));
        assertEquals(-1, KitShulkerLedger.interchangeablePendingSourceSlot(
                Map.of(36, packed, 40, empty, 44, empty), 36, packed,
                expected, empty, fingerprints));
        assertEquals(-1, KitShulkerLedger.interchangeablePendingSourceSlot(
                Map.of(36, packed, 40, empty, 42, stock, 44, empty, 43, stock),
                36, packed, expected, empty, fingerprints));
    }
}
