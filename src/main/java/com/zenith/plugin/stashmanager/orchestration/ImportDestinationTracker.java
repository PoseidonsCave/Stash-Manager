package com.zenith.plugin.stashmanager.orchestration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Remembers live import-capacity evidence across organizer transactions. */
public final class ImportDestinationTracker {
    private final Set<Long> saturated = new HashSet<>();
    private Long preferredWritable;

    public void reset() {
        saturated.clear();
        preferredWritable = null;
    }

    public void recordSaturated(long inventoryKey) {
        saturated.add(inventoryKey);
        if (preferredWritable != null && preferredWritable == inventoryKey) {
            preferredWritable = null;
        }
    }

    public void recordWritable(long inventoryKey) {
        saturated.remove(inventoryKey);
        preferredWritable = inventoryKey;
    }

    public boolean isSaturated(long inventoryKey) {
        return saturated.contains(inventoryKey);
    }

    /** Preferred live success first, then every other candidate in stable planning order. */
    public List<Long> order(Collection<Long> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        List<Long> ordered = new ArrayList<>();
        if (preferredWritable != null
                && !saturated.contains(preferredWritable)
                && candidates.contains(preferredWritable)) {
            ordered.add(preferredWritable);
        }
        for (Long candidate : candidates) {
            if (candidate == null || saturated.contains(candidate) || ordered.contains(candidate)) {
                continue;
            }
            ordered.add(candidate);
        }
        return List.copyOf(ordered);
    }
}
