package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockBreakAttemptGateTest {
    @Test
    void resetsOneTickBeforeSubmittingEachBreakAttempt() {
        BlockBreakAttemptGate gate = new BlockBreakAttemptGate();

        assertEquals(BlockBreakAttemptGate.Step.RESET, gate.next(false));
        assertEquals(BlockBreakAttemptGate.Step.SUBMIT, gate.next(false));
        assertEquals(BlockBreakAttemptGate.Step.WAIT, gate.next(true));

        gate.clear();
        assertEquals(BlockBreakAttemptGate.Step.RESET, gate.next(false));
    }
}
