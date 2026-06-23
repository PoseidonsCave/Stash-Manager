package com.zenith.plugin.stashmanager.travel.delivery;

import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.GoalGetToBlock;
import com.zenith.mc.block.BlockPos;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import com.zenith.plugin.stashmanager.StashManagerPlugin;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.INVENTORY;

// Deposits mission items into chests near the destination.
// Phases: IDLE → WALKING → OPENING → DEPOSITING → CLOSING → (next chest) → DONE / FAILED
public final class ChestDeposit {

    private static final Logger LOGGER = LoggerFactory.getLogger("StashManager/ChestDeposit");

    /** Horizontal block radius around destination to look for chests. */
    private static final int DEPOSIT_RADIUS = 24;
    /** Distance at which we consider ourselves "at" the chest. */
    private static final double OPEN_DISTANCE = 5.0;
    /** Ticks to walk before declaring timeout. */
    private static final int WALK_TIMEOUT_TICKS = 600;
    /** Ticks to wait for container data before giving up on a chest. */
    private static final int OPEN_TIMEOUT_TICKS = 100;
    /** Ticks to stagger each deposit click, to avoid packet flooding. */
    private static final int DEPOSIT_CLICK_INTERVAL = 2;

    private static final Random RANDOM = new Random();

    // ── State ─────────────────────────────────────────────────────────────────

    public enum State { IDLE, WALKING, OPENING, DEPOSITING, CLOSING, DONE, FAILED }

    private State state = State.IDLE;
    private String failReason = "";

    private List<ContainerEntry> targets = new ArrayList<>();
    private int targetIndex = 0;
    private String[] itemIds = new String[0];

    // Container interaction
    private volatile boolean containerDataReceived = false;
    private volatile int openContainerId = -1;
    private volatile int containerStateId = 0;
    private volatile ItemStack[] containerSlots = null;
    private Session serverSession = null;

    // Per-phase tick counters
    private int phaseTicks = 0;

    /** Ticks remaining in the between-chest gap before walking to the next target. */
    private int chestGapCooldown = 0;

    // Deposit iteration
    private int depositSlotIndex = 0;
    private int depositCooldown = 0;
    private Set<String> normalizedItemIds = new HashSet<>();

    // ── Public API ─────────────────────────────────────────────────────────────

    // Returns false if no containers found within DEPOSIT_RADIUS of destination.
    public boolean start(int[] destPos, String[] itemIds, ContainerIndex index) {
        if (state != State.IDLE) return false;

        this.itemIds = itemIds != null ? itemIds : new String[0];
        this.normalizedItemIds = new HashSet<>();
        for (String id : this.itemIds) {
            normalizedItemIds.add(normalizeItemId(id));
        }

        // Find chests within DEPOSIT_RADIUS of the destination
        targets = new ArrayList<>();
        for (ContainerEntry entry : index.getAll()) {
            int dx = entry.x() - destPos[0];
            int dz = entry.z() - destPos[2];
            if (dx * dx + dz * dz <= DEPOSIT_RADIUS * DEPOSIT_RADIUS) {
                targets.add(entry);
            }
        }

        if (targets.isEmpty()) {
            failReason = "No containers found within " + DEPOSIT_RADIUS + " blocks of destination";
            state = State.FAILED;
            LOGGER.warn("ChestDeposit failed: {}", failReason);
            return false;
        }

        LOGGER.info("ChestDeposit starting — {} container(s) in range, depositing {} item type(s)",
                targets.size(), normalizedItemIds.size());
        targetIndex = 0;
        walkToCurrentTarget();
        return true;
    }

    /** Called by StashManagerModule every time a container content packet arrives. */
    public void onContainerData(Session session, ClientboundContainerSetContentPacket packet) {
        if (state != State.OPENING) return;
        this.serverSession = session;
        this.openContainerId = packet.getContainerId();
        this.containerStateId = packet.getStateId();
        this.containerSlots = packet.getItems();
        this.containerDataReceived = true;
        LOGGER.debug("Container data received (id={}, slots={})", openContainerId, containerSlots.length);
    }

    /** Called every game tick. Drives the deposit state machine. */
    public void tick() {
        phaseTicks++;
        switch (state) {
            case WALKING    -> tickWalking();
            case OPENING    -> tickOpening();
            case DEPOSITING -> tickDepositing();
            case CLOSING    -> tickClosing();
            case IDLE       -> tickChestGap();
            default -> { /* DONE / FAILED */ }
        }
    }

    public boolean isActive()       { return state == State.WALKING || state == State.OPENING
                                           || state == State.DEPOSITING || state == State.CLOSING; }
    public State   getState()       { return state; }
    public boolean isDone()         { return state == State.DONE; }
    public boolean isFailed()       { return state == State.FAILED; }
    public String  getFailReason()  { return failReason; }

    // ── Walking ───────────────────────────────────────────────────────────────

    private void walkToCurrentTarget() {
        if (targetIndex >= targets.size()) {
            LOGGER.info("ChestDeposit: all {} targets processed — done", targets.size());
            state = State.DONE;
            return;
        }
        ContainerEntry target = targets.get(targetIndex);
        LOGGER.info("Walking to container [{},{},{}] ({}/{})",
                target.x(), target.y(), target.z(), targetIndex + 1, targets.size());
        BARITONE.pathTo(new GoalGetToBlock(new BlockPos(target.x(), target.y(), target.z())));
        state = State.WALKING;
        phaseTicks = 0;
    }

    private void tickWalking() {
        ContainerEntry target = targets.get(targetIndex);
        double dist = distanceTo(target.x(), target.y(), target.z());

        if (dist <= OPEN_DISTANCE) {
            BARITONE.stop();
            enterOpening(target);
            return;
        }

        if (phaseTicks > WALK_TIMEOUT_TICKS) {
            LOGGER.warn("Timed out walking to container [{},{},{}] — skipping",
                    target.x(), target.y(), target.z());
            advanceToNextTarget();
            return;
        }

        if (!BARITONE.getCustomGoalProcess().isActive()) {
            // Baritone stopped unexpectedly — retry path
            BARITONE.pathTo(new GoalGetToBlock(new BlockPos(target.x(), target.y(), target.z())));
        }
    }

    // ── Opening ───────────────────────────────────────────────────────────────

    private void enterOpening(ContainerEntry target) {
        containerDataReceived = false;
        openContainerId = -1;
        containerSlots = null;
        state = State.OPENING;
        phaseTicks = 0;
        BARITONE.rightClickBlock(target.x(), target.y(), target.z());
        LOGGER.debug("Right-clicking container at [{},{},{}]", target.x(), target.y(), target.z());
    }

    private void tickOpening() {
        if (containerDataReceived && openContainerId >= 0) {
            enterDepositing();
            return;
        }
        if (phaseTicks > OPEN_TIMEOUT_TICKS) {
            LOGGER.warn("Timed out waiting for container data — skipping chest");
            advanceToNextTarget();
        }
    }

    // ── Depositing ─────────────────────────────────────────────────────────────

    private void enterDepositing() {
        state = State.DEPOSITING;
        phaseTicks = 0;
        depositCooldown = 0;
        // Start from the first player-inventory slot (right after chest slots)
        int chestSlotCount = containerSlots.length - 36;
        depositSlotIndex = Math.max(0, chestSlotCount);
        LOGGER.info("Depositing from player inventory slots {}-{}",
                depositSlotIndex, containerSlots.length - 1);
    }

    private void tickDepositing() {
        if (depositCooldown > 0) {
            depositCooldown--;
            return;
        }

        // Find the next player inventory slot with a matching item
        while (depositSlotIndex < containerSlots.length) {
            ItemStack stack = containerSlots[depositSlotIndex];
            if (stack != null && matchesItemIds(stack)) {
                shiftClickSlot(depositSlotIndex);
                depositSlotIndex++;
                depositCooldown = randomClickDelay();
                return;
            }
            depositSlotIndex++;
        }

        // All slots checked — close this container and move on
        LOGGER.info("Deposit phase complete for current container");
        enterClosing();
    }

    // ── Closing ───────────────────────────────────────────────────────────────

    private void enterClosing() {
        state = State.CLOSING;
        phaseTicks = 0;
        try {
            INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this)
                    .actions(new CloseContainer(openContainerId))
                    .priority(5000)
                    .build());
        } catch (Exception ignored) { }
        openContainerId = -1;
        containerSlots = null;
        containerDataReceived = false;
    }

    private void tickClosing() {
        // Give the server a couple of ticks to process the close, then advance
        if (phaseTicks >= 3) {
            advanceToNextTarget();
        }
    }

    // ── Between-chest gap ─────────────────────────────────────────────────────

    private void tickChestGap() {
        if (chestGapCooldown <= 0) return; // shouldn't happen, but guard anyway
        chestGapCooldown--;
        if (chestGapCooldown == 0) {
            walkToCurrentTarget();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void advanceToNextTarget() {
        targetIndex++;
        int gap = chestGapTicks();
        if (gap > 0) {
            chestGapCooldown = gap;
            state = State.IDLE;  // pause in IDLE; tickChestGap() will fire walkToCurrentTarget()
            phaseTicks = 0;
        } else {
            walkToCurrentTarget();
        }
    }

    private void shiftClickSlot(int slot) {
        if (serverSession == null || openContainerId < 0) return;
        try {
            var packet = new ServerboundContainerClickPacket(
                    openContainerId,
                    containerStateId,
                    slot,
                    ContainerActionType.SHIFT_CLICK_ITEM,
                    ShiftClickItemAction.LEFT_CLICK,
                    null,
                    new Int2ObjectOpenHashMap<>()
            );
            serverSession.send(packet);
            containerStateId++;
        } catch (Exception e) {
            LOGGER.debug("Failed to send shift-click packet for slot {}: {}", slot, e.getMessage());
        }
    }

    private boolean matchesItemIds(ItemStack stack) {
        if (normalizedItemIds.isEmpty()) return true; // deposit everything if no filter
        var item = com.zenith.mc.item.ItemRegistry.REGISTRY.get(stack.getId());
        if (item == null) return false;
        String name = normalizeItemId(item.name());
        return normalizedItemIds.contains(name);
    }

    private static String normalizeItemId(String id) {
        // Accept both "diamond" and "minecraft:diamond" — strip namespace
        if (id == null) return "";
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    /** Randomised tick delay between inventory clicks, sourced from stealth config. */
    private int randomClickDelay() {
        var cfg = StashManagerPlugin.getConfig();
        if (cfg == null) return DEPOSIT_CLICK_INTERVAL;
        int min = Math.max(1, cfg.stealthClickDelayMinTicks);
        int max = Math.max(min, cfg.stealthClickDelayMaxTicks);
        return min == max ? min : min + RANDOM.nextInt(max - min + 1);
    }

    /** Between-chest pause ticks from stealth config. */
    private int chestGapTicks() {
        var cfg = StashManagerPlugin.getConfig();
        return cfg != null ? Math.max(0, cfg.stealthChestGapTicks) : 0;
    }

    private double distanceTo(int x, int y, int z) {
        try {
            var player = CACHE.getPlayerCache().getThePlayer();
            double dx = player.getX() - x;
            double dy = player.getY() - y;
            double dz = player.getZ() - z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        } catch (Exception e) {
            return Double.MAX_VALUE;
        }
    }
}
