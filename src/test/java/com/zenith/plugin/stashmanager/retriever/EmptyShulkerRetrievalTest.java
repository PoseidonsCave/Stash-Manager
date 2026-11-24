package com.zenith.plugin.stashmanager.retriever;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmptyShulkerRetrievalTest {
    @Test
    void onlyPhysicalEmptyInstancesCountAsShellSupply() {
        ContainerEntry entry = new ContainerEntry(1, 64, 2, "chest", false,
                Map.of("red_shulker_box", 3), 3, List.of(
                new ContainerEntry.ShulkerDetail(0, "red", Map.of()),
                new ContainerEntry.ShulkerDetail(1, "red", Map.of("obsidian", 64)),
                new ContainerEntry.ShulkerDetail("red", Map.of())), 1L);
        assertEquals(1, StashRetriever.emptyPhysicalShulkers(entry));
    }
}
