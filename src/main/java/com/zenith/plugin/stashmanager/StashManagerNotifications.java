package com.zenith.plugin.stashmanager;

import com.zenith.discord.Embed;
import org.jspecify.annotations.Nullable;

import static com.zenith.Globals.DISCORD;

/**
 * Builds Discord notifications for long-running stash jobs that finish
 * asynchronously after the original command response has already been sent.
 */
public final class StashManagerNotifications {

    public void sendScanFinished(int found, int indexed, int failed) {
        var embed = Embed.builder()
            .title("Stash Scan Finished")
            .description(found == 0
                ? "No containers were found in the configured region."
                : "Container scan completed.")
            .addField("Found", found, true)
            .addField("Indexed", indexed, true)
            .addField("Failed", failed, true)
            .successColor();
        DISCORD.sendEmbedMessage(embed);
    }

    public void sendReturnToStartCompleted(double x, double y, double z) {
        var embed = Embed.builder()
            .title("Returned to Position")
            .description("Bot returned to the recorded start position.")
            .addField("Position", formatPosition(x, y, z), false)
            .successColor();
        DISCORD.sendEmbedMessage(embed);
    }

    public void sendReturnToStartFailed(double x, double y, double z, double distanceRemaining) {
        var embed = Embed.builder()
            .title("Return to Position Failed")
            .description("Bot could not get back to the recorded start position.")
            .addField("Target Position", formatPosition(x, y, z), false)
            .addField("Distance Remaining", String.format("%.1f blocks", distanceRemaining), true)
            .errorColor();
        DISCORD.sendEmbedMessage(embed);
    }

    public void sendRetrievalFinished(@Nullable String requestName,
                                      boolean completed,
                                      int movedStacks,
                                      int obtainedTotal,
                                      int remainingTotal,
                                      @Nullable String reason) {
        var embed = Embed.builder()
            .title(completed ? "Retrieval Finished" : "Retrieval Incomplete")
            .description(completed
                ? "Requested stash items have been collected."
                : "Retrieval ended before every requested item was collected.")
            .addField("Moved Stacks", movedStacks, true)
            .addField("Obtained Total", obtainedTotal, true)
            .addField("Remaining", remainingTotal, true)
            .color(completed ? com.zenith.Globals.CONFIG.theme.success.color()
                : com.zenith.Globals.CONFIG.theme.error.color());
        if (requestName != null && !requestName.isBlank()) {
            embed.addField("Request", requestName, false);
        }
        if (!completed && reason != null && !reason.isBlank()) {
            embed.addField("Reason", reason, false);
        }
        DISCORD.sendEmbedMessage(embed);
    }

    public void sendOrganizerFinished(int completedTasks, int totalTasks, int overflowTypes) {
        boolean alreadyOrganized = totalTasks == 0 && completedTasks == 0 && overflowTypes == 0;
        var embed = Embed.builder()
            .title("Organizer Finished")
            .description(alreadyOrganized
                ? "No moves were needed. The stash was already organized."
                : "Stash organizer finished processing move tasks.")
            .addField("Completed Tasks", completedTasks, true)
            .addField("Planned Tasks", totalTasks, true)
            .addField("Overflow Types", overflowTypes, true)
            .successColor();
        DISCORD.sendEmbedMessage(embed);
    }

    // ── Delivery notifications ────────────────────────────────────────────────

    public void sendDeliveryStarted(int destX, int destZ, String[] itemIds, int[] quantities) {
        var sb = new StringBuilder();
        for (int i = 0; i < itemIds.length; i++) {
            if (i > 0) sb.append('\n');
            String shortName = itemIds[i].contains(":") ? itemIds[i].split(":", 2)[1] : itemIds[i];
            sb.append("× ").append(quantities[i]).append("  ").append(shortName);
        }
        var embed = Embed.builder()
            .title("Delivery Started")
            .description("Bot is beginning a delivery run.")
            .addField("Destination", destX + ", " + destZ, true)
            .addField("Items", sb.toString().isBlank() ? "(none)" : sb.toString(), false)
            .primaryColor();
        DISCORD.sendEmbedMessage(embed);
    }

    public void sendDeliveryComplete(int destX, int destZ, long durationMs) {
        var embed = Embed.builder()
            .title("Delivery Complete")
            .description("Items have been deposited at the destination.")
            .addField("Destination", destX + ", " + destZ, true)
            .addField("Duration", formatDuration(durationMs), true)
            .successColor();
        DISCORD.sendEmbedMessage(embed);
    }

    public void sendDeliveryFailed(int destX, int destZ, String reason, long durationMs) {
        var embed = Embed.builder()
            .title("Delivery Failed")
            .description("The delivery run was aborted.")
            .addField("Destination", destX + ", " + destZ, true)
            .addField("Reason", reason == null || reason.isBlank() ? "unknown" : reason, false)
            .addField("Duration", formatDuration(durationMs), true)
            .errorColor();
        DISCORD.sendEmbedMessage(embed);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String formatPosition(double x, double y, double z) {
        return String.format("%.1f, %.1f, %.1f", x, y, z);
    }

    private String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long minutes  = totalSec / 60;
        long seconds  = totalSec % 60;
        return minutes > 0
            ? String.format("%dm %ds", minutes, seconds)
            : String.format("%ds", seconds);
    }
}
