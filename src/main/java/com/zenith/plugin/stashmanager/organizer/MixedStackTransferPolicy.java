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
            boolean destinationOccupied) {
        if (!requestCompleted) return Result.WAIT;
        if (destinationOccupied) return Result.CONFIRMED;
        if (!requestAccepted || sourceOccupied) return Result.RETRY;
        return Result.UNVERIFIED;
    }
}
