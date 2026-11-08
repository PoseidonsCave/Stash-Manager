package com.zenith.plugin.stashmanager.organizer;

import com.zenith.plugin.stashmanager.StashManagerConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OrganizerVerticalRouteDescentTest {
    private static StashManagerConfig.OrganizerVerticalRouteRule ascent() {
        var rule = new StashManagerConfig.OrganizerVerticalRouteRule();
        rule.dimension = "OVERWORLD";
        rule.originYRange = new int[]{66, 73};
        rule.targetBounds = new int[]{50, 82, 50, 50, 87, 50};
        rule.waypoints = new int[][]{
                {60, 68, 60}, {20, 74, 25}, {20, 79, 22}, {50, 84, 50}
        };
        return rule;
    }

    @Test
    void reversesNearbyConfiguredAscentForLowerChest() {
        int[][] route = OrganizerVerticalRoute.selectSafeDescent(
                List.of(ascent()), "OVERWORLD", 21, 79, 21, 70, 68, 70);
        assertArrayEquals(new int[]{20, 79, 22}, route[0]);
        assertArrayEquals(new int[]{20, 74, 25}, route[1]);
        assertArrayEquals(new int[]{60, 68, 60}, route[2]);
    }

    @Test
    void doesNotBorrowDistantOrWrongDimensionRoute() {
        assertNull(OrganizerVerticalRoute.selectSafeDescent(
                List.of(ascent()), "OVERWORLD", 35, 79, 35, 70, 68, 70));
        assertNull(OrganizerVerticalRoute.selectSafeDescent(
                List.of(ascent()), "NETHER", 21, 79, 21, 70, 68, 70));
        assertNull(OrganizerVerticalRoute.selectSafeDescent(
                List.of(ascent()), "OVERWORLD", 21, 68, 21, 70, 79, 70));
    }
}
