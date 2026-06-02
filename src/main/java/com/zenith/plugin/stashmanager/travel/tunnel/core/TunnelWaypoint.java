package com.zenith.plugin.stashmanager.travel.tunnel.core;

/**
 * A single XZ waypoint along a tunnel's horizontal path.
 * Y is recorded for tunnels that deviate from a fixed floor (e.g. around a
 * lava lake), but most straight tunnels share the same Y as the parent tunnel.
 */
public record TunnelWaypoint(int x, int y, int z, int sequence) {

    /** Horizontal (XZ) distance to another waypoint. */
    public double horizontalDistanceTo(TunnelWaypoint other) {
        int dx = other.x - this.x;
        int dz = other.z - this.z;
        return Math.sqrt(dx * (double) dx + dz * (double) dz);
    }

    @Override
    public String toString() {
        return "[" + x + "," + y + "," + z + "]";
    }
}
