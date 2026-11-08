package com.zenith.plugin.stashmanager.orchestration;

import com.zenith.mc.block.properties.SlabType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerAccessPlannerTest {
    @Test
    void coveredChestCanReceiveOneBoundedInteractionProbe() {
        assertTrue(ContainerAccessPlanner.coveredChestProbeGeometry(
                "chest", SlabType.TOP, ContainerOpenReach.Band.EDGE));
        assertTrue(ContainerAccessPlanner.coveredChestProbeGeometry(
                "trapped_chest", SlabType.TOP, ContainerOpenReach.Band.CLOSE));
        assertFalse(ContainerAccessPlanner.coveredChestProbeGeometry(
                "chest", SlabType.TOP, ContainerOpenReach.Band.OUT_OF_REACH));
        assertFalse(ContainerAccessPlanner.coveredChestProbeGeometry(
                "chest", SlabType.BOTTOM, ContainerOpenReach.Band.EDGE));
        assertFalse(ContainerAccessPlanner.coveredChestProbeGeometry(
                "barrel", SlabType.TOP, ContainerOpenReach.Band.CLOSE));
    }
}
