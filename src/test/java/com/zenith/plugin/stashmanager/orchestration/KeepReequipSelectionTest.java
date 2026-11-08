package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class KeepReequipSelectionTest {
    @Test
    void neverReplacesExistingEquipment() {
        assertNull(KeepReequipSelection.next(
                Map.of(5, "diamond_helmet", 9, "netherite_helmet"),
                Set.of("netherite_helmet")));
    }

    @Test
    void equipsOneUnambiguousKeptItem() {
        assertEquals(new KeepReequipSelection.Swap(12, 6, "netherite_chestplate"),
                KeepReequipSelection.next(Map.of(12, "netherite_chestplate"),
                        Set.of("netherite_chestplate")));
    }

    @Test
    void skipsAmbiguousChestSlotAndCanStillEquipOffhand() {
        assertEquals(new KeepReequipSelection.Swap(14, 45, "totem_of_undying"),
                KeepReequipSelection.next(Map.of(9, "elytra", 10, "diamond_chestplate",
                                14, "totem_of_undying"),
                        Set.of("elytra", "diamond_chestplate", "totem_of_undying")));
        assertEquals(java.util.List.of(6), KeepReequipSelection.ambiguousTargets(
                Map.of(9, "elytra", 10, "diamond_chestplate", 14, "totem_of_undying"),
                Set.of("elytra", "diamond_chestplate", "totem_of_undying")));
    }
}
