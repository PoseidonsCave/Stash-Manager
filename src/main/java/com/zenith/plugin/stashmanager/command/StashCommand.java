package com.zenith.plugin.stashmanager.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.Proxy;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.command.api.CommandCategory;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.StashManagerModule;
import com.zenith.plugin.stashmanager.api.ApiServer;
import com.zenith.plugin.stashmanager.database.DatabaseManager;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import com.zenith.plugin.stashmanager.index.IndexExporter;
import com.zenith.plugin.stashmanager.organizer.StashOrganizer;
import com.zenith.plugin.stashmanager.update.PluginUpdateService;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static com.zenith.Globals.CACHE;

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
                "scan",
                "stop",
                "return",
                "status",
                "summary",
                "update",
                "update check",
                "list [page]",
                "export",
                "clear",
                "clearall",
                "label <x> <y> <z> <label>",
                "labels",
                "region save <name>",
                "region load <name>",
                "region list",
                "region delete <name>",
                "organize",
                "organize stop",
                "organize status",
                "db status",
                "db clear",
                "config",
                "config scanDelay <ticks>",
                "config openTimeout <ticks>",
                "config maxContainers <count>",
                "config returnToStart <on|off>",
                "config db enable/disable",
                "config db url <jdbc-url>",
                "config db user <username>",
                "config db password <password>",
                "config db poolSize <size>",
                "config db connect",
                "config api enable/disable",
                "config api port <port>",
                "config api bind <address>",
                "config api key <key>",
                "config api start/stop",
                "config webhook <url>",
                "config updates",
                "config updates checkOnLoad <on|off>",
                "config updates autoDownload <on|off>"
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
            .then(literal("organize")
                .executes(c -> {
                    var embed = c.getSource().getEmbed();
                    var organizer = module.getOrganizer();

                    if (organizer == null) {
                        embed.title("Organize Failed")
                            .description(config.organizerEnabled
                                ? "Organizer not available."
                                : "Organizer is disabled in config.")
                            .errorColor();
                        return OK;
                    }

                    if (module.getState() != StashManagerModule.ScanState.IDLE
                            && module.getState() != StashManagerModule.ScanState.DONE) {
                        embed.title("Organize Failed")
                            .description("Cannot organize while a scan is in progress. Stop the scan first.")
                            .errorColor();
                        return OK;
                    }

                    if (organizer.start()) {
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
                        if (organizer != null && organizer.isActive()) {
                            organizer.stop();
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
                            if (organizer.getTotalTasks() > 0) {
                                embed.addField("Progress",
                                    organizer.getCompletedTasks() + "/" + organizer.getTotalTasks(), true);
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

    // ── Config Subtree ──────────────────────────────────────────────────

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

                // Webhook
                embed.addField("Webhook URL", config.webhookUrl.isBlank() ? "(none)" : config.webhookUrl, false);

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
            // ── Scanner settings ─────────────────────────────────────
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
            // ── Database settings ────────────────────────────────────
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
            // ── API settings ─────────────────────────────────────────
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
            // ── Webhook ──────────────────────────────────────────────
            .then(literal("webhook")
                .executes(c -> {
                    c.getSource().getEmbed()
                        .title("Webhook Configuration")
                        .addField("URL", config.webhookUrl.isBlank() ? "(none)" : config.webhookUrl, false)
                        .primaryColor();
                    return OK;
                })
                .then(argument("url", greedyString())
                    .executes(c -> {
                        String url = StringArgumentType.getString(c, "url");
                        if (url.equalsIgnoreCase("off") || url.equalsIgnoreCase("none") || url.equalsIgnoreCase("clear")) {
                            config.webhookUrl = "";
                            c.getSource().getEmbed()
                                .title("Config Updated")
                                .description("Webhook URL cleared.")
                                .successColor();
                        } else {
                            config.webhookUrl = url;
                            c.getSource().getEmbed()
                                .title("Config Updated")
                                .description("Webhook URL = " + config.webhookUrl)
                                .successColor();
                        }
                        return OK;
                    })
                )
            )
            // ── Updates ──────────────────────────────────────────────
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
}
