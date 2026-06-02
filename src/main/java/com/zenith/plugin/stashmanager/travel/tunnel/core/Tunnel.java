package com.zenith.plugin.stashmanager.travel.tunnel.core;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// A known 2×1 air corridor through netherrack at floorY.
// Waypoints describe turns; empty list means straight tunnel.
public final class Tunnel {

    // ── Identity ─────────────────────────────────────────────────────────────

    /** Database row id. -1 for unsaved instances. */
    public long id = -1;

    /** The nether dimension key (always "minecraft:the_nether" for now). */
    public String dimension = "minecraft:the_nether";

    // ── Geometry ─────────────────────────────────────────────────────────────

    public int startX;
    public int startZ;
    public int endX;
    public int endZ;

    /** Y level of the tunnel floor (typically 8 — above bedrock, below lava). */
    public int floorY = 8;

    /**
     * Ordered intermediate waypoints. Empty for simple point-to-point tunnels.
     * The full path is: start → waypoints[0] → … → waypoints[n] → end.
     */
    public final List<TunnelWaypoint> waypoints = new ArrayList<>();

    // ── Discovery ────────────────────────────────────────────────────────────

    public TunnelDiscovery discovery = TunnelDiscovery.SELF_BUILT;
    public Instant discoveredAt = Instant.now();

    // ── Quality ──────────────────────────────────────────────────────────────

    public TunnelStatus status = TunnelStatus.UNVERIFIED;

    /**
     * 0.0–1.0 confidence that the tunnel is passable.
     * Self-built tunnels start at 1.0; scanned/shared tunnels start lower.
     */
    public double confidence = 1.0;

    // ── Usage tracking ────────────────────────────────────────────────────────

    public @Nullable Instant lastVerifiedAt = null;
    public @Nullable Instant lastUsedAt     = null;
    public int timesUsed = 0;

    // ── Network sync ─────────────────────────────────────────────────────────

    /** Opaque ID assigned by an external backend, null if not yet synced. */
    public @Nullable String networkId = null;
    public boolean sharedToNetwork = false;

    // ── Derived helpers ──────────────────────────────────────────────────────

    /** Total horizontal length in blocks (sum of all segments). */
    public double totalLength() {
        double len = 0;
        int prevX = startX, prevZ = startZ;
        for (TunnelWaypoint wp : waypoints) {
            int dx = wp.x() - prevX;
            int dz = wp.z() - prevZ;
            len += Math.sqrt(dx * (double) dx + dz * (double) dz);
            prevX = wp.x();
            prevZ = wp.z();
        }
        int dx = endX - prevX;
        int dz = endZ - prevZ;
        len += Math.sqrt(dx * (double) dx + dz * (double) dz);
        return len;
    }

    /**
     * Horizontal distance from origin to start of this tunnel.
     * Used to select the closest entry point.
     */
    public double entryDistanceFrom(int x, int z) {
        int dx = startX - x;
        int dz = startZ - z;
        return Math.sqrt(dx * (double) dx + dz * (double) dz);
    }

    /**
     * Horizontal distance from origin to end of this tunnel.
     */
    public double exitDistanceFrom(int x, int z) {
        int dx = endX - x;
        int dz = endZ - z;
        return Math.sqrt(dx * (double) dx + dz * (double) dz);
    }

    // All traversal points in order: start → waypoints → end. Y = floorY+1 (feet).
    public List<int[]> traversalPoints() {
        List<int[]> pts = new ArrayList<>();
        pts.add(new int[]{startX, floorY + 1, startZ});
        for (TunnelWaypoint wp : waypoints) {
            pts.add(new int[]{wp.x(), wp.y(), wp.z()});
        }
        pts.add(new int[]{endX, floorY + 1, endZ});
        return Collections.unmodifiableList(pts);
    }

    /** Mark a successful use: update counters and status. */
    public void recordUse() {
        timesUsed++;
        lastUsedAt = Instant.now();
        // Treat every successful traversal as lightweight verification
        if (status == TunnelStatus.UNVERIFIED) {
            status = TunnelStatus.INTACT;
            confidence = Math.min(1.0, confidence + 0.1);
        }
    }

    @Override
    public String toString() {
        return "Tunnel{id=%d, [%d,%d]→[%d,%d] @y=%d, %.0f blks, %s, conf=%.2f}"
                .formatted(id, startX, startZ, endX, endZ, floorY,
                        totalLength(), status, confidence);
    }
}
