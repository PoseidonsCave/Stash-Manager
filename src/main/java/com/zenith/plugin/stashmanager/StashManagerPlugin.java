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
import com.zenith.plugin.stashmanager.organizer.StashOrganizer;
import com.zenith.plugin.stashmanager.update.PluginUpdateService;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Container scanning, indexing, and multi-platform friendly inventory management",
    url = "https://github.com/PoseidonsCave/Stash-Manager",
    authors = {"Flagships"},
    mcVersions = {BuildConstants.MC_VERSION}
)
public class StashManagerPlugin implements ZenithProxyPlugin {

    private static ContainerIndex sharedIndex;
    private static StashManagerModule sharedModule;
    private static DatabaseManager sharedDatabase;
    private static ApiServer sharedApiServer;
    private static PluginUpdateService sharedUpdateService;
    private static ComponentLogger sharedLogger;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        sharedLogger = pluginAPI.getLogger();
        var config = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, StashManagerConfig.class);
        sharedUpdateService = new PluginUpdateService(config, sharedLogger);

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

        // Organizer
        if (config.organizerEnabled) {
            var organizer = new StashOrganizer(config, sharedIndex);
            organizer.setInfoCallback(msg -> System.out.println("[StashOrganizer] " + msg));
            sharedModule.setOrganizer(organizer);
        }

        // API Server (created before commands so it can be passed to StashCommand)
        sharedApiServer = new ApiServer(config, sharedModule, sharedIndex, sharedDatabase);
        if (config.apiEnabled) {
            try {
                sharedApiServer.start();
            } catch (Exception e) {
                System.err.println("[StashManager] API server failed to start: " + e.getMessage());
            }
        }

        pluginAPI.registerModule(sharedModule);
        pluginAPI.registerCommand(new StashCommand(config, sharedModule, sharedIndex, sharedDatabase, sharedApiServer, sharedUpdateService));
        pluginAPI.registerCommand(new StashSearchCommand(sharedIndex, sharedDatabase));
        pluginAPI.registerCommand(new StashSupplyCommand(config));
        sharedUpdateService.scheduleStartupCheck();
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

    public static PluginUpdateService getUpdateService() {
        return sharedUpdateService;
    }

    public static ComponentLogger getLogger() {
        return sharedLogger;
    }
}
