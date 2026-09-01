package com.zenith.plugin.stashmanager.organizer;

/** Keeps a delayed cache update distinct from a genuinely full destination. */
final class ContainerOpenAssessment {
    enum Result {
        WAIT_FOR_PACKET,
        WAIT_FOR_CACHE,
        READY,
        FULL
    }

    private ContainerOpenAssessment() { }

    static Result assess(boolean contentPacketReceived, boolean liveCacheReady, boolean hasRoom) {
        if (!contentPacketReceived) return Result.WAIT_FOR_PACKET;
        if (!liveCacheReady) return Result.WAIT_FOR_CACHE;
        return hasRoom ? Result.READY : Result.FULL;
    }
}
