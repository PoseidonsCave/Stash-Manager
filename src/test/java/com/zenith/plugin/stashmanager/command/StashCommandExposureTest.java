package com.zenith.plugin.stashmanager.command;

import com.mojang.brigadier.tree.CommandNode;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandSource;
import com.zenith.discord.Embed;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StashCommandExposureTest {
    @Test
    void sensitiveCommandsRequireExplicitDiagnosticsAndOwner() {
        var config = new StashManagerConfig();
        var root = new StashCommand(config, null, null, null, null, null).register().build();
        var owner = CommandContext.create("stash", new TestSource(true));
        var other = CommandContext.create("stash", new TestSource(false));

        assertFalse(allowed(root, owner, "debug"));
        assertFalse(allowed(root, owner, "kit", "diagnose"));
        assertFalse(allowed(root, owner, "kit", "inspectsource"));
        assertFalse(allowed(root, owner, "kit", "diagnosevariant"));
        assertFalse(allowed(root, other, "config", "diagnostics", "on"));
        assertFalse(allowed(root, other, "export"));
        assertFalse(allowed(root, other, "kit", "cargo"));
        assertTrue(allowed(root, owner, "kit", "cargo"));

        config.diagnosticCommandsEnabled = true;
        assertTrue(allowed(root, owner, "debug"));
        assertTrue(allowed(root, owner, "kit", "diagnose"));
        assertTrue(allowed(root, owner, "kit", "inspectsource"));
        assertTrue(allowed(root, owner, "kit", "diagnosevariant"));
        assertFalse(allowed(root, other, "debug"));
        assertFalse(allowed(root, other, "kit", "diagnose"));
        assertTrue(allowed(root, owner, "config", "diagnostics", "on"));
        assertTrue(allowed(root, owner, "export"));
    }

    private static boolean allowed(CommandNode<CommandContext> root, CommandContext context,
                                   String... path) {
        var node = root;
        for (String part : path) {
            node = node.getChild(part);
            if (node == null || !node.canUse(context)) return false;
        }
        return true;
    }

    private record TestSource(boolean owner) implements CommandSource {
        @Override public String name() { return "test"; }
        @Override public boolean validateAccountOwner(CommandContext context) { return owner; }
        @Override public void logEmbed(CommandContext context, Embed embed) { }
    }
}
