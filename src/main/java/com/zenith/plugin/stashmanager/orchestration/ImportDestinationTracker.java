package com.zenith.plugin.stashmanager.orchestration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Keeps successful import cadence separate for each cargo class. */
public final class ImportDestinationTracker {
    private static final String DEFAULT_CARGO = "";

    private final Map<String, Long> preferredWritableByCargo = new HashMap<>();

    public void reset() {
        preferredWritableByCargo.clear();
    }

    /** A rejection is valid only for the caller's current transaction. */
    public void recordRejected(long inventoryKey, String cargoKey) {
        String normalizedCargo = normalizeCargo(cargoKey);
        Long preferred = preferredWritableByCargo.get(normalizedCargo);
        if (preferred != null && preferred == inventoryKey) {
            preferredWritableByCargo.remove(normalizedCargo);
        }
    }

    public void recordWritable(long inventoryKey, String cargoKey) {
        preferredWritableByCargo.put(normalizeCargo(cargoKey), inventoryKey);
    }

    /** Preferred live success for this cargo first, then the caller's ranked candidates. */
    public List<Long> order(Collection<Long> candidates, String cargoKey) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        List<Long> ordered = new ArrayList<>();
        Long preferred = preferredWritableByCargo.get(normalizeCargo(cargoKey));
        if (preferred != null && candidates.contains(preferred)) {
            ordered.add(preferred);
        }
        for (Long candidate : candidates) {
            if (candidate == null || ordered.contains(candidate)) {
                continue;
            }
            ordered.add(candidate);
        }
        return List.copyOf(ordered);
    }

    public List<Long> order(Collection<Long> candidates) {
        return order(candidates, DEFAULT_CARGO);
    }

    private static String normalizeCargo(String cargoKey) {
        return Objects.requireNonNullElse(cargoKey, DEFAULT_CARGO);
    }
}
