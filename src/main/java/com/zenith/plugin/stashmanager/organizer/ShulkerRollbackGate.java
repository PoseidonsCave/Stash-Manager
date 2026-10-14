package com.zenith.plugin.stashmanager.organizer;

/** Wait for a restored block to persist before mining the worksite again. */
final class ShulkerRollbackGate {
    static final int CONFIRM_TICKS = 40;

    enum Observation { ABSENT, PENDING, CONFIRMED }

    private int presentTicks;

    Observation observe(boolean blockPresent) {
        if (!blockPresent) {
            presentTicks = 0;
            return Observation.ABSENT;
        }
        if (presentTicks < CONFIRM_TICKS) presentTicks++;
        return presentTicks >= CONFIRM_TICKS
                ? Observation.CONFIRMED : Observation.PENDING;
    }

    void reset() {
        presentTicks = 0;
    }
}
