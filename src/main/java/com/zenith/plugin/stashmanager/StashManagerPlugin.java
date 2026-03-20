package com.zenith.plugin.stashmanager;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import com.zenith.plugin.stashmanager.command.StashCommand;
import com.zenith.plugin.stashmanager.command.StashSearchCommand;
import com.zenith.plugin.stashmanager.command.StashSupplyCommand;
import com.zenith.plugin.stashmanager.index.ContainerIndex;

@Plugin(
    id = "stash-manager",
    version = "1.0.0",
    description = "Container scanning, indexing, and Discord-queryable inventory management",
    authors = {"MOAR"}
)
public class StashManagerPlugin implements ZenithProxyPlugin {

    private static ContainerIndex sharedIndex;
    private static StashManagerModule sharedModule;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        var config = pluginAPI.registerConfig("stash-manager", StashManagerConfig.class);
        sharedIndex = new ContainerIndex();
        sharedModule = new StashManagerModule(config, pluginAPI.getLogger(), sharedIndex);

        pluginAPI.registerModule(sharedModule);
        pluginAPI.registerCommand(new StashCommand(config, sharedModule, sharedIndex));
        pluginAPI.registerCommand(new StashSearchCommand(sharedIndex));
        pluginAPI.registerCommand(new StashSupplyCommand(config));
    }

    public static ContainerIndex getIndex() {
        return sharedIndex;
    }

    public static StashManagerModule getModule() {
        return sharedModule;
    }
}
