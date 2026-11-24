package com.zenith.plugin.stashmanager.kit;

import java.util.function.IntPredicate;

/** Zenith searches offhand, hotbar, then main inventory when placing by item ID. */
public final class ShulkerPlacementPriority {
    private ShulkerPlacementPriority() { }

    public static int firstMatchingSlot(IntPredicate matches) {
        if (matches.test(45)) return 45;
        for (int raw = 36; raw <= 44; raw++) if (matches.test(raw)) return raw;
        for (int raw = 9; raw <= 35; raw++) if (matches.test(raw)) return raw;
        return -1;
    }
}
