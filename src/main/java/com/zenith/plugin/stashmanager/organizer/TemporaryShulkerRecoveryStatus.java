package com.zenith.plugin.stashmanager.organizer;

/** Describes where a temporary shulker is known to be after a packing failure. */
final class TemporaryShulkerRecoveryStatus {
    enum CargoState {
        PLACED_BLOCK,
        INVENTORY,
        UNVERIFIED_DROP
    }

    record Assessment(
            boolean blockPresent,
            boolean inventoryRecovered,
            boolean cargoPreserved,
            CargoState cargoState) { }

    private TemporaryShulkerRecoveryStatus() { }

    static Assessment assess(boolean blockPresent, int expectedInventoryCount, int currentInventoryCount) {
        return assess(blockPresent, expectedInventoryCount, currentInventoryCount, false, false);
    }

    static Assessment assess(
            boolean blockPresent,
            int expectedInventoryCount,
            int currentInventoryCount,
            boolean collectionConfirmed,
            boolean expectedInventoryShapePresent) {
        // A still-present block always wins. Shape evidence can match another same-colored
        // empty box already in a full inventory and must never make us abandon our placed box.
        boolean inventoryRecovered = !blockPresent && (collectionConfirmed
                || expectedInventoryShapePresent
                || (expectedInventoryCount > 0 && currentInventoryCount >= expectedInventoryCount));
        CargoState cargoState = blockPresent
                ? CargoState.PLACED_BLOCK
                : inventoryRecovered ? CargoState.INVENTORY : CargoState.UNVERIFIED_DROP;
        return new Assessment(
                blockPresent,
                inventoryRecovered,
                blockPresent || inventoryRecovered,
                cargoState);
    }
}
