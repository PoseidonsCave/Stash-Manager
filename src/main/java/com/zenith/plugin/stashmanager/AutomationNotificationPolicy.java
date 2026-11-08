package com.zenith.plugin.stashmanager;

import java.util.Map;

/** Keeps high-volume automation telemetry in console/debug instead of Discord. */
final class AutomationNotificationPolicy {
    private AutomationNotificationPolicy() {}

    static boolean sendGenericDiscord(String event, Map<String, Object> payload) {
        if (event == null) return false;
        if ("stash_food_contingency".equals(String.valueOf(
                payload == null ? null : payload.get("request_name")))) return false;
        return switch (event) {
            case "organize_started",
                 "organize_start_blocked",
                 "organize_planning_blocked",
                 "organize_checkpoint_failed",
                 "organize_checkpoint_invalid",
                 "kit_fulfillment_started",
                 "kit_fulfillment_completed",
                 "kit_fulfillment_partial",
                 "kit_fulfillment_blocked",
                 "kit_recovery_blocked",
                 "kit_recovery_manual_review",
                 "retrieve_started",
                 "retrieve_no_targets" -> true;
            default -> false;
        };
    }
}
