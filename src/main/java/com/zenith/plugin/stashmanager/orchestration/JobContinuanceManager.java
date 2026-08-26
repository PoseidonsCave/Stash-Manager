package com.zenith.plugin.stashmanager.orchestration;

/** Tracks the grace window while a human controls the proxy during a resumable stash job. */
public final class JobContinuanceManager {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    public enum Job {
        NONE,
        SCAN,
        ORGANIZE
    }

    public enum Transition {
        NONE,
        CONTROL_STARTED,
        CONTROL_RELEASED,
        ABORT_REQUIRED
    }

    public record Update(Transition transition, Job job) {
        private static final Update NONE = new Update(Transition.NONE, Job.NONE);
    }

    private volatile Job job = Job.NONE;
    private volatile long controlStartedAtNanos;
    private volatile long abortAtNanos;

    public synchronized Update beginControl(Job interruptedJob, long nowNanos, int graceSeconds) {
        if (interruptedJob == null || interruptedJob == Job.NONE) return Update.NONE;
        if (graceSeconds < 1) throw new IllegalArgumentException("graceSeconds must be positive");
        if (job != Job.NONE) return Update.NONE;

        job = interruptedJob;
        controlStartedAtNanos = nowNanos;
        long graceNanos = saturatedMultiply(graceSeconds, NANOS_PER_SECOND);
        abortAtNanos = saturatedAdd(nowNanos, graceNanos);
        return new Update(Transition.CONTROL_STARTED, job);
    }

    public synchronized Update tick(boolean controllingPlayerPresent, long nowNanos) {
        if (job == Job.NONE) return Update.NONE;
        if (!controllingPlayerPresent) {
            Job releasedJob = job;
            clear();
            return new Update(Transition.CONTROL_RELEASED, releasedJob);
        }
        if (nowNanos >= abortAtNanos) {
            return new Update(Transition.ABORT_REQUIRED, job);
        }
        return Update.NONE;
    }

    public synchronized boolean isActive() {
        return job != Job.NONE;
    }

    public synchronized Job job() {
        return job;
    }

    public synchronized int remainingSeconds(long nowNanos) {
        if (job == Job.NONE) return 0;
        long remainingNanos = Math.max(0L, abortAtNanos - nowNanos);
        long seconds = (remainingNanos + NANOS_PER_SECOND - 1L) / NANOS_PER_SECOND;
        return (int) Math.min(Integer.MAX_VALUE, seconds);
    }

    public synchronized int elapsedSeconds(long nowNanos) {
        if (job == Job.NONE) return 0;
        long elapsedNanos = Math.max(0L, nowNanos - controlStartedAtNanos);
        return (int) Math.min(Integer.MAX_VALUE, elapsedNanos / NANOS_PER_SECOND);
    }

    public synchronized void clear() {
        job = Job.NONE;
        controlStartedAtNanos = 0L;
        abortAtNanos = 0L;
    }

    private static long saturatedMultiply(long left, long right) {
        if (left == 0L || right == 0L) return 0L;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
