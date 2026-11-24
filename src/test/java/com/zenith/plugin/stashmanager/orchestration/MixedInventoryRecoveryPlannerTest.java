package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MixedInventoryRecoveryPlannerTest {
    @Test
    void preservesOneTaskPerIdenticalPhysicalBox() {
        var box = cargo("pink_shulker_box", "same", Map.of(
                "diamond_sword", 1,
                "end_crystal", 64));

        assertEquals(2, MixedInventoryRecoveryPlanner.uncovered(
                List.of(box, box), List.of()).size());
    }

    @Test
    void subtractsAlreadyScheduledBoxesAsAMultiset() {
        var box = cargo("pink_shulker_box", "same", Map.of(
                "diamond_sword", 1,
                "end_crystal", 64));

        List<MixedInventoryRecoveryPlanner.Cargo> uncovered =
                MixedInventoryRecoveryPlanner.uncovered(
                        List.of(box, box, box), List.of(box, box));

        assertEquals(List.of(box), uncovered);
    }

    @Test
    void fingerprintsAndColorsRemainIndependent() {
        var pink = cargo("pink_shulker_box", "kit-a", Map.of("end_crystal", 64));
        var red = cargo("red_shulker_box", "kit-a", Map.of("end_crystal", 64));
        var otherKit = cargo("pink_shulker_box", "kit-b", Map.of("obsidian", 64));

        assertEquals(List.of(red, otherKit), MixedInventoryRecoveryPlanner.uncovered(
                List.of(pink, red, otherKit), List.of(pink)));
    }

    private static MixedInventoryRecoveryPlanner.Cargo cargo(
            String itemId, String fingerprint, Map<String, Integer> contents) {
        return new MixedInventoryRecoveryPlanner.Cargo(itemId, fingerprint, contents);
    }
}
