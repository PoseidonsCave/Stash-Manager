package com.zenith.plugin.stashmanager.retriever;

import com.zenith.Proxy;
import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.ClickItem;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.feature.inventory.actions.SetHeldItem;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.GoalBlock;
import com.zenith.feature.pathfinder.goals.GoalGetToBlock;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.kit.KitPackingWorksitePlanner;
import com.zenith.plugin.stashmanager.orchestration.ContainerApproach;
import com.zenith.plugin.stashmanager.orchestration.ContainerAccessPlanner;
import com.zenith.plugin.stashmanager.orchestration.SneakReleaseGate;
import com.zenith.plugin.stashmanager.organizer.ShulkerPickupSweep;
import com.zenith.plugin.stashmanager.organizer.lane.FifoLane;
import com.zenith.plugin.stashmanager.organizer.lane.LaneDetector;
import com.zenith.plugin.stashmanager.util.BaritoneCompat;
import com.zenith.plugin.stashmanager.util.BlockCompat;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import com.zenith.plugin.stashmanager.util.DoubleChestIdentity;
import com.zenith.plugin.stashmanager.profile.ItemProfiles;
import com.zenith.plugin.stashmanager.util.PathfinderCompat;
import com.zenith.util.RequestFuture;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.INPUTS;
import static com.zenith.Globals.INVENTORY;

// Retrieves requested items from candidate containers.
public final class StashRetriever {

    public enum State {
        IDLE,
        WALKING,
        OPENING,
        TAKING,
        UNLOADING_SHULKER,
        DONE
    }

    /** Explains why a retrieval could not enter its first state. */
    public enum StartFailureReason {
        NONE(false),
        EMPTY_REQUEST(false),
        ALREADY_ACTIVE(true),
        BOT_DISCONNECTED(true),
        PROXY_IN_USE(true),
        NO_MATCHES(false);

        private final boolean retryable;

        StartFailureReason(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private static final int OPEN_TIMEOUT_TICKS = 100;
    private static final int OPEN_CONTENT_GRACE_TICKS = 60;
    private static final int PREVIOUS_GUI_QUIET_TICKS = 20;
    private static final int PREVIOUS_GUI_CLOSE_TIMEOUT_TICKS = 120;
    private static final int APPROACH_STALL_TICKS = 80;
    private static final int MAX_APPROACH_STANCES = 4;
    // Match Zenith's default 5-tick click cadence; faster submissions are rejected.
    private static final int CLICK_COOLDOWN_TICKS = 6;
    private static final int WALK_TIMEOUT_TICKS = 400;
    private static final int MAX_CONSECUTIVE_FAILURES = 4;
    private static final int FOOD_REACH_FALLBACK_STILL_TICKS = 40;

    // Nested retrieval has to place, open, empty, break, and recover a borrowed box. A
    // ten-second budget was shorter than a single difficult Baritone approach in large stashes.
    private static final int SHULKER_TOTAL_TIMEOUT_TICKS = 2400;
    private static final int SHULKER_WORKSITE_WALK_TIMEOUT_TICKS = 2400;
    private static final int SHULKER_PLACE_TIMEOUT_TICKS = 400;
    private static final int SHULKER_OPEN_TIMEOUT_TICKS = 200;
    private static final int SHULKER_CONTAINER_SLOTS = 27;
    private static final int SHULKER_CLOSE_TIMEOUT_TICKS = 120;
    private static final int SHULKER_BREAK_TIMEOUT_TICKS = 300;
    private static final int MAX_SHULKER_PLACE_REPLANS = 4;
    private static final int SHULKER_PICKUP_WAIT_TICKS = 16;
    private static final int KIT_SHULKER_PICKUP_TIMEOUT_TICKS = 900;
    private static final int SHULKER_SEARCH_SETTLE_TICKS = 4;
    private static final int SHULKER_PLACE_RETRY_DELAY_TICKS = 10;
    private static final int SHULKER_HELD_RESELECT_TICKS = 40;
    private static final int MAX_SHULKER_HELD_RESELECTS = 5;
    private static final int TELEPORT_CALM_TICKS = 6;
    private static final int SHULKER_HOTBAR_SLOT = 6;
    private static final KitPackingWorksitePlanner.WorldView SHULKER_WORKSITE_WORLD =
            new KitPackingWorksitePlanner.WorldView() {
                private boolean loaded(int x, int y, int z) {
                    return World.isInWorldBounds(x, y, z)
                            && World.isChunkLoadedBlockPos(x, z)
                            && World.getChunkSection(x, y, z) != null;
                }
                @Override public boolean replaceable(int x, int y, int z) {
                    return loaded(x, y, z) && BlockCompat.canReplace(World.getBlock(x, y, z));
                }
                @Override public boolean solid(int x, int y, int z) {
                    return loaded(x, y, z) && BlockCompat.isSolid(x, y, z);
                }
                @Override public boolean interactable(int x, int y, int z) {
                    // An unknown neighbor cannot certify a safe placement face.
                    return !loaded(x, y, z) || BlockCompat.isInteractable(World.getBlock(x, y, z));
                }
            };
    public static final String EMPTY_SHELL_REQUEST = "@empty_shulker";

    private State state = State.IDLE;
    private String activeRequestName;
    private StartFailureReason lastStartFailureReason = StartFailureReason.NONE;

    private final Deque<int[]> targetQueue = new ArrayDeque<>();
    private final Map<Long, int[]> doubleChestPartners = new HashMap<>();
    private int[] currentTarget;
    private int[] openingClickTarget;
    private boolean partnerHalfProbed;
    private final List<BlockPos> approachStances = new ArrayList<>();
    private int approachStanceIndex;
    private int previousGuiQuietTicks;
    private int previousGuiCloseTicks;
    private int previousGuiId = -1;
    private final Map<String, Integer> remaining = new LinkedHashMap<>();
    private int[] activeRegionMin;
    private int[] activeRegionMax;
    private final Set<Long> excludedTargets = new HashSet<>();

    private int openWaitTicks;
    private int actionCooldown;
    private int actionSlotIndex;
    private int walkingTicks;
    private int stationaryWalkTicks;
    private double lastWalkX, lastWalkY, lastWalkZ;
    private boolean preferNearbyFoodTargets;
    private boolean directContentsOnly;
    private java.util.function.Predicate<ItemStack> kitPhysicalStackFilter;
    private Consumer<List<ItemStack>> physicalStackSurvey;
    private boolean physicalStackSurveyed;
    private boolean foodPathRearmed;
    private int consecutiveFailures;
    private int initialRequestedTotal;
    private int successfulTransfers;
    private int transfersAtTargetStart;
    private boolean targetOpenAttempted;
    private final SneakReleaseGate containerOpenGate = new SneakReleaseGate();

    private volatile boolean containerDataReceived = false;
    private volatile int openContainerId = -1;
    private volatile ItemStack[] containerSlots;
    private volatile Session serverSession;

    private int unloadPhase;
    private int unloadTicks;
    private int unloadTotalTicks;
    private int unloadShulkerSlot = -1;
    private int unloadChestSlot = -1;
    private int unloadSourceContainerId = -1;
    private int unloadCloseContainerId = -1;
    private int[] placedShulkerPos;
    private int shulkerCountBeforeBreak = -1;
    private int borrowedExactCountBeforeBreak = -1;
    private Map<String, Integer> borrowedBoxContentsAfter = Map.of();
    private Set<Integer> itemEntitiesBeforeBreak = Set.of();
    private final Set<Integer> observedBorrowedDropIds = new HashSet<>();
    private int borrowedDropEntityId = -1;
    private int[] borrowedDropLastTarget;
    private boolean borrowedDropSeen;
    private int borrowedPickupMismatchTicks;
    private int pickupSweepAttempt;
    private int lastPickupSweepTick = -1;
    private boolean borrowedPickupVerified;
    private int[] borrowedSourceOrigin;
    private String borrowedRequestItem;
    private int borrowedRemainingBefore;
    private String borrowedBoxItemId;
    private Map<String, Integer> borrowedBoxContentsBefore = Map.of();
    private boolean savedPlaceBlockSneak = false;
    private boolean placeSneakGuardActive = false;
    private ItemData unloadShulkerItemData;
    private PathingRequestFuture unloadPlaceFuture;
    private PathingRequestFuture unloadBreakFuture;
    private RequestFuture unloadHotbarRequest;
    private int unloadHotbarRawSlot = -1;
    private int shulkerInventorySearchDelay;
    private int shulkerOpenRetries;
    private int shulkerPlaceReplans;
    private int shulkerHeldReselects;
    private int[] fallbackWorksiteStation;
    private int[] fallbackWorksitePlacePos;
    private boolean discoveredWorksiteFallback;
    private boolean worksiteFallbackAttempted;
    private int worksiteCargoFloorY;
    private boolean worksiteNavigationGuardActive;
    private boolean savedWorksiteAllowBreak;
    private boolean savedWorksiteAllowPlace;
    private final Set<Long> rejectedShulkerPlacePositions = new HashSet<>();

    private int lastTeleportQueueSize = -1;
    private int ticksSinceTeleportQueueChange;

    // Split-take state (partial stack retrieval)
    private boolean splitInProgress;
    private int splitSrcSlot = -1;
    private int splitPutbacksLeft;
    private int splitNeeded;
    private boolean splitCursorReady;
    private boolean splitAwaitingDeposit;
    private int splitDropRawSlot = -1;
    private int splitDepositWaitTicks;
    private String splitItemId;

    // Owned shulker tracking (shulkers taken for their contents)
    private final Set<Integer> ownedShulkerSlots = new HashSet<>();
    private String pendingOwnedShulkerFingerprint;
    private final Set<Integer> pendingOwnedShulkerCandidateSlots = new HashSet<>();

    private BiConsumer<String, Map<String, Object>> eventCallback;

    public StashRetriever() {}

    public void setEventCallback(BiConsumer<String, Map<String, Object>> eventCallback) {
        this.eventCallback = eventCallback;
    }

    public State getState() {
        return state;
    }

    public boolean isActive() {
        return state != State.IDLE && state != State.DONE;
    }

    public String getActiveRequestName() {
        return activeRequestName;
    }

    public StartFailureReason getLastStartFailureReason() {
        return lastStartFailureReason;
    }

    public int getRemainingTotal() {
        return remaining.values().stream().mapToInt(Integer::intValue).sum();
    }

    public Map<String, Integer> getRemainingItems() {
        return Map.copyOf(remaining);
    }

    public String getStatus() {
        return switch (state) {
            case IDLE -> "Idle";
            case WALKING -> "Walking to container";
            case OPENING -> "Opening container";
            case TAKING -> "Taking matching items";
            case UNLOADING_SHULKER -> "Unloading nested shulker";
            case DONE -> "Done";
        };
    }

    public boolean startKit(String requestName,
                            Map<String, Integer> kitItems,
                            List<ContainerEntry> candidates) {
        return startKit(requestName, kitItems, candidates, null, null, Set.of());
    }

    public boolean startKit(String requestName,
                            Map<String, Integer> kitItems,
                            List<ContainerEntry> candidates,
                            int[] regionPos1,
                            int[] regionPos2,
                            Set<Long> excludedPositions) {
        return startKit(requestName, kitItems, candidates,
                regionPos1, regionPos2, excludedPositions, false);
    }

    public boolean startKit(String requestName,
                            Map<String, Integer> kitItems,
                            List<ContainerEntry> candidates,
                            int[] regionPos1,
                            int[] regionPos2,
                            Set<Long> excludedPositions,
                            boolean preferNearbyFoodTargets) {
        return startKit(requestName, kitItems, candidates, regionPos1, regionPos2,
                excludedPositions, preferNearbyFoodTargets, null, null);
    }

    public boolean startKit(String requestName,
                            Map<String, Integer> kitItems,
                            List<ContainerEntry> candidates,
                            int[] regionPos1,
                            int[] regionPos2,
                            Set<Long> excludedPositions,
                            boolean preferNearbyFoodTargets,
                            int[] fallbackWorksiteStation,
                            int[] fallbackWorksitePlacePos) {
        return startKit(requestName, kitItems, candidates, regionPos1, regionPos2,
                excludedPositions, preferNearbyFoodTargets, fallbackWorksiteStation,
                fallbackWorksitePlacePos, -1);
    }

    /** Only a checkpoint-verified kit recovery may borrow an inventory shulker. */
    public boolean startKit(String requestName,
                            Map<String, Integer> kitItems,
                            List<ContainerEntry> candidates,
                            int[] regionPos1,
                            int[] regionPos2,
                            Set<Long> excludedPositions,
                            boolean preferNearbyFoodTargets,
                            int[] fallbackWorksiteStation,
                            int[] fallbackWorksitePlacePos,
                            int recoveryInventorySourceSlot) {
        return startKit(requestName, kitItems, candidates, regionPos1, regionPos2,
                excludedPositions, preferNearbyFoodTargets, fallbackWorksiteStation,
                fallbackWorksitePlacePos, recoveryInventorySourceSlot, null);
    }

    /** A live physical filter may select only component-identical kit cargo. */
    public boolean startKit(String requestName,
                            Map<String, Integer> kitItems,
                            List<ContainerEntry> candidates,
                            int[] regionPos1,
                            int[] regionPos2,
                            Set<Long> excludedPositions,
                            boolean preferNearbyFoodTargets,
                            int[] fallbackWorksiteStation,
                            int[] fallbackWorksitePlacePos,
                            int recoveryInventorySourceSlot,
                            java.util.function.Predicate<ItemStack> physicalStackFilter) {
        return startKit(requestName, kitItems, candidates, regionPos1, regionPos2,
                excludedPositions, preferNearbyFoodTargets, fallbackWorksiteStation,
                fallbackWorksitePlacePos, recoveryInventorySourceSlot,
                physicalStackFilter, false);
    }

    /** Direct-only retrieval never borrows a carried or indexed nested shulker. */
    public boolean startKit(String requestName,
                            Map<String, Integer> kitItems,
                            List<ContainerEntry> candidates,
                            int[] regionPos1,
                            int[] regionPos2,
                            Set<Long> excludedPositions,
                            boolean preferNearbyFoodTargets,
                            int[] fallbackWorksiteStation,
                            int[] fallbackWorksitePlacePos,
                            int recoveryInventorySourceSlot,
                            java.util.function.Predicate<ItemStack> physicalStackFilter,
                            boolean directContentsOnly) {
        return startKit(requestName, kitItems, candidates, regionPos1, regionPos2,
                excludedPositions, preferNearbyFoodTargets, fallbackWorksiteStation,
                fallbackWorksitePlacePos, recoveryInventorySourceSlot,
                physicalStackFilter, directContentsOnly, null);
    }

    /** Survey a live source's physical stacks before a component-filtered transfer. */
    public boolean startKit(String requestName,
                            Map<String, Integer> kitItems,
                            List<ContainerEntry> candidates,
                            int[] regionPos1,
                            int[] regionPos2,
                            Set<Long> excludedPositions,
                            boolean preferNearbyFoodTargets,
                            int[] fallbackWorksiteStation,
                            int[] fallbackWorksitePlacePos,
                            int recoveryInventorySourceSlot,
                            java.util.function.Predicate<ItemStack> physicalStackFilter,
                            boolean directContentsOnly,
                            Consumer<List<ItemStack>> physicalStackSurvey) {
        lastStartFailureReason = StartFailureReason.NONE;
        if (kitItems == null || kitItems.isEmpty()) {
            lastStartFailureReason = StartFailureReason.EMPTY_REQUEST;
            return false;
        }
        if (isActive()) {
            lastStartFailureReason = StartFailureReason.ALREADY_ACTIVE;
            return false;
        }

        var proxy = Proxy.getInstance();
        if (!proxy.isConnected()) {
            lastStartFailureReason = StartFailureReason.BOT_DISCONNECTED;
            return false;
        }
        if (proxy.hasActivePlayer()) {
            lastStartFailureReason = StartFailureReason.PROXY_IN_USE;
            return false;
        }

        resetState();
        this.kitPhysicalStackFilter = physicalStackFilter;
        this.physicalStackSurvey = physicalStackSurvey;
        this.directContentsOnly = directContentsOnly;
        activeRequestName = requestName;
        this.preferNearbyFoodTargets = preferNearbyFoodTargets;
        this.fallbackWorksiteStation = fallbackWorksiteStation == null
                ? null : fallbackWorksiteStation.clone();
        this.fallbackWorksitePlacePos = fallbackWorksitePlacePos == null
                ? null : fallbackWorksitePlacePos.clone();
        setTargetPolicy(regionPos1, regionPos2, excludedPositions);
        kitItems.forEach((k, v) -> {
            if (v != null && v > 0) remaining.put(k, v);
        });

        if (remaining.isEmpty()) {
            lastStartFailureReason = StartFailureReason.EMPTY_REQUEST;
            state = State.DONE;
            emit("retrieve_no_targets", Map.of("reason", "empty_request"));
            return false;
        }

        initialRequestedTotal = getRemainingTotal();

        // Prefer a FIFO lane's output chest (oldest stock) over its input chest.
        Set<Long> laneOutputKeys = new HashSet<>();
        for (FifoLane lane : LaneDetector.detectLanes(candidates)) {
            laneOutputKeys.add(posKey(lane.outputPos()[0], lane.outputPos()[1], lane.outputPos()[2]));
        }

        List<ContainerEntry> sorted = new ArrayList<>(candidates);
        Comparator<ContainerEntry> normalOrder = Comparator
            .comparingInt((ContainerEntry e) -> -matchScore(e))
            .thenComparingInt(e -> directMatchScore(e) > 0 ? 0 : 1)
            .thenComparingInt(e -> laneOutputKeys.contains(posKey(e.x(), e.y(), e.z())) ? 0 : 1)
            .thenComparingDouble(e -> distanceTo(e.x(), e.y(), e.z()));
        if (preferNearbyFoodTargets) {
            var player = CACHE.getPlayerCache();
            sorted.sort(Comparator
                    .comparingInt((ContainerEntry e) -> foodAccessBand(
                            player.getX(), player.getY(), player.getZ(), e))
                    .thenComparingDouble(e -> distanceTo(e.x(), e.y(), e.z()))
                    .thenComparing(normalOrder));
        } else {
            sorted.sort(normalOrder);
        }

        for (ContainerEntry entry : sorted) {
            if (matchScore(entry) <= 0) continue;
            if (!isAllowedTarget(entry.x(), entry.y(), entry.z())) continue;
            targetQueue.add(new int[]{entry.x(), entry.y(), entry.z()});
            if (entry.isDouble() && entry.inventoryFootprintKnown()) {
                int partnerX = entry.inventoryX();
                int partnerZ = entry.inventoryZ();
                if ("X".equals(entry.doubleChestAxis())) partnerX++;
                else if ("Z".equals(entry.doubleChestAxis())) partnerZ++;
                int[] partner = entry.x() == partnerX && entry.z() == partnerZ
                        ? new int[]{entry.inventoryX(), entry.inventoryY(), entry.inventoryZ()}
                        : new int[]{partnerX, entry.inventoryY(), partnerZ};
                if (isAllowedTarget(partner[0], partner[1], partner[2])) {
                    doubleChestPartners.put(posKey(entry.x(), entry.y(), entry.z()), partner);
                }
            }
        }

        // A restart can occur after a nested shulker was borrowed but before its contents were
        // extracted. Recover from the live inventory first instead of borrowing a duplicate.
        // Kit builds protect everything already in inventory. Borrowing one of those boxes
        // would move a protected slot and make newly extracted cargo indistinguishable from it.
        int inventoryShulkerSlot = directContentsOnly ? -1 : recoveryInventorySourceSlot >= 9
                && recoveryInventorySourceSlot <= 44
                && matchesWantedShulker(getPlayerInventoryStack(recoveryInventorySourceSlot))
                    ? recoveryInventorySourceSlot
                    : mayBorrowInventoryShulker(requestName, directContentsOnly)
                        ? findInventoryShulkerWithWantedContents() : -1;
        if (recoveryInventorySourceSlot >= 0 && inventoryShulkerSlot != recoveryInventorySourceSlot) {
            lastStartFailureReason = StartFailureReason.NO_MATCHES;
            state = State.DONE;
            emit("retrieve_no_targets", Map.of("reason", "verified_inventory_source_changed"));
            return false;
        }
        if (targetQueue.isEmpty() && inventoryShulkerSlot < 0) {
            lastStartFailureReason = StartFailureReason.NO_MATCHES;
            state = State.DONE;
            emit("retrieve_no_targets", Map.of(
                "reason", "no_matches",
                "total_requested", initialRequestedTotal,
                "unique_items", remaining.size()
            ));
            return false;
        }

        emit("retrieve_started", Map.of(
            "total_requested", initialRequestedTotal,
            "unique_items", remaining.size(),
            "candidate_targets", targetQueue.size(),
            "inventory_shulker_available", inventoryShulkerSlot >= 0
        ));

        BARITONE.stop();
        if (inventoryShulkerSlot >= 0) {
            ItemStack inventoryShulker = getPlayerInventoryStack(inventoryShulkerSlot);
            ownedShulkerSlots.add(inventoryShulkerSlot);
            emit("retrieve_inventory_shulker_selected", Map.of(
                "inventory_slot", inventoryShulkerSlot,
                "reason", "wanted_contents_already_in_inventory"
            ));
            beginShulkerUnload(-1, inventoryShulker, -1);
            unloadShulkerSlot = inventoryShulkerSlot;
            return true;
        }
        advanceToNextTarget(null);
        return true;
    }

    public void stop() {
        if (isActive()) {
            emit("retrieve_stopped", Map.of(
                "reason", "manual_stop",
                "moved_stacks", successfulTransfers,
                "obtained_total", Math.max(0, initialRequestedTotal - getRemainingTotal())
            ));
        }
        BARITONE.stop();
        restoreWorksiteNavigation();
        closeCurrentContainer();
        state = State.IDLE;
        targetQueue.clear();
        currentTarget = null;
        activeRequestName = null;
    }

    public void onContainerData(Session session, ClientboundContainerSetContentPacket packet) {
        if (packet.getContainerId() <= 0) return;
        this.serverSession = session;
        this.openContainerId = packet.getContainerId();
        this.containerSlots = packet.getItems();
        this.containerDataReceived = true;
    }

    public void tick() {
        if (state == State.IDLE || state == State.DONE) return;

        updateTeleportStability();

        switch (state) {
            case WALKING -> tickWalking();
            case OPENING -> tickOpening();
            case TAKING -> tickTaking();
            case UNLOADING_SHULKER -> tickUnloadingShulker();
            default -> {
            }
        }
    }

    private void tickWalking() {
        if (currentTarget == null) {
            finish(false, "missing_target");
            return;
        }
        if (waitForPreviousGuiClose()) return;

        walkingTicks++;
        var player = CACHE.getPlayerCache();
        double movedSquared = Math.pow(player.getX() - lastWalkX, 2)
                + Math.pow(player.getY() - lastWalkY, 2)
                + Math.pow(player.getZ() - lastWalkZ, 2);
        stationaryWalkTicks = movedSquared < 0.0025 ? stationaryWalkTicks + 1 : 0;
        lastWalkX = player.getX();
        lastWalkY = player.getY();
        lastWalkZ = player.getZ();

        double dist = distanceTo(currentTarget[0], currentTarget[1], currentTarget[2]);
        if (isAtTargetAccessPosition() && selectVisibleClickHalf()) {
            beginOpeningTarget();
            return;
        }
        if (preferNearbyFoodTargets
                && stationaryWalkTicks >= FOOD_REACH_FALLBACK_STILL_TICKS
                && ContainerApproach.isWithinVanillaReach(
                        player.getX(), player.getY(), player.getZ(),
                        currentTarget[0], currentTarget[1], currentTarget[2])) {
            emit("retrieve_food_reach_fallback", Map.of(
                    "reason", "path_stalled_within_vanilla_reach",
                    "stationary_ticks", stationaryWalkTicks,
                    "distance", String.format("%.1f", dist)));
            beginOpeningTarget();
            return;
        }
        if (preferNearbyFoodTargets
                && stationaryWalkTicks >= FOOD_REACH_FALLBACK_STILL_TICKS
                && !foodPathRearmed) {
            foodPathRearmed = true;
            BARITONE.stop();
            emit("retrieve_food_path_rearmed", Map.of(
                    "reason", "no_movement_after_path_request",
                    "stationary_ticks", stationaryWalkTicks,
                    "distance", String.format("%.1f", dist),
                    "feet_block", blockNameAtPlayer(0),
                    "floor_block", blockNameAtPlayer(-1)));
            pathToTarget();
            return;
        }

        if (stationaryWalkTicks >= APPROACH_STALL_TICKS
                && advanceApproachStance()) return;

        if (walkingTicks > WALK_TIMEOUT_TICKS) {
            consecutiveFailures++;
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                emit("retrieve_target_failed", Map.of(
                    "failure_reason", "walk_timeout",
                    "distance", String.format("%.1f", dist)
                ));
                finish(false, "too_many_failures");
                return;
            }
            advanceToNextTarget("walk_timeout");
            return;
        }

        if (!BARITONE.getCustomGoalProcess().isActive()) {
            pathToTarget();
        }
    }

    private void beginOpeningTarget() {
        BARITONE.stop();
        targetOpenAttempted = true;
        state = State.OPENING;
        containerOpenGate.reset();
        openWaitTicks = 0;
        containerDataReceived = false;
        openContainerId = -1;
        containerSlots = null;
    }

    private String blockNameAtPlayer(int yOffset) {
        var player = CACHE.getPlayerCache();
        var block = World.getBlock(
                (int) Math.floor(player.getX()),
                (int) Math.floor(player.getY()) + yOffset,
                (int) Math.floor(player.getZ()));
        return block == null ? "unknown" : block.name();
    }

    private void tickOpening() {
        if (!prepareStandingContainerInteraction()) return;
        openWaitTicks++;
        int liveId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        var opening = RetrievalOpenPolicy.opening(containerDataReceived, openContainerId,
                liveId, openWaitTicks, OPEN_TIMEOUT_TICKS, OPEN_CONTENT_GRACE_TICKS);
        if (opening == RetrievalOpenPolicy.Opening.CONFIRMED) {
            state = State.TAKING;
            actionSlotIndex = 0;
            actionCooldown = 0;
            emit("retrieve_target_opened", Map.of());
            return;
        }
        if (opening == RetrievalOpenPolicy.Opening.WAIT_FOR_CONTENTS) return;

        if (!partnerHalfProbed && openWaitTicks >= 25) {
            partnerHalfProbed = true;
            int[] partner = doubleChestPartners.get(posKey(
                    currentTarget[0], currentTarget[1], currentTarget[2]));
            if (partner != null && verifiedReachablePartner(partner)) {
                openingClickTarget = partner;
                emit("retrieve_partner_half_retry", Map.of(
                        "partner_position", posString(partner),
                        "reason", "primary_open_unconfirmed"));
            }
        }

        if (opening == RetrievalOpenPolicy.Opening.TIMED_OUT) {
            consecutiveFailures++;
            advanceToNextTarget("open_timeout");
            return;
        }

        // Retry missed opens instead of failing the target.
        if (openWaitTicks == 1 || openWaitTicks % 10 == 0) {
            interactWithTarget();
        }
    }

    private void tickTaking() {
        if (actionCooldown > 0) {
            actionCooldown--;
            return;
        }

        // Finish the current split before taking another slot.
        if (splitInProgress) {
            if (tickSplitTake()) {
                actionCooldown = CLICK_COOLDOWN_TICKS;
                return;
            }
        }

        if (containerSlots == null || openContainerId < 0) {
            consecutiveFailures++;
            advanceToNextTarget("container_sync_failed");
            return;
        }

        int chestSlots = getOpenContainerSlotCount();
        surveyPhysicalStacks(chestSlots);
        resolvePendingOwnedShulker();
        // Kit fulfillment journals each borrowed box and returns it after the cargo
        // has been packed. An implicit return here would bypass that verification.
        if (!strictBorrowedPickup() && returnFinishedOwnedShulker(chestSlots)) {
            actionCooldown = CLICK_COOLDOWN_TICKS;
            return;
        }

        while (actionSlotIndex < chestSlots) {
            ItemStack stack = containerSlots[actionSlotIndex];
            if (stack != null && stack.getAmount() > 0) {
                String itemId = itemIdFromStack(stack);
                String requestKey = matchingRequestKey(itemId);
                if (requestKey == null && remaining.getOrDefault(EMPTY_SHELL_REQUEST, 0) > 0
                        && isShulkerBoxItem(itemId)
                        && ItemIdentifier.readShulkerContents(stack).isEmpty()) {
                    requestKey = EMPTY_SHELL_REQUEST;
                }
                boolean wantedDirectly = requestKey != null && physicalStackAllowed(stack);
                boolean wantedForContents = !directContentsOnly
                        && !wantedDirectly && containsWantedContents(stack);
                Integer needed = requestKey == null ? null : remaining.get(requestKey);

                if ((wantedDirectly || wantedForContents) && hasInventoryRoom(stack)) {
                    
                    // Split stacks larger than the remaining request.
                    if (wantedDirectly && !wantedForContents && needed != null && needed > 0
                            && exactSlotTransferRequired(activeRequestName, requestKey,
                                    stack.getAmount(), needed)) {
                        beginSplitTake(actionSlotIndex, requestKey, stack.getAmount(), needed);
                        actionSlotIndex++;
                        actionCooldown = CLICK_COOLDOWN_TICKS;
                        return;
                    }

                    if (!quickMoveSlot(actionSlotIndex)) {
                        actionCooldown = CLICK_COOLDOWN_TICKS;
                        return;
                    }
                    successfulTransfers++;
                    actionSlotIndex++;
                    actionCooldown = CLICK_COOLDOWN_TICKS;

                    if (wantedForContents) {
                        // Track shulkers borrowed for unloading.
                        beginTrackingOwnedShulker(stack, chestSlots);
                        
                        int[] revisitTarget = currentTarget == null ? null : currentTarget.clone();
                        int sourceContainerId = openContainerId;
                        closeCurrentContainer();
                        if (revisitTarget != null) {
                            targetQueue.addFirst(revisitTarget);
                        }
                        beginShulkerUnload(actionSlotIndex - 1, stack, sourceContainerId);
                        return;
                    }

                    if (needed != null && needed > 0) {
                        remaining.put(requestKey, Math.max(0, needed - stack.getAmount()));
                    }

                    if (successfulTransfers % 5 == 0) {
                        emit("retrieve_progress", Map.of(
                            "moved_stacks", successfulTransfers,
                            "obtained_total", Math.max(0, initialRequestedTotal - getRemainingTotal())
                        ));
                    }

                    if (isComplete()) {
                        finish(true, "complete");
                    }
                    return;
                }
            }
            actionSlotIndex++;
        }

        consecutiveFailures = 0;
        advanceToNextTarget("container_exhausted");
    }

    private void beginShulkerUnload(int chestSlot, ItemStack shulkerStack,
                                   int sourceContainerId) {
        // The container approach may still own a path request when a within-reach open succeeds.
        // Do not let that request carry the bot away after we select a local placement cell.
        BARITONE.stop();
        unloadPhase = 0;
        unloadTicks = 0;
        unloadTotalTicks = 0;
        unloadChestSlot = chestSlot;
        borrowedSourceOrigin = chestSlot >= 0 && currentTarget != null
                ? currentTarget.clone() : null;
        borrowedRequestItem = strictBorrowedPickup() && remaining.size() == 1
                ? remaining.keySet().iterator().next() : null;
        borrowedRemainingBefore = borrowedRequestItem == null ? 0
                : remaining.getOrDefault(borrowedRequestItem, 0);
        borrowedBoxItemId = ItemIdentifier.getItemId(shulkerStack);
        borrowedBoxContentsBefore = Map.copyOf(ItemIdentifier.readShulkerContents(shulkerStack));
        unloadSourceContainerId = sourceContainerId;
        unloadShulkerSlot = -1;
        placedShulkerPos = null;
        unloadShulkerItemData = ItemRegistry.REGISTRY.get(shulkerStack.getId());
        unloadPlaceFuture = null;
        unloadBreakFuture = null;
        unloadHotbarRequest = null;
        unloadHotbarRawSlot = -1;
        shulkerInventorySearchDelay = SHULKER_SEARCH_SETTLE_TICKS;
        shulkerOpenRetries = 0;
        shulkerPlaceReplans = 0;
        shulkerHeldReselects = 0;
        worksiteFallbackAttempted = false;
        rejectedShulkerPlacePositions.clear();
        state = State.UNLOADING_SHULKER;
        emit("retrieve_shulker_unload_started", Map.of(
            "source_container_slot", chestSlot,
            "shulker_item_id", itemIdFromStack(shulkerStack)
        ));
    }

    private void tickUnloadingShulker() {
        unloadTicks++;
        unloadTotalTicks++;

        int totalTimeout = SHULKER_TOTAL_TIMEOUT_TICKS
                + (worksiteFallbackAttempted ? SHULKER_WORKSITE_WALK_TIMEOUT_TICKS : 0);
        if (unloadTotalTicks > totalTimeout) {
            emit("retrieve_shulker_unload_failed", Map.of(
                "reason", "timeout",
                "phase", unloadPhase,
                "phase_ticks", unloadTicks,
                "place_replans", shulkerPlaceReplans
            ));
            finish(false, "shulker_unload_timeout");
            return;
        }

        // Sneak so right-click places the shulker instead of opening a nearby container.
        setPlaceBlockSneak(unloadPhase == 1);

        switch (unloadPhase) {
            case 0 -> tickUnloadLocateAndPrepare();
            case 1 -> tickUnloadPlace();
            case 2 -> tickUnloadOpen();
            case 3 -> tickUnloadTakeContents();
            case 4 -> tickUnloadBreak();
            case 5 -> tickUnloadResume();
            case 6 -> tickUnloadWorksiteWalk();
            case 7 -> tickUnloadClose();
            default -> {
                emit("retrieve_shulker_unload_failed", Map.of("reason", "invalid_phase"));
                finish(false, "invalid_shulker_phase");
            }
        }
    }

    private void setPlaceBlockSneak(boolean sneak) {
        if (!placeSneakGuardActive) {
            savedPlaceBlockSneak = PathfinderCompat.getPlaceBlockSneak();
            placeSneakGuardActive = true;
        }
        PathfinderCompat.setPlaceBlockSneak(sneak);
    }

    private void restorePlaceBlockSneak() {
        if (!placeSneakGuardActive) return;
        PathfinderCompat.setPlaceBlockSneak(savedPlaceBlockSneak);
        placeSneakGuardActive = false;
    }

    private int[] selectShulkerPlaceSpot() {
        if (!worksiteFallbackAttempted) return findShulkerPlaceSpot();
        if (fallbackWorksitePlacePos == null) return null;
        int[] worksite = fallbackWorksitePlacePos;
        if (rejectedShulkerPlacePositions.contains(posKey(
                worksite[0], worksite[1], worksite[2]))
                || !isSafeShulkerPlaceSpot(worksite)
                || !isWithinShulkerPlacementReach(worksite)) return null;
        return worksite.clone();
    }

    private void beginWorksiteFallback() {
        if (fallbackWorksiteStation.length != 3 || fallbackWorksitePlacePos.length != 3
                || !World.isInWorldBounds(fallbackWorksiteStation[0], fallbackWorksiteStation[1], fallbackWorksiteStation[2])
                || !World.isInWorldBounds(fallbackWorksitePlacePos[0], fallbackWorksitePlacePos[1], fallbackWorksitePlacePos[2])) {
            emit("retrieve_shulker_unload_failed", Map.of("reason", "invalid_worksite"));
            finish(false, "shulker_invalid_worksite");
            return;
        }
        worksiteFallbackAttempted = true;
        worksiteCargoFloorY = (int) Math.floor(CACHE.getPlayerCache().getY());
        unloadPhase = 6;
        unloadTicks = 0;
        BARITONE.stop();
        guardWorksiteNavigation();
        emit("retrieve_shulker_worksite_fallback", Map.of(
                "reason", "no_local_place_spot",
                "station", posString(fallbackWorksiteStation),
                "worksite", posString(fallbackWorksitePlacePos)));
    }

    private void tickUnloadWorksiteWalk() {
        if ((int) Math.floor(CACHE.getPlayerCache().getY()) < worksiteCargoFloorY) {
            BARITONE.stop();
            emit("retrieve_shulker_unload_failed", Map.of("reason", "worksite_descended_below_cargo_floor"));
            finish(false, "shulker_worksite_descended_below_cargo_floor");
            return;
        }
        if (atWorksiteStation()) {
            BARITONE.stop();
            restoreWorksiteNavigation();
            placedShulkerPos = selectShulkerPlaceSpot();
            if (placedShulkerPos == null) {
                emit("retrieve_shulker_unload_failed", Map.of(
                        "reason", "worksite_unavailable",
                        "worksite", posString(fallbackWorksitePlacePos)));
                finish(false, "shulker_worksite_unavailable");
                return;
            }
            emit("retrieve_shulker_worksite_reached", Map.of(
                    "station", posString(fallbackWorksiteStation),
                    "worksite", posString(placedShulkerPos)));
            unloadPhase = 0;
            unloadTicks = 0;
            return;
        }
        if (unloadTicks > SHULKER_WORKSITE_WALK_TIMEOUT_TICKS) {
            emit("retrieve_shulker_unload_failed", Map.of(
                    "reason", "worksite_walk_timeout",
                    "station", posString(fallbackWorksiteStation)));
            finish(false, "shulker_worksite_walk_timeout");
            return;
        }
        if (unloadTicks == 1 || (unloadTicks % 40 == 0
                && !BARITONE.getCustomGoalProcess().isActive())) {
            BARITONE.pathTo(new GoalBlock(new BlockPos(
                    fallbackWorksiteStation[0],
                    fallbackWorksiteStation[1],
                    fallbackWorksiteStation[2])));
        }
    }

    private boolean atWorksiteStation() {
        if (fallbackWorksiteStation == null) return false;
        var player = CACHE.getPlayerCache();
        return (int) Math.floor(player.getX()) == fallbackWorksiteStation[0]
                && (int) Math.floor(player.getY()) == fallbackWorksiteStation[1]
                && (int) Math.floor(player.getZ()) == fallbackWorksiteStation[2];
    }

    private void guardWorksiteNavigation() {
        if (worksiteNavigationGuardActive) return;
        savedWorksiteAllowBreak = CONFIG.client.extra.pathfinder.allowBreak;
        savedWorksiteAllowPlace = CONFIG.client.extra.pathfinder.allowPlace;
        worksiteNavigationGuardActive = true;
        CONFIG.client.extra.pathfinder.allowBreak = false;
        CONFIG.client.extra.pathfinder.allowPlace = false;
    }

    private void restoreWorksiteNavigation() {
        if (!worksiteNavigationGuardActive) return;
        CONFIG.client.extra.pathfinder.allowBreak = savedWorksiteAllowBreak;
        CONFIG.client.extra.pathfinder.allowPlace = savedWorksiteAllowPlace;
        worksiteNavigationGuardActive = false;
    }

    private void tickUnloadLocateAndPrepare() {
        if (shulkerInventorySearchDelay > 0) {
            shulkerInventorySearchDelay--;
            return;
        }

        // The source chest close follows the shift-click. Do not navigate or select a
        // player-inventory hotbar slot until Zenith observes that close.
        if (CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() > 0) return;

        resolvePendingOwnedShulker();

        if (unloadShulkerSlot < 0) {
            // A kit may already be carrying another shulker with the requested item.
            // Only unload the exact box just borrowed from this chest; selecting the
            // first content match can strand the borrowed box and misjournal its return.
            if (strictBorrowedPickup() && unloadChestSlot >= 0) {
                unloadShulkerSlot = findUniqueBorrowedSourceSlot();
                if (unloadShulkerSlot == -2) {
                    finish(false, "borrowed_source_inventory_ambiguous");
                    return;
                }
            } else {
                unloadShulkerSlot = findWantedShulkerSlot();
            }
            if (unloadShulkerSlot < 0) {
                return;
            }
        }

        if (placedShulkerPos == null) {
            placedShulkerPos = selectShulkerPlaceSpot();
            if (placedShulkerPos == null) {
                if (!worksiteFallbackAttempted && fallbackWorksiteStation == null
                        && fallbackWorksitePlacePos == null && strictBorrowedPickup()) {
                    var player = CACHE.getPlayerCache();
                    var discovered = KitPackingWorksitePlanner.discover(
                            (int) Math.floor(player.getX()),
                            (int) Math.floor(player.getY()),
                            (int) Math.floor(player.getZ()), SHULKER_WORKSITE_WORLD);
                    if (discovered != null) {
                        fallbackWorksiteStation = discovered.stance();
                        fallbackWorksitePlacePos = discovered.worksite();
                        discoveredWorksiteFallback = true;
                    }
                }
                if (!worksiteFallbackAttempted && fallbackWorksiteStation != null
                        && fallbackWorksitePlacePos != null) {
                    beginWorksiteFallback();
                    return;
                }
                if (worksiteFallbackAttempted) {
                    emit("retrieve_shulker_unload_failed", Map.of(
                            "reason", "worksite_unavailable",
                            "worksite", posString(fallbackWorksitePlacePos)));
                    finish(false, "shulker_worksite_unavailable");
                    return;
                }
                retryShulkerPlacement("no_place_spot");
                return;
            }
        }

        if (unloadShulkerItemData == null) {
            ItemStack stack = getPlayerInventoryStack(unloadShulkerSlot);
            if (stack == null || stack.getAmount() <= 0) {
                emit("retrieve_shulker_unload_failed", Map.of("reason", "missing_shulker_stack"));
                finish(false, "missing_shulker_stack");
                return;
            }
            unloadShulkerItemData = ItemRegistry.REGISTRY.get(stack.getId());
        }

        if (unloadShulkerItemData == null) {
            emit("retrieve_shulker_unload_failed", Map.of("reason", "missing_shulker_item_data"));
            finish(false, "missing_shulker_item_data");
            return;
        }

        // The source chest close is queued behind the shift-click. A held-item request
        // targets inventory container 0 and will be skipped while that chest is open.
        if (CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() > 0) {
            return;
        }

        if (unloadHotbarRequest == null) {
            if (!moveShulkerToHotbar(unloadShulkerSlot)) {
                emit("retrieve_shulker_unload_failed", Map.of(
                    "reason", "hotbar_transfer_rejected"
                ));
                finish(false, "shulker_hotbar_transfer_rejected");
            }
            unloadTicks = 0;
            return;
        }
        if (!unloadHotbarRequest.isCompleted()) return;
        if (!unloadHotbarRequest.getNow()) {
            emit("retrieve_shulker_unload_failed", Map.of(
                "reason", "hotbar_transfer_rejected"
            ));
            finish(false, "shulker_hotbar_transfer_rejected");
            return;
        }
        if (!unloadShulkerReadyForPlacement()) {
            if (unloadTicks >= SHULKER_HELD_RESELECT_TICKS
                    && shulkerHeldReselects < MAX_SHULKER_HELD_RESELECTS) {
                shulkerHeldReselects++;
                // A completed SetHeldItem can still be superseded by another automation owner.
                // Re-issue selection against the live hotbar slot without moving the box again.
                unloadShulkerSlot = unloadHotbarRawSlot;
                unloadHotbarRequest = null;
                unloadTicks = 0;
                emit("retrieve_shulker_held_reselected", Map.of(
                    "attempt", shulkerHeldReselects,
                    "max_attempts", MAX_SHULKER_HELD_RESELECTS,
                    "expected_hotbar_slot", unloadHotbarRawSlot,
                    "held_hotbar_index", CACHE.getPlayerCache().getHeldItemSlot()
                ));
                return;
            }
            if (unloadTicks > SHULKER_PLACE_TIMEOUT_TICKS) {
                emit("retrieve_shulker_unload_failed", Map.of(
                    "reason", "hotbar_transfer_unverified",
                    "expected_hotbar_slot", unloadHotbarRawSlot,
                    "held_hotbar_index", CACHE.getPlayerCache().getHeldItemSlot()
                ));
                finish(false, "shulker_hotbar_transfer_unverified");
            }
            return;
        }
        if (strictBorrowedPickup() && !prioritizeBorrowedShulkerForPlacement()) return;
        unloadPhase = 1;
        unloadTicks = 0;
    }

    // Zenith's placeBlock searches by item ID, not components. Put the borrowed box first
    // in that search order so another same-color box cannot be placed in its stead.
    private boolean prioritizeBorrowedShulkerForPlacement() {
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        ItemStack borrowed = getPlayerInventoryStack(unloadHotbarRawSlot);
        if (player == null || borrowed == null || borrowed.getAmount() <= 0) {
            finish(false, "borrowed_shell_missing_before_place");
            return false;
        }
        if (!matchesBorrowedSource(borrowed)) {
            finish(false, "borrowed_source_inventory_identity_changed");
            return false;
        }
        int first = firstPlacementItemSlot(raw -> {
            ItemStack stack = player.getItemStack(raw);
            return stack != null && stack.getAmount() > 0 && stack.getId() == borrowed.getId();
        });
        if (first == unloadHotbarRawSlot) return true;
        if (first < 36 || first > 44) {
            emit("retrieve_shulker_unload_failed", Map.of(
                    "reason", "borrowed_shell_selection_conflict",
                    "first_matching_slot", first,
                    "borrowed_slot", unloadHotbarRawSlot));
            finish(false, "borrowed_shell_selection_conflict");
            return false;
        }
        int source = unloadHotbarRawSlot;
        try {
            RequestFuture request = INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this).priority(6000)
                    .actions(new MoveToHotbarSlot(source, MoveToHotbarAction.from(first - 36)),
                            new SetHeldItem(first - 36))
                    .build());
            if (request.isCompleted() && !request.getNow()) {
                finish(false, "borrowed_shell_priority_swap_rejected");
                return false;
            }
            unloadHotbarRequest = request;
            unloadHotbarRawSlot = first;
            unloadShulkerSlot = first;
            swapOwnedShulkerSlots(source, first);
            unloadTicks = 0;
            emit("retrieve_borrowed_shell_prioritized", Map.of(
                    "from_slot", source, "to_slot", first));
            return false;
        } catch (RuntimeException e) {
            finish(false, "borrowed_shell_priority_swap_rejected");
            return false;
        }
    }

    static int firstPlacementItemSlot(IntPredicate matches) {
        if (matches.test(45)) return 45;
        for (int raw = 36; raw <= 44; raw++) if (matches.test(raw)) return raw;
        for (int raw = 9; raw <= 35; raw++) if (matches.test(raw)) return raw;
        return -1;
    }

    private void tickUnloadPlace() {
        if (placedShulkerPos == null || unloadShulkerItemData == null) {
            emit("retrieve_shulker_unload_failed", Map.of("reason", "place_state_missing"));
            finish(false, "place_state_missing");
            return;
        }

        if (World.getBlock(placedShulkerPos[0], placedShulkerPos[1], placedShulkerPos[2]).name().contains("shulker_box")) {
            emit("retrieve_shulker_placed", Map.of(
                "placed_position", posString(placedShulkerPos)
            ));
            BARITONE.stop();
            unloadPhase = 2;
            containerOpenGate.reset();
            unloadTicks = 0;
            unloadPlaceFuture = null;
            return;
        }

        // Revalidate immediately before placement. The bot can still receive movement/teleport
        // corrections after opening the source, making the previously local cell stale.
        if (!isSafeShulkerPlaceSpot(placedShulkerPos)
                || !isWithinShulkerPlacementReach(placedShulkerPos)) {
            retryShulkerPlacement("placement_cell_stale");
            return;
        }

        if (unloadTicks > SHULKER_PLACE_TIMEOUT_TICKS) {
            retryShulkerPlacement("place_timeout");
            return;
        }

        if (!isTeleportCalm()) {
            return;
        }

        if (unloadPlaceFuture == null) {
            ItemStack borrowed = getPlayerInventoryStack(unloadHotbarRawSlot);
            if (borrowed == null || borrowed.getAmount() <= 0
                    || !isShulkerBoxItem(itemIdFromStack(borrowed))) {
                finish(false, "borrowed_shell_missing_before_place");
                return;
            }
            if (strictBorrowedPickup() && !matchesBorrowedSource(borrowed)) {
                finish(false, "borrowed_source_inventory_identity_changed");
                return;
            }
            if (strictBorrowedPickup()) {
                Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
                int first = player == null ? -1 : firstPlacementItemSlot(raw -> {
                    ItemStack stack = player.getItemStack(raw);
                    return stack != null && stack.getAmount() > 0 && stack.getId() == borrowed.getId();
                });
                if (first != unloadHotbarRawSlot) {
                    emit("retrieve_shulker_unload_failed", Map.of(
                            "reason", "borrowed_shell_selection_changed",
                            "first_matching_slot", first,
                            "borrowed_slot", unloadHotbarRawSlot));
                    finish(false, "borrowed_shell_selection_changed");
                    return;
                }
            }
            emit("retrieve_borrowed_shell_placement_planned", Map.of(
                    "placed_position", posString(placedShulkerPos),
                    "source_item_id", borrowedBoxItemId,
                    "source_contents", borrowedBoxContentsBefore));
            if (state != State.UNLOADING_SHULKER) return;
            unloadPlaceFuture = BaritoneCompat.placeBlock(
                placedShulkerPos[0],
                placedShulkerPos[1],
                placedShulkerPos[2],
                unloadShulkerItemData
            );
            return;
        }

        if (unloadPlaceFuture.isDone() && !unloadPlaceFuture.getNow()) {
            retryShulkerPlacement("place_rejected");
        }
    }

    private void retryShulkerPlacement(String reason) {
        BARITONE.stop();
        if (placedShulkerPos != null) {
            // A rejected cell will still look replaceable in the world snapshot. Remember it
            // for this borrowed box so each replan actually tries a different local worksite.
            rejectedShulkerPlacePositions.add(posKey(
                    placedShulkerPos[0], placedShulkerPos[1], placedShulkerPos[2]));
        }
        shulkerPlaceReplans++;
        if (shulkerPlaceReplans > MAX_SHULKER_PLACE_REPLANS) {
            emit("retrieve_shulker_unload_failed", Map.of(
                "reason", reason,
                "phase", unloadPhase,
                "place_replans", shulkerPlaceReplans
            ));
            finish(false, "shulker_" + reason);
            return;
        }

        emit("retrieve_shulker_place_replanned", Map.of(
            "reason", reason,
            "attempt", shulkerPlaceReplans,
            "max_attempts", MAX_SHULKER_PLACE_REPLANS
        ));
        placedShulkerPos = null;
        unloadPlaceFuture = null;
        unloadPhase = 0;
        unloadTicks = 0;
        shulkerInventorySearchDelay = SHULKER_PLACE_RETRY_DELAY_TICKS;
    }

    private void tickUnloadOpen() {
        if (placedShulkerPos == null) {
            emit("retrieve_shulker_unload_failed", Map.of("reason", "missing_placed_position"));
            finish(false, "missing_placed_position");
            return;
        }

        int liveContainerId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (shulkerOpenRetries > 0
                && containerDataReceived
                && openContainerId > 0
                && openContainerId != unloadSourceContainerId
                && openContainerId == liveContainerId
                && getOpenContainerSlotCount() == SHULKER_CONTAINER_SLOTS
                && World.getBlock(placedShulkerPos[0], placedShulkerPos[1], placedShulkerPos[2])
                        .name().contains("shulker_box")) {
            if (strictBorrowedPickup() && !borrowedBoxContentsBefore.equals(openShulkerContents())) {
                emit("retrieve_shulker_unload_failed", Map.of(
                        "reason", "borrowed_source_open_contents_mismatch"));
                finish(false, "borrowed_source_open_contents_mismatch");
                return;
            }
            // A right-click process can outlive the server's first open packet. Cancel it
            // before any inventory clicks, or a late click can reopen this shulker just
            // as the close-before-break guard runs.
            BARITONE.stop();
            emit("retrieve_shulker_opened", Map.of(
                "container_id", openContainerId,
                "placed_position", posString(placedShulkerPos)
            ));
            unloadPhase = 3;
            unloadTicks = 0;
            actionCooldown = 0;
            actionSlotIndex = 0;
            physicalStackSurveyed = false;
            return;
        }

        if (!prepareStandingContainerInteraction()) {
            unloadTicks = 0;
            return;
        }

        if (unloadTicks > SHULKER_OPEN_TIMEOUT_TICKS) {
            emit("retrieve_shulker_unload_failed", Map.of("reason", "open_timeout"));
            finish(false, "shulker_open_timeout");
            return;
        }

        if (!isTeleportCalm()) {
            return;
        }

        if (unloadTicks == 1 || unloadTicks % 10 == 0) {
            shulkerOpenRetries++;
            BARITONE.rightClickBlock(placedShulkerPos[0], placedShulkerPos[1], placedShulkerPos[2]);
        }
    }

    private void tickUnloadTakeContents() {
        if (actionCooldown > 0) {
            actionCooldown--;
            return;
        }

        // Keep this shulker open until every click of an exact-slot transfer completes.
        // Otherwise the next target can reuse its slot numbers while the split is pending.
        if (splitInProgress) {
            if (!tickSplitTake()) {
                finish(false, "shulker_split_container_lost");
                return;
            }
            actionCooldown = CLICK_COOLDOWN_TICKS;
            return;
        }

        if (containerSlots == null || openContainerId < 0) {
            emit("retrieve_shulker_unload_failed", Map.of("reason", "open_container_missing"));
            finish(false, "shulker_container_missing");
            return;
        }

        int chestSlots = getOpenContainerSlotCount();
        surveyPhysicalStacks(chestSlots);
        while (actionSlotIndex < chestSlots) {
            ItemStack stack = containerSlots[actionSlotIndex];
            if (stack != null && stack.getAmount() > 0) {
                String itemId = itemIdFromStack(stack);
                String requestKey = matchingRequestKey(itemId);
                Integer needed = requestKey == null ? null : remaining.get(requestKey);
                if (needed != null && needed > 0 && physicalStackAllowed(stack)
                        && hasInventoryRoom(stack)) {
                    if (exactSlotTransferRequired(activeRequestName, requestKey,
                            stack.getAmount(), needed)) {
                        beginSplitTake(actionSlotIndex, requestKey, stack.getAmount(), needed);
                        actionSlotIndex++;
                        actionCooldown = CLICK_COOLDOWN_TICKS;
                        return;
                    }

                    if (!quickMoveSlot(actionSlotIndex)) {
                        actionCooldown = CLICK_COOLDOWN_TICKS;
                        return;
                    }
                    successfulTransfers++;
                    remaining.put(requestKey, Math.max(0, needed - stack.getAmount()));
                    actionSlotIndex++;
                    actionCooldown = CLICK_COOLDOWN_TICKS;

                    emit("retrieve_progress", Map.of(
                        "moved_stacks", successfulTransfers,
                        "obtained_total", Math.max(0, initialRequestedTotal - getRemainingTotal())
                    ));

                    if (isComplete()) {
                        beginUnloadClose();
                    }
                    return;
                }
            }
            actionSlotIndex++;
        }

        beginUnloadClose();
    }

    private Map<String, Integer> openShulkerContents() {
        Map<String, Integer> contents = new HashMap<>();
        if (containerSlots == null || containerSlots.length < SHULKER_CONTAINER_SLOTS) return contents;
        for (int raw = 0; raw < SHULKER_CONTAINER_SLOTS; raw++) {
            ItemStack stack = containerSlots[raw];
            if (stack != null && stack.getAmount() > 0) {
                contents.merge(ItemIdentifier.getItemId(stack), stack.getAmount(), Integer::sum);
            }
        }
        return contents;
    }

    private void beginUnloadClose() {
        BARITONE.stop();
        unloadCloseContainerId = openContainerId > 0 ? openContainerId
                : CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        unloadPhase = 7;
        unloadTicks = 0;
    }

    enum BorrowedCloseAction { CLOSED, WAIT, RETRY, TIMEOUT, WRONG_CONTAINER }

    static BorrowedCloseAction borrowedCloseAction(int expectedId, int liveId,
                                                    int ticks, boolean inventoryBusy) {
        if (liveId <= 0) return BorrowedCloseAction.CLOSED;
        if (expectedId <= 0 || liveId != expectedId) {
            return BorrowedCloseAction.WRONG_CONTAINER;
        }
        if (ticks > SHULKER_CLOSE_TIMEOUT_TICKS) return BorrowedCloseAction.TIMEOUT;
        return !inventoryBusy && (ticks == 1 || ticks % 10 == 0)
                ? BorrowedCloseAction.RETRY : BorrowedCloseAction.WAIT;
    }

    private void tickUnloadClose() {
        if (placedShulkerPos == null) {
            emit("retrieve_shulker_unload_failed", Map.of("reason", "missing_close_position"));
            finish(false, "missing_close_position");
            return;
        }
        int liveId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        switch (borrowedCloseAction(unloadCloseContainerId, liveId, unloadTicks,
                INVENTORY.hasActiveRequest())) {
            case CLOSED -> {
                containerDataReceived = false;
                openContainerId = -1;
                containerSlots = null;
                unloadCloseContainerId = -1;
                unloadPhase = 4;
                unloadTicks = 0;
                emit("retrieve_shulker_closed", Map.of(
                        "placed_position", posString(placedShulkerPos)));
            }
            case RETRY -> {
                try {
                    INVENTORY.submit(InventoryActionRequest.builder()
                            .owner(this).priority(5000)
                            .actions(new CloseContainer(liveId)).build());
                } catch (RuntimeException ignored) {
                    // Retry after the inventory manager releases the previous click.
                }
            }
            case TIMEOUT -> {
                emit("retrieve_shulker_unload_failed", Map.of("reason", "close_timeout"));
                finish(false, "shulker_close_timeout");
            }
            case WRONG_CONTAINER -> {
                emit("retrieve_shulker_unload_failed", Map.of("reason", "wrong_container_before_break"));
                finish(false, "wrong_container_before_shulker_break");
            }
            case WAIT -> { }
        }
    }

    private void tickUnloadBreak() {
        if (placedShulkerPos == null) {
            emit("retrieve_shulker_unload_failed", Map.of("reason", "missing_break_position"));
            finish(false, "missing_break_position");
            return;
        }

        if (CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() > 0) {
            emit("retrieve_shulker_unload_failed", Map.of("reason", "container_reopened_before_break"));
            finish(false, "container_reopened_before_shulker_break");
            return;
        }

        if (BlockCompat.isAir(World.getBlock(placedShulkerPos[0], placedShulkerPos[1], placedShulkerPos[2]))) {
            emit("retrieve_shulker_broken", Map.of(
                "placed_position", posString(placedShulkerPos)
            ));
            unloadPhase = 5;
            unloadTicks = 0;
            unloadBreakFuture = null;
            return;
        }

        if (unloadTicks > SHULKER_BREAK_TIMEOUT_TICKS) {
            emit("retrieve_shulker_unload_failed", Map.of("reason", "break_timeout"));
            finish(false, "shulker_break_timeout");
            return;
        }

        if (!isTeleportCalm()) {
            return;
        }

        if (unloadBreakFuture == null) {
            if (strictBorrowedPickup() && shulkerCountBeforeBreak < 0) {
                borrowedBoxContentsAfter = borrowedContentsAfterTake(
                        borrowedBoxContentsBefore, borrowedRequestItem,
                        borrowedRemainingBefore,
                        borrowedRequestItem == null ? -1
                                : remaining.getOrDefault(borrowedRequestItem, 0));
                if (borrowedBoxContentsAfter == null) {
                    emit("retrieve_shulker_unload_failed", Map.of(
                            "reason", "borrowed_contents_after_take_unverified"));
                    finish(false, "borrowed_contents_after_take_unverified");
                    return;
                }
                if (playerInventoryEmptySlots() == 0) {
                    emit("retrieve_shulker_unload_failed", Map.of(
                            "reason", "no_inventory_slot_for_borrowed_box"));
                    finish(false, "no_inventory_slot_for_borrowed_box");
                    return;
                }
                shulkerCountBeforeBreak = inventoryShulkerCount();
                borrowedExactCountBeforeBreak = countBorrowedBoxInInventory(
                        borrowedBoxContentsAfter);
                itemEntitiesBeforeBreak = Set.copyOf(
                        CACHE.getEntityCache().getEntities().keySet());
                emit("retrieve_borrowed_pickup_baseline", Map.of(
                        "inventory_shulkers", shulkerCountBeforeBreak,
                        "matching_boxes", borrowedExactCountBeforeBreak,
                        "empty_inventory_slots", playerInventoryEmptySlots(),
                        "tracked_items", itemEntitiesBeforeBreak.size(),
                        "expected_contents", borrowedBoxContentsAfter));
            }
            unloadBreakFuture = BaritoneCompat.breakBlock(
                placedShulkerPos[0], placedShulkerPos[1], placedShulkerPos[2], true);
            return;
        }

        if (unloadBreakFuture.isDone() && !unloadBreakFuture.getNow()) {
            emit("retrieve_shulker_unload_failed", Map.of("reason", "break_rejected"));
            finish(false, "shulker_break_rejected");
        }
    }

    private void tickUnloadResume() {
        if (strictBorrowedPickup()) {
            if (shulkerCountBeforeBreak < 0) {
                emit("retrieve_shulker_unload_failed", Map.of("reason", "pickup_baseline_missing"));
                finish(false, "shulker_pickup_baseline_missing");
                return;
            }
            int inventoryBoxes = inventoryShulkerCount();
            int matchingBoxes = countBorrowedBoxInInventory(borrowedBoxContentsAfter);
            if (inventoryBoxes <= shulkerCountBeforeBreak
                    || matchingBoxes <= borrowedExactCountBeforeBreak) {
                if (unloadTicks > KIT_SHULKER_PICKUP_TIMEOUT_TICKS) {
                    emit("retrieve_shulker_unload_failed", Map.of(
                            "reason", "pickup_unverified",
                            "drop_seen", borrowedDropSeen,
                            "last_drop_entity_id", borrowedDropEntityId,
                            "inventory_shulkers", inventoryBoxes,
                            "matching_boxes", matchingBoxes));
                    finish(false, "shulker_pickup_unverified");
                    return;
                }
                if (inventoryBoxes > shulkerCountBeforeBreak) {
                    if (++borrowedPickupMismatchTicks > 20) {
                        emit("retrieve_shulker_unload_failed", Map.of(
                                "reason", "borrowed_box_identity_mismatch",
                                "inventory_shulkers", inventoryBoxes,
                                "matching_boxes", matchingBoxes));
                        finish(false, "borrowed_box_identity_mismatch");
                    }
                    return;
                }
                borrowedPickupMismatchTicks = 0;
                BorrowedShulkerDropGoal.Target observed = observedBorrowedDropGoal();
                if (observed != null) {
                    boolean moved = observed.entityId() != borrowedDropEntityId
                            || !java.util.Arrays.equals(observed.standing(), borrowedDropLastTarget);
                    boolean pathActive = BARITONE.getCustomGoalProcess().isActive();
                    if (moved || ShulkerPickupSweep.shouldIssuePath(
                            unloadTicks, lastPickupSweepTick, pathActive)) {
                        if (moved && pathActive) BARITONE.stop();
                        borrowedDropEntityId = observed.entityId();
                        borrowedDropLastTarget = observed.standing();
                        lastPickupSweepTick = unloadTicks;
                        BARITONE.pathTo(new GoalBlock(new BlockPos(
                                observed.standing()[0], observed.standing()[1],
                                observed.standing()[2])));
                        if (moved) emit("retrieve_borrowed_drop_targeted", Map.of(
                                "entity_id", observed.entityId(),
                                "standing_position", posString(observed.standing()),
                                "metadata_kind", observed.metadataKind()));
                    }
                    return;
                }
                if (ShulkerPickupSweep.shouldIssuePath(unloadTicks, lastPickupSweepTick,
                        BARITONE.getCustomGoalProcess().isActive())) {
                    for (int checked = 0; checked < ShulkerPickupSweep.targetCount(); checked++) {
                        int[] target = ShulkerPickupSweep.target(
                                placedShulkerPos, pickupSweepAttempt++);
                        if (!safeBorrowedPickupStandingCell(target)) continue;
                        lastPickupSweepTick = unloadTicks;
                        BARITONE.pathTo(new GoalBlock(new BlockPos(target[0], target[1], target[2])));
                        break;
                    }
                }
                return;
            }
            if (!borrowedPickupVerified) {
                borrowedPickupVerified = true;
                BARITONE.stop();
                int remainingAfter = borrowedRequestItem == null ? -1
                        : remaining.getOrDefault(borrowedRequestItem, 0);
                Map<String, Integer> after = borrowedContentsAfterTake(
                        borrowedBoxContentsBefore, borrowedRequestItem,
                        borrowedRemainingBefore, remainingAfter);
                int taken = borrowedRemainingBefore - remainingAfter;
                if (after == null) {
                    emit("retrieve_borrowed_shell_recovery_unverified", Map.of(
                            "placed_position", posString(placedShulkerPos),
                            "taken_quantity", taken,
                            "source_quantity", borrowedRequestItem == null ? 0
                                    : borrowedBoxContentsBefore.getOrDefault(borrowedRequestItem, 0)));
                    finish(false, "borrowed_shell_contents_unverified");
                    return;
                }
                emit("retrieve_borrowed_shell_recovered", Map.of(
                        "placed_position", posString(placedShulkerPos),
                        "source_position", borrowedSourceOrigin == null
                                ? "inventory" : posString(borrowedSourceOrigin),
                        "source_item_id", borrowedBoxItemId,
                        "source_contents_after", Map.copyOf(after),
                        "taken_quantity", taken,
                        "pickup_evidence", "exact_inventory_box_net_increase",
                        "drop_seen", borrowedDropSeen));
            }
        }
        if (unloadTicks < SHULKER_PICKUP_WAIT_TICKS) {
            return;
        }

        resetUnloadState();
        if (isComplete()) {
            finish(true, "complete");
        } else {
            advanceToNextTarget(null);
        }
    }

    private boolean strictBorrowedPickup() {
        return activeRequestName != null && activeRequestName.startsWith("kit_fulfillment:");
    }

    static Map<String, Integer> borrowedContentsAfterTake(Map<String, Integer> before,
            String itemId, int remainingBefore, int remainingAfter) {
        if (before == null || itemId == null || remainingBefore < 0
                || remainingAfter < 0 || remainingAfter > remainingBefore) return null;
        int taken = remainingBefore - remainingAfter;
        int available = before.getOrDefault(itemId, 0);
        if (taken > available) return null;
        Map<String, Integer> after = new LinkedHashMap<>(before);
        if (taken == available) after.remove(itemId);
        else if (taken > 0) after.put(itemId, available - taken);
        return Map.copyOf(after);
    }

    private int inventoryShulkerCount() {
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null) return 0;
        int total = 0;
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack != null && stack.getAmount() > 0
                    && isShulkerBoxItem(itemIdFromStack(stack))) total += stack.getAmount();
        }
        return total;
    }

    private int playerInventoryEmptySlots() {
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null) return 0;
        int empty = 0;
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack == null || stack.getAmount() <= 0) empty++;
        }
        return empty;
    }

    private int countBorrowedBoxInInventory(Map<String, Integer> expectedContents) {
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null || borrowedBoxItemId == null || expectedContents == null) return 0;
        int count = 0;
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack != null && stack.getAmount() > 0
                    && borrowedBoxItemId.equals(ItemIdentifier.getItemId(stack))
                    && expectedContents.equals(ItemIdentifier.readShulkerContents(stack))) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private BorrowedShulkerDropGoal.Target observedBorrowedDropGoal() {
        if (placedShulkerPos == null) return null;
        List<BorrowedShulkerDropGoal.Drop> drops = new ArrayList<>();
        for (var entry : CACHE.getEntityCache().getEntities().entrySet()) {
            var entity = entry.getValue();
            if (entity == null || entity.isRemoved()
                    || entity.getEntityType() != EntityType.ITEM) continue;
            var metadata = entity.getMetadata().get(8);
            if (metadata == null || !(metadata.getValue() instanceof ItemStack stack)
                    || stack.getAmount() != 1) continue;
            String itemId = ItemIdentifier.getItemId(stack);
            if (!isShulkerBoxItem(itemId)) continue;
            double dx = entity.getX() - (placedShulkerPos[0] + 0.5);
            double dy = entity.getY() - (placedShulkerPos[1] + 0.5);
            double dz = entity.getZ() - (placedShulkerPos[2] + 0.5);
            if (dx * dx + dy * dy + dz * dz > 36.0) continue;
            Map<String, Integer> contents = ItemIdentifier.readShulkerContents(stack);
            drops.add(new BorrowedShulkerDropGoal.Drop(entry.getKey(), itemId,
                    contents, entity.getX(), entity.getY(), entity.getZ()));
            if (!itemEntitiesBeforeBreak.contains(entry.getKey())
                    && observedBorrowedDropIds.add(entry.getKey())) {
                borrowedDropSeen = true;
                emit("retrieve_borrowed_drop_seen", Map.of(
                        "entity_id", entry.getKey(), "item_id", itemId,
                        "contents", contents,
                        "position", entity.getX() + ", " + entity.getY() + ", " + entity.getZ()));
            }
        }
        return BorrowedShulkerDropGoal.select(drops, itemEntitiesBeforeBreak,
                borrowedBoxItemId, borrowedBoxContentsAfter,
                borrowedBoxContentsBefore, placedShulkerPos,
                this::safeBorrowedPickupStandingCell);
    }

    private boolean safeBorrowedPickupStandingCell(int[] target) {
        return target != null && World.isInWorldBounds(target[0], target[1], target[2])
                && World.isChunkLoadedBlockPos(target[0], target[2])
                && World.getChunkSection(target[0], target[1], target[2]) != null
                && BlockCompat.canReplace(World.getBlock(target[0], target[1], target[2]))
                && BlockCompat.canReplace(World.getBlock(target[0], target[1] + 1, target[2]))
                && BlockCompat.isSolid(target[0], target[1] - 1, target[2]);
    }

    private void advanceToNextTarget(String reason) {
        if (currentTarget != null && !isComplete() && reason != null) {
            if ("container_exhausted".equals(reason)) {
                emit("retrieve_target_exhausted", Map.of(
                    "moved_stacks", successfulTransfers,
                    "obtained_total", Math.max(0, initialRequestedTotal - getRemainingTotal())
                ));
            } else {
                emit("retrieve_target_failed", Map.of("failure_reason", reason));
            }
        }

        closeCurrentContainer();

        if (isComplete()) {
            finish(true, "complete");
            return;
        }

        int[] nextTarget;
        do {
            nextTarget = targetQueue.poll();
        } while (nextTarget != null && !isAllowedTarget(nextTarget[0], nextTarget[1], nextTarget[2]));

        if (nextTarget == null) {
            finish(false, "no_more_targets");
            return;
        }

        currentTarget = nextTarget;
        transfersAtTargetStart = successfulTransfers;
        targetOpenAttempted = false;
        openingClickTarget = nextTarget;
        partnerHalfProbed = false;
        approachStances.clear();
        approachStanceIndex = 0;
        state = State.WALKING;
        openWaitTicks = 0;
        actionCooldown = 0;
        actionSlotIndex = 0;
        walkingTicks = 0;
        stationaryWalkTicks = 0;
        foodPathRearmed = false;
        var player = CACHE.getPlayerCache();
        lastWalkX = player.getX();
        lastWalkY = player.getY();
        lastWalkZ = player.getZ();
        containerDataReceived = false;
        openContainerId = -1;
        containerSlots = null;
        physicalStackSurveyed = false;
        previousGuiQuietTicks = reason == null ? 0 : PREVIOUS_GUI_QUIET_TICKS;
        previousGuiCloseTicks = 0;
        previousGuiId = -1;
        BARITONE.stop();
        planApproachStances();
        emit("retrieve_target_selected", Map.of(
            "candidate_targets_remaining", targetQueue.size()
        ));
        // A prior click can open its GUI after the timeout tick. Do not begin the
        // next path until both the close acknowledgement and a quiet window pass.
        if (previousGuiQuietTicks == 0
                && !RetrievalOpenPolicy.shouldCloseBeforeWalking(
                        CACHE.getPlayerCache().getInventoryCache().getOpenContainerId())) {
            pathToTarget();
        }
    }

    private void finish(boolean completed, String reason) {
        BARITONE.stop();
        restoreWorksiteNavigation();
        closeCurrentContainer();
        restorePlaceBlockSneak();
        state = State.DONE;
        resetUnloadState();
        if (completed) {
            emit("retrieve_completed", Map.of(
                "moved_stacks", successfulTransfers,
                "obtained_total", Math.max(0, initialRequestedTotal - getRemainingTotal())
            ));
        } else {
            emit("retrieve_incomplete", Map.of(
                "reason", reason,
                "moved_stacks", successfulTransfers,
                "obtained_total", Math.max(0, initialRequestedTotal - getRemainingTotal())
            ));
        }
    }

    private void resetState() {
        restoreWorksiteNavigation();
        targetQueue.clear();
        doubleChestPartners.clear();
        currentTarget = null;
        openingClickTarget = null;
        partnerHalfProbed = false;
        approachStances.clear();
        approachStanceIndex = 0;
        previousGuiQuietTicks = 0;
        previousGuiCloseTicks = 0;
        previousGuiId = -1;
        remaining.clear();
        kitPhysicalStackFilter = null;
        physicalStackSurvey = null;
        physicalStackSurveyed = false;
        directContentsOnly = false;
        openWaitTicks = 0;
        actionCooldown = 0;
        actionSlotIndex = 0;
        walkingTicks = 0;
        stationaryWalkTicks = 0;
        foodPathRearmed = false;
        preferNearbyFoodTargets = false;
        consecutiveFailures = 0;
        initialRequestedTotal = 0;
        successfulTransfers = 0;
        transfersAtTargetStart = 0;
        targetOpenAttempted = false;
        containerDataReceived = false;
        openContainerId = -1;
        containerSlots = null;
        serverSession = null;
        activeRequestName = null;
        state = State.IDLE;
        lastTeleportQueueSize = -1;
        ticksSinceTeleportQueueChange = 0;
        resetUnloadState();
        resetSplit();
        ownedShulkerSlots.clear();
        pendingOwnedShulkerFingerprint = null;
        pendingOwnedShulkerCandidateSlots.clear();
        activeRegionMin = null;
        activeRegionMax = null;
        excludedTargets.clear();
        fallbackWorksiteStation = null;
        fallbackWorksitePlacePos = null;
        worksiteCargoFloorY = 0;
    }

    private void setTargetPolicy(int[] pos1, int[] pos2, Set<Long> excludedPositions) {
        if (pos1 != null && pos2 != null) {
            activeRegionMin = new int[]{
                Math.min(pos1[0], pos2[0]), Math.min(pos1[1], pos2[1]), Math.min(pos1[2], pos2[2])
            };
            activeRegionMax = new int[]{
                Math.max(pos1[0], pos2[0]), Math.max(pos1[1], pos2[1]), Math.max(pos1[2], pos2[2])
            };
        }
        if (excludedPositions != null) {
            excludedTargets.addAll(excludedPositions);
        }
    }

    private boolean isAllowedTarget(int x, int y, int z) {
        if (activeRegionMin != null && (x < activeRegionMin[0] || x > activeRegionMax[0]
                || y < activeRegionMin[1] || y > activeRegionMax[1]
                || z < activeRegionMin[2] || z > activeRegionMax[2])) {
            return false;
        }
        return !excludedTargets.contains(posKey(x, y, z));
    }

    private static long posKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
            | ((long) y & 0xFFFL) << 26
            | ((long) z & 0x3FFFFFFL);
    }

    private void resetUnloadState() {
        restoreWorksiteNavigation();
        unloadPhase = 0;
        unloadTicks = 0;
        unloadTotalTicks = 0;
        unloadShulkerSlot = -1;
        unloadChestSlot = -1;
        unloadSourceContainerId = -1;
        unloadCloseContainerId = -1;
        placedShulkerPos = null;
        shulkerCountBeforeBreak = -1;
        borrowedExactCountBeforeBreak = -1;
        borrowedBoxContentsAfter = Map.of();
        itemEntitiesBeforeBreak = Set.of();
        observedBorrowedDropIds.clear();
        borrowedDropEntityId = -1;
        borrowedDropLastTarget = null;
        borrowedDropSeen = false;
        borrowedPickupMismatchTicks = 0;
        pickupSweepAttempt = 0;
        lastPickupSweepTick = -1;
        borrowedPickupVerified = false;
        borrowedSourceOrigin = null;
        borrowedRequestItem = null;
        borrowedRemainingBefore = 0;
        borrowedBoxItemId = null;
        borrowedBoxContentsBefore = Map.of();
        unloadShulkerItemData = null;
        unloadPlaceFuture = null;
        unloadBreakFuture = null;
        unloadHotbarRequest = null;
        unloadHotbarRawSlot = -1;
        shulkerInventorySearchDelay = 0;
        shulkerOpenRetries = 0;
        shulkerPlaceReplans = 0;
        shulkerHeldReselects = 0;
        worksiteFallbackAttempted = false;
        worksiteCargoFloorY = 0;
        if (discoveredWorksiteFallback) {
            fallbackWorksiteStation = null;
            fallbackWorksitePlacePos = null;
            discoveredWorksiteFallback = false;
        }
        rejectedShulkerPlacePositions.clear();
    }

    static int foodAccessBand(double playerX, double playerY, double playerZ,
                              ContainerEntry entry) {
        if (ContainerApproach.isAtAccessPosition(
                playerX, playerY, playerZ, entry.x(), entry.y(), entry.z())) return 0;
        if (ContainerApproach.isWithinVanillaReach(
                playerX, playerY, playerZ, entry.x(), entry.y(), entry.z())) return 1;
        return 2;
    }

    private int matchScore(ContainerEntry entry) {
        if (directContentsOnly) return directMatchScore(entry);
        int score = 0;
        for (var kv : remaining.entrySet()) {
            int need = kv.getValue();
            if (need <= 0) continue;
            int have = EMPTY_SHELL_REQUEST.equals(kv.getKey())
                    ? emptyPhysicalShulkers(entry) : amountMatchingForCurrentJob(entry.items(), kv.getKey());
            if (have > 0) {
                score += Math.min(need, have);
            }
        }
        return score;
    }

    // Count loose items only; prefer direct grabs when total scores tie.
    private int directMatchScore(ContainerEntry entry) {
        Map<String, Integer> direct = directlyStoredItems(entry);

        int score = 0;
        for (var kv : remaining.entrySet()) {
            int need = kv.getValue();
            if (need <= 0) continue;
            int have = EMPTY_SHELL_REQUEST.equals(kv.getKey())
                    ? emptyPhysicalShulkers(entry) : amountMatchingForCurrentJob(direct, kv.getKey());
            if (have > 0) {
                score += Math.min(need, have);
            }
        }
        return score;
    }

    static Map<String, Integer> directlyStoredItems(ContainerEntry entry) {
        Map<String, Integer> direct = new HashMap<>(entry.items());
        for (ContainerEntry.ShulkerDetail sd : entry.shulkerDetails()) {
            for (var sdEntry : sd.items().entrySet()) {
                direct.computeIfPresent(sdEntry.getKey(), (k, v) -> {
                    int remainingAmount = v - sdEntry.getValue();
                    return remainingAmount > 0 ? remainingAmount : null;
                });
            }
        }
        return direct;
    }

    static int emptyPhysicalShulkers(ContainerEntry entry) {
        if (entry == null) return 0;
        return (int) entry.shulkerDetails().stream()
                .filter(detail -> detail.isPhysicalInstance() && detail.items().isEmpty())
                .count();
    }

    private boolean containsWantedContents(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) return false;
        String itemId = itemIdFromStack(stack);
        if (!isShulkerBoxItem(itemId)) return false;

        if (kitPhysicalStackFilter != null) {
            for (ItemStack inner : ItemIdentifier.readShulkerPhysicalStacks(stack)) {
                String requestKey = matchingRequestKey(itemIdFromStack(inner));
                if (requestKey != null && remaining.getOrDefault(requestKey, 0) > 0
                        && physicalStackAllowed(inner)) return true;
            }
            return false;
        }

        for (var entry : ItemIdentifier.readShulkerContents(stack).entrySet()) {
            // Let suffixed tool variants satisfy base-item requests.
            String requestKey = matchingRequestKey(entry.getKey());
            Integer needed = requestKey == null ? null : remaining.get(requestKey);
            if (needed != null && needed > 0 && entry.getValue() > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean physicalStackAllowed(ItemStack stack) {
        return kitPhysicalStackFilter == null || kitPhysicalStackFilter.test(stack);
    }

    private boolean isWanted(String itemId) {
        return matchingRequestKey(itemId) != null;
    }

    private String matchingRequestKey(String observedItemId) {
        if (strictBorrowedPickup()) {
            Integer needed = remaining.get(observedItemId);
            return needed != null && needed > 0 ? observedItemId : null;
        }
        return requestKeyForItem(remaining, observedItemId);
    }

    private int amountMatchingForCurrentJob(Map<String, Integer> indexedItems, String requestItemId) {
        return strictBorrowedPickup()
                ? Math.max(0, indexedItems.getOrDefault(requestItemId, 0))
                : amountMatchingRequest(indexedItems, requestItemId);
    }

    /** A plain request accepts named/enchantment variants; a qualified request stays exact. */
    static String requestKeyForItem(Map<String, Integer> requests, String observedItemId) {
        if (requests == null || observedItemId == null) return null;
        Integer exact = requests.get(observedItemId);
        if (exact != null && exact > 0) return observedItemId;
        for (var entry : requests.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0
                    && ItemProfiles.matchesRequest(entry.getKey(), observedItemId)) {
                return entry.getKey();
            }
        }
        String base = ItemIdentifier.baseItemId(observedItemId);
        Integer baseNeed = requests.get(base);
        return baseNeed != null && baseNeed > 0 ? base : null;
    }

    static int amountMatchingRequest(Map<String, Integer> indexedItems, String requestItemId) {
        if (indexedItems == null || requestItemId == null) return 0;
        if (requestItemId.startsWith("profile:")) {
            return indexedItems.entrySet().stream()
                    .filter(entry -> ItemProfiles.matchesRequest(requestItemId, entry.getKey()))
                    .mapToInt(entry -> Math.max(0, entry.getValue() == null ? 0 : entry.getValue()))
                    .sum();
        }
        String requestBase = ItemIdentifier.baseItemId(requestItemId);
        if (!requestItemId.equals(requestBase)) {
            return Math.max(0, indexedItems.getOrDefault(requestItemId, 0));
        }
        int total = 0;
        for (var entry : indexedItems.entrySet()) {
            if (requestBase.equals(ItemIdentifier.baseItemId(entry.getKey()))
                    && entry.getValue() != null && entry.getValue() > 0) {
                total += entry.getValue();
            }
        }
        return total;
    }

    private boolean isComplete() {
        return remaining.values().stream().noneMatch(v -> v != null && v > 0);
    }

    private void updateTeleportStability() {
        int queueSize = CACHE.getPlayerCache().getTeleportQueue().size();
        if (queueSize != lastTeleportQueueSize) {
            lastTeleportQueueSize = queueSize;
            ticksSinceTeleportQueueChange = 0;
        } else {
            ticksSinceTeleportQueueChange++;
        }
    }

    private boolean isTeleportCalm() {
        return CACHE.getPlayerCache().getTeleportQueue().isEmpty()
            && ticksSinceTeleportQueueChange >= TELEPORT_CALM_TICKS;
    }

    private void pathToTarget() {
        if (currentTarget == null) return;
        if (!approachStances.isEmpty() && approachStanceIndex < approachStances.size()) {
            BARITONE.pathTo(new GoalBlock(approachStances.get(approachStanceIndex)));
        } else {
            BARITONE.pathTo(new GoalGetToBlock(
                    new BlockPos(currentTarget[0], currentTarget[1], currentTarget[2])));
        }
    }

    private void surveyPhysicalStacks(int containerSlotCount) {
        if (physicalStackSurveyed || physicalStackSurvey == null || containerSlots == null) return;
        List<ItemStack> observed = new ArrayList<>();
        for (int raw = 0; raw < Math.min(containerSlotCount, containerSlots.length); raw++) {
            ItemStack stack = containerSlots[raw];
            if (stack == null || stack.getAmount() <= 0) continue;
            observed.add(stack);
            if (isShulkerBoxItem(itemIdFromStack(stack))) {
                observed.addAll(ItemIdentifier.readShulkerPhysicalStacks(stack));
            }
        }
        physicalStackSurvey.accept(List.copyOf(observed));
        physicalStackSurveyed = true;
    }

    private void planApproachStances() {
        if (currentTarget == null) return;
        var player = CACHE.getPlayerCache();
        var candidates = new java.util.LinkedHashSet<BlockPos>(
                ContainerAccessPlanner.alternateStances(player.getX(), player.getY(), player.getZ(),
                        currentTarget[0], currentTarget[1], currentTarget[2], Set.of()));
        int[] partner = doubleChestPartners.get(posKey(
                currentTarget[0], currentTarget[1], currentTarget[2]));
        if (partner != null) candidates.addAll(ContainerAccessPlanner.alternateStances(
                player.getX(), player.getY(), player.getZ(),
                partner[0], partner[1], partner[2], Set.of()));
        approachStances.addAll(candidates);
        approachStances.sort(Comparator.comparingDouble(stance ->
                Math.pow(player.getX() - (stance.x() + 0.5), 2)
                        + Math.pow(player.getY() - stance.y(), 2)
                        + Math.pow(player.getZ() - (stance.z() + 0.5), 2)));
        if (approachStances.size() > MAX_APPROACH_STANCES) {
            approachStances.subList(MAX_APPROACH_STANCES, approachStances.size()).clear();
        }
    }

    private boolean advanceApproachStance() {
        if (approachStanceIndex + 1 >= approachStances.size()) return false;
        approachStanceIndex++;
        stationaryWalkTicks = 0;
        BARITONE.stop();
        emit("retrieve_approach_retry", Map.of(
                "stance", approachStances.get(approachStanceIndex).toString(),
                "attempt", approachStanceIndex + 1));
        pathToTarget();
        return true;
    }

    private boolean selectVisibleClickHalf() {
        if (currentTarget == null) return false;
        var player = CACHE.getPlayerCache();
        boolean primaryVisible = ContainerAccessPlanner.visible(
                player.getX(), player.getY(), player.getZ(),
                currentTarget[0], currentTarget[1], currentTarget[2]);
        int[] partner = doubleChestPartners.get(posKey(
                currentTarget[0], currentTarget[1], currentTarget[2]));
        boolean partnerVisible = partner != null && verifiedReachablePartner(partner);
        int selected = RetrievalOpenPolicy.preferredHalf(primaryVisible, partnerVisible);
        if (selected < 0) return false;
        openingClickTarget = selected == 0 ? currentTarget : partner;
        partnerHalfProbed = selected == 1;
        if (selected == 1) emit("retrieve_partner_half_selected", Map.of(
                "partner_position", posString(partner),
                "reason", "primary_not_visible"));
        return true;
    }

    private boolean waitForPreviousGuiClose() {
        int liveId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (RetrievalOpenPolicy.shouldCloseBeforeWalking(liveId)) {
            BARITONE.stop();
            previousGuiQuietTicks = PREVIOUS_GUI_QUIET_TICKS;
            if (previousGuiId != liveId) {
                previousGuiId = liveId;
                previousGuiCloseTicks = 0;
                emit("retrieve_previous_gui_detected", Map.of("container_id", liveId));
            }
            if (++previousGuiCloseTicks > PREVIOUS_GUI_CLOSE_TIMEOUT_TICKS) {
                finish(false, "previous_gui_close_timeout");
                return true;
            }
            if ((previousGuiCloseTicks == 1 || previousGuiCloseTicks % 10 == 0)
                    && !INVENTORY.hasActiveRequest()) {
                try {
                    INVENTORY.submit(InventoryActionRequest.builder()
                            .owner(this).priority(5000)
                            .actions(new CloseContainer(liveId)).build());
                } catch (RuntimeException ignored) {
                    // Retry only while the same live GUI remains open.
                }
            }
            return true;
        }
        if (previousGuiId > 0) {
            emit("retrieve_previous_gui_closed", Map.of("container_id", previousGuiId));
            previousGuiId = -1;
            previousGuiCloseTicks = 0;
            containerDataReceived = false;
            openContainerId = -1;
            containerSlots = null;
        }
        if (previousGuiQuietTicks > 0) {
            previousGuiQuietTicks--;
            return true;
        }
        return false;
    }

    private boolean isAtTargetAccessPosition() {
        if (currentTarget == null) return false;
        var playerCache = CACHE.getPlayerCache();
        return ContainerApproach.isAtAccessPosition(
            playerCache.getX(), playerCache.getY(), playerCache.getZ(),
            currentTarget[0], currentTarget[1], currentTarget[2]);
    }

    private void interactWithTarget() {
        if (openingClickTarget == null) return;
        BARITONE.rightClickBlock(openingClickTarget[0], openingClickTarget[1], openingClickTarget[2]);
    }

    private boolean verifiedReachablePartner(int[] partner) {
        try {
            var identity = DoubleChestIdentity.resolve(
                    currentTarget[0], currentTarget[1], currentTarget[2], true);
            if (!identity.identityKnown() || identity.blocks().size() != 2
                    || identity.blocks().stream().noneMatch(block ->
                        java.util.Arrays.equals(block, partner))) return false;
            var player = CACHE.getPlayerCache();
            return ContainerAccessPlanner.visible(player.getX(), player.getY(), player.getZ(),
                    partner[0], partner[1], partner[2]);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean prepareStandingContainerInteraction() {
        setPlaceBlockSneak(false);
        if (containerOpenGate.tick(BOT.isSneaking())) return true;
        INPUTS.submit(InputRequest.builder()
            .owner(this)
            .input(Input.builder().sneaking(false).build())
            .priority(SneakReleaseGate.INPUT_PRIORITY)
            .build());
        return false;
    }

    private void closeCurrentContainer() {
        int containerId = openContainerId > 0
            ? openContainerId
            : CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (containerId > 0) {
            try {
                INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this)
                    .actions(new CloseContainer(containerId))
                    .priority(5000)
                    .build());
            } catch (Exception ignored) {
            }
        }
        containerDataReceived = false;
        openContainerId = -1;
        containerSlots = null;
    }

    private int getOpenContainerSlotCount() {
        if (containerSlots == null) return 0;
        return Math.max(0, containerSlots.length - 36);
    }

    // Use Zenith's queue for current action/container IDs; false means the click was rejected.
    private boolean quickMoveSlot(int slot) {
        if (openContainerId < 0) return false;

        try {
            var future = INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this)
                .priority(6000)
                .actions(new ShiftClick(openContainerId, slot, ShiftClickItemAction.LEFT_CLICK))
                .build());
            return !(future.isDone() && !future.isAccepted());
        } catch (Exception ignored) {
            return false;
        }
    }


    private boolean hasInventoryRoom(ItemStack incoming) {
        var invCache = CACHE.getPlayerCache().getInventoryCache();
        var playerContainer = invCache.getPlayerInventory();
        if (playerContainer == null) return false;

        return hasPlayerInventoryRoomFor(playerContainer, incoming);
    }

    static boolean hasPlayerInventoryRoomFor(Container playerContainer, ItemStack incoming) {
        if (playerContainer == null || incoming == null || incoming.getAmount() <= 0) return false;
        var incomingData = ItemRegistry.REGISTRY.get(incoming.getId());
        int maxStack = incomingData == null ? 1 : Math.max(1, incomingData.stackSize());

        // Player inventory: main 9-35, hotbar 36-44.
        for (int i = 9; i < 45; i++) {
            ItemStack stack = playerContainer.getItemStack(i);
            if (stack == null || stack.getAmount() == 0) return true;
            if (stack.getId() == incoming.getId()
                    && stack.getAmount() < maxStack
                    && java.util.Objects.equals(
                            stack.getDataComponents(), incoming.getDataComponents())) {
                return true;
            }
        }
        return false;
    }

    private int findWantedShulkerSlot() {
        var playerContainer = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (playerContainer == null) return -1;

        for (int slot = 36; slot <= 44; slot++) {
            if (matchesWantedShulker(playerContainer.getItemStack(slot))) return slot;
        }
        for (int slot = 9; slot <= 35; slot++) {
            if (matchesWantedShulker(playerContainer.getItemStack(slot))) return slot;
        }
        ItemStack offhand = playerContainer.getItemStack(45);
        if (matchesWantedShulker(offhand)) return 45;
        return -1;
    }

    /** -2 means multiple indistinguishable borrowed boxes; never guess which one moved. */
    private int findUniqueBorrowedSourceSlot() {
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null) return -1;
        return uniqueBorrowedSourceSlot(raw -> matchesBorrowedSource(player.getItemStack(raw)));
    }

    static int uniqueBorrowedSourceSlot(IntPredicate matches) {
        int found = -1;
        for (int raw = 9; raw <= 45; raw++) {
            if (!matches.test(raw)) continue;
            if (found >= 0) return -2;
            found = raw;
        }
        return found;
    }

    private boolean matchesBorrowedSource(ItemStack stack) {
        return stack != null && stack.getAmount() == 1
                && borrowedBoxItemId != null
                && borrowedBoxItemId.equals(ItemIdentifier.getItemId(stack))
                && borrowedBoxContentsBefore.equals(ItemIdentifier.readShulkerContents(stack));
    }

    private int findInventoryShulkerWithWantedContents() {
        var playerContainer = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (playerContainer == null) return -1;

        for (int slot = 36; slot <= 44; slot++) {
            if (matchesWantedShulker(playerContainer.getItemStack(slot))) return slot;
        }
        for (int slot = 9; slot <= 35; slot++) {
            if (matchesWantedShulker(playerContainer.getItemStack(slot))) return slot;
        }
        return matchesWantedShulker(playerContainer.getItemStack(45)) ? 45 : -1;
    }

    private boolean matchesWantedShulker(ItemStack stack) {
        return stack != null && stack.getAmount() > 0 && containsWantedContents(stack);
    }

    private boolean moveShulkerToHotbar(int slot) {
        try {
            var builder = InventoryActionRequest.builder()
                .owner(this)
                .priority(6000);
            if (slot >= 36 && slot <= 44) {
                unloadHotbarRawSlot = slot;
                builder.actions(new SetHeldItem(slot - 36));
            } else {
                int hotbarIndex = strictBorrowedPickup()
                        ? firstEmptyHotbarIndex() : SHULKER_HOTBAR_SLOT;
                if (hotbarIndex < 0) return false;
                unloadHotbarRawSlot = 36 + hotbarIndex;
                builder.actions(
                    new MoveToHotbarSlot(slot, MoveToHotbarAction.from(hotbarIndex)),
                    new SetHeldItem(hotbarIndex)
                );
                swapOwnedShulkerSlots(slot, 36 + hotbarIndex);
            }
            unloadHotbarRequest = INVENTORY.submit(builder.build());
            return !(unloadHotbarRequest.isCompleted() && !unloadHotbarRequest.getNow());
        } catch (Exception ignored) {
            unloadHotbarRequest = null;
            unloadHotbarRawSlot = -1;
            return false;
        }
    }

    private int firstEmptyHotbarIndex() {
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null) return -1;
        for (int raw = 36; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack == null || stack.getAmount() <= 0) return raw - 36;
        }
        return -1;
    }

    private boolean unloadShulkerReadyForPlacement() {
        if (unloadHotbarRawSlot < 36 || unloadHotbarRawSlot > 44) return false;
        int heldHotbarIndex = CACHE.getPlayerCache().getHeldItemSlot();
        if (36 + heldHotbarIndex != unloadHotbarRawSlot) return false;
        return matchesWantedShulker(getPlayerInventoryStack(unloadHotbarRawSlot));
    }

    private ItemStack getPlayerInventoryStack(int slot) {
        var playerContainer = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (playerContainer == null) return null;
        return playerContainer.getItemStack(slot);
    }

    // Search broadly and use the nearest safe placement spot.
    private int[] findShulkerPlaceSpot() {
        int baseX = (int) Math.floor(CACHE.getPlayerCache().getX());
        int baseY = (int) Math.floor(CACHE.getPlayerCache().getY());
        int baseZ = (int) Math.floor(CACHE.getPlayerCache().getZ());

        int[] best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dz == 0) continue; // player's own column — never place under/on self
                    int x = baseX + dx;
                    int y = baseY + dy;
                    int z = baseZ + dz;
                    if (!World.isInWorldBounds(x, y, z)) continue;
                    if (rejectedShulkerPlacePositions.contains(posKey(x, y, z))) continue;

                    int[] candidate = new int[]{x, y, z};
                    if (!isSafeShulkerPlaceSpot(candidate)
                            || !isWithinShulkerPlacementReach(candidate)) {
                        continue;
                    }

                    double distSq = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = new int[]{x, y, z};
                    }
                }
            }
        }

        return best;
    }

    private boolean isWithinShulkerPlacementReach(int[] pos) {
        var player = CACHE.getPlayerCache();
        return ContainerApproach.isWithinVanillaReach(
            player.getX(), player.getY(), player.getZ(), pos[0], pos[1], pos[2]);
    }

    private boolean isSafeShulkerPlaceSpot(int[] pos) {
        int x = pos[0];
        int y = pos[1];
        int z = pos[2];
        if (!World.isInWorldBounds(x, y, z)) return false;
        if (playerIntersectsBlock(x, y, z)) return false;

        var targetBlock = World.getBlock(x, y, z);
        var aboveBlock = World.getBlock(x, y + 1, z);
        var belowBlock = World.getBlock(x, y - 1, z);
        // Baritone may choose any adjacent support face. Keep all of them non-interactable.
        var northBlock = World.getBlock(x, y, z - 1);
        var southBlock = World.getBlock(x, y, z + 1);
        var eastBlock = World.getBlock(x + 1, y, z);
        var westBlock = World.getBlock(x - 1, y, z);
        return BlockCompat.canReplace(targetBlock)
            && BlockCompat.canReplace(aboveBlock)
            && !BlockCompat.isAir(belowBlock)
            && !BlockCompat.isInteractable(belowBlock)
            && !BlockCompat.isInteractable(northBlock)
            && !BlockCompat.isInteractable(southBlock)
            && !BlockCompat.isInteractable(eastBlock)
            && !BlockCompat.isInteractable(westBlock)
            && !BlockCompat.isInteractable(aboveBlock)
            && BlockCompat.isSolid(x, y - 1, z);
    }

    private boolean playerIntersectsBlock(int x, int y, int z) {
        var player = CACHE.getPlayerCache();
        double playerX = player.getX();
        double playerY = player.getY();
        double playerZ = player.getZ();
        // Minecraft players are 0.6 blocks wide and 1.8 blocks tall. A neighbouring block can
        // overlap the bot when it is standing close to an edge even though it is not the same
        // floored X/Z column, which makes Baritone reject every placement replan.
        return playerX + 0.3 > x && playerX - 0.3 < x + 1
                && playerY + 1.8 > y && playerY < y + 1
                && playerZ + 0.3 > z && playerZ - 0.3 < z + 1;
    }

    private boolean isShulkerBoxItem(String itemId) {
        return itemId != null && itemId.contains("shulker_box");
    }

    private String itemIdFromStack(ItemStack stack) {
        return ItemIdentifier.getItemId(stack);
    }

    private String posString(int[] pos) {
        return pos[0] + ", " + pos[1] + ", " + pos[2];
    }

    private void emit(String event, Map<String, Object> extraFields) {
        if (eventCallback == null) return;

        Map<String, Object> payload = new LinkedHashMap<>();
        if (activeRequestName != null) payload.put("request_name", activeRequestName);
        payload.put("retriever_state", state.name());
        payload.put("remaining_total", getRemainingTotal());
        payload.put("moved_stacks", successfulTransfers);
        if (currentTarget != null) {
            payload.put("target_moved_stacks", Math.max(0, successfulTransfers - transfersAtTargetStart));
            payload.put("target_open_attempted", targetOpenAttempted);
        }
        if (currentTarget != null) payload.put("target_position", posString(currentTarget));
        if (openingClickTarget != null && currentTarget != null
                && !java.util.Arrays.equals(openingClickTarget, currentTarget)) {
            payload.put("opening_click_target", posString(openingClickTarget));
        }
        if (placedShulkerPos != null) payload.put("placed_shulker_position", posString(placedShulkerPos));
        if (extraFields != null && !extraFields.isEmpty()) payload.putAll(extraFields);
        eventCallback.accept(event, payload);
    }

    private double distanceTo(int x, int y, int z) {
        var pc = CACHE.getPlayerCache();
        double dx = pc.getX() - x;
        double dy = pc.getY() - y;
        double dz = pc.getZ() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // Return true when this tick submits a split-stack click.
    private boolean tickSplitTake() {
        if (serverSession == null || openContainerId < 0) {
            resetSplit();
            return false;
        }

        if (splitAwaitingDeposit) {
            var invCache = CACHE.getPlayerCache().getInventoryCache();
            // While a container is open, Zenith's player-inventory view can lag behind
            // its live window slots. Verify the destination in the window we clicked.
            Container open = invCache.getOpenContainer();
            int dropWindowSlot = containerSlotFromPlayerInventorySlot(
                    getOpenContainerSlotCount(), splitDropRawSlot);
            ItemStack deposited = invCache.getOpenContainerId() == openContainerId
                    && open != null && open.getContainerId() == openContainerId
                    && dropWindowSlot >= 0 && dropWindowSlot < open.getSize()
                    ? open.getItemStack(dropWindowSlot) : null;
            ItemStack cursor = invCache.getMouseStack();
            if ((cursor == null || cursor.getAmount() == 0)
                    && deposited != null && deposited.getAmount() == splitNeeded
                    && splitItemId.equals(matchingRequestKey(itemIdFromStack(deposited)))) {
                emit("retrieve_split_deposit_verified", Map.of(
                        "item_id", splitItemId,
                        "raw_slot", splitDropRawSlot,
                        "quantity", splitNeeded,
                        "container_id", openContainerId));
                recordTaken(splitItemId, splitNeeded);
                resetSplit();
                return true;
            }
            if (++splitDepositWaitTicks > 40) {
                emit("retrieve_split_deposit_unverified", Map.of(
                        "drop_slot", splitDropRawSlot,
                        "window_slot", dropWindowSlot,
                        "window_ready", open != null && open.getContainerId() == openContainerId,
                        "expected", splitNeeded,
                        "observed", deposited == null ? 0 : deposited.getAmount(),
                        "cursor_count", cursor == null ? 0 : cursor.getAmount()));
                resetSplit();
                finish(false, "split_deposit_unverified");
            }
            return true;
        }

        // Pick up the source stack.
        if (!splitCursorReady) {
            if (!submitClickItem(splitSrcSlot, ClickItemAction.LEFT_CLICK)) {
                return true;
            }
            splitCursorReady = true;
            return true;
        }

        // Put excess items back one per tick.
        if (splitPutbacksLeft > 0) {
            if (!submitClickItem(splitSrcSlot, ClickItemAction.RIGHT_CLICK)) {
                return true;
            }
            splitPutbacksLeft--;
            return true;
        }

        // Move the kept portion to an empty player slot.
        int chestSlots = getOpenContainerSlotCount();
        var invCache = CACHE.getPlayerCache().getInventoryCache();
        Container player = invCache.getPlayerInventory();
        Container open = invCache.getOpenContainer();
        if (player == null || open == null || invCache.getOpenContainerId() != openContainerId
                || open.getContainerId() != openContainerId) {
            return true;
        }
        int dropRawSlot = containerSlots == null ? -1
                : firstEmptyTransferRawSlot(chestSlots,
                    raw -> isEmpty(player.getItemStack(raw)),
                    slot -> slot < open.getSize() && isEmpty(open.getItemStack(slot)));
        int dropSlot = dropRawSlot < 0 ? -1
                : containerSlotFromPlayerInventorySlot(chestSlots, dropRawSlot);

        if (dropSlot == -1) {
            // Restore the source stack when inventory is full.
            submitClickItem(splitSrcSlot, ClickItemAction.LEFT_CLICK);
            resetSplit();
            return true;
        }

        if (!submitClickItem(dropSlot, ClickItemAction.LEFT_CLICK)) {
            return true;
        }

        // Do not count a submitted click as cargo until the server shows it in that slot.
        splitAwaitingDeposit = true;
        splitDropRawSlot = dropRawSlot;
        splitDepositWaitTicks = 0;
        return true;
    }

    // Advance split state only after InventoryManager accepts the click.
    private boolean submitClickItem(int slot, ClickItemAction action) {
        try {
            var future = INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this)
                .priority(6000)
                .actions(new ClickItem(openContainerId, slot, action))
                .build());
            return !(future.isDone() && !future.isAccepted());
        } catch (Exception e) {
            resetSplit();
            return false;
        }
    }

    // Start splitting a stack larger than the remaining request.
    private void beginSplitTake(int srcSlot, String itemId, int stackCount, int needed) {
        splitInProgress = true;
        splitSrcSlot = srcSlot;
        splitItemId = itemId;
        splitNeeded = Math.max(1, Math.min(needed, stackCount));
        splitPutbacksLeft = stackCount - splitNeeded;
        splitCursorReady = false;
        splitAwaitingDeposit = false;
        splitDropRawSlot = -1;
        splitDepositWaitTicks = 0;
    }

    /** Kit cargo must land in an empty slot, never merge with protected player supplies. */
    static boolean exactSlotTransferRequired(String requestName, String requestKey,
                                             int stackCount, int needed) {
        return needed > 0 && (stackCount > needed
                || (requestName != null && requestName.startsWith("kit_fulfillment:")
                    && !EMPTY_SHELL_REQUEST.equals(requestKey)));
    }

    static boolean mayBorrowInventoryShulker(String requestName) {
        return requestName == null || !requestName.startsWith("kit_fulfillment:");
    }

    static boolean mayBorrowInventoryShulker(String requestName, boolean directContentsOnly) {
        return !directContentsOnly && mayBorrowInventoryShulker(requestName);
    }

    private void resetSplit() {
        splitInProgress = false;
        splitSrcSlot = -1;
        splitPutbacksLeft = 0;
        splitNeeded = 0;
        splitCursorReady = false;
        splitAwaitingDeposit = false;
        splitDropRawSlot = -1;
        splitDepositWaitTicks = 0;
        splitItemId = null;
    }

    private void recordTaken(String itemId, int count) {
        Integer current = remaining.get(itemId);
        if (current != null && current > 0) {
            remaining.put(itemId, Math.max(0, current - count));
        }
        consecutiveFailures = 0; // reset on successful take
    }

    // Track where a borrowed shulker lands.
    private void beginTrackingOwnedShulker(ItemStack stack, int chestSlots) {
        pendingOwnedShulkerFingerprint = shulkerFingerprint(stack);
        pendingOwnedShulkerCandidateSlots.clear();
        
        // Record possible destination slots.
        if (containerSlots != null) {
            for (int slot = chestSlots; slot < chestSlots + 36; slot++) {
                if (slot < containerSlots.length) {
                    ItemStack invStack = containerSlots[slot];
                    if (invStack == null || invStack.getAmount() == 0) {
                        pendingOwnedShulkerCandidateSlots.add(playerInventorySlotFromContainerSlot(chestSlots, slot));
                    }
                }
            }
        }
    }

    // Resolve the borrowed shulker's destination slot.
    private void resolvePendingOwnedShulker() {
        if (pendingOwnedShulkerFingerprint == null || pendingOwnedShulkerCandidateSlots.isEmpty()) return;

        var invCache = CACHE.getPlayerCache().getInventoryCache();
        var playerContainer = invCache.getPlayerInventory();
        if (playerContainer == null) return;

        for (int slot : pendingOwnedShulkerCandidateSlots) {
            ItemStack stack = playerContainer.getItemStack(slot);
            if (stack != null && stack.getAmount() > 0) {
                String itemId = ItemIdentifier.getItemId(stack);
                if (isShulkerBoxItem(itemId)) {
                    if (pendingOwnedShulkerFingerprint.equals(shulkerFingerprint(stack))) {
                        ownedShulkerSlots.add(slot);
                        pendingOwnedShulkerFingerprint = null;
                        pendingOwnedShulkerCandidateSlots.clear();
                        return;
                    }
                }
            }
        }
    }

    // Follow ownership through inventory swaps.
    private void swapOwnedShulkerSlots(int slotA, int slotB) {
        boolean ownedA = ownedShulkerSlots.remove(slotA);
        boolean ownedB = ownedShulkerSlots.remove(slotB);
        if (ownedA) ownedShulkerSlots.add(slotB);
        if (ownedB) ownedShulkerSlots.add(slotA);
    }

    private String shulkerFingerprint(ItemStack stack) {
        String itemId = ItemIdentifier.getItemId(stack);
        // TODO: Derive a stable fingerprint from item components.
        return itemId + "|" + stack.hashCode();
    }

    private static int playerInventorySlotFromContainerSlot(int chestSlots, int slot) {
        int relative = slot - chestSlots;
        if (relative < 27) return relative + 9; // Main inventory
        return relative + 9; // Hotbar (container slots 36-44)
    }

    private static int containerSlotFromPlayerInventorySlot(int chestSlots, int slot) {
        if (slot >= 9 && slot <= 35) return chestSlots + slot - 9;
        if (slot >= 36 && slot <= 44) return chestSlots + 27 + slot - 36;
        return -1;
    }

    static int firstEmptyTransferRawSlot(int chestSlots, IntPredicate liveEmpty,
                                         IntPredicate openEmpty) {
        for (int raw = 9; raw <= 44; raw++) {
            int openSlot = containerSlotFromPlayerInventorySlot(chestSlots, raw);
            if (openSlot >= 0 && liveEmpty.test(raw) && openEmpty.test(openSlot)) return raw;
        }
        return -1;
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getAmount() == 0;
    }

    private boolean returnFinishedOwnedShulker(int chestSlots) {
        var playerContainer = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (playerContainer == null) return false;

        var iterator = ownedShulkerSlots.iterator();
        while (iterator.hasNext()) {
            int inventorySlot = iterator.next();
            ItemStack stack = playerContainer.getItemStack(inventorySlot);
            if (stack == null || stack.getAmount() <= 0 || !isShulkerBoxItem(itemIdFromStack(stack))) {
                iterator.remove();
                continue;
            }

            boolean stillNeeded = ItemIdentifier.readShulkerContents(stack).keySet().stream()
                .anyMatch(this::isWanted);
            if (stillNeeded) continue;

            int containerSlot = containerSlotFromPlayerInventorySlot(chestSlots, inventorySlot);
            if (containerSlot < 0) {
                iterator.remove();
                continue;
            }

            if (!quickMoveSlot(containerSlot)) return true;
            iterator.remove();
            emit("retrieve_owned_shulker_returned", Map.of());
            return true;
        }
        return false;
    }

    // Return a matching owned slot, or -1.
    private int findShulkerWithNeededItems() {
        resolvePendingOwnedShulker();
        
        var invCache = CACHE.getPlayerCache().getInventoryCache();
        var playerContainer = invCache.getPlayerInventory();
        if (playerContainer == null) return -1;

        var it = ownedShulkerSlots.iterator();
        while (it.hasNext()) {
            int slot = it.next();
            ItemStack stack = playerContainer.getItemStack(slot);
            if (stack == null || stack.getAmount() == 0) {
                it.remove();
                continue;
            }
            
            String itemId = ItemIdentifier.getItemId(stack);
            if (!isShulkerBoxItem(itemId)) {
                it.remove();
                continue;
            }

            // Owned shulkers were selected because they contained requested items.
            for (String neededItem : remaining.keySet()) {
                if (remaining.get(neededItem) > 0) {
                    return slot;
                }
            }
        }
        
        return -1;
    }
}
