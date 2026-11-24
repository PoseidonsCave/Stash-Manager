package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FoodContingencyCloseGateTest {
    @Test
    void openRetrieverWindowMustCloseAndStayQuietBeforeParentResume() {
        var gate = new FoodContingencyCloseGate();
        assertEquals(FoodContingencyCloseGate.Action.REQUEST_CLOSE,
                gate.tick(true, false));
        assertEquals(FoodContingencyCloseGate.Action.WAIT,
                gate.tick(true, true));
        for (int tick = 1; tick < FoodContingencyCloseGate.QUIET_TICKS_REQUIRED; tick++) {
            assertEquals(FoodContingencyCloseGate.Action.WAIT,
                    gate.tick(false, false));
        }
        assertEquals(FoodContingencyCloseGate.Action.READY,
                gate.tick(false, false));
    }

    @Test
    void closeRequestsAreRateLimitedWhileTheWindowStaysOpen() {
        var gate = new FoodContingencyCloseGate();
        assertEquals(FoodContingencyCloseGate.Action.REQUEST_CLOSE,
                gate.tick(true, false));
        for (int tick = 1; tick < FoodContingencyCloseGate.CLOSE_RETRY_TICKS; tick++) {
            assertEquals(FoodContingencyCloseGate.Action.WAIT,
                    gate.tick(true, false));
        }
        assertEquals(FoodContingencyCloseGate.Action.REQUEST_CLOSE,
                gate.tick(true, false));
        assertEquals(2, gate.closeAttempts());
    }

    @Test
    void permanentlyOwnedWindowBlocksInsteadOfReleasingParentAutomation() {
        var gate = new FoodContingencyCloseGate();
        for (int tick = 1; tick < FoodContingencyCloseGate.TIMEOUT_TICKS; tick++) {
            gate.tick(true, true);
        }
        assertEquals(FoodContingencyCloseGate.Action.TIMEOUT,
                gate.tick(true, true));
    }
}
