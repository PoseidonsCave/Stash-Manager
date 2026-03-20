package com.zenith.plugin.stashmanager;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import com.zenith.plugin.stashmanager.api.ApiServer;
import com.zenith.plugin.stashmanager.command.StashCommand;
import com.zenith.plugin.stashmanager.command.StashSearchCommand;
import com.zenith.plugin.stashmanager.command.StashSupplyCommand;
import com.zenith.plugin.stashmanager.database.DatabaseManager;
import com.zenith.plugin.stashmanager.index.ContainerIndex;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Container scanning, indexing, and multi-platform friendly inventory management",
    url = "https://github.com/PoseidonsCave/Stash-Manager",
    authors = {"MOAR"},
    mcVersions = {BuildConstants.MC_VERSION}
)
public class StashManagerPlugin implements ZenithProxyPlugin {

    private static ContainerIndex sharedIndex;
    private static StashManagerModule sharedModule;
    private static DatabaseManager sharedDatabase;
    private static ApiServer sharedApiServer;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        var config = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, StashManagerConfig.class);

        // Database
        sharedDatabase = new DatabaseManager();
        if (config.databaseEnabled) {
            try {
                sharedDatabase.initialize(config);
            } catch (Exception e) {
                System.err.println("[StashManager] Database initialization failed: " + e.getMessage());
            }
        }

        // Index & Module
        sharedIndex = new ContainerIndex();
        sharedIndex.setDatabase(sharedDatabase);
        sharedModule = new StashManagerModule(config, sharedIndex);
        sharedModule.setDatabase(sharedDatabase);

        pluginAPI.registerModule(sharedModule);
        pluginAPI.registerCommand(new StashCommand(config, sharedModule, sharedIndex, sharedDatabase));
        pluginAPI.registerCommand(new StashSearchCommand(sharedIndex, sharedDatabase));
        pluginAPI.registerCommand(new StashSupplyCommand(config));

        // API Server
        sharedApiServer = new ApiServer(config, sharedModule, sharedIndex, sharedDatabase);
        if (config.apiEnabled) {
            try {
                sharedApiServer.start();
            } catch (Exception e) {
                System.err.println("[StashManager] API server failed to start: " + e.getMessage());
            }
        }
    }

    public static ContainerIndex getIndex() {
        return sharedIndex;
    }

    public static StashManagerModule getModule() {
        return sharedModule;
    }

    public static DatabaseManager getDatabase() {
        return sharedDatabase;
    }

    public static ApiServer getApiServer() {
        return sharedApiServer;
    }
}
