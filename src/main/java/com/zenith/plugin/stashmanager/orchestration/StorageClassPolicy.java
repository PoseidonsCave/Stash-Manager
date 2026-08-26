package com.zenith.plugin.stashmanager.orchestration;

import java.util.Locale;

/** Defines the exact item identity used by lanes, reconciliation, and future kit recipes. */
public final class StorageClassPolicy {
    private StorageClassPolicy() {}

    /**
     * Keep the complete item id. Structural variants and meaningful suffixes are separate
     * storage classes: slabs are not blocks, stairs are not slabs, and fortune is not silk.
     */
    public static String exact(String itemId) {
        if (itemId == null) return null;
        String normalized = itemId.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("minecraft:")) normalized = normalized.substring("minecraft:".length());
        return normalized.isBlank() ? null : normalized;
    }
}
