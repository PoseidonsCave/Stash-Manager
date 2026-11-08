package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DedicatedLaneCapacityTest {
    @Test
    void reportsExactShortfallWithoutSharing() {
        var audit = DedicatedLaneCapacity.assess(10, 2, 11);
        assertEquals(8, audit.assignableLanes());
        assertEquals(3, audit.laneShortfall());
        assertFalse(audit.feasible());
    }

    @Test
    void existingAndEmptyAssignableLanesSatisfyOneClassEach() {
        var audit = DedicatedLaneCapacity.assess(12, 2, 10);
        assertEquals(10, audit.assignableLanes());
        assertTrue(audit.feasible());
    }
}
