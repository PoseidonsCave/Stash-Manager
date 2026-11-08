package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import static com.zenith.plugin.stashmanager.organizer.DiscoveredVerticalRoute.Cell;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveredVerticalRouteTest {
    private static final StationWalkRecovery.Point START = point(0, 5, 0);
    private static final StationWalkRecovery.Point TARGET = point(20, 1, 0);

    @Test
    void findsStandingDetourWhenShorterSlabRunCannotBeStaged() {
        var result = DiscoveredVerticalRoute.inspect(START, TARGET, world(true));
        assertNotNull(result.plan());
        assertEquals("verified", result.reason());
    }

    @Test
    void refusesUnstageableSlabRunWithoutAStandingDetour() {
        assertNull(DiscoveredVerticalRoute.inspect(START, TARGET, world(false)).plan());
    }

    @Test
    void stagesEachVerifiedLadderRungInsteadOfRequestingACompressedClimb() {
        var start = point(0, 1, 0);
        var exit = point(2, 6, 0);
        var target = point(3, 6, 0);
        var plan = DiscoveredVerticalRoute.find(start, target,
                new DiscoveredVerticalRoute.WorldView() {
                    @Override
                    public Cell cell(StationWalkRecovery.Point p) {
                        if (p.equals(start) || p.equals(exit)) return Cell.STANDING;
                        if (p.x() == 1 && p.z() == 0 && p.y() >= 1 && p.y() <= 5) {
                            return Cell.LADDER;
                        }
                        return Cell.BLOCKED;
                    }

                    @Override
                    public boolean canAccess(StationWalkRecovery.Point p,
                                             StationWalkRecovery.Point destination) {
                        return p.equals(exit) && destination.equals(target);
                    }

                    @Override
                    public boolean safeLadderLip(StationWalkRecovery.Point standing,
                                                 StationWalkRecovery.Point ladder) {
                        return true;
                    }
                });
        assertNotNull(plan);
        for (int y = 2; y <= 5; y++) {
            assertTrue(plan.stages().contains(point(1, y, 0)),
                    "missing verified ladder rung at y=" + y);
        }
    }

    private static DiscoveredVerticalRoute.WorldView world(boolean detour) {
        return new DiscoveredVerticalRoute.WorldView() {
            @Override
            public Cell cell(StationWalkRecovery.Point p) {
                if (p.equals(START) || p.equals(point(14, 5, 0))
                        || p.equals(point(16, 1, 0))) return Cell.STANDING;
                if (p.y() == 5 && p.z() == 0 && p.x() >= 1 && p.x() <= 13) {
                    return Cell.BOTTOM_SLAB;
                }
                if (detour && p.y() == 5 && p.z() == 1 && p.x() >= 0 && p.x() <= 14) {
                    return Cell.STANDING;
                }
                if (p.x() == 15 && p.z() == 0 && p.y() >= 1 && p.y() <= 4) {
                    return Cell.LADDER;
                }
                return Cell.UNKNOWN;
            }

            @Override
            public boolean canAccess(StationWalkRecovery.Point p, StationWalkRecovery.Point target) {
                return p.equals(point(16, 1, 0)) && target.equals(TARGET);
            }

            @Override
            public boolean safeLadderLip(StationWalkRecovery.Point standing,
                                         StationWalkRecovery.Point ladder) {
                return true;
            }
        };
    }

    private static StationWalkRecovery.Point point(int x, int y, int z) {
        return new StationWalkRecovery.Point(x, y, z);
    }
}
