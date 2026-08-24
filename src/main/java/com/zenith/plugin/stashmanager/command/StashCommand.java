package com.zenith.plugin.stashmanager.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.brigadier.ItemArgument;
import com.zenith.Proxy;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.command.api.CommandCategory;
import com.zenith.feature.player.raycast.RaycastHelper;
import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.block.properties.ChestType;
import com.zenith.mc.block.properties.api.BlockStateProperties;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.StashManagerModule;
import com.zenith.plugin.stashmanager.api.ApiServer;
import com.zenith.plugin.stashmanager.database.DatabaseManager;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import com.zenith.plugin.stashmanager.index.IndexExporter;
import com.zenith.plugin.stashmanager.orchestration.LaneCapacityReport;
import com.zenith.plugin.stashmanager.orchestration.LaneConstructionPlan;
import com.zenith.plugin.stashmanager.orchestration.LaneReportExporter;
import com.zenith.plugin.stashmanager.update.PluginUpdateService;
import com.zenith.plugin.stashmanager.util.DoubleChestIdentity;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import com.zenith.plugin.stashmanager.util.ItemResolver;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.DISCORD;

// Main /stash command tree: pos1, pos2, scan, stop, status, list, export, clear, db, config.
public class StashCommand extends Command {

    private static final int PAGE_SIZE = 10;
    private static final String SPECTATOR_TESTING_TIP =
        "Use spectator mode for live observation: `/spectator on`, whitelist your test account, then `/spectator playerCamOnJoin on`.";
    private static final DateTimeFormatter UPDATE_TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private final StashManagerConfig config;
    private final StashManagerModule module;
    private final ContainerIndex index;
    private final DatabaseManager database;
    private final ApiServer apiServer;
    private final PluginUpdateService updateService;

    public StashCommand(StashManagerConfig config, StashManagerModule module,
                        ContainerIndex index, DatabaseManager database, ApiServer apiServer,
                        PluginUpdateService updateService) {
        this.config = config;
        this.module = module;
        this.index = index;
        this.database = database;
        this.apiServer = apiServer;
        this.updateService = updateService;
    }

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("stash")
            .category(CommandCategory.MODULE)
            .description("Stash manager — scan, index, and query container inventories")
            .usageLines(
                "pos1 [x y z]",
                "pos2 [x y z]",
                "scan / stop / return / status / summary",
                "update / update check",
                "list [page] / export / clear / clearall",
                "debug <recent [count]|clear|export>",
                "keep <add|remove|list> [count]",
                "import [remove|list|purge [confirm]] (face a chest for add/remove)",
                "label <x> <y> <z> <label> / labels",
                "region <save|load|list|delete> [name]",
                "kit <list|show|snap|delete|add|remove> <name>",
                "get <item_id> [count] / kit <name> / status / stop",
                "lanes [export] / organize [stop|status]",
                "db status / db clear",
                "config <scanDelay|openTimeout|maxContainers|walkTimeout|preemptionCooldown|controlGrace> <value>",
                "config returnToStart <on|off>",
                "config db <enable|disable|connect>",
                "config db <url|user|password|poolSize> <value>",
                "config api <enable|disable|start|stop>",
                "config api <port|bind|key> <value>",
                "config updates [checkOnLoad|autoDownload <on|off>]"
            )
            .aliases("sm")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("stash")
            .then(literal("pos1")
                .executes(c -> {
                    // Use player position
                    var pc = CACHE.getPlayerCache();
                    config.pos1 = new int[]{(int) pc.getX(), (int) pc.getY(), (int) pc.getZ()};
                    c.getSource().getEmbed()
                        .title("Stash Region")
                        .description("Position 1 set to: " + formatPos(config.pos1))
                        .successColor();
                    return OK;
                })
                .then(argument("x", integer())
                    .then(argument("y", integer())
                        .then(argument("z", integer())
                            .executes(c -> {
                                int x = IntegerArgumentType.getInteger(c, "x");
                                int y = IntegerArgumentType.getInteger(c, "y");
                                int z = IntegerArgumentType.getInteger(c, "z");
                                config.pos1 = new int[]{x, y, z};
                                c.getSource().getEmbed()
                                    .title("Stash Region")
                                    .description("Position 1 set to: " + formatPos(config.pos1))
                                    .successColor();
                                return OK;
                            })
                        )
                    )
                )
            )
            .then(literal("pos2")
                .executes(c -> {
                    var pc = CACHE.getPlayerCache();
                    config.pos2 = new int[]{(int) pc.getX(), (int) pc.getY(), (int) pc.getZ()};
                    c.getSource().getEmbed()
                        .title("Stash Region")
                        .description("Position 2 set to: " + formatPos(config.pos2))
                        .successColor();
                    return OK;
                })
                .then(argument("x", integer())
                    .then(argument("y", integer())
                        .then(argument("z", integer())
                            .executes(c -> {
                                int x = IntegerArgumentType.getInteger(c, "x");
                                int y = IntegerArgumentType.getInteger(c, "y");
                                int z = IntegerArgumentType.getInteger(c, "z");
                                config.pos2 = new int[]{x, y, z};
                                c.getSource().getEmbed()
                                    .title("Stash Region")
                                    .description("Position 2 set to: " + formatPos(config.pos2))
                                    .successColor();
                                return OK;
                            })
                        )
                    )
                )
            )
            .then(literal("scan")
                .executes(c -> {
                    boolean started = module.startScan();
                    var embed = c.getSource().getEmbed();
                    if (started) {
                        embed.title("Stash Scan Started")
                            .successColor();
                        if (config.pos1 != null && config.pos2 != null) {
                            int[] dims = module.getRegionDimensions();
                            if (dims != null) {
                                embed.addField("Region", formatPos(config.pos1)
                                    + " → " + formatPos(config.pos2), false);
                                embed.addField("Dimensions",
                                    dims[0] + " x " + dims[1] + " x " + dims[2], true);
                            }
                        }
                    } else {
                        String reason = module.getScanStartBlocker();
                        embed.title("Scan Failed")
                            .description(withSpectatorTip(reason != null ? reason : "Scan could not be started."))
                            .errorColor();
                    }
                    return OK;
                })
            )
            .then(literal("stop")
                .executes(c -> {
                    module.abortScan();
                    c.getSource().getEmbed()
                        .title("Stash Scan Stopped")
                        .addField("Indexed", String.valueOf(module.getContainersIndexed()), true)
                        .addField("Failed", String.valueOf(module.getContainersFailed()), true)
                        .primaryColor();
                    return OK;
                })
            )
            .then(literal("return")
                .executes(c -> {
                    var embed = c.getSource().getEmbed();
                    if (module.returnToStart()) {
                        embed.title("Returning to Start")
                            .description(String.format("Navigating to %.1f, %.1f, %.1f",
                                module.getStartX(), module.getStartY(), module.getStartZ()))
                            .successColor();
                    } else {
                        String reason = module.getReturnToStartBlocker();
                        embed.title("Return Failed")
                            .description(withSpectatorTip(reason != null ? reason : "Could not return to the recorded start position."))
                            .errorColor();
                    }
                    return OK;
                })
            )
            .then(literal("status")
                .executes(c -> {
                    var updateSnapshot = updateService.getSnapshot();
                    var embed = c.getSource().getEmbed()
                        .title("Stash Status")
                        .addField("State", module.getState().name(), true)
                        .addField("Index Size", String.valueOf(index.size()), true)
                        .primaryColor();

                    if (config.pos1 != null && config.pos2 != null) {
                        int[] dims = module.getRegionDimensions();
                        if (dims != null) {
                            embed.addField("Region", formatPos(config.pos1) + " → " + formatPos(config.pos2), false);
                            embed.addField("Dimensions", dims[0] + " x " + dims[1] + " x " + dims[2], true);
                        }
                    } else {
                        embed.addField("Region", "Not defined", false);
                    }

                    if (module.getState() != StashManagerModule.ScanState.IDLE) {
                        embed.addField("Found", String.valueOf(module.getContainersFound()), true);
                        embed.addField("Indexed", String.valueOf(module.getContainersIndexed()), true);
                        embed.addField("Failed", String.valueOf(module.getContainersFailed()), true);
                        embed.addField("Pending", String.valueOf(module.getPendingCount()), true);
                        embed.addField("Task Handoffs", String.valueOf(module.getScanPreemptionCount()), true);
                        if (module.getState() == StashManagerModule.ScanState.YIELDED) {
                            embed.addField("Minimum Resume Hold",
                                module.getScanPreemptionCooldownRemainingSeconds() + "s", true);
                        }
                        if (module.getControlledJob()
                                == com.zenith.plugin.stashmanager.orchestration.JobContinuanceManager.Job.SCAN) {
                            embed.addField("Proxy Control Grace",
                                module.getProxyControlGraceRemainingSeconds() + "s", true);
                        }
                    }

                    embed.addField("Return to Start", config.returnToStart ? "Enabled" : "Disabled", true);
                    embed.addField("Database", database != null && database.isInitialized() ? "Connected" : "Disabled", true);
                    embed.addField("API Server", config.apiEnabled ? "Port " + config.apiPort : "Disabled", true);
                    if (Proxy.getInstance().hasActivePlayer()) {
                        embed.addField("Automation Note",
                            "A player is controlling the proxy, so Baritone-based stash actions will not run. " + SPECTATOR_TESTING_TIP,
                            false);
                    }
                    embed.addField("Plugin Version", updateSnapshot.currentVersion(), true);
                    embed.addField("Updater", formatUpdaterState(updateSnapshot), true);
                    if (updateSnapshot.latestVersion() != null) {
                        embed.addField("Latest Release", updateSnapshot.latestVersion(), true);
                    }
                    if (updateSnapshot.stagedVersion() != null) {
                        embed.addField("Staged Update", updateSnapshot.stagedVersion(), true);
                    }

                    if (index.getLastScanTimestamp() > 0) {
                        embed.footer("Last scan: " + index.timeSinceLastScan(), null);
                    }
                    return OK;
                })
            )
            .then(literal("update")
                .executes(c -> {
                    renderUpdateResult(c.getSource(), updateService.checkAndStageUpdate(), "Update");
                    return OK;
                })
                .then(literal("check")
                    .executes(c -> {
                        renderUpdateResult(c.getSource(), updateService.checkForUpdates(), "Update Check");
                        return OK;
                    })
                )
            )
            .then(literal("list")
                .executes(c -> {
                    renderListPage(c.getSource(), 1);
                    return OK;
                })
                .then(argument("page", integer(1))
                    .executes(c -> {
                        int page = IntegerArgumentType.getInteger(c, "page");
                        renderListPage(c.getSource(), page);
                        return OK;
                    })
                )
            )
            .then(literal("export")
                .executes(c -> {
                    var embed = c.getSource().getEmbed();

                    java.util.Collection<ContainerEntry> entries;
                    int count;

                    if (database != null && database.isInitialized()) {
                        try {
                            entries = database.getAllContainers();
                            count = entries.size();
                        } catch (Exception e) {
                            embed.title("Export Failed")
                                .description("Database query failed: " + e.getMessage())
                                .errorColor();
                            return OK;
                        }
                    } else {
                        entries = index.getAll();
                        count = index.size();
                    }

                    if (count == 0) {
                        embed.title("Export Failed")
                            .description("Index is empty — nothing to export.")
                            .errorColor();
                        return OK;
                    }

                    byte[] csv = IndexExporter.exportCsv(entries);
                    embed.title("Stash Export")
                        .description("Exported " + count + " containers to CSV")
                        .successColor()
                        .fileAttachment(new com.zenith.discord.Embed.FileAttachment("stash_export.csv", csv));
                    return OK;
                })
            )
            .then(literal("clear")
                .executes(c -> {
                    int count = index.size();
                    index.clear();
                    c.getSource().getEmbed()
                        .title("Index Cleared")
                        .description("Removed " + count + " container entries. Region positions retained.")
                        .successColor();
                    return OK;
                })
            )
            .then(literal("clearall")
                .executes(c -> {
                    int count = index.size();
                    index.clearAll();
                    c.getSource().getEmbed()
                        .title("Index & Database Cleared")
                        .description("Removed " + count + " container entries from memory and database.")
                        .successColor();
                    return OK;
                })
            )
            .then(literal("debug")
                .then(literal("recent")
                    .executes(c -> {
                        sendRecentDebugEvents(c.getSource(), 20);
                        return OK;
                    })
                    .then(argument("count", integer(1, 100))
                        .executes(c -> {
                            sendRecentDebugEvents(c.getSource(), IntegerArgumentType.getInteger(c, "count"));
                            return OK;
                        })))
                .then(literal("clear")
                    .executes(c -> {
                        int count = module.getDebugRecorder().size();
                        module.getDebugRecorder().clear();
                        c.getSource().getEmbed()
                            .title("Debug Log Cleared")
                            .description("Removed " + count + " recorded debug event(s).")
                            .successColor();
                        return OK;
                    }))
                .then(literal("export")
                    .executes(c -> {
                        var embed = c.getSource().getEmbed();
                        var recorder = module.getDebugRecorder();
                        int count = recorder.size();
                        if (count == 0) {
                            embed.title("Debug Export Failed")
                                .description("No debug events recorded — nothing to export.")
                                .errorColor();
                            return OK;
                        }
                        byte[] text = recorder.exportText();
                        embed.title("Debug Log Export")
                            .description("Exported " + count + " debug event(s).")
                            .successColor()
                            .fileAttachment(new com.zenith.discord.Embed.FileAttachment("stash_debug.log", text));
                        return OK;
                    }))
            )
            .then(literal("keep")
                .then(literal("add")
                    .then(argument("item_id", ItemArgument.item())
                        .executes(c -> {
                            String itemId = ItemArgument.getItem(c, "item_id").name();
                            var embed = c.getSource().getEmbed();
                            if (database == null || !database.isInitialized()) {
                                embed.title("Keep Item Failed").description("Database not connected.").errorColor();
                                return OK;
                            }
                            try {
                                var keepItems = new LinkedHashMap<>(database.loadKeepItems());
                                boolean added = !keepItems.containsKey(itemId);
                                keepItems.put(itemId, null);
                                database.saveKeepItems(keepItems);
                                embed.title(added ? "Keep Item Added" : "Already Kept")
                                    .description("**" + itemId + "** — all of it will stay in the bot's inventory during organize.")
                                    .successColor();
                            } catch (Exception e) {
                                embed.title("Keep Item Failed").description("Error: " + e.getMessage()).errorColor();
                            }
                            return OK;
                        })
                        .then(argument("count", integer(1, 10000))
                            .executes(c -> {
                                String itemId = ItemArgument.getItem(c, "item_id").name();
                                int count = IntegerArgumentType.getInteger(c, "count");
                                var embed = c.getSource().getEmbed();
                                if (database == null || !database.isInitialized()) {
                                    embed.title("Keep Item Failed").description("Database not connected.").errorColor();
                                    return OK;
                                }
                                try {
                                    var keepItems = new LinkedHashMap<>(database.loadKeepItems());
                                    boolean added = !keepItems.containsKey(itemId);
                                    keepItems.put(itemId, count);
                                    database.saveKeepItems(keepItems);
                                    embed.title(added ? "Keep Item Added" : "Keep Quantity Updated")
                                        .description("Up to **" + count + "x " + itemId + "** will stay in the bot's inventory during organize — any excess gets deposited.")
                                        .successColor();
                                } catch (Exception e) {
                                    embed.title("Keep Item Failed").description("Error: " + e.getMessage()).errorColor();
                                }
                                return OK;
                            }))))
                .then(literal("remove")
                    .then(argument("item_id", ItemArgument.item())
                        .executes(c -> {
                            String itemId = ItemArgument.getItem(c, "item_id").name();
                            var embed = c.getSource().getEmbed();
                            if (database == null || !database.isInitialized()) {
                                embed.title("Keep Item Failed").description("Database not connected.").errorColor();
                                return OK;
                            }
                            try {
                                var keepItems = new LinkedHashMap<>(database.loadKeepItems());
                                boolean removed = keepItems.containsKey(itemId);
                                keepItems.remove(itemId);
                                database.saveKeepItems(keepItems);
                                embed.title(removed ? "Keep Item Removed" : "Not Kept")
                                    .description("**" + itemId + "** " + (removed ? "will now be eligible to deposit during organize." : "wasn't on the keep list."))
                                    .successColor();
                            } catch (Exception e) {
                                embed.title("Keep Item Failed").description("Error: " + e.getMessage()).errorColor();
                            }
                            return OK;
                        })))
                .then(literal("list")
                    .executes(c -> {
                        var embed = c.getSource().getEmbed();
                        if (database == null || !database.isInitialized()) {
                            embed.title("Keep List Failed").description("Database not connected.").errorColor();
                            return OK;
                        }
                        try {
                            var keepItems = database.loadKeepItems();
                            embed.title("Keep List")
                                .description(keepItems.isEmpty()
                                    ? "No items configured — organize will deposit everything from inventory."
                                    : keepItems.entrySet().stream()
                                        .map(e -> e.getValue() == null ? e.getKey() + " (all)" : e.getKey() + " (up to " + e.getValue() + ")")
                                        .collect(Collectors.joining("\n")))
                                .primaryColor();
                        } catch (Exception e) {
                            embed.title("Keep List Failed").description("Error: " + e.getMessage()).errorColor();
                        }
                        return OK;
                    }))
            )
            .then(literal("summary")
                .executes(c -> {
                    c.getSource().getEmbed()
                        .title("Stash Summary")
                        .description(index.getDetailedSummary())
                        .primaryColor();
                    if (index.getLastScanTimestamp() > 0) {
                        c.getSource().getEmbed().footer("Last scan: " + index.timeSinceLastScan(), null);
                    }
                    return OK;
                })
            )
            .then(literal("import")
                .executes((com.mojang.brigadier.Command<CommandContext>)
                    c -> updateTargetedImportChest(c, true))
                .then(literal("remove")
                    .executes((com.mojang.brigadier.Command<CommandContext>)
                        c -> updateTargetedImportChest(c, false)))
                .then(literal("list")
                    .executes(c -> {
                        var positions = index.getImportChests();
                        var embed = c.getSource().getEmbed()
                            .title("Import Chests")
                            .primaryColor();
                        if (positions.isEmpty()) {
                            embed.description("No import chests assigned. Face a chest and run `/stash import`.");
                        } else {
                            StringBuilder description = new StringBuilder();
                            int shown = 0;
                            for (int[] pos : positions) {
                                if (shown++ >= 40) {
                                    description.append("... and ").append(positions.size() - 40).append(" more block position(s)");
                                    break;
                                }
                                description.append(pos[0]).append(", ").append(pos[1]).append(", ").append(pos[2]).append('\n');
                            }
                            embed.description(description.toString())
                                .footer("Double chests are persisted as both block positions.", null);
                        }
                        return OK;
                    }))
                .then(literal("purge")
                    .executes((com.mojang.brigadier.Command<CommandContext>)
                        c -> purgeImportChests(c, false))
                    .then(literal("confirm")
                        .executes((com.mojang.brigadier.Command<CommandContext>)
                            c -> purgeImportChests(c, true))))
            )
            .then(literal("label")
                .then(argument("x", integer())
                    .then(argument("y", integer())
                        .then(argument("z", integer())
                            .then(argument("label", greedyString())
                                .executes(c -> {
                                    int x = IntegerArgumentType.getInteger(c, "x");
                                    int y = IntegerArgumentType.getInteger(c, "y");
                                    int z = IntegerArgumentType.getInteger(c, "z");
                                    String label = StringArgumentType.getString(c, "label");
                                    var embed = c.getSource().getEmbed();

                                    // Update in-memory index
                                    var entry = index.get(x, y, z);
                                    if (entry != null) {
                                        index.put(entry.withLabel(label));
                                    }
                                    // Update database
                                    if (database != null && database.isInitialized()) {
                                        try {
                                            database.updateLabel(x, y, z, label);
                                        } catch (Exception e) {
                                            embed.title("Label Failed")
                                                .description("Database error: " + e.getMessage())
                                                .errorColor();
                                            return OK;
                                        }
                                    }
                                    embed.title("Label Set")
                                        .description("Container at " + x + ", " + y + ", " + z + " → " + label)
                                        .successColor();
                                    return OK;
                                })
                            )
                        )
                    )
                )
            )
            .then(literal("labels")
                .executes(c -> {
                    var embed = c.getSource().getEmbed()
                        .title("Container Labels")
                        .primaryColor();

                    if (database != null && database.isInitialized()) {
                        try {
                            var labels = database.getAllLabels();
                            if (labels.isEmpty()) {
                                embed.description("No labels set. Use `/stash label <x> <y> <z> <label>` to add one.");
                            } else {
                                StringBuilder sb = new StringBuilder();
                                int count = 0;
                                for (var entry : labels.entrySet()) {
                                    if (count >= 25) {
                                        sb.append("... and ").append(labels.size() - 25).append(" more");
                                        break;
                                    }
                                    sb.append("**").append(entry.getValue()).append("** — ").append(entry.getKey()).append("\n");
                                    count++;
                                }
                                embed.description(sb.toString());
                            }
                        } catch (Exception e) {
                            embed.description("Database error: " + e.getMessage()).errorColor();
                        }
                    } else {
                        // Fall back to in-memory labels
                        StringBuilder sb = new StringBuilder();
                        int count = 0;
                        for (var entry : index.getAll()) {
                            if (entry.label() != null) {
                                if (count >= 25) {
                                    sb.append("... and more");
                                    break;
                                }
                                sb.append("**").append(entry.label()).append("** — ")
                                    .append(entry.posString()).append("\n");
                                count++;
                            }
                        }
                        embed.description(count == 0 ? "No labels set." : sb.toString());
                    }
                    return OK;
                })
            )
            .then(literal("region")
                .then(literal("save")
                    .then(argument("name", string())
                        .executes(c -> {
                            String name = StringArgumentType.getString(c, "name");
                            var embed = c.getSource().getEmbed();

                            if (config.pos1 == null || config.pos2 == null) {
                                embed.title("Region Save Failed")
                                    .description("Region not defined. Set pos1 and pos2 first.")
                                    .errorColor();
                                return OK;
                            }

                            if (database == null || !database.isInitialized()) {
                                embed.title("Region Save Failed")
                                    .description("Database not connected. Enable and connect the database first.")
                                    .errorColor();
                                return OK;
                            }

                            try {
                                database.saveRegion(name, config.pos1, config.pos2);
                                embed.title("Region Saved")
                                    .description("**" + name + "**: " + formatPos(config.pos1) + " → " + formatPos(config.pos2))
                                    .successColor();
                            } catch (Exception e) {
                                embed.title("Region Save Failed")
                                    .description("Error: " + e.getMessage())
                                    .errorColor();
                            }
                            return OK;
                        })
                    )
                )
                .then(literal("load")
                    .then(argument("name", string())
                        .executes(c -> {
                            String name = StringArgumentType.getString(c, "name");
                            var embed = c.getSource().getEmbed();

                            if (database == null || !database.isInitialized()) {
                                embed.title("Region Load Failed")
                                    .description("Database not connected.")
                                    .errorColor();
                                return OK;
                            }

                            try {
                                var region = database.loadRegion(name);
                                if (region == null) {
                                    embed.title("Region Not Found")
                                        .description("No saved region named: " + name)
                                        .errorColor();
                                } else {
                                    config.pos1 = region.pos1();
                                    config.pos2 = region.pos2();
                                    embed.title("Region Loaded")
                                        .description("**" + name + "**: " + formatPos(config.pos1) + " → " + formatPos(config.pos2))
                                        .successColor();
                                }
                            } catch (Exception e) {
                                embed.title("Region Load Failed")
                                    .description("Error: " + e.getMessage())
                                    .errorColor();
                            }
                            return OK;
                        })
                    )
                )
                .then(literal("list")
                    .executes(c -> {
                        var embed = c.getSource().getEmbed()
                            .title("Saved Regions")
                            .primaryColor();

                        if (database == null || !database.isInitialized()) {
                            embed.description("Database not connected.").errorColor();
                            return OK;
                        }

                        try {
                            var regions = database.listRegions();
                            if (regions.isEmpty()) {
                                embed.description("No saved regions. Use `/stash region save <name>` to save one.");
                            } else {
                                for (var region : regions) {
                                    embed.addField(region.name(),
                                        formatPos(region.pos1()) + " → " + formatPos(region.pos2()), false);
                                }
                            }
                        } catch (Exception e) {
                            embed.description("Error: " + e.getMessage()).errorColor();
                        }
                        return OK;
                    })
                )
                .then(literal("delete")
                    .then(argument("name", string())
                        .executes(c -> {
                            String name = StringArgumentType.getString(c, "name");
                            var embed = c.getSource().getEmbed();

                            if (database == null || !database.isInitialized()) {
                                embed.title("Region Delete Failed")
                                    .description("Database not connected.")
                                    .errorColor();
                                return OK;
                            }

                            try {
                                if (database.deleteRegion(name)) {
                                    embed.title("Region Deleted")
                                        .description("Removed saved region: " + name)
                                        .successColor();
                                } else {
                                    embed.title("Region Not Found")
                                        .description("No saved region named: " + name)
                                        .errorColor();
                                }
                            } catch (Exception e) {
                                embed.title("Region Delete Failed")
                                    .description("Error: " + e.getMessage())
                                    .errorColor();
                            }
                            return OK;
                        })
                    )
                )
            )
            .then(literal("kit")
                .then(literal("list")
                    .executes(c -> {
                        var embed = c.getSource().getEmbed()
                            .title("Saved Kits")
                            .primaryColor();

                        if (database == null || !database.isInitialized()) {
                            embed.description("Database not connected.").errorColor();
                            return OK;
                        }

                        try {
                            var kits = database.listKits();
                            if (kits.isEmpty()) {
                                embed.description("No kits saved. Use `/stash kit snapshot <name>` to create one.");
                            } else {
                                embed.description(String.join("\n", kits));
                            }
                        } catch (Exception e) {
                            embed.description("Error: " + e.getMessage()).errorColor();
                        }
                        return OK;
                    })
                )
                .then(literal("show")
                    .then(argument("name", string())
                        .executes(c -> {
                            String name = StringArgumentType.getString(c, "name");
                            var embed = c.getSource().getEmbed()
                                .title("Kit: " + name)
                                .primaryColor();

                            if (database == null || !database.isInitialized()) {
                                embed.description("Database not connected.").errorColor();
                                return OK;
                            }

                            try {
                                var items = database.loadKit(name);
                                if (items.isEmpty()) {
                                    embed.description("Kit is empty or does not exist.");
                                } else {
                                    StringBuilder sb = new StringBuilder();
                                    int shown = 0;
                                    for (var item : items.entrySet()) {
                                        if (shown >= 25) {
                                            sb.append("... and ").append(items.size() - 25).append(" more\n");
                                            break;
                                        }
                                        sb.append(item.getValue()).append("x ")
                                            .append(IndexExporter.toReadableName(item.getKey()))
                                            .append(" (`").append(item.getKey()).append("`)\n");
                                        shown++;
                                    }
                                    embed.description(sb.toString())
                                        .addField("Unique Items", String.valueOf(items.size()), true);
                                }
                            } catch (Exception e) {
                                embed.description("Error: " + e.getMessage()).errorColor();
                            }
                            return OK;
                        })
                    )
                )
                .then(literal("snapshot")
                    .then(argument("name", string())
                        .executes(c -> {
                            String name = StringArgumentType.getString(c, "name");
                            var embed = c.getSource().getEmbed();

                            if (database == null || !database.isInitialized()) {
                                embed.title("Kit Snapshot Failed")
                                    .description("Database not connected.")
                                    .errorColor();
                                return OK;
                            }

                            var snapshot = snapshotPlayerInventory();
                            if (snapshot.isEmpty()) {
                                module.fireWebhookEvent("kit_snapshot_failed", Map.of(
                                    "kit_name", name,
                                    "reason", "inventory_empty"
                                ));
                                embed.title("Kit Snapshot Failed")
                                    .description("Player inventory is empty.")
                                    .errorColor();
                                return OK;
                            }

                            int uniqueBefore = snapshot.size();
                            int totalBefore = snapshot.values().stream().mapToInt(Integer::intValue).sum();
                            var expectedPersisted = DatabaseManager.truncateKitItems(snapshot);
                            int persistedTotal = expectedPersisted.values().stream().mapToInt(Integer::intValue).sum();
                            boolean truncated = uniqueBefore > expectedPersisted.size();

                            try {
                                database.saveKit(name, snapshot);
                                var persisted = database.loadKit(name);
                                if (!persisted.equals(expectedPersisted)) {
                                    module.fireWebhookEvent("kit_snapshot_failed", Map.of(
                                        "kit_name", name,
                                        "reason", "verification_failed",
                                        "expected_unique_items", expectedPersisted.size(),
                                        "persisted_unique_items", persisted.size()
                                    ));
                                    embed.title("Kit Snapshot Failed")
                                        .description("Snapshot verification failed after saving **" + name + "**.")
                                        .errorColor();
                                    return OK;
                                }

                                if (truncated) {
                                    module.fireWebhookEvent("kit_snapshot_truncated", Map.of(
                                        "kit_name", name,
                                        "original_unique_items", uniqueBefore,
                                        "persisted_unique_items", expectedPersisted.size()
                                    ));
                                }
                                module.fireWebhookEvent("kit_snapshot_saved", Map.of(
                                    "kit_name", name,
                                    "original_unique_items", uniqueBefore,
                                    "persisted_unique_items", expectedPersisted.size(),
                                    "persisted_total_count", persistedTotal,
                                    "truncated", truncated
                                ));

                                String description = truncated
                                    ? "Saved **" + name + "** from current inventory. Truncated to the first **"
                                        + DatabaseManager.KIT_MAX_SLOTS + "** unique items to match one shulker load."
                                    : "Saved **" + name + "** from current inventory.";
                                embed.title("Kit Snapshot Saved")
                                    .description(description)
                                    .addField("Unique Items", String.valueOf(expectedPersisted.size()), true)
                                    .addField("Total Count", String.valueOf(persistedTotal), true)
                                    .successColor();
                            } catch (Exception e) {
                                module.fireWebhookEvent("kit_snapshot_failed", Map.of(
                                    "kit_name", name,
                                    "reason", "exception",
                                    "message", e.getMessage()
                                ));
                                embed.title("Kit Snapshot Failed")
                                    .description("Error: " + e.getMessage())
                                    .errorColor();
                            }
                            return OK;
                        })
                    )
                )
                .then(literal("add")
                    .then(argument("name", string())
                        .then(argument("item_id", string())
                            .then(argument("count", integer(1, 100000))
                                .executes(c -> {
                                    String name = StringArgumentType.getString(c, "name");
                                    String itemId = normalizeItemId(StringArgumentType.getString(c, "item_id"));
                                    int count = IntegerArgumentType.getInteger(c, "count");
                                    var embed = c.getSource().getEmbed();

                                    if (database == null || !database.isInitialized()) {
                                        embed.title("Kit Update Failed")
                                            .description("Database not connected.")
                                            .errorColor();
                                        return OK;
                                    }

                                    try {
                                        if (database.setKitItem(name, itemId, count)) {
                                            module.fireWebhookEvent("kit_item_updated", Map.of(
                                                "kit_name", name,
                                                "item_id", itemId,
                                                "quantity", count
                                            ));
                                            embed.title("Kit Updated")
                                                .description("Set **" + itemId + "** = **" + count + "** in kit **" + name + "**")
                                                .successColor();
                                        } else {
                                            module.fireWebhookEvent("kit_item_update_rejected", Map.of(
                                                "kit_name", name,
                                                "item_id", itemId,
                                                "quantity", count,
                                                "reason", "kit_slot_limit_reached"
                                            ));
                                            embed.title("Kit Update Rejected")
                                                .description("Kit **" + name + "** is already at the **" + DatabaseManager.KIT_MAX_SLOTS + "** item slot limit.")
                                                .errorColor();
                                        }
                                    } catch (Exception e) {
                                        module.fireWebhookEvent("kit_item_update_failed", Map.of(
                                            "kit_name", name,
                                            "item_id", itemId,
                                            "quantity", count,
                                            "message", e.getMessage()
                                        ));
                                        embed.title("Kit Update Failed")
                                            .description("Error: " + e.getMessage())
                                            .errorColor();
                                    }
                                    return OK;
                                })
                            )
                        )
                    )
                )
                .then(literal("remove")
                    .then(argument("name", string())
                        .then(argument("item_id", string())
                            .executes(c -> {
                                String name = StringArgumentType.getString(c, "name");
                                String itemId = normalizeItemId(StringArgumentType.getString(c, "item_id"));
                                var embed = c.getSource().getEmbed();

                                if (database == null || !database.isInitialized()) {
                                    embed.title("Kit Update Failed")
                                        .description("Database not connected.")
                                        .errorColor();
                                    return OK;
                                }

                                try {
                                    if (database.removeKitItem(name, itemId)) {
                                        module.fireWebhookEvent("kit_item_removed", Map.of(
                                            "kit_name", name,
                                            "item_id", itemId
                                        ));
                                        embed.title("Kit Updated")
                                            .description("Removed **" + itemId + "** from kit **" + name + "**")
                                            .successColor();
                                    } else {
                                        module.fireWebhookEvent("kit_item_remove_miss", Map.of(
                                            "kit_name", name,
                                            "item_id", itemId
                                        ));
                                        embed.title("Kit Update")
                                            .description("Item not found in kit: **" + itemId + "**")
                                            .primaryColor();
                                    }
                                } catch (Exception e) {
                                    module.fireWebhookEvent("kit_item_remove_failed", Map.of(
                                        "kit_name", name,
                                        "item_id", itemId,
                                        "message", e.getMessage()
                                    ));
                                    embed.title("Kit Update Failed")
                                        .description("Error: " + e.getMessage())
                                        .errorColor();
                                }
                                return OK;
                            })
                        )
                    )
                )
                .then(literal("delete")
                    .then(argument("name", string())
                        .executes(c -> {
                            String name = StringArgumentType.getString(c, "name");
                            var embed = c.getSource().getEmbed();

                            if (database == null || !database.isInitialized()) {
                                embed.title("Kit Delete Failed")
                                    .description("Database not connected.")
                                    .errorColor();
                                return OK;
                            }

                            try {
                                if (database.deleteKit(name)) {
                                    module.fireWebhookEvent("kit_deleted", Map.of("kit_name", name));
                                    embed.title("Kit Deleted")
                                        .description("Removed kit **" + name + "**")
                                        .successColor();
                                } else {
                                    module.fireWebhookEvent("kit_delete_miss", Map.of("kit_name", name));
                                    embed.title("Kit Not Found")
                                        .description("No kit named: **" + name + "**")
                                        .errorColor();
                                }
                            } catch (Exception e) {
                                module.fireWebhookEvent("kit_delete_failed", Map.of(
                                    "kit_name", name,
                                    "message", e.getMessage()
                                ));
                                embed.title("Kit Delete Failed")
                                    .description("Error: " + e.getMessage())
                                    .errorColor();
                            }
                            return OK;
                        })
                    )
                )
            )
            .then(literal("get")
                .then(argument("item_id", string())
                    .executes(c -> {
                        String itemId = normalizeItemId(StringArgumentType.getString(c, "item_id"));
                        return startRetrieval(c, "item:" + itemId, Map.of(itemId, 64));
                    })
                    .then(argument("count", integer(1, 100000))
                        .executes(c -> {
                            String itemId = normalizeItemId(StringArgumentType.getString(c, "item_id"));
                            int count = IntegerArgumentType.getInteger(c, "count");
                            return startRetrieval(c, "item:" + itemId, Map.of(itemId, count));
                        })
                    )
                )
                .then(literal("kit")
                    .then(argument("name", string())
                        .executes(c -> {
                            String kitName = StringArgumentType.getString(c, "name");
                            var embed = c.getSource().getEmbed();

                            if (database == null || !database.isInitialized()) {
                                embed.title("Retrieve Failed")
                                    .description("Database not connected.")
                                    .errorColor();
                                return OK;
                            }

                            try {
                                var kitItems = database.loadKit(kitName);
                                if (kitItems.isEmpty()) {
                                    embed.title("Retrieve Failed")
                                        .description("Kit is empty or not found: **" + kitName + "**")
                                        .errorColor();
                                    return OK;
                                }
                                return startRetrieval(c, "kit:" + kitName, kitItems);
                            } catch (Exception e) {
                                embed.title("Retrieve Failed")
                                    .description("Error loading kit: " + e.getMessage())
                                    .errorColor();
                                return OK;
                            }
                        })
                    )
                )
                .then(literal("status")
                    .executes(c -> {
                        var retriever = module.getRetriever();
                        var embed = c.getSource().getEmbed()
                            .title("Retrieval Status")
                            .primaryColor();

                        embed.addField("State", retriever.getState().name(), true)
                            .addField("Detail", retriever.getStatus(), false);

                        if (retriever.getActiveRequestName() != null) {
                            embed.addField("Request", retriever.getActiveRequestName(), true);
                        }
                        embed.addField("Remaining Total", String.valueOf(retriever.getRemainingTotal()), true);

                        var remaining = retriever.getRemainingItems();
                        if (!remaining.isEmpty()) {
                            StringBuilder sb = new StringBuilder();
                            int shown = 0;
                            for (var item : remaining.entrySet()) {
                                if (item.getValue() <= 0) continue;
                                if (shown >= 15) {
                                    sb.append("... and more");
                                    break;
                                }
                                sb.append(item.getValue()).append("x ")
                                    .append(IndexExporter.toReadableName(item.getKey()))
                                    .append("\n");
                                shown++;
                            }
                            if (sb.length() > 0) {
                                embed.addField("Needed Items", sb.toString(), false);
                            }
                        }

                        return OK;
                    })
                )
                .then(literal("stop")
                    .executes(c -> {
                        module.stopRetrieval();
                        c.getSource().getEmbed()
                            .title("Retrieval Stopped")
                            .description("Stopped active stash retrieval task.")
                            .successColor();
                        return OK;
                    })
                )
            )
            .then(literal("lanes")
                .executes(c -> {
                    LaneCapacityReport report = module.getLaneCapacityReport();
                    LaneConstructionPlan construction = LaneConstructionPlan.assess(
                            report.laneStorage());
                    boolean importStagingAvailable = config.pos1 != null && config.pos2 != null
                            && index.getInRegion(config.pos1, config.pos2).stream()
                                    .anyMatch(index::isImportChest);
                    boolean canStageShortage = !report.canOrganize()
                            && report.canOrganizeWithImportStaging(importStagingAvailable);
                    String friendlyStatus = switch (report.status()) {
                        case READY -> report.canOrganizeWithImportStaging(importStagingAvailable)
                                ? "Good to go"
                                : "Register an import chest for mixed boxes";
                        case INSUFFICIENT_LANES -> canStageShortage
                                ? "Can reconcile into import staging"
                                : "You need a few more lanes";
                        case INSUFFICIENT_LANE_STORAGE -> canStageShortage
                                ? "Can reconcile into import staging"
                                : "Some lanes are too small";
                        case NEEDS_FRESH_SCAN -> "Run a fresh scan first";
                        case NEEDS_FRESH_CONTAINER_SCAN -> "Scan the chests again first";
                        case REGION_NOT_DEFINED -> "Set the stash area first";
                        case NO_SCANNED_CONTAINERS -> "Scan the stash first";
                        case NO_LANES_DETECTED -> canStageShortage
                                ? "Can reconcile into import staging"
                                : "No lanes found yet";
                    };
                    var embed = c.getSource().getEmbed()
                            .title("Your Stash Lane Plan")
                            .addField("Where Things Stand", friendlyStatus, true)
                            .addField("Lanes Found", String.valueOf(report.detectedLanes()), true)
                            .addField("Lanes We're Leaving Alone", String.valueOf(report.protectedLanes()), true)
                            .addField("Lanes We Can Use", String.valueOf(report.assignableLanes()), true)
                            .addField("Item Lanes Needed", String.valueOf(report.requiredStorageClasses()), true)
                            .addField("Import Chest Blocks", String.valueOf(index.getImportChestBlockCount()), true)
                            .addField("Lanes Still Free", String.valueOf(report.spareLanes()), true)
                            .addField("More Lanes Needed", String.valueOf(report.laneShortfall()), true)
                            .addField("Shulker Spots Available",
                                    String.valueOf(report.laneStorage().totalAssignableShulkerSlots()), true)
                            .addField("Shulker Spots Needed Now",
                                    String.valueOf(report.laneStorage().totalRequiredShulkerSlots()), true)
                            .addField("Spots After Full Compaction",
                                    String.valueOf(report.laneStorage().totalCompactedShulkerSlots()), true)
                            .addField("Spots Compaction Can Free",
                                    String.valueOf(report.laneStorage().totalReclaimableShulkerSlots()), true)
                            .addField("Double-Chest Space Now",
                                    String.format(Locale.ROOT, "%.2f",
                                            construction.existingAssignableDoubleChestEquivalent()), true)
                            .addField("Double Chests Needed Now",
                                    String.valueOf(construction.requiredDedicatedDoubleChests()), true)
                            .addField("Double Chests After Compaction",
                                    String.valueOf(construction.compactedRequiredDedicatedDoubleChests()), true)
                            .addField("New Lanes To Make",
                                    String.valueOf(construction.newLanesToBuild()), true)
                            .addField("Lanes To Make Bigger",
                                    String.valueOf(construction.existingLanesToExpand()), true)
                            .addField("Double Chests To Place",
                                    String.valueOf(construction.doubleChestsToAdd()), true)
                            .addField("Bulk / Empty Shulkers",
                                    report.bulkShulkers() + " / " + report.emptyShulkers(), true)
                            .addField("Mixed / Unclassified",
                                    report.mixedShulkers() + " / " + report.unclassifiedShulkers(), true);

                    if (!report.laneStorage().unresolvedStackSizeClasses().isEmpty()) {
                        String unresolved = String.join(", ",
                                report.laneStorage().unresolvedStackSizeClasses());
                        if (unresolved.length() > 1000) unresolved = unresolved.substring(0, 997) + "...";
                        embed.addField("Stack Sizes We Couldn't Verify",
                                unresolved + "\nCounted as non-stackable so the plan stays safe.", false);
                    }

                    switch (report.status()) {
                        case READY -> embed.description("Everything has a lane with enough room. You're good to start organizing.")
                                .successColor();
                        case INSUFFICIENT_LANES -> {
                            if (canStageShortage) {
                                embed.description("You're short " + report.laneShortfall()
                                                + " permanent lane(s), but organization can continue. New reconciled boxes will wait in your import chests.")
                                        .inQueueColor();
                            } else {
                                embed.description("You're short " + report.laneShortfall()
                                                + " lane(s). Make those, or register an import chest for temporary staging.")
                                        .errorColor();
                            }
                        }
                        case INSUFFICIENT_LANE_STORAGE -> {
                            if (canStageShortage) {
                                embed.description("Some lanes are too small, but organization can continue. New reconciled boxes without room will wait in your import chests.")
                                        .inQueueColor();
                            } else {
                                embed.description("You have enough lanes, but some are too small. Check the build list or register an import chest for temporary staging.")
                                        .errorColor();
                            }
                        }
                        case NEEDS_FRESH_SCAN -> embed.description("Some shulkers still aren't identified. Run a fresh stash scan before you organize.")
                                .errorColor();
                        case NEEDS_FRESH_CONTAINER_SCAN -> embed.description("Some double chests came from an older scan. Scan the stash again so we can count them properly.")
                                .errorColor();
                        case REGION_NOT_DEFINED -> embed.description("Set the stash corners with pos1 and pos2 first.").errorColor();
                        case NO_SCANNED_CONTAINERS -> embed.description("Scan the stash first so there's something to work from.").errorColor();
                        case NO_LANES_DETECTED -> {
                            if (canStageShortage) {
                                embed.description("No permanent lanes were found. Reconciliation can still pack loose items into your import chests for now.")
                                        .inQueueColor();
                            } else {
                                embed.description("The last scan didn't find any storage lanes or registered import staging.")
                                        .errorColor();
                            }
                        }
                    }

                    if (!report.storageClasses().isEmpty()) {
                        String classes = String.join(", ", report.storageClasses());
                        if (classes.length() > 1000) classes = classes.substring(0, 997) + "...";
                        embed.addField("Bulk Items We Found", classes, false);
                    }
                    if (!report.laneStorage().allocations().isEmpty()) {
                        String allocations = report.laneStorage().allocations().stream()
                                .limit(20)
                                .map(allocation -> IndexExporter.toReadableName(
                                            allocation.demand().storageClass())
                                        + " → Lane " + allocation.lane().id() + ": "
                                        + LaneConstructionPlan.doubleChestsForSlots(
                                            allocation.demand().requiredShulkerSlots())
                                        + " double chest(s) now, "
                                        + LaneConstructionPlan.doubleChestsForSlots(
                                            allocation.demand().compactedShulkerSlots())
                                        + " after compaction ("
                                        + allocation.demand().requiredShulkerSlots()
                                        + "→" + allocation.demand().compactedShulkerSlots()
                                        + "/" + allocation.lane().shulkerSlots()
                                        + " shulker slots)")
                                .collect(Collectors.joining("\n"));
                        if (report.laneStorage().allocations().size() > 20) allocations += "\n...";
                        embed.addField("How Big Each Lane Needs To Be", allocations, false);
                    }
                    if (!report.laneStorage().unassigned().isEmpty()) {
                        String oversized = report.laneStorage().unassigned().stream()
                                .limit(20)
                                .map(demand -> demand.storageClass() + " (needs "
                                        + demand.requiredShulkerSlots() + " now, "
                                        + demand.compactedShulkerSlots() + " compacted)")
                                .collect(Collectors.joining(", "));
                        embed.addField("Items With Nowhere To Go Yet", oversized, false);
                    }
                    if (!construction.requirements().isEmpty()) {
                        String recommendations = construction.requirements().stream()
                                .limit(20)
                                .map(requirement -> {
                                    String itemName = IndexExporter.toReadableName(
                                            requirement.demand().storageClass());
                                    if (requirement.action()
                                            == LaneConstructionPlan.Action.BUILD_NEW_LANE) {
                                        return "Make a new " + itemName + " lane with at least "
                                                + requirement.requiredDoubleChests()
                                                + " double chest(s)";
                                    }
                                    return "Make Lane " + requirement.lane().id() + " bigger for "
                                            + itemName + " by adding "
                                            + requirement.doubleChestsToAdd()
                                            + " double chest(s)";
                                })
                                .collect(Collectors.joining("\n"));
                        if (construction.requirements().size() > 20) recommendations += "\n...";
                        embed.addField("What You Need To Build", recommendations, false);
                    }
                    embed.footer("Want the full breakdown? Use /stash lanes export", null);
                    return OK;
                })
                .then(literal("export")
                    .executes(c -> {
                        LaneCapacityReport report = module.getLaneCapacityReport();
                        LaneConstructionPlan construction = LaneConstructionPlan.assess(
                                report.laneStorage());
                        byte[] workbook = LaneReportExporter.exportWorkbook(report);
                        c.getSource().getEmbed()
                            .title("Your Stash Lane Workbook")
                            .description("Here you go — " + report.detectedLanes()
                                    + " lane(s) found. The workbook says to make "
                                    + construction.newLanesToBuild() + " new lane(s), make "
                                    + construction.existingLanesToExpand() + " lane(s) bigger, and place "
                                    + construction.doubleChestsToAdd() + " double chest(s) altogether.")
                            .successColor()
                            .fileAttachment(new com.zenith.discord.Embed.FileAttachment(
                                    "stash_lane_report.xlsx", workbook));
                        return OK;
                    }))
            )
            .then(literal("organize")
                .executes(c -> {
                    var embed = c.getSource().getEmbed();
                    String blocker = module.getOrganizerBlocker();
                    if (blocker != null) {
                        embed.title("Organize Failed")
                            .description("Cannot start organizer: " + blocker + ".")
                            .errorColor();
                        return OK;
                    }

                    if (module.startOrganizer()) {
                        embed.title("Organizing Stash")
                            .description("Planning item moves across containers...")
                            .successColor();
                        if (config.pos1 != null && config.pos2 != null) {
                            embed.addField("Region", formatPos(config.pos1) + " → " + formatPos(config.pos2), false);
                        }
                        embed.addField("Containers", String.valueOf(index.size()), true);
                    } else {
                        embed.title("Organize Failed")
                            .description("Check that region is defined, containers are scanned, and no player is actively controlling the proxy.\n"
                                + SPECTATOR_TESTING_TIP)
                            .errorColor();
                    }
                    return OK;
                })
                .then(literal("stop")
                    .executes(c -> {
                        var organizer = module.getOrganizer();
                        if (organizer != null && module.stopOrganizer()) {
                            c.getSource().getEmbed()
                                .title("Organizer Stopped")
                                .addField("Completed", String.valueOf(organizer.getCompletedTasks()), true)
                                .addField("Total Planned", String.valueOf(organizer.getTotalTasks()), true)
                                .primaryColor();
                        } else {
                            c.getSource().getEmbed()
                                .title("Organizer")
                                .description("Organizer is not running.")
                                .primaryColor();
                        }
                        return OK;
                    })
                )
                .then(literal("resume")
                    .executes(c -> {
                        var organizer = module.getOrganizer();
                        var embed = c.getSource().getEmbed().title("Organizer Resume");
                        if (organizer == null || !organizer.hasDurableCheckpoint()) {
                            embed.description("There is no restart checkpoint waiting to resume.")
                                .primaryColor();
                        } else if (module.requestOrganizerCheckpointResume()) {
                            embed.description("Resume is armed. The organizer will continue after the normal cooldown and quiet checks.")
                                .addField("Progress",
                                    organizer.getCompletedTasks() + "/" + organizer.getTotalTasks(), true)
                                .successColor();
                        } else if (organizer.getDurableResumeBlocker() != null) {
                            embed.description("The checkpoint is safe, but it cannot resume yet: "
                                    + organizer.getDurableResumeBlocker() + ".")
                                .errorColor();
                        } else {
                            embed.description("The checkpoint could not be armed for resume: "
                                    + Objects.toString(organizer.getDurableRecoveryError(), "unknown checkpoint problem") + ".")
                                .errorColor();
                        }
                        return OK;
                    })
                )
                .then(literal("discard")
                    .then(literal("confirm")
                        .executes(c -> {
                            var organizer = module.getOrganizer();
                            var embed = c.getSource().getEmbed().title("Organizer Checkpoint");
                            if (organizer == null || !organizer.hasDurableCheckpoint()) {
                                embed.description("There is no saved organizer checkpoint to discard.")
                                    .primaryColor();
                            } else if (module.discardOrganizerCheckpoint()) {
                                embed.description("The saved plan and queue were discarded. No game items were moved.")
                                    .successColor();
                            } else {
                                embed.description("The checkpoint could not be discarded while the organizer is running.")
                                    .errorColor();
                            }
                            return OK;
                        }))
                )
                .then(literal("status")
                    .executes(c -> {
                        var organizer = module.getOrganizer();
                        var embed = c.getSource().getEmbed()
                            .title("Organizer Status")
                            .primaryColor();

                        if (organizer == null) {
                            embed.description("Organizer not available.");
                        } else {
                            embed.addField("State", organizer.getState().name(), true);
                            embed.addField("Detail", organizer.getStatus(), false);
                            embed.addField("Restart Safe",
                                organizer.hasDurableCheckpoint() ? "Yes" : "No", true);
                            if (organizer.isDurableRecoveryLoaded()) {
                                String blocker = organizer.getDurableResumeBlocker();
                                embed.addField("Restart Recovery",
                                    blocker == null ? "Waiting for cooldown" : "Waiting: " + blocker,
                                    false);
                            } else if (organizer.getDurableRecoveryError() != null) {
                                embed.addField("Checkpoint Problem",
                                    organizer.getDurableRecoveryError(), false);
                            }
                            embed.addField("Task Handoffs",
                                String.valueOf(module.getOrganizerPreemptionCount()), true);
                            if (organizer.isYielded()) {
                                embed.addField("Paused From",
                                    String.valueOf(organizer.getYieldedFromState()), true);
                                embed.addField("Minimum Resume Hold",
                                    module.getOrganizerPreemptionCooldownRemainingSeconds() + "s", true);
                            }
                            if (module.getControlledJob()
                                    == com.zenith.plugin.stashmanager.orchestration.JobContinuanceManager.Job.ORGANIZE) {
                                embed.addField("Proxy Control Grace",
                                    module.getProxyControlGraceRemainingSeconds() + "s", true);
                            }
                            if (organizer.getTotalTasks() > 0) {
                                embed.addField("Progress",
                                    organizer.getCompletedTasks() + "/" + organizer.getTotalTasks(), true);
                            }
                            if (organizer.isUsingImportStaging() || organizer.getPermanentLaneGaps() > 0) {
                                embed.addField("Boxes Waiting in Imports",
                                    String.valueOf(organizer.getStagedShulkers()), true);
                                embed.addField("Staged Item Types",
                                    String.valueOf(organizer.getStagingStorageClassCount()), true);
                                embed.addField("Permanent Lane Gaps",
                                    String.valueOf(organizer.getPermanentLaneGaps()), true);
                            }
                        }
                        return OK;
                    })
                )
            )
            .then(literal("db")
                .then(literal("status")
                    .executes(c -> {
                        var embed = c.getSource().getEmbed()
                            .title("Database Status")
                            .primaryColor();

                        if (database == null || !database.isInitialized()) {
                            embed.description("Database is not connected.")
                                .addField("Enabled", String.valueOf(config.databaseEnabled), true)
                                .addField("URL", config.databaseUrl, false);
                            return OK;
                        }

                        try {
                            var stats = database.getStatistics();
                            embed.description("Database connected and operational")
                                .addField("Total Containers", String.valueOf(stats.getOrDefault("total_containers", 0)), true)
                                .addField("Total Items", String.valueOf(stats.getOrDefault("total_items", 0L)), true)
                                .addField("Unique Item Types", String.valueOf(stats.getOrDefault("unique_item_types", 0)), true)
                                .addField("Total Shulkers", String.valueOf(stats.getOrDefault("total_shulkers", 0)), true);
                        } catch (Exception e) {
                            embed.description("Database error: " + e.getMessage())
                                .errorColor();
                        }
                        return OK;
                    })
                )
                .then(literal("clear")
                    .executes(c -> {
                        var embed = c.getSource().getEmbed();
                        if (database == null || !database.isInitialized()) {
                            embed.title("Database Clear Failed")
                                .description("Database is not connected.")
                                .errorColor();
                            return OK;
                        }

                        try {
                            database.clearAll();
                            embed.title("Database Cleared")
                                .description("All container data has been removed from the database.")
                                .successColor();
                        } catch (Exception e) {
                            embed.title("Database Clear Failed")
                                .description("Error: " + e.getMessage())
                                .errorColor();
                        }
                        return OK;
                    })
                )
            )
            .then(buildConfigSubtree());
    }

    private void renderListPage(CommandContext context, int page) {
        var embed = context.getEmbed();

        // Prefer database if available
        boolean useDb = database != null && database.isInitialized();
        int totalCount;
        int totalPages;
        List<ContainerEntry> entries;

        if (useDb) {
            try {
                totalCount = database.getContainerCount();
                if (totalCount == 0) {
                    embed.title("Stash Index")
                        .description("No containers indexed.")
                        .primaryColor();
                    return;
                }
                totalPages = Math.max(1, (int) Math.ceil((double) totalCount / PAGE_SIZE));
                page = Math.max(1, Math.min(page, totalPages));
                entries = database.getContainersPage(page, PAGE_SIZE);
            } catch (Exception e) {
                embed.title("List Failed")
                    .description("Database query failed: " + e.getMessage())
                    .errorColor();
                return;
            }
        } else {
            totalCount = index.size();
            if (totalCount == 0) {
                embed.title("Stash Index")
                    .description("No containers indexed.")
                    .primaryColor();
                return;
            }
            totalPages = index.totalPages(PAGE_SIZE);
            page = Math.max(1, Math.min(page, totalPages));
            entries = index.getPage(page, PAGE_SIZE);
        }

        embed.title("Stash Index — Page " + page + "/" + totalPages)
            .description(totalCount + " containers indexed" + (useDb ? " (from database)" : ""))
            .primaryColor();

        int fieldCount = 0;
        for (ContainerEntry entry : entries) {
            if (fieldCount >= 25) break; // Discord embed field limit

            String name = entry.readableBlockType() + " at " + entry.posString();
            StringBuilder value = new StringBuilder();

            int itemCount = 0;
            for (var item : entry.items().entrySet()) {
                if (itemCount >= 3) {
                    value.append("... and ").append(entry.items().size() - 3).append(" more");
                    break;
                }
                if (value.length() > 0) value.append("\n");
                value.append(item.getValue()).append("x ")
                    .append(IndexExporter.toReadableName(item.getKey()));
                itemCount++;
            }

            if (entry.shulkerCount() > 0) {
                value.append("\n(").append(entry.shulkerCount()).append(" shulker boxes)");
            }

            if (value.length() == 0) value.append("Empty");

            embed.addField(name, value.toString(), false);
            fieldCount++;
        }

        embed.footer("Index contains " + totalCount + " containers | Last scan: "
            + index.timeSinceLastScan(), null);
    }

    private String formatPos(int[] pos) {
        return pos[0] + ", " + pos[1] + ", " + pos[2];
    }

    // Discord embed descriptions have a real size limit far beyond Zenith's own
    // 1024-char help threshold, but a wall of debug events can still exceed it —
    // split across multiple messages instead of cramming everything into one.
    private static final int DEBUG_CHUNK_CHAR_BUDGET = 3800;

    private void sendRecentDebugEvents(CommandContext source, int limit) {
        var recent = module.getDebugRecorder().recent(limit);
        if (recent.isEmpty()) {
            source.getEmbed().title("Recent Debug Events").description("No recent debug events.");
            return;
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (var event : recent) {
            String line = event.timestamp() + " | " + event.stage() + " | " + event.detail();
            if (!current.isEmpty() && current.length() + line.length() + 1 > DEBUG_CHUNK_CHAR_BUDGET) {
                chunks.add(current.toString());
                current = new StringBuilder();
            }
            if (!current.isEmpty()) current.append('\n');
            current.append(line);
        }
        if (!current.isEmpty()) chunks.add(current.toString());

        for (int i = 0; i < chunks.size(); i++) {
            String title = chunks.size() > 1
                ? "Recent Debug Events (" + (i + 1) + "/" + chunks.size() + ")"
                : "Recent Debug Events";
            if (i == 0) {
                source.getEmbed().title(title).description(chunks.get(i));
            } else {
                var embed = com.zenith.discord.Embed.builder().title(title).description(chunks.get(i));
                DISCORD.sendEmbedMessage(embed);
            }
        }
    }

    // Config Subtree
    private LiteralArgumentBuilder<CommandContext> buildConfigSubtree() {
        return literal("config")
            // Show all config
            .executes(c -> {
                var embed = c.getSource().getEmbed()
                    .title("Stash Manager Configuration")
                    .primaryColor();

                // Scanner
                embed.addField("Scan Delay", config.scanDelayTicks + " ticks", true);
                embed.addField("Open Timeout", config.openTimeoutTicks + " ticks", true);
                embed.addField("Max Containers", String.valueOf(config.maxContainers), true);
                embed.addField("Task Resume Cooldown", config.scanPreemptionCooldownSeconds + " seconds", true);
                embed.addField("Proxy Control Grace", config.proxyControlGraceSeconds + " seconds", true);
                embed.addField("Organizer Walk Timeout", config.organizerWalkTimeoutTicks + " ticks ("
                    + (config.organizerWalkTimeoutTicks / 20) + "s)", true);
                embed.addField("Waypoint Distance", String.valueOf(config.waypointDistance), true);
                embed.addField("Return to Start", config.returnToStart ? "Enabled" : "Disabled", true);

                // Database
                embed.addField("Database Enabled", String.valueOf(config.databaseEnabled), true);
                embed.addField("Database URL", config.databaseUrl, false);
                embed.addField("Database User", config.databaseUser, true);
                embed.addField("Database Pool Size", String.valueOf(config.databasePoolSize), true);
                embed.addField("Database Connected", String.valueOf(database != null && database.isInitialized()), true);

                // API
                embed.addField("API Enabled", String.valueOf(config.apiEnabled), true);
                embed.addField("API Bind", config.apiBindAddress + ":" + config.apiPort, true);
                embed.addField("API Threads", String.valueOf(config.apiThreads), true);
                embed.addField("API Key", config.apiKey.isBlank() ? "(none)" : "****" + config.apiKey.substring(Math.max(0, config.apiKey.length() - 4)), true);
                embed.addField("API Running", String.valueOf(apiServer != null && apiServer.isRunning()), true);

                // Updates
                embed.addField("Update Check On Load", String.valueOf(config.updateCheckOnLoad), true);
                embed.addField("Update Auto Download", String.valueOf(config.updateAutoDownload), true);
                embed.addField("Update Status", formatUpdaterState(updateService.getSnapshot()), true);
                if (updateService.getSnapshot().latestVersion() != null) {
                    embed.addField("Latest Release", updateService.getSnapshot().latestVersion(), true);
                }
                if (updateService.getSnapshot().stagedVersion() != null) {
                    embed.addField("Staged Update", updateService.getSnapshot().stagedVersion(), true);
                }
                if (updateService.getSnapshot().lastCheckedAt() != null) {
                    embed.addField("Last Update Check", UPDATE_TIME_FORMAT.format(updateService.getSnapshot().lastCheckedAt()), false);
                }
                if (updateService.getSnapshot().lastError() != null) {
                    embed.addField("Update Error", updateService.getSnapshot().lastError(), false);
                }

                return OK;
            })
            // Scanner settings
            .then(literal("scanDelay")
                .then(argument("ticks", integer(1, 200))
                    .executes(c -> {
                        config.scanDelayTicks = IntegerArgumentType.getInteger(c, "ticks");
                        c.getSource().getEmbed()
                            .title("Config Updated")
                            .description("scanDelayTicks = " + config.scanDelayTicks)
                            .successColor();
                        return OK;
                    })
                )
            )
            .then(literal("openTimeout")
                .then(argument("ticks", integer(1, 600))
                    .executes(c -> {
                        config.openTimeoutTicks = IntegerArgumentType.getInteger(c, "ticks");
                        c.getSource().getEmbed()
                            .title("Config Updated")
                            .description("openTimeoutTicks = " + config.openTimeoutTicks)
                            .successColor();
                        return OK;
                    })
                )
            )
            .then(literal("maxContainers")
                .then(argument("count", integer(1, 100000))
                    .executes(c -> {
                        config.maxContainers = IntegerArgumentType.getInteger(c, "count");
                        c.getSource().getEmbed()
                            .title("Config Updated")
                            .description("maxContainers = " + config.maxContainers)
                            .successColor();
                        return OK;
                    })
                )
            )
            .then(literal("preemptionCooldown")
                .then(argument("seconds", integer(1, 3600))
                    .executes(c -> {
                        config.scanPreemptionCooldownSeconds = IntegerArgumentType.getInteger(c, "seconds");
                        c.getSource().getEmbed()
                            .title("Config Updated")
                            .description("scanPreemptionCooldownSeconds = "
                                + config.scanPreemptionCooldownSeconds
                                + " (applies to the next scan or organize job)")
                            .successColor();
                        return OK;
                    })
                )
            )
            .then(literal("controlGrace")
                .then(argument("seconds", integer(60, 3600))
                    .executes(c -> {
                        config.proxyControlGraceSeconds = IntegerArgumentType.getInteger(c, "seconds");
                        c.getSource().getEmbed()
                            .title("Config Updated")
                            .description("proxyControlGraceSeconds = "
                                + config.proxyControlGraceSeconds
                                + " (applies to the next controlling client connection)")
                            .successColor();
                        return OK;
                    })
                )
            )
            .then(literal("walkTimeout")
                .then(argument("ticks", integer(20, 12000))
                    .executes(c -> {
                        config.organizerWalkTimeoutTicks = IntegerArgumentType.getInteger(c, "ticks");
                        c.getSource().getEmbed()
                            .title("Config Updated")
                            .description("organizerWalkTimeoutTicks = " + config.organizerWalkTimeoutTicks
                                + " (" + (config.organizerWalkTimeoutTicks / 20) + "s)")
                            .successColor();
                        return OK;
                    })
                )
            )
            .then(literal("waypointDistance")
                .then(argument("blocks", integer(1, 256))
                    .executes(c -> {
                        config.waypointDistance = IntegerArgumentType.getInteger(c, "blocks");
                        c.getSource().getEmbed()
                            .title("Config Updated")
                            .description("waypointDistance = " + config.waypointDistance)
                            .successColor();
                        return OK;
                    })
                )
            )
            .then(literal("returnToStart")
                .then(literal("on")
                    .executes(c -> {
                        config.returnToStart = true;
                        c.getSource().getEmbed()
                            .title("Config Updated")
                            .description("Return to start: **enabled**")
                            .successColor();
                        return OK;
                    })
                )
                .then(literal("off")
                    .executes(c -> {
                        config.returnToStart = false;
                        c.getSource().getEmbed()
                            .title("Config Updated")
                            .description("Return to start: **disabled**")
                            .successColor();
                        return OK;
                    })
                )
            )
            // Database settings
            .then(literal("db")
                .then(literal("enable")
                    .executes(c -> {
                        config.databaseEnabled = true;
                        c.getSource().getEmbed()
                            .title("Config Updated")
                            .description("Database enabled. Use `stash config db connect` to connect.")
                            .successColor();
                        return OK;
                    })
                )
                .then(literal("disable")
                    .executes(c -> {
                        config.databaseEnabled = false;
                        if (database != null) {
                            database.close();
                        }
                        c.getSource().getEmbed()
                            .title("Config Updated")
                            .description("Database disabled and disconnected.")
                            .successColor();
                        return OK;
                    })
                )
                .then(literal("url")
                    .then(argument("jdbc_url", greedyString())
                        .executes(c -> {
                            config.databaseUrl = StringArgumentType.getString(c, "jdbc_url");
                            c.getSource().getEmbed()
                                .title("Config Updated")
                                .description("Database URL = " + config.databaseUrl)
                                .successColor();
                            return OK;
                        })
                    )
                )
                .then(literal("user")
                    .then(argument("username", string())
                        .executes(c -> {
                            config.databaseUser = StringArgumentType.getString(c, "username");
                            c.getSource().getEmbed()
                                .title("Config Updated")
                                .description("Database user = " + config.databaseUser)
                                .successColor();
                            return OK;
                        })
                    )
                )
                .then(literal("password")
                    .then(argument("password", string())
                        .executes(c -> {
                            config.databasePassword = StringArgumentType.getString(c, "password");
                            c.getSource().getEmbed()
                                .title("Config Updated")
                                .description("Database password updated.")
                                .successColor();
                            return OK;
                        })
                    )
                )
                .then(literal("poolSize")
                    .then(argument("size", integer(1, 20))
                        .executes(c -> {
                            config.databasePoolSize = IntegerArgumentType.getInteger(c, "size");
                            c.getSource().getEmbed()
                                .title("Config Updated")
                                .description("Database pool size = " + config.databasePoolSize)
                                .successColor();
                            return OK;
                        })
                    )
                )
                .then(literal("connect")
                    .executes(c -> {
                        var embed = c.getSource().getEmbed();
                        if (!config.databaseEnabled) {
                            embed.title("Database Connect Failed")
                                .description("Database is not enabled. Run `stash config db enable` first.")
                                .errorColor();
                            return OK;
                        }
                        try {
                            if (database != null) {
                                database.close();
                            }
                            database.initialize(config);
                            embed.title("Database Connected")
                                .description("Successfully connected to: " + config.databaseUrl)
                                .successColor();
                        } catch (Exception e) {
                            embed.title("Database Connect Failed")
                                .description("Error: " + e.getMessage())
                                .errorColor();
                        }
                        return OK;
                    })
                )
            )
            // API settings
            .then(literal("api")
                .then(literal("enable")
                    .executes(c -> {
                        config.apiEnabled = true;
                        c.getSource().getEmbed()
                            .title("Config Updated")
                            .description("API enabled. Use `stash config api start` to start the server.")
                            .successColor();
                        return OK;
                    })
                )
                .then(literal("disable")
                    .executes(c -> {
                        config.apiEnabled = false;
                        if (apiServer != null) {
                            apiServer.close();
                        }
                        c.getSource().getEmbed()
                            .title("Config Updated")
                            .description("API disabled and server stopped.")
                            .successColor();
                        return OK;
                    })
                )
                .then(literal("port")
                    .then(argument("port", integer(1, 65535))
                        .executes(c -> {
                            config.apiPort = IntegerArgumentType.getInteger(c, "port");
                            c.getSource().getEmbed()
                                .title("Config Updated")
                                .description("API port = " + config.apiPort + ". Restart the API server to apply.")
                                .successColor();
                            return OK;
                        })
                    )
                )
                .then(literal("bind")
                    .then(argument("address", string())
                        .executes(c -> {
                            config.apiBindAddress = StringArgumentType.getString(c, "address");
                            c.getSource().getEmbed()
                                .title("Config Updated")
                                .description("API bind address = " + config.apiBindAddress + ". Restart the API server to apply.")
                                .successColor();
                            return OK;
                        })
                    )
                )
                .then(literal("key")
                    .then(argument("api_key", string())
                        .executes(c -> {
                            config.apiKey = StringArgumentType.getString(c, "api_key");
                            c.getSource().getEmbed()
                                .title("Config Updated")
                                .description("API key updated.")
                                .successColor();
                            return OK;
                        })
                    )
                )
                .then(literal("threads")
                    .then(argument("count", integer(1, 16))
                        .executes(c -> {
                            config.apiThreads = IntegerArgumentType.getInteger(c, "count");
                            c.getSource().getEmbed()
                                .title("Config Updated")
                                .description("API threads = " + config.apiThreads + ". Restart the API server to apply.")
                                .successColor();
                            return OK;
                        })
                    )
                )
                .then(literal("start")
                    .executes(c -> {
                        var embed = c.getSource().getEmbed();
                        if (apiServer != null && apiServer.isRunning()) {
                            embed.title("API Server")
                                .description("Server is already running on port " + config.apiPort)
                                .primaryColor();
                            return OK;
                        }
                        try {
                            config.apiEnabled = true;
                            if (apiServer != null) {
                                apiServer.start();
                            }
                            embed.title("API Server Started")
                                .description("Listening on " + config.apiBindAddress + ":" + config.apiPort)
                                .successColor();
                        } catch (Exception e) {
                            embed.title("API Server Failed")
                                .description("Error: " + e.getMessage())
                                .errorColor();
                        }
                        return OK;
                    })
                )
                .then(literal("stop")
                    .executes(c -> {
                        if (apiServer != null) {
                            apiServer.close();
                        }
                        c.getSource().getEmbed()
                            .title("API Server Stopped")
                            .successColor();
                        return OK;
                    })
                )
            )
            // Update settings
            .then(literal("updates")
                .executes(c -> {
                    var snapshot = updateService.getSnapshot();
                    var embed = c.getSource().getEmbed()
                        .title("Update Configuration")
                        .addField("Check On Load", String.valueOf(config.updateCheckOnLoad), true)
                        .addField("Auto Download", String.valueOf(config.updateAutoDownload), true)
                        .addField("Status", formatUpdaterState(snapshot), true)
                        .addField("Current Version", snapshot.currentVersion(), true)
                        .primaryColor();
                    if (snapshot.latestVersion() != null) {
                        embed.addField("Latest Release", snapshot.latestVersion(), true);
                    }
                    if (snapshot.stagedVersion() != null) {
                        embed.addField("Staged Update", snapshot.stagedVersion(), true);
                    }
                    if (snapshot.stagedJarName() != null) {
                        embed.addField("Staged Jar", snapshot.stagedJarName(), false);
                    }
                    if (snapshot.lastCheckedAt() != null) {
                        embed.addField("Last Checked", UPDATE_TIME_FORMAT.format(snapshot.lastCheckedAt()), false);
                    }
                    if (snapshot.lastError() != null) {
                        embed.addField("Last Error", snapshot.lastError(), false);
                    }
                    return OK;
                })
                .then(literal("checkOnLoad")
                    .then(literal("on")
                        .executes(c -> {
                            config.updateCheckOnLoad = true;
                            c.getSource().getEmbed()
                                .title("Config Updated")
                                .description("Plugin update checks on load are enabled.")
                                .successColor();
                            return OK;
                        })
                    )
                    .then(literal("off")
                        .executes(c -> {
                            config.updateCheckOnLoad = false;
                            c.getSource().getEmbed()
                                .title("Config Updated")
                                .description("Plugin update checks on load are disabled.")
                                .successColor();
                            return OK;
                        })
                    )
                )
                .then(literal("autoDownload")
                    .then(literal("on")
                        .executes(c -> {
                            config.updateAutoDownload = true;
                            c.getSource().getEmbed()
                                .title("Config Updated")
                                .description("New plugin releases will be staged automatically on startup checks.")
                                .successColor();
                            return OK;
                        })
                    )
                    .then(literal("off")
                        .executes(c -> {
                            config.updateAutoDownload = false;
                            c.getSource().getEmbed()
                                .title("Config Updated")
                                .description("Startup checks will notify only and will not stage downloads automatically.")
                                .successColor();
                            return OK;
                        })
                    )
                )
            );
    }

    private void renderUpdateResult(final CommandContext context,
                                    final PluginUpdateService.UpdateResult result,
                                    final String title) {
        var embed = context.getEmbed()
            .title(title)
            .description(result.message())
            .addField("Current Version", result.currentVersion(), true);

        if (result.latestVersion() != null) {
            embed.addField("Latest Release", result.latestVersion(), true);
        }
        if (result.stagedPath() != null) {
            embed.addField("Staged Jar", result.stagedPath().getFileName().toString(), false);
        }

        switch (result.outcome()) {
            case UP_TO_DATE -> embed.successColor();
            case UPDATE_AVAILABLE -> embed.primaryColor();
            case STAGED, ALREADY_STAGED -> embed.successColor();
            case FAILED -> embed.errorColor();
        }
    }

    private int updateTargetedImportChest(
            com.mojang.brigadier.context.CommandContext<CommandContext> command,
            boolean add) {
        var embed = command.getSource().getEmbed();
        if (database == null || !database.isInitialized()) {
            embed.title("Import Chest Failed")
                .description("Database is not connected; import assignments must be persistent.")
                .errorColor();
            return OK;
        }

        String automationBlocker = importMutationBlocker();
        if (automationBlocker != null) {
            embed.title("Import Chest Failed")
                .description("Cannot change import assignments while " + automationBlocker + ".")
                .errorColor();
            return OK;
        }

        ChestTarget target = targetedChest();
        if (!target.valid()) {
            embed.title("Import Chest Failed")
                .description(target.failure())
                .errorColor();
            return OK;
        }
        List<int[]> positions = target.positions();

        if (add && (config.pos1 == null || config.pos2 == null)) {
            embed.title("Import Chest Failed")
                .description("Set stash pos1 and pos2 before assigning imports so the target region can be validated.")
                .errorColor();
            return OK;
        }
        if (add && positions.stream().anyMatch(pos -> !isInsideConfiguredRegion(pos))) {
            embed.title("Import Chest Failed")
                .description("The targeted chest must be inside the configured stash region.")
                .errorColor();
            return OK;
        }
        if (add && positions.stream().anyMatch(this::isIndexedLanePosition)) {
            embed.title("Import Chest Conflict")
                .description("The targeted chest belongs to a detected storage lane. A permanent lane cannot also be registered as an import.")
                .errorColor();
            return OK;
        }

        long alreadyRegistered = positions.stream()
                .filter(pos -> index.isImportChest(pos[0], pos[1], pos[2]))
                .count();
        if (add && alreadyRegistered == positions.size()) {
            embed.title("Import Chest Already Assigned")
                .description("This chest is already registered as an import source; no changes were made.")
                .primaryColor();
            return OK;
        }
        if (add && alreadyRegistered > 0) {
            embed.title("Import Chest Conflict")
                .description("Only part of this double chest is already registered. Run `/stash import remove` while facing it, then add it again.")
                .errorColor();
            return OK;
        }
        if (!add && alreadyRegistered == 0) {
            embed.title("Import Chest Not Assigned")
                .description("This chest is not registered as an import source; no changes were made.")
                .primaryColor();
            return OK;
        }

        try {
            if (add) index.addImportChests(positions);
            else index.removeImportChests(positions);
        } catch (Exception e) {
            embed.title("Import Chest Failed")
                .description("Database error: " + e.getMessage())
                .errorColor();
            return OK;
        }

        int[] primary = positions.get(0);
        String inventoryType = positions.size() == 2 ? "Double chest" : "Chest";
        embed.title(add ? "Import Chest Assigned" : "Import Chest Removed")
            .description(inventoryType + " at " + formatPos(primary)
                + (add
                    ? " will be drained as intake during `/stash organize`."
                    : " is no longer managed as intake."))
            .successColor();
        module.getDebugRecorder().record(add ? "import_chest_added" : "import_chest_removed",
            "position=" + formatPos(primary) + ", block_positions=" + positions.size());
        return OK;
    }

    private record ChestTarget(List<int[]> positions, String failure) {
        private ChestTarget {
            positions = positions == null ? List.of() : List.copyOf(positions);
        }

        boolean valid() {
            return failure == null && !positions.isEmpty();
        }

        static ChestTarget failed(String failure) {
            return new ChestTarget(List.of(), failure);
        }
    }

    private ChestTarget targetedChest() {
        if (Proxy.getInstance().hasActivePlayer()) BOT.syncFromCache(true);
        var ray = RaycastHelper.playerBlockRaycast(BOT.getBlockReachDistance(), false);
        if (!ray.hit() || !(BlockRegistry.CHEST.equals(ray.block())
                || BlockRegistry.TRAPPED_CHEST.equals(ray.block()))) {
            module.getDebugRecorder().record("import_chest_target_failed",
                    "ray_hit=" + ray.hit()
                            + ", block=" + ray.block().name()
                            + ", reach=" + BOT.getBlockReachDistance()
                            + ", yaw=" + BOT.getYaw()
                            + ", pitch=" + BOT.getPitch());
            return ChestTarget.failed("Face a chest or trapped chest within normal interaction range and try again.");
        }

        var state = World.getBlockState(ray.x(), ray.y(), ray.z());
        ChestType chestType = state.getProperty(BlockStateProperties.CHEST_TYPE);
        if (chestType == null) {
            return ChestTarget.failed("The targeted chest state is incomplete; wait for the chunk to finish loading and try again.");
        }
        boolean expectedDouble = chestType != ChestType.SINGLE;
        var resolution = DoubleChestIdentity.resolve(ray.x(), ray.y(), ray.z(), expectedDouble);
        if (expectedDouble && !resolution.identityKnown()) {
            return ChestTarget.failed("The other half of this double chest could not be verified. Wait for both blocks to load and try again.");
        }

        List<int[]> positions = resolution.blocks();
        if (positions.size() != (expectedDouble ? 2 : 1)
                || positions.stream().anyMatch(pos -> pos == null || pos.length < 3
                    || !World.isInWorldBounds(pos[0], pos[1], pos[2]))) {
            return ChestTarget.failed("The targeted chest resolved to invalid block coordinates; no assignment was written.");
        }
        long uniquePositions = positions.stream()
                .map(pos -> pos[0] + ":" + pos[1] + ":" + pos[2])
                .distinct()
                .count();
        if (uniquePositions != positions.size()) {
            return ChestTarget.failed("The targeted double chest resolved to duplicate block positions; no assignment was written.");
        }
        for (int[] pos : positions) {
            var block = World.getBlock(pos[0], pos[1], pos[2]);
            if (!(BlockRegistry.CHEST.equals(block) || BlockRegistry.TRAPPED_CHEST.equals(block))) {
                return ChestTarget.failed("Every resolved block must still be a chest or trapped chest; no assignment was written.");
            }
        }
        return new ChestTarget(positions, null);
    }

    private boolean isInsideConfiguredRegion(int[] pos) {
        int minX = Math.min(config.pos1[0], config.pos2[0]);
        int minY = Math.min(config.pos1[1], config.pos2[1]);
        int minZ = Math.min(config.pos1[2], config.pos2[2]);
        int maxX = Math.max(config.pos1[0], config.pos2[0]);
        int maxY = Math.max(config.pos1[1], config.pos2[1]);
        int maxZ = Math.max(config.pos1[2], config.pos2[2]);
        return pos[0] >= minX && pos[0] <= maxX
                && pos[1] >= minY && pos[1] <= maxY
                && pos[2] >= minZ && pos[2] <= maxZ;
    }

    private boolean isIndexedLanePosition(int[] target) {
        return index.detectFifoLanes().stream().anyMatch(lane ->
                samePosition(target, lane.inputPos()) || samePosition(target, lane.outputPos()));
    }

    private static boolean samePosition(int[] first, int[] second) {
        return first[0] == second[0] && first[1] == second[1] && first[2] == second[2];
    }

    private String importMutationBlocker() {
        var scanState = module.getState();
        if (scanState != StashManagerModule.ScanState.IDLE
                && scanState != StashManagerModule.ScanState.DONE) {
            return "a stash scan is active (state=" + scanState + ")";
        }
        if (module.getOrganizer() != null && module.getOrganizer().isActive()) {
            return "the organizer is active";
        }
        if (module.getRetriever() != null && module.getRetriever().isActive()) {
            return "a retrieval job is active";
        }
        return null;
    }

    private int purgeImportChests(
            com.mojang.brigadier.context.CommandContext<CommandContext> command,
            boolean confirmed) {
        var embed = command.getSource().getEmbed();
        if (database == null || !database.isInitialized()) {
            embed.title("Import Purge Failed")
                .description("Database is not connected; persistent assignments cannot be changed safely.")
                .errorColor();
            return OK;
        }

        int count = index.getImportChestBlockCount();
        if (count == 0) {
            embed.title("No Import Chests")
                .description("There are no import chest assignments to purge.")
                .primaryColor();
            return OK;
        }
        if (!confirmed) {
            embed.title("Confirm Import Chest Purge")
                .description("This will remove all **" + count + "** persisted import chest block assignment(s). Run `/stash import purge confirm` to continue. No chest contents will be deleted.")
                .errorColor();
            return OK;
        }

        String blocker = importMutationBlocker();
        if (blocker != null) {
            embed.title("Import Purge Failed")
                .description("Cannot purge import assignments while " + blocker + ".")
                .errorColor();
            return OK;
        }

        try {
            int removed = index.clearImportChests();
            module.getDebugRecorder().record("import_chests_purged",
                    "cached_blocks=" + count + ", persisted_blocks_removed=" + removed);
            module.fireWebhookEvent("import_chests_purged", Map.of(
                    "cached_blocks", count,
                    "persisted_blocks_removed", removed));
            embed.title("Import Chests Purged")
                .description("Removed all import assignments (**" + removed + "** persisted block row(s)). Chest contents were not changed.")
                .successColor();
        } catch (Exception e) {
            embed.title("Import Purge Failed")
                .description("Database error: " + e.getMessage())
                .errorColor();
        }
        return OK;
    }

    private String formatUpdaterState(final PluginUpdateService.StatusSnapshot snapshot) {
        return switch (snapshot.state()) {
            case NOT_CHECKED -> "Not checked yet";
            case CHECKING -> "Checking for updates";
            case UPDATE_AVAILABLE -> "Update available";
            case UP_TO_DATE -> "Up to date";
            case STAGING -> "Downloading update";
            case STAGED -> snapshot.stagedVersion() == null
                ? "Update staged"
                : "Staged " + snapshot.stagedVersion();
            case FAILED -> snapshot.lastError() == null
                ? "Check failed"
                : "Check failed";
        };
    }

    private String withSpectatorTip(final String message) {
        if (message != null && message.contains("currently controlling the proxy")) {
            return message + "\n" + SPECTATOR_TESTING_TIP;
        }
        return message;
    }

    private int startRetrieval(com.mojang.brigadier.context.CommandContext<CommandContext> c,
                               String requestName,
                               Map<String, Integer> wantedItems) {
        var embed = c.getSource().getEmbed();
        if (module.startKitRetrieval(requestName, wantedItems)) {
            embed.title("Retrieval Started")
                .description("Request: **" + requestName + "**")
                .addField("Unique Items", String.valueOf(wantedItems.size()), true)
                .addField("Total Count", String.valueOf(wantedItems.values().stream().mapToInt(Integer::intValue).sum()), true)
                .successColor();
        } else {
            String reason = module.getRetrieveBlocker();
            embed.title("Retrieve Failed")
                .description(withSpectatorTip(reason != null ? reason : "Could not start stash retrieval."))
                .errorColor();
        }
        return OK;
    }

    private Map<String, Integer> snapshotPlayerInventory() {
        var snapshot = new java.util.LinkedHashMap<String, Integer>();
        var invCache = CACHE.getPlayerCache().getInventoryCache();
        var playerContainer = invCache.getPlayerInventory();
        if (playerContainer == null) return snapshot;

        // Raw player inventory container is size 46: 0-4=crafting, 5-8=armor, 9-35=main
        // inventory, 36-44=hotbar, 45=offhand — only 9-44 are actual carried items.
        for (int slot = 9; slot < 45; slot++) {
            ItemStack stack = playerContainer.getItemStack(slot);
            if (stack == null || stack.getAmount() <= 0) continue;
            String itemId = itemIdFromStack(stack);
            snapshot.merge(itemId, stack.getAmount(), Integer::sum);
        }

        return snapshot;
    }

    private String itemIdFromStack(ItemStack stack) {
        return ItemIdentifier.getItemId(stack);
    }

    private String normalizeItemId(String input) {
        return ItemResolver.resolve(input.toLowerCase());
    }
}
