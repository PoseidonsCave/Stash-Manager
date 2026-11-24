package com.zenith.plugin.stashmanager.organizer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

/**
 * Bounded route discovery over loaded, verified standing cells and ladders.
 * The destination comes from the organizer task; no site coordinates are configured here.
 */
final class DiscoveredVerticalRoute {
    private static final int MAX_HORIZONTAL_DISTANCE = 96;
    private static final int MAX_VERTICAL_SPAN = 32;
    // A safe path to a lower floor can first climb to a higher connecting floor.
    // Keep that search bounded without assuming any particular building layout.
    private static final int MAX_UPPER_FLOOR_DETOUR = 8;
    // A chest can be interacted with from a verified standing floor below its block Y.
    // The target is a container, not the player's eventual standing coordinate.
    private static final int MAX_LOWER_ACCESS_DETOUR = 3;
    private static final int MAX_VISITED = 30_000;
    private static final int MAX_STAGES = 64;
    private static final int MAX_STAGE_STEPS = 4;
    private static final int MAX_TRANSIT_STEPS = 12;

    enum Cell { UNKNOWN, BLOCKED, STANDING, LADDER, BOTTOM_SLAB, CONTAINER_TOP }

    interface WorldView {
        Cell cell(StationWalkRecovery.Point point);
        boolean canAccess(StationWalkRecovery.Point point, StationWalkRecovery.Point target);
        boolean safeLadderLip(StationWalkRecovery.Point standing, StationWalkRecovery.Point ladder);
    }

    record Plan(List<StationWalkRecovery.Point> stages, int visited) { }
    record SearchResult(Plan plan, String reason, int visited, int processed,
                        int nearTargetStanding, int nearestDistance,
                        int lowestY, int highestY, Map<Cell, Integer> classified) { }
    record NearbyStartResult(SearchResult search, StationWalkRecovery.Point bootstrap) { }

    private DiscoveredVerticalRoute() { }

    static Plan find(StationWalkRecovery.Point start, StationWalkRecovery.Point target,
                     WorldView world) {
        return inspect(start, target, world).plan();
    }

    /**
     * A player's center may enter a thin railing's block coordinate while still standing on
     * the adjacent floor. Only in that grounded, unusable-start case, try a verified standing
     * cell on the same floor within one short step of the actual pose. The first waypoint is
     * the candidate itself, so Baritone must validate and traverse the bootstrap before any
     * later vertical stage; this never treats an unverified cell as an arrived waypoint.
     */
    static NearbyStartResult inspectFromGroundedPose(double playerX, double playerY,
                                                      double playerZ, boolean onGround,
                                                      StationWalkRecovery.Point start,
                                                      StationWalkRecovery.Point target,
                                                      WorldView world) {
        SearchResult direct = inspect(start, target, world);
        if (!onGround || !"start_unusable".equals(direct.reason())
                || Math.abs(playerY - start.y()) > 0.25) {
            return new NearbyStartResult(direct, null);
        }
        record Candidate(StationWalkRecovery.Point point, double distanceSquared) { }
        List<Candidate> candidates = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                var point = new StationWalkRecovery.Point(start.x() + dx, start.y(),
                        start.z() + dz);
                double horizontal = Math.pow(point.x() + 0.5 - playerX, 2)
                        + Math.pow(point.z() + 0.5 - playerZ, 2);
                if (horizontal <= 1.25 * 1.25 && world.cell(point) == Cell.STANDING) {
                    candidates.add(new Candidate(point, horizontal));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::distanceSquared));
        for (Candidate candidate : candidates) {
            SearchResult inspected = inspect(candidate.point(), target, world);
            if (inspected.plan() == null || inspected.plan().stages().size() >= MAX_STAGES) continue;
            List<StationWalkRecovery.Point> stages = new ArrayList<>();
            stages.add(candidate.point());
            stages.addAll(inspected.plan().stages());
            SearchResult selected = new SearchResult(
                    new Plan(List.copyOf(stages), inspected.visited()), "verified_nearby_start",
                    inspected.visited(), inspected.processed(), inspected.nearTargetStanding(),
                    inspected.nearestDistance(), inspected.lowestY(), inspected.highestY(),
                    inspected.classified());
            return new NearbyStartResult(selected, candidate.point());
        }
        return new NearbyStartResult(direct, null);
    }

    /** Reports why the existing conservative route search stopped, without relaxing it. */
    static SearchResult inspect(StationWalkRecovery.Point start, StationWalkRecovery.Point target,
                                WorldView world) {
        if (start == null || target == null || world == null) {
            return result(null, "missing_input", 0, 0, 0, Integer.MAX_VALUE,
                    0, 0, Map.of());
        }
        int verticalSpan = Math.abs(target.y() - start.y());
        if (verticalSpan < 3 || verticalSpan > MAX_VERTICAL_SPAN) {
            return result(null, "vertical_span_out_of_range", 0, 0, 0,
                    distance(start, target), start.y(), start.y(), Map.of());
        }
        Cell startCell = world.cell(start);
        if (!usable(startCell)) {
            return result(null, "start_unusable", 0, 0, 0,
                    distance(start, target), start.y(), start.y(), Map.of(start, startCell));
        }

        // The same cell may be reached by a short but unstageable slab run or by a
        // slightly longer route with a normal standing point. Preserve the bounded
        // transit run in the search key so the first arrival does not discard the
        // safe alternative.
        record SearchNode(StationWalkRecovery.Point point, int transitRun) { }
        SearchNode first = new SearchNode(start, transitOnly(startCell) ? 1 : 0);
        ArrayDeque<SearchNode> queue = new ArrayDeque<>();
        Map<SearchNode, SearchNode> previous = new HashMap<>();
        Map<StationWalkRecovery.Point, Cell> cells = new HashMap<>();
        queue.add(first);
        previous.put(first, null);
        cells.put(start, startCell);
        int processed = 0;
        int nearTargetStanding = 0;
        int nearestDistance = distance(start, target);
        int lowestY = start.y();
        int highestY = start.y();
        boolean unstageableCandidate = false;
        while (!queue.isEmpty() && previous.size() <= MAX_VISITED) {
            SearchNode currentNode = queue.removeFirst();
            var current = currentNode.point();
            processed++;
            nearestDistance = Math.min(nearestDistance, distance(current, target));
            lowestY = Math.min(lowestY, current.y());
            highestY = Math.max(highestY, current.y());
            Cell currentCell = cells.get(current);
            if (currentCell == Cell.STANDING && closeToTarget(current, target)) {
                nearTargetStanding++;
                if (world.canAccess(current, target)) {
                    List<StationWalkRecovery.Point> path = new ArrayList<>();
                    for (SearchNode node = currentNode; node != null; node = previous.get(node)) {
                        path.add(node.point());
                    }
                    Collections.reverse(path);
                    List<StationWalkRecovery.Point> stages = stages(path, cells);
                    if (stages.isEmpty() || stages.size() > MAX_STAGES) {
                        unstageableCandidate = true;
                    } else {
                        return result(new Plan(List.copyOf(stages), previous.size()), "verified",
                                previous.size(), processed, nearTargetStanding, nearestDistance,
                                lowestY, highestY, cells);
                    }
                }
            }
            for (int[] step : new int[][]{
                    {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
                    {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
                    {1, -1, 0}, {-1, -1, 0}, {0, -1, 1}, {0, -1, -1},
                    {0, 1, 0}, {0, -1, 0}}) {
                var next = new StationWalkRecovery.Point(current.x() + step[0],
                        current.y() + step[1], current.z() + step[2]);
                if (!withinBounds(start, target, next)) continue;
                Cell nextCell = cells.computeIfAbsent(next, world::cell);
                if (!edgeAllowed(current, currentCell, next, nextCell, step, world)) continue;
                int transitRun = transitOnly(nextCell) ? currentNode.transitRun() + 1 : 0;
                if (transitRun > MAX_TRANSIT_STEPS) continue;
                SearchNode nextNode = new SearchNode(next, transitRun);
                if (previous.containsKey(nextNode)) continue;
                previous.put(nextNode, currentNode);
                queue.addLast(nextNode);
            }
        }
        return result(null, previous.size() > MAX_VISITED ? "visit_limit"
                        : unstageableCandidate ? "stage_limit" : "search_exhausted",
                previous.size(), processed, nearTargetStanding, nearestDistance,
                lowestY, highestY, cells);
    }

    private static SearchResult result(Plan plan, String reason, int visited, int processed,
                                       int nearTargetStanding, int nearestDistance,
                                       int lowestY, int highestY,
                                       Map<StationWalkRecovery.Point, Cell> cells) {
        var classified = new EnumMap<Cell, Integer>(Cell.class);
        for (Cell cell : cells.values()) classified.merge(cell, 1, Integer::sum);
        return new SearchResult(plan, reason, visited, processed, nearTargetStanding,
                nearestDistance, lowestY, highestY, Map.copyOf(classified));
    }

    private static int distance(StationWalkRecovery.Point from, StationWalkRecovery.Point to) {
        return Math.abs(from.x() - to.x()) + Math.abs(from.y() - to.y())
                + Math.abs(from.z() - to.z());
    }

    private static boolean usable(Cell cell) {
        return cell == Cell.STANDING || cell == Cell.LADDER || cell == Cell.BOTTOM_SLAB
                || cell == Cell.CONTAINER_TOP;
    }

    private static boolean transitOnly(Cell cell) {
        return cell == Cell.BOTTOM_SLAB || cell == Cell.CONTAINER_TOP;
    }

    private static boolean edgeAllowed(StationWalkRecovery.Point fromPoint, Cell from,
                                       StationWalkRecovery.Point toPoint, Cell to,
                                       int[] step, WorldView world) {
        if (!usable(to)) return false;
        int vertical = step[1];
        if (step[0] == 0 && step[2] == 0) {
            // A same-column climb is valid only inside one continuous ladder.
            return from == Cell.LADDER && to == Cell.LADDER;
        }
        if (from == Cell.STANDING && to == Cell.LADDER && vertical == -1) {
            return world.safeLadderLip(fromPoint, toPoint);
        }
        if (from == Cell.LADDER && to == Cell.STANDING && vertical == 1) {
            return world.safeLadderLip(toPoint, fromPoint);
        }
        if (from == Cell.LADDER || to == Cell.LADDER) return vertical == 0;
        // Bottom slabs and container tops are transit-only surfaces. Baritone may
        // walk off a chest/hopper top, but the route must reach a normal verified
        // standing cell before selecting an integer-height waypoint goal.
        return Math.abs(surfaceHalfY(fromPoint, from) - surfaceHalfY(toPoint, to)) <= 2;
    }

    private static int surfaceHalfY(StationWalkRecovery.Point point, Cell cell) {
        return point.y() * 2 + (cell == Cell.BOTTOM_SLAB ? 1 : 0);
    }

    private static boolean withinBounds(StationWalkRecovery.Point start,
                                        StationWalkRecovery.Point target,
                                        StationWalkRecovery.Point next) {
        int lowY = Math.min(start.y(), target.y()) - MAX_LOWER_ACCESS_DETOUR;
        int highY = Math.max(start.y(), target.y()) + MAX_UPPER_FLOOR_DETOUR;
        return next.y() >= lowY && next.y() <= highY
                && Math.abs((long) next.x() - start.x()) <= MAX_HORIZONTAL_DISTANCE
                && Math.abs((long) next.z() - start.z()) <= MAX_HORIZONTAL_DISTANCE
                && Math.abs((long) next.x() - target.x()) <= MAX_HORIZONTAL_DISTANCE
                && Math.abs((long) next.z() - target.z()) <= MAX_HORIZONTAL_DISTANCE;
    }

    private static boolean closeToTarget(StationWalkRecovery.Point point,
                                         StationWalkRecovery.Point target) {
        return Math.abs(point.x() - target.x()) <= 4
                && Math.abs(point.z() - target.z()) <= 4
                && Math.abs(point.y() - target.y()) <= 3;
    }

    private static List<StationWalkRecovery.Point> stages(
            List<StationWalkRecovery.Point> path,
            Map<StationWalkRecovery.Point, Cell> cells) {
        List<StationWalkRecovery.Point> stages = new ArrayList<>();
        int last = 0;
        for (int i = 1; i < path.size(); i++) {
            var before = path.get(i - 1);
            var current = path.get(i);
            if (cells.get(current) == Cell.BOTTOM_SLAB
                    || cells.get(current) == Cell.CONTAINER_TOP) {
                if (i - last > MAX_TRANSIT_STEPS) return List.of();
                continue;
            }
            boolean ladderBoundary = cells.get(before) != cells.get(current);
            // A pathfinder request spanning several ladder blocks can complete without
            // advancing the live player at the ladder foot. Require a separately
            // verified arrival at each loaded rung before requesting the next one.
            boolean ladderRung = cells.get(before) == Cell.LADDER
                    && cells.get(current) == Cell.LADDER
                    && before.y() != current.y();
            boolean verticalTransition = before.y() != current.y()
                    && (i + 1 == path.size()
                    || path.get(i + 1).y() - current.y() != current.y() - before.y());
            boolean turn = i + 1 < path.size()
                    && ((current.x() - before.x() != path.get(i + 1).x() - current.x())
                    || (current.z() - before.z() != path.get(i + 1).z() - current.z()));
            if (ladderBoundary || ladderRung || verticalTransition || turn || i - last >= MAX_STAGE_STEPS
                    || i == path.size() - 1) {
                stages.add(current);
                last = i;
            }
        }
        return stages;
    }
}
