package com.zenith.plugin.stashmanager.organizer;

/** Decides when an inventory action is safe to commit in the organizer state machine. */
final class InventoryTransferPolicy {
    enum Result {
        WAIT,
        CONFIRMED_DRAINED,
        CONFIRMED_PARTIAL,
        RETRY
    }

    private InventoryTransferPolicy() { }

    static Result assess(
            boolean windowAvailable,
            boolean requestCompleted,
            boolean requestAccepted,
            boolean sourceDrained,
            boolean sourceReduced,
            int verificationTicks,
            int timeoutTicks) {
        if (!windowAvailable) return Result.RETRY;
        if (sourceDrained) return Result.CONFIRMED_DRAINED;
        if (sourceReduced) return Result.CONFIRMED_PARTIAL;
        // The request future only proves that Zenith handed the packet to the client
        // connection. Do not let a future that never completes pin a container visit forever.
        if (verificationTicks >= timeoutTicks) return Result.RETRY;
        if (!requestCompleted) return Result.WAIT;
        if (!requestAccepted) return Result.RETRY;
        return Result.WAIT;
    }
}
