package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class KitShiftedShellCargoAuditTest {
    private static ItemStack item(String id, int count) {
        return new ItemStack(ItemRegistry.REGISTRY.get(id).id(), count);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ItemStack box(String id, String content, int count) {
        List<Optional<ItemStack>> slots = new ArrayList<>();
        for (int i = 0; i < 27; i++) slots.add(Optional.empty());
        for (int i = 0; count > 0; i++) {
            int amount = Math.min(64, count);
            slots.set(i, Optional.of(item(content, amount)));
            count -= amount;
        }
        var components = new DataComponents(new HashMap<>());
        components.put((DataComponentType) DataComponentTypes.CONTAINER, slots);
        return new ItemStack(ItemRegistry.REGISTRY.get(id).id(), 1, components);
    }

    private static KitFulfillmentJournalStore.Snapshot checkpoint(ItemStack retained) {
        return checkpoint(retained, false);
    }

    private static KitFulfillmentJournalStore.Snapshot checkpoint(
            ItemStack retained, boolean reviewedAfterRestart) {
        return new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp",
                reviewedAfterRestart ? "BLOCKED" : "WAIT_CARGO",
                Map.of("obsidian", 128, "experience_bottle", 128),
                Map.of("obsidian", 128), 54, 41, "experience_bottle", 128,
                36, new int[]{1, 70, 2}, null, null, null, Map.of(),
                reviewedAfterRestart ? "uncertain_wait_cargo" : null,
                1L, reviewedAfterRestart ? "WAIT_CARGO" : null, 0L,
                List.of(new KitFulfillmentJournalStore.BorrowedBox(
                        new int[]{3, 70, 4}, "orange_shulker_box",
                        Map.of("experience_bottle", 1344))), -1, List.of(), Map.of(),
                0, Map.of(), Map.of())
                .withRetainedShell(KitRetainedShell.evidence(44, retained));
    }

    private static Map<Integer, ItemStack> shifted(ItemStack retained) {
        return Map.of(11, retained,
                36, box("orange_shulker_box", "experience_bottle", 1344),
                44, box("orange_shulker_box", "obsidian", 128),
                9, item("experience_bottle", 64),
                16, item("experience_bottle", 64));
    }

    @Test
    void rebindsOnlyTheThreeExactBoxesAndBothLooseCargoStacks() {
        ItemStack retained = item("yellow_shulker_box", 1);
        var plan = KitShiftedShellCargoAudit.inspect(checkpoint(retained), shifted(retained),
                Map.of(9, 64, 16, 64));

        assertNotNull(plan);
        assertEquals(44, plan.ownedSlot());
        assertEquals(36, plan.sourceSlot());
        assertEquals(11, plan.retainedShell().rawSlot());
        assertEquals(KitRetainedShell.evidence(44, retained).componentsHash(),
                plan.retainedShell().componentsHash());
    }

    @Test
    void acceptsTheSameExactCargoAfterRestartReview() {
        ItemStack retained = item("yellow_shulker_box", 1);
        var plan = KitShiftedShellCargoAudit.inspect(checkpoint(retained, true),
                shifted(retained), Map.of(9, 64, 16, 64));
        assertNotNull(plan);
        assertEquals(44, plan.ownedSlot());
        assertEquals(11, plan.retainedShell().rawSlot());
    }

    @Test
    void refusesMissingCargoOrAnExtraCarriedBox() {
        ItemStack retained = item("yellow_shulker_box", 1);
        var snapshot = checkpoint(retained);
        var live = shifted(retained);
        assertNull(KitShiftedShellCargoAudit.inspect(snapshot, live, Map.of(9, 64, 16, 63)));

        var extra = new HashMap<>(live);
        extra.put(12, item("white_shulker_box", 1));
        assertNull(KitShiftedShellCargoAudit.inspect(snapshot, extra,
                Map.of(9, 64, 16, 64)));
    }

    @Test
    void refusesAChangedSourceOrReservedShell() {
        ItemStack retained = item("yellow_shulker_box", 1);
        var snapshot = checkpoint(retained);
        var wrongSource = new HashMap<>(shifted(retained));
        wrongSource.put(36, box("orange_shulker_box", "experience_bottle", 1280));
        assertNull(KitShiftedShellCargoAudit.inspect(snapshot, wrongSource,
                Map.of(9, 64, 16, 64)));

        var wrongRetained = new HashMap<>(shifted(retained));
        wrongRetained.put(11, item("white_shulker_box", 1));
        assertNull(KitShiftedShellCargoAudit.inspect(snapshot, wrongRetained,
                Map.of(9, 64, 16, 64)));
    }
}
