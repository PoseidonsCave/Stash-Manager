package com.zenith.plugin.stashmanager.kit;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Content identities for shulkers whose inventory slots can be swapped by placement. */
public final class KitShulkerLedger {
    private KitShulkerLedger() { }

    public record Identity(String itemId, Map<String, Integer> contents) {
        public Identity {
            contents = Map.copyOf(contents);
        }
    }

    public static boolean matchesAfterRetrieval(Map<Identity, Integer> before,
                                                Map<Identity, Integer> after,
                                                Identity recoveredSource) {
        return matchesAfterRetrieval(before, after,
                recoveredSource == null ? Map.of() : Map.of(recoveredSource, 1));
    }

    /** Every event-verified recovered box must be present, with no unrelated extra box. */
    public static boolean matchesAfterRetrieval(Map<Identity, Integer> before,
                                                Map<Identity, Integer> after,
                                                Map<Identity, Integer> recoveredSources) {
        if (before == null || after == null || recoveredSources == null) return false;
        Map<Identity, Integer> expected = new HashMap<>(before);
        for (var entry : recoveredSources.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0)
                return false;
            expected.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
        return expected.equals(after);
    }

    /** Exact owned shell plus every pending source, with no extra or missing carried box. */
    public static boolean matchesPendingReturns(Map<Identity, Integer> observed,
                                                Identity ownedShell,
                                                List<Identity> pendingSources) {
        if (observed == null || ownedShell == null || pendingSources == null
                || pendingSources.isEmpty()) return false;
        Map<Identity, Integer> expected = new HashMap<>();
        expected.merge(ownedShell, 1, Integer::sum);
        for (Identity source : pendingSources) {
            if (source == null) return false;
            expected.merge(source, 1, Integer::sum);
        }
        return expected.equals(observed);
    }

    /** Identify every pending source slot only when the entire carried-box set is exact. */
    public static Set<Integer> pendingReturnSlots(Map<Integer, Identity> observedSlots,
                                                  int ownedShellSlot, Identity ownedShell,
                                                  List<Identity> pendingSources) {
        if (observedSlots == null || ownedShell == null || pendingSources == null
                || pendingSources.isEmpty()
                || !ownedShell.equals(observedSlots.get(ownedShellSlot))) return Set.of();
        Map<Identity, Integer> expected = new HashMap<>();
        for (Identity source : pendingSources) {
            // The packer identifies the owned shell by contents. An identical
            // source would make that ownership ambiguous even with distinct slots.
            if (source == null || source.contents().equals(ownedShell.contents())) return Set.of();
            expected.merge(source, 1, Integer::sum);
        }
        Map<Identity, Integer> actual = new HashMap<>();
        for (var entry : observedSlots.entrySet()) {
            if (entry.getKey() == ownedShellSlot) continue;
            if (entry.getKey() < 9 || entry.getKey() > 44 || entry.getValue() == null)
                return Set.of();
            actual.merge(entry.getValue(), 1, Integer::sum);
        }
        if (!expected.equals(actual)) return Set.of();
        Set<Integer> sourceSlots = new HashSet<>(observedSlots.keySet());
        sourceSlots.remove(ownedShellSlot);
        return Set.copyOf(sourceSlots);
    }

    /** Return a unique kit shell, excluding a recovered source even if it looks identical. */
    public static int ownedSlot(Map<Integer, Identity> slots, int preferred,
                                Map<String, Integer> packedContents, Identity recoveredSource) {
        if (packedContents == null) return -1;
        if (recoveredSource != null && recoveredSource.contents().equals(packedContents)) return -1;
        if (recoveredSource == null) {
            Identity preferredBox = slots.get(preferred);
            if (preferredBox != null && preferredBox.contents().equals(packedContents)) return preferred;
        }
        int found = -1;
        for (var entry : slots.entrySet()) {
            Identity box = entry.getValue();
            if (!box.contents().equals(packedContents)) continue;
            if (found >= 0) return -1;
            found = entry.getKey();
        }
        return found;
    }
}
