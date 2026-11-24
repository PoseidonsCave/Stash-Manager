package com.zenith.plugin.stashmanager.kit;

import java.io.IOException;

import static com.zenith.plugin.stashmanager.kit.KitWorkingTransferJournal.Phase;
import static com.zenith.plugin.stashmanager.kit.KitWorkingTransferJournal.Ticket;

/** Durable one-box transaction driver. A port must supply live server-window evidence. */
public final class KitWorkingTransferExecutor {
    public enum Check { WAIT, MATCH, MISMATCH }

    public interface Port {
        default void onContainerData(int containerId) { }
        default void shutdown() { }
        /** Exact source box, its contents, and surrounding inventory still match the ticket. */
        Check verifySource(Ticket ticket);
        /** Submit exactly one source pickup click. Never retry a submitted click. */
        void submitPickup(Ticket ticket);
        /** Server source delta and independent player cargo identity agree. */
        Check verifyCargo(Ticket ticket);
        /** Live destination window is the reserved chest and target slot is empty. */
        Check verifyDestination(Ticket ticket);
        /** Submit exactly one destination deposit click. Never retry a submitted click. */
        void submitDeposit(Ticket ticket);
        /** Server destination, player inventory and cursor agree on the exact box. */
        Check verifyDeposit(Ticket ticket);
        /** Freshly reopened destination window still contains that exact box. */
        Check verifyPersistedDeposit(Ticket ticket);
    }

    private final KitWorkingTransferJournal journal;
    private final Port port;

    public KitWorkingTransferExecutor(KitWorkingTransferJournal journal, Port port) {
        this.journal = java.util.Objects.requireNonNull(journal);
        this.port = java.util.Objects.requireNonNull(port);
    }

    public void begin(Ticket ticket) throws IOException {
        if (journal.load().isPresent()) throw new IOException("existing transfer ticket requires review");
        if (ticket.phase() != Phase.PREPARED) throw new IllegalArgumentException("ticket must be prepared");
        journal.save(ticket);
    }

    /** Call only after the live player and container state have been re-established. */
    public Ticket recoverAfterRestart() throws IOException {
        Ticket ticket = journal.load().orElseThrow(() -> new IOException("no transfer ticket"));
        if (ticket.phase() == Phase.COMPLETE || ticket.phase() == Phase.REVIEW_REQUIRED) {
            return ticket;
        }
        Ticket reviewed = ticket.with(Phase.REVIEW_REQUIRED,
                "restart_requires_live_transaction_audit", System.currentTimeMillis());
        journal.save(reviewed);
        return reviewed;
    }

    public Ticket tick() throws IOException {
        Ticket ticket = journal.load().orElseThrow(() -> new IOException("no transfer ticket"));
        try {
            return step(ticket);
        } catch (RuntimeException e) {
            return advance(journal.load().orElse(ticket), Phase.REVIEW_REQUIRED,
                    "live_port_exception_" + ticket.phase().name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private Ticket step(Ticket ticket) throws IOException {
        return switch (ticket.phase()) {
            case PREPARED -> checked(ticket, port.verifySource(ticket), Phase.SOURCE_VERIFIED,
                    "source_identity_changed");
            case SOURCE_VERIFIED -> {
                Check fresh = port.verifySource(ticket);
                if (fresh != Check.MATCH) {
                    yield checked(ticket, fresh, Phase.SOURCE_VERIFIED,
                            "source_changed_before_pickup");
                }
                Ticket submitted = advance(ticket, Phase.PICKUP_SUBMITTED, null);
                port.submitPickup(submitted);
                yield submitted;
            }
            case PICKUP_SUBMITTED -> checked(ticket, port.verifyCargo(ticket),
                    Phase.CARGO_VERIFIED, "source_or_cargo_delta_mismatch");
            case CARGO_VERIFIED -> checked(ticket, port.verifyDestination(ticket),
                    Phase.DESTINATION_VERIFIED, "destination_identity_or_slot_changed");
            case DESTINATION_VERIFIED -> {
                Check fresh = port.verifyDestination(ticket);
                if (fresh != Check.MATCH) {
                    yield checked(ticket, fresh, Phase.DESTINATION_VERIFIED,
                            "destination_changed_before_deposit");
                }
                Ticket submitted = advance(ticket, Phase.DEPOSIT_SUBMITTED, null);
                port.submitDeposit(submitted);
                yield submitted;
            }
            case DEPOSIT_SUBMITTED -> checked(ticket, port.verifyDeposit(ticket),
                    Phase.DEPOSIT_VERIFIED, "destination_or_inventory_delta_mismatch");
            case DEPOSIT_VERIFIED -> checked(ticket, port.verifyPersistedDeposit(ticket),
                    Phase.COMPLETE, "destination_persistence_mismatch");
            case COMPLETE, REVIEW_REQUIRED -> ticket;
        };
    }

    private Ticket checked(Ticket ticket, Check check, Phase next, String mismatch)
            throws IOException {
        if (check == Check.WAIT) return ticket;
        if (check == Check.MATCH) return advance(ticket, next, null);
        return advance(ticket, Phase.REVIEW_REQUIRED, mismatch);
    }

    private Ticket advance(Ticket ticket, Phase next, String reason) throws IOException {
        Ticket updated = ticket.with(next, reason, System.currentTimeMillis());
        journal.save(updated);
        return updated;
    }
}
