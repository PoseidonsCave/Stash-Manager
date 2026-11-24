package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitPackingWorksitePlannerTest {
    @Test
    void discoversSameFloorWorksiteBeyondChestLinedAisle() {
        KitPackingWorksitePlanner.WorldView world = new KitPackingWorksitePlanner.WorldView() {
            @Override public boolean replaceable(int x, int y, int z) {
                return (y == 0 || y == 1)
                        && ((x >= -3 && x <= 3 && (z == 0 || z == 1))
                        || (x >= 4 && x <= 6 && z >= -1 && z <= 2));
            }
            @Override public boolean solid(int x, int y, int z) {
                return y == -1;
            }
            @Override public boolean interactable(int x, int y, int z) {
                return y == 0 && x >= -3 && x <= 3 && (z == -1 || z == 2);
            }
        };

        // The bot's two-wide aisle is bordered by chests, so neither aisle row is safe.
        assertTrue(!KitPackingWorksitePlanner.safeWorksite(0, 0, 0, world));
        assertTrue(!KitPackingWorksitePlanner.safeWorksite(0, 0, 1, world));

        var plan = KitPackingWorksitePlanner.discover(0, 0, 0, world);

        assertNotNull(plan);
        assertEquals(0, plan.stance()[1]);
        assertEquals(0, plan.worksite()[1]);
        assertTrue(plan.worksite()[0] >= 4);
        assertTrue(KitPackingWorksitePlanner.safeWorksite(
                plan.worksite()[0], plan.worksite()[1], plan.worksite()[2], world));
    }
}
