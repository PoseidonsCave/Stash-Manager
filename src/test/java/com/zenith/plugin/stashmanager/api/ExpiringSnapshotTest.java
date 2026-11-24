package com.zenith.plugin.stashmanager.api;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpiringSnapshotTest {
    @Test
    void reusesSuccessfulReadUntilExpiryAndDoesNotCacheFailures() {
        var clock = new AtomicLong(100);
        var reads = new AtomicInteger();
        var snapshot = new ExpiringSnapshot<Integer>(10, clock::get);

        assertEquals(1, snapshot.get(reads::incrementAndGet));
        clock.set(109);
        assertEquals(1, snapshot.get(reads::incrementAndGet));
        assertEquals(1, reads.get());

        clock.set(110);
        assertEquals(2, snapshot.get(reads::incrementAndGet));
        assertEquals(2, reads.get());

        var failed = new ExpiringSnapshot<Integer>(10, clock::get);
        assertEquals(null, failed.get(() -> null));
        assertEquals(3, failed.get(reads::incrementAndGet));
    }
}
