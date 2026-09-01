package com.zenith.plugin.stashmanager.organizer;

import java.util.IdentityHashMap;
import java.util.Map;

/** Counts retries per physical plan task, even when two tasks have equal fields. */
final class SourceTaskRetryTracker<T> {
    private final Map<T, Integer> attempts = new IdentityHashMap<>();

    int recordFailure(T task) {
        return attempts.merge(task, 1, Integer::sum);
    }

    void recordSuccess(T task) {
        attempts.remove(task);
    }

    void clear() {
        attempts.clear();
    }
}
