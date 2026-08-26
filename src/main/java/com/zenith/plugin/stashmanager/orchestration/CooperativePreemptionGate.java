package com.zenith.plugin.stashmanager.orchestration;

/**
 * Tick-based minimum hold and quiet-window gate for cooperative automation handoffs.
 * The hold starts when control is yielded. Time spent by the interrupting task counts
 * toward the hold, while the quiet window only accumulates after shared resources are idle.
 */
public final class CooperativePreemptionGate {
    private static final long NANOS_PER_TICK = 50_000_000L;

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
    private boolean clockSuspended;
    private long clockSuspendedAtNanos;

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
        clockSuspended = false;
        clockSuspendedAtNanos = 0;
        return Transition.YIELDED;
    }

    public Transition tick(boolean sharedResourcesBusy) {
        if (!yielded || clockSuspended) return Transition.NONE;

        addElapsedTicks(1);
        quietTicks = sharedResourcesBusy ? 0 : quietTicks + 1;
        if (elapsedTicks >= minimumHoldTicks && quietTicks >= quietTicksRequired) {
            yielded = false;
            return Transition.RESUMED;
        }
        return Transition.NONE;
    }

    /** Pause tick accounting while direct player control stops bot ticks. */
    public void suspendClock(long nowNanos) {
        if (!yielded || clockSuspended) return;
        clockSuspended = true;
        clockSuspendedAtNanos = nowNanos;
        quietTicks = 0;
    }

    /** Count suspended wall time toward the hold without treating it as quiet time. */
    public int resumeClock(long nowNanos) {
        if (!yielded || !clockSuspended) return 0;

        long suspendedNanos = Math.max(0L, nowNanos - clockSuspendedAtNanos);
        long suspendedTicks = suspendedNanos / NANOS_PER_TICK;
        int accountedTicks = (int) Math.min(Integer.MAX_VALUE, suspendedTicks);
        addElapsedTicks(accountedTicks);
        quietTicks = 0;
        clockSuspended = false;
        clockSuspendedAtNanos = 0;
        return accountedTicks;
    }

    private void addElapsedTicks(int ticks) {
        if (ticks <= 0) return;
        elapsedTicks = ticks > Integer.MAX_VALUE - elapsedTicks
            ? Integer.MAX_VALUE
            : elapsedTicks + ticks;
    }

    public void reset() {
        yielded = false;
        elapsedTicks = 0;
        quietTicks = 0;
        clockSuspended = false;
        clockSuspendedAtNanos = 0;
    }

    public boolean isYielded() {
        return yielded;
    }

    public int elapsedTicks() {
        return elapsedTicks;
    }

    public boolean isClockSuspended() {
        return clockSuspended;
    }

    public int remainingHoldTicks() {
        if (!yielded) return 0;
        return Math.max(0, minimumHoldTicks - elapsedTicks);
    }
}
