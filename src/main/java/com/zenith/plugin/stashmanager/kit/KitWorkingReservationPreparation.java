package com.zenith.plugin.stashmanager.kit;

import java.util.Objects;

/** In-memory live empty-chest audit. No reservation is written until its GUI closes. */
public final class KitWorkingReservationPreparation {
    public interface Probe {
        KitWorkingMinecraftPort.ProbeResult inspect(int[] position);
        KitWorkingTransferExecutor.Check close();
        void shutdown();
        void onContainerData(int containerId);
    }

    public record Result(boolean verified, String blocker, long verifiedAtEpochMilli) { }

    private final int[] position;
    private final Probe probe;
    private boolean witnessedEmpty;
    private boolean finished;
    private String blocker;
    private long verifiedAt;

    public KitWorkingReservationPreparation(int[] position, Probe probe) {
        if (position == null || position.length != 3)
            throw new IllegalArgumentException("canonical double-chest position required");
        this.position = position.clone();
        this.probe = Objects.requireNonNull(probe);
    }

    public void onContainerData(int containerId) { probe.onContainerData(containerId); }

    public Result tick() {
        if (finished) return result();
        if (!witnessedEmpty && blocker == null) {
            var observed = probe.inspect(position.clone());
            if (observed.check() == KitWorkingTransferExecutor.Check.MISMATCH)
                blocker = observed.blocker() == null ? "empty_chest_probe_mismatch"
                        : observed.blocker();
            else if (observed.check() == KitWorkingTransferExecutor.Check.MATCH) {
                witnessedEmpty = true;
                verifiedAt = System.currentTimeMillis();
            }
        }
        if (witnessedEmpty || blocker != null) {
            var closed = probe.close();
            if (closed == KitWorkingTransferExecutor.Check.MISMATCH)
                blocker = "destination_window_close_failed";
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
        return new Result(finished && blocker == null && witnessedEmpty,
                finished ? blocker : null, finished && blocker == null ? verifiedAt : 0);
    }
}
