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

    public void sendOrganizerFinished(int completedTasks, int totalTasks, int overflowTypes,
                                      int stagedShulkers, int stagedStorageClasses,
                                      int permanentLaneGaps) {
        boolean waitingForLanes = permanentLaneGaps > 0;
        boolean alreadyOrganized = totalTasks == 0 && completedTasks == 0
                && overflowTypes == 0 && !waitingForLanes;
        var embed = Embed.builder()
            .title("Organizer Finished")
            .description(waitingForLanes
                ? stagedShulkers > 0
                    ? "I packed the loose items I could, but the stash needs more permanent lane space. The new bulk shulkers are waiting in your import chests."
                    : "The current items are safe, but some item types still need suitable permanent lanes."
                : alreadyOrganized
                    ? "No moves were needed. The stash was already organized."
                    : "Stash organizer finished processing move tasks.")
            .addField("Completed Tasks", completedTasks, true)
            .addField("Planned Tasks", totalTasks, true)
            .addField("Overflow Types", overflowTypes, true)
            .addField("Boxes Waiting in Imports", stagedShulkers, true)
            .addField("Staged Item Types", stagedStorageClasses, true)
            .addField("Permanent Lane Gaps", permanentLaneGaps, true)
            .color(waitingForLanes
                ? com.zenith.Globals.CONFIG.theme.inQueue.color()
                : com.zenith.Globals.CONFIG.theme.success.color());
        DISCORD.sendEmbedMessage(embed);
    }

    public void sendOrganizerFailed(int completedTasks, int totalTasks, @Nullable String reason,
                                    boolean checkpointPreserved, boolean cargoPreserved,
                                    @Nullable String cargoState) {
        String cargo = switch (cargoState == null ? "" : cargoState) {
            case "placed_block" -> "Preserved at reconciliation worksite";
            case "inventory" -> "Recovered in inventory";
            case "unverified_drop" -> "Unverified — check the reconciliation worksite";
            default -> cargoPreserved ? "Preserved in inventory" : "Check manually";
        };
        var embed = Embed.builder()
            .title("Stash Organizer Needs Attention")
            .description(checkpointPreserved
                ? "The organizer stopped safely and kept its restart checkpoint. Fix the reported problem, then use `/stash organize resume`."
                : "The organizer stopped before finishing. Check the bot and run a fresh scan before starting another organization job.")
            .addField("Progress", completedTasks + "/" + totalTasks, true)
            .addField("Restart Checkpoint", checkpointPreserved ? "Saved" : "Unavailable", true)
            .addField("Cargo", cargo, true)
            .errorColor();
        if (reason != null && !reason.isBlank()) {
            embed.addField("Reason", reason, false);
        }
        DISCORD.sendEmbedMessage(embed);
    }

    public void sendProxyControlWarning(@Nullable String playerName, String job,
                                        int graceSeconds, int cooldownSeconds,
                                        boolean temporaryShulkerOutstanding) {
        var embed = Embed.builder()
            .title("Stash Job Paused for Proxy Control")
            .description(temporaryShulkerOutstanding
                ? "A controlling client connected during temporary shulker recovery. Use `/swap` now so the bot can secure the box."
                : "A controlling client connected while a long stash job was running. Switch to spectator with `/swap` before the grace period expires to keep the checkpoint.")
            .addField("Job", job, true)
            .addField("Controller", displayName(playerName), true)
            .addField("Grace Period", duration(graceSeconds), true)
            .addField("Resume Cooldown", duration(cooldownSeconds), true)
            .color(com.zenith.Globals.CONFIG.theme.inQueue.color());
        if (temporaryShulkerOutstanding) {
            embed.addField("Temporary Shulker", "Mid-recovery — switch promptly", false);
        }
        DISCORD.sendEmbedMessage(embed);
    }

    public void sendProxyControlReleased(@Nullable String playerName, String job) {
        var embed = Embed.builder()
            .title("Stash Job Checkpoint Kept")
            .description("Proxy control was released in time. The job will resume after the automation cooldown and quiet check.")
            .addField("Job", job, true)
            .addField("Controller", displayName(playerName), true)
            .successColor();
        DISCORD.sendEmbedMessage(embed);
    }

    public void sendProxyControlAbort(@Nullable String playerName, String job, int graceSeconds) {
        var embed = Embed.builder()
            .title("Stash Job Aborted")
            .description("The controlling client stayed active past the grace period. The in-memory job checkpoint was discarded; completed container moves remain in place.")
            .addField("Job", job, true)
            .addField("Controller", displayName(playerName), true)
            .addField("Grace Period", duration(graceSeconds), true)
            .errorColor();
        DISCORD.sendEmbedMessage(embed);
    }

    // Helpers
    private String formatPosition(double x, double y, double z) {
        return String.format("%.1f, %.1f, %.1f", x, y, z);
    }

    private String displayName(@Nullable String playerName) {
        return playerName == null || playerName.isBlank() ? "Unknown" : playerName;
    }

    private String duration(int seconds) {
        if (seconds % 60 == 0) return (seconds / 60) + " minutes";
        return seconds + " seconds";
    }

}
