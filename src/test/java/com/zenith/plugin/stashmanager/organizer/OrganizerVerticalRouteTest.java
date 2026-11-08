package com.zenith.plugin.stashmanager.organizer;

import com.zenith.plugin.stashmanager.StashManagerConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrganizerVerticalRouteTest {
    @Test
    void configuredDestinationOnAnUncoveredLandingCanUseVerifiedDiscovery() {
        var rule = new StashManagerConfig.OrganizerVerticalRouteRule();
        rule.dimension = "OVERWORLD";
        rule.originYRange = new int[]{1, 5};
        rule.targetBounds = new int[]{3, 9, 0, 3, 9, 0};
        // The configured ascent is elsewhere, so the Y6 landing cannot safely
        // borrow its transition-stage shortcut.
        rule.waypoints = new int[][]{{20, 5, 0}};
        var rules = List.of(rule);
        var start = new StationWalkRecovery.Point(0, 6, 0);
        var exit = new StationWalkRecovery.Point(2, 9, 0);
        var target = new StationWalkRecovery.Point(3, 9, 0);

        assertTrue(OrganizerVerticalRoute.targetsAnyRule(rules, "OVERWORLD", 3, 9, 0));
        assertFalse(OrganizerVerticalRoute.matchesAnyRule(rules, "OVERWORLD", 6, 3, 9, 0));
        assertNull(OrganizerVerticalRoute.select(rules, null, null, null,
                "OVERWORLD", 0, 6, 0, 3, 9, 0));

        var discovered = DiscoveredVerticalRoute.inspect(start, target,
                new DiscoveredVerticalRoute.WorldView() {
                    @Override
                    public DiscoveredVerticalRoute.Cell cell(StationWalkRecovery.Point point) {
                        if (point.equals(start) || point.equals(exit)) {
                            return DiscoveredVerticalRoute.Cell.STANDING;
                        }
                        if (point.x() == 1 && point.z() == 0
                                && point.y() >= 6 && point.y() <= 8) {
                            return DiscoveredVerticalRoute.Cell.LADDER;
                        }
                        return DiscoveredVerticalRoute.Cell.BLOCKED;
                    }

                    @Override
                    public boolean canAccess(StationWalkRecovery.Point standing,
                                             StationWalkRecovery.Point destination) {
                        return standing.equals(exit) && destination.equals(target);
                    }

                    @Override
                    public boolean safeLadderLip(StationWalkRecovery.Point standing,
                                                 StationWalkRecovery.Point ladder) {
                        return true;
                    }
                });
        assertNotNull(discovered.plan());
        assertTrue(discovered.plan().stages().contains(new StationWalkRecovery.Point(1, 8, 0)));
    }

    @Test
    void oneRungGoalRequiresTheNextVerifiedLadderRung() {
        int[] first = {4, 75, 9};
        int[] second = {4, 76, 9};
        assertTrue(OrganizerVerticalRoute.needsNextLadderGoal(
                first, second, new int[]{4, 75, 9}, 74));
        assertFalse(OrganizerVerticalRoute.needsNextLadderGoal(
                first, second, new int[]{4, 75, 9}, 75));
        assertTrue(OrganizerVerticalRoute.needsNextLadderGoal(
                first, second, new int[]{4, 74, 9}, 74));
        assertFalse(OrganizerVerticalRoute.needsNextLadderGoal(
                first, second, new int[]{4, 73, 9}, 73));
    }

    @Test
    void neverSkipsAHorizontalOrNoncontiguousStage() {
        int[] first = {4, 75, 9};
        int[] feet = {4, 75, 9};
        assertFalse(OrganizerVerticalRoute.needsNextLadderGoal(
                first, new int[]{5, 76, 9}, feet, 74));
        assertFalse(OrganizerVerticalRoute.needsNextLadderGoal(
                first, new int[]{4, 77, 9}, feet, 74));
    }

    @Test
    void topRungGoalCanExtendOnlyToAdjacentUpperLanding() {
        int[] rung = {4, 78, 9};
        int[] feet = {4, 78, 9};
        assertTrue(OrganizerVerticalRoute.needsLadderLipGoal(
                rung, new int[]{5, 79, 9}, feet, 77));
        assertFalse(OrganizerVerticalRoute.needsLadderLipGoal(
                rung, new int[]{5, 79, 9}, feet, 78));
        assertFalse(OrganizerVerticalRoute.needsLadderLipGoal(
                rung, new int[]{6, 79, 9}, feet, 77));
        assertFalse(OrganizerVerticalRoute.needsLadderLipGoal(
                rung, new int[]{5, 78, 9}, feet, 77));
        assertFalse(OrganizerVerticalRoute.needsLadderLipGoal(
                rung, new int[]{5, 79, 9}, new int[]{4, 76, 9}, 76));
    }
}
