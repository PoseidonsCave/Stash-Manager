package com.zenith.plugin.stashmanager.kit;

import java.util.Map;

/** An operator's no-cargo declaration must account for every live matching stack. */
public final class KitNoCargoKeepAudit {
    private KitNoCargoKeepAudit() { }

    public static boolean matches(Map<Integer, KitCargoAudit.Stack> observed,
                                  String itemId, int ownedShellSlot,
                                  Map<Integer, Integer> declaredKept) {
        if (observed == null || itemId == null || declaredKept == null
                || declaredKept.isEmpty()) return false;
        for (var entry : declaredKept.entrySet()) {
            Integer raw = entry.getKey();
            Integer count = entry.getValue();
            if (raw == null || raw < 9 || raw > 44 || raw == ownedShellSlot
                    || count == null || count < 1 || count > 64) return false;
            var stack = observed.get(raw);
            if (stack == null || !itemId.equals(stack.itemId())
                    || stack.amount() != count) return false;
        }
        for (int raw = 9; raw <= 44; raw++) {
            var stack = observed.get(raw);
            if (stack != null && stack.amount() > 0 && itemId.equals(stack.itemId())
                    && !declaredKept.containsKey(raw)) return false;
        }
        return true;
    }
}
