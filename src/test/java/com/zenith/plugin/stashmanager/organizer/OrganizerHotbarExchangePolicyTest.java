package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrganizerHotbarExchangePolicyTest {
    @Test void selectsProtectedPassiveSupplyBeforeHeldItemOrTool() {
        assertEquals(38, OrganizerHotbarExchangePolicy.protectedSupplySlot(
                Map.of(36, "diamond_pickaxe", 37, "enchanted_golden_apple",
                        38, "firework_rocket"), Set.of(36, 37, 38), 37));
    }

    @Test void mayUseHeldPassiveSupplyButNeverEquipment() {
        assertEquals(37, OrganizerHotbarExchangePolicy.protectedSupplySlot(
                Map.of(36, "diamond_pickaxe", 37, "enchanted_golden_apple"),
                Set.of(36, 37), 37));
        assertEquals(-1, OrganizerHotbarExchangePolicy.protectedSupplySlot(
                Map.of(36, "diamond_pickaxe", 37, "diamond_sword"),
                Set.of(36, 37), 36));
    }
}
