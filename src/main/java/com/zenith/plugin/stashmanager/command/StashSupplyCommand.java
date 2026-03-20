package com.zenith.plugin.stashmanager.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.Command;
import com.zenith.command.CommandContext;
import com.zenith.command.CommandUsage;
import com.zenith.command.brigadier.CommandCategory;
import com.zenith.plugin.stashmanager.StashManagerConfig;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.zenith.Globals.CACHE;

// Supply chest management: add, remove, list.
public class StashSupplyCommand extends Command {

    private final StashManagerConfig config;

    public StashSupplyCommand(StashManagerConfig config) {
        this.config = config;
    }

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("stashsupply")
            .category(CommandCategory.MODULE)
            .description("Manage supply chests")
            .usageLines(
                "add",
                "remove <id>",
                "list"
            )
            .aliases("supply")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("stashsupply")
            .then(literal("add")
                .executes(c -> {
                    var pc = CACHE.getPlayerCache();
                    int x = (int) pc.getX();
                    int y = (int) pc.getY();
                    int z = (int) pc.getZ();

                    config.supplyChests.add(new int[]{x, y, z});
                    int id = config.supplyChests.size();

                    c.getSource().getEmbed()
                        .title("Supply Chest Added")
                        .description("Supply chest #" + id + " at " + x + ", " + y + ", " + z)
                        .successColor();
                    return OK;
                })
            )
            .then(literal("remove")
                .then(argument("id", integer(1))
                    .executes(c -> {
                        int id = IntegerArgumentType.getInteger(c, "id");
                        var embed = c.getSource().getEmbed();

                        if (id < 1 || id > config.supplyChests.size()) {
                            embed.title("Remove Failed")
                                .description("Invalid supply chest ID: " + id
                                    + ". Valid range: 1-" + config.supplyChests.size())
                                .errorColor();
                            return OK;
                        }

                        int[] removed = config.supplyChests.remove(id - 1);
                        embed.title("Supply Chest Removed")
                            .description("Removed supply chest #" + id + " at "
                                + removed[0] + ", " + removed[1] + ", " + removed[2])
                            .successColor();
                        return OK;
                    })
                )
            )
            .then(literal("list")
                .executes(c -> {
                    var embed = c.getSource().getEmbed()
                        .title("Supply Chests")
                        .primaryColor();

                    if (config.supplyChests.isEmpty()) {
                        embed.description("No supply chests registered.");
                        return OK;
                    }

                    embed.description(config.supplyChests.size() + " supply chests registered");

                    for (int i = 0; i < config.supplyChests.size(); i++) {
                        int[] pos = config.supplyChests.get(i);
                        embed.addField("#" + (i + 1),
                            pos[0] + ", " + pos[1] + ", " + pos[2], true);
                    }

                    return OK;
                })
            );
    }
}
