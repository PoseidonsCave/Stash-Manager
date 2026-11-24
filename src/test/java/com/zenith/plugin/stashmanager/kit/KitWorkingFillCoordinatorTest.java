package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class KitWorkingFillCoordinatorTest {
    @TempDir Path temporary;

    private static KitWorkingTransferJournal.Ticket ticket(int n) {
        return new KitWorkingTransferJournal.Ticket(1, "redstone", "redstone", 64,
                new int[]{n, 64, 1}, 2, new int[]{10, 64, 2}, n,
                Integer.toHexString(n).repeat(64),
                KitWorkingTransferJournal.Phase.PREPARED, 1, null);
    }

    private static final class Port implements KitWorkingTransferExecutor.Port {
        final AtomicInteger pickups;
        final AtomicInteger deposits;
        Port(AtomicInteger pickups, AtomicInteger deposits) {
            this.pickups = pickups; this.deposits = deposits;
        }
        @Override public KitWorkingTransferExecutor.Check verifySource(
                KitWorkingTransferJournal.Ticket ticket) { return KitWorkingTransferExecutor.Check.MATCH; }
        @Override public void submitPickup(KitWorkingTransferJournal.Ticket ticket) { pickups.incrementAndGet(); }
        @Override public KitWorkingTransferExecutor.Check verifyCargo(
                KitWorkingTransferJournal.Ticket ticket) { return KitWorkingTransferExecutor.Check.MATCH; }
        @Override public KitWorkingTransferExecutor.Check verifyDestination(
                KitWorkingTransferJournal.Ticket ticket) { return KitWorkingTransferExecutor.Check.MATCH; }
        @Override public void submitDeposit(KitWorkingTransferJournal.Ticket ticket) { deposits.incrementAndGet(); }
        @Override public KitWorkingTransferExecutor.Check verifyDeposit(
                KitWorkingTransferJournal.Ticket ticket) { return KitWorkingTransferExecutor.Check.MATCH; }
        @Override public KitWorkingTransferExecutor.Check verifyPersistedDeposit(
                KitWorkingTransferJournal.Ticket ticket) { return KitWorkingTransferExecutor.Check.MATCH; }
    }

    @Test
    void activatesEachSlotOnlyAfterCompleteAndAdvancesSerially() throws Exception {
        var fill = new KitWorkingFillJournal(temporary.resolve("fill.json"));
        var transfer = new KitWorkingTransferJournal(temporary.resolve("transfer.json"));
        var pickups = new AtomicInteger();
        var deposits = new AtomicInteger();
        var activated = new AtomicInteger();
        var coordinator = new KitWorkingFillCoordinator(fill, transfer,
                () -> new Port(pickups, deposits), next -> true, completed -> {
                    assertEquals(KitWorkingTransferJournal.Phase.COMPLETE, completed.phase());
                    activated.incrementAndGet();
                    return true;
                });
        coordinator.start(List.of(ticket(1), ticket(2)));
        for (int i = 0; i < 30 && fill.load().orElseThrow().status()
                != KitWorkingFillJournal.Status.COMPLETE; i++) coordinator.tick();
        assertEquals(KitWorkingFillJournal.Status.COMPLETE, fill.load().orElseThrow().status());
        assertEquals(2, fill.load().orElseThrow().nextIndex());
        assertEquals(2, pickups.get());
        assertEquals(2, deposits.get());
        assertEquals(2, activated.get());
    }

    @Test
    void restartAfterPickupRequiresReviewAndNeverReplaysClick() throws Exception {
        var fill = new KitWorkingFillJournal(temporary.resolve("fill.json"));
        var transfer = new KitWorkingTransferJournal(temporary.resolve("transfer.json"));
        var pickups = new AtomicInteger();
        var deposits = new AtomicInteger();
        var ports = (java.util.function.Supplier<KitWorkingTransferExecutor.Port>)
                () -> new Port(pickups, deposits);
        var first = new KitWorkingFillCoordinator(fill, transfer, ports,
                next -> true, completed -> true);
        first.start(List.of(ticket(1)));
        first.tick(); // persist first transfer ticket
        first.tick(); // source verified
        first.tick(); // pickup submitted
        assertEquals(1, pickups.get());

        var restored = new KitWorkingFillCoordinator(fill, transfer, ports,
                next -> true, completed -> true);
        assertEquals(KitWorkingFillJournal.Status.REVIEW_REQUIRED, restored.tick().status());
        assertEquals("restart_requires_live_transaction_audit",
                fill.load().orElseThrow().blocker());
        restored.tick();
        assertEquals(1, pickups.get());
        assertEquals(0, deposits.get());
    }

    @Test
    void rejectedActivationStopsBeforeNextBox() throws Exception {
        var fill = new KitWorkingFillJournal(temporary.resolve("fill.json"));
        var transfer = new KitWorkingTransferJournal(temporary.resolve("transfer.json"));
        var pickups = new AtomicInteger();
        var deposits = new AtomicInteger();
        var coordinator = new KitWorkingFillCoordinator(fill, transfer,
                () -> new Port(pickups, deposits), next -> true, completed -> false);
        coordinator.start(List.of(ticket(1), ticket(2)));
        for (int i = 0; i < 20 && fill.load().orElseThrow().status()
                == KitWorkingFillJournal.Status.RUNNING; i++) coordinator.tick();
        assertEquals(KitWorkingFillJournal.Status.REVIEW_REQUIRED,
                fill.load().orElseThrow().status());
        assertEquals(1, pickups.get());
        assertEquals(1, deposits.get());
    }

    @Test
    void failedAdmissionCannotOpenOrClickFirstSource() throws Exception {
        var fill = new KitWorkingFillJournal(temporary.resolve("fill.json"));
        var transfer = new KitWorkingTransferJournal(temporary.resolve("transfer.json"));
        var pickups = new AtomicInteger();
        var deposits = new AtomicInteger();
        var coordinator = new KitWorkingFillCoordinator(fill, transfer,
                () -> new Port(pickups, deposits), next -> false, completed -> true);
        coordinator.start(List.of(ticket(1)));
        assertEquals(KitWorkingFillJournal.Status.REVIEW_REQUIRED, coordinator.tick().status());
        assertTrue(transfer.load().isEmpty());
        assertEquals(0, pickups.get());
        assertEquals(0, deposits.get());
    }

    @Test
    void completedFillCanStartAnotherManifestWithoutReplayingOldClick() throws Exception {
        var fill = new KitWorkingFillJournal(temporary.resolve("fill.json"));
        var transfer = new KitWorkingTransferJournal(temporary.resolve("transfer.json"));
        var pickups = new AtomicInteger();
        var deposits = new AtomicInteger();
        var coordinator = new KitWorkingFillCoordinator(fill, transfer,
                () -> new Port(pickups, deposits), next -> true, completed -> true);
        coordinator.start(List.of(ticket(1)));
        for (int i = 0; i < 20 && fill.load().orElseThrow().status()
                != KitWorkingFillJournal.Status.COMPLETE; i++) coordinator.tick();
        assertEquals(1, pickups.get());
        assertEquals(1, deposits.get());
        coordinator.start(List.of(ticket(2)));
        for (int i = 0; i < 20 && fill.load().orElseThrow().status()
                != KitWorkingFillJournal.Status.COMPLETE; i++) coordinator.tick();
        assertEquals(2, pickups.get());
        assertEquals(2, deposits.get());
    }

    @Test
    void reviewedFillWithoutCurrentTicketCanResumeBeforeAnyClick() throws Exception {
        var fill = new KitWorkingFillJournal(temporary.resolve("fill.json"));
        var transfer = new KitWorkingTransferJournal(temporary.resolve("transfer.json"));
        var pickups = new AtomicInteger();
        var deposits = new AtomicInteger();
        var coordinator = new KitWorkingFillCoordinator(fill, transfer,
                () -> new Port(pickups, deposits), next -> true, completed -> true);
        coordinator.start(List.of(ticket(1)));
        coordinator.forceReview("bot_ticks_stopped");
        assertTrue(transfer.load().isEmpty());
        assertTrue(coordinator.resumeBeforeNextTicket());
        for (int i = 0; i < 15 && fill.load().orElseThrow().status()
                != KitWorkingFillJournal.Status.COMPLETE; i++) coordinator.tick();
        assertEquals(1, pickups.get());
        assertEquals(1, deposits.get());
    }
}
