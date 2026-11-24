package com.zenith.plugin.stashmanager.retriever;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BorrowedShulkerDropGoalTest {
    private static final int[] SITE = {100, 70, 200};
    private static final Map<String, Integer> BEFORE = Map.of("diamond_sword[custom_name]", 27);
    private static final Map<String, Integer> AFTER = Map.of("diamond_sword[custom_name]", 26);

    @Test
    void followsNewExactDropAcrossLevels() {
        var drop = new BorrowedShulkerDropGoal.Drop(12, "blue_shulker_box", AFTER,
                102.25, 69.3, 200.5);
        var target = BorrowedShulkerDropGoal.select(List.of(drop), Set.of(3),
                "blue_shulker_box", AFTER, BEFORE, SITE,
                cell -> cell[0] == 102 && cell[1] == 69 && cell[2] == 200);
        assertArrayEquals(new int[]{102, 69, 200}, target.standing());
        assertEquals("exact", target.metadataKind());
    }

    @Test
    void staleMetadataIsOnlyANavigationHint() {
        var drop = new BorrowedShulkerDropGoal.Drop(12, "blue_shulker_box", BEFORE,
                100.5, 70.3, 200.5);
        var target = BorrowedShulkerDropGoal.select(List.of(drop), Set.of(),
                "blue_shulker_box", AFTER, BEFORE, SITE, cell -> true);
        assertArrayEquals(SITE, target.standing());
        assertEquals("stale", target.metadataKind());
    }

    @Test
    void refusesOldWrongIdentityDistantAmbiguousOrUnreachableDrops() {
        var old = new BorrowedShulkerDropGoal.Drop(3, "blue_shulker_box", AFTER,
                100.5, 70.5, 200.5);
        var wrongColor = new BorrowedShulkerDropGoal.Drop(4, "red_shulker_box", AFTER,
                100.5, 70.5, 200.5);
        var wrongContents = new BorrowedShulkerDropGoal.Drop(5, "blue_shulker_box",
                Map.of("stone", 1), 100.5, 70.5, 200.5);
        var distant = new BorrowedShulkerDropGoal.Drop(6, "blue_shulker_box", AFTER,
                120.5, 70.5, 200.5);
        assertNull(BorrowedShulkerDropGoal.select(
                List.of(old, wrongColor, wrongContents, distant), Set.of(3),
                "blue_shulker_box", AFTER, BEFORE, SITE, cell -> true));

        var a = new BorrowedShulkerDropGoal.Drop(7, "blue_shulker_box", AFTER,
                100.5, 70.5, 200.5);
        var b = new BorrowedShulkerDropGoal.Drop(8, "blue_shulker_box", AFTER,
                101.5, 70.5, 200.5);
        assertNull(BorrowedShulkerDropGoal.select(List.of(a, b), Set.of(),
                "blue_shulker_box", AFTER, BEFORE, SITE, cell -> true));
        assertNull(BorrowedShulkerDropGoal.select(List.of(a), Set.of(),
                "blue_shulker_box", AFTER, BEFORE, SITE, cell -> false));
    }
}
