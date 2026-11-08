package com.zenith.plugin.stashmanager.organizer;

import com.zenith.cache.data.inventory.Container;
import com.zenith.mc.item.ItemRegistry;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitFulfillmentPackingCapacityTest {
    private static final int BOX_SLOTS = 27;

    @Test
    void choosesAnEmptySlotEvenWhenMoreThanOneExists() {
        assertEquals(0, KitPacker.firstEmptyBoxSlot(BOX_SLOTS, slot -> slot >= 2));
        assertEquals(8, KitPacker.firstEmptyBoxSlot(BOX_SLOTS, slot -> slot != 8));
        assertEquals(-1, KitPacker.firstEmptyBoxSlot(BOX_SLOTS, slot -> true));
    }

    @Test
    void compactsTwoCompatiblePartialStacksToFreeOneSlot() {
        Container box = fullBox();
        box.setItemStack(0, stack("obsidian", 32));
        box.setItemStack(1, stack("obsidian", 32));

        KitPacker.CompactionPlan plan = KitPacker.findCompactionPlan(box);
        assertNotNull(plan);
        assertEquals(0, plan.sourceSlot());
        assertEquals(1, plan.targetSlot());
        assertEquals(0, plan.sourceAfter());
        assertEquals(64, plan.targetAfter());

        box.setItemStack(0, Container.EMPTY_STACK);
        box.setItemStack(1, stack("obsidian", 64));
        assertTrue(plan.confirmed(box));
    }

    @Test
    void canRedistributeThreePartialStacksBeforeFreeingASlot() {
        Container box = fullBox();
        box.setItemStack(0, stack("obsidian", 40));
        box.setItemStack(1, stack("obsidian", 40));
        box.setItemStack(2, stack("obsidian", 48));

        KitPacker.CompactionPlan first = KitPacker.findCompactionPlan(box);
        assertNotNull(first);
        assertEquals(16, first.sourceAfter());
        assertEquals(64, first.targetAfter());
        box.setItemStack(first.sourceSlot(), stack("obsidian", first.sourceAfter()));
        box.setItemStack(first.targetSlot(), stack("obsidian", first.targetAfter()));
        assertTrue(first.confirmed(box));

        KitPacker.CompactionPlan second = KitPacker.findCompactionPlan(box);
        assertNotNull(second);
        assertEquals(0, second.sourceAfter());
        assertEquals(64, second.targetAfter());
    }

    @Test
    void refusesToMergeIncompatibleOrUnstackableItems() {
        Container box = fullBox();
        box.setItemStack(0, stack("obsidian", 40));
        box.setItemStack(1, stack("end_crystal", 40));
        assertNull(KitPacker.findCompactionPlan(box));
    }

    @Test
    void selectsOnlyTheUniqueCaseMismatchedAppleStackForExchange() {
        Container box = fullBox();
        box.setItemStack(6, namedApple(64, "Glory Apple"));
        box.setItemStack(7, namedApple(46, "Glory Apple"));
        box.setItemStack(8, namedApple(18, "glory Apple"));
        assertEquals(8, KitPacker.uniqueIncompatibleAppleSlot(
                box, namedApple(18, "Glory Apple"), 18));
        assertEquals(-1, KitPacker.uniqueIncompatibleAppleSlot(
                box, namedApple(18, "Glory Apple"), 17));
        box.setItemStack(9, namedApple(1, "Another Apple"));
        assertEquals(-1, KitPacker.uniqueIncompatibleAppleSlot(
                box, namedApple(18, "Glory Apple"), 18));
    }

    @Test
    void displacesTwoNamedStacksOneAtATimeForUnnamedFallback() {
        Container box = fullBox();
        box.setItemStack(6, namedApple(64, "Glory Apple"));
        box.setItemStack(7, namedApple(46, "Glory Apple"));
        box.setItemStack(8, namedApple(18, "glory Apple"));
        ItemStack unnamed = stack("enchanted_golden_apple", 64);

        assertEquals(6, KitPacker.firstIncompatibleAppleSlot(box, unnamed, 128));
        box.setItemStack(6, Container.EMPTY_STACK);
        assertEquals(7, KitPacker.firstIncompatibleAppleSlot(box, unnamed, 64));
        box.setItemStack(7, Container.EMPTY_STACK);
        assertEquals(8, KitPacker.firstIncompatibleAppleSlot(box, unnamed, 18));
        box.setItemStack(8, Container.EMPTY_STACK);
        assertEquals(-1, KitPacker.firstIncompatibleAppleSlot(box, unnamed, 1));
    }

    private static Container fullBox() {
        Container box = new Container(5, BOX_SLOTS + 36);
        for (int slot = 0; slot < BOX_SLOTS; slot++) {
            box.setItemStack(slot, stack("diamond_sword", 1));
        }
        return box;
    }

    private static ItemStack stack(String item, int amount) {
        return new ItemStack(ItemRegistry.REGISTRY.get(item).id(), amount);
    }

    private static ItemStack namedApple(int amount, String name) {
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, Component.text(name));
        return new ItemStack(ItemRegistry.REGISTRY.get("enchanted_golden_apple").id(),
                amount, components);
    }
}
