package com.zenith.plugin.stashmanager.travel.tunnel.network.sync;

import com.zenith.plugin.stashmanager.travel.tunnel.core.Tunnel;
import com.zenith.plugin.stashmanager.travel.tunnel.core.TunnelStatus;
import com.zenith.plugin.stashmanager.travel.tunnel.core.TunnelWaypoint;

import java.time.Instant;

/** Conservative conflict resolver for local vs remote tunnel data. */
public final class ConflictResolver {

    public String key(Tunnel tunnel) {
        return tunnel.dimension + "|" + tunnel.floorY + "|" + tunnel.startX + "," + tunnel.startZ + "|" + tunnel.endX + "," + tunnel.endZ;
    }

    public Tunnel merge(Tunnel local, Tunnel remote) {
        if (local == null) return copy(remote);
        if (remote == null) return copy(local);

        boolean preferRemote = shouldPreferRemote(local, remote);
        Tunnel chosen = copy(preferRemote ? remote : local);

        if (!preferRemote && chosen.waypoints.isEmpty() && !remote.waypoints.isEmpty()) {
            chosen.waypoints.clear();
            chosen.waypoints.addAll(remote.waypoints);
        }

        if (chosen.networkId == null || chosen.networkId.isBlank()) {
            chosen.networkId = remote.networkId != null && !remote.networkId.isBlank() ? remote.networkId : local.networkId;
        }
        chosen.sharedToNetwork = local.sharedToNetwork || remote.sharedToNetwork;
        if (chosen.discoveredAt == null) {
            chosen.discoveredAt = local.discoveredAt != null ? local.discoveredAt : remote.discoveredAt;
        }
        if (chosen.lastUsedAt == null) {
            chosen.lastUsedAt = later(local.lastUsedAt, remote.lastUsedAt);
        }
        if (chosen.lastVerifiedAt == null) {
            chosen.lastVerifiedAt = later(local.lastVerifiedAt, remote.lastVerifiedAt);
        }
        chosen.timesUsed = Math.max(local.timesUsed, remote.timesUsed);
        return chosen;
    }

    private boolean shouldPreferRemote(Tunnel local, Tunnel remote) {
        if (local.status == TunnelStatus.COMPROMISED && remote.status != TunnelStatus.COMPROMISED) return true;
        if (remote.waypoints.size() > local.waypoints.size()) return true;
        if (remote.confidence > local.confidence + 0.05) return true;
        if (later(remote.lastVerifiedAt, local.lastVerifiedAt) == remote.lastVerifiedAt && remote.lastVerifiedAt != null) return true;
        if ((local.networkId == null || local.networkId.isBlank()) && remote.networkId != null && !remote.networkId.isBlank()) return true;
        return false;
    }

    private Tunnel copy(Tunnel source) {
        Tunnel copy = new Tunnel();
        copy.id = source.id;
        copy.dimension = source.dimension;
        copy.startX = source.startX;
        copy.startZ = source.startZ;
        copy.endX = source.endX;
        copy.endZ = source.endZ;
        copy.floorY = source.floorY;
        copy.discovery = source.discovery;
        copy.discoveredAt = source.discoveredAt;
        copy.status = source.status;
        copy.confidence = source.confidence;
        copy.lastVerifiedAt = source.lastVerifiedAt;
        copy.lastUsedAt = source.lastUsedAt;
        copy.timesUsed = source.timesUsed;
        copy.networkId = source.networkId;
        copy.sharedToNetwork = source.sharedToNetwork;
        copy.waypoints.clear();
        for (TunnelWaypoint waypoint : source.waypoints) {
            copy.waypoints.add(new TunnelWaypoint(waypoint.x(), waypoint.y(), waypoint.z(), waypoint.sequence()));
        }
        return copy;
    }

    private Instant later(Instant left, Instant right) {
        if (left == null) return right;
        if (right == null) return left;
        return right.isAfter(left) ? right : left;
    }
}