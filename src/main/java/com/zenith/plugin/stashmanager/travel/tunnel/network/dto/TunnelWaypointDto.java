package com.zenith.plugin.stashmanager.travel.tunnel.network.dto;

import com.zenith.plugin.stashmanager.travel.tunnel.core.TunnelWaypoint;

// Transport form of a tunnel waypoint.
public class TunnelWaypointDto {
    public int x;
    public int y;
    public int z;
    public int sequence;

    public static TunnelWaypointDto from(TunnelWaypoint waypoint) {
        TunnelWaypointDto dto = new TunnelWaypointDto();
        dto.x = waypoint.x();
        dto.y = waypoint.y();
        dto.z = waypoint.z();
        dto.sequence = waypoint.sequence();
        return dto;
    }

    public TunnelWaypoint toDomain() {
        return new TunnelWaypoint(x, y, z, sequence);
    }
}