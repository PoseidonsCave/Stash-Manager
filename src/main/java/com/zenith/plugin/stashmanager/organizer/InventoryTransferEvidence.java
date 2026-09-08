package com.zenith.plugin.stashmanager.organizer;

import com.zenith.cache.data.inventory.Container;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;

import java.util.Map;

/** Count exact-stack movement on the player side, away from hopper traffic. */
record InventoryTransferEvidence(int itemId, DataComponents components, int requestedAmount,
                                 int receiverStart, int receiverEnd, int receiverBefore,
                                 int playerBefore, boolean taking) {
    static InventoryTransferEvidence capture(Container window, int containerSlots, int sourceSlot) {
        ItemStack source = window.getItemStack(sourceSlot);
        boolean taking = sourceSlot < containerSlots;
        int start = taking ? containerSlots : 0;
        int end = taking ? containerSlots + 36 : containerSlots;
        var evidence = new InventoryTransferEvidence(source.getId(), components(source).clone(),
                source.getAmount(), start, end, 0, 0, taking);
        return new InventoryTransferEvidence(evidence.itemId, evidence.components,
                evidence.requestedAmount, start, end, evidence.receiverUnits(window),
                evidence.playerUnits(window), taking);
    }

    boolean matches(ItemStack stack) {
        return stack != null && stack.getAmount() > 0 && stack.getId() == itemId
                && components.equals(components(stack));
    }

    int receiverUnits(Container window) {
        return units(window, receiverStart, receiverEnd);
    }

    int playerUnits(Container window) {
        int start = taking ? receiverStart : receiverEnd;
        return units(window, start, start + 36);
    }

    private int units(Container window, int start, int end) {
        int units = 0;
        for (int slot = start; slot < Math.min(end, window.getSize()); slot++) {
            ItemStack stack = window.getItemStack(slot);
            if (matches(stack)) units += stack.getAmount();
        }
        return units;
    }

    int received(Container window) {
        return InventoryTransferPolicy.observedTaskCargoDelta(
                receiverBefore, receiverUnits(window), requestedAmount);
    }

    int moved(Container window) {
        if (taking) return received(window);
        // A hopper can drain a successful deposit before the chest snapshot arrives.
        // Count all player slots so rearranging a stack cannot masquerade as a deposit.
        return Math.min(requestedAmount, Math.max(0, playerBefore - playerUnits(window)));
    }

    private static DataComponents components(ItemStack stack) {
        return stack.getDataComponents() == null ? new DataComponents(Map.of()) : stack.getDataComponents();
    }
}
