package com.zenith.plugin.stashmanager.organizer;

import com.zenith.cache.data.inventory.Container;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import com.zenith.plugin.stashmanager.orchestration.ShulkerClassification;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.util.RequestFuture;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static com.zenith.Globals.CACHE;
import static org.junit.jupiter.api.Assertions.*;

class PackingSupplyRuntimeTest {
    @TempDir Path temp;
    private final int[] staging = {90, 64, 0};
    private final int[] lane = {80, 64, 0};
    private Container raw, window, oldRaw, oldWindow;
    private int oldOpen;
    private StashOrganizer organizer;
    private ContainerIndex index;
    private StashManagerConfig config;
    private OrganizerJournalStore journal;
    private final Map<String, Map<String, Object>> events = new HashMap<>();

    @BeforeEach
    void setup() throws Exception {
        var cache = CACHE.getPlayerCache().getInventoryCache();
        oldOpen = cache.getOpenContainerId();
        raw = new Container(0, 46);
        window = new Container(73, 90);
        oldRaw = cache.getContainers().put(0, raw);
        oldWindow = cache.getContainers().put(73, window);
        set(cache, "openContainerId", 73);
        config = new StashManagerConfig();
        config.pos1 = new int[]{0, 0, 0}; config.pos2 = new int[]{100, 100, 10};
        index = new ContainerIndex();
        index.put(new ContainerEntry(90, 64, 0, "chest", true, Map.of(), 0, List.of(), 1));
        var cacheImports = ContainerIndex.class.getDeclaredMethod("cacheImportChests", Collection.class, boolean.class);
        cacheImports.setAccessible(true);
        cacheImports.invoke(index, List.of(staging), true);
        journal = new OrganizerJournalStore(temp.resolve("plan.json"), temp.resolve("checkpoint.json"));
        organizer = new StashOrganizer(config, index, journal);
        organizer.setEventCallback(events::put);
        set(organizer, "state", StashOrganizer.State.SHULKER_FETCH_TAKE);
        set(organizer, "openContainerId", 73);
        set(organizer, "packItemId", "obsidian");
        set(organizer, "packDestination", lane);
        set(organizer, "reconciliationStation", new int[]{0, 64, 0});
        set(organizer, "currentTask", StashOrganizer.MoveTask.mixedBatchInInventory(staging, lane, "obsidian"));
        set(organizer, "columnAssignment", Map.of("obsidian", new StashOrganizer.Column(1, List.of(lane))));
    }

    @AfterEach
    void cleanup() throws Exception {
        call("clearOwnedAutomation");
        var cache = CACHE.getPlayerCache().getInventoryCache();
        if (oldRaw == null) cache.getContainers().remove(0); else cache.getContainers().put(0, oldRaw);
        if (oldWindow == null) cache.getContainers().remove(73); else cache.getContainers().put(73, oldWindow);
        set(cache, "openContainerId", oldOpen);
    }

    @Test
    void retainedShellIsReallyStoredBeforeTakingTheNextMixedBox() throws Exception {
        var box = mixedBox();
        var classification = ShulkerClassification.classify(ItemIdentifier.readShulkerContents(box));
        var mixed = StashOrganizer.MoveTask.mixed(new int[]{1,64,0},staging,"gray_shulker_box",
                classification.fingerprint(),classification.contents());
        var stale = StashOrganizer.MoveTask.mixedBatch(staging,staging,"totem_of_undying");
        consolidation().add(stale);
        normal().add(mixed);
        for (int slot = 9; slot <= 20; slot++) {
            protectedSlots().add(slot);
            putPlayer(slot,InventoryTransferEvidenceTest.item("diamond_sword",1));
        }
        var shell = InventoryTransferEvidenceTest.item("yellow_shulker_box",1);
        putPlayer(21,shell);
        set(organizer,"currentTask",StashOrganizer.MoveTask.mixedBatch(staging,staging,"totem_of_undying"));
        set(organizer,"packItemId","totem_of_undying");
        set(organizer,"packDestination",staging);
        set(organizer,"consolidationMode",true);
        set(organizer,"mixedBatchConsolidationMode",true);
        set(organizer,"shulkerFillMovedUnits",4);
        set(organizer,"totalTasks",2);
        var delivered = StashOrganizer.class.getDeclaredMethod("completePackedShulkerStore",int.class);
        delivered.setAccessible(true);
        delivered.invoke(organizer,0);
        assertSame(mixed,get(organizer,"currentTask"));
        assertNotNull(raw.getItemStack(21),"Topping up a partial box leaves the reserve aboard");

        openWindow(StashOrganizer.State.TAKING);
        window.setItemStack(0,box);
        call("tickTaking");
        var cleanup = (StashOrganizer.MoveTask) get(organizer,"currentTask");
        assertEquals("@empty",cleanup.shulkerContentFilter());
        assertEquals("yellow_shulker_box",cleanup.itemId());
        assertTrue(cleanup.mandatoryInventoryDeposit());
        assertFalse(events.containsKey("organize_empty_shulker_reused"));
        assertEquals(0,organizer.getCompletedTasks());
        assertEquals(List.of(stale),List.copyOf(consolidation()));
        assertEquals(List.of(mixed),List.copyOf(normal()));

        openWindow(StashOrganizer.State.DEPOSITING);
        // Simulate accepted clicks and server receipts; no live inventory manager runs here.
        pendingMove(66);
        call("tickDepositing");
        assertNotNull(get(organizer,"pendingQuickMove"));
        assertEquals(0,organizer.getCompletedTasks(),"A submitted click is not a completed cleanup");
        pendingMove(66);
        window.setItemStack(0,shell);
        putPlayer(21,null);
        set(organizer,"actionCooldown",0);
        call("tickDepositing");
        set(organizer,"actionCooldown",0);
        call("tickDepositing");
        assertEquals(StashOrganizer.State.CLOSING_DEST,organizer.getState());
        for (int tick = 0; tick < 3; tick++) call("tickClosingDest");
        assertEquals(1,organizer.getCompletedTasks());
        assertSame(mixed,get(organizer,"currentTask"));

        openWindow(StashOrganizer.State.TAKING);
        window.setItemStack(0,box);
        var admit = StashOrganizer.class.getDeclaredMethod("admitMixedShulkerTake",ItemStack.class);
        admit.setAccessible(true);
        assertEquals(true,admit.invoke(organizer,box));
        assertFalse(organizer.isFailed());
        pendingMove(0);
        window.setItemStack(0,null);
        putPlayer(21,box);
        set(organizer,"actionCooldown",0);
        call("tickTaking");
        set(organizer,"actionCooldown",0);
        call("tickTaking");
        closeSource();
        assertEquals(StashOrganizer.State.SHULKER_STATION_WALK,organizer.getState());
        assertEquals(true,get(organizer,"mixedDecompositionMode"));
        assertEquals(0,((InventoryRecoveryGuard) get(organizer,"inventoryRecoveryGuard")).snapshot().attempts());
        assertEquals(List.of(stale),List.copyOf(consolidation()));
        assertFalse(events.containsKey("organize_recovery_loop_detected"));
        for (int slot = 9; slot <= 20; slot++) {
            assertEquals("diamond_sword",ItemIdentifier.getItemId(raw.getItemStack(slot)));
        }
    }

    @Test
    void exhaustedCheckpointResumesCleanupBeforeRevisitingMixedSource() throws Exception {
        durable();
        var classification = ShulkerClassification.classify(ItemIdentifier.readShulkerContents(mixedBox()));
        var mixed = StashOrganizer.MoveTask.mixed(new int[]{1,64,0},staging,"gray_shulker_box",
                classification.fingerprint(),classification.contents());
        set(organizer,"currentTask",mixed);
        set(organizer,"state",StashOrganizer.State.TAKING);
        var stale = StashOrganizer.MoveTask.mixedBatch(staging,staging,"totem_of_undying");
        consolidation().add(stale);
        for (int slot = 9; slot <= 20; slot++) {
            protectedSlots().add(slot);
            putPlayer(slot,InventoryTransferEvidenceTest.item("diamond_sword",1));
        }
        var shell = InventoryTransferEvidenceTest.item("yellow_shulker_box",1);
        putPlayer(21,shell);
        var key = mixed.itemId()+"|"+Arrays.toString(mixed.source())+"|"+mixed.shulkerContentFilter();
        var exhausted = new InventoryRecoveryGuard.Snapshot(key,3,1,23);
        ((InventoryRecoveryGuard) get(organizer,"inventoryRecoveryGuard")).restore(exhausted);
        persist(StashOrganizer.State.TAKING);
        restore();
        resume(StashOrganizer.State.TAKING);

        var cleanup = (StashOrganizer.MoveTask) get(organizer,"currentTask");
        assertTrue(cleanup.mandatoryInventoryDeposit());
        assertEquals("@empty",cleanup.shulkerContentFilter());
        assertEquals(StashOrganizer.State.WALKING,organizer.getState());
        assertEquals("DESTINATION",get(organizer,"currentRole").toString());
        assertEquals(1,normal().size());
        assertTrue(normal().getFirst().mixedDecomposition());
        assertEquals(1,consolidation().size());
        assertEquals(exhausted,((InventoryRecoveryGuard) get(organizer,"inventoryRecoveryGuard")).snapshot());
        assertEquals(0,organizer.getCompletedTasks());

        // Restart once more in the middle of cleanup: keep the deposit intent and guard.
        var saved = journal.load().orElseThrow();
        assertTrue(saved.plan().tasks().stream().filter(t -> t.id() == saved.checkpoint().currentTaskId())
                .findFirst().orElseThrow().mandatoryInventoryDeposit());
        restore();
        resume(StashOrganizer.State.WALKING);
        assertTrue(((StashOrganizer.MoveTask) get(organizer,"currentTask")).mandatoryInventoryDeposit());
        assertEquals(1,normal().size());
        assertEquals(exhausted,((InventoryRecoveryGuard) get(organizer,"inventoryRecoveryGuard")).snapshot());

        openWindow(StashOrganizer.State.DEPOSITING);
        pendingMove(66);
        call("tickDepositing");
        assertEquals(0,organizer.getCompletedTasks());
        assertEquals(exhausted,((InventoryRecoveryGuard) get(organizer,"inventoryRecoveryGuard")).snapshot());
        window.setItemStack(0,shell);
        putPlayer(21,null);
        call("tickDepositing");
        set(organizer,"actionCooldown",0);
        call("tickDepositing");
        for (int tick = 0; tick < 3; tick++) call("tickClosingDest");
        assertEquals(1,organizer.getCompletedTasks());
        assertTrue(((StashOrganizer.MoveTask) get(organizer,"currentTask")).mixedDecomposition());
        var admit = StashOrganizer.class.getDeclaredMethod("admitMixedShulkerTake",ItemStack.class);
        admit.setAccessible(true);
        assertEquals(true,admit.invoke(organizer,mixedBox()));
        assertEquals(0,((InventoryRecoveryGuard) get(organizer,"inventoryRecoveryGuard")).snapshot().attempts());
        assertFalse(organizer.isFailed());
        assertFalse(events.containsKey("organize_empty_shulker_reused"));
    }

    @Test
    void priorityCleanupUpgradesAnExistingTaskWithoutDuplicatingItsJournalId() throws Exception {
        durable();
        var optional = new StashOrganizer.MoveTask(staging,staging,"yellow_shulker_box","@empty",true);
        normal().add(optional);
        putPlayer(44,InventoryTransferEvidenceTest.item("yellow_shulker_box",1));
        set(organizer,"totalTasks",2);
        persist(StashOrganizer.State.WALKING);
        int id = journal.load().orElseThrow().checkpoint().taskQueue().getFirst();
        queueDeposits();
        queueDeposits();
        assertEquals(1,normal().size());
        assertTrue(normal().getFirst().mandatoryInventoryDeposit());
        assertEquals(2,organizer.getTotalTasks());
        persist(StashOrganizer.State.WALKING);
        var saved = journal.load().orElseThrow();
        assertEquals(List.of(id),saved.checkpoint().taskQueue());
        assertEquals(2,saved.plan().tasks().size());
        assertTrue(saved.plan().tasks().stream().filter(t -> t.id() == id)
                .findFirst().orElseThrow().mandatoryInventoryDeposit());
    }

    @Test
    void resumedOptionalShellTaskBecomesCleanupWithoutAddingAnotherTask() throws Exception {
        var optional = new StashOrganizer.MoveTask(staging,staging,"yellow_shulker_box","@empty",true);
        set(organizer,"currentTask",optional);
        putPlayer(44,InventoryTransferEvidenceTest.item("yellow_shulker_box",1));
        consolidation().add(StashOrganizer.MoveTask.mixedBatch(staging,staging,"totem_of_undying"));
        set(organizer,"totalTasks",2);
        resume(StashOrganizer.State.WALKING);
        assertTrue(((StashOrganizer.MoveTask) get(organizer,"currentTask")).mandatoryInventoryDeposit());
        assertTrue(normal().isEmpty());
        assertEquals(1,consolidation().size());
        assertEquals(2,organizer.getTotalTasks());
        assertEquals(0,organizer.getCompletedTasks());
        assertFalse(events.containsKey("organize_empty_shulker_reused"));
    }

    @Test
    void protectedShellIsNotQueuedForMandatoryCleanup() throws Exception {
        putPlayer(44,InventoryTransferEvidenceTest.item("yellow_shulker_box",1));
        protectedSlots().add(44);
        assertFalse(queueDeposits());
        assertTrue(normal().isEmpty());
        assertTrue(consolidation().isEmpty());
    }

    @Test
    void optionalShellCanStillBeReusedWhenNoCleanupIsRequired() throws Exception {
        var optional = new StashOrganizer.MoveTask(staging,staging,"yellow_shulker_box","@empty",true);
        set(organizer,"currentTask",optional);
        putPlayer(44,InventoryTransferEvidenceTest.item("yellow_shulker_box",1));
        var source = StashOrganizer.MoveTask.mixedBatch(staging,staging,"totem_of_undying");
        consolidation().add(source);
        assertEquals(true,call("reuseEmptyShulkerForDeferredPacking"));
        assertSame(source,get(organizer,"currentTask"));
        assertNotNull(raw.getItemStack(44));
        assertTrue(events.containsKey("organize_empty_shulker_reused"));
    }

    @Test
    void mandatoryDepositIntentSurvivesTaskCopies() {
        var required = new StashOrganizer.MoveTask(staging,staging,"yellow_shulker_box","@empty")
                .requireInventoryDeposit();
        assertSame(required,required.requireInventoryDeposit());
        assertTrue(required.markAlreadyInInventory().mandatoryInventoryDeposit());
        assertTrue(required.withDestination(lane).mandatoryInventoryDeposit());
        assertTrue(required.withShulkerFingerprint("@empty").mandatoryInventoryDeposit());
        assertTrue(required.withShulkerSnapshot("@empty",Map.of()).mandatoryInventoryDeposit());
    }

    @Test
    void rejectedMandatoryDepositStopsWithCargoAndMixedWorkStillQueued() throws Exception {
        prepareMandatoryCleanup();
        for (int attempt = 0; attempt < 3; attempt++) {
            pendingMove(89,false);
            set(organizer,"actionCooldown",0);
            call("tickDepositing");
        }
        assertEquals("inventory_transfer_unavailable_with_cargo",organizer.getLastFailureReason());
        assertEquals(0,organizer.getCompletedTasks());
        assertEquals(1,normal().size());
        assertTrue(normal().getFirst().mixedDecomposition());
        assertNotNull(raw.getItemStack(44));
        assertFalse(events.containsKey("organize_empty_shulker_reused"));
        var saved = journal.load().orElseThrow();
        assertEquals(1,saved.checkpoint().taskQueue().size());
        assertTrue(saved.plan().tasks().stream().filter(t -> t.id() == saved.checkpoint().currentTaskId())
                .findFirst().orElseThrow().mandatoryInventoryDeposit());
    }

    @Test
    void fullImportSwitchesMandatoryCleanupAndExhaustionPreservesCargo() throws Exception {
        prepareMandatoryCleanup();
        int[] second = {92,64,0};
        index.put(new ContainerEntry(92,64,0,"chest",true,Map.of(),0,List.of(),1));
        var imports = ContainerIndex.class.getDeclaredMethod("cacheImportChests",Collection.class,boolean.class);
        imports.setAccessible(true);
        imports.invoke(index,List.of(staging,second),true);
        for (int slot = 0; slot < 54; slot++) {
            window.setItemStack(slot,InventoryTransferEvidenceTest.item("stone",64));
        }
        call("tickDepositing");
        assertEquals(StashOrganizer.State.WALKING,organizer.getState());
        var switched = (StashOrganizer.MoveTask) get(organizer,"currentTask");
        assertArrayEquals(second,switched.destination());
        assertTrue(switched.mandatoryInventoryDeposit());
        assertEquals(0,organizer.getCompletedTasks());
        assertNotNull(raw.getItemStack(44));

        openWindow(StashOrganizer.State.DEPOSITING);
        for (int slot = 0; slot < 54; slot++) {
            window.setItemStack(slot,InventoryTransferEvidenceTest.item("stone",64));
        }
        call("tickDepositing");
        assertEquals("empty_shulker_staging_full_with_cargo",organizer.getLastFailureReason());
        assertNotNull(raw.getItemStack(44));
        assertEquals(0,organizer.getCompletedTasks());
        assertEquals(1,normal().size());
        assertFalse(events.containsKey("organize_empty_shulker_reused"));
    }

    @Test
    void mandatoryEmptyDepositDoesNotMatchAFilledBoxOfTheSameColor() throws Exception {
        prepareMandatoryCleanup();
        var filled = new ItemStack(ItemRegistry.REGISTRY.get("yellow_shulker_box").id(),1,
                InventoryTransferEvidenceTest.box("totem_of_undying").getDataComponents());
        putPlayer(44,filled);
        call("tickDepositing");
        assertNull(get(organizer,"pendingQuickMove"));
        assertSame(filled,raw.getItemStack(44));
        assertEquals(0,organizer.getCompletedTasks());
        assertEquals("task_cargo_not_found_during_deposit",organizer.getLastFailureReason());
    }

    private void prepareMandatoryCleanup() throws Exception {
        durable();
        set(organizer,"currentTask",new StashOrganizer.MoveTask(
                staging,staging,"yellow_shulker_box","@empty",true).requireInventoryDeposit());
        normal().add(StashOrganizer.MoveTask.mixed(new int[]{1,64,0},staging,"gray_shulker_box",
                "mixed",Map.of("stone",1,"totem_of_undying",26)));
        consolidation().add(StashOrganizer.MoveTask.mixedBatch(staging,staging,"totem_of_undying"));
        putPlayer(44,InventoryTransferEvidenceTest.item("yellow_shulker_box",1));
        ((CargoTransactionLedger) get(organizer,"taskCargo")).reset(1);
        resume(StashOrganizer.State.WALKING);
        openWindow(StashOrganizer.State.DEPOSITING);
    }

    private void persist(StashOrganizer.State state) throws Exception {
        var method = StashOrganizer.class.getDeclaredMethod("persistDurableCheckpoint",StashOrganizer.State.class);
        method.setAccessible(true);
        assertEquals(true,method.invoke(organizer,state));
    }

    private void restore() throws Exception {
        call("clearOwnedAutomation");
        organizer = new StashOrganizer(config,index,journal);
        organizer.setEventCallback(events::put);
        assertEquals(StashOrganizer.DurableRestoreResult.RESTORED,organizer.restoreDurableCheckpoint());
    }

    private void resume(StashOrganizer.State state) throws Exception {
        var method = StashOrganizer.class.getDeclaredMethod("resumeMoveCheckpoint",StashOrganizer.State.class);
        method.setAccessible(true);
        method.invoke(organizer,state);
    }

    private boolean queueDeposits() throws Exception {
        var method = StashOrganizer.class.getDeclaredMethod("queueInventoryDepositTasks",boolean.class);
        method.setAccessible(true);
        return (boolean) method.invoke(organizer,true);
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private ItemStack mixedBox() {
        var components = new DataComponents(new HashMap<>());
        List<ItemStack> contents = new ArrayList<>();
        for (int slot = 0; slot < 27; slot++) {
            contents.add(InventoryTransferEvidenceTest.item(slot == 0 ? "stone" : "totem_of_undying",1));
        }
        components.put(DataComponentTypes.CONTAINER,(List) contents);
        return new ItemStack(ItemRegistry.REGISTRY.get("gray_shulker_box").id(),1,components);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ItemStack mixedPackingBox() {
        var components = new DataComponents(new HashMap<>());
        List<ItemStack> contents = new ArrayList<>();
        contents.add(InventoryTransferEvidenceTest.item("obsidian", 64));
        for (int slot = 1; slot < 27; slot++) {
            contents.add(InventoryTransferEvidenceTest.item("stone", 1));
        }
        components.put(DataComponentTypes.CONTAINER, (List) contents);
        return new ItemStack(ItemRegistry.REGISTRY.get("gray_shulker_box").id(), 1, components);
    }

    private void openWindow(StashOrganizer.State state) throws Exception {
        set(CACHE.getPlayerCache().getInventoryCache(),"openContainerId",73);
        set(organizer,"openContainerId",73);
        set(organizer,"state",state);
        set(organizer,"actionSlotIndex",0);
        set(organizer,"actionCooldown",0);
        set(organizer,"movedThisVisit",0);
        set(organizer,"sourceVisitFailed",false);
        set(organizer,"destinationVisitFailed",false);
        for (int slot = 0; slot < 54; slot++) window.setItemStack(slot,null);
    }

    @Test
    void firstPartialUnstackableSourcePacksBeforeReturningToMixedWork() throws Exception {
        var source = StashOrganizer.MoveTask.mixedBatch(staging, staging, "totem_of_undying");
        var mixed = new StashOrganizer.MoveTask(new int[]{1,64,0}, staging, "gray_shulker_box",
                "mixed", false, true, false, Map.of("stone",64,"obsidian",64));
        set(organizer, "currentTask", source);
        set(organizer, "state", StashOrganizer.State.TAKING);
        set(organizer, "consolidationMode", true);
        set(organizer, "mixedBatchConsolidationMode", true);
        set(organizer, "movedThisVisit", 22);
        set(organizer, "totalTasks", 2);
        normal().add(mixed);
        for (int slot = 9; slot <= 20; slot++) {
            protectedSlots().add(slot);
            putPlayer(slot, InventoryTransferEvidenceTest.item("diamond_sword", 1));
        }
        putPlayer(21, InventoryTransferEvidenceTest.item("yellow_shulker_box", 1));
        for (int slot = 22; slot <= 43; slot++) {
            putPlayer(slot, InventoryTransferEvidenceTest.item("totem_of_undying", 1));
        }
        window.setItemStack(0, InventoryTransferEvidenceTest.item("totem_of_undying", 1));
        ((CargoTransactionLedger) get(organizer,"taskCargo")).recordAcquired(22);

        call("tickTaking");
        assertEquals(StashOrganizer.State.CLOSING_SOURCE, organizer.getState());
        assertEquals("pack_current_batch_then_retry_source",
                events.get("organize_packing_headroom_preserved").get("disposition"));
        closeSource();

        assertEquals(StashOrganizer.State.SHULKER_STATION_WALK, organizer.getState());
        assertSame(source, get(organizer, "currentTask"));
        assertEquals(List.of(source), List.copyOf(consolidation()));
        assertEquals(List.of(mixed), List.copyOf(normal()));
        assertEquals(0, organizer.getCompletedTasks());
        assertEquals(2, organizer.getTotalTasks());
        assertEquals(0, get(organizer, "consolidationSourcesInBatch"));
        assertFalse(events.containsKey("organize_inventory_recovery_started"));
        assertEquals(22, ((CargoTransactionLedger) get(organizer,"taskCargo")).remaining());
        assertNull(raw.getItemStack(44));
        assertNotNull(raw.getItemStack(21));
        assertNotNull(raw.getItemStack(9));

        for (int slot = 21; slot <= 43; slot++) putPlayer(slot, null);
        set(organizer, "shulkerFillMovedUnits", 22);
        var delivered = StashOrganizer.class.getDeclaredMethod("completePackedShulkerStore", int.class);
        delivered.setAccessible(true);
        delivered.invoke(organizer, 0);
        assertTrue(events.containsKey("organize_packed_shulker_delivered"));
        assertSame(mixed, get(organizer,"currentTask"));
        assertEquals(List.of(source), List.copyOf(consolidation()));
        assertEquals(0, organizer.getCompletedTasks(), "The unfinished source is still pending");
        assertEquals(2, organizer.getTotalTasks());
    }

    @Test
    void interruptedFirstSourceUsesTaskCargoEvenAfterVisitCounterReset() throws Exception {
        set(organizer, "consolidationMode", true);
        set(organizer, "sourceVisitFailed", true);
        set(organizer, "state", StashOrganizer.State.CLOSING_SOURCE);
        ((CargoTransactionLedger) get(organizer,"taskCargo")).recordAcquired(8);
        putPlayer(44, InventoryTransferEvidenceTest.item("obsidian",8));
        closeSource();
        assertEquals(StashOrganizer.State.SHULKER_STATION_WALK, organizer.getState());
        assertEquals(0, organizer.getCompletedTasks());
    }

    @Test
    void partialSourceCheckpointRestoresPackingAndOnlyOneSourceRetry() throws Exception {
        durable();
        var source = StashOrganizer.MoveTask.mixedBatch(staging,lane,"obsidian");
        set(organizer, "currentTask", source);
        set(organizer, "consolidationMode", true);
        set(organizer, "mixedBatchConsolidationMode", true);
        set(organizer, "movedThisVisit", 8);
        ((CargoTransactionLedger) get(organizer,"taskCargo")).recordAcquired(8);
        putPlayer(44,InventoryTransferEvidenceTest.item("obsidian",8));
        call("pauseCollectionForPackingHeadroom");
        call("pauseCollectionForPackingHeadroom");
        var saved = journal.load().orElseThrow().checkpoint();
        assertEquals("CLOSING_SOURCE",saved.interruptedState());
        assertEquals(List.of(saved.currentTaskId()),saved.consolidationQueue());
        assertEquals(0,saved.consolidationSourcesInBatch());

        var restored = new StashOrganizer(config,index,journal);
        assertEquals(StashOrganizer.DurableRestoreResult.RESTORED,restored.restoreDurableCheckpoint());
        var resume = StashOrganizer.class.getDeclaredMethod("resumeMoveCheckpoint",StashOrganizer.State.class);
        resume.setAccessible(true);
        resume.invoke(restored,StashOrganizer.State.CLOSING_SOURCE);
        assertEquals(StashOrganizer.State.SHULKER_STATION_WALK,restored.getState());
        var queued = (Deque<?>) get(restored,"consolidationQueue");
        assertEquals(1,queued.size());
        assertFalse(((StashOrganizer.MoveTask) queued.getFirst()).alreadyInInventory());
        assertEquals(0,restored.getCompletedTasks());
    }

    @Test
    void failedEmptySourceDoesNotInventAPackingBatch() throws Exception {
        set(organizer,"currentTask",StashOrganizer.MoveTask.mixedBatch(staging,staging,"totem_of_undying"));
        set(organizer,"state",StashOrganizer.State.CLOSING_SOURCE);
        set(organizer,"consolidationMode",true);
        set(organizer,"mixedBatchConsolidationMode",true);
        set(organizer,"sourceVisitFailed",true);
        var next = new StashOrganizer.MoveTask(new int[]{1,64,0},staging,"shulker_box",null,false);
        normal().add(next);
        closeSource();
        assertEquals(StashOrganizer.State.WALKING,organizer.getState());
        assertSame(next,get(organizer,"currentTask"));
        assertFalse(events.containsKey("organize_partial_batch_packing"));
    }

    @Test
    void repeatedMixedAdmissionStopsWithoutGrowingQueuesAndSurvivesRestart() throws Exception {
        durable();
        var mixed = new StashOrganizer.MoveTask(new int[]{1,64,0},staging,"gray_shulker_box",
                "same-mixed-box",false,true,false,Map.of("stone",64,"obsidian",64));
        putPlayer(44,InventoryTransferEvidenceTest.item("obsidian",64));
        var admit = StashOrganizer.class.getDeclaredMethod("admitMixedShulkerTake",ItemStack.class);
        admit.setAccessible(true);
        for (int attempt = 0; attempt < InventoryRecoveryGuard.MAX_ATTEMPTS; attempt++) {
            // Replay the observed return to the same source with unchanged cargo.
            normal().clear();
            set(organizer,"currentTask",mixed);
            set(organizer,"state",StashOrganizer.State.TAKING);
            assertEquals(false,admit.invoke(organizer,InventoryTransferEvidenceTest.box("obsidian")));
            assertFalse(organizer.isFailed());
        }
        set(organizer,"currentTask",mixed);
        set(organizer,"state",StashOrganizer.State.TAKING);
        var queueBefore = List.copyOf(normal());
        int totalBefore = organizer.getTotalTasks();
        assertEquals(false,admit.invoke(organizer,InventoryTransferEvidenceTest.box("obsidian")));
        assertEquals("inventory_recovery_no_progress",organizer.getLastFailureReason());
        assertEquals(totalBefore,organizer.getTotalTasks());
        assertEquals(queueBefore,List.copyOf(normal()));
        assertEquals(64,raw.getItemStack(44).getAmount());
        assertEquals(true,events.get("organize_failed").get("checkpoint_preserved"));
        assertEquals(true,events.get("organize_failed").get("cargo_preserved"));
        var saved = journal.load().orElseThrow().checkpoint();
        assertEquals(3,saved.inventoryRecovery().attempts());
        var restored = new StashOrganizer(config,index,journal);
        assertEquals(StashOrganizer.DurableRestoreResult.RESTORED,restored.restoreDurableCheckpoint());
        set(restored,"state",StashOrganizer.State.TAKING);
        assertEquals(false,admit.invoke(restored,InventoryTransferEvidenceTest.box("obsidian")));
        assertEquals("inventory_recovery_no_progress",restored.getLastFailureReason());
        assertEquals(totalBefore,restored.getTotalTasks());
    }

    @Test
    void onlyVerifiedUsefulPackingResetsTheRecoveryGuard() throws Exception {
        var guard = (InventoryRecoveryGuard) get(organizer,"inventoryRecoveryGuard");
        var snapshot = new InventoryRecoveryGuard.Snapshot("blocked",3,23,1);
        var store = StashOrganizer.class.getDeclaredMethod("completePackedShulkerStore",int.class,boolean.class);
        store.setAccessible(true);
        for (int mode = 0; mode < 3; mode++) {
            guard.restore(snapshot);
            set(organizer,"shulkerFillMovedUnits",mode == 0 ? 0 : 64);
            normal().add(new StashOrganizer.MoveTask(staging,lane,"shulker_box",null,false));
            store.invoke(organizer,0,mode != 1);
            assertEquals(mode == 2 ? 0 : 3,guard.snapshot().attempts());
        }
    }

    private void closeSource() throws Exception {
        set(organizer, "actionCooldown", 0);
        for (int tick = 0; tick < 3; tick++) call("tickClosingSource");
    }

    @Test
    void hundredsOfFullCandidatesFallBackAfterThreeAndKeepCargo() throws Exception {
        putPlayer(44, InventoryTransferEvidenceTest.item("obsidian", 64));
        for (int i = 1; i <= 50; i++) addStock(i, Map.of("stone", 1728));
        for (int i = 0; i < 4; i++) call("startFetchShulker");
        assertEquals(3, ((PackingSupplySearch) get(organizer, "packingSupplySearch")).tried().size());
        assertEquals(StashOrganizer.State.OVERFLOW_WALKING, organizer.getState());
        assertEquals(true, get(organizer, "stagingForPackingSupply"));
        assertEquals(64, raw.getItemStack(44).getAmount());
        assertTrue(events.containsKey("organize_packing_supply_deferred"));
    }

    @Test
    void keepListedCargoCanBeStagedWithoutMovingTheRetainedQuota() throws Exception {
        var task = StashOrganizer.MoveTask.mixedBatchInInventory(
                staging, lane, "enchanted_golden_apple");
        set(organizer, "currentTask", task);
        set(organizer, "packItemId", "enchanted_golden_apple");
        set(organizer, "state", StashOrganizer.State.OVERFLOW_DEPOSITING);
        set(organizer, "overflowChestPos", staging);
        set(organizer, "stagingForPackingSupply", true);
        set(organizer, "actionSlotIndex", 9);
        set(organizer, "inventoryKeepPolicy",
                InventoryKeepPolicy.from(Map.of("enchanted_golden_apple", 128)));
        putPlayer(9, InventoryTransferEvidenceTest.item("enchanted_golden_apple", 40));
        putPlayer(10, InventoryTransferEvidenceTest.item("enchanted_golden_apple", 64));
        putPlayer(11, InventoryTransferEvidenceTest.item("enchanted_golden_apple", 64));
        ((CargoTransactionLedger) get(organizer, "taskCargo")).reset(40);

        call("refreshProtectedInventorySlots");
        assertEquals(Set.of(10, 11), protectedSlots());

        // Use the accepted-transfer fixture so this test is independent of the shared mock
        // inventory manager's state when the full suite runs in parallel.
        pendingMove(54);
        window.setItemStack(0, InventoryTransferEvidenceTest.item("enchanted_golden_apple", 40));
        putPlayer(9, null);
        call("tickOverflowDepositing");

        var cargo = (CargoTransactionLedger) get(organizer, "taskCargo");
        assertEquals(0, cargo.remaining());
        assertFalse(organizer.isFailed());
        assertNotNull(raw.getItemStack(10));
        assertNotNull(raw.getItemStack(11));
    }

    @Test
    void anOnboardEmptyShellIsUsedInsteadOfSearchingFullChests() throws Exception {
        putPlayer(9, InventoryTransferEvidenceTest.item("shulker_box", 1));
        putPlayer(44, InventoryTransferEvidenceTest.item("obsidian", 64));
        addStock(1, Map.of("stone", 1728));
        call("startFetchShulker");
        assertEquals(StashOrganizer.State.SHULKER_STATION_WALK, organizer.getState());
        assertFalse(events.containsKey("organize_shulker_source_selected"));
        assertFalse(events.containsKey("organize_packing_supply_deferred"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void packedShellSupplyQueuesOneVerifiedDecompositionInsteadOfAborting() throws Exception {
        assertTrue(StashOrganizer.isPackedShellSupplyCandidate(
                ShulkerClassification.classify(Map.of("shulker_shell", 128))));
        assertFalse(StashOrganizer.isPackedShellSupplyCandidate(
                ShulkerClassification.classify(Map.of("shulker_shell", 1))));
        assertFalse(StashOrganizer.isPackedShellSupplyCandidate(
                ShulkerClassification.classify(Map.of("shulker_shell", 128, "chest", 1))));

        config.craftShulkersEnabled = true;
        int[] table = {2, 64, 0};
        ((Map<Long, int[]>) get(index, "craftingTables")).put(
                new ContainerEntry(2, 64, 0, "crafting_table", false,
                        Map.of(), 0, List.of(), 1).posKey(), table);
        putPlayer(9, InventoryTransferEvidenceTest.item("chest", 1));
        addStock(1, Map.of("shulker_shell", 128));
        set(organizer, "totalTasks", 5);

        assertEquals(true, call("queuePackedShellSupplyDecomposition"));
        var task = normal().peekFirst();
        assertNotNull(task);
        assertTrue(task.mixedDecomposition());
        assertEquals(Map.of("shulker_shell", 128), task.mixedContents());
        assertEquals(6, organizer.getTotalTasks());
        assertTrue(events.containsKey("organize_packed_shell_supply_selected"));
        assertEquals(false, call("queuePackedShellSupplyDecomposition"),
                "The same physical source must not be queued twice");
    }

    @Test
    void packedChestSupplyQueuesSmallSufficientBoxOnlyOnce() throws Exception {
        assertTrue(StashOrganizer.isPackedChestSupplyCandidate(
                ShulkerClassification.classify(Map.of("chest", 365))));
        assertFalse(StashOrganizer.isPackedChestSupplyCandidate(
                ShulkerClassification.classify(Map.of("chest", 365, "oak_planks", 8))));

        config.craftShulkersEnabled = true;
        int[] table = {2, 64, 0};
        ((Map<Long, int[]>) get(index, "craftingTables")).put(
                new ContainerEntry(2, 64, 0, "crafting_table", false,
                        Map.of(), 0, List.of(), 1).posKey(), table);
        putPlayer(9, InventoryTransferEvidenceTest.item("shulker_shell", 2));
        addStock(1, Map.of("chest", 64));
        addStock(2, Map.of("chest", 365));
        addStock(3, Map.of("chest", 1728));
        set(organizer, "totalTasks", 135);

        assertEquals(true, call("queuePackedChestSupplyDecomposition"));
        var task = normal().peekFirst();
        assertNotNull(task);
        assertTrue(task.mixedDecomposition());
        assertEquals(Map.of("chest", 365), task.mixedContents());
        assertEquals(136, organizer.getTotalTasks());
        assertTrue(events.containsKey("organize_packed_chest_supply_selected"));
        assertEquals(false, call("queuePackedChestSupplyDecomposition"),
                "A box must not be queued twice while its first extraction is pending");
    }

    @Test
    void packedChestSourceAttemptSurvivesRestart() throws Exception {
        durable();
        config.craftShulkersEnabled = true;
        int[] table = {2, 64, 0};
        ((Map<Long, int[]>) get(index, "craftingTables")).put(
                new ContainerEntry(2, 64, 0, "crafting_table", false,
                        Map.of(), 0, List.of(), 1).posKey(), table);
        putPlayer(9, InventoryTransferEvidenceTest.item("shulker_shell", 2));
        addStock(1, Map.of("chest", 365));
        set(organizer, "totalTasks", 135);
        assertEquals(true, call("queuePackedChestSupplyDecomposition"));
        var persist = StashOrganizer.class.getDeclaredMethod(
                "persistDurableCheckpoint", StashOrganizer.State.class);
        persist.setAccessible(true);
        assertEquals(true, persist.invoke(organizer, StashOrganizer.State.CLOSING_SOURCE));
        var saved = journal.load().orElseThrow().checkpoint().packingSupply();
        assertEquals(1, saved.chestSourcesTried().size());

        var restored = new StashOrganizer(config, index, journal);
        assertEquals(StashOrganizer.DurableRestoreResult.RESTORED,
                restored.restoreDurableCheckpoint());
        assertEquals(1, ((Set<?>) get(restored, "chestSupplySourcesTried")).size());
    }

    @Test
    void unpackedChestSupplyRemainsLooseForCrafting() throws Exception {
        prepareMixedCompletion();
        var contents = Map.of("chest", 365);
        var classification = ShulkerClassification.classify(contents);
        set(organizer, "currentTask", StashOrganizer.MoveTask.mixed(
                new int[]{1, 64, 0}, staging, "shulker_box",
                classification.fingerprint(), contents));
        set(organizer, "totalTasks", 5);
        putPlayer(9, InventoryTransferEvidenceTest.item("shulker_box", 1));
        var ledger = (MixedStagingLedger) get(organizer, "mixedStagingLedger");
        ledger.reset();
        ledger.begin("chest", staging);
        ledger.confirm();

        call("finishMixedShulkerDecomposition");

        assertFalse(organizer.isFailed());
        assertTrue(events.containsKey("organize_packed_chest_supply_recovered"));
        assertTrue(consolidation().stream().noneMatch(task -> "chest".equals(task.itemId())));
    }

    @Test
    void unpackedShellSupplyIsNotScheduledToBePackedBackIntoAnotherBox() throws Exception {
        prepareMixedCompletion();
        var contents = Map.of("shulker_shell", 128);
        var classification = ShulkerClassification.classify(contents);
        set(organizer, "currentTask", StashOrganizer.MoveTask.mixed(
                new int[]{1, 64, 0}, staging, "shulker_box",
                classification.fingerprint(), contents));
        set(organizer, "totalTasks", 5);
        putPlayer(9, InventoryTransferEvidenceTest.item("shulker_box", 1));
        var ledger = (MixedStagingLedger) get(organizer, "mixedStagingLedger");
        ledger.reset();
        ledger.begin("shulker_shell", staging);
        ledger.confirm();

        call("finishMixedShulkerDecomposition");

        assertFalse(organizer.isFailed());
        assertTrue(events.containsKey("organize_packed_shell_supply_recovered"));
        assertTrue(consolidation().stream().noneMatch(task ->
                "shulker_shell".equals(task.itemId())));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shellOnlyBulkBoxMatchesItsVerifiedExtractionShape() throws Exception {
        var components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CONTAINER, (List) List.of(
                InventoryTransferEvidenceTest.item("shulker_shell", 64),
                InventoryTransferEvidenceTest.item("shulker_shell", 64)));
        var box = new ItemStack(ItemRegistry.REGISTRY.get("white_shulker_box").id(), 1, components);
        var classification = ShulkerClassification.classify(ItemIdentifier.readShulkerContents(box));
        assertEquals(ShulkerClassification.Kind.BULK, classification.kind());
        assertEquals(128, classification.contents().get("shulker_shell"));
        var task = StashOrganizer.MoveTask.mixed(
                new int[]{1, 64, 0}, staging, "white_shulker_box",
                classification.fingerprint(), classification.contents());
        var matcher = StashOrganizer.class.getDeclaredMethod(
                "isDecompositionSourceShape", StashOrganizer.MoveTask.class,
                ShulkerClassification.class);
        matcher.setAccessible(true);

        assertEquals(true, matcher.invoke(null, task, classification));
        assertEquals(false, matcher.invoke(null, task,
                ShulkerClassification.classify(Map.of("obsidian", 128))));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void chestOnlyBulkBoxMatchesItsVerifiedExtractionShape() throws Exception {
        var components = new DataComponents(new HashMap<>());
        components.put(DataComponentTypes.CONTAINER, (List) List.of(
                InventoryTransferEvidenceTest.item("chest", 64)));
        var box = new ItemStack(ItemRegistry.REGISTRY.get("white_shulker_box").id(), 1, components);
        var classification = ShulkerClassification.classify(ItemIdentifier.readShulkerContents(box));
        assertEquals(ShulkerClassification.Kind.BULK, classification.kind());
        assertEquals(64, classification.contents().get("chest"));
        var task = StashOrganizer.MoveTask.mixed(
                new int[]{1, 64, 0}, staging, "white_shulker_box",
                classification.fingerprint(), classification.contents());
        var matcher = StashOrganizer.class.getDeclaredMethod(
                "isDecompositionSourceShape", StashOrganizer.MoveTask.class,
                ShulkerClassification.class);
        matcher.setAccessible(true);

        assertEquals(true, matcher.invoke(null, task, classification));
        assertEquals(false, matcher.invoke(null, task,
                ShulkerClassification.classify(Map.of("shulker_shell", 64))));
    }

    @Test
    void partialStockIsPreferredWhileTheOnboardShellStaysAvailable() throws Exception {
        putPlayer(9, InventoryTransferEvidenceTest.item("shulker_box", 1));
        putPlayer(44, InventoryTransferEvidenceTest.item("obsidian", 64));
        addStock(1, Map.of("obsidian", 1600));
        call("startFetchShulker");
        assertEquals(StashOrganizer.State.SHULKER_FETCH_WALK, organizer.getState());
        assertEquals("matching_partial", events.get("organize_shulker_source_selected").get("candidate_kind"));
        assertNotNull(raw.getItemStack(9));
    }

    @Test
    void searchBudgetSurvivesARestart() throws Exception {
        durable();
        for (int i = 1; i <= 4; i++) addStock(i, Map.of("stone", 1728));
        for (int i = 0; i < 3; i++) call("startFetchShulker");
        var snapshot = journal.load().orElseThrow().checkpoint().packingSupply();
        assertEquals(3, snapshot.search().speculativeAttempts());
        var restored = new StashOrganizer(config, index, journal);
        assertEquals(StashOrganizer.DurableRestoreResult.RESTORED, restored.restoreDurableCheckpoint());
        assertEquals(snapshot.search(), ((PackingSupplySearch) get(restored, "packingSupplySearch")).snapshot());
    }

    @Test
    void aVerifiedEmptyProductWakesOnlyOneDeferredItemGroup() throws Exception {
        var guard = (InventoryRecoveryGuard) get(organizer,"inventoryRecoveryGuard");
        guard.restore(new InventoryRecoveryGuard.Snapshot("blocked",3,23,1));
        putPlayer(9, InventoryTransferEvidenceTest.item("shulker_box", 1));
        var obsidian = StashOrganizer.MoveTask.mixedBatch(new int[]{2,64,0}, lane, "obsidian");
        var crystal = StashOrganizer.MoveTask.mixedBatch(new int[]{3,64,0}, lane, "end_crystal");
        deferred().addAll(List.of(obsidian, crystal));
        consolidation().addAll(List.of(obsidian, crystal));
        prepareMixedCompletion();
        call("finishMixedShulkerDecomposition");
        assertTrue(events.containsKey("organize_empty_shulker_produced"));
        assertEquals(0,guard.snapshot().attempts());
        assertSame(obsidian, get(organizer, "currentTask"));
        assertFalse(deferred().contains(obsidian));
        assertTrue(deferred().contains(crystal));
        assertEquals(1, deferred().size());
        assertNotNull(raw.getItemStack(9));
    }

    @Test
    void aProtectedEmptyShellIsNotDeclaredAvailable() throws Exception {
        prepareMixedCompletion();
        putPlayer(9, InventoryTransferEvidenceTest.item("shulker_box", 1));
        protectedSlots().add(9);
        call("finishMixedShulkerDecomposition");
        assertFalse(events.containsKey("organize_empty_shulker_produced"));
        assertEquals(0, organizer.getCompletedTasks());
        assertTrue(organizer.isFailed());
    }

    @Test
    @SuppressWarnings("unchecked")
    void capacityRecoveryKeepsOwnershipWhenKeepRefreshProtectsExtractedCargo() throws Exception {
        durable();
        var originalContents = Map.of("end_crystal", 64, "obsidian", 64);
        var empty = ShulkerClassification.classify(Map.of());
        set(organizer, "currentTask", StashOrganizer.MoveTask.mixedInInventory(
                new int[]{1, 64, 0}, staging, "cyan_shulker_box",
                empty.fingerprint(), originalContents));
        set(organizer, "mixedDecompositionMode", true);
        set(organizer, "mixedBoxDrained", false);
        set(organizer, "temporaryShulkerOutstanding", false);
        set(organizer, "temporaryShulkerPickupConfirmed", false);
        set(organizer, "temporaryShulkerPickupFingerprint", null);
        set(organizer, "shulkerInventoryCountBeforePlacement", 1);
        set(organizer, "totalTasks", 10);
        set(organizer, "columnAssignment", Map.of(
                "end_crystal", new StashOrganizer.Column(1, List.of(lane)),
                "obsidian", new StashOrganizer.Column(2, List.of(new int[]{81, 64, 0}))));
        putPlayer(13, InventoryTransferEvidenceTest.item("end_crystal", 64));
        putPlayer(14, InventoryTransferEvidenceTest.item("cyan_shulker_box", 1));
        ((Set<Integer>) get(organizer, "mixedCargoSlots")).add(13);
        protectedSlots().add(13);
        protectedSlots().add(14);
        var ledger = (MixedStagingLedger) get(organizer, "mixedStagingLedger");
        ledger.begin("obsidian", staging);
        ledger.confirm();

        var finish = StashOrganizer.class.getDeclaredMethod(
                "finishResumableStagingStop", String.class);
        finish.setAccessible(true);
        finish.invoke(organizer, "mixed_staging_full");

        assertFalse(organizer.isFailed());
        assertFalse(protectedSlots().contains(13),
                "A durable mixed-cargo slot must outrank a derived keep snapshot");
        assertFalse(protectedSlots().contains(14),
                "The unique recovered shell recorded by the checkpoint is organizer-owned");
        assertEquals(StashOrganizer.State.SHULKER_STATION_WALK, organizer.getState());
        var packing = (StashOrganizer.MoveTask) get(organizer, "currentTask");
        assertEquals("end_crystal", packing.itemId());
        assertTrue(packing.alreadyInInventory());
        assertEquals(64, ((CargoTransactionLedger) get(organizer, "taskCargo")).remaining());
        assertTrue(consolidation().stream().anyMatch(task ->
                task.itemId().equals("obsidian") && !task.alreadyInInventory()),
                "Confirmed import receipts remain queued for later consolidation");
        assertTrue(events.containsKey("organize_mixed_cargo_ownership_restored"));
        assertTrue(events.containsKey("organize_mixed_shell_ownership_restored"));
        assertTrue(events.containsKey("organize_empty_shulker_reused"));
        assertTrue(events.containsKey("organize_capacity_recovery_continued"));
    }

    @Test
    void postStagingRestartFinishesItsProtectedRecoveredEmptyShell() throws Exception {
        durable();
        var empty = ShulkerClassification.classify(Map.of());
        set(organizer, "currentTask", StashOrganizer.MoveTask.mixedInInventory(
                new int[]{1, 64, 0}, staging, "cyan_shulker_box",
                empty.fingerprint(), Map.of("obsidian", 64)));
        set(organizer, "mixedDecompositionMode", true);
        set(organizer, "mixedBoxDrained", false);
        set(organizer, "temporaryShulkerOutstanding", false);
        set(organizer, "totalTasks", 10);
        set(organizer, "columnAssignment", Map.of(
                "obsidian", new StashOrganizer.Column(1, List.of(lane))));
        putPlayer(10, InventoryTransferEvidenceTest.item("cyan_shulker_box", 1));
        protectedSlots().add(10);
        var ledger = (MixedStagingLedger) get(organizer, "mixedStagingLedger");
        ledger.begin("obsidian", staging);
        ledger.confirm();

        call("resumePackingSelection");

        assertFalse(organizer.isFailed());
        assertFalse(protectedSlots().contains(10));
        assertEquals(2, organizer.getCompletedTasks(),
                "The mixed box and its reused empty-shell staging task both complete");
        assertEquals("obsidian", ((StashOrganizer.MoveTask) get(organizer, "currentTask")).itemId());
        assertEquals(StashOrganizer.State.WALKING, organizer.getState());
        assertTrue(events.containsKey("organize_mixed_shell_ownership_restored"));
        assertTrue(events.containsKey("organize_empty_shulker_reused"));
        assertTrue(events.containsKey("organize_capacity_recovery_continued"));
    }

    @Test
    void restoredPartialMixedBoxRepairsItsFingerprintAndGetsAFreshStationTrip() throws Exception {
        var liveBox = mixedBox();
        var live = ShulkerClassification.classify(ItemIdentifier.readShulkerContents(liveBox));
        var empty = ShulkerClassification.classify(Map.of());
        set(organizer, "currentTask", StashOrganizer.MoveTask.mixedInInventory(
                new int[]{1, 64, 0}, staging, "gray_shulker_box",
                empty.fingerprint(), Map.of("stone", 65, "totem_of_undying", 26)));
        set(organizer, "mixedDecompositionMode", true);
        set(organizer, "mixedBoxDrained", false);
        set(organizer, "temporaryShulkerOutstanding", false);
        putPlayer(10, liveBox);
        var recovery = (StationWalkRecovery) get(organizer, "stationWalkRecovery");
        recovery.restore(new StationWalkRecovery.Snapshot(
                new StationWalkRecovery.Point(0, 64, 0),
                new StationWalkRecovery.Point(2, 64, 0),
                802, 202, StationWalkRecovery.MAX_DETOURS,
                new StationWalkRecovery.Position(2.5, 64, 0.5),
                2.0, List.of(new StationWalkRecovery.Point(2, 64, 0))));

        call("resumePackingSelection");

        var repaired = (StashOrganizer.MoveTask) get(organizer, "currentTask");
        assertEquals(live.fingerprint(), repaired.shulkerContentFilter());
        assertEquals(Map.of("stone", 65, "totem_of_undying", 26), repaired.mixedContents(),
                "The original manifest remains available for staged-cargo task generation");
        assertNull(recovery.snapshot());
        assertEquals(StashOrganizer.State.SHULKER_STATION_WALK, organizer.getState());
        assertEquals("continue_live_partial_box",
                events.get("organize_mixed_shulker_identity_refreshed").get("disposition"));
        assertEquals("fresh_bounded_station_trip",
                events.get("organize_station_walk_rearmed").get("disposition"));
        assertEquals(0, organizer.getCompletedTasks());
    }

    @Test
    void overflowCheckpointFindsThePackedBoxInsteadOfSearchingForLooseCargo() throws Exception {
        set(organizer, "currentTask", StashOrganizer.MoveTask.mixedBatchInInventory(
                staging, lane, "diamond_leggings"));
        set(organizer, "packItemId", "diamond_leggings");
        set(organizer, "packDestination", lane.clone());
        set(organizer, "consolidationMode", true);
        set(organizer, "mixedBatchConsolidationMode", true);
        set(organizer, "mixedDecompositionMode", false);
        set(organizer, "shulkerFillMovedUnits", 1);
        set(organizer, "temporaryShulkerPickupConfirmed", true);
        set(organizer, "temporaryShulkerPickupItemId", "cyan_shulker_box");
        set(organizer, "temporaryShulkerPickupStorageKey", "diamond_leggings");
        set(organizer, "stagingForPackingSupply", true);
        set(organizer, "state", StashOrganizer.State.OVERFLOW_DEPOSITING);
        ((CargoTransactionLedger) get(organizer, "taskCargo")).reset(1);
        putPlayer(10, InventoryTransferEvidenceTest.item("cyan_shulker_box", 1));

        call("tickOverflowDepositing");

        assertEquals(StashOrganizer.State.SHULKER_STORE_WALK, organizer.getState());
        assertArrayEquals(lane, (int[]) get(organizer, "walkTarget"));
        assertFalse((boolean) get(organizer, "stagingForPackingSupply"));
        assertEquals(1, ((CargoTransactionLedger) get(organizer, "taskCargo")).remaining());
        assertEquals("resume_packed_shulker_handoff",
                events.get("organize_overflow_packed_shulker_recovered").get("disposition"));
        assertEquals("cyan_shulker_box", get(organizer, "temporaryShulkerPickupItemId"));
        assertFalse(organizer.isFailed());
    }

    @Test
    void destinationWindowNeverTreatsAMixedBoxAsThePackedBulkOutput() throws Exception {
        prepareStalePackedTransaction();
        openWindow(StashOrganizer.State.SHULKER_STORE_DEPOSIT);
        putPlayer(38, mixedPackingBox());

        var select = StashOrganizer.class.getDeclaredMethod(
                "packedShulkerTransactionSlot",
                Container.class, int.class, int.class, boolean.class);
        select.setAccessible(true);

        assertEquals(-1, select.invoke(organizer, window, 54, 0, false));
        assertFalse(organizer.isFailed());
    }

    @Test
    void destinationWindowAcceptsTheUniqueRecoveredEmptyShellWithStaleMixedComponents()
            throws Exception {
        prepareStalePackedTransaction();
        set(organizer, "shulkerInventoryCountBeforePlacement", 1);
        set(organizer, "compatibleShulkerCountBeforePlacement", 0);
        set(organizer, "temporaryShulkerPickupItemId", "gray_shulker_box");
        openWindow(StashOrganizer.State.SHULKER_STORE_DEPOSIT);
        putPlayer(38, mixedPackingBox());
        putPlayer(43, InventoryTransferEvidenceTest.item("light_gray_shulker_box", 1));

        var select = StashOrganizer.class.getDeclaredMethod(
                "packedShulkerTransactionSlot",
                Container.class, int.class, int.class, boolean.class);
        select.setAccessible(true);

        assertEquals(38, select.invoke(organizer, window, 54, 0, false));
        assertFalse(organizer.isFailed());
    }

    @Test
    void recoveryKeepsPickupIdentityUntilThePackedHandoffIsVerified() throws Exception {
        set(organizer, "temporaryShulkerOutstanding", true);
        set(organizer, "temporaryShulkerPickupConfirmed", true);
        set(organizer, "temporaryShulkerPickupItemId", "gray_shulker_box");
        set(organizer, "temporaryShulkerPickupStorageKey", "obsidian");
        set(organizer, "shulkerInventoryCountBeforePlacement", 2);
        set(organizer, "compatibleShulkerCountBeforePlacement", 0);
        set(organizer, "packedShulkerInventorySyncRecoveries", 2);
        set(organizer, "shulkerPlacePos", new int[]{0, 64, 1});

        call("resetTemporaryShulkerWorksiteForPackedHandoff");

        assertFalse((boolean) get(organizer, "temporaryShulkerOutstanding"));
        assertNull(get(organizer, "shulkerPlacePos"));
        assertTrue((boolean) get(organizer, "temporaryShulkerPickupConfirmed"));
        assertEquals("gray_shulker_box", get(organizer, "temporaryShulkerPickupItemId"));
        assertEquals("obsidian", get(organizer, "temporaryShulkerPickupStorageKey"));
        assertEquals(2, get(organizer, "shulkerInventoryCountBeforePlacement"));
        assertEquals(2, get(organizer, "packedShulkerInventorySyncRecoveries"));
    }

    @Test
    void destinationWindowUsesOnlyTheUniquePacketIdentifiedPackedShell() throws Exception {
        prepareStalePackedTransaction();
        set(organizer, "temporaryShulkerPickupFingerprint",
                ShulkerClassification.classify(Map.of("obsidian", 64)).fingerprint());
        set(organizer, "temporaryShulkerPickupStorageKey", "obsidian");
        set(organizer, "temporaryShulkerPickupItemId", "gray_shulker_box");
        set(organizer, "shulkerInventoryCountBeforePlacement", 2);
        openWindow(StashOrganizer.State.SHULKER_STORE_DEPOSIT);
        putPlayer(38, mixedPackingBox());
        putPlayer(43, InventoryTransferEvidenceTest.item("light_gray_shulker_box", 1));

        var select = StashOrganizer.class.getDeclaredMethod(
                "packedShulkerTransactionSlot",
                Container.class, int.class, int.class, boolean.class);
        select.setAccessible(true);

        assertEquals(38, select.invoke(organizer, window, 54, 0, false));
        set(organizer, "temporaryShulkerPickupStorageKey", "stone");
        assertEquals(-1, select.invoke(organizer, window, 54, 0, false));
        set(organizer, "temporaryShulkerPickupStorageKey", "obsidian");
        set(organizer, "shulkerInventoryCountBeforePlacement", 3);
        assertEquals(-1, select.invoke(organizer, window, 54, 0, false));
        set(organizer, "shulkerInventoryCountBeforePlacement", 2);
        putPlayer(44, InventoryTransferEvidenceTest.item("gray_shulker_box", 1));
        protectedSlots().add(44);
        assertEquals(-1, select.invoke(organizer, window, 54, 0, false),
                "A second same-colored box, even a protected one, makes pickup identity ambiguous");
    }

    @Test
    void destinationWindowReclaimsUniquePacketIdentifiedBoxFromAStaleProtectedSlot()
            throws Exception {
        prepareStalePackedTransaction();
        set(organizer, "temporaryShulkerPickupItemId", "gray_shulker_box");
        set(organizer, "shulkerInventoryCountBeforePlacement", 1);
        openWindow(StashOrganizer.State.SHULKER_STORE_DEPOSIT);
        putPlayer(38, InventoryTransferEvidenceTest.item("gray_shulker_box", 1));
        protectedSlots().add(38);

        var select = StashOrganizer.class.getDeclaredMethod(
                "packedShulkerTransactionSlot",
                Container.class, int.class, int.class, boolean.class);
        select.setAccessible(true);

        assertEquals(38, select.invoke(organizer, window, 54, 0, false));
        assertFalse(protectedSlots().contains(38));
        assertEquals("transaction_packet_overrides_stale_keep_slot",
                events.get("organize_packed_shulker_ownership_restored").get("reason"));
    }

    @Test
    void pickupSyncRetryReturnsToTheWorksiteInsteadOfSweepingWithoutATarget() throws Exception {
        prepareStalePackedTransaction();
        int[] worksite = {1, 64, 1};
        set(organizer, "reconciliationWorksite", worksite);
        set(organizer, "shulkerPlacePos", null);

        var recover = StashOrganizer.class.getDeclaredMethod(
                "recoverPackedShulkerInventoryVisibility", String.class);
        recover.setAccessible(true);
        recover.invoke(organizer, "packed_shulker_not_found_in_destination_window");

        assertEquals(StashOrganizer.State.SHULKER_PICKUP, organizer.getState());
        assertArrayEquals(worksite, (int[]) get(organizer, "shulkerPlacePos"));
        assertEquals(1, get(organizer, "packedShulkerInventorySyncRecoveries"));
    }

    @Test
    void pickupSyncRetryClearsDisprovedCollectionEvidence() throws Exception {
        prepareStalePackedTransaction();
        set(organizer, "temporaryShulkerPickupItemId", "gray_shulker_box");
        set(organizer, "temporaryShulkerPickupStorageKey", "obsidian");
        set(organizer, "temporaryShulkerPickupStack",
                InventoryTransferEvidenceTest.item("gray_shulker_box", 1));
        set(organizer, "reconciliationWorksite", new int[]{1,64,1});

        var recover = StashOrganizer.class.getDeclaredMethod(
                "recoverPackedShulkerInventoryVisibility", String.class);
        recover.setAccessible(true);
        recover.invoke(organizer, "packed_shulker_not_found_in_destination_window");

        assertFalse((boolean) get(organizer, "temporaryShulkerPickupConfirmed"));
        assertNull(get(organizer, "temporaryShulkerPickupFingerprint"));
        assertNull(get(organizer, "temporaryShulkerPickupStorageKey"));
        assertNull(get(organizer, "temporaryShulkerPickupItemId"));
        assertNull(get(organizer, "temporaryShulkerPickupStack"));
        assertEquals(StashOrganizer.State.SHULKER_PICKUP, organizer.getState());
    }

    @Test
    void destinationWindowConvertsTheWrongMixedShellIntoADecompositionTask() throws Exception {
        prepareStalePackedTransaction();
        openWindow(StashOrganizer.State.SHULKER_STORE_DEPOSIT);
        putPlayer(38, mixedPackingBox());

        call("tickShulkerStoreDeposit");

        var recovered = (StashOrganizer.MoveTask) get(organizer, "currentTask");
        assertTrue(recovered.mixedDecomposition());
        assertEquals(StashOrganizer.State.SHULKER_STATION_WALK, organizer.getState());
        assertEquals("decompose_recovered_mixed_box",
                events.get("organize_legacy_mixed_packing_recovered").get("disposition"));
        assertFalse(organizer.isFailed());
    }

    @Test
    void restartConvertsTheWrongMixedShellInsteadOfRepeatingPickupSync() throws Exception {
        prepareStalePackedTransaction();
        putPlayer(38, mixedPackingBox());
        set(organizer, "packedShulkerInventorySyncRecoveries", 4);

        var resume = StashOrganizer.class.getDeclaredMethod(
                "resumeInterruptedCheckpoint", StashOrganizer.State.class);
        resume.setAccessible(true);
        resume.invoke(organizer, StashOrganizer.State.SHULKER_STORE_DEPOSIT);

        var recovered = (StashOrganizer.MoveTask) get(organizer, "currentTask");
        assertTrue(recovered.mixedDecomposition());
        assertEquals(StashOrganizer.State.SHULKER_STATION_WALK, organizer.getState());
        assertTrue(events.containsKey("organize_legacy_mixed_packing_recovered"));
        assertFalse(organizer.isFailed());
    }

    @Test
    void aPendingPickupCannotCreateSupplyEvenIfAnotherEmptyShellIsPresent() throws Exception {
        prepareMixedCompletion();
        putPlayer(9, InventoryTransferEvidenceTest.item("shulker_box", 1));
        set(organizer, "temporaryShulkerOutstanding", true);
        call("finishMixedShulkerDecomposition");
        assertFalse(events.containsKey("organize_empty_shulker_produced"));
        assertEquals(0, organizer.getCompletedTasks());
    }

    @Test
    void surplusShellsAreStagedInsteadOfAccumulatingInInventory() throws Exception {
        prepareMixedCompletion();
        putPlayer(9, InventoryTransferEvidenceTest.item("shulker_box", 1));
        putPlayer(10, InventoryTransferEvidenceTest.item("shulker_box", 1));
        call("finishMixedShulkerDecomposition");
        var task = (StashOrganizer.MoveTask) get(organizer, "currentTask");
        assertEquals("@empty", task.shulkerContentFilter());
        assertFalse(events.containsKey("organize_empty_shulker_reused"));
    }

    @Test
    void unusedEmptyShellIsStoredBeforeDeclaringCompletion() throws Exception {
        putPlayer(9, InventoryTransferEvidenceTest.item("shulker_box", 1));
        call("finishOrganization");
        assertNotEquals(StashOrganizer.State.DONE, organizer.getState());
        var task = (StashOrganizer.MoveTask) get(organizer, "currentTask");
        assertEquals("@empty", task.shulkerContentFilter());
        assertFalse(events.containsKey("organize_completed"));
    }

    @Test
    void shortageStagingRecordsPartialAndSplitDestinationsAndSurvivesRestart() throws Exception {
        durable();
        putPlayer(44, InventoryTransferEvidenceTest.item("obsidian", 64));
        set(organizer, "state", StashOrganizer.State.OVERFLOW_DEPOSITING);
        set(organizer, "overflowChestPos", staging);
        set(organizer, "stagingForPackingSupply", true);
        set(organizer, "actionSlotIndex", 44);
        ((CargoTransactionLedger) get(organizer, "taskCargo")).reset(64);
        var ledger = (MixedStagingLedger) get(organizer, "supplyStagingLedger");
        ledger.begin("obsidian", staging);
        pendingMove(89);
        putPlayer(44, InventoryTransferEvidenceTest.item("obsidian", 32));
        call("tickOverflowDepositing");
        assertEquals(1, ledger.snapshot().confirmed().size());
        int[] second = {91,64,0};
        set(organizer, "overflowChestPos", second);
        ledger.begin("obsidian", second);
        pendingMove(89);
        putPlayer(44, null);
        set(organizer, "actionCooldown", 0);
        call("tickOverflowDepositing");
        set(organizer, "actionCooldown", 0);
        normal().add(new StashOrganizer.MoveTask(new int[]{1,64,0}, staging, "shulker_box", null, false));
        call("tickOverflowDepositing");
        assertEquals(2, deferred().size());
        assertEquals(2, consolidation().size());
        assertTrue(consolidation().stream().allMatch(t -> t.itemId().equals("obsidian")));
        var saved = journal.load().orElseThrow().checkpoint();
        assertFalse(saved.packingSupply().stagingCargo());
        assertEquals(2, saved.packingSupply().deferredTaskIds().size());
        assertTrue(saved.consolidationQueue().containsAll(saved.packingSupply().deferredTaskIds()));
        var restored = new StashOrganizer(config, index, journal);
        assertEquals(StashOrganizer.DurableRestoreResult.RESTORED, restored.restoreDurableCheckpoint());
        assertEquals(2, ((Set<?>) get(restored, "supplyDeferredTasks")).size());
    }

    @Test
    void noSupplyPreservesTheDeferredQueueInsteadOfReportingDone() throws Exception {
        durable();
        var task = StashOrganizer.MoveTask.mixedBatch(staging, lane, "obsidian");
        consolidation().add(task); deferred().add(task);
        call("finishOrganization");
        assertEquals("packing_supply_unavailable", organizer.getLastFailureReason());
        assertEquals(1, journal.load().orElseThrow().checkpoint().packingSupply().deferredTaskIds().size());
        assertFalse(events.containsKey("organize_completed"));
    }

    @Test
    void interruptedDepositKeepsItsAttemptedSourceEvenWithoutAnAcknowledgement() throws Exception {
        durable();
        putPlayer(44, InventoryTransferEvidenceTest.item("obsidian", 64));
        set(organizer, "state", StashOrganizer.State.OVERFLOW_DEPOSITING);
        set(organizer, "overflowChestPos", staging);
        set(organizer, "stagingForPackingSupply", true);
        set(organizer, "actionSlotIndex", 44);
        ((CargoTransactionLedger) get(organizer, "taskCargo")).reset(64);
        normal().add(new StashOrganizer.MoveTask(new int[]{1,64,0}, staging, "shulker_box", null, false));
        call("tickOverflowDepositing");
        var saved = journal.load().orElseThrow().checkpoint().packingSupply();
        assertTrue(saved.stagingCargo());
        assertEquals(1, saved.stagingLedger().uncertain().size());
        assertTrue(saved.stagingLedger().confirmed().isEmpty());

        putPlayer(44, null);
        var restored = new StashOrganizer(config, index, journal);
        assertEquals(StashOrganizer.DurableRestoreResult.RESTORED, restored.restoreDurableCheckpoint());
        var resume = StashOrganizer.class.getDeclaredMethod("resumeOverflowCheckpoint");
        resume.setAccessible(true);
        resume.invoke(restored);
        assertEquals(1, ((Set<?>) get(restored, "supplyDeferredTasks")).size());
        assertFalse((boolean) get(restored, "stagingForPackingSupply"));
        assertEquals(StashOrganizer.State.WALKING, restored.getState());
    }

    @Test
    void addingAnUnprotectedEmptyShellLetsTheSavedQueueResumeWithoutRescanning() throws Exception {
        durable();
        var task = StashOrganizer.MoveTask.mixedBatch(staging, lane, "obsidian");
        consolidation().add(task); deferred().add(task);
        call("finishOrganization");
        putPlayer(9, InventoryTransferEvidenceTest.item("shulker_box", 1));
        var restored = new StashOrganizer(config, index, journal);
        assertEquals(StashOrganizer.DurableRestoreResult.RESTORED, restored.restoreDurableCheckpoint());
        var resume = StashOrganizer.class.getDeclaredMethod("resumeMoveCheckpoint", StashOrganizer.State.class);
        resume.setAccessible(true);
        resume.invoke(restored, StashOrganizer.State.CLOSING_SOURCE);
        assertEquals(StashOrganizer.State.WALKING, restored.getState());
        assertTrue(((Set<?>) get(restored, "supplyDeferredTasks")).isEmpty());
        assertEquals("obsidian", ((StashOrganizer.MoveTask) get(restored, "currentTask")).itemId());
    }

    @Test
    void aBoxWithRemainingContentsIsNotAnEmptyProduct() throws Exception {
        prepareMixedCompletion();
        putPlayer(9, InventoryTransferEvidenceTest.box("obsidian"));
        call("finishMixedShulkerDecomposition");
        assertFalse(events.containsKey("organize_empty_shulker_produced"));
        assertEquals(0, organizer.getCompletedTasks());
    }

    @Test
    void absentInventoryPackingCargoIsVerifiedLiveAndRetiredWithoutAFalseHandoff() throws Exception {
        durable();
        set(organizer, "stagingForPackingSupply", true);
        call("resumeOverflowCheckpoint");

        assertEquals(StashOrganizer.State.OVERFLOW_WALKING, organizer.getState());
        openWindow(StashOrganizer.State.OVERFLOW_DEPOSITING);
        call("tickOverflowDepositing");

        assertFalse(organizer.isFailed());
        assertNull(organizer.getLastFailureReason());
        assertEquals(StashOrganizer.State.CLOSING_SOURCE, organizer.getState());
        assertNull(get(organizer, "currentTask"));
        assertTrue(events.containsKey("organize_stale_inventory_cargo_retired"));
        assertTrue(deferred().isEmpty());
        assertEquals(0, organizer.getCompletedTasks());
    }

    @Test
    void staleInventoryHeadIsRetiredWhileStoredSiblingSourcesRemainQueued() throws Exception {
        durable();
        var stored = StashOrganizer.MoveTask.mixedBatch(
                new int[]{91, 64, 0}, lane, "obsidian");
        consolidation().add(stored);
        set(organizer, "stagingForPackingSupply", true);

        call("resumeOverflowCheckpoint");

        assertFalse(organizer.isFailed());
        assertEquals(StashOrganizer.State.CLOSING_SOURCE, organizer.getState());
        assertEquals(List.of(stored), List.copyOf(consolidation()));
        assertTrue(events.containsKey("organize_stale_inventory_cargo_retired"));
        assertEquals(1, events.get("organize_stale_inventory_cargo_retired").get("retired_tasks"));
    }

    @Test
    void recoveredPackedBoxWinsBeforeTheEmptyStagingReceiptCheck() throws Exception {
        durable();
        set(organizer, "stagingForPackingSupply", true);
        set(organizer, "temporaryShulkerPickupConfirmed", true);
        set(organizer, "temporaryShulkerPickupStorageKey", "obsidian");

        call("resumeOverflowCheckpoint");

        assertFalse(organizer.isFailed());
        assertEquals(StashOrganizer.State.SHULKER_STORE_WALK, organizer.getState());
        assertTrue(events.containsKey("organize_recovery_completed"));
    }

    @Test
    void placementFallbackJournalsSplitDepositsAndClearsTheFinishedBatch() throws Exception {
        durable();
        var source=StashOrganizer.MoveTask.mixedBatch(staging,lane,"obsidian");
        set(organizer,"currentTask",source);set(organizer,"consolidationMode",true);
        set(organizer,"mixedBatchConsolidationMode",true);
        set(organizer,"consolidationSourcesInBatch",1);set(organizer,"sourceVisitFailed",true);
        set(organizer,"movedThisVisit",2);set(organizer,"totalTasks",2);
        var cargo=(CargoTransactionLedger)get(organizer,"taskCargo");cargo.reset(128);
        putPlayer(9,InventoryTransferEvidenceTest.item("shulker_box",1));
        putPlayer(43,InventoryTransferEvidenceTest.item("obsidian",64));
        putPlayer(44,InventoryTransferEvidenceTest.item("obsidian",64));
        var next=StashOrganizer.MoveTask.mixed(new int[]{1,64,0},staging,"gray_shulker_box",
                "mixed",Map.of("obsidian",64,"end_crystal",64));
        normal().add(next);
        set(organizer,"state",StashOrganizer.State.SHULKER_WAIT_PLACE);
        call("startOverflow");
        assertEquals(true,get(organizer,"stagingForPackingSupply"));
        assertTrue(journal.load().orElseThrow().checkpoint().packingSupply().stagingCargo());
        assertEquals(StashOrganizer.State.OVERFLOW_WALKING,organizer.getState());
        assertTrue(events.containsKey("organize_packing_fallback_started"));

        openWindow(StashOrganizer.State.OVERFLOW_DEPOSITING);
        depositPackingCargo(43,18);
        var supply=journal.load().orElseThrow().checkpoint().packingSupply();
        assertEquals(1,supply.stagingLedger().confirmed().size());
        int[] second={92,64,0};set(organizer,"overflowChestPos",second);
        depositPackingCargo(43,0);
        depositPackingCargo(44,0);
        supply=journal.load().orElseThrow().checkpoint().packingSupply();
        assertEquals(2,supply.stagingLedger().confirmed().size());
        assertEquals(128,cargo.acquired());assertEquals(128,cargo.deposited());
        set(organizer,"actionCooldown",0);call("tickOverflowDepositing");
        assertFalse(organizer.isFailed());assertSame(next,get(organizer,"currentTask"));
        assertEquals(0,organizer.getCompletedTasks());
        assertEquals(2,deferred().size());
        assertEquals(0,get(organizer,"consolidationSourcesInBatch"));
        assertEquals(0,get(organizer,"movedThisVisit"));assertEquals(false,get(organizer,"sourceVisitFailed"));
        assertEquals(0,cargo.acquired());assertEquals(0,cargo.deposited());
        assertEquals(false,call("hasCollectedPackingCargo"));
        assertNotNull(raw.getItemStack(9),"Overflow must leave the reserved empty shell alone");
        assertFalse(events.containsKey("organize_partial_batch_packing"));
    }

    @Test
    void partialPackedDeliveryCreditsOnlyItsFillAndStagesRemainingCargo() throws Exception {
        durable();
        normal().add(new StashOrganizer.MoveTask(
                new int[]{1, 64, 0}, staging, "shulker_box"));
        set(organizer, "totalTasks", 2);
        var cargo = (CargoTransactionLedger) get(organizer, "taskCargo");
        cargo.reset(128);
        openWindow(StashOrganizer.State.SHULKER_STORE_DEPOSIT);
        set(organizer, "shulkerFillMovedUnits", 29);
        set(organizer, "packStoreMatchingShulkersBefore", 1);
        var packedBox = InventoryTransferEvidenceTest.box("obsidian");
        putPlayer(36, packedBox);
        putPlayer(43, InventoryTransferEvidenceTest.item("obsidian", 64));
        putPlayer(44, InventoryTransferEvidenceTest.item("obsidian", 35));
        pendingMove(81);
        window.setItemStack(0, packedBox);
        putPlayer(36, null);

        call("tickShulkerStoreDeposit");
        assertEquals(29, cargo.deposited());
        assertEquals(99, cargo.remaining());

        set(organizer, "actionCooldown", 0);
        call("tickShulkerStoreDeposit");
        assertEquals(StashOrganizer.State.SHULKER_STATION_WALK, organizer.getState());
        assertEquals(99, cargo.remaining());

        set(organizer, "state", StashOrganizer.State.SHULKER_WAIT_PLACE);
        call("startOverflow");
        assertTrue((boolean) get(organizer, "stagingForPackingSupply"));
        openWindow(StashOrganizer.State.OVERFLOW_DEPOSITING);
        set(organizer, "actionSlotIndex", 43);
        call("tickOverflowDepositing");
        pendingMove(88);
        window.setItemStack(0, InventoryTransferEvidenceTest.item("obsidian", 64));
        putPlayer(43, null);
        call("tickOverflowDepositing");
        set(organizer, "actionCooldown", 0);
        call("tickOverflowDepositing");
        pendingMove(89);
        window.setItemStack(1, InventoryTransferEvidenceTest.item("obsidian", 35));
        putPlayer(44, null);
        set(organizer, "actionCooldown", 0);
        call("tickOverflowDepositing");
        set(organizer, "actionCooldown", 0);
        call("tickOverflowDepositing");

        assertFalse(organizer.isFailed(), organizer.getLastFailureReason());
        assertTrue(events.containsKey("organize_packing_supply_staged"),
                organizer.getState() + " cargo=" + cargo.remaining()
                        + " slot=" + get(organizer, "actionSlotIndex")
                        + " pending=" + get(organizer, "pendingQuickMove")
                        + " events=" + events.keySet());
        assertEquals(0, cargo.remaining());
        assertEquals(1, deferred().size());
    }

    @Test
    void legacyFullCreditCheckpointRecountsLooseCargoBeforeStaging() throws Exception {
        durable();
        set(organizer, "stagingForPackingSupply", true);
        var cargo = (CargoTransactionLedger) get(organizer, "taskCargo");
        cargo.restore(128, 128);
        putPlayer(43, InventoryTransferEvidenceTest.item("obsidian", 64));
        putPlayer(44, InventoryTransferEvidenceTest.item("obsidian", 35));

        call("resumeOverflowCheckpoint");

        assertFalse(organizer.isFailed(), organizer.getLastFailureReason());
        assertEquals(StashOrganizer.State.OVERFLOW_WALKING, organizer.getState());
        assertEquals(99, cargo.acquired());
        assertEquals(0, cargo.deposited());
    }

    private void depositPackingCargo(int slot,int remaining) throws Exception {
        set(organizer,"actionSlotIndex",slot);set(organizer,"actionCooldown",0);
        call("tickOverflowDepositing");
        pendingMove(54+slot-9);
        putPlayer(slot,remaining==0?null:InventoryTransferEvidenceTest.item("obsidian",remaining));
        set(organizer,"actionCooldown",0);call("tickOverflowDepositing");
    }

    @Test
    void retryingAPackingFallbackPreservesEarlierReceipts() throws Exception {
        durable();
        set(organizer,"state",StashOrganizer.State.SHULKER_OPENING);
        call("startOverflowAfterShulkerCleanup");
        var ledger=(MixedStagingLedger)get(organizer,"supplyStagingLedger");
        ledger.begin("obsidian",staging);ledger.confirm();
        call("startOverflowAfterShulkerCleanup");
        assertEquals(1,ledger.snapshot().confirmed().size());
        assertEquals(1,journal.load().orElseThrow().checkpoint().packingSupply().stagingLedger().confirmed().size());
    }

    @Test
    void completedLedgerAndStaleCountersCannotStartAnotherPackingCycle() throws Exception {
        var cargo=(CargoTransactionLedger)get(organizer,"taskCargo");
        cargo.reset(128);cargo.recordDeposited(128);
        set(organizer,"consolidationSourcesInBatch",1);set(organizer,"movedThisVisit",2);
        set(organizer,"consolidationMode",true);set(organizer,"mixedBatchConsolidationMode",true);
        set(organizer,"sourceVisitFailed",true);
        var next=StashOrganizer.MoveTask.mixed(new int[]{1,64,0},staging,"gray_shulker_box",
                "mixed",Map.of("obsidian",64,"end_crystal",64));
        normal().add(next);
        assertEquals(false,call("hasCollectedPackingCargo"));
        closeSource();
        assertSame(next,get(organizer,"currentTask"));
        assertFalse(events.containsKey("organize_partial_batch_packing"));
        assertFalse(organizer.isFailed());
    }

    @Test
    void partiallyDepositedCargoStillNeedsPackingDespiteZeroVisitCounters() throws Exception {
        var cargo=(CargoTransactionLedger)get(organizer,"taskCargo");
        cargo.reset(128);cargo.recordDeposited(46);
        set(organizer,"consolidationSourcesInBatch",0);set(organizer,"movedThisVisit",0);
        assertEquals(true,call("hasCollectedPackingCargo"));
        assertEquals(82,cargo.remaining());
    }

    @Test
    void packingFallbackStopsBeforeMovementIfItsCheckpointCannotBeSaved() throws Exception {
        durable();
        putPlayer(44,InventoryTransferEvidenceTest.item("obsidian",64));
        java.nio.file.Files.createDirectory(temp.resolve("checkpoint.json"));
        call("startOverflowAfterShulkerCleanup");
        assertTrue(organizer.isFailed());
        assertEquals("packing_supply_checkpoint_unavailable",organizer.getLastFailureReason());
        assertEquals(64,raw.getItemStack(44).getAmount());
        assertTrue(deferred().isEmpty());
        assertEquals(0,organizer.getCompletedTasks());
    }

    @Test
    void recoveredReceiptsResumeAnOldEmptyOverflowWithoutInventingADelivery() throws Exception {
        durable();
        set(organizer,"currentTask",StashOrganizer.MoveTask.mixedBatch(staging,lane,"obsidian"));
        set(organizer,"consolidationMode",true);set(organizer,"mixedBatchConsolidationMode",true);
        set(organizer,"consolidationSourcesInBatch",1);set(organizer,"sourceVisitFailed",true);
        set(organizer,"stagingForPackingSupply",true);
        set(organizer,"completedTasks",8879);set(organizer,"totalTasks",18938);
        set(organizer,"lastFailure",new OrganizerJournalStore.Failure(
                "packing_supply_staging_unverified","OVERFLOW_DEPOSITING",1));
        var ledger=(MixedStagingLedger)get(organizer,"supplyStagingLedger");
        ledger.restore(new MixedStagingLedger.Snapshot(List.of(
                new MixedStagingLedger.Source("obsidian",90,64,0),
                new MixedStagingLedger.Source("obsidian",92,64,0)),List.of(),true));
        var mixed=StashOrganizer.MoveTask.mixed(new int[]{1,64,0},staging,"gray_shulker_box",
                "mixed",Map.of("obsidian",64,"end_crystal",64));
        normal().add(mixed);
        persistSupply(StashOrganizer.State.OVERFLOW_DEPOSITING);
        restore();call("resumeOverflowCheckpoint");
        assertFalse(organizer.isFailed());
        assertTrue(((StashOrganizer.MoveTask)get(organizer,"currentTask")).mixedDecomposition());
        assertEquals(8879,organizer.getCompletedTasks());
        assertEquals(18939,organizer.getTotalTasks());
        assertEquals(2,deferred().size());
        assertEquals(false,get(organizer,"stagingForPackingSupply"));
        assertEquals(0,get(organizer,"consolidationSourcesInBatch"));
        assertFalse(events.containsKey("organize_packed_shulker_delivered"));
        restore();
        assertEquals(2,deferred().size());
        assertEquals(8879,organizer.getCompletedTasks());
    }

    private void prepareMixedCompletion() throws Exception {
        set(organizer, "currentTask", new StashOrganizer.MoveTask(new int[]{1,64,0}, staging, "shulker_box",
                null, false, true, false, Map.of("obsidian",64)));
        set(organizer, "mixedDecompositionMode", true);
        set(organizer, "mixedBoxDrained", true);
        set(organizer, "state", StashOrganizer.State.SHULKER_PICKUP);
        var ledger = (MixedStagingLedger) get(organizer, "mixedStagingLedger");
        ledger.begin("obsidian", staging); ledger.confirm();
    }

    @Test
    void returnedPartialBatchDefersTheOriginalSourceWithoutClaimingProgress() throws Exception {
        durable();
        var source = StashOrganizer.MoveTask.mixedBatch(staging,lane,"obsidian");
        var sibling = StashOrganizer.MoveTask.mixedBatch(new int[]{92,64,0},lane,"obsidian");
        var unrelated = StashOrganizer.MoveTask.mixedBatch(staging,lane,"end_crystal");
        var mixed = StashOrganizer.MoveTask.mixed(new int[]{1,64,0},staging,"gray_shulker_box",
                "mixed",Map.of("obsidian",64,"end_crystal",64));
        normal().add(mixed);
        consolidation().addAll(List.of(source,sibling,unrelated));
        set(organizer,"completedTasks",100); set(organizer,"totalTasks",104);
        assertTrue(stageSupply(source,staging,0));
        assertEquals(100,organizer.getCompletedTasks());
        assertEquals(104,organizer.getTotalTasks());
        assertEquals(3,consolidation().size());
        assertTrue(deferred().containsAll(List.of(source,sibling)));
        assertEquals(List.of(unrelated),new ArrayList<>((Deque<?>)call("eligibleConsolidationTasks")));
        assertEquals(List.of(mixed),List.copyOf(normal()));
        assertNull(get(organizer,"currentTask"));
        assertEquals("CLOSING_SOURCE",journal.load().orElseThrow().checkpoint().interruptedState());
    }

    @Test
    void legacyIdentityDuplicatesAreMergedAndAllMatchingSourcesAreBlocked() throws Exception {
        durable();
        var original = StashOrganizer.MoveTask.mixedBatch(staging,lane,"obsidian");
        consolidation().add(original);
        for(int i=0;i<80;i++) {
            var duplicate=StashOrganizer.MoveTask.mixedBatch(staging.clone(),lane.clone(),"obsidian");
            consolidation().add(duplicate); deferred().add(duplicate);
        }
        var sibling=StashOrganizer.MoveTask.mixedBatch(new int[]{92,64,0},lane,"obsidian");
        consolidation().add(sibling);
        set(organizer,"currentTask",original);
        set(organizer,"completedTasks",100); set(organizer,"totalTasks",182);
        call("normalizeSupplyDeferredTasks");
        assertEquals(List.of(original,sibling),List.copyOf(consolidation()));
        assertEquals(2,deferred().size());
        assertTrue(((Deque<?>)call("eligibleConsolidationTasks")).isEmpty());
        assertEquals(100,organizer.getCompletedTasks());
        assertEquals(102,organizer.getTotalTasks());
        call("normalizeSupplyDeferredTasks");
        assertEquals(102,organizer.getTotalTasks(),"Repair must be idempotent");
    }

    @Test
    void doubleChestHalvesShareOnePackingObligationButDifferentLanesDoNot() throws Exception {
        for(int x=90;x<=91;x++) index.put(new ContainerEntry(x,64,0,"chest",true,Map.of(),0,List.of(),1,
                null,null,90,64,0,true,"X"));
        var left=StashOrganizer.MoveTask.mixedBatch(staging,lane,"obsidian");
        var right=StashOrganizer.MoveTask.mixedBatch(new int[]{91,64,0},lane.clone(),"obsidian");
        var elsewhere=StashOrganizer.MoveTask.mixedBatch(staging,new int[]{81,64,0},"obsidian");
        consolidation().addAll(List.of(left,right,elsewhere)); deferred().add(right);
        set(organizer,"totalTasks",3);
        call("normalizeSupplyDeferredTasks");
        assertEquals(List.of(left,elsewhere),List.copyOf(consolidation()));
        assertEquals(2,organizer.getTotalTasks());
        assertEquals(2,deferred().size());
    }

    @Test
    void exactVariantsAndOnboardCargoRemainSeparateFromBlockedSourceWork() throws Exception {
        var fortune=StashOrganizer.MoveTask.mixedBatch(staging,lane,"diamond_pickaxe[fortune]");
        var silk=StashOrganizer.MoveTask.mixedBatch(staging,lane,"diamond_pickaxe[silk_touch]");
        var onboard=StashOrganizer.MoveTask.mixedBatchInInventory(staging,lane,"diamond_pickaxe[fortune]");
        consolidation().addAll(List.of(fortune,silk,onboard)); deferred().add(fortune);
        call("normalizeSupplyDeferredTasks");
        assertEquals(List.of(silk,onboard),new ArrayList<>((Deque<?>)call("eligibleConsolidationTasks")));
        assertEquals(3,consolidation().size());
    }

    @Test
    void newlyQueuedSourcesCannotBypassAnAlreadyBlockedItemType() throws Exception {
        putPlayer(9,InventoryTransferEvidenceTest.item("shulker_box",1));
        var original=StashOrganizer.MoveTask.mixedBatch(staging,lane,"obsidian");
        consolidation().add(original);deferred().add(original);
        consolidation().add(StashOrganizer.MoveTask.mixedBatch(new int[]{92,64,0},lane,"obsidian"));
        assertTrue(((Deque<?>)call("eligibleConsolidationTasks")).isEmpty());
        assertTrue(releaseSupply(true));
        assertTrue(deferred().isEmpty());
        assertEquals(2,((Deque<?>)call("eligibleConsolidationTasks")).size());
    }

    @Test
    void splitStagingReplacesCollectedObligationsWithoutAddingCompletedTasks() throws Exception {
        durable();
        var source=StashOrganizer.MoveTask.mixedBatch(new int[]{1,64,0},lane,"obsidian");
        set(organizer,"completedTasks",20);set(organizer,"totalTasks",23);
        set(organizer,"currentTask",source);set(organizer,"consolidationMode",true);
        set(organizer,"consolidationSourcesInBatch",3);
        var ledger=(MixedStagingLedger)get(organizer,"supplyStagingLedger");
        ledger.begin("obsidian",staging);ledger.confirm();
        ledger.begin("obsidian",new int[]{92,64,0});ledger.confirm();
        assertEquals(true,call("finishSupplyStaging"));
        assertEquals(20,organizer.getCompletedTasks());
        assertEquals(22,organizer.getTotalTasks());
        assertEquals(2,consolidation().size());
    }

    @Test
    void legacyCheckpointRepairsTheStillEligibleCurrentSourceBeforeResume() throws Exception {
        durable();
        var original=StashOrganizer.MoveTask.mixedBatch(staging,lane,"obsidian");
        var duplicate=StashOrganizer.MoveTask.mixedBatch(staging.clone(),lane.clone(),"obsidian");
        set(organizer,"currentTask",original);set(organizer,"consolidationMode",true);
        set(organizer,"mixedBatchConsolidationMode",true);
        consolidation().addAll(List.of(original,duplicate)); deferred().add(duplicate);
        set(organizer,"totalTasks",2);
        var mixed=StashOrganizer.MoveTask.mixed(new int[]{1,64,0},staging,"gray_shulker_box",
                "mixed",Map.of("obsidian",64,"end_crystal",64));
        normal().add(mixed);
        persistSupply(StashOrganizer.State.TAKING);
        organizer=new StashOrganizer(config,index,journal);
        assertEquals(StashOrganizer.DurableRestoreResult.RESTORED,organizer.restoreDurableCheckpoint());
        assertEquals(1,consolidation().size());assertEquals(1,deferred().size());
        var resume=StashOrganizer.class.getDeclaredMethod("resumeMoveCheckpoint",StashOrganizer.State.class);
        resume.setAccessible(true);resume.invoke(organizer,StashOrganizer.State.TAKING);
        assertTrue(((StashOrganizer.MoveTask)get(organizer,"currentTask")).mixedDecomposition());
        assertEquals(0,organizer.getCompletedTasks());
        assertEquals(1,deferred().size());
    }

    @Test
    void threeStagingsDeferOnlyThatClassAndContinueIndependentWorkAcrossRestarts() throws Exception {
        durable();
        putPlayer(9,InventoryTransferEvidenceTest.item("shulker_box",1));
        var source=StashOrganizer.MoveTask.mixedBatch(staging,lane,"obsidian");
        consolidation().add(source);set(organizer,"totalTasks",1);
        assertTrue(stageSupply(source,staging,0));
        assertTrue(releaseSupply(true));
        assertTrue(stageSupply(source,staging,0));
        organizer=new StashOrganizer(config,index,journal);
        assertEquals(StashOrganizer.DurableRestoreResult.RESTORED,organizer.restoreDurableCheckpoint());
        assertEquals(2,((Map<?,?>)get(organizer,"supplyStagesWithoutDelivery")).get("obsidian"));
        assertTrue(releaseSupply(true));
        source=consolidation().getFirst();
        organizer.setEventCallback(events::put);
        assertTrue(stageSupply(source,staging,0));
        assertFalse(organizer.isFailed());
        assertEquals("packing_supply_no_delivery_progress",
                events.get("organize_packing_supply_blocked").get("reason"));
        assertEquals(0,organizer.getCompletedTasks());assertEquals(1,organizer.getTotalTasks());
        assertEquals(1,consolidation().size());assertEquals(1,deferred().size());
        assertEquals(3,journal.load().orElseThrow().checkpoint().packingSupply().stagesWithoutDelivery().get("obsidian"));
        putPlayer(9,null);
        var mixed=StashOrganizer.MoveTask.mixed(new int[]{1,64,0},staging,"gray_shulker_box",
                "mixed",Map.of("obsidian",64,"end_crystal",64));
        normal().add(mixed);
        call("advanceToNextTask");
        assertSame(mixed,get(organizer,"currentTask"));
        assertFalse(organizer.isFailed());
        assertEquals(1,deferred().size());
    }

    @Test
    void legacyOrdinaryGroupClaimsTheRecoveredShellBeforeOtherMixedBatchWork() throws Exception {
        durable();
        var crystal=new StashOrganizer.MoveTask(new int[]{2,64,0},lane,"end_crystal");
        var sibling=new StashOrganizer.MoveTask(new int[]{3,64,0},lane,"end_crystal");
        var duplicate=StashOrganizer.MoveTask.mixedBatch(crystal.source().clone(),lane.clone(),"end_crystal");
        var totems=StashOrganizer.MoveTask.mixedBatch(staging,staging,"totem_of_undying");
        consolidation().addAll(List.of(crystal,sibling,duplicate,totems));
        deferred().add(duplicate);
        prepareMixedCompletion();
        set(organizer,"totalTasks",5);
        persistSupply(StashOrganizer.State.SHULKER_PICKUP);
        var before=journal.load().orElseThrow();
        int originalId=before.checkpoint().consolidationQueue().getFirst();
        putPlayer(9,InventoryTransferEvidenceTest.item("shulker_box",1));
        call("finishMixedShulkerDecomposition");

        var selected=(StashOrganizer.MoveTask)get(organizer,"currentTask");
        assertEquals("end_crystal",selected.itemId());
        assertTrue(selected.mixedBatchConsolidation());
        assertArrayEquals(crystal.source(),selected.source());
        assertEquals("end_crystal",events.get("organize_packing_supply_resumed").get("storage_class"));
        assertEquals("end_crystal",events.get("organize_empty_shulker_reused").get("storage_class"));
        assertTrue(consolidation().contains(totems));
        assertEquals(1,consolidation().stream().filter(t->t.itemId().equals("end_crystal")).count());
        assertTrue(deferred().isEmpty());
        var saved=journal.load().orElseThrow();
        assertEquals(originalId,saved.checkpoint().currentTaskId());
        assertTrue(saved.plan().tasks().stream().filter(t->t.id()==originalId).findFirst().orElseThrow().mixedBatchConsolidation());
        assertEquals(2,organizer.getCompletedTasks(),"Only mixed completion and shell reuse are credited");

        restore();
        selected=(StashOrganizer.MoveTask)get(organizer,"currentTask");
        assertEquals("end_crystal",selected.itemId());
        assertTrue(selected.mixedBatchConsolidation());
        assertEquals(1,consolidation().stream().filter(t->t.itemId().equals("end_crystal")).count());
        assertEquals(2,organizer.getCompletedTasks());
    }

    @Test
    void emptyShellCleanupDoesNotWakeAGroupBeforeItCanBeConsumed() throws Exception {
        var waiting=new StashOrganizer.MoveTask(staging,lane,"obsidian");
        consolidation().add(waiting);deferred().add(waiting);
        putPlayer(9,InventoryTransferEvidenceTest.item("shulker_box",1));
        var shell=new StashOrganizer.MoveTask(staging,staging,"shulker_box","@empty",true);
        set(organizer,"currentTask",shell.requireInventoryDeposit());
        assertEquals(false,call("reuseEmptyShulkerForDeferredPacking"));
        assertTrue(deferred().contains(waiting));
        assertFalse(events.containsKey("organize_packing_supply_resumed"));
        set(organizer,"currentTask",shell);
        putPlayer(10,InventoryTransferEvidenceTest.item("shulker_box",1));
        assertEquals(false,call("reuseEmptyShulkerForDeferredPacking"));
        assertTrue(deferred().contains(waiting));
        assertFalse(events.containsKey("organize_packing_supply_resumed"));
    }

    @Test
    void recoveredInventoryCargoGetsItsShellWithoutWakingAnUnrelatedGroup() throws Exception {
        var waiting=new StashOrganizer.MoveTask(staging,lane,"end_crystal");
        consolidation().add(waiting);deferred().add(waiting);
        prepareMixedCompletion();
        putPlayer(9,InventoryTransferEvidenceTest.item("shulker_box",1));
        putPlayer(10,InventoryTransferEvidenceTest.item("obsidian",64));
        var cargo=StashOrganizer.MoveTask.mixedBatchInInventory(staging,lane,"obsidian");
        var finish=StashOrganizer.class.getDeclaredMethod("finishMixedShulkerDecomposition",List.class);
        finish.setAccessible(true);finish.invoke(organizer,List.of(cargo));
        assertEquals("obsidian",((StashOrganizer.MoveTask)get(organizer,"currentTask")).itemId());
        assertTrue(deferred().contains(waiting));
        assertFalse(events.containsKey("organize_packing_supply_resumed"));
        assertFalse(organizer.isFailed());
    }

    @Test
    void exhaustedClassNeedsAnActualUnprotectedShellInsteadOfAnIndexedHint() throws Exception {
        var waiting=new StashOrganizer.MoveTask(staging,lane,"obsidian");
        consolidation().add(waiting);deferred().add(waiting);
        @SuppressWarnings("unchecked") var counts=(Map<String,Integer>)get(organizer,"supplyStagesWithoutDelivery");
        counts.put("obsidian",3);
        addStock(1,Map.of("obsidian",1600));
        assertFalse(releaseSupply(false));
        assertFalse(releaseSupply(true),"A wake-up argument alone is not supply evidence");
        putPlayer(9,InventoryTransferEvidenceTest.item("shulker_box",1));protectedSlots().add(9);
        assertFalse(releaseSupply(true));
        protectedSlots().remove(9);
        assertTrue(releaseSupply(true));
        assertTrue(consolidation().getFirst().mixedBatchConsolidation());
        assertTrue(deferred().isEmpty());
        assertEquals(3,counts.get("obsidian"),"Claiming a shell is not a delivery");
    }

    @Test
    void indexedSupplyStillWakesANonexhaustedOrdinaryGroup() throws Exception {
        var waiting=new StashOrganizer.MoveTask(staging,lane,"obsidian");
        consolidation().add(waiting);deferred().add(waiting);
        addStock(1,Map.of("obsidian",1600));
        assertTrue(releaseSupply(false));
        assertTrue(consolidation().getFirst().mixedBatchConsolidation());
        assertEquals("useful_indexed_stock",events.get("organize_packing_supply_resumed").get("supply"));
    }

    @Test
    void staleLanelessSourceKeepsItsShellForTheNextMatchingSource() throws Exception {
        var first=new StashOrganizer.MoveTask(new int[]{1,64,0},staging,"totem_of_undying");
        var next=new StashOrganizer.MoveTask(new int[]{2,64,0},staging,"totem_of_undying");
        var unrelated=StashOrganizer.MoveTask.mixedBatch(staging,lane,"obsidian");
        consolidation().addAll(List.of(first,next,unrelated));deferred().add(first);
        putPlayer(9,InventoryTransferEvidenceTest.item("shulker_box",1));
        set(organizer,"currentTask",new StashOrganizer.MoveTask(staging,staging,"shulker_box","@empty",true));
        assertEquals(true,call("reuseEmptyShulkerForDeferredPacking"));
        set(organizer,"sourceVisitFailed",true);set(organizer,"movedThisVisit",0);
        set(organizer,"actionCooldown",0);
        closeSource();
        var selected=(StashOrganizer.MoveTask)get(organizer,"currentTask");
        assertEquals("totem_of_undying",selected.itemId());
        assertArrayEquals(next.source(),selected.source());
        assertTrue(consolidation().contains(unrelated));
        assertNotNull(raw.getItemStack(9));
    }

    @Test
    void legacyGlobalAbortCanResumeIndependentMixedWorkWithClassStillBlocked() throws Exception {
        durable();
        var waiting=new StashOrganizer.MoveTask(staging,lane,"end_crystal");
        consolidation().add(waiting);deferred().add(waiting);
        @SuppressWarnings("unchecked") var counts=(Map<String,Integer>)get(organizer,"supplyStagesWithoutDelivery");
        counts.put("end_crystal",3);
        var mixed=StashOrganizer.MoveTask.mixed(new int[]{1,64,0},staging,"gray_shulker_box",
                "mixed",Map.of("obsidian",64,"end_crystal",64));
        normal().add(mixed);
        set(organizer,"currentTask",null);set(organizer,"consolidationMode",true);
        set(organizer,"mixedBatchConsolidationMode",true);
        set(organizer,"lastFailure",new OrganizerJournalStore.Failure(
                "packing_supply_no_delivery_progress","CLOSING_SOURCE",1));
        set(organizer,"completedTasks",8044);set(organizer,"totalTasks",18201);
        persistSupply(StashOrganizer.State.CLOSING_SOURCE);
        restore();
        resume(StashOrganizer.State.CLOSING_SOURCE);
        assertTrue(((StashOrganizer.MoveTask)get(organizer,"currentTask")).mixedDecomposition());
        assertFalse(organizer.isFailed());
        assertEquals(8044,organizer.getCompletedTasks());
        assertEquals(18201,organizer.getTotalTasks());
        assertEquals(1,deferred().size());
    }

    @Test
    void onlyALiveProductiveDeliveryResetsTheRestagingGuard() throws Exception {
        normal().add(new StashOrganizer.MoveTask(new int[]{1,64,0},staging,"shulker_box"));
        normal().add(new StashOrganizer.MoveTask(new int[]{2,64,0},staging,"shulker_box"));
        @SuppressWarnings("unchecked") var counts=(Map<String,Integer>)get(organizer,"supplyStagesWithoutDelivery");
        var finish=StashOrganizer.class.getDeclaredMethod("completePackedShulkerStore",int.class,boolean.class);
        finish.setAccessible(true);
        counts.put("obsidian",2);counts.put("end_crystal",2);
        set(organizer,"shulkerFillMovedUnits",64);
        finish.invoke(organizer,0,false);
        assertEquals(2,counts.get("obsidian"));
        set(organizer,"packItemId","obsidian");set(organizer,"packDestination",lane);
        set(organizer,"shulkerFillMovedUnits",64);
        finish.invoke(organizer,0,true);
        assertFalse(counts.containsKey("obsidian"));
        assertEquals(2,counts.get("end_crystal"));
    }

    @Test
    void resumedPartialPickupKeepsItsStoredSourceWithoutCompletionCredit() throws Exception {
        durable();
        var source=StashOrganizer.MoveTask.mixedBatch(staging,lane,"obsidian");
        set(organizer,"currentTask",source);set(organizer,"consolidationMode",true);
        set(organizer,"movedThisVisit",1);set(organizer,"totalTasks",1);
        putPlayer(10,InventoryTransferEvidenceTest.item("obsidian",64));
        var resume=StashOrganizer.class.getDeclaredMethod("resumeMoveCheckpoint",StashOrganizer.State.class);
        resume.setAccessible(true);resume.invoke(organizer,StashOrganizer.State.TAKING);
        assertTrue(((StashOrganizer.MoveTask)get(organizer,"currentTask")).alreadyInInventory());
        assertEquals(List.of(source),List.copyOf(consolidation()));
        assertEquals(0,get(organizer,"consolidationSourcesInBatch"));
        assertEquals(0,organizer.getCompletedTasks());
        assertEquals(64,raw.getItemStack(10).getAmount());
    }

    @Test
    void stagingCheckpointFailureRetainsBlockedWorkWithoutCompletionCredit() throws Exception {
        durable();
        var source=StashOrganizer.MoveTask.mixedBatch(staging,lane,"obsidian");
        consolidation().add(source);set(organizer,"totalTasks",1);
        java.nio.file.Files.createDirectory(temp.resolve("checkpoint.json"));
        assertFalse(stageSupply(source,staging,0));
        assertEquals("packing_supply_checkpoint_unavailable",organizer.getLastFailureReason());
        assertEquals(0,organizer.getCompletedTasks());
        assertEquals(1,deferred().size());
        assertEquals(1,consolidation().size());
    }

    private boolean stageSupply(StashOrganizer.MoveTask source,int[] destination,int collectedSources) throws Exception {
        set(organizer,"currentTask",source);set(organizer,"consolidationMode",true);
        set(organizer,"mixedBatchConsolidationMode",true);set(organizer,"consolidationSourcesInBatch",collectedSources);
        set(organizer,"state",StashOrganizer.State.OVERFLOW_DEPOSITING);set(organizer,"stagingForPackingSupply",true);
        var ledger=(MixedStagingLedger)get(organizer,"supplyStagingLedger");ledger.reset();
        ledger.begin(source.itemId(),destination);ledger.confirm();
        return (boolean)call("finishSupplyStaging");
    }

    private boolean releaseSupply(boolean shell) throws Exception {
        var method=StashOrganizer.class.getDeclaredMethod("releaseSupplyDeferredGroup",boolean.class);
        method.setAccessible(true);return (boolean)method.invoke(organizer,shell);
    }

    private void persistSupply(StashOrganizer.State state) throws Exception {
        var method=StashOrganizer.class.getDeclaredMethod("persistDurableCheckpoint",StashOrganizer.State.class);
        method.setAccessible(true);assertEquals(true,method.invoke(organizer,state));
    }

    @Test
    void collectionBeforeInventoryUpdateWaitsThenFinishesExactlyOnce() throws Exception {
        prepareShellVerification();
        set(organizer, "temporaryShulkerOutstanding", true);
        set(organizer, "shulkerInventoryCountBeforePlacement", 1);
        Object mixed = get(organizer, "currentTask");
        var queued = new StashOrganizer.MoveTask(staging, lane, "stone");
        normal().add(queued);
        call("tickShulkerPickup");
        assertEquals(StashOrganizer.State.MIXED_SHELL_VERIFY, organizer.getState());
        assertSame(mixed, get(organizer, "currentTask"));
        assertEquals(List.of(queued), List.copyOf(normal()));
        assertEquals(0, organizer.getCompletedTasks());
        assertFalse(events.containsKey("organize_empty_shulker_produced"));
        verifyTicks(12);
        putPlayer(10, InventoryTransferEvidenceTest.item("shulker_box", 1));
        call("tickMixedShellVerification");
        assertEquals(1, organizer.getCompletedTasks());
        assertTrue(events.containsKey("organize_mixed_shell_inventory_verified"));
        assertFalse(events.containsKey("organize_failed"));
        var next = (StashOrganizer.MoveTask) get(organizer, "currentTask");
        assertEquals("@empty", next.shulkerContentFilter());
        assertNotNull(raw.getItemStack(10), "Verification must not consume the shell");
        call("tickMixedShellVerification");
        assertEquals(1, organizer.getCompletedTasks());
    }

    @Test
    void authoritativeEmptyPickupRepairsAStaleInventoryShape() throws Exception {
        prepareShellVerification();
        putPlayer(10, InventoryTransferEvidenceTest.box("obsidian"));
        protectedSlots().add(10);
        set(organizer, "shulkerInventoryCountBeforePlacement", 2);
        set(organizer, "temporaryShulkerPickupStack",
                InventoryTransferEvidenceTest.item("shulker_box", 1));

        call("finishMixedShulkerDecomposition");

        assertEquals(1, organizer.getCompletedTasks());
        assertFalse(organizer.isFailed());
        assertTrue(ItemIdentifier.readShulkerContents(raw.getItemStack(10)).isEmpty());
        assertFalse(protectedSlots().contains(10));
        assertTrue(events.containsKey("organize_mixed_shell_inventory_reconciled"));
    }

    @Test
    void authoritativePickupClaimsUniqueProtectedEmptyAmongSameIdBoxes() throws Exception {
        prepareShellVerification();
        String emptyFingerprint = ShulkerClassification.classify(Map.of()).fingerprint();
        var mixed = ((StashOrganizer.MoveTask) get(organizer, "currentTask"))
                .withShulkerFingerprint(emptyFingerprint)
                .markAlreadyInInventory();
        set(organizer, "currentTask", mixed);
        putPlayer(9, InventoryTransferEvidenceTest.item("shulker_box", 1));
        putPlayer(10, InventoryTransferEvidenceTest.box("obsidian"));
        protectedSlots().add(9);
        set(organizer, "shulkerInventoryCountBeforePlacement", 2);
        set(organizer, "temporaryShulkerPickupStack",
                InventoryTransferEvidenceTest.item("shulker_box", 1));

        call("finishMixedShulkerDecomposition");

        assertEquals(1, organizer.getCompletedTasks());
        assertFalse(organizer.isFailed());
        assertFalse(protectedSlots().contains(9));
        assertTrue(ItemIdentifier.readShulkerContents(raw.getItemStack(9)).isEmpty());
        assertFalse(ItemIdentifier.readShulkerContents(raw.getItemStack(10)).isEmpty());
        assertTrue(events.containsKey("organize_mixed_shell_ownership_restored"));
    }

    @Test
    void authoritativePickupClaimsOnlyOneOfIdenticalProtectedEmptyShells() throws Exception {
        prepareShellVerification();
        String emptyFingerprint = ShulkerClassification.classify(Map.of()).fingerprint();
        var mixed = ((StashOrganizer.MoveTask) get(organizer, "currentTask"))
                .withShulkerFingerprint(emptyFingerprint)
                .markAlreadyInInventory();
        set(organizer, "currentTask", mixed);
        putPlayer(9, InventoryTransferEvidenceTest.item("shulker_box", 1));
        putPlayer(10, InventoryTransferEvidenceTest.item("shulker_box", 1));
        protectedSlots().addAll(List.of(9, 10));
        set(organizer, "shulkerInventoryCountBeforePlacement", 2);
        set(organizer, "temporaryShulkerPickupStack",
                InventoryTransferEvidenceTest.item("shulker_box", 1));

        call("finishMixedShulkerDecomposition");

        assertEquals(1, organizer.getCompletedTasks());
        assertFalse(organizer.isFailed());
        assertEquals(1, protectedSlots().stream()
                .filter(slot -> slot == 9 || slot == 10)
                .count());
        assertEquals(1,
                events.get("organize_mixed_shell_ownership_restored")
                        .get("released_shells"));
    }

    @Test
    void authoritativePickupClaimsOneProtectedEmptyShellOfAnotherColor() throws Exception {
        prepareShellVerification();
        String emptyFingerprint = ShulkerClassification.classify(Map.of()).fingerprint();
        var mixed = ((StashOrganizer.MoveTask) get(organizer, "currentTask"))
                .withShulkerFingerprint(emptyFingerprint)
                .markAlreadyInInventory();
        set(organizer, "currentTask", mixed);
        putPlayer(9, InventoryTransferEvidenceTest.item("cyan_shulker_box", 1));
        putPlayer(10, InventoryTransferEvidenceTest.box("obsidian"));
        putPlayer(11, InventoryTransferEvidenceTest.box("stone"));
        protectedSlots().addAll(List.of(9, 10, 11));
        set(organizer, "shulkerInventoryCountBeforePlacement", 3);
        set(organizer, "temporaryShulkerPickupStack", null);

        call("finishMixedShulkerDecomposition");

        assertEquals(1, organizer.getCompletedTasks());
        assertFalse(organizer.isFailed());
        assertFalse(protectedSlots().contains(9));
        assertTrue(protectedSlots().contains(10));
        var next = (StashOrganizer.MoveTask) get(organizer, "currentTask");
        assertEquals("cyan_shulker_box", next.itemId());
        assertEquals("cyan_shulker_box",
                events.get("organize_mixed_shell_ownership_restored")
                        .get("claimed_item_id"));
    }

    @Test
    void confirmedLostEmptyShellSourcesAReplacementForPreservedCargo() throws Exception {
        prepareShellVerification();
        String emptyFingerprint = ShulkerClassification.classify(Map.of()).fingerprint();
        var mixed = ((StashOrganizer.MoveTask) get(organizer, "currentTask"))
                .withShulkerFingerprint(emptyFingerprint)
                .markAlreadyInInventory();
        set(organizer, "currentTask", mixed);
        putPlayer(9, InventoryTransferEvidenceTest.box("obsidian"));
        putPlayer(10, InventoryTransferEvidenceTest.box("stone"));
        set(organizer, "temporaryShulkerPickupStack", null);
        set(organizer, "shulkerInventoryCountBeforePlacement", 2);
        var cargo = List.of(
                StashOrganizer.MoveTask.mixedBatchInInventory(staging, lane, "light_gray_glazed_terracotta"),
                StashOrganizer.MoveTask.mixedBatchInInventory(staging, lane, "blue_glazed_terracotta"));
        set(organizer, "mixedShellVerificationCargo", cargo);
        set(organizer, "mixedShellVerificationActive", true);
        set(organizer, "mixedShellVerificationTicks",
                StashOrganizer.MIXED_SHELL_VERIFY_TIMEOUT_TICKS - 1);
        set(organizer, "state", StashOrganizer.State.MIXED_SHELL_VERIFY);

        call("tickMixedShellVerification");

        assertEquals(1, organizer.getCompletedTasks());
        assertFalse(organizer.isFailed());
        assertTrue(events.containsKey("organize_empty_shulker_substitution_required"));
        assertFalse(events.containsKey("organize_failed"));
        assertEquals(2,
                events.get("organize_empty_shulker_substitution_required")
                        .get("inventory_cargo_classes"));
        assertEquals("light_gray_glazed_terracotta",
                ((StashOrganizer.MoveTask) get(organizer, "currentTask")).itemId());
        assertFalse(ItemIdentifier.readShulkerContents(raw.getItemStack(9)).isEmpty());
        assertFalse(ItemIdentifier.readShulkerContents(raw.getItemStack(10)).isEmpty());
    }

    @Test
    void restoredFingerprintCannotOverwriteANonemptyServerSnapshot() throws Exception {
        prepareShellVerification();
        putPlayer(10, InventoryTransferEvidenceTest.box("obsidian"));
        protectedSlots().add(10);
        set(organizer, "shulkerInventoryCountBeforePlacement", 2);
        set(organizer, "state", StashOrganizer.State.MIXED_SHELL_VERIFY);
        set(organizer, "mixedShellVerificationActive", true);
        set(organizer, "mixedShellVerificationTicks",
                StashOrganizer.MIXED_SHELL_VERIFY_TIMEOUT_TICKS);

        call("tickMixedShellVerification");

        assertEquals(0, organizer.getCompletedTasks());
        assertTrue(organizer.isFailed());
        assertEquals("mixed_shulker_inventory_sync_timeout", organizer.getLastFailureReason());
        assertFalse(ItemIdentifier.readShulkerContents(raw.getItemStack(10)).isEmpty());
        assertTrue(protectedSlots().contains(10));
        assertFalse(events.containsKey("organize_mixed_shell_inventory_reconciled"));
    }

    @Test
    void missingInventoryUpdateTimesOutWithAnIntactWaitCheckpoint() throws Exception {
        prepareShellVerification();
        call("finishMixedShulkerDecomposition");
        verifyTicks(StashOrganizer.MIXED_SHELL_VERIFY_TIMEOUT_TICKS - 1);
        assertEquals(StashOrganizer.State.MIXED_SHELL_VERIFY, organizer.getState());
        call("tickMixedShellVerification");
        assertEquals("mixed_shulker_inventory_sync_timeout", organizer.getLastFailureReason());
        assertEquals(0, organizer.getCompletedTasks());
        var saved = journal.load().orElseThrow().checkpoint();
        assertEquals("MIXED_SHELL_VERIFY", saved.interruptedState());
        assertEquals(StashOrganizer.MIXED_SHELL_VERIFY_TIMEOUT_TICKS, saved.mixedShellVerification().elapsedTicks());
        assertTrue(saved.mixedShellVerification().collectionConfirmed());
        assertFalse(saved.temporaryShulkerOutstanding());
        assertTrue(saved.mixedBoxDrained());
    }

    @Test
    void unrelatedInventoryShulkerDoesNotCompleteMixedPickup() throws Exception {
        prepareShellVerification();
        set(organizer, "temporaryShulkerPickupConfirmed", false);
        set(organizer, "temporaryShulkerPickupFingerprint", null);
        set(organizer, "temporaryShulkerOutstanding", true);
        set(organizer, "shulkerInventoryCountBeforePlacement", 1);
        set(organizer, "shulkerPlacePos", new int[]{0,64,1});

        call("tickShulkerPickup");

        assertEquals(StashOrganizer.State.SHULKER_PICKUP, organizer.getState());
        assertTrue((boolean) get(organizer, "temporaryShulkerOutstanding"));
        assertEquals(0, organizer.getCompletedTasks());
        assertFalse(events.containsKey("organize_mixed_shell_inventory_wait"));
    }

    @Test
    void missingCollectionEvidenceReturnsForOneBoundedPickupSweep() throws Exception {
        prepareShellVerification();
        set(organizer, "temporaryShulkerPickupConfirmed", false);
        set(organizer, "temporaryShulkerPickupFingerprint", null);
        set(organizer, "shulkerPlacePos", new int[]{0,64,1});
        call("finishMixedShulkerDecomposition");

        verifyTicks(StashOrganizer.MIXED_SHELL_VERIFY_TIMEOUT_TICKS);

        assertEquals(StashOrganizer.State.SHULKER_RECOVERY_WALK, organizer.getState());
        assertTrue((boolean) get(organizer, "temporaryShulkerOutstanding"));
        assertEquals("mixed_shell_pickup_inventory_timeout", get(organizer, "shulkerRecoveryTrigger"));
        assertEquals(0, organizer.getCompletedTasks());
        assertFalse(organizer.isFailed());
        assertTrue(events.containsKey("organize_mixed_shell_pickup_recovery_started"));
    }

    @Test
    void exhaustedPickupSweepContinuesCargoWithExactConfirmedReceipts() throws Exception {
        prepareShellVerification();
        set(organizer, "temporaryShulkerPickupConfirmed", false);
        set(organizer, "temporaryShulkerPickupFingerprint", null);
        set(organizer, "temporaryShulkerOutstanding", true);
        set(organizer, "shulkerPlacePos", new int[]{0,64,1});
        set(organizer, "shulkerRecoveryTrigger", "mixed_shell_pickup_inventory_timeout");

        assertTrue((boolean) call("continueMixedCargoAfterIrrecoverableEmptyShell"));

        assertEquals(1, organizer.getCompletedTasks());
        assertFalse(organizer.isFailed());
        assertTrue(events.containsKey("organize_empty_shulker_substitution_required"));
        assertEquals(1, events.get("organize_empty_shulker_substitution_required")
                .get("confirmed_receipts"));
        assertEquals("obsidian", ((StashOrganizer.MoveTask) get(organizer, "currentTask")).itemId());
        assertEquals(0, ((CargoTransactionLedger) get(organizer, "taskCargo")).remaining());
    }

    @Test
    void exhaustedPickupSweepStopsWhenAnyStagingReceiptIsUncertain() throws Exception {
        prepareShellVerification();
        set(organizer, "shulkerPlacePos", new int[]{0,64,1});
        set(organizer, "shulkerRecoveryTrigger", "mixed_shell_pickup_inventory_timeout");
        var ledger = (MixedStagingLedger) get(organizer, "mixedStagingLedger");
        ledger.reset();
        ledger.begin("obsidian", staging);

        assertFalse((boolean) call("continueMixedCargoAfterIrrecoverableEmptyShell"));
        assertEquals(0, organizer.getCompletedTasks());
        assertNotNull(get(organizer, "currentTask"));
        assertFalse(events.containsKey("organize_empty_shulker_substitution_required"));
    }

    @Test
    void protectedWrongColorAndNonemptyBoxesCannotSatisfyTheWait() throws Exception {
        prepareShellVerification();
        putPlayer(9, InventoryTransferEvidenceTest.item("shulker_box", 1));
        protectedSlots().add(9);
        putPlayer(10, InventoryTransferEvidenceTest.item("gray_shulker_box", 1));
        putPlayer(11, InventoryTransferEvidenceTest.box("obsidian"));
        call("finishMixedShulkerDecomposition");
        verifyTicks(20);
        assertEquals(StashOrganizer.State.MIXED_SHELL_VERIFY, organizer.getState());
        assertEquals(0, organizer.getCompletedTasks());
        putPlayer(12, InventoryTransferEvidenceTest.item("shulker_box", 1));
        call("tickMixedShellVerification");
        assertEquals(1, organizer.getCompletedTasks());
        assertNotNull(raw.getItemStack(9));
        assertNotNull(raw.getItemStack(10));
        assertNotNull(raw.getItemStack(11));
    }

    @Test
    void nonemptyCollectedBoxCannotBeHiddenByAnotherEmptyShell() throws Exception {
        prepareShellVerification();
        set(organizer, "temporaryShulkerPickupFingerprint", ShulkerClassification.classify(
                Map.of("obsidian", 64)).fingerprint());
        putPlayer(10, InventoryTransferEvidenceTest.item("shulker_box", 1));
        call("finishMixedShulkerDecomposition");
        assertEquals("mixed_shulker_recovered_nonempty", organizer.getLastFailureReason());
        assertEquals(0, organizer.getCompletedTasks());
        assertFalse(events.containsKey("organize_empty_shulker_produced"));
    }

    @Test
    void recoveryPickupUsesTheSameInventoryWait() throws Exception {
        prepareShellVerification();
        set(organizer, "state", StashOrganizer.State.SHULKER_RECOVERY_PICKUP);
        set(organizer, "temporaryShulkerOutstanding", true);
        set(organizer, "shulkerPlacePos", new int[]{0,64,1});
        set(organizer, "shulkerTicks", 100);
        call("tickShulkerRecoveryPickup");
        assertEquals(StashOrganizer.State.MIXED_SHELL_VERIFY, organizer.getState());
        putPlayer(10, InventoryTransferEvidenceTest.item("shulker_box", 1));
        call("tickMixedShellVerification");
        assertEquals(1, organizer.getCompletedTasks());
        assertFalse(events.containsKey("organize_failed"));
    }

    @Test
    void waitBudgetAndPendingCargoSurvivePauseAndRestart() throws Exception {
        prepareShellVerification();
        var pending = StashOrganizer.MoveTask.mixedBatchInInventory(staging, lane, "obsidian");
        putPlayer(10, InventoryTransferEvidenceTest.item("obsidian", 64));
        var finish = StashOrganizer.class.getDeclaredMethod("finishMixedShulkerDecomposition", List.class);
        finish.setAccessible(true);
        finish.invoke(organizer, List.of(pending));
        verifyTicks(37);
        assertTrue(organizer.yieldToAutomation("test_interruption"));
        var snapshot = journal.load().orElseThrow().checkpoint().mixedShellVerification();
        assertEquals(37, snapshot.elapsedTicks());
        assertEquals(1, snapshot.cargoTaskIds().size());
        organizer = new StashOrganizer(config, index, journal);
        organizer.setEventCallback(events::put);
        assertEquals(StashOrganizer.DurableRestoreResult.RESTORED, organizer.restoreDurableCheckpoint());
        assertEquals(StashOrganizer.State.MIXED_SHELL_VERIFY, organizer.getYieldedFromState());
        resumeShellVerification();
        assertEquals(38, get(organizer, "mixedShellVerificationTicks"));
        assertTrue((boolean) get(organizer, "temporaryShulkerPickupConfirmed"));
        assertEquals(1, ((List<?>) get(organizer, "mixedShellVerificationCargo")).size());
        putPlayer(11, InventoryTransferEvidenceTest.item("shulker_box", 1));
        call("tickMixedShellVerification");
        assertEquals(1, organizer.getCompletedTasks());
        assertEquals(64, raw.getItemStack(10).getAmount());
        assertTrue(events.containsKey("organize_empty_shulker_reused"));
        assertFalse(events.containsKey("organize_failed"));
    }

    @Test
    void exhaustedWaitDoesNotGetAnotherBudgetAfterRestart() throws Exception {
        prepareShellVerification();
        call("finishMixedShulkerDecomposition");
        verifyTicks(StashOrganizer.MIXED_SHELL_VERIFY_TIMEOUT_TICKS);
        organizer = new StashOrganizer(config, index, journal);
        assertEquals(StashOrganizer.DurableRestoreResult.RESTORED, organizer.restoreDurableCheckpoint());
        resumeShellVerification();
        assertEquals("mixed_shulker_inventory_sync_timeout", organizer.getLastFailureReason());
        assertEquals(StashOrganizer.MIXED_SHELL_VERIFY_TIMEOUT_TICKS, get(organizer, "mixedShellVerificationTicks"));
        assertEquals(0, organizer.getCompletedTasks());
    }

    @Test
    void verifiedInventoryCanResumeAnExhaustedWaitWithoutRescanning() throws Exception {
        prepareShellVerification();
        call("finishMixedShulkerDecomposition");
        verifyTicks(StashOrganizer.MIXED_SHELL_VERIFY_TIMEOUT_TICKS);
        putPlayer(10, InventoryTransferEvidenceTest.item("shulker_box", 1));
        organizer = new StashOrganizer(config, index, journal);
        assertEquals(StashOrganizer.DurableRestoreResult.RESTORED, organizer.restoreDurableCheckpoint());
        resumeShellVerification();
        assertEquals(1, organizer.getCompletedTasks());
        assertFalse(organizer.isFailed());
    }

    @Test
    void failedWaitCheckpointStopsWithoutAdvancingOrMovingCargo() throws Exception {
        prepareShellVerification();
        java.nio.file.Files.createDirectory(temp.resolve("checkpoint.json"));
        call("finishMixedShulkerDecomposition");
        assertEquals("mixed_shulker_inventory_checkpoint_unavailable", organizer.getLastFailureReason());
        assertEquals(0, organizer.getCompletedTasks());
        assertNotNull(get(organizer, "currentTask"));
        assertFalse(events.containsKey("organize_empty_shulker_produced"));
    }

    @Test
    void legacyPickupCheckpointStillFinishesAfterInventoryArrives() throws Exception {
        prepareShellVerification();
        set(organizer, "shulkerPlacePos", new int[]{0,64,1});
        var persist = StashOrganizer.class.getDeclaredMethod("persistDurableCheckpoint", StashOrganizer.State.class);
        persist.setAccessible(true);
        assertTrue((boolean) persist.invoke(organizer, StashOrganizer.State.SHULKER_PICKUP));
        assertNull(journal.load().orElseThrow().checkpoint().mixedShellVerification());
        organizer = new StashOrganizer(config, index, journal);
        assertEquals(StashOrganizer.DurableRestoreResult.RESTORED, organizer.restoreDurableCheckpoint());
        putPlayer(10, InventoryTransferEvidenceTest.item("shulker_box", 1));
        call("resumeTemporaryShulkerAtStation");
        assertEquals(1, organizer.getCompletedTasks());
        assertFalse(organizer.isFailed());
    }

    @Test
    void fullImportDefersPackedBoxAndStartsStagedCapacityRecovery() throws Exception {
        durable();
        putPlayer(9, InventoryTransferEvidenceTest.box("obsidian"));
        var packed = StashOrganizer.MoveTask.mixedBatchInInventory(
                staging, staging, "obsidian");
        var staged = StashOrganizer.MoveTask.mixedBatch(
                staging, staging, "end_crystal");
        set(organizer, "currentTask", packed);
        set(organizer, "packItemId", "obsidian");
        set(organizer, "packDestination", staging.clone());
        set(organizer, "consolidationMode", true);
        set(organizer, "mixedBatchConsolidationMode", true);
        consolidation().add(staged);

        var method = StashOrganizer.class.getDeclaredMethod(
                "deferPackedImportShulkerForLaneRecovery", String.class);
        method.setAccessible(true);
        assertTrue((boolean) method.invoke(organizer, "test_import_full"));

        assertSame(staged, get(organizer, "currentTask"));
        assertEquals(StashOrganizer.State.WALKING, organizer.getState());
        assertEquals(1, normal().size());
        assertEquals("obsidian", normal().getFirst().shulkerContentFilter());
        assertEquals("compact_staged_import_cargo_then_retry",
                events.get("organize_packed_shulker_deferred").get("disposition"));
        assertTrue(events.containsKey("organize_import_capacity_recovery_started"));
        assertFalse(organizer.isFailed());
    }

    @Test
    void overflowExhaustionUsesPackedImportCapacityRecoveryInsteadOfAborting() throws Exception {
        durable();
        for (int slot = 0; slot < 54; slot++) {
            window.setItemStack(slot, InventoryTransferEvidenceTest.item("stone", 64));
        }
        putPlayer(9, InventoryTransferEvidenceTest.box("obsidian"));
        var packed = new StashOrganizer.MoveTask(
                staging, staging, "shulker_box", "obsidian", true);
        var staged = StashOrganizer.MoveTask.mixedBatch(
                staging, staging, "end_crystal");
        set(organizer, "currentTask", packed);
        set(organizer, "state", StashOrganizer.State.OVERFLOW_DEPOSITING);
        set(organizer, "overflowChestPos", staging.clone());
        set(organizer, "actionSlotIndex", 9);
        ((CargoTransactionLedger) get(organizer, "taskCargo")).reset(1);
        consolidation().add(staged);

        call("tickOverflowDepositing");

        assertSame(staged, get(organizer, "currentTask"));
        assertEquals(StashOrganizer.State.WALKING, organizer.getState());
        assertSame(packed, normal().getFirst());
        assertTrue(events.containsKey("organize_packed_shulker_deferred"));
        assertTrue(events.containsKey("organize_import_capacity_recovery_started"));
        assertFalse(organizer.isFailed());
        assertNotNull(raw.getItemStack(9));
    }

    @Test
    @SuppressWarnings("unchecked")
    void loosePackingFallbackStopsAtProbeBudgetAndCompactsImportCargo() throws Exception {
        durable();
        for (int slot = 0; slot < 54; slot++) {
            window.setItemStack(slot, InventoryTransferEvidenceTest.item("stone", 64));
        }
        putPlayer(9, InventoryTransferEvidenceTest.item(
                "light_gray_glazed_terracotta", 64));
        var blocked = StashOrganizer.MoveTask.mixedBatchInInventory(
                staging, staging, "light_gray_glazed_terracotta");
        var recovery = StashOrganizer.MoveTask.mixedBatch(
                staging, lane, "end_crystal");
        set(organizer, "currentTask", blocked);
        set(organizer, "state", StashOrganizer.State.OVERFLOW_DEPOSITING);
        set(organizer, "overflowChestPos", staging.clone());
        set(organizer, "actionSlotIndex", 9);
        set(organizer, "stagingForPackingSupply", true);
        ((CargoTransactionLedger) get(organizer, "taskCargo")).reset(64);
        consolidation().add(recovery);
        Set<Long> tried = (Set<Long>) get(organizer, "overflowTriedDestinations");
        for (long key = 1; key <= 31; key++) tried.add(key);

        call("tickOverflowDepositing");

        assertSame(recovery, get(organizer, "currentTask"));
        assertEquals(StashOrganizer.State.WALKING, organizer.getState());
        assertSame(blocked, normal().getFirst());
        assertFalse((boolean) get(organizer, "stagingForPackingSupply"));
        assertEquals("compact_staged_import_cargo_then_retry",
                events.get("organize_loose_cargo_deferred").get("disposition"));
        assertEquals(32,
                events.get("organize_import_capacity_probe_budget_reached")
                        .get("destinations_tried"));
        assertTrue(events.containsKey("organize_import_capacity_recovery_started"));
        assertFalse(organizer.isFailed());
        assertNotNull(raw.getItemStack(9));
    }

    @Test
    void packedImportSearchUsesABoundedLiveProbeBudgetWhenRecoveryWorkExists() throws Exception {
        var packed = new StashOrganizer.MoveTask(
                staging, staging, "shulker_box", "obsidian", true);
        var staged = StashOrganizer.MoveTask.mixedBatch(
                staging, staging, "end_crystal");
        set(organizer, "currentTask", packed);
        consolidation().add(staged);
        var method = StashOrganizer.class.getDeclaredMethod(
                "shouldStartCurrentPackedImportCapacityRecovery", int.class);
        method.setAccessible(true);

        assertFalse((boolean) method.invoke(organizer, 7));
        assertTrue((boolean) method.invoke(organizer, 8));
        consolidation().clear();
        assertFalse((boolean) method.invoke(organizer, 8),
                "Without safe recovery work, later import candidates must remain eligible");
    }

    @Test
    @SuppressWarnings("unchecked")
    void packedImportProbeBatchContinuesIntoNinthRegisteredInventory() throws Exception {
        List<int[]> imports = new ArrayList<>();
        for (int x = 90; x <= 98; x++) {
            int[] position = {x, 64, 0};
            imports.add(position);
            index.put(new ContainerEntry(x, 64, 0, "chest", true,
                    Map.of(), 0, List.of(), 1));
        }
        var cacheImports = ContainerIndex.class.getDeclaredMethod(
                "cacheImportChests", Collection.class, boolean.class);
        cacheImports.setAccessible(true);
        cacheImports.invoke(index, imports, true);

        set(organizer, "packDestination", imports.get(7).clone());
        set(organizer, "packingLaneExhausted", true);
        set(organizer, "packDestinationOpenFailures", 3);
        Set<Long> tried = (Set<Long>) get(organizer, "packStoreTriedDestinations");
        for (int i = 0; i < 7; i++) tried.add(importInventoryKey(imports.get(i)));

        var retry = StashOrganizer.class.getDeclaredMethod(
                "retryOrSwitchPackedShulkerDestination", String.class, boolean.class);
        retry.setAccessible(true);
        retry.invoke(organizer, "destination_full", true);

        assertEquals(StashOrganizer.State.SHULKER_STORE_WALK, organizer.getState());
        assertArrayEquals(imports.get(8), (int[]) get(organizer, "packDestination"));
        assertEquals(8, tried.size());
        assertEquals("continue_untried_imports",
                events.get("organize_import_capacity_probe_batch_advanced")
                        .get("disposition"));
        assertFalse(organizer.isFailed());
    }

    @Test
    @SuppressWarnings("unchecked")
    void blockedMixedBoxReturnsToItsVacatedSourceAndRollbackSurvivesRestart() throws Exception {
        durable();
        var box = mixedBox();
        var classification = ShulkerClassification.classify(
                ItemIdentifier.readShulkerContents(box));
        int[] source = {1, 64, 0};
        index.put(new ContainerEntry(1, 64, 0, "chest", false,
                Map.of(), 0, List.of(), 1));
        var blocked = StashOrganizer.MoveTask.mixed(
                source, staging, "gray_shulker_box",
                classification.fingerprint(), classification.contents());
        set(organizer, "currentTask", blocked);
        set(organizer, "mixedDecompositionMode", true);
        set(organizer, "state", StashOrganizer.State.OVERFLOW_DEPOSITING);
        set(organizer, "overflowChestPos", staging.clone());
        set(organizer, "actionSlotIndex", 9);
        set(organizer, "totalTasks", 10);
        ((CargoTransactionLedger) get(organizer, "taskCargo")).reset(1);
        putPlayer(9, box);
        for (int slot = 0; slot < 54; slot++) {
            window.setItemStack(slot, InventoryTransferEvidenceTest.item("stone", 64));
        }
        Set<Long> tried = (Set<Long>) get(organizer, "overflowTriedDestinations");
        for (long key = 1; key <= 7; key++) tried.add(key);

        call("tickOverflowDepositing");

        var rollback = (StashOrganizer.MoveTask) get(organizer, "currentTask");
        assertArrayEquals(source, rollback.destination());
        assertArrayEquals(source, rollback.source());
        assertTrue(rollback.alreadyInInventory());
        assertTrue(rollback.mixedDecomposition());
        assertTrue(rollback.mandatoryInventoryDeposit());
        assertEquals(StashOrganizer.State.WALKING, organizer.getState());
        assertEquals(11, organizer.getTotalTasks());
        assertEquals(1, normal().size());
        assertEquals(blocked.shulkerContentFilter(), normal().getLast().shulkerContentFilter());
        assertTrue(normal().getLast().mandatoryInventoryDeposit());
        assertEquals("return_to_source_then_retry_tail_once",
                events.get("organize_mixed_shulker_rollback_started").get("disposition"));
        assertNotNull(raw.getItemStack(9));

        organizer = new StashOrganizer(config, index, journal);
        organizer.setEventCallback(events::put);
        assertEquals(StashOrganizer.DurableRestoreResult.RESTORED,
                organizer.restoreDurableCheckpoint());
        resume(StashOrganizer.State.WALKING);
        var restoredRollback = (StashOrganizer.MoveTask) get(organizer, "currentTask");
        assertArrayEquals(source, restoredRollback.destination());
        assertEquals(StashOrganizer.State.WALKING, organizer.getState());
        assertFalse(organizer.isFailed());
    }

    @Test
    void inventoryOriginMixedBoxCompactsImportCargoInsteadOfWalkingToItsOldPosition() throws Exception {
        var box = mixedBox();
        var classification = ShulkerClassification.classify(ItemIdentifier.readShulkerContents(box));
        int[] oldStandingPosition = {1, 64, 0};
        set(organizer, "currentTask", StashOrganizer.MoveTask.mixedInInventory(
                oldStandingPosition, staging, "gray_shulker_box",
                classification.fingerprint(), classification.contents()));
        set(organizer, "mixedDecompositionMode", true);
        set(organizer, "state", StashOrganizer.State.OVERFLOW_DEPOSITING);
        set(organizer, "overflowChestPos", staging.clone());
        set(organizer, "actionSlotIndex", 9);
        ((CargoTransactionLedger) get(organizer, "taskCargo")).reset(1);
        putPlayer(9, box);
        consolidation().add(StashOrganizer.MoveTask.mixedBatch(staging, lane, "obsidian"));
        for (int slot = 0; slot < 54; slot++) {
            window.setItemStack(slot, InventoryTransferEvidenceTest.item("stone", 64));
        }
        Set<Long> tried = (Set<Long>) get(organizer, "overflowTriedDestinations");
        for (long key = 1; key <= 7; key++) tried.add(key);

        call("tickOverflowDepositing");

        assertArrayEquals(staging, ((StashOrganizer.MoveTask) get(organizer, "currentTask")).source());
        assertEquals(StashOrganizer.State.WALKING, organizer.getState());
        assertEquals(1, normal().size());
        assertTrue(normal().getFirst().alreadyInInventory());
        assertFalse(events.containsKey("organize_mixed_shulker_rollback_started"));
        assertEquals("compact_staged_import_cargo_then_retry",
                events.get("organize_mixed_shulker_deferred").get("disposition"));
        assertNotNull(raw.getItemStack(9));
    }

    @Test
    void oldInventoryOriginRollbackCheckpointRepairsToMixedDecomposition() throws Exception {
        durable();
        var box = mixedBox();
        var classification = ShulkerClassification.classify(ItemIdentifier.readShulkerContents(box));
        int[] oldStandingPosition = {1, 64, 0};
        var deferred = StashOrganizer.MoveTask.mixedInInventory(
                oldStandingPosition, staging, "gray_shulker_box",
                classification.fingerprint(), classification.contents()).requireInventoryDeposit();
        index.put(new ContainerEntry(1, 64, 0, "chest", false,
                Map.of(), 0, List.of(), 1));
        var rollback = new StashOrganizer.MoveTask(
                oldStandingPosition, oldStandingPosition, "gray_shulker_box",
                classification.fingerprint(), true, true, false,
                classification.contents(), true);
        normal().add(deferred);
        set(organizer, "currentTask", rollback);
        set(organizer, "totalTasks", 2);
        putPlayer(9, box);

        resume(StashOrganizer.State.OPENING);

        assertEquals(StashOrganizer.State.SHULKER_STATION_WALK, organizer.getState());
        assertArrayEquals(staging,
                ((StashOrganizer.MoveTask) get(organizer, "currentTask")).destination());
        assertEquals(1, organizer.getTotalTasks());
        assertTrue(normal().isEmpty());
        assertEquals("resume_mixed_decomposition_from_inventory",
                events.get("organize_mixed_rollback_repaired").get("disposition"));
    }

    @Test
    void recoveredImportSlotIsReservedForDeferredPackedHandoff() throws Exception {
        durable();
        var recovery = StashOrganizer.MoveTask.mixedBatch(
                staging, staging, "end_crystal");
        var handoff = new StashOrganizer.MoveTask(
                staging, staging, "shulker_box", "obsidian", true);
        putPlayer(9, InventoryTransferEvidenceTest.box("obsidian"));
        set(organizer, "currentTask", recovery);
        set(organizer, "packItemId", "end_crystal");
        set(organizer, "packDestination", staging.clone());
        set(organizer, "consolidationMode", true);
        set(organizer, "mixedBatchConsolidationMode", true);
        set(organizer, "consolidationSourcesInBatch", 1);
        set(organizer, "shulkerFillMovedUnits", 128);
        set(organizer, "totalTasks", 2);
        normal().addFirst(handoff);

        var method = StashOrganizer.class.getDeclaredMethod(
                "completePackedShulkerStore", int.class, boolean.class);
        method.setAccessible(true);
        method.invoke(organizer, 0, true);

        assertSame(handoff, get(organizer, "currentTask"));
        assertEquals(StashOrganizer.State.WALKING, organizer.getState());
        assertEquals("retry_deferred_import_handoff",
                events.get("organize_import_capacity_recovered").get("disposition"));
        assertFalse((boolean) get(organizer, "consolidationMode"));
        assertFalse((boolean) get(organizer, "mixedBatchConsolidationMode"));
        assertFalse(organizer.isFailed());
    }

    @Test
    void staleEmptyPickupCheckpointResumesItsPackedLaneHandoff() throws Exception {
        durable();
        set(organizer, "currentTask", StashOrganizer.MoveTask.mixedBatchInInventory(
                staging, lane, "enchanted_golden_apple"));
        set(organizer, "packItemId", "enchanted_golden_apple");
        set(organizer, "packDestination", lane.clone());
        set(organizer, "shulkerFillMovedUnits", 350);
        set(organizer, "shulkerInventoryCountBeforePlacement", 2);
        set(organizer, "temporaryShulkerPickupConfirmed", true);
        set(organizer, "temporaryShulkerPickupFingerprint",
                ShulkerClassification.classify(Map.of()).fingerprint());
        set(organizer, "temporaryShulkerPickupStorageKey", null);
        set(organizer, "temporaryShulkerPickupItemId", "red_shulker_box");
        set(organizer, "stagingForPackingSupply", true);
        ((CargoTransactionLedger) get(organizer, "taskCargo")).restore(286, 0);
        putPlayer(9, InventoryTransferEvidenceTest.item("red_shulker_box", 1));
        putPlayer(10, InventoryTransferEvidenceTest.item("gray_shulker_box", 1));
        persist(StashOrganizer.State.OVERFLOW_DEPOSITING);

        restore();
        call("resumeOverflowCheckpoint");

        assertEquals(StashOrganizer.State.SHULKER_STORE_WALK, organizer.getState());
        assertArrayEquals(lane, (int[]) get(organizer, "walkTarget"));
        assertEquals("resume_packed_shulker_handoff",
                events.get("organize_recovery_completed").get("disposition"));
        assertEquals(286, ((CargoTransactionLedger) get(organizer, "taskCargo")).remaining());
        assertFalse(organizer.isFailed());
    }

    @Test
    void uniqueCollectedBaseItemIdentifiesStalePackedBoxInDestinationWindow() throws Exception {
        set(organizer, "currentTask", StashOrganizer.MoveTask.mixedBatchInInventory(
                staging, lane, "enchanted_golden_apple"));
        set(organizer, "packItemId", "enchanted_golden_apple");
        set(organizer, "packDestination", lane.clone());
        set(organizer, "shulkerFillMovedUnits", 350);
        set(organizer, "shulkerInventoryCountBeforePlacement", 2);
        set(organizer, "temporaryShulkerPickupConfirmed", true);
        set(organizer, "temporaryShulkerPickupFingerprint",
                ShulkerClassification.classify(Map.of()).fingerprint());
        set(organizer, "temporaryShulkerPickupItemId", "red_shulker_box");
        ((CargoTransactionLedger) get(organizer, "taskCargo")).restore(286, 0);
        putPlayer(9, InventoryTransferEvidenceTest.item("red_shulker_box", 1));
        putPlayer(10, InventoryTransferEvidenceTest.item("gray_shulker_box", 1));

        var slot = StashOrganizer.class.getDeclaredMethod(
                "packedShulkerTransactionSlot", Container.class, int.class, int.class, boolean.class);
        slot.setAccessible(true);
        assertEquals(9, slot.invoke(organizer, raw, -1, 0, false));
    }

    @Test
    void reusedDeferredBoxCompletesBothCapacityRecoveryObligations() throws Exception {
        durable();
        var recovery = StashOrganizer.MoveTask.mixedBatch(
                staging, staging, "end_crystal");
        var coalesced = new StashOrganizer.MoveTask(
                staging, staging, "shulker_box", "end_crystal", true);
        var next = new StashOrganizer.MoveTask(
                new int[]{1, 64, 0}, lane, "obsidian");
        set(organizer, "currentTask", recovery);
        set(organizer, "packItemId", "end_crystal");
        set(organizer, "packDestination", staging.clone());
        set(organizer, "consolidationMode", true);
        set(organizer, "mixedBatchConsolidationMode", true);
        set(organizer, "consolidationSourcesInBatch", 1);
        set(organizer, "shulkerFillMovedUnits", 192);
        set(organizer, "totalTasks", 3);
        normal().addLast(coalesced);
        normal().addLast(next);

        var method = StashOrganizer.class.getDeclaredMethod(
                "completePackedShulkerStore", int.class, boolean.class, int.class);
        method.setAccessible(true);
        method.invoke(organizer, 0, true, 0);

        assertSame(next, get(organizer, "currentTask"));
        assertFalse(normal().contains(coalesced));
        assertEquals(2, organizer.getCompletedTasks());
        assertEquals("deferred_handoff_completed_by_live_delivery",
                events.get("organize_packed_shulker_coalesced").get("disposition"));
        assertFalse(organizer.isFailed());
    }

    @Test
    void inventoryCargoIdentityDoesNotChangeWithImportFailover() throws Exception {
        var first = new StashOrganizer.MoveTask(
                staging, staging, "purple_glazed_terracotta", null, true);
        var second = new StashOrganizer.MoveTask(
                staging, new int[]{91, 64, 0}, "purple_glazed_terracotta", null, true);
        var method = StashOrganizer.class.getDeclaredMethod(
                "inventoryTaskKey", StashOrganizer.MoveTask.class);
        method.setAccessible(true);
        assertEquals(method.invoke(organizer, first), method.invoke(organizer, second));
    }

    @Test
    void promotedInventoryBatchKeepsItsPackingContract() throws Exception {
        putPlayer(10, InventoryTransferEvidenceTest.item("obsidian", 64));
        var packing = StashOrganizer.MoveTask.mixedBatchInInventory(
                staging, lane, "obsidian");
        set(organizer, "currentTask", null);
        set(organizer, "consolidationMode", false);
        set(organizer, "mixedBatchConsolidationMode", false);
        normal().add(packing);

        call("advanceToNextTask");

        assertSame(packing, get(organizer, "currentTask"));
        assertEquals(StashOrganizer.State.SHULKER_STATION_WALK, organizer.getState());
        assertEquals("obsidian", get(organizer, "packItemId"));
        assertTrue((boolean) get(organizer, "consolidationMode"));
        assertTrue((boolean) get(organizer, "mixedBatchConsolidationMode"));
    }

    @Test
    void restoredDestinationDuplicateMigratesBackToPacking() throws Exception {
        putPlayer(10, InventoryTransferEvidenceTest.item(
                "purple_glazed_terracotta", 64));
        var duplicate = new StashOrganizer.MoveTask(
                staging, staging, "purple_glazed_terracotta", null, true);
        var packing = StashOrganizer.MoveTask.mixedBatchInInventory(
                staging, staging, "purple_glazed_terracotta");
        set(organizer, "currentTask", duplicate);
        set(organizer, "consolidationMode", false);
        set(organizer, "mixedBatchConsolidationMode", false);
        set(organizer, "completedTasks", 100);
        set(organizer, "totalTasks", 200);
        consolidation().add(packing);

        var method = StashOrganizer.class.getDeclaredMethod(
                "resumeMoveCheckpoint", StashOrganizer.State.class);
        method.setAccessible(true);
        method.invoke(organizer, StashOrganizer.State.DEPOSITING);

        assertSame(packing, get(organizer, "currentTask"));
        assertEquals(StashOrganizer.State.SHULKER_STATION_WALK, organizer.getState());
        assertEquals(199, organizer.getTotalTasks());
        assertEquals("resume_existing_packing_obligation",
                events.get("organize_inventory_task_repaired").get("disposition"));
        assertTrue(consolidation().isEmpty());
        assertFalse(organizer.isFailed());
    }

    private void prepareShellVerification() throws Exception {
        durable();
        prepareMixedCompletion();
        set(organizer, "temporaryShulkerPickupConfirmed", true);
        set(organizer, "temporaryShulkerPickupFingerprint", ShulkerClassification.classify(Map.of()).fingerprint());
        // Another color must not satisfy verification or trigger the single-shell reuse shortcut.
        putPlayer(44, InventoryTransferEvidenceTest.item("gray_shulker_box", 1));
        set(organizer, "packItemId", null);
        set(organizer, "totalTasks", 10);
    }

    private void prepareStalePackedTransaction() throws Exception {
        set(organizer, "currentTask", StashOrganizer.MoveTask.mixedBatchInInventory(
                staging, lane, "obsidian"));
        set(organizer, "packItemId", "obsidian");
        set(organizer, "packDestination", lane.clone());
        set(organizer, "mixedDecompositionMode", false);
        set(organizer, "shulkerFillMovedUnits", 64);
        set(organizer, "temporaryShulkerPickupConfirmed", true);
        set(organizer, "temporaryShulkerPickupFingerprint",
                ShulkerClassification.classify(Map.of()).fingerprint());
        set(organizer, "temporaryShulkerPickupStorageKey", null);
        ((CargoTransactionLedger) get(organizer, "taskCargo")).reset(64);
    }

    private void verifyTicks(int count) throws Exception {
        for (int tick = 0; tick < count; tick++) call("tickMixedShellVerification");
    }

    private void resumeShellVerification() throws Exception {
        var method = StashOrganizer.class.getDeclaredMethod("resumeInterruptedCheckpoint", StashOrganizer.State.class);
        method.setAccessible(true);
        method.invoke(organizer, StashOrganizer.State.MIXED_SHELL_VERIFY);
    }

    private void durable() throws Exception { set(organizer, "journalJobId", "packing-supply-test"); set(organizer, "journalPlanDirty", true); }

    private void putPlayer(int slot, ItemStack stack) {
        raw.setItemStack(slot, stack);
        window.setItemStack(54 + slot - 9, stack);
    }

    @SuppressWarnings("unchecked")
    private void addStock(int x, Map<String,Integer> contents) throws Exception {
        var entry = new ContainerEntry(x,64,0,"chest",false,Map.of("shulker_box",1),1,
                List.of(new ContainerEntry.ShulkerDetail(0,"white",contents)),1);
        index.put(entry);
        ((Set<Long>) get(organizer,"managedSourceContainerKeys")).add(entry.posKey());
    }

    private void pendingMove(int slot) throws Exception {
        pendingMove(slot,true);
    }

    private void pendingMove(int slot, boolean accepted) throws Exception {
        var type = Class.forName(StashOrganizer.class.getName()+"$PendingQuickMove");
        var ctor = type.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        set(organizer,"pendingQuickMove",ctor.newInstance(73,slot,window.getItemStack(slot),
                InventoryTransferEvidence.capture(window,54,slot),RequestFuture.immediateFuture(accepted)));
    }

    @SuppressWarnings("unchecked") private Set<StashOrganizer.MoveTask> deferred() throws Exception { return (Set<StashOrganizer.MoveTask>) get(organizer,"supplyDeferredTasks"); }
    @SuppressWarnings("unchecked") private Deque<StashOrganizer.MoveTask> consolidation() throws Exception { return (Deque<StashOrganizer.MoveTask>) get(organizer,"consolidationQueue"); }
    @SuppressWarnings("unchecked") private Deque<StashOrganizer.MoveTask> normal() throws Exception { return (Deque<StashOrganizer.MoveTask>) get(organizer,"taskQueue"); }
    @SuppressWarnings("unchecked") private Set<Integer> protectedSlots() throws Exception { return (Set<Integer>) get(organizer,"protectedInventorySlots"); }
    private long importInventoryKey(int[] position) throws Exception {
        var method = StashOrganizer.class.getDeclaredMethod("importInventoryKey", int[].class);
        method.setAccessible(true);
        return (long) method.invoke(organizer, (Object) position);
    }
    private Object call(String name) throws Exception { var m=StashOrganizer.class.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(organizer); }
    private static Object get(Object object,String name) throws Exception { var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object); }
    private static void set(Object object,String name,Object value) throws Exception { var f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value); }
}
