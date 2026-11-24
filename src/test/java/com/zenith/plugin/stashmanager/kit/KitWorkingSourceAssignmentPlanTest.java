package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.database.DatabaseManager;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class KitWorkingSourceAssignmentPlanTest {
    private static DatabaseManager.KitWorkingSlotRow slot(int destinationSlot) {
        return new DatabaseManager.KitWorkingSlotRow(10, 64, 2, destinationSlot,
                "redstone", "redstone", 64, "pending", null);
    }

    private static ContainerEntry chest(long timestamp, int... sourceSlots) {
        return new ContainerEntry(1, 64, 1, "minecraft:chest", false,
                Map.of("redstone", sourceSlots.length * 64), sourceSlots.length,
                java.util.Arrays.stream(sourceSlots).mapToObj(sourceSlot ->
                        new ContainerEntry.ShulkerDetail(sourceSlot, "blue",
                                Map.of("redstone", 64))).toList(), timestamp);
    }

    @Test void assignsDistinctPhysicalBoxesWithoutDoubleCounting() {
        var source = chest(200, 2, 3);
        var report = KitWorkingSourceAssignmentPlan.assign(List.of(slot(5), slot(4)),
                List.of(source), Set.of(), 100);
        assertTrue(report.ready());
        assertEquals(2, report.assignments().size());
        assertEquals(2, report.assignments().get(0).sourceSlot());
        assertEquals(3, report.assignments().get(1).sourceSlot());
        assertEquals(4, report.assignments().get(0).destination().slot());
    }

    @Test void missingOrStaleStockNeverProducesPartialAssignments() {
        var report = KitWorkingSourceAssignmentPlan.assign(List.of(slot(4), slot(5)),
                List.of(chest(200, 2)), Set.of(), 100);
        assertFalse(report.ready());
        assertTrue(report.assignments().isEmpty());
        assertEquals(1, report.missingBoxes().get("redstone x64"));
        var stale = KitWorkingSourceAssignmentPlan.assign(List.of(slot(4)),
                List.of(chest(50, 2)), Set.of(), 100);
        assertFalse(stale.ready());
    }

    @Test void protectedInventoryIsExcluded() {
        var entry = chest(200, 2);
        var report = KitWorkingSourceAssignmentPlan.assign(List.of(slot(4)),
                List.of(entry), Set.of(entry.inventoryKey()), 100);
        assertFalse(report.ready());
    }
}
