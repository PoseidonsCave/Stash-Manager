package com.zenith.plugin.stashmanager.orchestration;

/**
 * Requires a complete standing tick before a normal block interaction. Zenith performs the
 * click portion of an input before it emits STOP_SNEAKING, so changing sneak=false on the click
 * itself is one tick too late from the server's perspective.
 */
public final class SneakReleaseGate {
    public static final int INPUT_PRIORITY = 9000;
    private static final int REQUIRED_STANDING_OBSERVATIONS = 2;
    private int standingObservations;

    public void reset() {
        standingObservations = 0;
    }

    public boolean tick(boolean botIsSneaking) {
        if (botIsSneaking) {
            standingObservations = 0;
            return false;
        }
        standingObservations++;
        return standingObservations >= REQUIRED_STANDING_OBSERVATIONS;
    }
}
