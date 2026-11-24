package com.zenith.plugin.stashmanager.organizer;

import com.zenith.cache.data.chunk.Chunk;
import com.zenith.cache.data.inventory.Container;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.PalettedWorldState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;

import static com.zenith.Globals.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.zenith.plugin.stashmanager.organizer.StationWalkRecovery.*;

class StationWalkRuntimeTest {
    @TempDir Path temp;
    private StashOrganizer organizer;
    private StashManagerConfig config;
    private OrganizerJournalStore journal;
    private Chunk chunk, oldChunk, stationChunk, oldStationChunk;
    private Container inventory, oldInventory;
    private double oldX, oldY, oldZ;
    private int oldOpen;
    private boolean oldAllowBreak, oldAllowPlace, oldParkourPlace;
    private final int[] station = {38,64,8};
    private final Map<String,Map<String,Object>> events = new LinkedHashMap<>();

    @BeforeEach
    void setup() throws Exception {
        oldX=CACHE.getPlayerCache().getX(); oldY=CACHE.getPlayerCache().getY(); oldZ=CACHE.getPlayerCache().getZ();
        position(8.5,64,8.5);
        oldAllowBreak=CONFIG.client.extra.pathfinder.allowBreak;
        oldAllowPlace=CONFIG.client.extra.pathfinder.allowPlace;
        oldParkourPlace=CONFIG.client.extra.pathfinder.allowParkourPlace;
        CONFIG.client.extra.pathfinder.allowBreak=true;
        CONFIG.client.extra.pathfinder.allowPlace=true;
        CONFIG.client.extra.pathfinder.allowParkourPlace=true;
        var paletteConstructor=PalettedWorldState.class.getDeclaredConstructors()[0];
        Object[] paletteArguments=paletteConstructor.getParameterCount()==5
                ? new Object[]{24,65536,0,64,0}
                : new Object[]{24,65536,0,64,0,new it.unimi.dsi.fastutil.ints.IntOpenHashSet()};
        var palettes=(PalettedWorldState)paletteConstructor.newInstance(paletteArguments);
        var sections=new ChunkSection[24];
        Arrays.setAll(sections,i -> {
            var section=new ChunkSection(palettes);
            section.setBlock(0,0,0,0);
            return section;
        });
        chunk=new Chunk(0,0,sections,20,-4,new ArrayList<>(),null);
        oldChunk=CACHE.getChunkCache().getCache().put(0L,chunk);
        var stationSections = new ChunkSection[24];
        Arrays.setAll(stationSections, i -> {
            var section = new ChunkSection(palettes);
            section.setBlock(0,0,0,0);
            return section;
        });
        stationChunk = new Chunk(2,0,stationSections,20,-4,new ArrayList<>(),null);
        oldStationChunk = CACHE.getChunkCache().getCache().put(2L,stationChunk);
        for (int x=0;x<16;x++) for (int z=0;z<16;z++) block(x,63,z,BlockRegistry.GLASS.minStateId());
        for (int x=32;x<48;x++) for (int z=0;z<16;z++) block(x,63,z,BlockRegistry.GLASS.minStateId());
        var cache=CACHE.getPlayerCache().getInventoryCache();
        oldOpen=cache.getOpenContainerId(); set(cache,"openContainerId",0);
        inventory=new Container(0,46); oldInventory=cache.getContainers().put(0,inventory);
        for (int slot=9;slot<=13;slot++) inventory.setItemStack(slot,InventoryTransferEvidenceTest.item("end_crystal",64));
        inventory.setItemStack(14,InventoryTransferEvidenceTest.item("diamond_sword",1));
        config=new StashManagerConfig(); config.pos1=new int[]{0,0,0}; config.pos2=new int[]{100,100,100};
        journal=new OrganizerJournalStore(temp.resolve("plan.json"),temp.resolve("checkpoint.json"));
        organizer=new StashOrganizer(config,new ContainerIndex(),journal);
        organizer.setEventCallback(events::put);
        set(organizer,"journalJobId","walking-recovery-test"); set(organizer,"journalPlanDirty",true);
        set(organizer,"state",StashOrganizer.State.SHULKER_STATION_WALK);
        set(organizer,"walkTarget",station); set(organizer,"reconciliationStation",station);
        set(organizer,"reconciliationWorksite",new int[]{38,64,9});
        set(organizer,"packItemId","end_crystal"); set(organizer,"packDestination",new int[]{70,64,8});
        set(organizer,"consolidationMode",true); set(organizer,"mixedBatchConsolidationMode",true);
        set(organizer,"currentTask",StashOrganizer.MoveTask.mixedBatch(new int[]{8,64,7},new int[]{70,64,8},"end_crystal"));
        set(organizer,"totalTasks",2); ledger().reset(320);
        queue().add(new StashOrganizer.MoveTask(new int[]{9,64,7},new int[]{70,64,8},"gray_shulker_box"));
        protectedSlots().add(14);
        call("saveAndDisableBaritoneBreaking");
    }

    @AfterEach
    void cleanup() throws Exception {
        call("stopOwnedBaritoneProcess"); call("clearOwnedAutomation"); call("restoreBaritoneBreaking");
        CONFIG.client.extra.pathfinder.allowBreak=oldAllowBreak;
        CONFIG.client.extra.pathfinder.allowPlace=oldAllowPlace;
        CONFIG.client.extra.pathfinder.allowParkourPlace=oldParkourPlace;
        position(oldX,oldY,oldZ);
        if(oldChunk==null) CACHE.getChunkCache().getCache().remove(0L); else CACHE.getChunkCache().getCache().put(0L,oldChunk);
        if(oldStationChunk==null) CACHE.getChunkCache().getCache().remove(2L); else CACHE.getChunkCache().getCache().put(2L,oldStationChunk);
        var cache=CACHE.getPlayerCache().getInventoryCache(); set(cache,"openContainerId",oldOpen);
        if(oldInventory==null) cache.getContainers().remove(0); else cache.getContainers().put(0,oldInventory);
    }

    @Test
    void observedStallDetoursThenReturnsToStationWithoutAdvancingCargoTask() throws Exception {
        Object task=get(organizer,"currentTask"); var queued=List.copyOf(queue());
        ticks(STALL_TICKS);
        assertEquals(1,recovery().attempts());
        assertEquals(new Point(8,64,10),recovery().waypoint());
        assertArrayEquals(station,(int[])get(organizer,"walkTarget"));
        assertEquals(StashOrganizer.State.SHULKER_STATION_WALK,organizer.getState());
        assertFalse(CONFIG.client.extra.pathfinder.allowPlace);
        assertFalse(CONFIG.client.extra.pathfinder.allowBreak);
        assertFalse(CONFIG.client.extra.pathfinder.allowParkourPlace);
        assertEquals(recovery().snapshot(),journal.load().orElseThrow().checkpoint().stationWalkRecovery());
        position(8.5,64,10.5); ticks(1);
        assertNull(recovery().waypoint());
        assertEquals(new Point(38,64,8),get(organizer,"stationPathTarget"));
        assertEquals(0,organizer.getCompletedTasks());
        assertSame(task,get(organizer,"currentTask")); assertEquals(queued,List.copyOf(queue()));
        assertEquals(320,ledger().remaining());
        position(38.5,64,8.5); ticks(1);
        assertEquals(StashOrganizer.State.SHULKER_SELECTING,organizer.getState());
        assertTrue(events.containsKey("organize_station_walk_recovered"));
        assertNull(recovery().snapshot());
        assertTrue(CONFIG.client.extra.pathfinder.allowPlace);
        assertTrue(CONFIG.client.extra.pathfinder.allowParkourPlace);
        assertEquals(320,inventoryUnits()); assertNotNull(inventory.getItemStack(14));
    }

    @Test
    void restoredWorldBlockPreventsCompletionEvenWithAnEmptyShellInInventory() throws Exception {
        int[] worksite = {8,64,9};
        block(8,64,9,BlockRegistry.GRAY_SHULKER_BOX.minStateId());
        inventory.setItemStack(15,InventoryTransferEvidenceTest.item("gray_shulker_box",1));
        set(organizer,"currentTask",StashOrganizer.MoveTask.mixed(new int[]{8,64,7},new int[]{70,64,8},
                "gray_shulker_box","mixed-fingerprint",Map.of("obsidian",64,"end_crystal",64)));
        set(organizer,"mixedDecompositionMode",true);
        set(organizer,"mixedBoxDrained",true);
        set(organizer,"temporaryShulkerOutstanding",false);
        set(organizer,"shulkerPlacePos",worksite);
        set(organizer,"state",StashOrganizer.State.MIXED_SHELL_VERIFY);
        set(organizer,"mixedShellVerificationActive",true);
        call("tickMixedShellVerification");
        assertEquals("mixed_shulker_block_still_present",organizer.getLastFailureReason());
        assertEquals(0,organizer.getCompletedTasks());
        assertTrue(journal.load().orElseThrow().checkpoint().temporaryShulkerOutstanding());
        assertNotNull(inventory.getItemStack(15));
        assertEquals(64,inventory.getItemStack(9).getAmount());
        assertNotNull(inventory.getItemStack(14));
    }

    @Test
    void threeFailedDetoursStopAndPreserveCargoAndQueues() throws Exception {
        var queued=List.copyOf(queue());
        ticks(STALL_TICKS * (MAX_DETOURS + 1));
        assertEquals("reconciliation_station_unreachable_with_cargo",organizer.getLastFailureReason());
        assertEquals(3,recovery().attempts());
        assertEquals(0,organizer.getCompletedTasks());
        assertEquals(320,inventoryUnits()); assertEquals(queued,List.copyOf(queue()));
        assertEquals("walk_recovery_budget_exhausted",events.get("organize_station_walk_recovery_exhausted").get("reason"));
        var saved=journal.load().orElseThrow().checkpoint();
        assertEquals(3,saved.stationWalkRecovery().attempts());
        assertEquals("SHULKER_STATION_WALK",saved.interruptedState());
        assertFalse(saved.temporaryShulkerOutstanding());
        assertTrue(CONFIG.client.extra.pathfinder.allowPlace);
        assertTrue(CONFIG.client.extra.pathfinder.allowBreak);
    }

    @Test
    void unloadedOrObstructedDetoursStopWithoutMiningOrBridging() throws Exception {
        CACHE.getChunkCache().getCache().remove(0L);
        ticks(STALL_TICKS);
        assertEquals("no_safe_local_detour",events.get("organize_station_walk_recovery_exhausted").get("reason"));
        assertTrue(organizer.isFailed()); assertEquals(320,inventoryUnits());
        assertEquals(0,recovery().attempts());
    }

    @Test
    void liveWorldFilterRejectsChestFootingBlockedHeadroomAndMissingFloor() throws Exception {
        var cell=new Point(8,64,10);
        assertTrue(safe(cell));
        block(8,65,10,BlockRegistry.GLASS.minStateId()); assertFalse(safe(cell));
        block(8,65,10,0); block(8,63,10,0); assertFalse(safe(cell));
        block(8,63,10,BlockRegistry.CHEST.minStateId()); assertFalse(safe(cell));
        block(8,63,10,BlockRegistry.OBSIDIAN.minStateId()); assertTrue(safe(cell));
        chunk.getSections()[chunk.getSectionIndex(64)]=null;
        assertFalse(safe(cell),"A loaded chunk with a missing body section is not known-safe air");
    }

    @Test
    void restartRetainsBudgetAndRevalidatesTheDetour() throws Exception {
        ticks(STALL_TICKS);
        var saved=journal.load().orElseThrow().checkpoint().stationWalkRecovery();
        call("stopOwnedBaritoneProcess"); call("clearOwnedAutomation"); call("restoreBaritoneBreaking");
        organizer=new StashOrganizer(config,new ContainerIndex(),journal);
        organizer.setEventCallback(events::put);
        assertEquals(StashOrganizer.DurableRestoreResult.RESTORED,organizer.restoreDurableCheckpoint());
        assertEquals(saved,recovery().snapshot());
        call("resumePackingSelection");
        assertEquals(320,ledger().remaining());
        block(8,64,9,BlockRegistry.GLASS.minStateId()); // The first corridor changed while offline.
        ticks(1);
        assertEquals(2,recovery().attempts());
        assertEquals(new Point(8,64,6),recovery().waypoint());
        assertEquals(0,organizer.getCompletedTasks()); assertEquals(1,queue().size());
        assertEquals(320,inventoryUnits());
    }

    @Test
    void yieldingRestoresSharedPathfinderSettingsWithoutResettingTheBudget() throws Exception {
        ticks(STALL_TICKS);
        var saved=recovery().snapshot();
        assertTrue(organizer.yieldToAutomation("shared_automation"));
        assertEquals(saved,recovery().snapshot());
        assertEquals(saved,journal.load().orElseThrow().checkpoint().stationWalkRecovery());
        assertTrue(CONFIG.client.extra.pathfinder.allowPlace);
        assertTrue(CONFIG.client.extra.pathfinder.allowParkourPlace);
        assertTrue(CONFIG.client.extra.pathfinder.allowBreak);
    }

    @Test
    void exhaustedResumeStillRecognizesAPlayerWhoAlreadyReachedTheStation() throws Exception {
        recovery().restore(new Snapshot(new Point(38,64,8),null,4800,200,3,
                new Position(8.5,64,8.5),30,List.of()));
        position(38.5,64,8.5); ticks(1);
        assertEquals(StashOrganizer.State.SHULKER_SELECTING,organizer.getState());
        assertFalse(organizer.isFailed());
    }

    @Test
    void exhaustedBudgetCannotBeResetByRestoringAwayFromTheStation() throws Exception {
        ticks(STALL_TICKS * (MAX_DETOURS + 1));
        organizer=new StashOrganizer(config,new ContainerIndex(),journal);
        organizer.setEventCallback(events::put);
        assertEquals(StashOrganizer.DurableRestoreResult.RESTORED,organizer.restoreDurableCheckpoint());
        call("resumePackingSelection"); ticks(1);
        assertTrue(organizer.isFailed());
        assertEquals(3,recovery().attempts());
        assertEquals(320,ledger().remaining());
        assertEquals(0,organizer.getCompletedTasks());
    }

    @Test
    void explicitManualResumeRearmsAndPersistsOneFreshBoundedTrip() throws Exception {
        ticks(STALL_TICKS * (MAX_DETOURS + 1));
        organizer=new StashOrganizer(config,new ContainerIndex(),journal);
        organizer.setEventCallback(events::put);
        assertEquals(StashOrganizer.DurableRestoreResult.RESTORED,organizer.restoreDurableCheckpoint());
        assertEquals(3,recovery().attempts());

        assertTrue(organizer.prepareManualCheckpointResume());

        assertNull(recovery().snapshot());
        assertNull(journal.load().orElseThrow().checkpoint().stationWalkRecovery());
        assertEquals("fresh_bounded_station_trip",
                events.get("organize_station_walk_manual_rearmed").get("disposition"));
        assertEquals(320,ledger().remaining());
        assertEquals(0,organizer.getCompletedTasks());
    }

    @Test
    void failedCheckpointWriteStopsBeforeDispatchingADetour() throws Exception {
        Files.writeString(temp.resolve("blocked-parent"),"not a directory");
        set(journal,"checkpointPath",temp.resolve("blocked-parent/checkpoint.json"));
        ticks(STALL_TICKS);
        assertEquals("station_walk_checkpoint_unavailable",organizer.getLastFailureReason());
        assertFalse(BARITONE.getCustomGoalProcess().isActive());
        assertEquals(0,organizer.getCompletedTasks());
        assertEquals(320,inventoryUnits());
        assertEquals(1,queue().size());
    }

    @Test
    void temporaryWorksiteReturnRetainsThePlacedBoxRecoveryBoundary() throws Exception {
        set(organizer,"state",StashOrganizer.State.MIXED_RETURN_WALK);
        set(organizer,"temporaryShulkerOutstanding",true);
        set(organizer,"shulkerPlacePos",new int[]{38,64,9});
        recovery().restore(new Snapshot(new Point(38,64,8),null,4800,200,3,
                new Position(8.5,64,8.5),30,List.of()));
        ticks(1);
        assertEquals(true,get(organizer,"temporaryShulkerOutstanding"));
        assertEquals(true,get(organizer,"stopAfterShulkerRecovery"));
        assertEquals("mixed_station_return_unreachable",get(organizer,"shulkerRecoveryTrigger"));
        assertEquals(0,organizer.getCompletedTasks());
        assertEquals(320,inventoryUnits());
        assertEquals(1,queue().size());
        assertTrue(CONFIG.client.extra.pathfinder.allowPlace);
    }

    private void ticks(int count) throws Exception { for(int i=0;i<count;i++) call("tickWalking"); }
    @Test
    void chestTopBesideWorksiteArrivesWithoutExactOriginalBlock() throws Exception {
        int[] original = {8,64,8};
        int[] pad = {8,64,11};
        set(organizer,"walkTarget",original); set(organizer,"reconciliationStation",original);
        set(organizer,"reconciliationWorksite",pad);
        block(8,63,9,BlockRegistry.CHEST.minStateId());
        position(8.5,63.875,9.5);
        recovery().restore(new Snapshot(new Point(8,64,8),null,246,200,0,
                new Position(8.5,63.875,9.5),0.95,List.of()));
        Object task = get(organizer,"currentTask");
        ticks(1);
        assertEquals(StashOrganizer.State.SHULKER_SELECTING,organizer.getState());
        assertSame(task,get(organizer,"currentTask"));
        assertArrayEquals(pad,(int[])get(organizer,"reconciliationWorksite"));
        assertEquals(320,ledger().remaining());
        assertEquals(0,organizer.getCompletedTasks());
        assertEquals("worksite_access_verified",events.get("organize_station_walk_recovered").get("reason"));
    }

    @Test
    void exactOriginalBlockDoesNotBypassUnloadedWorksite() throws Exception {
        CACHE.getChunkCache().getCache().remove(2L);
        position(38.5,64,8.5);
        ticks(1);
        assertEquals(StashOrganizer.State.SHULKER_STATION_WALK,organizer.getState());
        assertFalse(events.containsKey("organize_station_walk_recovered"));
    }

    @Test
    void multipleValidatedPositionsReplaceTheSingleStartingBlockGoal() throws Exception {
        ticks(1);
        assertTrue((int)events.get("organize_station_access_planned").get("standing_positions") > 1);
        assertEquals(320,ledger().remaining());
        assertArrayEquals(new int[]{38,64,9},(int[])get(organizer,"reconciliationWorksite"));
    }

    @Test
    void placedBoxResumeUsesItsOriginalWorksiteNotANewPad() throws Exception {
        set(organizer,"state",StashOrganizer.State.SHULKER_RESUME_WALK);
        set(organizer,"temporaryShulkerOutstanding",true);
        set(organizer,"shulkerPlacePos",new int[]{8,64,10});
        block(8,64,10,BlockRegistry.GRAY_SHULKER_BOX.minStateId());
        var method = StashOrganizer.class.getDeclaredMethod("stationAccessWorksite");
        method.setAccessible(true);
        assertEquals(new Point(8,64,10),method.invoke(organizer));
        assertTrue(StationWorksiteAccess.canWorkFrom(new Position(8.5,64,8.5),new Point(8,64,10)));
        assertEquals(true,get(organizer,"temporaryShulkerOutstanding"));
    }

    @Test
    void worksiteAccessRejectsOcclusionUnsupportedFeetAndPlacementOverlap() throws Exception {
        Point pad = new Point(8,64,11);
        Position feet = new Position(8.5,64,8.5);
        assertTrue(StationWorksiteAccess.canWorkFrom(feet,pad));
        block(8,64,10,BlockRegistry.OBSIDIAN.minStateId());
        block(8,65,10,BlockRegistry.OBSIDIAN.minStateId());
        assertFalse(StationWorksiteAccess.canWorkFrom(feet,pad));
        block(8,64,10,0); block(8,65,10,0);
        block(8,63,8,0);
        assertFalse(StationWorksiteAccess.canWorkFrom(feet,pad));
        assertFalse(StationWorksiteAccess.canWorkFrom(new Position(8.5,64,11.5),pad));
        assertFalse(StationWorksiteAccess.canWorkFrom(new Position(8.5,64.1,9.5),pad));
    }

    @Test
    void worksiteAccessRejectsHazardsHeadObstructionAndLongReach() throws Exception {
        Point pad = new Point(8,64,11);
        Position feet = new Position(8.5,64,8.5);
        block(8,63,8,BlockRegistry.MAGMA_BLOCK.minStateId());
        assertFalse(StationWorksiteAccess.canWorkFrom(feet,pad));
        block(8,63,8,BlockRegistry.GLASS.minStateId());
        block(8,65,8,BlockRegistry.GLASS.minStateId());
        assertFalse(StationWorksiteAccess.canWorkFrom(feet,pad));
        block(8,65,8,0);
        assertFalse(StationWorksiteAccess.canWorkFrom(new Position(8.5,64,5.5),pad));
        assertTrue(StationWorksiteAccess.candidates(pad).contains(new Point(8,64,8)));
    }

    @Test
    void chestTopNavigationUsesZenithsFeetConvention() {
        block(8,63,9,BlockRegistry.CHEST.minStateId());
        Point pad = new Point(8,64,11);
        assertEquals(new Point(8,64,9),StationWorksiteAccess.navigationCell(new Position(8.5,63.875,9.5)));
        assertTrue(StationWorksiteAccess.candidates(pad).contains(new Point(8,64,9)));
    }

    @Test
    void offCenterChestLandingHasStableCenterSupportWithoutFullWidthSupport() {
        block(8,63,9,BlockRegistry.CHEST.minStateId());
        block(7,63,9,BlockRegistry.CHEST.minStateId());
        Point pad = new Point(8,64,11);
        Position observed = new Position(8.14829144,63.875,9.6133242);
        assertTrue(StationWorksiteAccess.canWorkFrom(observed,pad));
        assertFalse(StationWorksiteAccess.canWorkFrom(new Position(8.02,63.875,9.6133242),pad),
                "The center must still be supported, even when the player's bounding box overhangs");
    }

    @Test
    void unsafeOffCenterLandingIsExcludedInsteadOfReplayingCompletedGoal() throws Exception {
        set(organizer,"walkTarget",new int[]{8,64,8});
        set(organizer,"reconciliationStation",new int[]{8,64,8});
        set(organizer,"reconciliationWorksite",new int[]{8,64,11});
        block(8,63,9,BlockRegistry.CHEST.minStateId());
        position(8.02,63.875,9.5);
        ticks(1);
        assertTrue(events.containsKey("organize_station_access_replanned"));
        @SuppressWarnings("unchecked") var rejected = (Set<Point>)get(organizer,"rejectedStationAccessCells");
        assertTrue(rejected.contains(new Point(8,64,9)));
        assertEquals(0,organizer.getCompletedTasks());
        assertEquals(320,ledger().remaining());
        assertEquals(StashOrganizer.State.SHULKER_STATION_WALK,organizer.getState());
    }

    @Test
    void seeingTheSideOfAnElevatedSupportDoesNotAuthorizeTopFacePlacement() {
        Point pad = new Point(8,66,11);
        block(8,65,11,BlockRegistry.GLASS.minStateId());
        // Eye height 65.62 can see the support's side, but not its top at 66.
        assertFalse(StationWorksiteAccess.canWorkFrom(new Position(8.5,64,9.5),pad));
        block(8,64,9,BlockRegistry.GLASS.minStateId());
        assertTrue(StationWorksiteAccess.canWorkFrom(new Position(8.5,65,9.5),pad));
    }

    @Test
    void cleanupReturnsBeforeStartingMiningAndDoesNotSpendBreakTicksWalking() throws Exception {
        Object task = get(organizer,"currentTask");
        var queued = List.copyOf(queue());
        beginCleanup("mixed_staging_full");
        assertEquals(StashOrganizer.State.SHULKER_RECOVERY_WALK,organizer.getState());
        assertArrayEquals(new int[]{38,64,9},(int[])get(organizer,"walkTarget"));
        assertEquals("SHULKER_RECOVERY_WALK",journal.load().orElseThrow().checkpoint().interruptedState());
        ticks(100);
        assertEquals(0,get(organizer,"shulkerTicks"));
        assertNull(get(organizer,"shulkerBreakFuture"));
        assertFalse(CONFIG.client.extra.pathfinder.allowBreak);
        assertFalse(CONFIG.client.extra.pathfinder.allowPlace);
        assertSame(task,get(organizer,"currentTask"));
        assertEquals(queued,List.copyOf(queue()));
        position(38.5,64,8.5); ticks(1);
        assertEquals(StashOrganizer.State.SHULKER_RECOVERY_BREAKING,organizer.getState());
        assertEquals(0,get(organizer,"shulkerTicks"));
        assertTrue(events.containsKey("organize_recovery_worksite_reached"));
        assertEquals("SHULKER_RECOVERY_BREAKING",journal.load().orElseThrow().checkpoint().interruptedState());
        organizer.tick();
        assertEquals(1,get(organizer,"shulkerTicks"));
        assertFalse(CONFIG.client.extra.pathfinder.allowBreak,"Explicit shulker mining must not enable path mining");
        assertEquals(0,organizer.getCompletedTasks());
        assertEquals(320,inventoryUnits());
    }

    @Test
    void displacementDuringCleanupStopsMiningAndRechecksAccess() throws Exception {
        beginCleanup("mixed_staging_full");
        position(38.5,64,8.5); ticks(1);
        set(organizer,"shulkerTicks",399);
        position(8.5,64,8.5);
        call("tickShulkerRecoveryBreaking");
        assertEquals(StashOrganizer.State.SHULKER_RECOVERY_WALK,organizer.getState());
        assertEquals(0,get(organizer,"shulkerTicks"));
        assertEquals(1,get(organizer,"shulkerRecoveryBreakAttempts"));
        assertNull(get(organizer,"shulkerBreakFuture"));
        assertTrue(events.containsKey("organize_recovery_access_lost"));
        assertFalse(CONFIG.client.extra.pathfinder.allowBreak);
    }

    @Test
    void repeatedLostAccessCannotLoopForever() throws Exception {
        beginCleanup("mixed_staging_full");
        for (int attempt=0;attempt<3;attempt++) {
            position(38.5,64,8.5); ticks(1);
            position(8.5,64,8.5); call("tickShulkerRecoveryBreaking");
        }
        assertTrue(organizer.isFailed());
        assertEquals("temporary_shulker_access_repeatedly_lost",organizer.getLastFailureReason());
        assertTrue(journal.load().orElseThrow().checkpoint().temporaryShulkerOutstanding());
        assertEquals(0,organizer.getCompletedTasks());
    }

    @Test
    void unreachableCleanupPreservesPlacedBoxAndRestoresPathSettings() throws Exception {
        beginCleanup("mixed_staging_full");
        ticks(STALL_TICKS * (MAX_DETOURS + 1));
        assertTrue(organizer.isFailed());
        assertEquals("temporary_shulker_return_unreachable",organizer.getLastFailureReason());
        assertEquals(0,get(organizer,"shulkerTicks"));
        assertNull(get(organizer,"shulkerBreakFuture"));
        var saved = journal.load().orElseThrow().checkpoint();
        assertTrue(saved.temporaryShulkerOutstanding());
        assertEquals("SHULKER_RECOVERY_WALK",saved.interruptedState());
        assertEquals(3,saved.stationWalkRecovery().attempts());
        assertTrue(CONFIG.client.extra.pathfinder.allowPlace);
        assertTrue(CONFIG.client.extra.pathfinder.allowParkourPlace);
    }

    @Test
    void restoredCleanupKeepsItsReturnBudgetAndDoesNotReopenTheBox() throws Exception {
        beginCleanup("orphaned_worksite");
        ticks(STALL_TICKS);
        var saved = recovery().snapshot();
        call("stopOwnedBaritoneProcess"); call("clearOwnedAutomation"); call("restoreBaritoneBreaking");
        organizer=new StashOrganizer(config,new ContainerIndex(),journal);
        organizer.setEventCallback(events::put);
        assertEquals(StashOrganizer.DurableRestoreResult.RESTORED,organizer.restoreDurableCheckpoint());
        resumeCleanup(StashOrganizer.State.SHULKER_RECOVERY_WALK);
        assertEquals(saved,recovery().snapshot());
        assertEquals(StashOrganizer.State.SHULKER_RECOVERY_WALK,organizer.getState());
        position(38.5,64,8.5); ticks(1);
        assertEquals(StashOrganizer.State.SHULKER_RECOVERY_BREAKING,organizer.getState());
        assertEquals(0,organizer.getCompletedTasks());
    }

    @Test
    void oldFailedBreakCheckpointReturnsThroughValidatedAccess() throws Exception {
        beginCleanup("mixed_staging_full");
        set(organizer,"state",StashOrganizer.State.YIELDED);
        resumeCleanup(StashOrganizer.State.SHULKER_RECOVERY_BREAKING);
        assertEquals(StashOrganizer.State.SHULKER_RECOVERY_WALK,organizer.getState());
        assertNull(get(organizer,"shulkerBreakFuture"));
        assertEquals(0,get(organizer,"shulkerTicks"));
        assertFalse(CONFIG.client.extra.pathfinder.allowBreak);
    }

    @Test
    void serverRestoredBlockRequiresAccessCheckBeforeAnotherBreak() throws Exception {
        beginCleanup("mixed_staging_full");
        set(organizer,"state",StashOrganizer.State.SHULKER_RECOVERY_PICKUP);
        for (int tick = 1; tick < ShulkerRollbackGate.CONFIRM_TICKS; tick++) {
            call("tickShulkerRecoveryPickup");
            assertEquals(StashOrganizer.State.SHULKER_RECOVERY_PICKUP, organizer.getState());
        }
        call("tickShulkerRecoveryPickup");
        assertEquals(StashOrganizer.State.SHULKER_RECOVERY_WALK,organizer.getState());
        assertNull(get(organizer,"shulkerBreakFuture"));
        assertEquals(0,get(organizer,"shulkerTicks"));
    }

    private void beginCleanup(String trigger) throws Exception {
        set(organizer,"temporaryShulkerOutstanding",true);
        set(organizer,"shulkerPlacePos",new int[]{38,64,9});
        block(38,64,9,BlockRegistry.GRAY_SHULKER_BOX.minStateId());
        var method=StashOrganizer.class.getDeclaredMethod("beginTemporaryShulkerRecovery",String.class);
        method.setAccessible(true); method.invoke(organizer,trigger);
    }

    @Test
    void returningCleanupCanRecordAShulkerCollectedOnTheWay() throws Exception {
        var method=StashOrganizer.class.getDeclaredMethod("isTemporaryShulkerPickupState",StashOrganizer.State.class);
        method.setAccessible(true);
        assertEquals(true,method.invoke(null,StashOrganizer.State.SHULKER_RECOVERY_WALK));
        assertEquals(false,method.invoke(null,StashOrganizer.State.WALKING));
    }

    private void resumeCleanup(StashOrganizer.State interrupted) throws Exception {
        var method=StashOrganizer.class.getDeclaredMethod("resumeInterruptedCheckpoint",StashOrganizer.State.class);
        method.setAccessible(true); method.invoke(organizer,interrupted);
    }

    private void block(int x,int y,int z,int id) {
        // The minimum chest state is waterlogged; these fixtures represent dry storage.
        if (id == BlockRegistry.CHEST.minStateId()) {
            while (com.zenith.feature.player.World.getFluidState(id) != null) id++;
        }
        (x >= 32 ? stationChunk : chunk).getChunkSection(y).setBlock(x & 15,y & 15,z & 15,id);
    }
    private int inventoryUnits() { int units=0; for(int slot=9;slot<=13;slot++) units+=inventory.getItemStack(slot).getAmount(); return units; }
    private boolean safe(Point cell) throws Exception { var m=StashOrganizer.class.getDeclaredMethod("isSafeStationDetourCell",Point.class); m.setAccessible(true); return (boolean)m.invoke(organizer,cell); }
    private StationWalkRecovery recovery() throws Exception { return (StationWalkRecovery)get(organizer,"stationWalkRecovery"); }
    private CargoTransactionLedger ledger() throws Exception { return (CargoTransactionLedger)get(organizer,"taskCargo"); }
    @SuppressWarnings("unchecked") private Deque<StashOrganizer.MoveTask> queue() throws Exception { return (Deque<StashOrganizer.MoveTask>)get(organizer,"taskQueue"); }
    @SuppressWarnings("unchecked") private Set<Integer> protectedSlots() throws Exception { return (Set<Integer>)get(organizer,"protectedInventorySlots"); }
    private void position(double x,double y,double z) { CACHE.getPlayerCache().setX(x).setY(y).setZ(z); }
    private Object call(String name) throws Exception { var m=StashOrganizer.class.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(organizer); }
    private static Object get(Object obj,String name) throws Exception { var f=obj.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(obj); }
    private static void set(Object obj,String name,Object value) throws Exception { var f=obj.getClass().getDeclaredField(name); f.setAccessible(true); f.set(obj,value); }
}
