package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShulkerRollbackGateTest {
    @Test
    void transientCacheRestorationDoesNotTriggerAnotherBreak() {
        ShulkerRollbackGate gate = new ShulkerRollbackGate();
        for (int tick = 0; tick < ShulkerRollbackGate.CONFIRM_TICKS - 1; tick++) {
            assertEquals(ShulkerRollbackGate.Observation.PENDING, gate.observe(true));
        }
        assertEquals(ShulkerRollbackGate.Observation.ABSENT, gate.observe(false));
        assertEquals(ShulkerRollbackGate.Observation.PENDING, gate.observe(true));
    }

    @Test
    void persistentRestorationCanBeRebroken() {
        ShulkerRollbackGate gate = new ShulkerRollbackGate();
        for (int tick = 0; tick < ShulkerRollbackGate.CONFIRM_TICKS - 1; tick++) {
            assertEquals(ShulkerRollbackGate.Observation.PENDING, gate.observe(true));
        }
        assertEquals(ShulkerRollbackGate.Observation.CONFIRMED, gate.observe(true));
        assertEquals(ShulkerRollbackGate.Observation.CONFIRMED, gate.observe(true));
        gate.reset();
        assertEquals(ShulkerRollbackGate.Observation.PENDING, gate.observe(true));
    }
}
