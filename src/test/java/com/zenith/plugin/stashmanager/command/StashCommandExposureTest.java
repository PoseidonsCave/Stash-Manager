package com.zenith.plugin.stashmanager.command;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.CommandDispatcher;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandSource;
import com.zenith.discord.Embed;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertFalse(allowed(root, other, "config"));
        assertFalse(allowed(root, other, "export"));
        assertFalse(allowed(root, other, "kit", "cargo"));
        assertTrue(allowed(root, owner, "kit", "cargo"));
        assertFalse(allowed(root, other, "kit", "return", "preview"));
        assertTrue(allowed(root, owner, "kit", "return", "preview"));
        assertFalse(allowed(root, other, "kit", "laneplan"));
        assertTrue(allowed(root, owner, "kit", "laneplan"));
        assertFalse(allowed(root, other, "kit", "working", "fill"));
        assertTrue(allowed(root, owner, "kit", "working", "fill"));
        assertFalse(allowed(root, other, "kit", "working", "status"));
        assertFalse(allowed(root, other, "kit", "working", "reserve"));
        assertTrue(allowed(root, owner, "kit", "working", "reserve"));
        assertFalse(allowed(root, other, "kit", "working", "release"));
        assertTrue(allowed(root, owner, "kit", "working", "release"));
        assertFalse(allowed(root, other, "kit", "working", "recover"));
        assertTrue(allowed(root, owner, "kit", "working", "recover"));
        assertFalse(allowed(root, other, "kit", "consolidate", "preview"));
        assertTrue(allowed(root, owner, "kit", "consolidate", "preview"));
        assertFalse(allowed(root, other, "kit", "rebasecheck"));
        assertTrue(allowed(root, owner, "kit", "rebasecheck"));
        assertFalse(allowed(root, other, "kit", "rebase"));
        assertTrue(allowed(root, owner, "kit", "rebase"));
        assertFalse(allowed(root, other, "kit", "auditcargo", "partial"));
        assertTrue(allowed(root, owner, "kit", "auditcargo", "partial"));

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

    @Test
    void brigadierParsingRejectsSensitiveCommandsAndAliasWithoutPermission() {
        var config = new StashManagerConfig();
        var command = new StashCommand(config, null, null, null, null, null);
        var dispatcher = new CommandDispatcher<CommandContext>();
        var root = dispatcher.register(command.register());
        dispatcher.register(command.redirect("sm", root));
        var other = new TestSource(false);
        var owner = new TestSource(true);

        for (String input : new String[]{"stash export", "sm export", "stash kit cargo",
                "stash kit laneplan rainbow_wool 10",
                "stash kit working fill rainbow_wool", "stash kit working status",
                "stash kit working repack rainbow_wool white_wool 64 1 64 2",
                "stash kit working reserve rainbow_wool 10 1 64 2",
                "stash kit working release 1 64 2",
                "stash kit working recover",
                "stash kit consolidate preview all 10",
                "stash kit return preview",
                "stash kit rebasecheck 33",
                "stash kit rebase 33",
                "stash kit auditcargo partial 35 33 13:31 14:64",
                "stash config", "sm config api key sentinel", "stash config api key sentinel",
                "stash config db password sentinel", "stash config diagnostics on",
                "stash kit diagnose"}) {
            assertTrue(dispatcher.parse(input, CommandContext.create(input, other))
                    .getReader().canRead(), input);
        }
        config.diagnosticCommandsEnabled = true;
        for (String input : new String[]{"stash debug recent", "sm kit diagnose",
                "stash kit inspectsource", "stash kit diagnosevariant 9"}) {
            assertTrue(dispatcher.parse(input, CommandContext.create(input, other))
                    .getReader().canRead(), input);
        }
        for (String input : new String[]{"stash export", "sm kit cargo", "stash debug recent",
                "stash kit laneplan rainbow_wool 10",
                "stash kit working fill rainbow_wool", "stash kit working status",
                "stash kit working repack rainbow_wool white_wool 64 1 64 2",
                "stash kit working reserve rainbow_wool 10 1 64 2",
                "stash kit working release 1 64 2",
                "stash kit working recover",
                "stash kit consolidate preview all 10",
                "stash kit return preview",
                "stash kit diagnose", "stash kit rebasecheck 33",
                "stash kit rebase 33",
                "stash kit auditcargo partial 35 33 13:31 14:64"}) {
            assertFalse(dispatcher.parse(input, CommandContext.create(input, owner))
                    .getReader().canRead(), input);
        }
    }

    @Test
    void secretBearingConfigurationCommandsMarkRawInputSensitive() throws Exception {
        var config = new StashManagerConfig();
        var dispatcher = new CommandDispatcher<CommandContext>();
        dispatcher.register(new StashCommand(config, null, null, null, null, null).register());
        var owner = new TestSource(true);

        for (String input : new String[]{
                "stash config db url jdbc:postgresql://localhost:5432/example?password=sentinel",
                "stash config db password sentinel",
                "stash config api key sentinel"}) {
            var context = CommandContext.create(input, owner);
            assertEquals(1, dispatcher.execute(input, context), input);
            assertTrue(context.isSensitiveInput(), input);
            assertFalse(context.getEmbed().description().contains("sentinel"), input);
        }
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
