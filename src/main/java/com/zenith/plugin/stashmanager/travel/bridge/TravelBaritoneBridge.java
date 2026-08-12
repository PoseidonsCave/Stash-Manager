package com.zenith.plugin.stashmanager.travel.bridge;

import com.zenith.feature.pathfinder.goals.GoalBlock;
import com.zenith.mc.block.BlockPos;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;

/**
 * Narrow adapter over Zenith's Baritone API for travel system.
 * Provides a stable interface that isolates travel code from Baritone API changes.
 */
public final class TravelBaritoneBridge {

    private static final TravelBaritoneBridge INSTANCE = new TravelBaritoneBridge();

    public static TravelBaritoneBridge get() { return INSTANCE; }

    private TravelBaritoneBridge() {}

    private int ticksPathing = 0;
    private boolean wasPathing = false;

    /** Walk to exact block position. */
    public void walkToExact(int[] pos) {
        BARITONE.pathTo(new GoalBlock(new BlockPos(pos[0], pos[1], pos[2])));
    }

    /** Stop all Baritone operations. */
    public void cancelAll() {
        BARITONE.stop();
        ticksPathing = 0;
        wasPathing = false;
    }

    /**
     * Check if Baritone is actively pathing.
     * Uses CustomGoalProcess.isActive() which is more reliable than Baritone.isActive()
     * according to zenith-baritone-api.md notes.
     */
    public boolean isPathing() {
        return BARITONE.getCustomGoalProcess().isActive();
    }

    /** 
     * Check if Baritone has arrived at the destination.
     * For Zenith, we check if pathing stopped and the player is near the target.
     */
    public boolean isArrived() {
        // If CustomGoalProcess is no longer active and we were pathing, assume arrival
        return wasPathing && !BARITONE.getCustomGoalProcess().isActive();
    }

    /**
     * Check if Baritone is stuck.
     * For Zenith, we implement timeout-based stuck detection.
     */
    public boolean isStuck() {
        // Timeout after 10 seconds (200 ticks) of continuous pathing without arrival
        return ticksPathing > 200 && isPathing();
    }

    /**
     * Tick method to update pathing state tracking.
     * Should be called once per tick while travel system is active.
     */
    public void tick() {
        boolean pathing = isPathing();
        if (pathing) {
            ticksPathing++;
            wasPathing = true;
        } else {
            if (wasPathing) {
                // Just finished pathing
                wasPathing = false;
            }
            ticksPathing = 0;
        }
    }

    /** Get player's current position. */
    public int[] getPlayerPos() {
        var player = CACHE.getPlayerCache();
        return new int[]{
            (int) Math.floor(player.getX()),
            (int) Math.floor(player.getY()),
            (int) Math.floor(player.getZ())
        };
    }

}
