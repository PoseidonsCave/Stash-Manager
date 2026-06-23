package com.zenith.plugin.stashmanager.travel.tunnel.builder;

/** Three-phase tunnel dig states. */
public enum TunnelBuildPhase {
    /** Not yet started. */
    IDLE,
    /** Digging down to floorY. */
    DESCENDING,
    /** Mining horizontally to destination. */
    TRAVERSING,
    /** Digging up to surface at destination. */
    ASCENDING,
    /** Build completed successfully. */
    COMPLETE,
    /** Build failed (stuck, timeout, or error). */
    FAILED
}
