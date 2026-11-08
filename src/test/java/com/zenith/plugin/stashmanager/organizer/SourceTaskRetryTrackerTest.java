package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceTaskRetryTrackerTest {
    @Test
    void equalTasksKeepIndependentRetryCounts() {
        record Task(String source, String item) { }

        Task first = new Task("same-chest", "same-shulker");
        Task second = new Task("same-chest", "same-shulker");
        SourceTaskRetryTracker<Task> tracker = new SourceTaskRetryTracker<>();

        assertEquals(1, tracker.recordFailure(first));
        assertEquals(2, tracker.recordFailure(first));
        assertEquals(1, tracker.recordFailure(second));
        tracker.recordSuccess(first);
        assertEquals(1, tracker.recordFailure(first));
    }
}
