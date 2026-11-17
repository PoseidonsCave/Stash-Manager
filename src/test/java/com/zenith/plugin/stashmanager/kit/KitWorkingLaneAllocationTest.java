package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KitWorkingLaneAllocationTest {
    private static KitWorkingLaneAllocation.Chest chest(int x, boolean empty) {
        return new KitWorkingLaneAllocation.Chest(x, 130, 10, true, empty, true);
    }

    @Test
    void threePilotKitsFitTwoChestsWithHeadroomAndNoOverlappingSlots() {
        var plan = KitWorkingLaneAllocation.assess(
                Map.of("rainbow_wool", 16L, "redstone", 21L, "griefing", 16L),
                List.of(chest(1, true), chest(2, true)), 3);

        assertTrue(plan.ready());
        assertEquals(53, plan.requestedSlots());
        assertEquals(51, plan.usableSlotsPerChest());
        assertEquals(3, plan.ranges().size());
        assertTrue(plan.ranges().stream().allMatch(range -> range.firstSlot() >= 0
                && range.lastSlot() < 51));
        for (var left : plan.ranges()) for (var right : plan.ranges()) {
            if (left == right || !left.chest().equals(right.chest())) continue;
            assertTrue(left.lastSlot() < right.firstSlot()
                    || right.lastSlot() < left.firstSlot());
        }
    }

    @Test
    void unverifiedAndConflictingChestsCannotBeReserved() {
        var roleConflict = new KitWorkingLaneAllocation.Chest(2, 130, 10,
                true, true, false);
        var plan = KitWorkingLaneAllocation.assess(Map.of("redstone", 21L),
                List.of(chest(1, false), roleConflict), 3);

        assertFalse(plan.ready());
        assertEquals(21, plan.missingSlots());
        assertTrue(plan.ranges().isEmpty());
    }

    @Test
    void insufficientCapacityReturnsNoPartialManifest() {
        var plan = KitWorkingLaneAllocation.assess(Map.of("large", 60L),
                List.of(chest(1, true)), 3);
        assertFalse(plan.ready());
        assertEquals(9, plan.missingSlots());
        assertTrue(plan.ranges().isEmpty());
    }

    @Test
    void oversizedKitCanSpanVerifiedChestsWithoutUsingHeadroom() {
        var plan = KitWorkingLaneAllocation.assess(Map.of("large", 60L),
                List.of(chest(1, true), chest(2, true)), 3);
        assertTrue(plan.ready());
        assertEquals(List.of(51, 9), plan.ranges().stream()
                .map(KitWorkingLaneAllocation.Range::slotCount).toList());
    }
}
