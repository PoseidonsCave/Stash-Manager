package com.zenith.plugin.stashmanager.orchestration;

/** Pure timing policy shared by headless container-opening state machines. */
public final class OpenRetryCadence {
    private OpenRetryCadence() {}

    public static boolean shouldInteract(int elapsedTicks, int timeoutTicks, int retryIntervalTicks) {
        if (elapsedTicks < 1 || elapsedTicks >= timeoutTicks || retryIntervalTicks < 1) {
            return false;
        }
        return elapsedTicks == 1 || elapsedTicks % retryIntervalTicks == 0;
    }
}
