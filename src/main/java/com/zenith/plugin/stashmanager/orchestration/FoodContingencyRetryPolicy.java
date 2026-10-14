package com.zenith.plugin.stashmanager.orchestration;

/** Bounds food retrieval retries to a quiet bot back at its scanned stash. */
public final class FoodContingencyRetryPolicy {
    public static final int RETRY_DELAY_TICKS = 20 * 60 * 5;
    public static final int MAX_RETRIES = 3;

    private FoodContingencyRetryPolicy() {}

    public static boolean shouldRetry(String failureReason, int blockedTicks, int retries,
                                      boolean nearStash, boolean botAvailable,
                                      boolean automationQuiet) {
        // A missing or occupied work pad will not repair itself while the parent job is paused.
        if (failureReason != null && (failureReason.endsWith("shulker_no_place_spot")
                || failureReason.endsWith("shulker_worksite_unavailable")
                || failureReason.endsWith("shulker_invalid_worksite"))) return false;
        boolean transientFailure = "bot_outside_stash_region".equals(failureReason)
                || failureReason != null && failureReason.startsWith("no_food_retrieved_");
        return transientFailure && blockedTicks >= RETRY_DELAY_TICKS
                && retries < MAX_RETRIES && nearStash && botAvailable && automationQuiet;
    }
}
