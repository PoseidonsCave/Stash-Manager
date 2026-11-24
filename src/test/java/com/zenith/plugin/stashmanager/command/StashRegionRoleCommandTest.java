package com.zenith.plugin.stashmanager.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.zenith.command.api.CommandContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StashRegionRoleCommandTest {

    @Test
    void roleUsesClientCompatibleArgumentsAndParsesAdvertisedSyntax() {
        var dispatcher = new CommandDispatcher<CommandContext>();
        dispatcher.register(new StashCommand(null, null, null, null, null, null).register());

        var roleNode = dispatcher.getRoot().getChild("stash").getChild("region")
            .getChild("role").getChild("name").getChild("role");
        assertInstanceOf(ArgumentCommandNode.class, roleNode);

        var parsed = dispatcher.parse("stash region role nessus-001 intake", (CommandContext) null);
        assertEquals(0, parsed.getReader().getRemainingLength());
        assertNotNull(parsed.getContext().getCommand());

        var defaultRole = dispatcher.parse("stash region role warehouse default", (CommandContext) null);
        assertEquals(0, defaultRole.getReader().getRemainingLength());
        assertNotNull(defaultRole.getContext().getCommand());
    }
}
