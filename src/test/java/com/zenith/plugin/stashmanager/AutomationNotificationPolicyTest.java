package com.zenith.plugin.stashmanager;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationNotificationPolicyTest {
    @Test
    void incompleteKitSendsOneTerminalNoticeNotANoticePerBox() {
        assertFalse(AutomationNotificationPolicy.sendGenericDiscord(
                "kit_fulfillment_box_marked_incomplete", Map.of("kit", "cpvp")));
        assertFalse(AutomationNotificationPolicy.sendGenericDiscord(
                "kit_fulfillment_box_delivered", Map.of("kit", "cpvp")));
        assertTrue(AutomationNotificationPolicy.sendGenericDiscord(
                "kit_fulfillment_partial", Map.of("kit", "cpvp",
                        "missing_by_item", Map.of("enchanted_golden_apple", 18))));
    }
}
