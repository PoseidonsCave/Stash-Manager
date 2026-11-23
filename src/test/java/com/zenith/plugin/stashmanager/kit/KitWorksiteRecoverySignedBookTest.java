package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KitWorksiteRecoverySignedBookTest {
    @TempDir Path temporary;
    private static final Map<String, Integer> BORROWED = Map.of(
            "firework_rocket", 960, "written_book[book=before]", 12);
    private static final Map<String, Integer> OBSERVED = Map.of(
            "firework_rocket", 960, "written_book[book=after]", 12);

    private static KitFulfillmentJournalStore.Snapshot blocked() {
        return new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "travel", "BLOCKED",
                Map.of("experience_bottle", 576, "firework_rocket", 576,
                        "totem_of_undying", 9), Map.of("experience_bottle", 576),
                1, 0, "firework_rocket", 576, 40, new int[]{1, 131, 2},
                null, new int[]{3, 130, 4}, "red_shulker_box", BORROWED,
                "kit_cargo_retrieval_incomplete", 1L, null, 0L, List.of(),
                -1, List.of(), Map.of(), 0, Map.of(), Map.of(), Map.of(), null);
    }

    @Test
    void schedulesOnlyTheRecoveredIntactSourceForReturn() throws IOException {
        var scheduled = KitWorksiteRecovery.unopenedSignedBookReturnSnapshot(
                blocked(), "red_shulker_box", OBSERVED, BORROWED, 39, 0, true, 100L);
        assertEquals("RETURNING_SOURCE", scheduled.resumeState());
        assertEquals("resume_source_return", scheduled.blocker());
        assertEquals(1, scheduled.sourceBoxesToReturn().size());
        assertEquals(BORROWED, scheduled.sourceBoxesToReturn().getFirst().contents());
        assertEquals("firework_rocket", scheduled.currentItem());
        assertEquals(576, scheduled.currentQuantity());
        assertEquals(Map.of("experience_bottle", 576), scheduled.packedContents());
        assertNull(scheduled.borrowedSourceWorksite());
        assertNull(KitResumePolicy.blocker(scheduled));
        var store = new KitFulfillmentJournalStore(temporary.resolve("travel.json"));
        store.save(blocked());
        store.save(scheduled);
        assertEquals(BORROWED, store.load().orElseThrow()
                .sourceBoxesToReturn().getFirst().contents());
    }

    @Test
    void refusesChangedCargoOrUnverifiedInventory() {
        assertNull(KitWorksiteRecovery.unopenedSignedBookReturnSnapshot(
                blocked(), "red_shulker_box", Map.of("firework_rocket", 959,
                        "written_book[book=after]", 12), BORROWED, 39, 0, true, 100L));
        assertNull(KitWorksiteRecovery.unopenedSignedBookReturnSnapshot(
                blocked(), "red_shulker_box", OBSERVED, BORROWED, 39, 1, true, 100L));
        assertNull(KitWorksiteRecovery.unopenedSignedBookReturnSnapshot(
                blocked(), "red_shulker_box", OBSERVED, BORROWED, 39, 0, false, 100L));
        assertNull(KitWorksiteRecovery.unopenedSignedBookReturnSnapshot(
                blocked(), "red_shulker_box", OBSERVED, BORROWED, -1, 0, true, 100L));
        assertNull(KitWorksiteRecovery.unopenedSignedBookReturnSnapshot(
                blocked(), "blue_shulker_box", OBSERVED, BORROWED, 39, 0, true, 100L));
        assertNull(KitWorksiteRecovery.unopenedSignedBookReturnSnapshot(
                blocked(), "red_shulker_box", OBSERVED,
                Map.of("firework_rocket", 960, "written_book[book=other]", 12),
                39, 0, true, 100L));
    }

    @Test
    void intactHeldSourceCanBeScheduledAfterWorksiteBreak() {
        var scheduled = KitWorksiteRecovery.unopenedSignedBookReturnSnapshot(
                blocked(), "red_shulker_box", BORROWED, BORROWED,
                36, 0, true, 100L);
        assertEquals(BORROWED, scheduled.sourceBoxesToReturn().getFirst().contents());
        assertNull(KitResumePolicy.blocker(scheduled));
    }
}
