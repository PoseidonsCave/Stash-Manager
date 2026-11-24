package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class KitWorkingRecoveryPreparationTest {
    @TempDir Path temporary;
    private static KitWorkingTransferJournal.Ticket ticket(KitWorkingTransferJournal.Phase phase) {
        return new KitWorkingTransferJournal.Ticket(1, "redstone", "redstone", 64,
                new int[]{1, 64, 1}, 2, new int[]{10, 64, 2}, 5,
                "a".repeat(64), phase, 1, null);
    }

    @Test void preClickAuditReturnsToPrepared() {
        var port = new FakePort(true, false, 0);
        var audit = new KitWorkingRecoveryPreparation(ticket(
                KitWorkingTransferJournal.Phase.SOURCE_VERIFIED), port);
        assertTrue(audit.tick().ready());
        assertEquals(KitWorkingTransferJournal.Phase.PREPARED, audit.tick().next());
        assertEquals(0, port.clicks);
    }

    @Test void submittedPickupIsNeverReplayedWhenBoxRemainsAtSource() {
        var port = new FakePort(true, false, 0);
        var audit = new KitWorkingRecoveryPreparation(ticket(
                KitWorkingTransferJournal.Phase.PICKUP_SUBMITTED), port);
        assertEquals("recovery_inventory_disposition_ambiguous", audit.tick().blocker());
        assertEquals(0, port.clicks);
    }

    @Test void carriedBoxCanResumeAtDestinationAfterSourceProof() {
        var port = new FakePort(false, false, 1);
        var reviewed = ticket(KitWorkingTransferJournal.Phase.PICKUP_SUBMITTED).with(
                KitWorkingTransferJournal.Phase.REVIEW_REQUIRED, "restart", 2);
        assertEquals(KitWorkingTransferJournal.Phase.PICKUP_SUBMITTED,
                reviewed.reviewFromPhase());
        var audit = new KitWorkingRecoveryPreparation(reviewed, port);
        assertTrue(audit.tick().ready());
        assertEquals(KitWorkingTransferJournal.Phase.CARGO_VERIFIED, audit.tick().next());
        assertTrue(port.primed);
    }

    @Test void depositedBoxNeedsIndependentReopen() {
        var port = new FakePort(false, true, 0);
        var audit = new KitWorkingRecoveryPreparation(ticket(
                KitWorkingTransferJournal.Phase.DEPOSIT_SUBMITTED), port);
        assertTrue(audit.tick().ready());
        assertEquals(KitWorkingTransferJournal.Phase.COMPLETE, audit.tick().next());
        assertEquals(2, port.destinationReads);
        assertEquals(2, port.closes);
    }

    @Test void reviewOriginSurvivesDurableJournalReload() throws Exception {
        var journal = new KitWorkingTransferJournal(temporary.resolve("transfer.json"));
        journal.save(ticket(KitWorkingTransferJournal.Phase.PICKUP_SUBMITTED).with(
                KitWorkingTransferJournal.Phase.REVIEW_REQUIRED, "restart", 2));
        var restored = journal.load().orElseThrow();
        assertEquals(KitWorkingTransferJournal.Phase.PICKUP_SUBMITTED,
                restored.reviewFromPhase());
        assertEquals(KitWorkingTransferJournal.Phase.REVIEW_REQUIRED, restored.phase());
    }

    private static final class FakePort implements KitWorkingRecoveryPreparation.Port {
        final boolean sourceExact;
        final boolean destinationExact;
        final int carried;
        int destinationReads;
        int closes;
        int clicks;
        boolean primed;
        FakePort(boolean sourceExact, boolean destinationExact, int carried) {
            this.sourceExact = sourceExact;
            this.destinationExact = destinationExact;
            this.carried = carried;
        }
        @Override public KitWorkingMinecraftPort.TransferObservation observe(
                KitWorkingTransferJournal.Ticket ticket, boolean destination) {
            if (destination) destinationReads++;
            return new KitWorkingMinecraftPort.TransferObservation(
                    KitWorkingTransferExecutor.Check.MATCH,
                    destination ? destinationExact : sourceExact, carried, null);
        }
        @Override public KitWorkingTransferExecutor.Check closeAndReset() {
            closes++; return KitWorkingTransferExecutor.Check.MATCH;
        }
        @Override public boolean primeCargo(KitWorkingTransferJournal.Ticket ticket) {
            primed = true; return true;
        }
        @Override public void shutdown() { }
        @Override public KitWorkingTransferExecutor.Check verifySource(
                KitWorkingTransferJournal.Ticket ticket) { return KitWorkingTransferExecutor.Check.MATCH; }
        @Override public void submitPickup(KitWorkingTransferJournal.Ticket ticket) { clicks++; }
        @Override public KitWorkingTransferExecutor.Check verifyCargo(
                KitWorkingTransferJournal.Ticket ticket) { return KitWorkingTransferExecutor.Check.MATCH; }
        @Override public KitWorkingTransferExecutor.Check verifyDestination(
                KitWorkingTransferJournal.Ticket ticket) { return KitWorkingTransferExecutor.Check.MATCH; }
        @Override public void submitDeposit(KitWorkingTransferJournal.Ticket ticket) { clicks++; }
        @Override public KitWorkingTransferExecutor.Check verifyDeposit(
                KitWorkingTransferJournal.Ticket ticket) { return KitWorkingTransferExecutor.Check.MATCH; }
        @Override public KitWorkingTransferExecutor.Check verifyPersistedDeposit(
                KitWorkingTransferJournal.Ticket ticket) { return KitWorkingTransferExecutor.Check.MATCH; }
    }
}
