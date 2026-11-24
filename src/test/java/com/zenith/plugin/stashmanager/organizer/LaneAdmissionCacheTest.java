package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LaneAdmissionCacheTest {
    @Test
    void onlyObservedEmptySlotsAuthorizePickups() {
        var cache = new LaneAdmissionCache();
        assertEquals(0, cache.reserve(1, 1000));
        cache.record(1, 2, 1000);
        assertEquals(121000, cache.reserve(1, 1001));
        assertEquals(121000, cache.reserve(1, 1002));
        assertEquals(0, cache.reserve(1, 1003));
        cache.record(1, 1, 1004);
        assertNotEquals(0, cache.reserve(1, 1005));
    }

    @Test
    void fullStaleAndClockReversedObservationsCannotAuthorizeAPickup() {
        var cache = new LaneAdmissionCache();
        cache.record(1, 0, 1000);
        assertEquals(0, cache.reserve(1, 1001));
        cache.record(1, 54, 1000);
        assertEquals(0, cache.reserve(1, 999));
        assertEquals(0, cache.reserve(1, 1000 + LaneAdmissionCache.FRESH_MILLIS));
        cache.record(1, 54, 2000);
        cache.reset();
        assertEquals(0, cache.reserve(1, 2001));
    }
}
