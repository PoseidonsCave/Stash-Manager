package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KitDropGoalPolicyTest {
    private static final int[] SITE = {100, 70, 200};
    private static final Map<String, Integer> PACKED = Map.of("diamond_axe", 1,
            "diamond_boots", 1);

    @Test
    void followsNewExactDropInsteadOfTheWorksiteSweep() {
        var drop = new KitDropGoalPolicy.Drop(12, 7, PACKED,
                102.25, 70.3, 200.5);
        assertArrayEquals(new int[]{102, 70, 200}, KitDropGoalPolicy.target(
                List.of(drop), Set.of(3), 7, PACKED, SITE,
                goal -> goal[0] == 102 && goal[1] == 70 && goal[2] == 200));
    }

    @Test
    void usesNewEmptyMetadataOnlyAsANavigationHint() {
        var drop = new KitDropGoalPolicy.Drop(12, 7, Map.of(),
                101.25, 70.3, 200.5);
        assertArrayEquals(new int[]{100, 70, 200}, KitDropGoalPolicy.target(
                List.of(drop), Set.of(3), 7, PACKED, SITE,
                goal -> goal[0] == 100 && goal[1] == 70 && goal[2] == 200));
    }

    @Test
    void rejectsPreexistingWrongTypeWrongContentsAndDistantDrops() {
        var old = new KitDropGoalPolicy.Drop(3, 7, PACKED, 100.5, 70.5, 200.5);
        var wrongType = new KitDropGoalPolicy.Drop(4, 8, PACKED, 100.5, 70.5, 200.5);
        var wrongContents = new KitDropGoalPolicy.Drop(5, 7, Map.of("diamond_helmet", 1),
                100.5, 70.5, 200.5);
        var distant = new KitDropGoalPolicy.Drop(6, 7, PACKED, 120.5, 70.5, 200.5);
        assertNull(KitDropGoalPolicy.target(List.of(old, wrongType, wrongContents, distant),
                Set.of(3), 7, PACKED, SITE, goal -> true));
    }

    @Test
    void refusesAmbiguousDropsAndUnreachableStandingCells() {
        var a = new KitDropGoalPolicy.Drop(4, 7, Map.of(), 100.5, 70.5, 200.5);
        var b = new KitDropGoalPolicy.Drop(5, 7, Map.of(), 101.5, 70.5, 200.5);
        assertNull(KitDropGoalPolicy.target(List.of(a, b), Set.of(), 7, PACKED,
                SITE, goal -> true));
        assertNull(KitDropGoalPolicy.target(List.of(a), Set.of(), 7, PACKED,
                SITE, goal -> false));
    }
}
