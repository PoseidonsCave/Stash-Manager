package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemRegistry;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class KitVariantExchangePlanTest {
    @Test
    void exactFifteenCrystalSwapFreesOneSlotInFullMixedKit() {
        var shell = fullShell();
        var source = List.of(new KitVariantExchangePlan.SlotStack(12,
                crystal(64, "matching")));

        var plan = KitVariantExchangePlan.oneStack(shell, source,
                "end_crystal", 256, 26);

        assertNotNull(plan);
        assertEquals(4, plan.outgoingSlot());
        assertEquals(1, plan.mergeTargetSlot());
        assertEquals(15, plan.amount());
        assertEquals(26, plan.slotsAfter());
    }

    @Test
    void shellOpportunityIsNotSourceProofButSelectsMinimalUniqueMove() {
        var opportunity = KitVariantExchangePlan.shellOpportunity(fullShell(),
                "end_crystal", 256, 26);
        assertNotNull(opportunity);
        assertEquals(15, opportunity.amount());
        assertEquals(4, opportunity.outgoingSlot());
        assertEquals(1, opportunity.mergeTargetSlot());
        assertNull(opportunity.withLiveSource(3, crystal(15, "other")));
        assertNotNull(opportunity.withLiveSource(3, crystal(15, "matching")));
    }

    @Test
    void rejectsSourceWithoutTheMergeableVariant() {
        var source = List.of(new KitVariantExchangePlan.SlotStack(12,
                crystal(64, "unrepresented")));
        assertNull(KitVariantExchangePlan.oneStack(fullShell(), source,
                "end_crystal", 256, 26));
    }

    @Test
    void rejectsExchangeWithoutExactMergeHeadroom() {
        var shell = fullShell();
        shell.set(1, new KitVariantExchangePlan.SlotStack(1,
                crystal(64, "matching")));
        assertNull(KitVariantExchangePlan.oneStack(shell,
                List.of(new KitVariantExchangePlan.SlotStack(12,
                        crystal(64, "matching"))), "end_crystal", 271, 26));
    }

    @Test
    void tenCrystalExchangeMakesRoomForTwoAppleStacks() {
        var shell = new ArrayList<KitVariantExchangePlan.SlotStack>();
        shell.add(new KitVariantExchangePlan.SlotStack(12, crystal(64, "matching")));
        shell.add(new KitVariantExchangePlan.SlotStack(13, crystal(54, "matching")));
        shell.add(new KitVariantExchangePlan.SlotStack(14, crystal(64, "other")));
        shell.add(new KitVariantExchangePlan.SlotStack(15, crystal(64, "other")));
        shell.add(new KitVariantExchangePlan.SlotStack(16, crystal(10, "other")));
        for (int slot = 0; shell.size() < 26; slot++) {
            if (slot >= 12 && slot <= 16) continue;
            shell.add(new KitVariantExchangePlan.SlotStack(slot,
                    new ItemStack(ItemRegistry.REGISTRY.get("diamond_sword").id(), 1)));
        }
        var opportunity = KitVariantExchangePlan.shellOpportunity(
                shell, "end_crystal", 256, 25);
        assertNotNull(opportunity);
        assertEquals(10, opportunity.amount());
        assertEquals(16, opportunity.outgoingSlot());
        assertEquals(13, opportunity.mergeTargetSlot());
        assertEquals(25, opportunity.slotsAfter());
    }

    private static List<KitVariantExchangePlan.SlotStack> fullShell() {
        var shell = new ArrayList<KitVariantExchangePlan.SlotStack>();
        shell.add(new KitVariantExchangePlan.SlotStack(0, crystal(64, "matching")));
        shell.add(new KitVariantExchangePlan.SlotStack(1, crystal(49, "matching")));
        shell.add(new KitVariantExchangePlan.SlotStack(2, crystal(64, "other")));
        shell.add(new KitVariantExchangePlan.SlotStack(3, crystal(64, "other")));
        shell.add(new KitVariantExchangePlan.SlotStack(4, crystal(15, "other")));
        for (int slot = 5; slot < 27; slot++) {
            shell.add(new KitVariantExchangePlan.SlotStack(slot,
                    new ItemStack(ItemRegistry.REGISTRY.get("diamond_sword").id(), 1)));
        }
        return shell;
    }

    private static ItemStack crystal(int amount, String name) {
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, Component.text(name));
        return new ItemStack(ItemRegistry.REGISTRY.get("end_crystal").id(),
                amount, components);
    }
}
