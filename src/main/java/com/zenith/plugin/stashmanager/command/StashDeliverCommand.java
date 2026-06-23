package com.zenith.plugin.stashmanager.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.plugin.stashmanager.travel.TravelManager;
import com.zenith.plugin.stashmanager.travel.TravelMission;
import com.zenith.plugin.stashmanager.travel.TravelPhase;
import com.zenith.plugin.stashmanager.util.ItemResolver;

import java.util.ArrayList;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;

// /stashdeliver — queue and manage delivery missions.
// start <x> <z> <item[:qty] ...>  |  stop  |  pause  |  resume  |  status
public class StashDeliverCommand extends Command {

    private static final int DEFAULT_QUANTITY = 64;

    // Parses "diamond:64 emerald:32 iron_ingot" into item-id / qty arrays.
    // Trailing colon-segment is quantity if numeric; minecraft: prefix added if absent.
    static ParsedItems parseItems(String raw) {
        var ids  = new ArrayList<String>();
        var qtys = new ArrayList<Integer>();
        for (String token : raw.trim().split("\\s+")) {
            if (token.isBlank()) continue;
            // Check if the last colon-segment is a number (quantity)
            int lastColon = token.lastIndexOf(':');
            String itemId;
            int qty = DEFAULT_QUANTITY;
            if (lastColon > 0) {
                String maybeQty = token.substring(lastColon + 1);
                try {
                    qty = Integer.parseInt(maybeQty);
                    if (qty < 1) qty = 1;
                    itemId = token.substring(0, lastColon);
                } catch (NumberFormatException e) {
                    // Not a number — the whole token is the item ID
                    itemId = token;
                }
            } else {
                itemId = token;
            }
            // Resolve item term → full ID (supports human-friendly aliases)
            itemId = ItemResolver.resolve(itemId);
            ids.add(itemId);
            qtys.add(qty);
        }
        String[] idArr  = ids.toArray(new String[0]);
        int[]    qtyArr = qtys.stream().mapToInt(Integer::intValue).toArray();
        return new ParsedItems(idArr, qtyArr);
    }

    record ParsedItems(String[] itemIds, int[] quantities) {
        boolean isEmpty() { return itemIds.length == 0; }
    }

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("stashdeliver")
            .category(CommandCategory.MODULE)
            .description("Start and manage autonomous stash delivery missions")
            .usageLines(
                "start <x> <z> <item[:qty] ...>",
                "stop",
                "pause",
                "resume",
                "status"
            )
            .aliases("deliver")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("stashdeliver")

            // ── start <x> <z> <item[:qty] ...> ──────────────────────────────
            .then(literal("start")
                .then(argument("x", integer())
                    .then(argument("z", integer())
                        .then(argument("items", greedyString())
                            .executes(c -> { return handleStart(
                                    c.getSource(),
                                    IntegerArgumentType.getInteger(c, "x"),
                                    IntegerArgumentType.getInteger(c, "z"),
                                    StringArgumentType.getString(c, "items")); })))))

            // ── stop ─────────────────────────────────────────────────────────
            .then(literal("stop")
                .executes(c -> { return handleStop(c.getSource()); }))

            // ── pause ────────────────────────────────────────────────────────
            .then(literal("pause")
                .executes(c -> { return handlePause(c.getSource()); }))

            // ── resume ───────────────────────────────────────────────────────
            .then(literal("resume")
                .executes(c -> { return handleResume(c.getSource()); }))

            // ── status ───────────────────────────────────────────────────────
            .then(literal("status")
                .executes(c -> { return handleStatus(c.getSource()); }));
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private int handleStart(CommandContext ctx, int x, int z, String rawItems) {
        var embed = ctx.getEmbed();
        var tm = TravelManager.get();

        if (tm.isActive()) {
            embed.title("Delivery Rejected")
                .description("A mission is already active. Use `/stashdeliver stop` first, or `/stashdeliver status` to check.")
                .errorColor();
            return OK;
        }

        var parsed = parseItems(rawItems);
        if (parsed.isEmpty()) {
            embed.title("No Items Specified")
                .description("Provide at least one item, e.g. `diamond:64 emerald:32`")
                .errorColor();
            return OK;
        }

        var mission = TravelMission.to(x, z)
                .asDelivery(parsed.itemIds(), parsed.quantities())
                .build();

        boolean started = tm.start(mission);
        if (!started) {
            embed.title("Delivery Failed to Start")
                .description("TravelManager rejected the mission (possibly not IDLE).")
                .errorColor();
            return OK;
        }

        var itemSummary = new StringBuilder();
        for (int i = 0; i < parsed.itemIds().length; i++) {
            if (i > 0) itemSummary.append('\n');
            itemSummary.append("× ").append(parsed.quantities()[i])
                       .append("  ").append(parsed.itemIds()[i]);
        }

        embed.title("Delivery Started")
            .description("Queued delivery mission #" + mission.id + ".")
            .addField("Destination", x + ", " + z, true)
            .addField("Items", itemSummary.toString(), false)
            .successColor();
        return OK;
    }

    private int handleStop(CommandContext ctx) {
        var embed = ctx.getEmbed();
        var tm = TravelManager.get();

        if (!tm.isActive()) {
            embed.title("No Active Mission")
                .description("There is nothing to stop.")
                .primaryColor();
            return OK;
        }

        tm.stop();
        embed.title("Mission Stopped")
            .description("The delivery mission has been aborted.")
            .successColor();
        return OK;
    }

    private int handlePause(CommandContext ctx) {
        var embed = ctx.getEmbed();
        var tm = TravelManager.get();

        if (!tm.isActive()) {
            embed.title("No Active Mission")
                .description("Nothing to pause.")
                .primaryColor();
            return OK;
        }

        TravelPhase phase = tm.currentPhase();
        if (phase == TravelPhase.PAUSED) {
            embed.title("Already Paused")
                .description("The mission is already paused. Use `/stashdeliver resume` to continue.")
                .primaryColor();
            return OK;
        }

        tm.pause();
        embed.title("Mission Paused")
            .description("Delivery paused at phase **" + phase + "**. Use `/stashdeliver resume` to continue.")
            .successColor();
        return OK;
    }

    private int handleResume(CommandContext ctx) {
        var embed = ctx.getEmbed();
        var tm = TravelManager.get();
        TravelPhase phase = tm.currentPhase();

        if (phase != TravelPhase.PAUSED && phase != TravelPhase.ABORTED && phase != TravelPhase.IDLE) {
            embed.title("Nothing to Resume")
                .description("Mission is currently **" + phase + "** — not paused or aborted.")
                .primaryColor();
            return OK;
        }

        tm.resume();
        embed.title("Mission Resumed")
            .description("Delivery mission resumed from **" + phase + "**.")
            .successColor();
        return OK;
    }

    private int handleStatus(CommandContext ctx) {
        var embed = ctx.getEmbed();
        var tm = TravelManager.get();
        var state = tm.getState();
        TravelPhase phase = state.phase;

        embed.title("Delivery Status").primaryColor();

        if (phase == TravelPhase.IDLE) {
            embed.description("No delivery mission is active.");
            return OK;
        }

        embed.description("Mission **#" + (state.mission != null ? state.mission.id : "?") + "** is " + phase + ".");
        embed.addField("Phase", phase.toString(), true);
        embed.addField("Ticks in Phase", String.valueOf(state.ticksInPhase), true);
        embed.addField("Mission Ticks", String.valueOf(state.missionTicks), true);

        if (state.mission != null) {
            int[] dest = state.mission.destination;
            embed.addField("Destination", dest[0] + ", " + dest[2], true);

            if (state.mission.isDelivery && state.mission.itemIds != null
                    && state.mission.itemIds.length > 0) {
                StringBuilder items = new StringBuilder();
                for (int i = 0; i < state.mission.itemIds.length; i++) {
                    if (i > 0) items.append('\n');
                    items.append("× ").append(state.mission.quantities[i])
                         .append("  ").append(state.mission.itemIds[i]);
                }
                embed.addField("Items", items.toString(), false);
            }
        }

        if (!state.lastTransitionReason.isBlank()) {
            embed.addField("Last Reason", state.lastTransitionReason, false);
        }

        if (phase == TravelPhase.ABORTED && !state.abortReason.isBlank()) {
            embed.addField("Abort Reason", state.abortReason, false);
        }

        return OK;
    }
}
