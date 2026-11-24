package com.zenith.plugin.stashmanager.api;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Short-lived API read snapshot; never used for automation or safety decisions. */
final class ExpiringSnapshot<T> {
    private final long ttlNanos;
    private final LongSupplier clock;
    private volatile T value;
    private volatile long expiresAt;

    ExpiringSnapshot(long ttlNanos, LongSupplier clock) {
        this.ttlNanos = ttlNanos;
        this.clock = clock;
    }

    T get(Supplier<T> loader) {
        long now = clock.getAsLong();
        T cached = value;
        if (cached != null && now - expiresAt < 0) return cached;
        synchronized (this) {
            now = clock.getAsLong();
            cached = value;
            if (cached != null && now - expiresAt < 0) return cached;
            T loaded = loader.get();
            if (loaded != null) {
                value = loaded;
                expiresAt = now + ttlNanos;
            }
            return loaded;
        }
    }
}
