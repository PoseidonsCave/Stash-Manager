package com.zenith.plugin.stashmanager.organizer;

/** Chooses the safe next transaction when the intended destination rejects cargo. */
final class CargoDestinationPolicy {
    enum FullDestinationAction {
        TRY_ALTERNATE_IMPORT,
        STAGE_SHULKER_IN_IMPORT,
        PACK_LOOSE_INTO_IMPORT
    }

    private CargoDestinationPolicy() {}

    static FullDestinationAction afterPermanentCascadeExhausted(
            boolean destinationIsImport,
            boolean cargoIsShulker) {
        if (destinationIsImport) return FullDestinationAction.TRY_ALTERNATE_IMPORT;
        return cargoIsShulker
                ? FullDestinationAction.STAGE_SHULKER_IN_IMPORT
                : FullDestinationAction.PACK_LOOSE_INTO_IMPORT;
    }
}
