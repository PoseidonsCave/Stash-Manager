package com.zenith.plugin.stashmanager;

import java.util.Map;

/** Keeps high-volume automation telemetry in console/debug instead of Discord. */
final class AutomationNotificationPolicy {
    private AutomationNotificationPolicy() {}

    static boolean sendGenericDiscord(String event, Map<String, Object> payload) {
        if (event == null) return false;
        return switch (event) {
            case "organize_started",
                 "organize_start_blocked",
                 "organize_planning_blocked",
                 "organize_checkpoint_failed",
                 "organize_checkpoint_invalid",
                 "retrieve_started",
                 "retrieve_no_targets" -> true;
            default -> false;
        };
    }
}
