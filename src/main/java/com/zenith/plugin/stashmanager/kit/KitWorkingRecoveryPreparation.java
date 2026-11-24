package com.zenith.plugin.stashmanager.kit;

import java.util.Objects;

/** Read-only audit of both ends of a reviewed one-box transfer. */
public final class KitWorkingRecoveryPreparation {
    public interface Port extends KitWorkingTransferExecutor.Port {
        KitWorkingMinecraftPort.TransferObservation observe(
                KitWorkingTransferJournal.Ticket ticket, boolean destination);
        KitWorkingTransferExecutor.Check closeAndReset();
        boolean primeCargo(KitWorkingTransferJournal.Ticket ticket);
        void shutdown();
    }

    public record Result(boolean ready, KitWorkingTransferJournal.Phase next,
                         String blocker) { }

    private enum Stage { SOURCE, DESTINATION, REOPEN_CLOSE, REOPEN_OBSERVE, CLOSE, DONE }
    private final KitWorkingTransferJournal.Ticket ticket;
    private final Port port;
    private Stage stage = Stage.SOURCE;
    private boolean sourceExact;
    private int sourceCarried;
    private KitWorkingTransferJournal.Phase next;
    private String blocker;

    public KitWorkingRecoveryPreparation(KitWorkingTransferJournal.Ticket ticket, Port port) {
        this.ticket = Objects.requireNonNull(ticket);
        this.port = Objects.requireNonNull(port);
    }

    public void onContainerData(int containerId) { port.onContainerData(containerId); }
    public KitWorkingTransferJournal.Ticket ticket() { return ticket; }

    public Result tick() {
        if (stage == Stage.DONE) return result();
        if (stage == Stage.SOURCE) {
            var observed = port.observe(ticket, false);
            if (observed.check() == KitWorkingTransferExecutor.Check.WAIT) return result();
            if (observed.check() == KitWorkingTransferExecutor.Check.MISMATCH) {
                fail(observed.blocker()); return result();
            }
            sourceExact = observed.exactAtSlot();
            sourceCarried = observed.playerMatches();
            stage = Stage.DESTINATION;
        }
        if (stage == Stage.DESTINATION) {
            var observed = port.observe(ticket, true);
            if (observed.check() == KitWorkingTransferExecutor.Check.WAIT) return result();
            if (observed.check() == KitWorkingTransferExecutor.Check.MISMATCH
                    || observed.playerMatches() != sourceCarried) {
                fail(observed.blocker() == null ? "recovery_cargo_changed_between_windows"
                        : observed.blocker());
                return result();
            }
            var previous = ticket.phase() == KitWorkingTransferJournal.Phase.REVIEW_REQUIRED
                    ? ticket.reviewFromPhase() : ticket.phase();
            if (previous == null) {
                fail("legacy_review_phase_unknown"); return result();
            }
            if (sourceExact && !observed.exactAtSlot() && sourceCarried == 0
                    && (previous == KitWorkingTransferJournal.Phase.PREPARED
                        || previous == KitWorkingTransferJournal.Phase.SOURCE_VERIFIED)) {
                next = KitWorkingTransferJournal.Phase.PREPARED;
                stage = Stage.CLOSE;
            } else if (!sourceExact && !observed.exactAtSlot() && sourceCarried == 1
                    && (previous == KitWorkingTransferJournal.Phase.PICKUP_SUBMITTED
                        || previous == KitWorkingTransferJournal.Phase.CARGO_VERIFIED
                        || previous == KitWorkingTransferJournal.Phase.DESTINATION_VERIFIED)) {
                if (!port.primeCargo(ticket)) {
                    fail("recovery_carried_box_changed"); return result();
                }
                next = KitWorkingTransferJournal.Phase.CARGO_VERIFIED;
                stage = Stage.CLOSE;
            } else if (!sourceExact && observed.exactAtSlot() && sourceCarried == 0
                    && (previous == KitWorkingTransferJournal.Phase.DEPOSIT_SUBMITTED
                        || previous == KitWorkingTransferJournal.Phase.DEPOSIT_VERIFIED
                        || previous == KitWorkingTransferJournal.Phase.COMPLETE)) {
                stage = Stage.REOPEN_CLOSE;
            } else {
                fail("recovery_inventory_disposition_ambiguous"); return result();
            }
        }
        if (stage == Stage.REOPEN_CLOSE) {
            if (port.closeAndReset() == KitWorkingTransferExecutor.Check.WAIT) return result();
            stage = Stage.REOPEN_OBSERVE;
        }
        if (stage == Stage.REOPEN_OBSERVE) {
            var reopened = port.observe(ticket, true);
            if (reopened.check() == KitWorkingTransferExecutor.Check.WAIT) return result();
            if (reopened.check() != KitWorkingTransferExecutor.Check.MATCH
                    || !reopened.exactAtSlot() || reopened.playerMatches() != 0) {
                fail("recovery_deposit_not_persistent"); return result();
            }
            next = KitWorkingTransferJournal.Phase.COMPLETE;
            stage = Stage.CLOSE;
        }
        if (stage == Stage.CLOSE) {
            if (port.closeAndReset() == KitWorkingTransferExecutor.Check.WAIT) return result();
            stage = Stage.DONE;
        }
        return result();
    }

    public Port takePort() {
        if (stage != Stage.DONE || blocker != null || next == null)
            throw new IllegalStateException("recovery audit is not complete");
        return port;
    }

    public void interrupt(String reason) { fail(reason); }

    private void fail(String reason) {
        blocker = reason == null ? "recovery_live_mismatch" : reason;
        port.shutdown();
        stage = Stage.DONE;
    }

    private Result result() {
        return new Result(stage == Stage.DONE && blocker == null,
                stage == Stage.DONE ? next : null, stage == Stage.DONE ? blocker : null);
    }
}
