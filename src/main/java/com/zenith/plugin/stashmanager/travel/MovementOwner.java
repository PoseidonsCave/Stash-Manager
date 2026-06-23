package com.zenith.plugin.stashmanager.travel;

/** Identifies which subsystem currently owns player movement. */
public enum MovementOwner {
    NONE,
    BARITONE,
    TUNNEL,   // TunnelManager owns movement (building or traversing)
    DELIVERY  // Delivery operation owns movement
}
