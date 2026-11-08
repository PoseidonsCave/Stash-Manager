package com.zenith.plugin.stashmanager.orchestration;

/** Give a new path request time to start before counting it as a failed walk. */
public final class ScanPathRetryCadence {
    private ScanPathRetryCadence() { }

    public static boolean shouldHandleInactive(boolean goalActive, int walkTick,
                                               int requestTick, int settleTicks) {
        return !goalActive && settleTicks > 0 && walkTick - requestTick >= settleTicks;
    }
}
