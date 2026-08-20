package com.zenith.plugin.stashmanager.orchestration;

/** Ownership boundary between scanned inventories and organizer-managed storage. */
public final class OrganizerOwnershipPolicy {
    private OrganizerOwnershipPolicy() {}

    /**
     * A lane or an explicitly registered import inventory may supply organization work.
     * This decides ownership of the containing inventory only; cargo classification (including
     * loose shulker-box items) remains identical for both source roles.
     */
    public static boolean isManagedSource(boolean laneInventory, boolean importInventory) {
        return laneInventory || importInventory;
    }

    /** Imports are never permanent lane destinations. */
    public static boolean isDestination(boolean laneInventory, boolean importInventory) {
        return laneInventory && !importInventory;
    }

    /** A produced bulk shulker may wait in an import only when no permanent lane was assigned. */
    public static boolean isReconciliationStagingDestination(
            boolean importInventory,
            boolean permanentLaneAssigned) {
        return importInventory && !permanentLaneAssigned;
    }
}
