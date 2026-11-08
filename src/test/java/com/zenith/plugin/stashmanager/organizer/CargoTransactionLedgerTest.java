package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CargoTransactionLedgerTest {
    @Test
    void requiresEveryAcquiredUnitToBeConfirmedAtADestination() {
        CargoTransactionLedger ledger = new CargoTransactionLedger();
        ledger.reset(64);
        ledger.recordDeposited(32);

        assertEquals(32, ledger.remaining());
        assertFalse(ledger.fullyDeposited());

        ledger.recordDeposited(32);
        assertEquals(0, ledger.remaining());
        assertTrue(ledger.fullyDeposited());
    }

    @Test
    void accumulatesPartialSourceTransfers() {
        CargoTransactionLedger ledger = new CargoTransactionLedger();
        ledger.reset(0);
        ledger.recordAcquired(16);
        ledger.recordAcquired(48);

        assertEquals(64, ledger.acquired());
        assertTrue(ledger.hasAcquiredCargo());
    }

    @Test
    void resetSeparatesConsecutiveTasks() {
        CargoTransactionLedger ledger = new CargoTransactionLedger();
        ledger.reset(27);
        ledger.recordDeposited(27);
        ledger.reset(1);

        assertEquals(1, ledger.acquired());
        assertEquals(0, ledger.deposited());
        assertEquals(1, ledger.remaining());
    }

    @Test
    void restoresAnInterruptedPartialHandoff() {
        CargoTransactionLedger ledger = new CargoTransactionLedger();
        ledger.restore(40, 16);

        assertEquals(40, ledger.acquired());
        assertEquals(16, ledger.deposited());
        assertEquals(24, ledger.remaining());
    }
}
