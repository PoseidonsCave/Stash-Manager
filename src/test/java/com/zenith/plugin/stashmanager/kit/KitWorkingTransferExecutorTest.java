package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static com.zenith.plugin.stashmanager.kit.KitWorkingTransferJournal.Phase;

class KitWorkingTransferExecutorTest {
    @TempDir Path temporary;

    private static KitWorkingTransferJournal.Ticket ticket() {
        return new KitWorkingTransferJournal.Ticket(1, "redstone", "redstone", 640,
                new int[]{1, 64, 1}, 7, new int[]{2, 64, 2}, 4,
                "f".repeat(64), Phase.PREPARED, 1, null);
    }

    private static final class Port implements KitWorkingTransferExecutor.Port {
        int pickups;
        int deposits;
        KitWorkingTransferJournal journal;
        KitWorkingTransferExecutor.Check source = KitWorkingTransferExecutor.Check.MATCH;
        KitWorkingTransferExecutor.Check destination = KitWorkingTransferExecutor.Check.MATCH;
        boolean throwAfterPickup;
        @Override public KitWorkingTransferExecutor.Check verifySource(
                KitWorkingTransferJournal.Ticket ticket) { return source; }
        @Override public void submitPickup(KitWorkingTransferJournal.Ticket ticket) {
            if (journal != null) {
                try { assertEquals(Phase.PICKUP_SUBMITTED, journal.load().orElseThrow().phase()); }
                catch (IOException e) { throw new AssertionError(e); }
            }
            pickups++;
            if (throwAfterPickup) throw new IllegalStateException("request result uncertain");
        }
        @Override public KitWorkingTransferExecutor.Check verifyCargo(
                KitWorkingTransferJournal.Ticket ticket) {
            return KitWorkingTransferExecutor.Check.MATCH;
        }
        @Override public KitWorkingTransferExecutor.Check verifyDestination(
                KitWorkingTransferJournal.Ticket ticket) {
            return destination;
        }
        @Override public void submitDeposit(KitWorkingTransferJournal.Ticket ticket) {
            if (journal != null) {
                try { assertEquals(Phase.DEPOSIT_SUBMITTED, journal.load().orElseThrow().phase()); }
                catch (IOException e) { throw new AssertionError(e); }
            }
            deposits++;
        }
        @Override public KitWorkingTransferExecutor.Check verifyDeposit(
                KitWorkingTransferJournal.Ticket ticket) {
            return KitWorkingTransferExecutor.Check.MATCH;
        }
        @Override public KitWorkingTransferExecutor.Check verifyPersistedDeposit(
                KitWorkingTransferJournal.Ticket ticket) {
            return KitWorkingTransferExecutor.Check.MATCH;
        }
    }

    @Test
    void journalsBeforeEachClickAndCompletesOnlyAfterTwoDepositChecks() throws Exception {
        var journal = new KitWorkingTransferJournal(temporary.resolve("transfer.json"));
        var port = new Port();
        port.journal = journal;
        var executor = new KitWorkingTransferExecutor(journal, port);
        executor.begin(ticket());
        assertEquals(Phase.SOURCE_VERIFIED, executor.tick().phase());
        assertEquals(Phase.PICKUP_SUBMITTED, executor.tick().phase());
        assertEquals(Phase.PICKUP_SUBMITTED, journal.load().orElseThrow().phase());
        assertEquals(1, port.pickups);
        assertEquals(Phase.CARGO_VERIFIED, executor.tick().phase());
        assertEquals(Phase.DESTINATION_VERIFIED, executor.tick().phase());
        assertEquals(Phase.DEPOSIT_SUBMITTED, executor.tick().phase());
        assertEquals(Phase.DEPOSIT_SUBMITTED, journal.load().orElseThrow().phase());
        assertEquals(1, port.deposits);
        assertEquals(Phase.DEPOSIT_VERIFIED, executor.tick().phase());
        assertEquals(Phase.COMPLETE, executor.tick().phase());
        assertEquals(1, port.pickups);
        assertEquals(1, port.deposits);
    }

    @Test
    void restartAfterSubmittedClickRequiresReviewWithoutReplay() throws Exception {
        var journal = new KitWorkingTransferJournal(temporary.resolve("transfer.json"));
        var port = new Port();
        var executor = new KitWorkingTransferExecutor(journal, port);
        executor.begin(ticket());
        executor.tick();
        executor.tick();
        assertEquals(1, port.pickups);

        var restored = new KitWorkingTransferExecutor(journal, port);
        assertEquals(Phase.REVIEW_REQUIRED, restored.recoverAfterRestart().phase());
        assertEquals(Phase.REVIEW_REQUIRED, restored.tick().phase());
        assertEquals(1, port.pickups);
        assertEquals(0, port.deposits);
    }

    @Test
    void sourceMismatchBlocksBeforeAnyClick() throws Exception {
        var journal = new KitWorkingTransferJournal(temporary.resolve("transfer.json"));
        var port = new Port();
        port.source = KitWorkingTransferExecutor.Check.MISMATCH;
        var executor = new KitWorkingTransferExecutor(journal, port);
        executor.begin(ticket());
        assertEquals(Phase.REVIEW_REQUIRED, executor.tick().phase());
        assertEquals(0, port.pickups);
        assertEquals("source_identity_changed", journal.load().orElseThrow().blocker());
    }

    @Test
    void sourceChangedAfterInitialProofBlocksPickup() throws Exception {
        var journal = new KitWorkingTransferJournal(temporary.resolve("transfer.json"));
        var port = new Port();
        var executor = new KitWorkingTransferExecutor(journal, port);
        executor.begin(ticket());
        assertEquals(Phase.SOURCE_VERIFIED, executor.tick().phase());
        port.source = KitWorkingTransferExecutor.Check.MISMATCH;
        assertEquals(Phase.REVIEW_REQUIRED, executor.tick().phase());
        assertEquals("source_changed_before_pickup", journal.load().orElseThrow().blocker());
        assertEquals(0, port.pickups);
    }

    @Test
    void destinationChangedAfterInitialProofBlocksDeposit() throws Exception {
        var journal = new KitWorkingTransferJournal(temporary.resolve("transfer.json"));
        var port = new Port();
        var executor = new KitWorkingTransferExecutor(journal, port);
        executor.begin(ticket());
        for (int i = 0; i < 4; i++) executor.tick();
        assertEquals(Phase.DESTINATION_VERIFIED, journal.load().orElseThrow().phase());
        port.destination = KitWorkingTransferExecutor.Check.MISMATCH;
        assertEquals(Phase.REVIEW_REQUIRED, executor.tick().phase());
        assertEquals("destination_changed_before_deposit", journal.load().orElseThrow().blocker());
        assertEquals(0, port.deposits);
    }

    @Test
    void uncertainPickupSubmissionIsJournaledForReviewWithoutRetry() throws Exception {
        var journal = new KitWorkingTransferJournal(temporary.resolve("transfer.json"));
        var port = new Port();
        port.throwAfterPickup = true;
        var executor = new KitWorkingTransferExecutor(journal, port);
        executor.begin(ticket());
        executor.tick();
        assertEquals(Phase.REVIEW_REQUIRED, executor.tick().phase());
        assertEquals("live_port_exception_source_verified",
                journal.load().orElseThrow().blocker());
        executor.tick();
        assertEquals(1, port.pickups);
    }

    @Test
    void existingTicketCannotBeOverwritten() throws IOException {
        var journal = new KitWorkingTransferJournal(temporary.resolve("transfer.json"));
        var executor = new KitWorkingTransferExecutor(journal, new Port());
        executor.begin(ticket());
        assertThrows(IOException.class, () -> executor.begin(ticket()));
    }
}
