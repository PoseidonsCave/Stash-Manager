package com.zenith.plugin.stashmanager.kit;

/** A durable wall-clock hold with a fresh quiet window before kit work resumes. */
public final class KitResumeCadence {
    private final int quietTicksRequired;
    private int quietTicks;

    public KitResumeCadence(int quietTicksRequired) {
        if (quietTicksRequired < 1) throw new IllegalArgumentException("quietTicksRequired");
        this.quietTicksRequired = quietTicksRequired;
    }

    public boolean tick(long nowEpochMilli, long notBeforeEpochMilli, boolean quiet) {
        if (!quiet) {
            quietTicks = 0;
            return false;
        }
        if (quietTicks < quietTicksRequired) quietTicks++;
        return nowEpochMilli >= notBeforeEpochMilli && quietTicks >= quietTicksRequired;
    }

    public void reset() { quietTicks = 0; }
}
