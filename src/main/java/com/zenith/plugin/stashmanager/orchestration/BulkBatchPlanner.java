package com.zenith.plugin.stashmanager.orchestration;

import java.util.Objects;

/** Pure decision boundary for gathering one exact bulk class across source containers. */
public final class BulkBatchPlanner {
    private BulkBatchPlanner() { }

    public static boolean shouldCollectNext(
            String currentStorageKey,
            String nextStorageKey,
            boolean nextAlreadyInInventory,
            boolean inventoryHasRoom) {
        return inventoryHasRoom
                && !nextAlreadyInInventory
                && Objects.equals(currentStorageKey, nextStorageKey);
    }
}
