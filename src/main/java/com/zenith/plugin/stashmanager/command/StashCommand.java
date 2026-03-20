package com.zenith.plugin.stashmanager.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.Command;
import com.zenith.command.CommandContext;
import com.zenith.command.CommandUsage;
import com.zenith.command.brigadier.CommandCategory;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.StashManagerModule;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import com.zenith.plugin.stashmanager.index.IndexExporter;

import java.util.List;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.zenith.Globals.CACHE;

// Main /stash command tree: pos1, pos2, scan, stop, status, list, export, clear.
public class StashCommand extends Command {

    private static final int PAGE_SIZE = 10;

    private final StashManagerConfig config;
    private final StashManagerModule module;
    private final ContainerIndex index;

    public StashCommand(StashManagerConfig config, StashManagerModule module, ContainerIndex index) {
        this.config = config;
        this.module = module;
        this.index = index;
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
                "status",
                "list [page]",
                "export",
                "clear"
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
                        embed.title("Scan Failed")
                            .description(config.pos1 == null || config.pos2 == null
                                ? "Region not defined. Set pos1 and pos2 first."
                                : "A scan is already in progress.")
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
            .then(literal("status")
                .executes(c -> {
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

                    if (index.getLastScanTimestamp() > 0) {
                        embed.footer("Last scan: " + index.timeSinceLastScan(), null);
                    }
                    return OK;
                })
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
                    if (index.size() == 0) {
                        embed.title("Export Failed")
                            .description("Index is empty — nothing to export.")
                            .errorColor();
                        return OK;
                    }

                    byte[] csv = IndexExporter.exportCsv(index.getAll());
                    embed.title("Stash Export")
                        .description("Exported " + index.size() + " containers to CSV")
                        .successColor()
                        .fileAttachment("stash_export.csv", csv);
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
            );
    }

    private void renderListPage(CommandContext context, int page) {
        var embed = context.getEmbed();

        if (index.size() == 0) {
            embed.title("Stash Index")
                .description("No containers indexed.")
                .primaryColor();
            return;
        }

        int totalPages = index.totalPages(PAGE_SIZE);
        page = Math.max(1, Math.min(page, totalPages));

        List<ContainerEntry> entries = index.getPage(page, PAGE_SIZE);

        embed.title("Stash Index — Page " + page + "/" + totalPages)
            .description(index.size() + " containers indexed")
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

        embed.footer("Index contains " + index.size() + " containers | Last scan: "
            + index.timeSinceLastScan(), null);
    }

    private String formatPos(int[] pos) {
        return pos[0] + ", " + pos[1] + ", " + pos[2];
    }
}
