package com.zenith.plugin.stashmanager.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** In-memory, read-only source inspection before a durable fill job exists. */
public final class KitWorkingFillPreparation {
    public interface Probe {
        KitWorkingMinecraftPort.ProbeResult inspect(KitWorkingSourceAssignmentPlan.Assignment assignment);
        KitWorkingTransferExecutor.Check close();
        void shutdown();
        void onContainerData(int containerId);
    }

    public record Result(boolean ready, String blocker,
                         List<KitWorkingTransferJournal.Ticket> tickets) {
        public Result { tickets = List.copyOf(tickets); }
    }

    private final List<KitWorkingSourceAssignmentPlan.Assignment> assignments;
    private final Probe probe;
    private final List<KitWorkingTransferJournal.Ticket> tickets = new ArrayList<>();
    private int next;
    private String blocker;
    private boolean finished;

    public KitWorkingFillPreparation(List<KitWorkingSourceAssignmentPlan.Assignment> assignments,
                                     Probe probe) {
        if (assignments == null || assignments.isEmpty())
            throw new IllegalArgumentException("no source assignments");
        this.assignments = List.copyOf(assignments);
        this.probe = Objects.requireNonNull(probe);
    }

    public void onContainerData(int containerId) { probe.onContainerData(containerId); }

    public int inspected() { return next; }
    public int total() { return assignments.size(); }

    public Result tick() {
        if (finished) return result();
        if (blocker == null && next < assignments.size()) {
            var assignment = assignments.get(next);
            var observed = probe.inspect(assignment);
            if (observed.check() == KitWorkingTransferExecutor.Check.MISMATCH) {
                blocker = observed.blocker() == null ? "source_probe_mismatch" : observed.blocker();
            } else if (observed.check() == KitWorkingTransferExecutor.Check.MATCH) {
                String fingerprint = observed.fingerprint();
                if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")) {
                    blocker = "source_fingerprint_unavailable";
                } else {
                    var destination = assignment.destination();
                    var ticket = new KitWorkingTransferJournal.Ticket(
                            KitWorkingTransferJournal.SCHEMA_VERSION,
                            destination.kitName(), destination.itemId(), destination.plannedUnits(),
                            assignment.source(), assignment.sourceSlot(),
                            new int[]{destination.x(), destination.y(), destination.z()},
                            destination.slot(), fingerprint,
                            KitWorkingTransferJournal.Phase.PREPARED,
                            System.currentTimeMillis(), null);
                    KitWorkingTransferJournal.validate(ticket);
                    tickets.add(ticket);
                    next++;
                }
            }
        }
        if (blocker != null || next == assignments.size()) {
            var closed = probe.close();
            if (closed == KitWorkingTransferExecutor.Check.MISMATCH) {
                blocker = "source_window_close_failed";
            }
            if (closed != KitWorkingTransferExecutor.Check.WAIT) {
                probe.shutdown();
                finished = true;
            }
        }
        return result();
    }

    public void interrupt(String reason) {
        blocker = reason;
        probe.shutdown();
        finished = true;
    }

    private Result result() {
        return new Result(finished && blocker == null, finished ? blocker : null,
                finished && blocker == null ? tickets : List.of());
    }
}
