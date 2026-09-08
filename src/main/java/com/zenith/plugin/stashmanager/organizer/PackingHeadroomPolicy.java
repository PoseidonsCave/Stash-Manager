package com.zenith.plugin.stashmanager.organizer;

/** Keeps room for the shulker which will receive a gathered loose-item batch. */
final class PackingHeadroomPolicy {
    private PackingHeadroomPolicy() { }

    static boolean canTakeWithoutConsumingReserve(
            int freeSlots,
            int matchingStackHeadroom,
            int incomingUnits,
            int reservedSlots) {
        if (incomingUnits <= 0) return true;
        if (freeSlots > Math.max(0, reservedSlots)) return true;
        return matchingStackHeadroom >= incomingUnits;
    }
}
