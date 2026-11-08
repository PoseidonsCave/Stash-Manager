package com.zenith.plugin.stashmanager;

import com.zenith.discord.Embed;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StashManagerNotificationsExposureTest {
    @Test
    void routineNotificationsOmitCoordinatesNamesAndRawReasonsByDefault() {
        var config = new StashManagerConfig();
        var messages = new ArrayList<Embed>();
        var notifications = new StashManagerNotifications(config, messages::add);

        emitSensitiveNotifications(notifications);
        String output = flatten(messages);
        assertFalse(output.contains("12345678.5"));
        assertFalse(output.contains("private-request"));
        assertFalse(output.contains("private-reason"));
        assertFalse(output.contains("private-player"));
        assertTrue(output.contains("Return to Position Failed"));
    }

    @Test
    void explicitDetailedPayloadOptInIncludesOperatorDetails() {
        var config = new StashManagerConfig();
        config.webhookIncludeDetailedPayload = true;
        var messages = new ArrayList<Embed>();
        emitSensitiveNotifications(new StashManagerNotifications(config, messages::add));

        String output = flatten(messages);
        assertTrue(output.contains("12345678.5"));
        assertTrue(output.contains("private-request"));
        assertTrue(output.contains("private-reason"));
        assertTrue(output.contains("private-player"));
    }

    private static void emitSensitiveNotifications(StashManagerNotifications notifications) {
        notifications.sendReturnToStartCompleted(12345678.5, 70, -87654321.5);
        notifications.sendReturnToStartFailed(12345678.5, 70, -87654321.5, 12);
        notifications.sendRetrievalFinished("private-request", false, 1, 2, 3, "private-reason");
        notifications.sendOrganizerFailed(1, 2, "private-reason", true, true, "inventory");
        notifications.sendFoodContingencyBlocked("kit", "private-reason");
        notifications.sendProxyControlWarning("private-player", "kit", 30, 60, false);
        notifications.sendProxyControlReleased("private-player", "kit");
        notifications.sendProxyControlAbort("private-player", "kit", 30);
        notifications.sendProxyControlKitReview("private-player", 30);
    }

    private static String flatten(List<Embed> messages) {
        StringBuilder result = new StringBuilder();
        for (Embed message : messages) {
            result.append(message.title()).append(message.description());
            for (Embed.Field field : message.fields()) {
                result.append(field.name()).append(field.value());
            }
        }
        return result.toString();
    }
}
