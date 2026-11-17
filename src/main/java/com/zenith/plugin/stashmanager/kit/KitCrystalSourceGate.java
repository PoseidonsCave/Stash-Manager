package com.zenith.plugin.stashmanager.kit;

import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.List;

/** Compatibility facade for forks using the older crystal source API. */
@Deprecated
public final class KitCrystalSourceGate {
    private KitCrystalSourceGate() { }

    public static boolean accepts(ItemStack stack, ItemStack exemplar) {
        return KitItemSourcePolicy.CRYSTALS.accepts(stack, exemplar);
    }

    public static ItemStack partialShellVariant(List<ItemStack> shell, int needed) {
        return KitItemSourcePolicy.CRYSTALS.partialShellVariant(shell, needed);
    }

    public static ItemStack choose(List<ItemStack> observed, int needed) {
        return KitItemSourcePolicy.CRYSTALS.choose(observed, needed);
    }
}
