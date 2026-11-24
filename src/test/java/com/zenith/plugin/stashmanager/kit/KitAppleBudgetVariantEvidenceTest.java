package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemRegistry;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class KitAppleBudgetVariantEvidenceTest {
    @Test void exactPostPackingShellReconstructsTheRecordedPreExchangeHash() {
        Map<Integer, ItemStack> before = new HashMap<>();
        for (int slot = 0; slot < 26; slot++) before.put(slot, item("stone", 1));
        before.put(10, crystal(64, "incoming"));
        before.put(11, crystal(64, "incoming"));
        before.put(12, crystal(64, "incoming"));
        before.put(13, crystal(54, "incoming"));
        before.put(16, crystal(10, "outgoing"));
        Map<Integer, ItemStack> after = new HashMap<>(before);
        after.remove(16);
        after.put(13, crystal(64, "incoming"));
        after.put(16, item("enchanted_golden_apple", 64));
        after.put(26, item("enchanted_golden_apple", 64));
        var repair = new KitFulfillmentJournalStore.VariantRepair(
                new int[]{3, 64, 4}, 54, -1,
                KitVariantFingerprint.canonicalVariant(item("enchanted_golden_apple", 64)),
                "end_crystal", 10, 16, 13,
                KitVariantFingerprint.canonicalVariant(before.get(13)),
                KitVariantFingerprint.canonicalVariant(before.get(16)),
                KitVariantFingerprint.canonicalShell(before), -1, null, null,
                16, 2, false);
        var checkpoint = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "VARIANT_PACKING",
                Map.of("stone", 21, "end_crystal", 256, "enchanted_golden_apple", 128),
                Map.of("stone", 21, "end_crystal", 256), 54, 18,
                "enchanted_golden_apple", 128, 40, new int[]{1, 64, 2},
                new int[]{2, 64, 3}, null, null, Map.of(), null, 1L,
                null, 0L, List.of(
                    new KitFulfillmentJournalStore.BorrowedBox(new int[]{4, 64, 5},
                            "shulker_box", Map.of("enchanted_golden_apple", 960)),
                    new KitFulfillmentJournalStore.BorrowedBox(new int[]{6, 64, 7},
                            "pink_shulker_box", Map.of("end_crystal", 1656))),
                -1, List.of(), Map.of(9, 64, 11, 64), 0, Map.of(), Map.of(),
                Map.of(), null, repair);
        Map<Integer, ItemStack> player = new HashMap<>();
        player.put(40, box(after));
        player.put(26, before.get(16));
        assertEquals(Map.of("stone", 21, "end_crystal", 256,
                "enchanted_golden_apple", 128),
                ItemIdentifier.readShulkerContents(player.get(40)));
        assertEquals(27, ItemIdentifier.readShulkerPhysicalSlotStacks(player.get(40)).size());
        assertEquals("end_crystal", ItemIdentifier.getItemId(player.get(26)));
        assertEquals(repair.outgoingHash(),
                KitVariantFingerprint.canonicalVariant(player.get(26)));
        var reconstructed = new HashMap<>(
                ItemIdentifier.readShulkerPhysicalSlotStacks(player.get(40)));
        reconstructed.remove(16);
        reconstructed.remove(26);
        reconstructed.put(13, crystal(54, "incoming"));
        reconstructed.put(16, player.get(26));
        assertEquals(repair.shellHash(), KitVariantFingerprint.canonicalShell(reconstructed));
        var proof = KitAppleBudgetVariantEvidence.prove(checkpoint, player);
        assertNotNull(proof);
        assertEquals(40, proof.shellSlot());
        assertEquals(26, proof.displacedSlot());
        assertEquals(KitVariantFingerprint.canonicalShell(after), proof.resultShellHash());
        var staged = new KitFulfillmentJournalStore.Snapshot(
                checkpoint.schemaVersion(), checkpoint.kitName(), "BLOCKED",
                checkpoint.recipe(), Map.of("stone", 21, "end_crystal", 256,
                        "enchanted_golden_apple", 128), checkpoint.requestedBoxes(),
                checkpoint.completedBoxes(), checkpoint.currentItem(),
                checkpoint.currentQuantity(), checkpoint.ownedShellSlot(),
                checkpoint.exportPosition(), null, null, null, Map.of(),
                "resume_variant_staging", 2L, "VARIANT_STAGING", 300_000L,
                checkpoint.sourceBoxesToReturn(), -1, List.of(), Map.of(), 0,
                Map.of(), Map.of(), Map.of(), null,
                repair.withResult(26, repair.outgoingHash(), proof.resultShellHash()));
        assertNotNull(KitAppleBudgetVariantEvidence.prove(staged, player));
        assertNull(KitAppleBudgetVariantEvidence.prove(staged.withVariantRepair(
                repair.withResult(26, repair.outgoingHash(), "f".repeat(64))), player));

        Map<Integer, ItemStack> duplicate = new HashMap<>(player);
        duplicate.put(27, before.get(16));
        assertNull(KitAppleBudgetVariantEvidence.prove(checkpoint, duplicate));
        Map<Integer, ItemStack> changed = new HashMap<>(after);
        changed.put(26, item("enchanted_golden_apple", 63));
        assertNull(KitAppleBudgetVariantEvidence.prove(checkpoint,
                Map.of(40, box(changed), 26, before.get(16))));
        Map<Integer, ItemStack> oldLooseApple = new HashMap<>(player);
        oldLooseApple.put(9, item("enchanted_golden_apple", 64));
        assertNull(KitAppleBudgetVariantEvidence.prove(checkpoint, oldLooseApple));
    }

    private static ItemStack item(String id, int amount) {
        return new ItemStack(ItemRegistry.REGISTRY.get(id).id(), amount);
    }

    private static ItemStack crystal(int amount, String name) {
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, Component.text(name));
        return new ItemStack(ItemRegistry.REGISTRY.get("end_crystal").id(),
                amount, components);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ItemStack box(Map<Integer, ItemStack> contents) {
        var slots = new ArrayList<ItemStack>();
        for (int i = 0; i < 27; i++) slots.add(contents.get(i));
        DataComponents components = new DataComponents(new HashMap<>());
        components.put((DataComponentType) DataComponentTypes.CONTAINER, slots);
        return new ItemStack(ItemRegistry.REGISTRY.get("cyan_shulker_box").id(),
                1, components);
    }
}
