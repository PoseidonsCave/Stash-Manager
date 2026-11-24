package com.zenith.plugin.stashmanager.kit;

import java.util.Map;
import java.util.TreeSet;

/** Bounded, single-line evidence for a kit inventory-baseline rejection. */
public final class KitBaselineMismatchDiagnostic {
    private static final int MAX_DIFFERENCES = 3;
    private static final int MAX_ITEM_ID_LENGTH = 96;

    private KitBaselineMismatchDiagnostic() { }

    public static String looseTotals(Map<String, Integer> expected,
                                     Map<String, Integer> observed) {
        var keys = new TreeSet<String>();
        keys.addAll(expected.keySet());
        keys.addAll(observed.keySet());
        var out = new StringBuilder("loose_totals");
        int changed = 0;
        for (String key : keys) {
            int before = expected.getOrDefault(key, 0);
            int after = observed.getOrDefault(key, 0);
            if (before == after) continue;
            changed++;
            if (changed <= MAX_DIFFERENCES) {
                out.append("; ").append(safeItemId(key))
                        .append(" expected=").append(before)
                        .append(" observed=").append(after);
            }
        }
        out.append("; changed_items=").append(changed);
        return out.toString();
    }

    public static String shulkerLedger(String reason,
                                       Map<KitShulkerLedger.Identity, Integer> expected,
                                       Map<KitShulkerLedger.Identity, Integer> observed) {
        int missing = 0;
        int extra = 0;
        var identities = new java.util.HashSet<KitShulkerLedger.Identity>();
        identities.addAll(expected.keySet());
        identities.addAll(observed.keySet());
        for (var identity : identities) {
            int delta = observed.getOrDefault(identity, 0)
                    - expected.getOrDefault(identity, 0);
            if (delta < 0) missing -= delta;
            if (delta > 0) extra += delta;
        }
        return "shulker_ledger; reason=" + safeItemId(reason)
                + "; expected_boxes=" + total(expected)
                + "; observed_boxes=" + total(observed)
                + "; missing_boxes=" + missing
                + "; extra_boxes=" + extra;
    }

    private static int total(Map<KitShulkerLedger.Identity, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static String safeItemId(String value) {
        if (value == null) return "unknown";
        String safe = value.replaceAll("[^a-zA-Z0-9_:.,=\\[\\]-]", "?");
        return safe.length() <= MAX_ITEM_ID_LENGTH
                ? safe : safe.substring(0, MAX_ITEM_ID_LENGTH) + "...";
    }
}
