package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TailRetryTrackerTest {
    @Test
    void transientFailuresRetryWithoutCreatingANewLogicalTask() {
        TailRetryTracker<Long> tracker = new TailRetryTracker<>(3);

        TailRetryTracker.Decision first = tracker.recordFailure(42L);
        TailRetryTracker.Decision second = tracker.recordFailure(42L);

        assertTrue(first.shouldRetry());
        assertTrue(second.shouldRetry());
        assertEquals(1, first.attempt());
        assertEquals(2, second.attempt());
        assertEquals(2, tracker.recordSuccess(42L));
    }

    @Test
    void thirdFailedVisitIsTerminal() {
        TailRetryTracker<Long> tracker = new TailRetryTracker<>(3);

        tracker.recordFailure(7L);
        tracker.recordFailure(7L);
        TailRetryTracker.Decision exhausted = tracker.recordFailure(7L);

        assertFalse(exhausted.shouldRetry());
        assertEquals(TailRetryTracker.Disposition.TERMINAL_FAILURE, exhausted.disposition());
        assertEquals(3, exhausted.attempt());
    }
}
