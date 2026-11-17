package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class KitConsolidationPlanTest {
    @Test
    void sharedIngredientDemandIsSummedBeforeAnyLaneIsProjectedEmpty() {
        var plan = KitConsolidationPlan.assess(
                Map.of("replenishment", Map.of("respawn_anchor", 24),
                       "griefing", Map.of("respawn_anchor", 16)),
                Map.of("replenishment", 10, "griefing", 10),
                Map.of("respawn_anchor", 420L), Set.of("respawn_anchor"), Set.of());

        assertEquals(20, plan.outputBoxes());
        assertEquals(400, plan.classes().get("respawn_anchor").plannedUnits());
        assertEquals(20, plan.classes().get("respawn_anchor").residualUnits());
        assertEquals(0, plan.projectedEmptyLaneCount());
        assertEquals(1, plan.residualHandoffCandidateCount());
        assertEquals(1, plan.residualHandoffShulkerSlots());
    }

    @Test
    void exactItemShortageCannotBeHiddenByOtherVariantsInSameClass() {
        var plan = KitConsolidationPlan.assess(
                Map.of("silk", Map.of("diamond_pickaxe[silk_touch=1]", 2)),
                Map.of("silk", 1),
                Map.of("diamond_pickaxe[silk_touch=1]", 1L,
                       "diamond_pickaxe[fortune=3]", 100L),
                Set.of("diamond_pickaxe[silk_touch]"), Set.of());

        assertEquals(1, plan.exactShortages().get("diamond_pickaxe[silk_touch=1]"));
        assertFalse(plan.indexedMaterialsSuffice());
        assertEquals(0, plan.projectedEmptyLaneCount());
        assertEquals(0, plan.residualHandoffCandidateCount());
    }

    @Test
    void surplusOtherSwordVariantCannotFakeADepletedLane() {
        var plan = KitConsolidationPlan.assess(
                Map.of("swords", Map.of("diamond_sword[sharpness=5]", 2)),
                Map.of("swords", 1),
                Map.of("diamond_sword[sharpness=5]", 1L,
                       "diamond_sword[smite=5]", 1L),
                Set.of("diamond_sword"), Set.of());

        assertEquals(1, plan.classes().get("diamond_sword").exactShortageUnits());
        assertEquals(0, plan.projectedEmptyLaneCount());
    }

    @Test
    void pinSensitiveClassesEvenWhenProjectedUseEqualsIndexedStock() {
        var plan = KitConsolidationPlan.assess(
                Map.of("special", Map.of("petrified_oak_slab", 64,
                        "filled_map", 1, "written_book[book=abc]", 1,
                        "oak_log", 64, "beacon", 1)),
                Map.of("special", 1),
                Map.of("petrified_oak_slab", 64L, "filled_map", 1L,
                        "written_book[book=abc]", 1L, "oak_log", 64L, "beacon", 1L),
                Set.of("petrified_oak_slab", "filled_map", "written_book[book=abc]",
                        "oak_log", "beacon"), Set.of());

        assertEquals(1, plan.projectedEmptyLaneCount());
        assertTrue(plan.classes().get("beacon").projectedEmpty());
        assertTrue(plan.classes().get("oak_log").protectedClass());
        assertTrue(plan.classes().get("written_book[book=abc]").protectedClass());
        assertTrue(KitConsolidationPlan.protectedByDefault("oak_door"));
        assertTrue(KitConsolidationPlan.protectedByDefault("bamboo"));
        assertTrue(KitConsolidationPlan.protectedByDefault("purple_shulker_box"));
    }

    @Test
    void projectedZeroRequiresAnAssignedLane() {
        var plan = KitConsolidationPlan.assess(Map.of("test", Map.of("stone", 64)),
                Map.of("test", 1), Map.of("stone", 64L), Set.of(), Set.of());
        assertEquals(0, plan.projectedEmptyLaneCount());
        assertEquals(0, plan.residualHandoffCandidateCount());
    }

    @Test
    void mixedExactVariantsNeedSeparateResidualBoxSlots() {
        var plan = KitConsolidationPlan.assess(
                Map.of("swords", Map.of("diamond_sword[sharpness=5]", 1)),
                Map.of("swords", 1),
                Map.of("diamond_sword[sharpness=5]", 2L,
                        "diamond_sword[smite=5]", 1L),
                Set.of("diamond_sword"), Set.of());

        assertEquals(2, plan.classes().get("diamond_sword").residualShulkerSlots());
        assertEquals(1, plan.residualHandoffCandidateCount());
    }
}
