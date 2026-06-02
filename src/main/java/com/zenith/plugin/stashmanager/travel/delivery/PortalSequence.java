package com.zenith.plugin.stashmanager.travel.delivery;

import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.GoalBlock;
import com.zenith.feature.pathfinder.goals.GoalNear;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;

// Builds a minimum 2×3 nether portal and walks the bot through it.
// Frame: 10 obsidian, corners skipped, extending along X-Y at fixed Z.
// Phases: IDLE → CLEARING → PLACING → EQUIPPING → IGNITING → ENTERING → WAITING_DIM → DONE / FAILED
public final class PortalSequence {

    private static final Logger LOGGER = LoggerFactory.getLogger("StashManager/PortalSeq");

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Ticks to wait after ignition for the portal flame to appear. */
    private static final int IGNITE_SETTLE_TICKS = 10;
    /** Ticks to wait for a dimension change before giving up. */
    private static final int DIM_WAIT_TIMEOUT_TICKS = 400; // 20 s
    /** Ticks before declaring a block-placement attempt timed out. */
    private static final int PLACE_TIMEOUT_TICKS = 200;

    // Frame offsets [dx, dy] relative to base. Corners are skipped (10 blocks total).
    private static final int[][] FRAME = {
        {1, 0}, {2, 0},              // bottom row (inner 2)
        {0, 1}, {0, 2}, {0, 3},      // left column
        {3, 1}, {3, 2}, {3, 3},      // right column
        {1, 4}, {2, 4}               // top row (inner 2)
    };

    // ── State ─────────────────────────────────────────────────────────────────

    public enum Phase {
        IDLE,
        CLEARING,      // clear 4×5 space above base
        PLACING,       // place obsidian frame blocks one-by-one
        EQUIPPING,     // switch hotbar to flint & steel slot
        IGNITING,      // right-click to ignite the portal
        ENTERING,      // walk into the portal
        WAITING_DIM,   // wait for dimension to change to nether
        DONE,
        FAILED
    }

    private Phase phase = Phase.IDLE;
    private String failReason = "";

    /** Bottom-left corner of the 4×5 portal bounding box (z fixed). */
    private int baseX, baseY, baseZ;

    private int blockIndex = 0;
    private PathingRequestFuture currentFuture = null;
    private int phaseTicks = 0;
    private int flintHotbarSlot = -1;

    // ── Public API ─────────────────────────────────────────────────────────────

    // Returns false if obsidian or flint & steel is missing.
    public boolean start(int[] playerPos) {
        if (phase != Phase.IDLE) return false;

        // Place portal 1 block to the player's side so they can step in easily
        baseX = playerPos[0] - 1;
        baseY = playerPos[1];
        baseZ = playerPos[2];

        if (!hasRequiredItems()) {
            failReason = "Missing obsidian or flint & steel in inventory";
            phase = Phase.FAILED;
            LOGGER.warn("PortalSequence cannot start: {}", failReason);
            return false;
        }

        LOGGER.info("PortalSequence starting at base=[{},{},{}]", baseX, baseY, baseZ);
        enterClearing();
        return true;
    }

    /** Drive the state machine. Call once per game tick. */
    public void tick() {
        phaseTicks++;
        switch (phase) {
            case CLEARING  -> tickClearing();
            case PLACING   -> tickPlacing();
            case EQUIPPING -> tickEquipping();
            case IGNITING  -> tickIgniting();
            case ENTERING  -> tickEntering();
            case WAITING_DIM -> tickWaitingDim();
            default -> { /* IDLE / DONE / FAILED */ }
        }
    }

    public Phase   getPhase()      { return phase; }
    public boolean isDone()        { return phase == Phase.DONE; }
    public boolean isFailed()      { return phase == Phase.FAILED; }
    public String  getFailReason() { return failReason; }

    // ── Clearing ──────────────────────────────────────────────────────────────

    private void enterClearing() {
        phase = Phase.CLEARING;
        phaseTicks = 0;
        // Clear a 4 wide × 5 tall × 1 deep area at the portal location
        BlockPos p1 = new BlockPos(baseX,     baseY,     baseZ);
        BlockPos p2 = new BlockPos(baseX + 3, baseY + 4, baseZ);
        currentFuture = BARITONE.clearArea(p1, p2);
        LOGGER.info("Clearing portal space [{},{},{}] to [{},{},{}]",
                baseX, baseY, baseZ, baseX + 3, baseY + 4, baseZ);
    }

    private void tickClearing() {
        if (currentFuture == null || currentFuture.isDone()) {
            enterPlacing();
            return;
        }
        if (phaseTicks > PLACE_TIMEOUT_TICKS * 3) {
            fail("Timed out clearing portal area");
        }
    }

    // ── Placing ───────────────────────────────────────────────────────────────

    private void enterPlacing() {
        phase = Phase.PLACING;
        phaseTicks = 0;
        blockIndex = 0;
        scheduleNextBlock();
    }

    private void scheduleNextBlock() {
        if (blockIndex >= FRAME.length) {
            LOGGER.info("All {} obsidian blocks placed — equipping flint & steel", FRAME.length);
            enterEquipping();
            return;
        }
        int[] offset = FRAME[blockIndex];
        int bx = baseX + offset[0];
        int by = baseY + offset[1];
        int bz = baseZ;
        LOGGER.debug("Placing obsidian at [{},{},{}] (frame block {})", bx, by, bz, blockIndex);
        currentFuture = BARITONE.placeBlock(bx, by, bz, ItemRegistry.OBSIDIAN);
    }

    private void tickPlacing() {
        if (currentFuture == null) {
            fail("Null future during block placement");
            return;
        }
        if (phaseTicks > PLACE_TIMEOUT_TICKS) {
            fail("Timed out placing obsidian block " + blockIndex);
            return;
        }
        if (currentFuture.isDone()) {
            phaseTicks = 0;
            blockIndex++;
            scheduleNextBlock();
        }
    }

    // ── Equipping flint & steel ───────────────────────────────────────────────

    private void enterEquipping() {
        phase = Phase.EQUIPPING;
        phaseTicks = 0;
        flintHotbarSlot = findHotbarSlot("flint_and_steel");
        if (flintHotbarSlot < 0) {
            fail("Flint & steel not found in hotbar");
            return;
        }
        // Switch held item via INVENTORY API
        CACHE.getPlayerCache().setHeldItemSlot(flintHotbarSlot);
    }

    private void tickEquipping() {
        // Give it 2 ticks to take effect, then ignite
        if (phaseTicks >= 2) {
            enterIgniting();
        }
    }

    // ── Igniting ──────────────────────────────────────────────────────────────

    private void enterIgniting() {
        phase = Phase.IGNITING;
        phaseTicks = 0;
        // Right-click the bottom-left inner frame block to ignite
        int igX = baseX + 1;
        int igY = baseY;
        int igZ = baseZ;
        LOGGER.info("Igniting portal at [{},{},{}]", igX, igY, igZ);
        currentFuture = BARITONE.rightClickBlock(igX, igY, igZ);
    }

    private void tickIgniting() {
        if (phaseTicks > PLACE_TIMEOUT_TICKS) {
            fail("Timed out during portal ignition");
            return;
        }
        if (currentFuture != null && currentFuture.isDone()) {
            // Wait a few ticks for the purple flame to appear
            if (phaseTicks >= IGNITE_SETTLE_TICKS) {
                enterEntering();
            }
        }
    }

    // ── Entering ──────────────────────────────────────────────────────────────

    private void enterEntering() {
        phase = Phase.ENTERING;
        phaseTicks = 0;
        // Walk to the inside of the portal (first interior column)
        int entX = baseX + 1;
        int entY = baseY + 1;
        int entZ = baseZ;
        LOGGER.info("Walking into portal at [{},{},{}]", entX, entY, entZ);
        BARITONE.pathTo(new GoalNear(new BlockPos(entX, entY, entZ), 1));
    }

    private void tickEntering() {
        if (phaseTicks > PLACE_TIMEOUT_TICKS) {
            fail("Timed out walking into portal");
            return;
        }
        if (!BARITONE.getCustomGoalProcess().isActive()) {
            // Player reached portal interior; now wait for dimension change
            phase = Phase.WAITING_DIM;
            phaseTicks = 0;
            LOGGER.info("Waiting for nether dimension transition...");
        }
    }

    // ── Waiting for dimension change ──────────────────────────────────────────

    private void tickWaitingDim() {
        if (DimensionHelper.isNether()) {
            LOGGER.info("Dimension changed to nether — portal sequence complete");
            phase = Phase.DONE;
            return;
        }
        if (phaseTicks > DIM_WAIT_TIMEOUT_TICKS) {
            fail("Timed out waiting for nether dimension change");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean hasRequiredItems() {
        int obsidian = 0;
        boolean hasFlint = false;
        var inv = CACHE.getPlayerCache().getPlayerInventory();
        for (var stack : inv) {
            if (stack == null) continue;
            var item = com.zenith.mc.item.ItemRegistry.REGISTRY.get(stack.getId());
            if (item == null) continue;
            if (item.name().contains("obsidian")) obsidian += stack.getAmount();
            if (item.name().contains("flint_and_steel")) hasFlint = true;
        }
        if (obsidian < FRAME.length) {
            LOGGER.warn("Need {} obsidian, have {}", FRAME.length, obsidian);
        }
        return obsidian >= FRAME.length && hasFlint;
    }

    private int findHotbarSlot(String itemNameSubstring) {
        var inv = CACHE.getPlayerCache().getPlayerInventory();
        // Hotbar slots are indices 36-44 in the full player inventory (slots 0-8 in hotbar)
        for (int i = 36; i <= 44; i++) {
            if (i >= inv.size()) break;
            var stack = inv.get(i);
            if (stack == null) continue;
            var item = com.zenith.mc.item.ItemRegistry.REGISTRY.get(stack.getId());
            if (item != null && item.name().contains(itemNameSubstring)) {
                return i - 36; // convert to hotbar slot (0-8)
            }
        }
        return -1;
    }

    private void fail(String reason) {
        failReason = reason;
        phase = Phase.FAILED;
        LOGGER.warn("PortalSequence failed: {}", reason);
    }
}
