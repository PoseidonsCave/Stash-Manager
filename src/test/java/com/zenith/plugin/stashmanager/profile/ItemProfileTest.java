package com.zenith.plugin.stashmanager.profile;

import com.zenith.plugin.stashmanager.orchestration.ShulkerClassification;
import com.zenith.plugin.stashmanager.orchestration.StorageClassPolicy;
import com.zenith.plugin.stashmanager.orchestration.LaneStorageCapacity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ItemProfileTest {
    @AfterEach void reset() { ItemProfiles.install(List.of()); }

    @Test void containsRuleRoutesMultipleVariantsToOneLaneWithoutLosingIdentity() {
        var fortune = new ItemProfile("fortune_picks", "diamond_pickaxe",
                ItemProfile.Mode.CONTAINS, Map.of("fortune", 3), true);
        ItemProfiles.install(List.of(fortune));
        String first = "diamond_pickaxe[efficiency=5,fortune=3]";
        String second = "diamond_pickaxe[fortune=3,mending=1]";
        assertEquals("profile:fortune_picks", StorageClassPolicy.route(first));
        assertEquals("profile:fortune_picks", StorageClassPolicy.route(second));
        assertEquals(first, StorageClassPolicy.exact(first));
        assertEquals("diamond_pickaxe[silk_touch]",
                StorageClassPolicy.route("diamond_pickaxe[silk_touch=1]"));
        assertEquals("diamond_sword", StorageClassPolicy.route(
                "diamond_sword[sharpness=5,unbreaking=3]"));
        var classified = ShulkerClassification.classify(Map.of(first, 1, second, 1));
        assertEquals(ShulkerClassification.Kind.BULK, classified.kind());
        assertEquals("profile:fortune_picks", classified.storageKey());
        assertEquals(2, classified.contents().size());
    }

    @Test void rejectsPotentiallyOverlappingLaneProfiles() {
        var fortune = new ItemProfile("fortune", "diamond_pickaxe",
                ItemProfile.Mode.CONTAINS, Map.of("fortune", 3), true);
        var efficiency = new ItemProfile("efficient", "diamond_pickaxe",
                ItemProfile.Mode.CONTAINS, Map.of("efficiency", 5), true);
        assertThrows(IllegalArgumentException.class,
                () -> ItemProfiles.install(List.of(fortune, efficiency)));
        assertTrue(ItemProfiles.all().isEmpty());
    }

    @Test void fortuneAndSilkProfilesCanCoexist() {
        var fortune = new ItemProfile("fortune", "diamond_pickaxe",
                ItemProfile.Mode.CONTAINS, Map.of("fortune", 3), true);
        var silk = new ItemProfile("silk", "diamond_pickaxe",
                ItemProfile.Mode.CONTAINS, Map.of("silk_touch", 1), true);
        ItemProfiles.install(List.of(fortune, silk));
        assertEquals("profile:fortune", StorageClassPolicy.route(
                "diamond_pickaxe[efficiency=5,fortune=3]"));
        assertEquals("profile:silk", StorageClassPolicy.route(
                "diamond_pickaxe[efficiency=5,silk_touch=1]"));
        assertEquals("diamond_pickaxe[fortune=3,silk_touch=1]", StorageClassPolicy.route(
                "diamond_pickaxe[fortune=3,silk_touch=1]"));
    }

    @Test void exactKitProfileRequiresTheCompleteEnchantmentSet() {
        var sword = new ItemProfile("pvp_sword", "diamond_sword", ItemProfile.Mode.EXACT,
                Map.of("sharpness", 5, "unbreaking", 3, "fire_aspect", 2), false);
        ItemProfiles.install(List.of(sword));
        assertTrue(ItemProfiles.matchesRequest("profile:pvp_sword",
                "diamond_sword[fire_aspect=2,sharpness=5,unbreaking=3]"));
        assertFalse(ItemProfiles.matchesRequest("profile:pvp_sword",
                "diamond_sword[fire_aspect=2,mending=1,sharpness=5,unbreaking=3]"));
    }

    @Test void enabledProfileCanReserveAnEmptyLane() {
        var sword = new ItemProfile("pvp_sword", "diamond_sword", ItemProfile.Mode.EXACT,
                Map.of("sharpness", 5), true);
        ItemProfiles.install(List.of(sword));
        var demand = LaneStorageCapacity.Demand.reserveEmptyProfile(sword.storageKey());
        assertEquals(1, demand.requiredShulkerSlots());
        assertEquals(1, demand.compactedShulkerSlots());
        assertEquals(1, demand.maxStackSize());
    }
}
