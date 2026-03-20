package com.zenith.plugin.stashmanager.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.command.api.CommandCategory;
import com.zenith.plugin.stashmanager.database.DatabaseManager;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import com.zenith.plugin.stashmanager.index.IndexExporter;

import java.util.List;

// Search the container index by item name (prefers database when connected).
public class StashSearchCommand extends Command {

    private static final int MAX_RESULTS = 20;
    private static final int MAX_FIELDS = 25;

    private final ContainerIndex index;
    private final DatabaseManager database;

    public StashSearchCommand(ContainerIndex index, DatabaseManager database) {
        this.index = index;
        this.database = database;
    }

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("stashsearch")
            .category(CommandCategory.MODULE)
            .description("Search stash index by item name")
            .usageLines("search <item>")
            .aliases("ss")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("stashsearch")
            .then(argument("item", StringArgumentType.greedyString())
                .executes(c -> {
                    String search = StringArgumentType.getString(c, "item");
                    performSearch(c.getSource(), search);
                    return OK;
                })
            );
    }

    private void performSearch(CommandContext context, String search) {
        var embed = context.getEmbed();

        // Prefer database when connected
        List<ContainerEntry> results;
        int totalCount;
        boolean useDb = database != null && database.isInitialized();

        if (useDb) {
            try {
                results = database.searchContainers(search);
                totalCount = database.getTotalItemCount(search);
            } catch (Exception e) {
                embed.title("Search Failed")
                    .description("Database query failed: " + e.getMessage())
                    .errorColor();
                return;
            }
        } else {
            results = index.search(search);
            totalCount = index.totalItemCount(search);
        }
        if (results.isEmpty()) {
            embed.title("Search: " + search)
                .description("No containers found matching \"" + search + "\"")
                .errorColor();
            return;
        }

        String readableSearch = IndexExporter.toReadableName(search);

        embed.title("Search: " + readableSearch)
            .description("Found " + totalCount + " " + readableSearch.toLowerCase()
                + " across " + results.size() + " containers")
            .primaryColor();

        int fieldCount = 0;
        for (int i = 0; i < Math.min(results.size(), MAX_RESULTS); i++) {
            if (fieldCount >= MAX_FIELDS) break;

            ContainerEntry entry = results.get(i);
            String fieldName = entry.readableBlockType() + " at " + entry.posString();
            StringBuilder fieldValue = new StringBuilder();

            // Direct item count
            int directCount = 0;
            for (var item : entry.items().entrySet()) {
                if (item.getKey().toLowerCase().contains(search.toLowerCase())) {
                    directCount += item.getValue();
                }
            }

            // Check for items in shulkers
            int shulkerCount = 0;
            String shulkerColor = null;
            for (ContainerEntry.ShulkerDetail shulker : entry.shulkerDetails()) {
                for (var item : shulker.items().entrySet()) {
                    if (item.getKey().toLowerCase().contains(search.toLowerCase())) {
                        shulkerCount += item.getValue();
                        shulkerColor = shulker.color();
                    }
                }
            }

            if (directCount > 0) {
                fieldValue.append(directCount).append("x ").append(readableSearch.toLowerCase());
            }

            if (shulkerCount > 0) {
                if (fieldValue.length() > 0) fieldValue.append("\n");
                fieldValue.append(shulkerCount).append("x ").append(readableSearch.toLowerCase());
                if (shulkerColor != null && !shulkerColor.equals("unknown")) {
                    fieldValue.append(" (in ").append(shulkerColor).append(" Shulker)");
                } else {
                    fieldValue.append(" (in Shulker)");
                }
            }

            if (fieldValue.length() == 0) {
                fieldValue.append(entry.getItemCount(search)).append("x ").append(readableSearch.toLowerCase());
            }

            embed.addField(fieldName, fieldValue.toString(), true);
            fieldCount++;
        }

        if (results.size() > MAX_RESULTS) {
            embed.addField("...", (results.size() - MAX_RESULTS) + " more containers not shown", false);
        }

        embed.footer((useDb ? "Database" : "Index") + " contains " + results.size() + " matching containers | Last scan: "
            + index.timeSinceLastScan(), null);
    }
}
