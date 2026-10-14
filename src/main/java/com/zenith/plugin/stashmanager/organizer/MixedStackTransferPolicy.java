package com.zenith.plugin.stashmanager.organizer;

/** Decides when a two-click mixed-shulker transfer is safe to verify. */
final class MixedStackTransferPolicy {

    enum Result {
        WAIT,
        CONFIRMED,
        RETRY,
        UNVERIFIED
    }

    private MixedStackTransferPolicy() {}

    static Result assess(
            boolean requestCompleted,
            boolean requestAccepted,
            boolean sourceOccupied,
            boolean destinationOccupied,
            boolean cursorOccupied,
            int verificationTicks,
            int verificationTimeoutTicks) {
        if (!requestCompleted) return Result.WAIT;
        if (destinationOccupied && !cursorOccupied) return Result.CONFIRMED;
        if (cursorOccupied) {
            return verificationTicks < Math.max(1, verificationTimeoutTicks)
                    ? Result.WAIT
                    : Result.UNVERIFIED;
        }
        if (!requestAccepted || sourceOccupied) return Result.RETRY;
        if (verificationTicks < Math.max(1, verificationTimeoutTicks)) return Result.WAIT;
        return Result.UNVERIFIED;
    }
}
