package com.zenith.plugin.stashmanager.command;

import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StashCommandTreeTest {
    @Test
    void purgeIsRegisteredUnderImportAndNotKeep() {
        var command = new StashCommand(
                new StashManagerConfig(), null, new ContainerIndex(), null, null, null);
        var root = command.register().build();

        var importNode = root.getChild("import");
        assertNotNull(importNode);
        var purgeNode = importNode.getChild("purge");
        assertNotNull(purgeNode);
        assertNotNull(purgeNode.getChild("confirm"));

        var keepNode = root.getChild("keep");
        assertNotNull(keepNode);
        assertNull(keepNode.getChild("purge"));

        var blacklistNode = root.getChild("blacklist");
        assertNotNull(blacklistNode);
        assertNotNull(blacklistNode.getChild("remove"));
        assertNotNull(blacklistNode.getChild("purge").getChild("confirm"));

        var exportNode = root.getChild("exportchest");
        assertNotNull(exportNode);
        assertNotNull(exportNode.getChild("remove"));
        assertNotNull(exportNode.getChild("purge").getChild("confirm"));
    }

    @Test
    void discordKitWizardCommandsAreRegistered() {
        var command = new StashCommand(
                new StashManagerConfig(), null, new ContainerIndex(), null, null, null);
        var kit = command.register().build().getChild("kit");

        assertNotNull(kit);
        assertNotNull(kit.getChild("create"));
        assertNotNull(kit.getChild("catalog"));
        assertNotNull(kit.getChild("select"));
        assertNotNull(kit.getChild("output"));
        assertNotNull(kit.getChild("finish"));
    }

    @Test
    void laneSpreadsheetExportIsRegisteredUnderLanes() {
        var command = new StashCommand(
                new StashManagerConfig(), null, new ContainerIndex(), null, null, null);
        var root = command.register().build();

        var lanesNode = root.getChild("lanes");
        assertNotNull(lanesNode);
        assertNotNull(lanesNode.getChild("export"));
    }

    @Test
    void proxyControlGraceIsConfigurable() {
        var command = new StashCommand(
                new StashManagerConfig(), null, new ContainerIndex(), null, null, null);
        var root = command.register().build();

        var configNode = root.getChild("config");
        assertNotNull(configNode);
        assertNotNull(configNode.getChild("controlgrace"));
    }

    @Test
    void organizerRestartRecoveryCommandsAreRegistered() {
        var command = new StashCommand(
                new StashManagerConfig(), null, new ContainerIndex(), null, null, null);
        var root = command.register().build();

        var organizeNode = root.getChild("organize");
        assertNotNull(organizeNode);
        assertNotNull(organizeNode.getChild("resume"));
        assertNotNull(organizeNode.getChild("discard"));
        assertNotNull(organizeNode.getChild("discard").getChild("confirm"));
    }
}
