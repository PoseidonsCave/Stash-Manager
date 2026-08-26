package com.zenith.plugin.stashmanager.orchestration;

/** Quarter-progress cadence for long-running jobs. Completion has its own event. */
public final class ProgressMilestones {
    public static final int FIRST = 25;
    public static final int COMPLETE = 100;
    private static final int STEP = 25;

    private ProgressMilestones() {}

    public record Crossing(boolean crossed, int milestonePercent, int nextMilestonePercent) {}

    public static Crossing afterProgress(int completed, int total, int nextMilestonePercent) {
        int next = normalize(nextMilestonePercent);
        if (total <= 0 || completed <= 0 || next >= COMPLETE) {
            return new Crossing(false, 0, next);
        }

        int percent = (int) Math.min(COMPLETE,
                ((long) completed * COMPLETE) / total);
        if (percent < next) return new Crossing(false, 0, next);

        int highestCrossed = next;
        while (highestCrossed + STEP <= percent && highestCrossed + STEP < COMPLETE) {
            highestCrossed += STEP;
        }
        return new Crossing(true, highestCrossed, highestCrossed + STEP);
    }

    public static int nextAfterCompleted(int completed, int total) {
        if (total <= 0 || completed <= 0) return FIRST;
        int percent = (int) Math.min(COMPLETE,
                ((long) completed * COMPLETE) / total);
        int next = FIRST;
        while (next <= percent && next < COMPLETE) next += STEP;
        return next;
    }

    private static int normalize(int nextMilestonePercent) {
        if (nextMilestonePercent <= FIRST) return FIRST;
        if (nextMilestonePercent >= COMPLETE) return COMPLETE;
        return ((nextMilestonePercent + STEP - 1) / STEP) * STEP;
    }
}
