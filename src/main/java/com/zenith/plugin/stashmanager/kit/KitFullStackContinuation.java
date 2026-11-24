package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.List;
import java.util.function.BiPredicate;

/** Select an existing physical variant when the next batch needs fresh box slots. */
public final class KitFullStackContinuation {
    private static final int BOX_SLOTS = 27;

    private KitFullStackContinuation() { }

    public static ItemStack choose(List<ItemStack> shell, List<ItemStack> matching,
                                   int needed, int stackLimit,
                                   BiPredicate<ItemStack, ItemStack> sameStack) {
        if (shell == null || matching == null || matching.isEmpty() || sameStack == null
                || needed < 1 || stackLimit < 1 || shell.size() > BOX_SLOTS
                || (needed + (long) stackLimit - 1) / stackLimit > BOX_SLOTS - shell.size()) {
            return null;
        }
        ItemStack exemplar = matching.getFirst();
        if (exemplar == null || exemplar.getAmount() != stackLimit) return null;
        for (ItemStack stack : matching) {
            if (stack == null || stack.getAmount() != stackLimit
                    || !sameStack.test(stack, exemplar)) return null;
        }
        return exemplar;
    }

    /** Narrow compatibility entry point used by the deployed crystal source gate. */
    public static ItemStack continueCrystals(ItemStack partial, List<ItemStack> shell, int needed) {
        return partial != null ? partial : chooseCrystals(shell, needed);
    }

    /** Preserve the same crystal variant when all packed stacks are full. */
    public static ItemStack chooseCrystals(List<ItemStack> shell, int needed) {
        if (shell == null) return null;
        List<ItemStack> crystals = shell.stream()
                .filter(stack -> stack != null && stack.getAmount() > 0
                        && "end_crystal".equals(ItemIdentifier.getItemId(stack)))
                .toList();
        return choose(shell, crystals, needed, 64, KitVariantFingerprint::sameComponents);
    }
}
