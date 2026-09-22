package com.zenith.plugin.stashmanager.organizer;

import java.util.Objects;

/** Bound repeated cargo evacuation for the same blocked mixed box. */
final class InventoryRecoveryGuard {
    static final int MAX_ATTEMPTS = 3;

    record Snapshot(String sourceKey, int attempts, int fewestCargoSlots, int mostFreeSlots) {}

    private String sourceKey;
    private int attempts;
    private int fewestCargoSlots;
    private int mostFreeSlots;

    boolean allowRecovery(String key, int cargoSlots, int freeSlots) {
        Objects.requireNonNull(key, "source key");
        int cargo = Math.max(0, cargoSlots);
        int free = Math.max(0, freeSlots);
        if (!key.equals(sourceKey)) {
            sourceKey = key;
            attempts = 0;
            fewestCargoSlots = cargo;
            mostFreeSlots = free;
        } else if (cargo < fewestCargoSlots || free > mostFreeSlots) {
            attempts = 0;
            fewestCargoSlots = Math.min(fewestCargoSlots, cargo);
            mostFreeSlots = Math.max(mostFreeSlots, free);
        }
        if (attempts >= MAX_ATTEMPTS) return false;
        attempts++;
        return true;
    }

    Snapshot snapshot() {
        return new Snapshot(sourceKey, attempts, fewestCargoSlots, mostFreeSlots);
    }

    void restore(Snapshot saved) {
        reset();
        if (saved == null || saved.sourceKey() == null) return;
        sourceKey = saved.sourceKey();
        attempts = Math.clamp(saved.attempts(), 0, MAX_ATTEMPTS);
        fewestCargoSlots = Math.max(0, saved.fewestCargoSlots());
        mostFreeSlots = Math.max(0, saved.mostFreeSlots());
    }

    void reset() {
        sourceKey = null;
        attempts = 0;
        fewestCargoSlots = 0;
        mostFreeSlots = 0;
    }
}
