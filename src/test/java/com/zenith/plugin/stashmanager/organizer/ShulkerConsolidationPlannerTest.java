package com.zenith.plugin.stashmanager.organizer;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.orchestration.ShulkerClassification;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ShulkerConsolidationPlannerTest {
    @Test
    void selectsFreshCompatibleDonorThenExcludesItsAttemptKey() {
        var small = box(1, 5, "netherite_sword[sharpness=5]", 5, 100);
        var larger = box(2, 20, "netherite_sword[sharpness=5]", 20, 100);
        var candidate = ShulkerConsolidationPlanner.next(
                List.of(larger, small), ignored -> true, 90, Set.of()).orElseThrow();
        assertEquals(small, candidate.source());
        assertEquals(larger, candidate.recipient());
        assertEquals(25, candidate.occupiedSlots() + candidate.recipientSlots());
        assertTrue(ShulkerConsolidationPlanner.next(
                List.of(larger, small), ignored -> true, 90,
                Set.of(candidate.attemptKey())).isEmpty());
    }

    @Test
    void rejectsStaleUnknownOversizedAndDifferentItemBoxes() {
        var stale = box(1, 4, "stone", 4, 10);
        var tooLarge = box(2, 24, "stone", 24, 100);
        var other = box(3, 4, "cobblestone", 4, 100);
        assertTrue(ShulkerConsolidationPlanner.next(
                List.of(stale, tooLarge, other), ignored -> true, 90, Set.of()).isEmpty());
        var fresh = box(4, 2, "stone", 2, 100);
        assertTrue(ShulkerConsolidationPlanner.next(
                List.of(fresh, tooLarge), entry -> entry.x() != 4, 90, Set.of()).isEmpty());
        assertEquals(fresh, ShulkerConsolidationPlanner.next(
                List.of(fresh, tooLarge), ignored -> true, 90, Set.of()).orElseThrow().source());
    }

    @Test
    void neverUnpacksCraftMaterialsAsConsolidationDonors() {
        assertTrue(ShulkerConsolidationPlanner.next(
                List.of(box(1, 1, "chest", 1, 100), box(2, 1, "chest", 1, 100)),
                ignored -> true, 90, Set.of()).isEmpty());
        assertTrue(ShulkerConsolidationPlanner.next(
                List.of(box(1, 1, "shulker_shell", 1, 100),
                        box(2, 1, "shulker_shell", 1, 100)),
                ignored -> true, 90, Set.of()).isEmpty());
    }

    @Test
    void bulkDonorPassesTheVerifiedDecompositionSourceGate() throws Exception {
        var contents = Map.of("stone", 64);
        var classified = ShulkerClassification.classify(contents);
        var task = StashOrganizer.MoveTask.mixed(
                new int[]{1, 64, 0}, new int[]{2, 64, 0},
                "purple_shulker_box", classified.fingerprint(), contents);
        Method gate = StashOrganizer.class.getDeclaredMethod(
                "isDecompositionSourceShape", StashOrganizer.MoveTask.class,
                ShulkerClassification.class);
        gate.setAccessible(true);
        assertEquals(true, gate.invoke(null, task, classified));
        assertNotEquals(task.shulkerContentFilter(),
                ShulkerClassification.classify(Map.of("dirt", 64)).fingerprint());
    }

    private static ContainerEntry box(int x, int slot, String itemId, int occupiedSlots, long time) {
        var detail = new ContainerEntry.ShulkerDetail(slot, "purple",
                Map.of(itemId, occupiedSlots), Map.of(itemId, occupiedSlots));
        return new ContainerEntry(x, 64, 0, "chest", false,
                Map.of("purple_shulker_box", 1, itemId, occupiedSlots), 1,
                List.of(detail), time);
    }
}
