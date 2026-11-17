package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitTerminalReturnFingerprintTest {
    private static ItemStack pickaxe(int damage, boolean reverseOrder) {
        var components = new DataComponents(new LinkedHashMap<>());
        if (reverseOrder) {
            components.put(DataComponentTypes.REPAIR_COST, 5);
            components.put(DataComponentTypes.DAMAGE, damage);
        } else {
            components.put(DataComponentTypes.DAMAGE, damage);
            components.put(DataComponentTypes.REPAIR_COST, 5);
        }
        return new ItemStack(ItemRegistry.REGISTRY.get("diamond_pickaxe").id(),
                1, components);
    }

    @Test void acceptsComponentOrderOnlyAndRejectsActualDamageChange() {
        var saved = KitTerminalReturnRunner.fingerprint(pickaxe(309, false));
        var reordered = pickaxe(309, true);
        assertNotEquals(saved.componentHash(),
                KitTerminalReturnRunner.fingerprint(reordered).componentHash());
        assertTrue(KitTerminalReturnRunner.fingerprintMatches(saved, reordered));
        assertFalse(KitTerminalReturnRunner.fingerprintMatches(saved,
                pickaxe(310, true)));
    }
}
