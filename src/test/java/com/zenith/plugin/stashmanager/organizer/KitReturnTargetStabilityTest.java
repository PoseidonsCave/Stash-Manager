package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KitReturnTargetStabilityTest {
    @Test void postcloseReopenMustSettleIndependentlyOfPredepositWindow() {
        var gate = new KitReturnTargetStability<String>();
        var before = List.of("existing", "empty");
        assertEquals(KitReturnTargetStability.Result.SETTLING,
                gate.observe(before, 3, 60).result());
        gate.observe(before, 3, 60);
        assertEquals(KitReturnTargetStability.Result.STABLE,
                gate.observe(before, 3, 60).result());

        gate.reset();
        var incomplete = List.of("existing", "empty");
        var deposited = List.of("existing", "borrowed-box");
        assertEquals(KitReturnTargetStability.Result.SETTLING,
                gate.observe(incomplete, 3, 60).result());
        assertEquals(KitReturnTargetStability.Result.SETTLING,
                gate.observe(deposited, 3, 60).result());
        gate.observe(deposited, 3, 60);
        assertEquals(KitReturnTargetStability.Result.STABLE,
                gate.observe(deposited, 3, 60).result());
    }

    @Test void continuouslyChangingReopenNeverPasses() {
        var gate = new KitReturnTargetStability<Integer>();
        for (int tick = 1; tick < 60; tick++) {
            assertEquals(KitReturnTargetStability.Result.SETTLING,
                    gate.observe(List.of(tick), 3, 60).result());
        }
        assertEquals(KitReturnTargetStability.Result.TIMED_OUT,
                gate.observe(List.of(60), 3, 60).result());
    }
}
