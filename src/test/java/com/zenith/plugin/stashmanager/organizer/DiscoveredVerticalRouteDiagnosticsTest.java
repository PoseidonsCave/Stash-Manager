package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DiscoveredVerticalRouteDiagnosticsTest {
    @Test
    void reportsUnusableStartWithoutInventingAPath() {
        var start = new StationWalkRecovery.Point(0, 3, 0);
        var target = new StationWalkRecovery.Point(3, 0, 0);
        var result = DiscoveredVerticalRoute.inspect(start, target,
                world(Set.of(), target));
        assertNull(result.plan());
        assertEquals("start_unusable", result.reason());
        assertEquals(0, result.visited());
    }

    @Test
    void reportsExhaustionSeparatelyFromAnUnsafeStart() {
        var start = new StationWalkRecovery.Point(0, 3, 0);
        var target = new StationWalkRecovery.Point(6, 0, 0);
        var result = DiscoveredVerticalRoute.inspect(start, target,
                world(Set.of(start), target));
        assertNull(result.plan());
        assertEquals("search_exhausted", result.reason());
        assertEquals(1, result.visited());
        assertEquals(1, result.processed());
    }

    @Test
    void verifiedRouteStillReturnsTheSameConservativePlan() {
        var start = new StationWalkRecovery.Point(0, 3, 0);
        var target = new StationWalkRecovery.Point(3, 0, 0);
        var result = DiscoveredVerticalRoute.inspect(start, target,
                world(Set.of(start, new StationWalkRecovery.Point(1, 2, 0),
                        new StationWalkRecovery.Point(2, 1, 0), target), target));
        assertNotNull(result.plan());
        assertEquals("verified", result.reason());
        assertEquals(target, result.plan().stages().getLast());
    }

    @Test
    void findsBoundedUpperFloorDetourBeforeDescending() {
        var start = new StationWalkRecovery.Point(0, 79, 0);
        var target = new StationWalkRecovery.Point(18, 68, 0);
        var result = DiscoveredVerticalRoute.inspect(start, target,
                upperFloorDetourWorld(84, target));
        assertNotNull(result.plan());
        assertEquals("verified", result.reason());
        assertEquals(84, result.highestY());
        assertEquals(target, result.plan().stages().getLast());
    }

    @Test
    void rejectsDetourAboveTheBoundedSearchCeiling() {
        var start = new StationWalkRecovery.Point(0, 79, 0);
        var target = new StationWalkRecovery.Point(22, 68, 0);
        var result = DiscoveredVerticalRoute.inspect(start, target,
                upperFloorDetourWorld(88, target));
        assertNull(result.plan());
        assertEquals("search_exhausted", result.reason());
        assertEquals(87, result.highestY());
    }

    private static DiscoveredVerticalRoute.WorldView upperFloorDetourWorld(
            int topY, StationWalkRecovery.Point target) {
        Map<StationWalkRecovery.Point, DiscoveredVerticalRoute.Cell> cells = new HashMap<>();
        cells.put(new StationWalkRecovery.Point(0, 79, 0),
                DiscoveredVerticalRoute.Cell.STANDING);
        for (int y = 79; y < topY; y++) {
            cells.put(new StationWalkRecovery.Point(1, y, 0),
                    DiscoveredVerticalRoute.Cell.LADDER);
        }
        cells.put(new StationWalkRecovery.Point(2, topY, 0),
                DiscoveredVerticalRoute.Cell.STANDING);
        for (int step = 1; step <= topY - target.y(); step++) {
            cells.put(new StationWalkRecovery.Point(2 + step, topY - step, 0),
                    DiscoveredVerticalRoute.Cell.STANDING);
        }
        return new DiscoveredVerticalRoute.WorldView() {
            @Override
            public DiscoveredVerticalRoute.Cell cell(StationWalkRecovery.Point point) {
                return cells.getOrDefault(point, DiscoveredVerticalRoute.Cell.BLOCKED);
            }

            @Override
            public boolean canAccess(StationWalkRecovery.Point point,
                                     StationWalkRecovery.Point chest) {
                return point.equals(target);
            }

            @Override
            public boolean safeLadderLip(StationWalkRecovery.Point standing,
                                         StationWalkRecovery.Point ladder) {
                return cells.get(standing) == DiscoveredVerticalRoute.Cell.STANDING
                        && cells.get(ladder) == DiscoveredVerticalRoute.Cell.LADDER;
            }
        };
    }

    private static DiscoveredVerticalRoute.WorldView world(
            Set<StationWalkRecovery.Point> standing, StationWalkRecovery.Point target) {
        return new DiscoveredVerticalRoute.WorldView() {
            @Override
            public DiscoveredVerticalRoute.Cell cell(StationWalkRecovery.Point point) {
                return standing.contains(point) ? DiscoveredVerticalRoute.Cell.STANDING
                        : DiscoveredVerticalRoute.Cell.BLOCKED;
            }

            @Override
            public boolean canAccess(StationWalkRecovery.Point point,
                                     StationWalkRecovery.Point chest) {
                return point.equals(target);
            }

            @Override
            public boolean safeLadderLip(StationWalkRecovery.Point standing,
                                         StationWalkRecovery.Point ladder) {
                return false;
            }
        };
    }
}
