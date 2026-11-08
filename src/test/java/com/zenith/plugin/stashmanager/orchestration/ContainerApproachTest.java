package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerApproachTest {
    @Test
    void acceptsAdjacentAndDirectlyBelowContainerPositions() {
        assertTrue(ContainerApproach.isAtAccessPosition(11.2, 64.0, 10.8, 10, 64, 10));
        assertTrue(ContainerApproach.isAtAccessPosition(10.2, 63.0, 10.8, 10, 64, 10));
    }

    @Test
    void acceptsConservativeInteractionReachAfterGoalProcessStops() {
        assertTrue(ContainerApproach.isAtAccessPosition(13.9, 64.0, 10.5, 10, 64, 10));
        assertTrue(ContainerApproach.isAtAccessPosition(12.7, 64.0, 12.0, 10, 64, 10));
    }

    @Test
    void rejectsBeyondConservativeReachAndHandlesNegativeCoordinatesByFlooring() {
        assertFalse(ContainerApproach.isAtAccessPosition(15.2, 64.0, 10.5, 10, 64, 10));
        assertTrue(ContainerApproach.isAtAccessPosition(-11.1, 64.0, -10.2, -11, 64, -11));
        assertTrue(ContainerApproach.isAtAccessPosition(-14.1, 64.0, -10.5, -11, 64, -11));
        assertFalse(ContainerApproach.isAtAccessPosition(-15.2, 64.0, -10.5, -11, 64, -11));
    }

    @Test
    void measuresVerticalReachFromPlayerEyesToBlockBounds() {
        assertTrue(ContainerApproach.isAtAccessPosition(10.5, 60.0, 10.5, 10, 65, 10));
        assertFalse(ContainerApproach.isAtAccessPosition(10.5, 59.0, 10.5, 10, 65, 10));
    }

    @Test
    void vanillaFallbackOnlyExtendsTheLastHalfBlock() {
        assertFalse(ContainerApproach.isAtAccessPosition(15.2, 64.0, 10.5, 10, 64, 10));
        assertTrue(ContainerApproach.isWithinVanillaReach(15.2, 64.0, 10.5, 10, 64, 10));
        assertFalse(ContainerApproach.isWithinVanillaReach(15.8, 64.0, 10.5, 10, 64, 10));
    }
}
