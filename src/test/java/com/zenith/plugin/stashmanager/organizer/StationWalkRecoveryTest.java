package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationWalkRecoveryTest {
    private static final StationWalkRecovery.Point LANDING = new StationWalkRecovery.Point(10, 20, 10);

    private static StationWalkRecovery recovery() {
        var recovery = new StationWalkRecovery();
        var start = new StationWalkRecovery.Position(10.5, 25, 10.5);
        recovery.begin(new StationWalkRecovery.Point(30, 10, 30), start);
        recovery.setReturnRoute(List.of(LANDING, new StationWalkRecovery.Point(15, 20, 15)), start);
        return recovery;
    }

    @Test
    void adjacentLadderDoesNotCompleteLanding() {
        var recovery = recovery();
        var midClimb = new StationWalkRecovery.Position(11.5, 20.57, 10.5);
        assertFalse(recovery.atSafeReturnWaypoint(midClimb, cell -> true));
        assertFalse(recovery.atSafeReturnWaypoint(
                new StationWalkRecovery.Position(11.5, 20, 10.5), cell -> false));
        assertEquals(LANDING, recovery.returnWaypoint());
    }

    @Test
    void groundedAdjacentTopSlabCanCompleteLanding() {
        var recovery = recovery();
        var landed = new StationWalkRecovery.Position(11.5, 20, 10.5);
        assertTrue(recovery.atSafeReturnWaypoint(landed,
                cell -> cell.equals(landed.block())));
        recovery.advanceReturnRoute(landed);
        assertEquals(new StationWalkRecovery.Point(15, 20, 15), recovery.returnWaypoint());
    }

    @Test
    void carpetHeightIsAllowedButBottomSlabHeightIsNot() {
        var recovery = recovery();
        assertTrue(recovery.atSafeReturnWaypoint(
                new StationWalkRecovery.Position(10.5, 20.0625, 10.5), cell -> true));
        assertFalse(recovery.atSafeReturnWaypoint(
                new StationWalkRecovery.Position(10.5, 20.5, 10.5), cell -> true));
    }
}
