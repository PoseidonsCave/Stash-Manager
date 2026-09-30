package com.zenith.plugin.stashmanager.organizer;

import java.util.HashMap;
import java.util.Map;

/** Reserve one recently observed empty slot per shulker pickup. Never use scan estimates. */
final class LaneAdmissionCache {
    static final long FRESH_MILLIS = 120_000L;
    private record Space(int emptySlots, long observedAt) {}
    private final Map<Long, Space> observations = new HashMap<>();

    void reset() { observations.clear(); }

    void record(long key, int emptySlots, long now) {
        observations.put(key, new Space(Math.max(0, emptySlots), now));
    }

    /** Returns the reservation expiry, or zero when a live check is needed. */
    long reserve(long key, long now) {
        Space space = observations.get(key);
        if (space == null || space.emptySlots() <= 0 || now < space.observedAt()
                || now - space.observedAt() >= FRESH_MILLIS) return 0;
        observations.put(key, new Space(space.emptySlots() - 1, space.observedAt()));
        return space.observedAt() + FRESH_MILLIS;
    }
}
