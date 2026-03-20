package com.zenith.plugin.stashmanager;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import com.zenith.plugin.stashmanager.command.StashCommand;
import com.zenith.plugin.stashmanager.command.StashSearchCommand;
import com.zenith.plugin.stashmanager.command.StashSupplyCommand;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Container scanning, indexing, and Discord-queryable inventory management",
    authors = {"MOAR"},
    mcVersions = {BuildConstants.MC_VERSION}
)
public class StashManagerPlugin implements ZenithProxyPlugin {

    public static ComponentLogger LOG;
    private static ContainerIndex sharedIndex;
    private static StashManagerModule sharedModule;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        var config = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, StashManagerConfig.class);
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
