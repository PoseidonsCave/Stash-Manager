package com.zenith.plugin.stashmanager.organizer;

import com.zenith.cache.data.inventory.Container;
import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InventoryTransferEvidenceTest {
    @Test
    void confirmsFetchedBoxEvenWhenHopperRefillsTheSource() {
        Container window = new Container(73, 90);
        window.setItemStack(51, box("diamond_boots"));
        var evidence = InventoryTransferEvidence.capture(window, 54, 51);
        window.setItemStack(51, box("diamond_boots"));
        window.setItemStack(89, box("diamond_boots"));
        assertEquals(1, evidence.received(window));
        assertTrue(evidence.taking());
    }

    @Test
    void sameColorWithDifferentContentsIsNotTheRequestedBox() {
        Container window = new Container(73, 90);
        window.setItemStack(51, box("diamond_boots"));
        var evidence = InventoryTransferEvidence.capture(window, 54, 51);
        window.setItemStack(54, box("end_crystal"));
        assertEquals(0, evidence.received(window));
    }

    @Test
    void sourceDrainingAloneIsNotEvidenceOfPlayerReceipt() {
        Container window = new Container(73, 90);
        window.setItemStack(51, box("diamond_boots"));
        var evidence = InventoryTransferEvidence.capture(window, 54, 51);
        window.setItemStack(51, null);
        assertEquals(0, evidence.received(window));
        window.setItemStack(70, box("diamond_boots"));
        assertEquals(1, evidence.received(window));
    }

    @Test
    void preexistingInventoryAndChestGrowthCannotConfirmATake() {
        Container window = new Container(73, 90);
        window.setItemStack(51, box("diamond_boots"));
        window.setItemStack(54, box("diamond_boots"));
        var evidence = InventoryTransferEvidence.capture(window, 54, 51);
        window.setItemStack(52, box("diamond_boots"));
        assertEquals(0, evidence.received(window));
    }

    @Test
    void countsPartialMergeAndCapsReceiptAtRequestedAmount() {
        Container window = new Container(4, 63);
        window.setItemStack(0, item("end_crystal", 64));
        window.setItemStack(27, item("end_crystal", 32));
        var evidence = InventoryTransferEvidence.capture(window, 27, 0);
        window.setItemStack(27, item("end_crystal", 48));
        assertEquals(16, evidence.received(window));
        window.setItemStack(28, item("end_crystal", 64));
        assertEquals(64, evidence.received(window));
    }

    @Test
    void depositObservesChestNotOtherPlayerSlots() {
        Container window = new Container(73, 90);
        window.setItemStack(54, box("diamond_boots"));
        var evidence = InventoryTransferEvidence.capture(window, 54, 54);
        window.setItemStack(55, box("diamond_boots"));
        assertEquals(0, evidence.received(window));
        window.setItemStack(0, box("diamond_boots"));
        assertEquals(1, evidence.received(window));
        assertFalse(evidence.taking());
    }

    @Test
    void countsDepositsDespiteHopperDrainForEveryContainerLayout() {
        for (int containerSlots : new int[]{5, 27, 54}) {
            Container window = new Container(73, containerSlots + 36);
            int playerSlot = containerSlots + 35;
            window.setItemStack(playerSlot, item("obsidian", 28));
            var evidence = InventoryTransferEvidence.capture(window, containerSlots, playerSlot);
            window.setItemStack(playerSlot, null);
            window.setItemStack(0, item("obsidian", 27));
            assertEquals(27, evidence.received(window));
            assertEquals(28, evidence.moved(window));
            window.setItemStack(0, null);
            assertEquals(28, evidence.moved(window));
        }
    }

    @Test
    void partialDepositCountsWhatLeftPlayerNotWhatRemainsInChest() {
        Container window = new Container(73, 90);
        window.setItemStack(89, item("obsidian", 64));
        var evidence = InventoryTransferEvidence.capture(window, 54, 89);
        window.setItemStack(89, item("obsidian", 36));
        window.setItemStack(0, item("obsidian", 27));
        assertEquals(28, evidence.moved(window));
    }

    @Test
    void exactCargoSplitCreditsOnlyUnitsRemovedFromMergedProtectedStacks() {
        Container window = new Container(73, 90);
        window.setItemStack(88, item("firework_rocket", 64));
        window.setItemStack(89, item("firework_rocket", 42));
        var evidence = InventoryTransferEvidence.partialDeposit(window, 54, 89, 13);

        window.setItemStack(89, item("firework_rocket", 29));
        window.setItemStack(0, item("firework_rocket", 13));

        assertEquals(13, evidence.moved(window));
        assertEquals(13, evidence.received(window));
        assertEquals(106, evidence.playerBefore());
        assertEquals(93, evidence.playerUnits(window));
        assertFalse(evidence.taking());
    }

    @Test
    void craftingMaterialTakeCreditsOnlyExactUnitsReceivedByPlayer() {
        Container window = new Container(73, 90);
        window.setItemStack(10, item("shulker_shell", 64));
        var evidence = InventoryTransferEvidence.partialTake(window, 54, 10, 2);

        window.setItemStack(10, item("shulker_shell", 62));
        assertEquals(0, evidence.moved(window));
        window.setItemStack(54, item("shulker_shell", 2));
        assertEquals(2, evidence.moved(window));
        assertEquals(2, evidence.requestedAmount());
    }

    @Test
    void chestGrowthWithoutPlayerLossIsNotADeposit() {
        Container window = new Container(73, 90);
        window.setItemStack(89, item("obsidian", 28));
        var evidence = InventoryTransferEvidence.capture(window, 54, 89);
        window.setItemStack(0, item("obsidian", 64));
        assertEquals(0, evidence.moved(window));
    }

    @Test
    void differentlyPackedShulkerLeavingInventoryCannotConfirmDeposit() {
        Container window = new Container(73, 90);
        window.setItemStack(89, box("diamond_boots"));
        window.setItemStack(54, box("end_crystal"));
        var evidence = InventoryTransferEvidence.capture(window, 54, 89);
        window.setItemStack(54, null);
        assertEquals(0, evidence.moved(window));
        window.setItemStack(89, null);
        assertEquals(1, evidence.moved(window));
    }

    @Test
    void depositCannotCreditMoreThanRequestedEvenWithOtherInventoryChanges() {
        Container window = new Container(73, 90);
        window.setItemStack(89, item("obsidian", 28));
        window.setItemStack(54, item("obsidian", 64));
        var evidence = InventoryTransferEvidence.capture(window, 54, 89);
        window.setItemStack(89, null);
        window.setItemStack(54, null);
        assertEquals(28, evidence.moved(window));
    }

    @Test
    void takeStillRequiresPlayerReceiptEvenWhenSourceDrains() {
        Container window = new Container(73, 90);
        window.setItemStack(0, item("obsidian", 28));
        var evidence = InventoryTransferEvidence.capture(window, 54, 0);
        window.setItemStack(0, null);
        assertEquals(0, evidence.moved(window));
        window.setItemStack(89, item("obsidian", 28));
        assertEquals(28, evidence.moved(window));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static ItemStack box(String content) {
        var components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CONTAINER, (List) List.of(item(content, 1)));
        return new ItemStack(ItemRegistry.REGISTRY.get("shulker_box").id(), 1, components);
    }

    static ItemStack item(String id, int count) {
        return new ItemStack(ItemRegistry.REGISTRY.get(id).id(), count);
    }
}
