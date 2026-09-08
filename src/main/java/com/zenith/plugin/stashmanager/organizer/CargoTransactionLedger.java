package com.zenith.plugin.stashmanager.organizer;

/** Tracks confirmed item-unit handoffs for one organizer task. */
final class CargoTransactionLedger {
    private int acquired;
    private int deposited;

    void reset(int initialCargoUnits) {
        acquired = Math.max(0, initialCargoUnits);
        deposited = 0;
    }

    void recordAcquired(int units) {
        acquired += Math.max(0, units);
    }

    void recordDeposited(int units) {
        deposited += Math.max(0, units);
    }

    int acquired() {
        return acquired;
    }

    int deposited() {
        return deposited;
    }

    int remaining() {
        return Math.max(0, acquired - deposited);
    }

    boolean hasAcquiredCargo() {
        return acquired > 0;
    }

    boolean fullyDeposited() {
        return acquired > 0 && deposited >= acquired;
    }
}
