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

    /** Choose among physically interchangeable duplicate source boxes only after
     * the entire carried-box multiset is accounted for. Fingerprints include
     * every serialized component, including any custom presentation. Their
     * source positions are checked by the caller against the journal.
     */
    public static int interchangeablePendingSourceSlot(
            Map<Integer, Identity> observed, int ownedRaw, Identity owned,
            List<Identity> pending, Identity wanted,
            Map<Integer, String> fingerprints) {
        if (observed == null || pending == null || wanted == null
                || fingerprints == null) return -1;
        long obligations = pending.stream().filter(wanted::equals).count();
        if (obligations < 2) return -1;
        Set<Integer> accounted = pendingReturnSlots(observed, ownedRaw, owned, pending);
        if (accounted.size() != pending.size()) return -1;
        String fingerprint = null;
        int chosen = -1;
        int matches = 0;
        for (int raw : accounted) {
            if (!wanted.equals(observed.get(raw))) continue;
            String observedFingerprint = fingerprints.get(raw);
            if (observedFingerprint == null
                    || fingerprint != null && !fingerprint.equals(observedFingerprint))
                return -1;
            fingerprint = observedFingerprint;
            chosen = chosen < 0 ? raw : Math.min(chosen, raw);
            matches++;
        }
        return matches == obligations ? chosen : -1;
    }

    /** Return a unique kit shell, excluding a recovered source even if it looks identical. */
    public static int ownedSlot(Map<Integer, Identity> slots, int preferred,
                                Map<String, Integer> packedContents, Identity recoveredSource) {
        if (packedContents == null) return -1;
        if (recoveredSource != null && recoveredSource.contents().equals(packedContents)) {
            int sourceCount = 0;
            int owned = -1;
            for (var entry : slots.entrySet()) {
                Identity box = entry.getValue();
                if (box == null || !packedContents.equals(box.contents())) continue;
                if (recoveredSource.equals(box)) {
                    sourceCount++;
                } else {
                    if (owned >= 0) return -1;
                    owned = entry.getKey();
                }
            }
            // During a return the server can remove the source from inventory
            // before the exporter has reopened the target chest. The distinct
            // shell remains identifiable; the exporter must still verify the
            // deposit independently before clearing the return obligation.
            return sourceCount <= 1 ? owned : -1;
        }
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

    /** The packed shell and a pending source may become physically identical.
     * Permit the journaled shell slot to stand only if the whole carried-box
     * inventory is exactly those two boxes and every serialized component is
     * identical. The caller still has to verify the source deposit separately.
     */
    public static int interchangeableOwnedSourceSlot(
            Map<Integer, Identity> observed, int ownedRaw, Identity owned,
            Identity source, Map<Integer, String> fingerprints) {
        if (observed == null || owned == null || source == null
                || fingerprints == null || ownedRaw < 9 || ownedRaw > 44
                || observed.size() != 2 || !owned.equals(source)
                || !owned.equals(observed.get(ownedRaw))) return -1;
        String ownedFingerprint = fingerprints.get(ownedRaw);
        if (ownedFingerprint == null) return -1;
        for (var entry : observed.entrySet()) {
            int raw = entry.getKey();
            if (raw == ownedRaw) continue;
            if (raw < 9 || raw > 44 || !source.equals(entry.getValue())
                    || !ownedFingerprint.equals(fingerprints.get(raw))) return -1;
            return ownedRaw;
        }
        return -1;
    }

    /** Identify the shell after retrieval when a source box has become empty.
     * A recovered box with the same contents but a different item identity is
     * excluded; a matching source identity remains ambiguous and fails closed.
     */
    public static int ownedSlotAfterRetrieval(Map<Integer, Identity> slots,
                                              Map<String, Integer> packedContents,
                                              Map<Identity, Integer> recoveredSources) {
        if (slots == null || packedContents == null || recoveredSources == null) return -1;
        int found = -1;
        for (var entry : slots.entrySet()) {
            Identity box = entry.getValue();
            if (box == null || !packedContents.equals(box.contents())
                    || recoveredSources.containsKey(box)) continue;
            if (found >= 0) return -1;
            found = entry.getKey();
        }
        return found;
    }

    /** A source emptied during retrieval can become physically identical to the
     * kit shell. Either box can then fill the shell role, but only if the full
     * before/recovered/after ledger and serialized components agree exactly.
     */
    public static int interchangeableOwnedSlotAfterRetrieval(
            Map<Integer, Identity> slots, int preferred,
            Map<String, Integer> packedContents,
            Map<Identity, Integer> before,
            Map<Identity, Integer> recoveredSources,
            Map<Integer, String> fingerprints) {
        if (slots == null || packedContents == null || before == null
                || recoveredSources == null || fingerprints == null) return -1;
        Identity owned = null;
        for (var entry : before.entrySet()) {
            if (!packedContents.equals(entry.getKey().contents())) continue;
            if (owned != null || !Integer.valueOf(1).equals(entry.getValue())) return -1;
            owned = entry.getKey();
        }
        if (owned == null || !Integer.valueOf(1).equals(recoveredSources.get(owned)))
            return -1;
        for (var entry : recoveredSources.entrySet()) {
            if (!owned.equals(entry.getKey())
                    && packedContents.equals(entry.getKey().contents())) return -1;
        }
        Map<Identity, Integer> observed = new HashMap<>();
        int first = -1;
        int second = -1;
        String fingerprint = null;
        for (var entry : slots.entrySet()) {
            int raw = entry.getKey();
            Identity box = entry.getValue();
            if (raw < 9 || raw > 44 || box == null) return -1;
            observed.merge(box, 1, Integer::sum);
            if (!packedContents.equals(box.contents())) continue;
            if (!owned.equals(box) || second >= 0) return -1;
            String hash = fingerprints.get(raw);
            if (hash == null || fingerprint != null && !fingerprint.equals(hash))
                return -1;
            fingerprint = hash;
            if (first < 0) first = raw;
            else second = raw;
        }
        if (second < 0 || !matchesAfterRetrieval(before, observed, recoveredSources))
            return -1;
        return preferred == first || preferred == second
                ? preferred : Math.min(first, second);
    }
}
