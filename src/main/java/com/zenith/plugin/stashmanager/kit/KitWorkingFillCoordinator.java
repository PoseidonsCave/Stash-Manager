package com.zenith.plugin.stashmanager.kit;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Serial box fill. A crash never causes either submitted click to run again. */
public final class KitWorkingFillCoordinator {
    @FunctionalInterface public interface CompletionSink {
        /** Idempotently activate one destination slot after reopened-window proof. */
        boolean activate(KitWorkingTransferJournal.Ticket completed) throws Exception;
    }
    @FunctionalInterface public interface Admission {
        /** Recheck scan, chest roles, job exclusivity and the pending slot before starting a box. */
        boolean permits(KitWorkingTransferJournal.Ticket next) throws Exception;
    }

    private final KitWorkingFillJournal fillJournal;
    private final KitWorkingTransferJournal transferJournal;
    private final Supplier<? extends KitWorkingTransferExecutor.Port> ports;
    private final CompletionSink completionSink;
    private final Admission admission;
    private KitWorkingTransferExecutor.Port port;
    private KitWorkingTransferExecutor executor;
    private boolean startedInProcess;

    public KitWorkingFillCoordinator(KitWorkingFillJournal fillJournal,
                                     KitWorkingTransferJournal transferJournal,
                                     Supplier<? extends KitWorkingTransferExecutor.Port> ports,
                                     Admission admission,
                                     CompletionSink completionSink) {
        this.fillJournal = Objects.requireNonNull(fillJournal);
        this.transferJournal = Objects.requireNonNull(transferJournal);
        this.ports = Objects.requireNonNull(ports);
        this.admission = Objects.requireNonNull(admission);
        this.completionSink = Objects.requireNonNull(completionSink);
    }

    public synchronized void start(List<KitWorkingTransferJournal.Ticket> tickets)
            throws IOException {
        var previousFill = fillJournal.load().orElse(null);
        var previousTransfer = transferJournal.load().orElse(null);
        if ((previousFill != null && previousFill.status() != KitWorkingFillJournal.Status.COMPLETE)
                || (previousTransfer != null
                    && previousTransfer.phase() != KitWorkingTransferJournal.Phase.COMPLETE)
                || (previousFill == null && previousTransfer != null)
                || (previousFill != null && previousTransfer == null))
            throw new IOException("existing working fill checkpoint requires review");
        var job = new KitWorkingFillJournal.Job(1, tickets, 0,
                KitWorkingFillJournal.Status.RUNNING, null, System.currentTimeMillis());
        fillJournal.save(job);
        startedInProcess = true;
    }

    public synchronized void onContainerData(int containerId) {
        if (port != null) port.onContainerData(containerId);
    }

    public synchronized void forceReview(String reason) throws IOException {
        var job = fillJournal.load().orElse(null);
        if (job != null && job.status() == KitWorkingFillJournal.Status.RUNNING) {
            var ticket = transferJournal.load().orElse(null);
            if (ticket != null && ticket.phase() != KitWorkingTransferJournal.Phase.COMPLETE
                    && ticket.phase() != KitWorkingTransferJournal.Phase.REVIEW_REQUIRED)
                transferJournal.save(ticket.with(KitWorkingTransferJournal.Phase.REVIEW_REQUIRED,
                        reason, System.currentTimeMillis()));
            review(job, reason);
        }
    }

    /** A read-only audit must witness both physical endpoints before this transition. */
    public synchronized void resumeAudited(KitWorkingRecoveryPreparation audit)
            throws IOException {
        var result = audit.tick();
        if (!result.ready() || result.next() == null)
            throw new IOException("live working-transfer audit is incomplete");
        var job = fillJournal.load().orElseThrow(() -> new IOException("no working fill job"));
        if (job.status() != KitWorkingFillJournal.Status.REVIEW_REQUIRED)
            throw new IOException("working fill is not awaiting review");
        var ticket = transferJournal.load().orElseThrow(() ->
                new IOException("no working transfer ticket"));
        if (!KitWorkingTransferJournal.sameTransfer(ticket,
                job.transfers().get(job.nextIndex()))
                || !KitWorkingTransferJournal.sameTransfer(ticket, audit.ticket()))
            throw new IOException("transfer ticket does not match fill manifest");
        transferJournal.save(ticket.with(result.next(), null, System.currentTimeMillis()));
        shutdownPort();
        port = audit.takePort();
        executor = new KitWorkingTransferExecutor(transferJournal, port);
        startedInProcess = true;
        fillJournal.save(job.with(job.nextIndex(), KitWorkingFillJournal.Status.RUNNING, null));
    }

    /** Resume only when no ticket for the current box was ever journaled. */
    public synchronized boolean resumeBeforeNextTicket() throws IOException {
        var job = fillJournal.load().orElse(null);
        if (job == null || job.status() != KitWorkingFillJournal.Status.REVIEW_REQUIRED)
            return false;
        var saved = transferJournal.load().orElse(null);
        boolean noCurrentTicket = saved == null && job.nextIndex() == 0;
        if (saved != null && saved.phase() == KitWorkingTransferJournal.Phase.COMPLETE
                && !KitWorkingTransferJournal.sameTransfer(saved,
                    job.transfers().get(job.nextIndex()))) {
            noCurrentTicket = job.nextIndex() == 0
                    || KitWorkingTransferJournal.sameTransfer(saved,
                            job.transfers().get(job.nextIndex() - 1));
        }
        if (!noCurrentTicket) return false;
        shutdownPort();
        startedInProcess = true;
        fillJournal.save(job.with(job.nextIndex(), KitWorkingFillJournal.Status.RUNNING, null));
        return true;
    }

    public synchronized KitWorkingFillJournal.Job tick() throws IOException {
        var job = fillJournal.load().orElseThrow(() -> new IOException("no working fill job"));
        if (job.status() != KitWorkingFillJournal.Status.RUNNING) return job;
        var expected = job.transfers().get(job.nextIndex());
        var saved = transferJournal.load().orElse(null);
        if (job.nextIndex() == 0 && saved != null
                && saved.phase() == KitWorkingTransferJournal.Phase.COMPLETE
                && !KitWorkingTransferJournal.sameTransfer(saved, expected)
                && startedInProcess) {
            try { startTicket(expected, true); }
            catch (Exception e) { return review(job, "next_box_admission_rejected"); }
            return job;
        }
        if (saved == null) {
            try { startTicket(expected, false); }
            catch (Exception e) { return review(job, "next_box_admission_rejected"); }
            startedInProcess = true;
            return job;
        }
        if (job.nextIndex() > 0 && saved.phase() == KitWorkingTransferJournal.Phase.COMPLETE
                && KitWorkingTransferJournal.sameTransfer(saved,
                    job.transfers().get(job.nextIndex() - 1))) {
            try { startTicket(expected, true); }
            catch (Exception e) { return review(job, "next_box_admission_rejected"); }
            startedInProcess = true;
            return job;
        }
        if (!KitWorkingTransferJournal.sameTransfer(saved, expected))
            return review(job, "transfer_ticket_does_not_match_manifest");
        if (saved.phase() == KitWorkingTransferJournal.Phase.REVIEW_REQUIRED)
            return review(job, saved.blocker());
        if (saved.phase() == KitWorkingTransferJournal.Phase.COMPLETE) {
            try {
                if (!completionSink.activate(saved))
                    return review(job, "verified_slot_activation_rejected");
            } catch (Exception e) {
                return review(job, "verified_slot_activation_failed");
            }
            shutdownPort();
            int next = job.nextIndex() + 1;
            var advanced = job.with(next,
                    next == job.transfers().size()
                            ? KitWorkingFillJournal.Status.COMPLETE
                            : KitWorkingFillJournal.Status.RUNNING, null);
            fillJournal.save(advanced);
            return advanced;
        }
        if (!startedInProcess) {
            ensureExecutor();
            var reviewed = executor.recoverAfterRestart();
            return review(job, reviewed.blocker());
        }
        ensureExecutor();
        var result = executor.tick();
        if (result.phase() == KitWorkingTransferJournal.Phase.REVIEW_REQUIRED)
            return review(job, result.blocker());
        return job;
    }

    private void startTicket(KitWorkingTransferJournal.Ticket ticket, boolean replaceComplete)
            throws IOException {
        try {
            if (!admission.permits(ticket)) throw new IOException("working box admission rejected");
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("working box admission failed", e); }
        shutdownPort();
        port = Objects.requireNonNull(ports.get());
        executor = new KitWorkingTransferExecutor(transferJournal, port);
        if (replaceComplete) transferJournal.beginAfterComplete(ticket);
        else executor.begin(ticket);
    }

    private void ensureExecutor() {
        if (executor != null) return;
        port = Objects.requireNonNull(ports.get());
        executor = new KitWorkingTransferExecutor(transferJournal, port);
    }

    private KitWorkingFillJournal.Job review(KitWorkingFillJournal.Job job, String reason)
            throws IOException {
        shutdownPort();
        var reviewed = job.with(job.nextIndex(), KitWorkingFillJournal.Status.REVIEW_REQUIRED,
                reason == null ? "working_fill_requires_review" : reason);
        fillJournal.save(reviewed);
        return reviewed;
    }

    private void shutdownPort() {
        if (port != null) port.shutdown();
        port = null;
        executor = null;
    }
}
