package com.zenith.plugin.stashmanager.organizer;

import com.zenith.cache.data.inventory.Container;
import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverflowPartialDepositTest {
    @Test
    void splitsExactCargoAcrossMatchingHeadroomAndAnEmptySlot() {
        Container window = new Container(73, 90);
        ItemStack source = item("firework_rocket", 42);
        window.setItemStack(0, item("firework_rocket", 60));
        window.setItemStack(2, item("obsidian", 64));

        List<Integer> targets = StashOrganizer.partialDepositTargets(window, 54, source, 13);

        assertEquals(13, targets.size());
        assertEquals(List.of(0, 0, 0, 0), targets.subList(0, 4));
        assertEquals(List.of(1, 1, 1, 1, 1, 1, 1, 1, 1), targets.subList(4, 13));
    }

    @Test
    void rejectsAChestWithoutEnoughExactItemHeadroom() {
        Container window = new Container(73, 90);
        for (int slot = 0; slot < 54; slot++) {
            window.setItemStack(slot, item("obsidian", 64));
        }
        window.setItemStack(0, item("firework_rocket", 60));

        assertTrue(StashOrganizer.partialDepositTargets(
                window, 54, item("firework_rocket", 42), 13).isEmpty());
    }

    @Test
    void selectsSemanticClickPacketConstructorInsteadOfRawByteOverload() {
        var constructor = PlaceOneFromCursor.findClickPacketConstructor(
                ServerboundContainerClickPacket.class);

        assertEquals(ContainerActionType.class, constructor.getParameterTypes()[3]);
        assertTrue(constructor.getParameterTypes()[4].isAssignableFrom(ClickItemAction.class));
    }

    private static ItemStack item(String id, int amount) {
        return new ItemStack(ItemRegistry.REGISTRY.get(id).id(), amount);
    }
}
