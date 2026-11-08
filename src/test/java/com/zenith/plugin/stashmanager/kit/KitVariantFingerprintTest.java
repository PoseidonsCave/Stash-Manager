package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemRegistry;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitVariantFingerprintTest {
    @Test
    void canonicalFingerprintIgnoresComponentMapIterationOrder() {
        DataComponents first = new DataComponents(new LinkedHashMap<>());
        first.put(DataComponentTypes.CUSTOM_NAME, Component.text("same"));
        first.put(DataComponentTypes.ITEM_NAME, Component.text("same item"));
        DataComponents reordered = new DataComponents(new LinkedHashMap<>());
        reordered.put(DataComponentTypes.ITEM_NAME, Component.text("same item"));
        reordered.put(DataComponentTypes.CUSTOM_NAME, Component.text("same"));
        int itemId = ItemRegistry.REGISTRY.get("end_crystal").id();
        ItemStack a = new ItemStack(itemId, 15, first);
        ItemStack b = new ItemStack(itemId, 15, reordered);

        assertEquals(first.getDataComponents(), reordered.getDataComponents());
        assertNotEquals(KitVariantFingerprint.variant(a), KitVariantFingerprint.variant(b));
        assertEquals(KitVariantFingerprint.canonicalVariant(a),
                KitVariantFingerprint.canonicalVariant(b));
        assertEquals(KitVariantFingerprint.canonicalShell(Map.of(21, a)),
                KitVariantFingerprint.canonicalShell(Map.of(21, b)));
    }

    @Test
    void canonicalFingerprintStillSeparatesDifferentComponentsAndAmounts() {
        int itemId = ItemRegistry.REGISTRY.get("end_crystal").id();
        DataComponents aData = new DataComponents(new LinkedHashMap<>());
        aData.put(DataComponentTypes.CUSTOM_NAME, Component.text("one"));
        DataComponents bData = new DataComponents(new LinkedHashMap<>());
        bData.put(DataComponentTypes.CUSTOM_NAME, Component.text("two"));
        ItemStack a = new ItemStack(itemId, 15, aData);
        ItemStack b = new ItemStack(itemId, 15, bData);

        assertNotEquals(KitVariantFingerprint.canonicalVariant(a),
                KitVariantFingerprint.canonicalVariant(b));
        assertNotEquals(KitVariantFingerprint.canonicalShell(Map.of(21, a)),
                KitVariantFingerprint.canonicalShell(Map.of(21,
                        new ItemStack(itemId, 16, aData))));
    }

    @Test
    void legacyCargoMigrationRequiresMatchingPhysicalSourceWithoutOldHashCompetitor() {
        int itemId = ItemRegistry.REGISTRY.get("diamond_helmet").id();
        DataComponents components = new DataComponents(new LinkedHashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, Component.text("matching"));
        ItemStack cargo = new ItemStack(itemId, 1, components);
        DataComponents independentMatchingComponents = new DataComponents(new LinkedHashMap<>());
        independentMatchingComponents.put(DataComponentTypes.CUSTOM_NAME,
                Component.text("matching"));
        ItemStack matching = new ItemStack(itemId, 1, independentMatchingComponents);
        DataComponents differentComponents = new DataComponents(new LinkedHashMap<>());
        differentComponents.put(DataComponentTypes.CUSTOM_NAME, Component.text("other"));
        ItemStack different = new ItemStack(itemId, 1, differentComponents);
        String oldHash = KitVariantFingerprint.variant(different);

        assertTrue(KitVariantFingerprint.witnessedLegacyCargo(
                cargo, oldHash, List.of(matching)));
        assertFalse(KitVariantFingerprint.witnessedLegacyCargo(
                cargo, oldHash, List.of(different, matching)));
        assertFalse(KitVariantFingerprint.witnessedLegacyCargo(
                cargo, oldHash, List.of(different)));
        assertFalse(KitVariantFingerprint.witnessedLegacyCargo(
                cargo, KitVariantFingerprint.variant(cargo), List.of(matching)));
    }
}
