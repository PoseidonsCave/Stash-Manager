package com.zenith.plugin.stashmanager.travel.delivery;

import com.zenith.feature.pathfinder.PathingRequestFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.zenith.Globals.BARITONE;

// Mines the obsidian frame of the nether-side portal block by block.
// Best-effort: timed-out blocks are skipped; the mission never aborts for this.
// Frame geometry matches PortalSequence; base inferred from player spawn position.
public final class PortalDestroyer {

    private static final Logger LOGGER = LoggerFactory.getLogger("StashManager/PortalDestroyer");

    /** Ticks to wait for one break operation before skipping the block. */
    private static final int BREAK_TIMEOUT_TICKS = 200;

    // Frame offsets [dx, dy] relative to base — same geometry as PortalSequence.
    private static final int[][] FRAME = {
        {1, 0}, {2, 0},          // bottom row
        {0, 1}, {0, 2}, {0, 3}, // left column
        {3, 1}, {3, 2}, {3, 3}, // right column
        {1, 4}, {2, 4}           // top row
    };

    // ── State ─────────────────────────────────────────────────────────────────

    private boolean started    = false;
    private boolean done       = false;
    private int     baseX, baseY, baseZ;
    private int     blockIndex = 0;
    private PathingRequestFuture currentFuture = null;
    private int     ticksOnBlock = 0;

    // ── Public API ────────────────────────────────────────────────────────────

    // Infer portal base from nether spawn pos: player lands at [baseX+1, baseY+1, baseZ].
    public void start(int[] playerPos) {
        if (started) return;
        started = true;
        // Player lands at [baseX+1, baseY+1, baseZ], so infer base
        this.baseX = playerPos[0] - 1;
        this.baseY = playerPos[1] - 1;
        this.baseZ = playerPos[2];
        LOGGER.info("PortalDestroyer: starting at inferred base=[{},{},{}]", baseX, baseY, baseZ);
        scheduleNextBlock();
    }

    /** Drive the demolition.  Call once per game tick. */
    public void tick() {
        if (!started || done) return;

        ticksOnBlock++;

        if (ticksOnBlock > BREAK_TIMEOUT_TICKS) {
            LOGGER.warn("PortalDestroyer: timed out on block {} — skipping", blockIndex);
            blockIndex++;
            scheduleNextBlock();
            return;
        }

        if (currentFuture != null && currentFuture.isDone()) {
            ticksOnBlock = 0;
            blockIndex++;
            scheduleNextBlock();
        }
    }

    /** True once all reachable frame blocks have been mined (or skipped). */
    public boolean isDone() { return done; }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void scheduleNextBlock() {
        if (blockIndex >= FRAME.length) {
            LOGGER.info("PortalDestroyer: portal frame demolished ({} blocks attempted)", FRAME.length);
            done = true;
            return;
        }
        int[] offset = FRAME[blockIndex];
        int bx = baseX + offset[0];
        int by = baseY + offset[1];
        LOGGER.debug("PortalDestroyer: breaking [{},{},{}] (block {}/{})",
                bx, by, baseZ, blockIndex + 1, FRAME.length);
        currentFuture = BARITONE.breakBlock(bx, by, baseZ, false);
        ticksOnBlock = 0;
    }
}
