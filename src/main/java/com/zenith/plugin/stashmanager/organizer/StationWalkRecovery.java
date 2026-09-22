package com.zenith.plugin.stashmanager.organizer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Bounded, restart-safe detours without changing the cargo destination. */
final class StationWalkRecovery {
    static final int STALL_TICKS = 200;
    static final int MAX_DETOURS = 3;
    static final int TRIP_TIMEOUT_MULTIPLIER = 4;

    record Point(int x, int y, int z) {
        int[] array() { return new int[]{x, y, z}; }
    }

    record Position(double x, double y, double z) {
        Point block() { return new Point((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)); }
        double distance(Position other) {
            return Math.sqrt(Math.pow(x - other.x, 2) + Math.pow(y - other.y, 2) + Math.pow(z - other.z, 2));
        }
        double distance(Point other) {
            return distance(new Position(other.x + 0.5, other.y, other.z + 0.5));
        }
        boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
    }

    record Snapshot(Point target, Point waypoint, int elapsedTicks, int idleTicks, int attempts,
                    Position motionAnchor, double bestDistance, List<Point> triedWaypoints) {}

    enum Decision { CONTINUE, RECOVER, EXHAUSTED }

    private Point target;
    private Point waypoint;
    private int elapsedTicks;
    private int idleTicks;
    private int attempts;
    private Position motionAnchor;
    private double bestDistance;
    private final Set<Point> triedWaypoints = new LinkedHashSet<>();

    void begin(Point destination, Position position) {
        if (destination.equals(target)) return;
        reset();
        target = destination;
        motionAnchor = position;
        bestDistance = position.distance(destination);
    }

    Decision tick(Position position, int walkTimeoutTicks) {
        elapsedTicks = Math.min(Integer.MAX_VALUE - 1, elapsedTicks) + 1;
        idleTicks = Math.min(Integer.MAX_VALUE - 1, idleTicks) + 1;
        // Small collision jitter must not count as a fresh walking attempt.
        if (motionAnchor == null || position.distance(motionAnchor) >= 1.0) {
            motionAnchor = position;
            idleTicks = 0;
        }
        bestDistance = Math.min(bestDistance, position.distance(target));
        if (elapsedTicks >= tripLimit(walkTimeoutTicks)) return Decision.EXHAUSTED;
        if (idleTicks < Math.min(STALL_TICKS, Math.max(1, walkTimeoutTicks))) return Decision.CONTINUE;
        return attempts < MAX_DETOURS ? Decision.RECOVER : Decision.EXHAUSTED;
    }

    static long tripLimit(int walkTimeoutTicks) {
        return (long) Math.max(1, walkTimeoutTicks) * TRIP_TIMEOUT_MULTIPLIER;
    }

    static boolean safeFloor(String name) {
        return switch (name.replace("minecraft:", "")) {
            case "magma_block", "cactus", "powder_snow", "slime_block", "honey_block" -> false;
            default -> true;
        };
    }

    Point nextDetour(Position position, Predicate<Point> safeCell) {
        Point origin = position.block();
        int dx = target.x - origin.x;
        int dz = target.z - origin.z;
        int forwardX = Math.abs(dx) >= Math.abs(dz) ? Integer.signum(dx) : 0;
        int forwardZ = forwardX == 0 ? Integer.signum(dz) : 0;
        if (forwardX == 0 && forwardZ == 0) forwardX = 1;
        // Step sideways first, then back. Do not replay the stalled forward edge.
        int[][] directions = {{-forwardZ, forwardX}, {forwardZ, -forwardX}, {-forwardX, -forwardZ}};
        for (int length : new int[]{2, 1}) {
            for (int[] direction : directions) {
                Point candidate = new Point(origin.x + direction[0] * length, origin.y,
                        origin.z + direction[1] * length);
                if (candidate.equals(target) || triedWaypoints.contains(candidate)) continue;
                if (safeCorridor(origin, candidate, safeCell)) return candidate;
            }
        }
        return null;
    }

    static boolean safeCorridor(Point from, Point to, Predicate<Point> safeCell) {
        int dx = to.x - from.x;
        int dz = to.z - from.z;
        int length = Math.abs(dx) + Math.abs(dz);
        if (to.y != from.y || (dx != 0 && dz != 0) || length < 1 || length > 2) return false;
        for (int step = 1; step <= length; step++) {
            if (!safeCell.test(new Point(from.x + Integer.signum(dx) * step, from.y,
                    from.z + Integer.signum(dz) * step))) return false;
        }
        return true;
    }

    void detour(Point next, Position position) {
        if (attempts >= MAX_DETOURS || next == null) throw new IllegalStateException("Detour budget exhausted");
        attempts++;
        triedWaypoints.add(next);
        waypoint = next;
        idleTicks = 0;
        motionAnchor = position;
    }

    boolean atWaypoint(Position position) {
        return waypoint != null && waypoint.equals(position.block());
    }

    void finishDetour(Position position) {
        waypoint = null;
        idleTicks = 0;
        motionAnchor = position;
    }

    Point waypoint() { return waypoint; }
    int attempts() { return attempts; }

    Snapshot snapshot() {
        return target == null ? null : new Snapshot(target, waypoint, elapsedTicks, idleTicks, attempts,
                motionAnchor, bestDistance, List.copyOf(triedWaypoints));
    }

    void restore(Snapshot saved) {
        reset();
        if (saved == null || saved.target == null) return;
        target = saved.target;
        waypoint = saved.waypoint;
        elapsedTicks = Math.max(0, saved.elapsedTicks);
        idleTicks = Math.max(0, saved.idleTicks);
        attempts = Math.clamp(saved.attempts, 0, MAX_DETOURS);
        motionAnchor = saved.motionAnchor != null && saved.motionAnchor.finite() ? saved.motionAnchor : null;
        bestDistance = Double.isFinite(saved.bestDistance) ? Math.max(0, saved.bestDistance) : Double.MAX_VALUE;
        if (saved.triedWaypoints != null) saved.triedWaypoints.stream()
                .filter(point -> point != null).limit(MAX_DETOURS).forEach(triedWaypoints::add);
    }

    void reset() {
        target = null;
        waypoint = null;
        elapsedTicks = 0;
        idleTicks = 0;
        attempts = 0;
        motionAnchor = null;
        bestDistance = 0;
        triedWaypoints.clear();
    }
}
