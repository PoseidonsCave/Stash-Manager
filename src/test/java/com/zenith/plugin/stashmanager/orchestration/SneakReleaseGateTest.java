package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SneakReleaseGateTest {
    @Test
    void requiresACompleteStandingTick() {
        var gate = new SneakReleaseGate();
        assertFalse(gate.tick(true));
        assertFalse(gate.tick(false));
        assertTrue(gate.tick(false));
    }

    @Test
    void sneakingAgainResetsTheBarrier() {
        var gate = new SneakReleaseGate();
        assertFalse(gate.tick(false));
        assertFalse(gate.tick(true));
        assertFalse(gate.tick(false));
        assertTrue(gate.tick(false));
    }

    @Test
    void explicitResetRequiresAnotherStandingTick() {
        var gate = new SneakReleaseGate();
        assertFalse(gate.tick(false));
        assertTrue(gate.tick(false));
        gate.reset();
        assertFalse(gate.tick(false));
        assertTrue(gate.tick(false));
    }
}
