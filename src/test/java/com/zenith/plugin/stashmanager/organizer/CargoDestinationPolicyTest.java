package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.zenith.plugin.stashmanager.organizer.CargoDestinationPolicy.FullDestinationAction.PACK_LOOSE_INTO_IMPORT;
import static com.zenith.plugin.stashmanager.organizer.CargoDestinationPolicy.FullDestinationAction.STAGE_SHULKER_IN_IMPORT;
import static com.zenith.plugin.stashmanager.organizer.CargoDestinationPolicy.FullDestinationAction.TRY_ALTERNATE_IMPORT;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CargoDestinationPolicyTest {
    @Test
    void importCargoNeverFallsIntoPacking() {
        assertEquals(TRY_ALTERNATE_IMPORT,
                CargoDestinationPolicy.afterPermanentCascadeExhausted(true, false));
        assertEquals(TRY_ALTERNATE_IMPORT,
                CargoDestinationPolicy.afterPermanentCascadeExhausted(true, true));
    }

    @Test
    void fullPermanentLaneStagesShulkers() {
        assertEquals(STAGE_SHULKER_IN_IMPORT,
                CargoDestinationPolicy.afterPermanentCascadeExhausted(false, true));
    }

    @Test
    void fullPermanentLanePacksLooseCargoIntoImportStaging() {
        assertEquals(PACK_LOOSE_INTO_IMPORT,
                CargoDestinationPolicy.afterPermanentCascadeExhausted(false, false));
    }

    @Test
    void inaccessibleTopChestAdvancesWithinTheSameAssignedLane() {
        List<int[]> lane = List.of(
                new int[]{10, 82, 20},
                new int[]{10, 81, 20},
                new int[]{10, 80, 20},
                new int[]{10, 79, 20});
        assertEquals(1, CargoDestinationPolicy.nextLaneChest(lane, lane.get(0),
                new int[]{13, 79, 23}, point -> point[1], point -> true));
        assertEquals(2, CargoDestinationPolicy.nextLaneChest(lane, lane.get(1),
                new int[]{13, 79, 23}, point -> point[1], point -> true));
        assertEquals(-1, CargoDestinationPolicy.nextLaneChest(lane, lane.get(3),
                new int[]{13, 79, 23}, point -> point[1], point -> true));
    }

    @Test
    void fallbackSkipsSameInventorySourceAndExcludedChest() {
        List<int[]> lane = List.of(new int[]{0, 82, 0}, new int[]{1, 82, 0},
                new int[]{2, 81, 0}, new int[]{3, 80, 0});
        // The first two coordinates are opposite halves of one double chest.
        assertEquals(3, CargoDestinationPolicy.nextLaneChest(lane, lane.get(0), lane.get(2),
                point -> point[0] <= 1 ? 10 : point[0] == 2 ? 20 : 30,
                point -> true));
        assertEquals(-1, CargoDestinationPolicy.nextLaneChest(lane, lane.get(0), lane.get(2),
                point -> point[0] <= 1 ? 10 : point[0] == 2 ? 20 : 30,
                point -> point[0] != 3));
        assertEquals(-1, CargoDestinationPolicy.nextLaneChest(lane, new int[]{9, 82, 0}, null,
                point -> point[0], point -> true));
    }
}
