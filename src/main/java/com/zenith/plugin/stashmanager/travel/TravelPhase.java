package com.zenith.plugin.stashmanager.travel;

/** Phases of one travel mission. */
public enum TravelPhase {
    /** No mission active. */
    IDLE,
    /** TravelManager is looking up or building a tunnel route. */
    PLANNING,
    /** TunnelManager is scanning loaded chunks and checking the database for a usable route. */
    TUNNEL_PLANNING,
    /** Bot is traversing the bedrock-floor tunnel toward the destination. */
    TUNNEL_TRAVERSE,

    // ── Delivery-specific phases ─────────────────────────────────
    /** Delivery system initialization and order validation. */
    DELIVERY_INIT,
    /** Gathering items from stash using StashRetriever. */
    GATHERING,
    /** Detecting current dimension (overworld vs nether). */
    DIM_DETECT,
    /** Setting home position (bed in overworld, respawn anchor in nether). */
    HOME_SETUP,
    /** Building nether portal (overworld only). */
    PORTAL_CREATE,
    /** Entering the nether portal. */
    PORTAL_ENTER,
    /** Mining the portal completely (no obsidian left behind). */
    PORTAL_DESTROY,
    /** Mining/walking from tunnel exit to exact delivery destination. */
    MINING_TO_DEST,
    /** Depositing items into nearby chests at destination. */
    DELIVERY,
    /** Executing /kill to return home. */
    RETURN_HOME,

    // ── Terminal phases ──────────────────────────────────────────
    /** Mission finished successfully. */
    ARRIVED,
    /** Mission aborted (user stop, planner failure, stuck, etc.). */
    ABORTED,
    /** User-requested pause. */
    PAUSED;

    /** True for terminal phases that automatically transition back to IDLE. */
    public boolean isTerminal() {
        return this == ARRIVED || this == ABORTED;
    }

    /** True for tunnel-related phases. */
    public boolean isTunnelPhase() {
        return this == TUNNEL_PLANNING || this == TUNNEL_TRAVERSE;
    }

    /** True for delivery-specific phases. */
    public boolean isDeliveryPhase() {
        return this == DELIVERY_INIT || this == GATHERING || this == DIM_DETECT
                || this == HOME_SETUP || this == PORTAL_CREATE || this == PORTAL_ENTER
                || this == PORTAL_DESTROY || this == MINING_TO_DEST
                || this == DELIVERY || this == RETURN_HOME;
    }
}

