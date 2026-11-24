package com.zenith.plugin.stashmanager.kit;

import java.util.Map;

/** Verify explicitly pinned loose cargo before replaying a kit pack. */
public final class KitCargoAudit {
    private KitCargoAudit() { }

    public record Stack(String itemId, int amount) { }

    public static int verifiedSlot(Map<Integer, Stack> slots, String itemId,
                                   int quantity, int ownedShellSlot, int auditedSlot) {
        if (auditedSlot < 9 || auditedSlot > 44 || auditedSlot == ownedShellSlot
                || slots == null || itemId == null || quantity <= 0) return -1;
        Stack stack = slots.get(auditedSlot);
        return stack != null && itemId.equals(stack.itemId())
                && stack.amount() == quantity ? auditedSlot : -1;
    }

    public static int uniqueSlot(Map<Integer, Stack> slots, String itemId,
                                 int quantity, int ownedShellSlot) {
        if (slots == null || itemId == null || quantity <= 0) return -1;
        int found = -1;
        for (int raw = 9; raw <= 44; raw++) {
            Stack stack = slots.get(raw);
            if (stack == null || !itemId.equals(stack.itemId())) continue;
            if (raw == ownedShellSlot || stack.amount() != quantity || found >= 0) return -1;
            found = raw;
        }
        return found;
    }

    /** Every pinned stack must still have the exact identity and count, including across slots. */
    public static boolean verifiedBatch(Map<Integer, Stack> slots, String itemId,
                                        int quantity, int ownedShellSlot,
                                        Map<Integer, Integer> pinned) {
        return verifiedBatch(slots, itemId, quantity, ownedShellSlot, pinned, false);
    }

    /** Explicitly selected cargo may coexist with kept same-item stock. Only pinned slots move. */
    public static boolean verifiedBatch(Map<Integer, Stack> slots, String itemId,
                                        int quantity, int ownedShellSlot,
                                        Map<Integer, Integer> pinned,
                                        boolean allowUnpinnedKeptStock) {
        if (slots == null || itemId == null || quantity <= 0 || pinned == null
                || pinned.size() < 2) return false;
        int total = 0;
        for (var entry : pinned.entrySet()) {
            Integer raw = entry.getKey();
            Integer count = entry.getValue();
            if (raw == null || raw < 9 || raw > 44 || raw == ownedShellSlot
                    || count == null || count < 1 || count > 64) return false;
            Stack observed = slots.get(raw);
            if (observed == null || !itemId.equals(observed.itemId())
                    || observed.amount() != count) return false;
            total += count;
        }
        if (total != quantity) return false;
        // A matching stack outside the pinned set could be pre-existing kept
        // supply. Never silently claim it as part of this recovered batch.
        for (var entry : slots.entrySet()) {
            if (!allowUnpinnedKeptStock && !pinned.containsKey(entry.getKey())
                    && entry.getValue() != null
                    && itemId.equals(entry.getValue().itemId())) return false;
        }
        return true;
    }
}
