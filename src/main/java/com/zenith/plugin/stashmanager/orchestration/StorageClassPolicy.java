package com.zenith.plugin.stashmanager.orchestration;

import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import com.zenith.plugin.stashmanager.profile.ItemProfiles;

/** Keeps physical item identity separate from the lane it is routed into. */
public final class StorageClassPolicy {
    private StorageClassPolicy() {}

    /** Preserve the full observed item key for inventory movements and kit selection. */
    public static String exact(String itemId) {
        String normalized = ItemIdentifier.normalizeItemId(itemId);
        return normalized == null || normalized.isBlank() ? null : normalized;
    }

    public static String route(String observedItemId) {
        return ItemProfiles.route(observedItemId);
    }

    public static boolean sameClass(String left, String right) {
        String a = route(left);
        String b = route(right);
        return a != null && a.equals(b);
    }
}
