package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContainerOpenReachTest {
    @Test
    void bandsUseEyeToBlockBoundsRatherThanLoggedOriginDistance() {
        assertEquals(ContainerOpenReach.Band.CLOSE,
                ContainerOpenReach.band(12.5, 64, 10.5, 10, 64, 10));
        assertEquals(ContainerOpenReach.Band.EDGE,
                ContainerOpenReach.band(14.5, 64, 10.5, 10, 64, 10));
        assertEquals(ContainerOpenReach.Band.OUT_OF_REACH,
                ContainerOpenReach.band(15.1, 64, 10.5, 10, 64, 10));
        assertEquals(ContainerOpenReach.Band.OUT_OF_REACH,
                ContainerOpenReach.band(10.5, 59, 10.5, 10, 65, 10));
    }

    @Test
    void edgeGetsOneProbeAndFarGetsNone() {
        assertEquals(3, ContainerOpenReach.maxAttempts(ContainerOpenReach.Band.CLOSE));
        assertEquals(1, ContainerOpenReach.maxAttempts(ContainerOpenReach.Band.EDGE));
        assertEquals(0, ContainerOpenReach.maxAttempts(ContainerOpenReach.Band.OUT_OF_REACH));
    }

    @Test
    void stanceIdentityUsesFeetCellIncludingNegativeCoordinates() {
        assertEquals(ContainerOpenReach.stanceKey(-10.2, 64, 5.2),
                ContainerOpenReach.stanceKey(-10.8, 64.8, 5.9));
        assertNotEquals(ContainerOpenReach.stanceKey(-10.2, 64, 5.2),
                ContainerOpenReach.stanceKey(-11.2, 64, 5.2));
    }
}
