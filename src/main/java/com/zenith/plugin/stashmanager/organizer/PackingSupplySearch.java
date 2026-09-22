package com.zenith.plugin.stashmanager.organizer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Bound each supply search, including across reconnects. */
final class PackingSupplySearch {
    static final int MAX_CANDIDATES = 8;
    static final int MAX_SPECULATIVE_CANDIDATES = 3;

    record Snapshot(List<Long> triedInventories, int speculativeAttempts) {}

    private final Set<Long> tried = new LinkedHashSet<>();
    private int speculativeAttempts;

    Set<Long> tried() { return Set.copyOf(tried); }

    boolean canTry(PackingSourceSelector.Candidate candidate) {
        return !tried.contains(PackingSourceSelector.inventoryKey(candidate.container()))
                && tried.size() < MAX_CANDIDATES
                && (candidate.rank() < 2 || speculativeAttempts < MAX_SPECULATIVE_CANDIDATES);
    }

    void record(PackingSourceSelector.Candidate candidate) {
        if (!canTry(candidate)) throw new IllegalStateException("Packing search budget exhausted");
        tried.add(PackingSourceSelector.inventoryKey(candidate.container()));
        if (candidate.rank() >= 2) speculativeAttempts++;
    }

    void reset() { tried.clear(); speculativeAttempts = 0; }

    Snapshot snapshot() { return new Snapshot(List.copyOf(tried), speculativeAttempts); }

    void restore(Snapshot snapshot) {
        reset();
        if (snapshot == null) return;
        if (snapshot.triedInventories() != null) tried.addAll(snapshot.triedInventories());
        speculativeAttempts = Math.max(0, snapshot.speculativeAttempts());
    }
}
