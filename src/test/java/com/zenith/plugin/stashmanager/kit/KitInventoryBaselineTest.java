package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitInventoryBaselineTest {
    @Test void movedKeptStacksAndShellDoNotLookLost() {
        var before = Map.of(
                13, new KitInventoryBaseline.Stack("golden_carrot", 57),
                39, new KitInventoryBaseline.Stack("white_shulker_box", 1),
                41, new KitInventoryBaseline.Stack("totem_of_undying", 2));
        var after = Map.of(
                14, new KitInventoryBaseline.Stack("golden_carrot", 57),
                13, new KitInventoryBaseline.Stack("end_crystal", 64),
                39, new KitInventoryBaseline.Stack("shulker_box", 1),
                44, new KitInventoryBaseline.Stack("white_shulker_box", 1),
                40, new KitInventoryBaseline.Stack("totem_of_undying", 2));
        assertTrue(KitInventoryBaseline.compare(before, after, "end_crystal", 64).preserved());
    }

    @Test void actualKeptCountChangeStillStops() {
        var before = Map.of(13, new KitInventoryBaseline.Stack("golden_carrot", 57));
        var after = Map.of(
                14, new KitInventoryBaseline.Stack("golden_carrot", 56),
                20, new KitInventoryBaseline.Stack("end_crystal", 64));
        assertFalse(KitInventoryBaseline.compare(before, after, "end_crystal", 64).preserved());
    }

    @Test void unexpectedLooseCargoStillStops() {
        var before = Map.of(13, new KitInventoryBaseline.Stack("golden_carrot", 57));
        var after = Map.of(
                13, new KitInventoryBaseline.Stack("golden_carrot", 57),
                20, new KitInventoryBaseline.Stack("end_crystal", 65));
        assertFalse(KitInventoryBaseline.compare(before, after, "end_crystal", 64).preserved());
    }

    @Test void everyBorrowedBoxMustRemainExactlyAccountedFor() {
        var shell = new KitShulkerLedger.Identity("white_shulker_box", Map.of("obsidian", 128));
        var empty = new KitShulkerLedger.Identity("white_shulker_box", Map.of());
        var crystals = new KitShulkerLedger.Identity("shulker_box", Map.of("end_crystal", 1585));
        var expected = Map.of(shell, 1, empty, 1, crystals, 1);
        assertTrue(KitShulkerLedger.matchesPendingReturns(
                expected, shell, List.of(empty, crystals)));
        assertFalse(KitShulkerLedger.matchesPendingReturns(
                Map.of(shell, 1, empty, 1), shell, List.of(empty, crystals)));
        assertFalse(KitShulkerLedger.matchesPendingReturns(
                Map.of(shell, 1, empty, 1, crystals, 1,
                        new KitShulkerLedger.Identity("purple_shulker_box", Map.of()), 1),
                shell, List.of(empty, crystals)));
    }
}
