package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.index.ContainerEntry;

import java.util.Collection;

/** Early warning for an indexed hopper that would drain a kit output chest. */
public final class KitOutputChestPolicy {
    private KitOutputChestPolicy() { }

    public static boolean hasIndexedHopperBelow(ContainerEntry output,
                                                Collection<ContainerEntry> indexed) {
        if (output == null || indexed == null || !output.inventoryFootprintKnown()) return false;
        int x = output.inventoryX();
        int y = output.inventoryY() - 1;
        int z = output.inventoryZ();
        int partnerX = x + (output.isDouble() && "X".equals(output.doubleChestAxis()) ? 1 : 0);
        int partnerZ = z + (output.isDouble() && "Z".equals(output.doubleChestAxis()) ? 1 : 0);
        for (ContainerEntry entry : indexed) {
            if (entry == null || entry.y() != y
                    || !"minecraft:hopper".equals(entry.blockType())) continue;
            if (entry.x() == x && entry.z() == z
                    || output.isDouble() && entry.x() == partnerX && entry.z() == partnerZ)
                return true;
        }
        return false;
    }
}
