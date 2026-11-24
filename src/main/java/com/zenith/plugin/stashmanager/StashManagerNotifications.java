package com.zenith.plugin.stashmanager;

import com.zenith.discord.Embed;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

import static com.zenith.Globals.DISCORD;

// Sends completion notifications for asynchronous stash jobs.
public final class StashManagerNotifications {

    private final StashManagerConfig config;
    private final Consumer<Embed> sender;

    public StashManagerNotifications(StashManagerConfig config) {
        this(config, DISCORD::sendEmbedMessage);
    }

    StashManagerNotifications(StashManagerConfig config, Consumer<Embed> sender) {
        this.config = config;
        this.sender = sender;
    }

    public void sendScanFinished(int found, int indexed, int failed) {
        boolean partial = failed > 0 || indexed != found;
        var embed = Embed.builder()
            .title(partial ? "Stash Scan Needs Attention" : "Stash Scan Finished")
            .description(found == 0
                ? "No containers were found in the configured region."
                : partial
                    ? "Some containers were not refreshed. Check the scan failures before organizing."
                    : "Container scan completed.")
            .addField("Found", found, true)
            .addField("Indexed", indexed, true)
            .addField("Failed", failed, true);
        if (partial) embed.errorColor();
        else embed.successColor();
        sender.accept(embed);
    }

    public void sendReturnToStartCompleted(double x, double y, double z) {
        var embed = Embed.builder()
            .title("Returned to Position")
            .description("Bot returned to the recorded start position.")
            .successColor();
        if (config.webhookIncludeDetailedPayload) {
            embed.addField("Position", formatPosition(x, y, z), false);
        }
        sender.accept(embed);
    }

    public void sendReturnToStartFailed(double x, double y, double z, double distanceRemaining) {
        var embed = Embed.builder()
            .title("Return to Position Failed")
            .description("Bot could not get back to the recorded start position.")
            .addField("Distance Remaining", String.format("%.1f blocks", distanceRemaining), true)
            .errorColor();
        if (config.webhookIncludeDetailedPayload) {
            embed.addField("Target Position", formatPosition(x, y, z), false);
        }
        sender.accept(embed);
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
        if (config.webhookIncludeDetailedPayload && requestName != null && !requestName.isBlank()) {
            embed.addField("Request", requestName, false);
        }
        if (config.webhookIncludeDetailedPayload && !completed && reason != null && !reason.isBlank()) {
            embed.addField("Reason", reason, false);
        }
        sender.accept(embed);
    }

    public void sendOrganizerFinished(int completedTasks, int totalTasks, int overflowTypes,
                                      int stagedShulkers, int stagedStorageClasses,
                                      int permanentLaneGaps, int scanFailedTargets,
                                      boolean partialConsolidationRemaining) {
        boolean waitingForLanes = permanentLaneGaps > 0;
        boolean alreadyOrganized = totalTasks == 0 && completedTasks == 0
                && overflowTypes == 0 && !waitingForLanes
                && scanFailedTargets == 0 && !partialConsolidationRemaining;
        var embed = Embed.builder()
            .title("Organizer Finished")
            .description(waitingForLanes
                ? stagedShulkers > 0
                    ? "I packed the loose items I could, but the stash needs more permanent lane space. The new bulk shulkers are waiting in your import chests."
                    : "The current items are safe, but some item types still need suitable permanent lanes."
                : partialConsolidationRemaining || scanFailedTargets != 0
                    ? "The planned moves finished, but some partial boxes or scan targets still need a follow-up. Run a fresh scan before treating the stash as fully organized."
                : alreadyOrganized
                    ? "No moves were needed. The stash was already organized."
                    : "Stash organizer finished processing move tasks.")
            .addField("Completed Tasks", completedTasks, true)
            .addField("Planned Tasks", totalTasks, true)
            .addField("Overflow Types", overflowTypes, true)
            .addField("Boxes Waiting in Imports", stagedShulkers, true)
            .addField("Staged Item Types", stagedStorageClasses, true)
            .addField("Permanent Lane Gaps", permanentLaneGaps, true)
            .addField("Unverified Scan Targets", scanFailedTargets < 0 ? "Unknown" : scanFailedTargets, true)
            .addField("Partial Boxes Need Another Pass", partialConsolidationRemaining ? "Yes" : "No", true)
            .color(waitingForLanes || partialConsolidationRemaining || scanFailedTargets != 0
                ? com.zenith.Globals.CONFIG.theme.inQueue.color()
                : com.zenith.Globals.CONFIG.theme.success.color());
        sender.accept(embed);
    }

    public void sendKitInventoryReconciliationFinished(int completedTasks, int totalTasks,
                                                       int stagedShulkers) {
        var embed = Embed.builder()
            .title("Kit Inventory Reconciliation Finished")
            .description("Unexpected inventory cargo was handed to saved lanes or registered "
                    + "import storage. The existing kit checkpoint remains paused for a separate cargo audit.")
            .addField("Verified Handoffs", completedTasks + "/" + totalTasks, true)
            .addField("Boxes Waiting in Imports", stagedShulkers, true)
            .color(com.zenith.Globals.CONFIG.theme.success.color());
        sender.accept(embed);
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
                ? "The organizer stopped safely and kept its restart checkpoint. Check `/stash organize status` for the reason, then use `/stash organize resume`."
                : "The organizer stopped before finishing. Check the bot and run a fresh scan before starting another organization job.")
            .addField("Progress", completedTasks + "/" + totalTasks, true)
            .addField("Restart Checkpoint", checkpointPreserved ? "Saved" : "Unavailable", true)
            .addField("Cargo", cargo, true)
            .errorColor();
        if (config.webhookIncludeDetailedPayload && reason != null && !reason.isBlank()) {
            embed.addField("Reason", reason, false);
        }
        sender.accept(embed);
    }

    public void sendFoodContingencyBlocked(String job, @Nullable String reason) {
        var embed = Embed.builder()
            .title("Stash Job Paused: No Food")
            .description("The bot ran out of usable food, so the stash job was paused before it could starve. Add safe food to its inventory or configure a finite `/stash keep add <food> <count>` target with scanned stock.")
            .addField("Job", job, true)
            .addField("Checkpoint", "Preserved", true)
            .errorColor();
        if (config.webhookIncludeDetailedPayload && reason != null && !reason.isBlank()) {
            embed.addField("Reason", reason, false);
        }
        sender.accept(embed);
    }

    public void sendFoodContingencyRecovered(String job, int foodUnits, int cooldownSeconds) {
        var embed = Embed.builder()
            .title("Food Restocked")
            .description("The bot retrieved usable AutoEat food. The saved stash job will resume after its normal cooldown and quiet check.")
            .addField("Job", job, true)
            .addField("Food Available", foodUnits, true)
            .addField("Resume Cooldown", duration(cooldownSeconds), true)
            .successColor();
        sender.accept(embed);
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
            .addField("Grace Period", duration(graceSeconds), true)
            .addField("Resume Cooldown", duration(cooldownSeconds), true)
            .color(com.zenith.Globals.CONFIG.theme.inQueue.color());
        if (temporaryShulkerOutstanding) {
            embed.addField("Temporary Shulker", "Mid-recovery — switch promptly", false);
        }
        addControllerIfEnabled(embed, playerName);
        sender.accept(embed);
    }

    public void sendProxyControlReleased(@Nullable String playerName, String job) {
        var embed = Embed.builder()
            .title("Stash Job Checkpoint Kept")
            .description("Proxy control was released in time. A safe checkpoint can resume after the automation cooldown and quiet check; uncertain cargo still needs review.")
            .addField("Job", job, true)
            .successColor();
        addControllerIfEnabled(embed, playerName);
        sender.accept(embed);
    }

    public void sendProxyControlAbort(@Nullable String playerName, String job, int graceSeconds) {
        var embed = Embed.builder()
            .title("Stash Job Aborted")
            .description("The controlling client stayed active past the grace period. The in-memory job checkpoint was discarded; completed container moves remain in place.")
            .addField("Job", job, true)
            .addField("Grace Period", duration(graceSeconds), true)
            .errorColor();
        addControllerIfEnabled(embed, playerName);
        sender.accept(embed);
    }

    public void sendProxyControlKitReview(@Nullable String playerName, int graceSeconds) {
        var embed = Embed.builder()
            .title("Kit Auto-Resume Canceled")
            .description("Proxy control exceeded the grace period. The kit checkpoint and physical cargo are preserved; review `/stash kit job` before using `/stash kit resume`.")
            .addField("Grace Period", duration(graceSeconds), true)
            .errorColor();
        addControllerIfEnabled(embed, playerName);
        sender.accept(embed);
    }

    private void addControllerIfEnabled(Embed embed, @Nullable String playerName) {
        if (config.webhookIncludeDetailedPayload) {
            embed.addField("Controller", displayName(playerName), true);
        }
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
