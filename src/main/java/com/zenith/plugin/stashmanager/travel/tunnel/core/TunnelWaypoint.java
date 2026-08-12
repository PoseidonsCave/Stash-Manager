package com.zenith.plugin.stashmanager.travel.tunnel.core;

// Records an ordered waypoint along a tunnel path.
public record TunnelWaypoint(int x, int y, int z, int sequence) {

    // Horizontal (XZ) distance to another waypoint.
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
