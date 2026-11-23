package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitRecoveryIdentityTest {
    @Test
    void sourceCanBePartiallyEmptiedButCannotChangeColorOrGainUnknownCargo() {
        Map<String, Integer> before = Map.of("end_crystal", 64, "obsidian", 64);
        assertTrue(KitRecoveryIdentity.sourceMatches("PURPLE_SHULKER_BOX",
                "purple_shulker_box", before, Map.of("obsidian", 32)));
        assertFalse(KitRecoveryIdentity.sourceMatches("BLUE_SHULKER_BOX",
                "purple_shulker_box", before, Map.of("obsidian", 32)));
        assertFalse(KitRecoveryIdentity.sourceMatches("PURPLE_SHULKER_BOX",
                "purple_shulker_box", before, Map.of("obsidian", 65)));
        assertFalse(KitRecoveryIdentity.sourceMatches("PURPLE_SHULKER_BOX",
                "purple_shulker_box", before, Map.of("diamond", 1)));
    }

    @Test
    void unopenedSignedBooksMayReserializeOnlyWhenOtherContentsRemainExact() {
        Map<String, Integer> before = Map.of("firework_rocket", 960,
                "written_book[book=old1]", 1, "written_book[book=old2]", 1);
        Map<String, Integer> observed = Map.of("firework_rocket", 960,
                "written_book[book=new1]", 1, "written_book[book=new2]", 1);
        assertTrue(KitRecoveryIdentity.unopenedSignedBookRoundTrip(before, observed));
        assertTrue(KitRecoveryIdentity.sourceMatches("RED_SHULKER_BOX",
                "red_shulker_box", before, observed));
        assertTrue(KitRecoveryIdentity.recoveredSourceItemMatches(before, observed, before));
        assertTrue(KitRecoveryIdentity.recoveredSourceItemMatches(before, observed, observed));
        assertFalse(KitRecoveryIdentity.recoveredSourceItemMatches(before, observed,
                Map.of("firework_rocket", 960, "written_book[book=third]", 2)));
        assertFalse(KitRecoveryIdentity.unopenedSignedBookRoundTrip(before,
                Map.of("firework_rocket", 959, "written_book[book=new1]", 1,
                        "written_book[book=new2]", 1)));
        assertFalse(KitRecoveryIdentity.unopenedSignedBookRoundTrip(before,
                Map.of("firework_rocket", 960, "written_book[book=new1]", 1)));
        assertFalse(KitRecoveryIdentity.unopenedSignedBookRoundTrip(before,
                Map.of("firework_rocket", 960, "written_book[book=new1]", 1,
                        "written_book[book=new2]", 1, "diamond", 1)));
        assertFalse(KitRecoveryIdentity.sourceMatches("BLUE_SHULKER_BOX",
                "red_shulker_box", before, observed));
    }

    @Test
    void kitCanBePartiallyFilledButMustRetainPriorBatches() {
        Map<String, Integer> recipe = Map.of("firework_rocket", 576,
                "experience_bottle", 576, "totem_of_undying", 9);
        Map<String, Integer> packed = Map.of("firework_rocket", 576);
        assertTrue(KitRecoveryIdentity.kitMatches(recipe, packed,
                Map.of("firework_rocket", 576, "experience_bottle", 64)));
        assertFalse(KitRecoveryIdentity.kitMatches(recipe, packed,
                Map.of("firework_rocket", 512, "experience_bottle", 64)));
        assertFalse(KitRecoveryIdentity.kitMatches(recipe, packed,
                Map.of("firework_rocket", 576, "diamond", 1)));
    }

    @Test
    void misplacedSourceRequiresExactJournaledContents() {
        Map<String, Integer> borrowed = Map.of("diamond_boots", 2,
                "diamond_boots[thorns=3]", 1);
        assertTrue(KitRecoveryIdentity.misplacedSourceMatches("SHULKER_BOX",
                "shulker_box", borrowed, borrowed));
        assertFalse(KitRecoveryIdentity.misplacedSourceMatches("BLUE_SHULKER_BOX",
                "shulker_box", borrowed, borrowed));
        assertFalse(KitRecoveryIdentity.misplacedSourceMatches("SHULKER_BOX",
                "shulker_box", borrowed, Map.of("diamond_boots", 1,
                        "diamond_boots[thorns=3]", 1)));
        assertFalse(KitRecoveryIdentity.misplacedSourceMatches("SHULKER_BOX",
                "shulker_box", borrowed, Map.of("diamond_boots", 2,
                        "diamond_boots[thorns=3]", 1, "diamond_axe", 1)));
    }
}
