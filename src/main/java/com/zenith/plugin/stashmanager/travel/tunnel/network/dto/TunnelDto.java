package com.zenith.plugin.stashmanager.travel.tunnel.network.dto;

import com.zenith.plugin.stashmanager.travel.tunnel.core.Tunnel;
import com.zenith.plugin.stashmanager.travel.tunnel.core.TunnelDiscovery;
import com.zenith.plugin.stashmanager.travel.tunnel.core.TunnelStatus;
import com.zenith.plugin.stashmanager.travel.tunnel.core.TunnelWaypoint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** JSON transport model for tunnel routes. */
public class TunnelDto {
    public long id = -1;
    public String dimension;
    public int startX;
    public int startZ;
    public int endX;
    public int endZ;
    public int floorY;
    public List<TunnelWaypointDto> waypoints = new ArrayList<>();
    public String discovery;
    public String discoveredAt;
    public String status;
    public double confidence;
    public String lastVerifiedAt;
    public String lastUsedAt;
    public int timesUsed;
    public String networkId;
    public boolean sharedToNetwork;

    public static TunnelDto fromTunnel(Tunnel tunnel) {
        TunnelDto dto = new TunnelDto();
        dto.id = tunnel.id;
        dto.dimension = tunnel.dimension;
        dto.startX = tunnel.startX;
        dto.startZ = tunnel.startZ;
        dto.endX = tunnel.endX;
        dto.endZ = tunnel.endZ;
        dto.floorY = tunnel.floorY;
        for (TunnelWaypoint waypoint : tunnel.waypoints) {
            dto.waypoints.add(TunnelWaypointDto.from(waypoint));
        }
        dto.discovery = tunnel.discovery.name().toLowerCase();
        dto.discoveredAt = tunnel.discoveredAt != null ? tunnel.discoveredAt.toString() : null;
        dto.status = tunnel.status.name().toLowerCase();
        dto.confidence = tunnel.confidence;
        dto.lastVerifiedAt = tunnel.lastVerifiedAt != null ? tunnel.lastVerifiedAt.toString() : null;
        dto.lastUsedAt = tunnel.lastUsedAt != null ? tunnel.lastUsedAt.toString() : null;
        dto.timesUsed = tunnel.timesUsed;
        dto.networkId = tunnel.networkId;
        dto.sharedToNetwork = tunnel.sharedToNetwork;
        return dto;
    }

    public Tunnel toTunnel() {
        Tunnel tunnel = new Tunnel();
        tunnel.id = id;
        tunnel.dimension = dimension != null ? dimension : tunnel.dimension;
        tunnel.startX = startX;
        tunnel.startZ = startZ;
        tunnel.endX = endX;
        tunnel.endZ = endZ;
        tunnel.floorY = floorY;
        tunnel.discovery = parseDiscovery(discovery);
        tunnel.discoveredAt = parseInstant(discoveredAt, tunnel.discoveredAt);
        tunnel.status = parseStatus(status);
        tunnel.confidence = confidence;
        tunnel.lastVerifiedAt = parseInstant(lastVerifiedAt, null);
        tunnel.lastUsedAt = parseInstant(lastUsedAt, null);
        tunnel.timesUsed = timesUsed;
        tunnel.networkId = networkId;
        tunnel.sharedToNetwork = sharedToNetwork;
        tunnel.waypoints.clear();
        for (TunnelWaypointDto waypoint : waypoints) {
            tunnel.waypoints.add(waypoint.toDomain());
        }
        return tunnel;
    }

    public String key() {
        return (dimension == null ? "" : dimension) + "|" + floorY + "|" + startX + "," + startZ + "|" + endX + "," + endZ;
    }

    private static TunnelDiscovery parseDiscovery(String value) {
        if (value == null || value.isBlank()) return TunnelDiscovery.NETWORK_SHARED;
        try {
            return TunnelDiscovery.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return TunnelDiscovery.NETWORK_SHARED;
        }
    }

    private static TunnelStatus parseStatus(String value) {
        if (value == null || value.isBlank()) return TunnelStatus.UNVERIFIED;
        try {
            return TunnelStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return TunnelStatus.UNVERIFIED;
        }
    }

    private static Instant parseInstant(String value, Instant fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Instant.parse(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}