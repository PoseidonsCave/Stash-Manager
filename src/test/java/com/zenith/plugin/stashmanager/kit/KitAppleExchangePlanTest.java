package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemRegistry;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KitAppleExchangePlanTest {
    @Test
    void exchangesMismatchedEighteenForExactNameCaseAndComponents() {
        ItemStack matching = apple(64, "Glory Apple");
        ItemStack mismatched = apple(18, "glory Apple");
        var plan = KitAppleExchangePlan.choose(
                List.of(matching, apple(46, "Glory Apple"), mismatched),
                List.of(apple(18, "Glory Apple")), 128, 2);

        assertEquals(KitAppleExchangePlan.Choice.EXISTING_NAMED, plan.choice());
        assertTrue(plan.complete());
        assertEquals(110, plan.retained());
        assertEquals(18, plan.toRetrieve());
        assertEquals(18, plan.toRemove());
        assertEquals(2, plan.slotsUsed());
        assertFalse(KitAppleExchangePlan.samePhysicalStack(matching, mismatched));
    }

    @Test
    void usesUnnamedSupplyWhenNamedSupplyCannotFillBothStacks() {
        var plan = KitAppleExchangePlan.choose(
                List.of(apple(64, "Glory Apple"), apple(46, "Glory Apple"),
                        apple(18, "glory Apple")),
                List.of(apple(64, null), apple(64, null)), 128, 2);

        assertEquals(KitAppleExchangePlan.Choice.UNNAMED, plan.choice());
        assertTrue(plan.complete());
        assertEquals(128, plan.toRemove());
        assertEquals(128, plan.toRetrieve());
    }

    @Test
    void reportsPartialInsteadOfPretendingMixedNamesStack() {
        var plan = KitAppleExchangePlan.choose(
                List.of(apple(64, "Glory Apple"), apple(46, "Glory Apple"),
                        apple(18, "glory Apple")),
                List.of(apple(12, null)), 128, 2);

        assertEquals(KitAppleExchangePlan.Choice.PARTIAL, plan.choice());
        assertEquals(110, plan.retained());
        assertEquals(18, plan.toRemove());
        assertEquals(0, plan.toRetrieve());
        assertEquals(18, plan.shortfall());
        assertFalse(plan.complete());
    }

    @Test
    void keepsIdenticalNamedStacksWithoutAnExchange() {
        var plan = KitAppleExchangePlan.choose(
                List.of(apple(64, "Glory Apple"), apple(64, "Glory Apple")),
                List.of(), 128, 2);
        assertTrue(plan.complete());
        assertFalse(plan.needsExchange());
        assertEquals(0, plan.toRetrieve());
    }

    @Test
    void partialTieKeepsTheExistingCaseRatherThanDisplacingIt() {
        var plan = KitAppleExchangePlan.choose(
                List.of(apple(64, "Glory Apple"), apple(46, "Glory Apple")),
                List.of(apple(64, null), apple(46, null)), 128, 2);
        assertEquals(KitAppleExchangePlan.Choice.PARTIAL, plan.choice());
        assertEquals(110, plan.retained());
        assertEquals(0, plan.toRemove());
        assertEquals(18, plan.shortfall());
    }

    @Test
    void auditedMixedApplesMustFitTwoPhysicalSlots() {
        assertTrue(KitAppleExchangePlan.fitsPhysicalSlots(List.of(
                apple(56, "UNIVAC"), apple(8, "UNIVAC"), apple(64, "L9")), 128, 2));
        assertFalse(KitAppleExchangePlan.fitsPhysicalSlots(List.of(
                apple(56, "UNIVAC"), apple(8, "other"), apple(64, "L9")), 128, 2));
        assertFalse(KitAppleExchangePlan.fitsPhysicalSlots(List.of(
                apple(56, "UNIVAC"), apple(8, "UNIVAC"), apple(63, "L9")), 128, 2));
    }

    private static ItemStack apple(int amount, String name) {
        int itemId = ItemRegistry.REGISTRY.get("enchanted_golden_apple").id();
        if (name == null) return new ItemStack(itemId, amount);
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, Component.text(name));
        return new ItemStack(itemId, amount, components);
    }
}
