package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemRegistry;
import com.zenith.plugin.stashmanager.organizer.KitPacker;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KitPackedStackCapacityRepairTest {
    @TempDir Path temporary;

    @Test void selectsAndJournalsOneExactReplacementWithoutRepackingTheBatch()
            throws Exception {
        Map<Integer, ItemStack> shell = new HashMap<>();
        for (int slot = 0; slot < 21; slot++) shell.put(slot, stack("diamond_sword", 1));
        shell.put(21, obsidian(64, "A"));
        shell.put(22, obsidian(31, "A"));
        shell.put(23, obsidian(33, "B"));
        Map<String, Integer> recipe = Map.of("diamond_sword", 21,
                "obsidian", 128, "end_crystal", 256);
        Map<String, Integer> packed = Map.of("diamond_sword", 21, "obsidian", 128);
        var blocked = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                recipe, packed, 54, 22, "obsidian", 128, 40,
                new int[]{1, 130, 2}, null, null, null, Map.of(),
                "kit_physical_slot_budget_exceeded", 1L, null, 0L, List.of());
        assertTrue(KitPackedStackCapacityRepair.blockedBoundary(blocked));
        assertFalse(KitPhysicalSlotBudget.fitsAfterCompaction(shell.size(),
                List.copyOf(shell.values()), recipe, packed));
        var opportunity = KitPackedStackCapacityRepair.opportunity(shell, "obsidian");
        assertNotNull(opportunity);
        assertEquals(22, opportunity.outgoingSlot());
        assertEquals(23, opportunity.mergeTargetSlot());
        assertEquals(31, opportunity.amount());
        var source = obsidian(31, "B");
        var plan = opportunity.withLiveSource(5, source);
        assertNotNull(plan);
        var evidence = KitVariantExchangeEvidence.capture(plan, shell, shell,
                Map.of(9, source), 9, 10, true);
        assertNotNull(evidence);
        assertNotNull(KitVariantExchangeEvidence.capture(plan, shell, shell,
                Map.of(44, source), 44, 10, true));
        assertDoesNotThrow(() -> new KitPacker.VariantExchange(plan, 44, true));
        assertThrows(IllegalArgumentException.class,
                () -> new KitPacker.VariantExchange(plan, 44, false));
        var removedShell = new HashMap<>(shell);
        removedShell.remove(22);
        var displacedPlayer = new HashMap<Integer, ItemStack>();
        displacedPlayer.put(9, source);
        displacedPlayer.put(10, opportunity.outgoingStack());
        assertTrue(evidence.matchesAfterRemoval(removedShell, displacedPlayer));
        removedShell.put(22, source);
        displacedPlayer.remove(9);
        assertTrue(evidence.matchesAfterAddition(removedShell, displacedPlayer));
        assertNull(opportunity.withLiveSource(5, obsidian(31, "A")));
        var after = new HashMap<>(shell);
        after.put(22, source);
        assertTrue(KitPhysicalSlotBudget.fitsAfterCompaction(after.size(),
                List.copyOf(after.values()), recipe, packed));
        String sourceHash = KitVariantFingerprint.canonicalVariant(source);
        var repair = new KitFulfillmentJournalStore.VariantRepair(
                new int[]{3, 130, 4}, 54, -1, sourceHash, "obsidian", 31,
                opportunity.outgoingSlot(), opportunity.mergeTargetSlot(),
                sourceHash, KitVariantFingerprint.canonicalVariant(
                        opportunity.outgoingStack()),
                KitVariantFingerprint.canonicalShell(shell), -1, null, null,
                0, 2, true);
        var scheduled = new KitFulfillmentJournalStore.Snapshot(
                blocked.schemaVersion(), blocked.kitName(), "BLOCKED", recipe, packed,
                54, 22, "obsidian", 128, 40, blocked.exportPosition(),
                null, null, null, Map.of(), "resume_variant_exchange", 2L,
                "VARIANT_NEED_SOURCE", 3L, List.of()).withVariantRepair(repair);
        assertTrue(KitPackedStackCapacityRepair.repairCheckpoint(scheduled));
        assertNull(KitResumePolicy.blocker(scheduled));
        var journal = new KitFulfillmentJournalStore(temporary.resolve("obsidian.json"));
        journal.save(scheduled);
        assertEquals(repair.amount(), journal.load().orElseThrow()
                .variantRepair().amount());
        var ready = new KitFulfillmentJournalStore.Snapshot(
                scheduled.schemaVersion(), scheduled.kitName(), "BLOCKED", recipe,
                packed, 54, 22, "obsidian", 128, 40,
                scheduled.exportPosition(), null, null, null, Map.of(),
                "resume_variant_source_cargo", 4L, "VARIANT_WAIT_SOURCE", 5L,
                List.of(new KitFulfillmentJournalStore.BorrowedBox(
                        new int[]{3, 130, 4}, "shulker_box",
                        Map.of("obsidian", 1664))), -1, List.of(), Map.of(),
                0, Map.of(), Map.of(), Map.of(), null)
                .withVariantRepair(repair.withAuditedIncoming(44));
        assertTrue(KitPackedStackCapacityRepair.repairCheckpoint(ready));
        assertNull(KitResumePolicy.blocker(ready));
        journal.save(ready);
        assertEquals(44, journal.load().orElseThrow().variantRepair()
                .auditedIncomingRawSlot());
    }

    @Test void recognizesTheSixAndTenEnderChestSplitWithoutLosingCargo()
            throws Exception {
        Map<Integer, ItemStack> shell = new HashMap<>();
        for (int slot = 0; slot < 17; slot++) shell.put(slot, stack("diamond_sword", 1));
        shell.put(17, named("ender_chest", 6, "A"));
        shell.put(18, named("ender_chest", 10, "B"));
        Map<String, Integer> packed = Map.of("diamond_sword", 17,
                "ender_chest", 16);
        Map<String, Integer> recipe = Map.of("diamond_sword", 17,
                "ender_chest", 16, "experience_bottle", 128,
                "end_crystal", 256, "obsidian", 128,
                "diamond_chestplate", 1);
        var blocked = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                recipe, packed, 54, 39, "ender_chest", 16, 40,
                new int[]{1, 130, 2}, null, null, null, Map.of(),
                "kit_physical_slot_budget_exceeded", 1L, null, 0L, List.of());
        assertTrue(KitPackedStackCapacityRepair.blockedBoundary(blocked));
        assertFalse(KitPhysicalSlotBudget.fitsAfterCompaction(shell.size(),
                List.copyOf(shell.values()), recipe, packed));
        var opportunity = KitPackedStackCapacityRepair.opportunity(shell,
                "ender_chest");
        assertNotNull(opportunity);
        assertEquals(17, opportunity.outgoingSlot());
        assertEquals(18, opportunity.mergeTargetSlot());
        assertEquals(6, opportunity.amount());
        assertNull(opportunity.withLiveSource(5, named("ender_chest", 6, "A")));
        var incoming = named("ender_chest", 6, "B");
        var enderPlan = opportunity.withLiveSource(5, incoming);
        assertNotNull(enderPlan);
        assertNotNull(KitVariantExchangeEvidence.capture(enderPlan, shell, shell,
                Map.of(9, incoming), 9, 10, true));
        String sourceHash = KitVariantFingerprint.canonicalVariant(
                opportunity.mergeTargetStack());
        var repair = new KitFulfillmentJournalStore.VariantRepair(
                new int[]{3, 130, 4}, 54, -1, sourceHash,
                "ender_chest", 6, opportunity.outgoingSlot(),
                opportunity.mergeTargetSlot(), sourceHash,
                KitVariantFingerprint.canonicalVariant(opportunity.outgoingStack()),
                KitVariantFingerprint.canonicalShell(shell), -1, null, null,
                0, 2, true);
        var scheduled = new KitFulfillmentJournalStore.Snapshot(
                blocked.schemaVersion(), blocked.kitName(), "BLOCKED", recipe,
                packed, 54, 39, "ender_chest", 16, 40,
                blocked.exportPosition(), null, null, null, Map.of(),
                "resume_variant_exchange", 2L, "VARIANT_NEED_SOURCE", 3L,
                List.of()).withVariantRepair(repair);
        assertTrue(KitPackedStackCapacityRepair.repairCheckpoint(scheduled));
        assertNull(KitResumePolicy.blocker(scheduled));
        var journal = new KitFulfillmentJournalStore(
                temporary.resolve("ender-chest.json"));
        journal.save(scheduled);
        assertEquals(6, journal.load().orElseThrow().variantRepair().amount());
        shell.put(17, named("ender_chest", 6, "B"));
        assertTrue(KitPhysicalSlotBudget.fitsAfterCompaction(shell.size(),
                List.copyOf(shell.values()), recipe, packed));
    }

    @Test void provesAlreadyExchangedShellWhenDisplacedStackMovedToHotbar()
            throws Exception {
        Map<Integer, ItemStack> before = new HashMap<>();
        for (int slot = 0; slot < 21; slot++)
            before.put(slot, stack("diamond_sword", 1));
        before.put(21, obsidian(64, "A"));
        before.put(22, obsidian(33, "B"));
        ItemStack displaced = obsidian(31, "A");
        ItemStack incoming = obsidian(31, "B");
        before.put(23, displaced);
        var after = new HashMap<>(before);
        after.put(23, incoming);
        Map<String, Integer> packed = Map.of("diamond_sword", 21, "obsidian", 128);
        Map<String, Integer> recipe = Map.of("diamond_sword", 21,
                "obsidian", 128, "end_crystal", 256);
        String sourceHash = KitVariantFingerprint.canonicalVariant(incoming);
        var repair = new KitFulfillmentJournalStore.VariantRepair(
                new int[]{3, 130, 4}, 54, -1, sourceHash, "obsidian", 31,
                23, 22, sourceHash,
                KitVariantFingerprint.canonicalVariant(displaced),
                KitVariantFingerprint.canonicalShell(before), -1, null, null,
                44, 2, true);
        var blocked = new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                recipe, packed, 54, 22, "obsidian", 128, 40,
                new int[]{1, 130, 2}, new int[]{7, 130, 8}, null, null,
                Map.of(), "kit_variant_packed_evidence_unverified", 1L,
                null, 0L, List.of(new KitFulfillmentJournalStore.BorrowedBox(
                        new int[]{3, 130, 4}, "shulker_box",
                        Map.of("obsidian", 1664))), -1, List.of(), Map.of(),
                0, Map.of(), Map.of(), Map.of(), null).withVariantRepair(repair);
        var proof = KitPackedStackCapacityRepair.proveAlreadyPacked(blocked, after,
                Map.of(44, displaced));
        assertNotNull(proof);
        assertEquals(44, proof.displacedRawSlot());
        assertEquals(KitVariantFingerprint.canonicalShell(after),
                proof.resultShellHash());
        assertEquals("none", KitPackedStackCapacityRepair.explainAlreadyPacked(
                blocked, after, Map.of(44, displaced)));
        assertNull(KitPackedStackCapacityRepair.proveAlreadyPacked(blocked, after,
                Map.of(11, displaced, 44, displaced)), "ambiguous displaced stacks");
        assertEquals("displaced_variant_ambiguous",
                KitPackedStackCapacityRepair.explainAlreadyPacked(blocked, after,
                        Map.of(11, displaced, 44, displaced)));
        assertNull(KitPackedStackCapacityRepair.proveAlreadyPacked(blocked, before,
                Map.of(44, displaced)), "unchanged shell cannot prove the exchange");
        assertEquals("incoming_shell_variant_mismatch",
                KitPackedStackCapacityRepair.explainAlreadyPacked(blocked, before,
                        Map.of(44, displaced)));
        var staged = new KitFulfillmentJournalStore.Snapshot(
                blocked.schemaVersion(), blocked.kitName(), "BLOCKED", recipe,
                packed, 54, 22, "obsidian", 128, 40, blocked.exportPosition(),
                null, null, null, Map.of(), "resume_variant_staging", 2L,
                "VARIANT_STAGING", 3L, blocked.sourceBoxesToReturn(), -1,
                List.of(), Map.of(), 0, Map.of(), Map.of(), Map.of(), null)
                .withVariantRepair(repair.withResult(44, repair.outgoingHash(),
                        proof.resultShellHash()));
        assertNull(KitResumePolicy.blocker(staged));
        var journal = new KitFulfillmentJournalStore(temporary.resolve("staged.json"));
        journal.save(staged);
        assertEquals(44, journal.load().orElseThrow().variantRepair()
                .displacedRawSlot());
    }

    private static ItemStack stack(String id, int count) {
        return new ItemStack(ItemRegistry.REGISTRY.get(id).id(), count);
    }

    private static ItemStack obsidian(int count, String name) {
        return named("obsidian", count, name);
    }

    private static ItemStack named(String itemId, int count, String name) {
        DataComponents components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CUSTOM_NAME, Component.text(name));
        return new ItemStack(ItemRegistry.REGISTRY.get(itemId).id(), count, components);
    }
}
