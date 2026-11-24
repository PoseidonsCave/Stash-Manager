package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KitWorkingManifestPlanTest {
    private static KitWorkingLaneAllocation.Chest chest() {
        return new KitWorkingLaneAllocation.Chest(1, 64, 1, true, true, true);
    }

    @Test
    void pinsEachExactBoxQuantityToOneNonoverlappingSlot() {
        var report = KitWorkingManifestPlan.assess(
                Map.of("bulk", Map.of("redstone", 900)),
                Map.of("bulk", 2), Map.of("redstone", 1800L),
                List.of(chest()), 3);

        assertTrue(report.ready());
        assertEquals(2, report.slots().size());
        assertEquals(1728, report.slots().get(0).units());
        assertEquals(72, report.slots().get(1).units());
        assertNotEquals(report.slots().get(0).chestSlot(),
                report.slots().get(1).chestSlot());
    }

    @Test
    void sharedShortageProducesNoTransferableManifest() {
        var report = KitWorkingManifestPlan.assess(
                Map.of("a", Map.of("beacon", 2), "b", Map.of("beacon", 2)),
                Map.of("a", 1, "b", 1), Map.of("beacon", 3L),
                List.of(chest()), 3);

        assertFalse(report.ready());
        assertEquals(1, report.indexedShortages().get("beacon"));
        assertTrue(report.slots().isEmpty());
    }

    @Test
    void potionWithoutEffectIdentityCannotEnterPhysicalManifest() {
        assertThrows(IllegalArgumentException.class, () -> KitWorkingManifestPlan.assess(
                Map.of("potions", Map.of("potion", 1)),
                Map.of("potions", 1), Map.of("potion", 1L),
                List.of(chest()), 3));
    }
}
