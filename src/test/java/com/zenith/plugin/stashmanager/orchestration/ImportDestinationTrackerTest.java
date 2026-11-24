package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImportDestinationTrackerTest {
    @Test
    void rejectionDoesNotPoisonLaterCargoTransactions() {
        ImportDestinationTracker tracker = new ImportDestinationTracker();
        tracker.recordWritable(10L, "shulker_box");
        tracker.recordRejected(10L, "shulker_box");
        tracker.recordWritable(30L, "stone");

        assertEquals(List.of(10L, 20L, 30L),
                tracker.order(List.of(10L, 20L, 30L), "shulker_box"));
        assertEquals(List.of(30L, 10L, 20L),
                tracker.order(List.of(10L, 20L, 30L), "stone"));
    }

    @Test
    void rejectedPreferredDestinationFallsOutOfCurrentCargoCadence() {
        ImportDestinationTracker tracker = new ImportDestinationTracker();
        tracker.recordWritable(20L, "totem_of_undying");
        tracker.recordRejected(20L, "totem_of_undying");

        assertEquals(List.of(10L, 20L, 30L),
                tracker.order(List.of(10L, 20L, 30L), "totem_of_undying"));
    }

    @Test
    void resetStartsAFreshRun() {
        ImportDestinationTracker tracker = new ImportDestinationTracker();
        tracker.recordWritable(20L, "stone");
        tracker.reset();

        assertEquals(List.of(10L, 20L), tracker.order(List.of(10L, 20L), "stone"));
    }
}
