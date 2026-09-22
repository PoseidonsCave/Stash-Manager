package com.zenith.plugin.stashmanager.orchestration;

/** Holds a parent job until a food-retrieval container is really closed. */
public final class FoodContingencyCloseGate {
    public static final int QUIET_TICKS_REQUIRED = 5;
    public static final int CLOSE_RETRY_TICKS = 20;
    public static final int TIMEOUT_TICKS = 200;

    public enum Action {
        WAIT,
        REQUEST_CLOSE,
        READY,
        TIMEOUT
    }

    private int elapsedTicks;
    private int quietTicks;
    private int lastCloseAttemptTick = -CLOSE_RETRY_TICKS;
    private int closeAttempts;

    public Action tick(boolean containerOpen, boolean inventoryBusy) {
        elapsedTicks++;
        if (!containerOpen && !inventoryBusy) {
            quietTicks++;
            return quietTicks >= QUIET_TICKS_REQUIRED ? Action.READY : Action.WAIT;
        }

        quietTicks = 0;
        if (elapsedTicks >= TIMEOUT_TICKS) return Action.TIMEOUT;
        if (containerOpen && !inventoryBusy
                && elapsedTicks - lastCloseAttemptTick >= CLOSE_RETRY_TICKS) {
            lastCloseAttemptTick = elapsedTicks;
            closeAttempts++;
            return Action.REQUEST_CLOSE;
        }
        return Action.WAIT;
    }

    public int elapsedTicks() {
        return elapsedTicks;
    }

    public int closeAttempts() {
        return closeAttempts;
    }

    public void reset() {
        elapsedTicks = 0;
        quietTicks = 0;
        lastCloseAttemptTick = -CLOSE_RETRY_TICKS;
        closeAttempts = 0;
    }
}
