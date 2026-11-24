package com.zenith.plugin.stashmanager.database;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContainerDetailsTest {
    @Test
    void combinesPhysicalLegacyAndDirectRowsWithoutLosingSlotEvidence() {
        var details = new DatabaseManager.ContainerDetails();
        details.addShulker(4, "blue");
        details.addShulker(7, "red");
        details.addItem("obsidian", 64, true, "blue", 4, true, 1, true);
        details.addItem("obsidian", 32, true, "blue", 4, true, 1, true);
        details.addItem("ender_pearl", 16, true, "green", 0, false, 1, true);
        details.addItem("obsidian", 4, false, null, 0, false, 1, true);

        var base = new ContainerEntry(1, 2, 3, "minecraft:chest", true,
                Map.of(), 2, null, 123L, "label", null,
                1, 2, 3, true, "X");
        var entry = details.finish(base);

        assertEquals(Map.of("obsidian", 100, "ender_pearl", 16), entry.items());
        assertEquals(Map.of("obsidian", 1), entry.directStackSlots());
        assertEquals(3, entry.shulkerDetails().size());
        assertEquals(Map.of("obsidian", 96), entry.shulkerDetails().get(0).items());
        assertEquals(Map.of("obsidian", 2), entry.shulkerDetails().get(0).stackSlots());
        assertEquals(Map.of(), entry.shulkerDetails().get(1).items());
        assertEquals(-1, entry.shulkerDetails().get(2).slot());
        assertEquals(Map.of("ender_pearl", 16), entry.shulkerDetails().get(2).items());
        assertEquals("X", entry.doubleChestAxis());
    }
}
