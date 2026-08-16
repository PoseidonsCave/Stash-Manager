package com.zenith.plugin.stashmanager.orchestration;

/**
 * Tick-based minimum hold and quiet-window gate for cooperative automation handoffs.
 * The hold starts when control is yielded. Time spent by the interrupting task counts
 * toward the hold, while the quiet window only accumulates after shared resources are idle.
 */
public final class CooperativePreemptionGate {
    public enum Transition {
        NONE,
        YIELDED,
        RESUMED
    }

    private final int minimumHoldTicks;
    private final int quietTicksRequired;
    private boolean yielded;
    private int elapsedTicks;
    private int quietTicks;

    public CooperativePreemptionGate(int minimumHoldTicks, int quietTicksRequired) {
        if (minimumHoldTicks < 1) throw new IllegalArgumentException("minimumHoldTicks must be positive");
        if (quietTicksRequired < 1) throw new IllegalArgumentException("quietTicksRequired must be positive");
        this.minimumHoldTicks = minimumHoldTicks;
        this.quietTicksRequired = quietTicksRequired;
    }

    public Transition yield() {
        if (yielded) return Transition.NONE;
        yielded = true;
        elapsedTicks = 0;
        quietTicks = 0;
        return Transition.YIELDED;
    }

    public Transition tick(boolean sharedResourcesBusy) {
        if (!yielded) return Transition.NONE;

        elapsedTicks++;
        quietTicks = sharedResourcesBusy ? 0 : quietTicks + 1;
        if (elapsedTicks >= minimumHoldTicks && quietTicks >= quietTicksRequired) {
            yielded = false;
            return Transition.RESUMED;
        }
        return Transition.NONE;
    }

    public void reset() {
        yielded = false;
        elapsedTicks = 0;
        quietTicks = 0;
    }

    public boolean isYielded() {
        return yielded;
    }

    public int elapsedTicks() {
        return elapsedTicks;
    }

    public int remainingHoldTicks() {
        if (!yielded) return 0;
        return Math.max(0, minimumHoldTicks - elapsedTicks);
    }
}
