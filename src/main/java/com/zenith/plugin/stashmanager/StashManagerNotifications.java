package com.zenith.plugin.stashmanager;

import com.zenith.discord.Embed;
import org.jspecify.annotations.Nullable;

import static com.zenith.Globals.DISCORD;

// Sends completion notifications for asynchronous stash jobs.
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

    // Helpers
    private String formatPosition(double x, double y, double z) {
        return String.format("%.1f, %.1f, %.1f", x, y, z);
    }

}
