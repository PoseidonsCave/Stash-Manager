package com.zenith.plugin.stashmanager.organizer;

import java.util.*;

/** Track item/chest pairs across partial deposits and interrupted staging visits. */
final class MixedStagingLedger {
    record Source(String itemId, int x, int y, int z) {
        Source {
            if (itemId == null || itemId.isBlank()) throw new IllegalArgumentException("Missing staged item type");
        }
        int[] position() { return new int[]{x, y, z}; }
    }
    record Snapshot(List<Source> confirmed, List<Source> uncertain, boolean exact) {
        Snapshot {
            confirmed = confirmed == null ? List.of() : List.copyOf(confirmed);
            uncertain = uncertain == null ? List.of() : List.copyOf(uncertain);
        }
    }

    private final Set<Source> confirmed = new LinkedHashSet<>();
    private final Set<Source> uncertain = new LinkedHashSet<>();
    private Source pending;
    private boolean exact = true;

    void reset() {
        confirmed.clear();
        uncertain.clear();
        pending = null;
        exact = true;
    }

    void restore(Snapshot snapshot) {
        reset();
        exact = snapshot != null && snapshot.exact();
        if (snapshot != null) {
            confirmed.addAll(snapshot.confirmed());
            uncertain.addAll(snapshot.uncertain());
        }
    }

    void begin(String itemId, int[] position) {
        pending = new Source(itemId, position[0], position[1], position[2]);
        uncertain.add(pending);
    }

    void confirm() {
        if (pending == null) return;
        confirmed.add(pending);
        uncertain.remove(pending);
        pending = null;
    }

    Snapshot snapshot() {
        return new Snapshot(List.copyOf(confirmed), List.copyOf(uncertain), exact);
    }

    List<Source> sources(Collection<String> classes, Collection<int[]> legacySources) {
        Set<Source> result = new LinkedHashSet<>(confirmed);
        result.addAll(uncertain);
        if (!exact) {
            // Old checkpoints did not record which item went into each visited chest.
            for (String item : classes) {
                for (int[] pos : legacySources) result.add(new Source(item, pos[0], pos[1], pos[2]));
            }
        }
        return result.stream().sorted(Comparator.comparing(Source::itemId)
                .thenComparingInt(Source::x).thenComparingInt(Source::y).thenComparingInt(Source::z)).toList();
    }
}
