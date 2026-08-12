package com.zenith.plugin.stashmanager.travel.tunnel.core;

// How a tunnel was originally found.
public enum TunnelDiscovery {
    // Built by this bot on a prior trip.
    SELF_BUILT,
    // Detected by scanning loaded chunk data for the 2×1 air signature.
    SCANNED,
    // Received from a network backend (shared by another bot).
    NETWORK_SHARED
}
