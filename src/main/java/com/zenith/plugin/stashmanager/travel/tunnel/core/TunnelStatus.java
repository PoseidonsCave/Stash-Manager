package com.zenith.plugin.stashmanager.travel.tunnel.core;

// Observed health of a tunnel route.
public enum TunnelStatus {
    // All sampled blocks match the expected air signature.
    INTACT,
    // Some blocks are blocked (partially griefed/collapsed).
    PARTIAL,
    // Route is unusable — significant blockage detected.
    COMPROMISED,
    // Never verified since discovery (treat as lower confidence).
    UNVERIFIED
}
