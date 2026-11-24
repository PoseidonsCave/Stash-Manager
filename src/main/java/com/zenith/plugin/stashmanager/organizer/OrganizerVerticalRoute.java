package com.zenith.plugin.stashmanager.organizer;

import com.zenith.plugin.stashmanager.StashManagerConfig;

import java.util.List;
import java.util.ArrayList;

/** Fail-closed bounds for an operator-verified stair/ladder approach. */
final class OrganizerVerticalRoute {
    private static final int MIN_CLIMB = 5;
    private static final long MAX_WAYPOINT_DISTANCE_SQ = 96L * 96L;
    private static final int MAX_STAGES = 8;
    private static final int TRANSITION_BAND = 2;
    private static final long TRANSITION_STAGE_DISTANCE_SQ = 4L * 4L;
    private static final long DESCENT_ENTRY_DISTANCE_SQ = 4L * 4L;

    private OrganizerVerticalRoute() {}

    static boolean eligible(int[] waypoint, int playerX, int playerY, int playerZ,
                            int targetX, int targetY, int targetZ) {
        if (waypoint == null || waypoint.length != 3 || targetY - playerY < MIN_CLIMB) return false;
        if (waypoint[1] < playerY - 6 || waypoint[1] > playerY + 4) return false;
        return horizontalDistanceSq(waypoint[0], waypoint[2], playerX, playerZ)
                        <= MAX_WAYPOINT_DISTANCE_SQ
                && horizontalDistanceSq(waypoint[0], waypoint[2], targetX, targetZ)
                        <= MAX_WAYPOINT_DISTANCE_SQ;
    }

    static int[][] select(int[] legacyWaypoint, int[][] stagedWaypoints,
                          int[] stagedDestination,
                          int playerX, int playerY, int playerZ,
                          int targetX, int targetY, int targetZ) {
        if (stagedWaypoints != null && stagedWaypoints.length > 0
                && (stagedDestination == null || stagedDestination.length != 3
                        || stagedDestination[0] != targetX || stagedDestination[1] != targetY
                        || stagedDestination[2] != targetZ)) return null;
        int[][] requested = stagedWaypoints != null && stagedWaypoints.length > 0
                ? stagedWaypoints : legacyWaypoint == null ? null : new int[][]{legacyWaypoint};
        if (requested == null || requested.length > MAX_STAGES
                || targetY - playerY < MIN_CLIMB) return null;
        int[][] copy = new int[requested.length][];
        for (int i = 0; i < requested.length; i++) {
            int[] stage = requested[i];
            if (stage == null || stage.length != 3
                    || horizontalDistanceSq(stage[0], stage[2], targetX, targetZ)
                            > MAX_WAYPOINT_DISTANCE_SQ
                    || (i > 0 && (stage[1] < requested[i - 1][1]
                            || horizontalDistanceSq(stage[0], stage[2],
                                    requested[i - 1][0], requested[i - 1][2])
                                    > MAX_WAYPOINT_DISTANCE_SQ))) return null;
            copy[i] = stage.clone();
        }
        int first = firstIndex(copy, playerX, playerY, playerZ);
        return first < copy.length && eligible(copy[first], playerX, playerY, playerZ,
                targetX, targetY, targetZ) ? copy : null;
    }

    static int[][] select(List<StashManagerConfig.OrganizerVerticalRouteRule> rules,
                          int[] legacyWaypoint, int[][] stagedWaypoints,
                          int[] stagedDestination,
                          String dimension,
                          int playerX, int playerY, int playerZ,
                          int targetX, int targetY, int targetZ) {
        if (rules != null) {
            for (var rule : rules) {
                if (!matches(rule, dimension, playerY, targetX, targetY, targetZ)) continue;
                // An operator-matched but malformed rule fails closed: do not silently
                // substitute another route or let a distant chest borrow these waypoints.
                return validatedRoute(rule.waypoints, playerX, playerY, playerZ,
                        targetX, targetY, targetZ, true);
            }
            // A bot can stand one or two blocks above a route's declared origin
            // while interacting with a chest beside its upper ladder. Only reuse
            // that route when it is actually beside a verified standing stage.
            for (var rule : rules) {
                if (!matchesTransitionStage(rule, dimension, playerX, playerY, playerZ,
                        targetX, targetY, targetZ)) continue;
                return validatedRoute(rule.waypoints, playerX, playerY, playerZ,
                        targetX, targetY, targetZ, true);
            }
        }
        return select(legacyWaypoint, stagedWaypoints, stagedDestination,
                playerX, playerY, playerZ, targetX, targetY, targetZ);
    }

    static boolean matchesAnyRule(List<StashManagerConfig.OrganizerVerticalRouteRule> rules,
                                  String dimension, int playerY,
                                  int targetX, int targetY, int targetZ) {
        if (rules == null) return false;
        for (var rule : rules) {
            if (matches(rule, dimension, playerY, targetX, targetY, targetZ)) return true;
        }
        return false;
    }

    /** A configured upper-floor bank must not be approached directly from an uncovered floor. */
    static boolean targetsAnyRule(List<StashManagerConfig.OrganizerVerticalRouteRule> rules,
                                  String dimension, int targetX, int targetY, int targetZ) {
        if (rules == null) return false;
        for (var rule : rules) {
            if (targets(rule, dimension, targetX, targetY, targetZ)) return true;
        }
        return false;
    }

    /** Reverse an operator-configured ascent through the same verified stairwell. */
    static List<StationWalkRecovery.Point> returnStages(
            List<StashManagerConfig.OrganizerVerticalRouteRule> rules, String dimension,
            int playerX, int playerY, int playerZ, int stationX, int stationY, int stationZ) {
        if (rules == null || dimension == null || playerY - stationY < 3) return List.of();
        for (var rule : rules) {
            if (rule == null || rule.dimension == null
                    || !rule.dimension.equalsIgnoreCase(dimension)
                    || rule.originYRange == null || rule.originYRange.length != 2
                    || stationY < rule.originYRange[0] || stationY > rule.originYRange[1]
                    || rule.waypoints == null || rule.waypoints.length < 2
                    || rule.waypoints.length > MAX_STAGES) continue;
            int[][] stages = rule.waypoints;
            int start = -1;
            long nearest = Long.MAX_VALUE;
            boolean valid = stages[0] != null && stages[0].length == 3
                    && horizontalDistanceSq(stages[0][0], stages[0][2], stationX, stationZ)
                            <= MAX_WAYPOINT_DISTANCE_SQ;
            for (int i = 0; i < stages.length; i++) {
                int[] stage = stages[i];
                if (stage == null || stage.length != 3 || (i > 0
                        && (stage[1] < stages[i - 1][1]
                                || horizontalDistanceSq(stage[0], stage[2],
                                        stages[i - 1][0], stages[i - 1][2])
                                        > MAX_WAYPOINT_DISTANCE_SQ))) {
                    valid = false;
                    break;
                }
                if (Math.abs(stage[1] - playerY) <= 1) {
                    long distance = horizontalDistanceSq(stage[0], stage[2], playerX, playerZ);
                    if (distance < nearest) {
                        nearest = distance;
                        start = i;
                    }
                }
            }
            if (!valid || start < 1 || nearest > MAX_WAYPOINT_DISTANCE_SQ) continue;
            List<StationWalkRecovery.Point> reverse = new ArrayList<>();
            for (int i = start; i >= 0; i--) {
                int[] stage = stages[i];
                reverse.add(new StationWalkRecovery.Point(stage[0], stage[1], stage[2]));
            }
            return List.copyOf(reverse);
        }
        return List.of();
    }

    /** Reuse an operator-verified ascent in reverse for an ordinary lower-floor delivery.
     * The rule lives in the bot's configuration, never in plugin source. A distant stage
     * is not enough evidence to take cargo across an unverified floor to the stairwell.
     */
    static int[][] selectSafeDescent(
            List<StashManagerConfig.OrganizerVerticalRouteRule> rules, String dimension,
            int playerX, int playerY, int playerZ,
            int targetX, int targetY, int targetZ) {
        if (playerY - targetY < 3) return null;
        List<StationWalkRecovery.Point> stages = returnStages(
                rules, dimension, playerX, playerY, playerZ,
                targetX, targetY, targetZ);
        if (stages.isEmpty()) return null;
        var entry = stages.getFirst();
        var exit = stages.getLast();
        if (Math.abs(entry.y() - playerY) > 1
                || horizontalDistanceSq(entry.x(), entry.z(), playerX, playerZ)
                        > DESCENT_ENTRY_DISTANCE_SQ
                || Math.abs(exit.y() - targetY) > 2) return null;
        return stages.stream().map(StationWalkRecovery.Point::array)
                .toArray(int[][]::new);
    }

    private static boolean matches(StashManagerConfig.OrganizerVerticalRouteRule rule,
                                   String dimension, int playerY,
                                   int targetX, int targetY, int targetZ) {
        if (!targets(rule, dimension, targetX, targetY, targetZ)
                || rule.originYRange == null || rule.originYRange.length != 2) return false;
        int[] o = rule.originYRange;
        return o[0] <= playerY && playerY <= o[1] && targetY > playerY;
    }

    private static boolean targets(StashManagerConfig.OrganizerVerticalRouteRule rule,
                                   String dimension, int targetX, int targetY, int targetZ) {
        if (rule == null || rule.dimension == null || dimension == null
                || !rule.dimension.equalsIgnoreCase(dimension)
                || rule.targetBounds == null || rule.targetBounds.length != 6) return false;
        int[] b = rule.targetBounds;
        return b[0] <= targetX && targetX <= b[3]
                // A validated container access stance may be up to two blocks below
                // the configured chest. Keep x/z exact; do not borrow nearby banks.
                && targetY <= b[4] && targetY + 2 >= b[1]
                && b[2] <= targetZ && targetZ <= b[5];
    }

    private static int[][] validatedRoute(int[][] requested,
                                          int playerX, int playerY, int playerZ,
                                          int targetX, int targetY, int targetZ,
                                          boolean allowShortClimb) {
        if (requested == null || requested.length == 0 || requested.length > MAX_STAGES
                || targetY - playerY < (allowShortClimb ? 1 : MIN_CLIMB)) return null;
        int[][] copy = new int[requested.length][];
        for (int i = 0; i < requested.length; i++) {
            int[] stage = requested[i];
            if (stage == null || stage.length != 3
                    || horizontalDistanceSq(stage[0], stage[2], targetX, targetZ)
                            > MAX_WAYPOINT_DISTANCE_SQ
                    || (i > 0 && (stage[1] < requested[i - 1][1]
                            || horizontalDistanceSq(stage[0], stage[2],
                                    requested[i - 1][0], requested[i - 1][2])
                                    > MAX_WAYPOINT_DISTANCE_SQ))) return null;
            copy[i] = stage.clone();
        }
        int first = firstIndex(copy, playerX, playerY, playerZ);
        if (first >= copy.length) return null;
        int[] waypoint = copy[first];
        if (waypoint[1] < playerY - 6 || waypoint[1] > playerY + 4
                || horizontalDistanceSq(waypoint[0], waypoint[2], playerX, playerZ)
                        > MAX_WAYPOINT_DISTANCE_SQ) return null;
        return copy;
    }

    /** A checkpoint resumed on an upper floor must not descend to an earlier stage. */
    static int firstIndex(int[][] waypoints, int playerY) {
        int index = 0;
        while (index < waypoints.length && waypoints[index][1] < playerY - 2) index++;
        return index;
    }

    /** Prefer the nearest operator-verified cell when a floor has several stages. */
    static int firstIndex(int[][] waypoints, int playerX, int playerY, int playerZ) {
        int first = firstIndex(waypoints, playerY);
        if (first >= waypoints.length) return first;
        int best = first;
        long nearest = horizontalDistanceSq(waypoints[first][0], waypoints[first][2],
                playerX, playerZ);
        for (int i = first + 1; i < waypoints.length
                && waypoints[i][1] == waypoints[first][1]; i++) {
            long distance = horizontalDistanceSq(waypoints[i][0], waypoints[i][2],
                    playerX, playerZ);
            if (distance < nearest) {
                best = i;
                nearest = distance;
            }
        }
        return best;
    }

    private static boolean matchesTransitionStage(
            StashManagerConfig.OrganizerVerticalRouteRule rule, String dimension,
            int playerX, int playerY, int playerZ,
            int targetX, int targetY, int targetZ) {
        if (!targets(rule, dimension, targetX, targetY, targetZ)
                || rule.originYRange == null || rule.originYRange.length != 2
                || rule.waypoints == null || targetY <= playerY) return false;
        int upper = rule.originYRange[1];
        if (playerY <= upper || playerY > upper + TRANSITION_BAND) return false;
        for (int[] stage : rule.waypoints) {
            if (stage != null && stage.length == 3 && stage[1] == upper
                    && playerY - stage[1] <= TRANSITION_BAND
                    && horizontalDistanceSq(stage[0], stage[2], playerX, playerZ)
                            <= TRANSITION_STAGE_DISTANCE_SQ) return true;
        }
        return false;
    }

    static boolean reached(int[] waypoint, int playerX, int playerY, int playerZ) {
        return waypoint != null && waypoint.length == 3
                && playerX == waypoint[0] && playerY == waypoint[1]
                && playerZ == waypoint[2];
    }

    /**
     * A one-rung path goal can complete during a transient jump without the player
     * settling at that height. Target one additional verified rung while retaining
     * the physical-arrival check for the current stage. This also covers Zenith's
     * slab-adjusted feet cell at a ladder foot.
     */
    static boolean needsNextLadderGoal(int[] waypoint, int[] nextRung,
                                       int[] pathfinderFeet, int physicalPlayerY) {
        return waypoint != null && waypoint.length == 3
                && nextRung != null && nextRung.length == 3
                && pathfinderFeet != null && pathfinderFeet.length == 3
                && physicalPlayerY < waypoint[1]
                && pathfinderFeet[0] == waypoint[0]
                && pathfinderFeet[1] >= waypoint[1] - 1
                && pathfinderFeet[1] <= waypoint[1]
                && pathfinderFeet[2] == waypoint[2]
                && nextRung[0] == waypoint[0]
                && nextRung[1] == waypoint[1] + 1
                && nextRung[2] == waypoint[2];
    }

    /** Keep climbing through the last rung until the adjacent loaded landing is reached. */
    static boolean needsLadderLipGoal(int[] waypoint, int[] landing,
                                      int[] pathfinderFeet, int physicalPlayerY) {
        return waypoint != null && waypoint.length == 3
                && landing != null && landing.length == 3
                && pathfinderFeet != null && pathfinderFeet.length == 3
                && physicalPlayerY < waypoint[1]
                && pathfinderFeet[0] == waypoint[0]
                && pathfinderFeet[1] >= waypoint[1] - 1
                && pathfinderFeet[1] <= waypoint[1]
                && pathfinderFeet[2] == waypoint[2]
                && landing[1] == waypoint[1] + 1
                && Math.abs(landing[0] - waypoint[0])
                    + Math.abs(landing[2] - waypoint[2]) == 1;
    }

    static int minimumSafeY(int segmentStartY, int targetY, int[] pendingWaypoint,
                            boolean stagedRoute) {
        if (pendingWaypoint != null) return Math.min(segmentStartY, pendingWaypoint[1]) - 2;
        if (stagedRoute) return Math.min(segmentStartY, targetY) - 1;
        return targetY > segmentStartY ? segmentStartY - 1 : Integer.MIN_VALUE;
    }

    static boolean supportedCarpetFooting(String feetBlock, boolean supportSolid,
                                          String supportBlock, boolean headClear,
                                          boolean upperHeadClear) {
        return feetBlock != null && feetBlock.endsWith("_carpet")
                && supportSolid && supportBlock != null
                && StationWalkRecovery.safeFloor(supportBlock)
                && headClear && upperHeadClear;
    }

    private static long horizontalDistanceSq(int x1, int z1, int x2, int z2) {
        long dx = (long) x1 - x2;
        long dz = (long) z1 - z2;
        return dx * dx + dz * dz;
    }
}
