package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DiscoveredVerticalRouteNearbyStartTest {
    private static final StationWalkRecovery.Point BLOCKED_NAVIGATION_CELL = point(0, 5, 0);
    private static final StationWalkRecovery.Point CARPET_START = point(-1, 5, -1);
    private static final StationWalkRecovery.Point TARGET = point(1, 8, 0);

    @Test
    void groundedPoseBesideBlockedCellBootstrapsThroughVerifiedSameFloorStart() {
        var result = DiscoveredVerticalRoute.inspectFromGroundedPose(
                0.10, 5.063, 0.29, true, BLOCKED_NAVIGATION_CELL, TARGET, routeWorld());

        assertEquals(CARPET_START, result.bootstrap());
        assertNotNull(result.search().plan());
        assertEquals("verified_nearby_start", result.search().reason());
        assertEquals(CARPET_START, result.search().plan().stages().getFirst());
    }

    @Test
    void airborneOrNotOnTheSameFloorCannotBorrowNearbyStart() {
        var airborne = DiscoveredVerticalRoute.inspectFromGroundedPose(
                0.10, 5.063, 0.29, false, BLOCKED_NAVIGATION_CELL, TARGET, routeWorld());
        var elevated = DiscoveredVerticalRoute.inspectFromGroundedPose(
                0.10, 5.50, 0.29, true, BLOCKED_NAVIGATION_CELL, TARGET, routeWorld());

        assertNull(airborne.bootstrap());
        assertNull(airborne.search().plan());
        assertEquals("start_unusable", airborne.search().reason());
        assertNull(elevated.bootstrap());
        assertEquals("start_unusable", elevated.search().reason());
    }

    @Test
    void blockedOrDistantStandingCellDoesNotAuthorizeBootstrap() {
        var result = DiscoveredVerticalRoute.inspectFromGroundedPose(
                0.10, 5.063, 0.29, true, BLOCKED_NAVIGATION_CELL, TARGET,
                new DiscoveredVerticalRoute.WorldView() {
                    @Override
                    public DiscoveredVerticalRoute.Cell cell(StationWalkRecovery.Point point) {
                        return point.equals(point(1, 5, 1))
                                ? DiscoveredVerticalRoute.Cell.STANDING
                                : DiscoveredVerticalRoute.Cell.BLOCKED;
                    }

                    @Override
                    public boolean canAccess(StationWalkRecovery.Point from,
                                             StationWalkRecovery.Point target) {
                        return false;
                    }

                    @Override
                    public boolean safeLadderLip(StationWalkRecovery.Point standing,
                                                  StationWalkRecovery.Point ladder) {
                        return false;
                    }
                });
        assertNull(result.bootstrap());
        assertEquals("start_unusable", result.search().reason());
    }

    private static DiscoveredVerticalRoute.WorldView routeWorld() {
        Map<StationWalkRecovery.Point, DiscoveredVerticalRoute.Cell> cells = Map.of(
                CARPET_START, DiscoveredVerticalRoute.Cell.STANDING,
                point(0, 5, -1), DiscoveredVerticalRoute.Cell.LADDER,
                point(0, 6, -1), DiscoveredVerticalRoute.Cell.LADDER,
                point(0, 7, -1), DiscoveredVerticalRoute.Cell.LADDER,
                point(1, 8, -1), DiscoveredVerticalRoute.Cell.STANDING);
        return new DiscoveredVerticalRoute.WorldView() {
            @Override
            public DiscoveredVerticalRoute.Cell cell(StationWalkRecovery.Point point) {
                return cells.getOrDefault(point, DiscoveredVerticalRoute.Cell.BLOCKED);
            }

            @Override
            public boolean canAccess(StationWalkRecovery.Point from,
                                     StationWalkRecovery.Point target) {
                return from.equals(point(1, 8, -1));
            }

            @Override
            public boolean safeLadderLip(StationWalkRecovery.Point standing,
                                          StationWalkRecovery.Point ladder) {
                return standing.equals(point(1, 8, -1)) && ladder.equals(point(0, 7, -1));
            }
        };
    }

    private static StationWalkRecovery.Point point(int x, int y, int z) {
        return new StationWalkRecovery.Point(x, y, z);
    }
}
