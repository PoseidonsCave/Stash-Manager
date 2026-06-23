package com.zenith.plugin.stashmanager.travel;

import java.util.concurrent.atomic.AtomicLong;

/** Immutable description of a travel job, with optional delivery parameters. */
public final class TravelMission {

    private static final AtomicLong ID_GEN = new AtomicLong(1);

    /** Final destination coordinates [x, y, z]. */
    public final int[] destination;

    /**
     * If true, travel automatically re-plans and resumes from the current position
     * after a non-user abort. Up to MAX_AUTO_RESUME_ATTEMPTS retries.
     */
    public final boolean autoResume;

    /** Unique identifier for telemetry correlation. */
    public final long id;

    // ── Delivery-specific fields ────────────────────────────────

    /** If true, this is a delivery mission (not just travel). */
    public final boolean isDelivery;

    /** Item IDs to gather and deliver (e.g., "minecraft:diamond"). */
    public final String[] itemIds;

    /** Quantities for each item (parallel to itemIds). */
    public final int[] quantities;

    /** If true, destroy portal after entering nether (overworld mode). */
    public final boolean destroyPortalAfterUse;

    /** If true, set home position before starting travel. */
    public final boolean setHomeBeforeTravel;

    /** Discord webhook URL for delivery notifications (null if disabled). */
    public final String discordWebhook;

    private TravelMission(Builder b) {
        this.destination          = b.destination;
        this.autoResume           = b.autoResume;
        this.isDelivery           = b.isDelivery;
        this.itemIds              = b.itemIds;
        this.quantities           = b.quantities;
        this.destroyPortalAfterUse = b.destroyPortalAfterUse;
        this.setHomeBeforeTravel  = b.setHomeBeforeTravel;
        this.discordWebhook       = b.discordWebhook;
        this.id                   = ID_GEN.getAndIncrement();
    }

    public static Builder to(int x, int z) {
        return new Builder(new int[]{x, 120, z});
    }

    public static Builder to(int x, int y, int z) {
        return new Builder(new int[]{x, y, z});
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TravelMission#").append(id)
                .append("{dest=[").append(destination[0]).append(",")
                .append(destination[1]).append(",").append(destination[2]).append("]")
                .append(", autoResume=").append(autoResume);
        if (isDelivery) {
            sb.append(", delivery=true, items=").append(itemIds != null ? itemIds.length : 0);
        }
        return sb.append("}").toString();
    }

    public static final class Builder {
        private final int[] destination;
        private boolean autoResume = true;

        // Delivery fields
        private boolean isDelivery = false;
        private String[] itemIds = null;
        private int[] quantities = null;
        private boolean destroyPortalAfterUse = true;
        private boolean setHomeBeforeTravel = true;
        private String discordWebhook = null;

        private Builder(int[] destination) {
            this.destination = destination;
        }

        public Builder autoResume(boolean v) { this.autoResume = v; return this; }

        public Builder asDelivery(String[] itemIds, int[] quantities) {
            this.isDelivery = true;
            this.itemIds    = itemIds;
            this.quantities = quantities;
            return this;
        }

        public Builder destroyPortalAfterUse(boolean v) { this.destroyPortalAfterUse = v; return this; }
        public Builder setHomeBeforeTravel(boolean v)   { this.setHomeBeforeTravel = v; return this; }
        public Builder discordWebhook(String url)       { this.discordWebhook = url; return this; }

        public TravelMission build() {
            if (isDelivery && (itemIds == null || quantities == null
                    || itemIds.length != quantities.length)) {
                throw new IllegalStateException("Delivery mission must have matching itemIds and quantities");
            }
            return new TravelMission(this);
        }
    }
}

