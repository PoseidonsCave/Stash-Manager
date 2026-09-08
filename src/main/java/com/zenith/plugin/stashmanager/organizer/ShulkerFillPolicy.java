package com.zenith.plugin.stashmanager.organizer;

/** Keeps packing decisions based on actual stack capacity and observed movement. */
final class ShulkerFillPolicy {
    private ShulkerFillPolicy() {}

    static boolean hasCapacity(int emptySlots, int matchingStackHeadroom) {
        return emptySlots > 0 || matchingStackHeadroom > 0;
    }

    static boolean stalled(int movedUnits, int looseItemsRemaining) {
        return movedUnits == 0 && looseItemsRemaining > 0;
    }
}
