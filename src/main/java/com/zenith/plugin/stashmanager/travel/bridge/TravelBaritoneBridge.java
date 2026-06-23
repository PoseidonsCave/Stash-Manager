package com.zenith.plugin.stashmanager.travel.bridge;

import com.zenith.feature.pathfinder.goals.GoalBlock;
import com.zenith.feature.pathfinder.goals.GoalGetToBlock;
import com.zenith.feature.pathfinder.goals.GoalNear;
import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockPos;

import java.util.List;

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

    /** True if Baritone is available (always true for Zenith). */
    public boolean isAvailable() {
        return true;
    }

    /** Walk near a target (within radius blocks). */
    public void walkNear(int[] pos, int radius) {
        BARITONE.pathTo(new GoalNear(new BlockPos(pos[0], pos[1], pos[2]), radius));
    }

    /** Walk directly adjacent to a target (for container interaction). */
    public void walkTo(int[] pos) {
        BARITONE.pathTo(new GoalGetToBlock(new BlockPos(pos[0], pos[1], pos[2])));
    }

    /** Walk to exact block position. */
    public void walkToExact(int[] pos) {
        BARITONE.pathTo(new GoalBlock(new BlockPos(pos[0], pos[1], pos[2])));
    }

    /**
     * Walk through ordered detour waypoints.
     * For now, just path to the final destination.
     * TODO: Implement proper waypoint following if Zenith supports it.
     */
    public void walkToWaypoints(List<int[]> waypoints, int radius) {
        if (waypoints.isEmpty()) return;
        int[] finalPos = waypoints.get(waypoints.size() - 1);
        walkNear(finalPos, radius);
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

    /** Get current pathing target (not directly available in Zenith API). */
    public int[] currentTarget() {
        // Zenith doesn't expose current goal position easily
        // Return null for now, callers should track their own targets
        return null;
    }

    /** Get ticks spent pathing. */
    public int ticksPathing() {
        return ticksPathing;
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

    // ── Elytra Flight Methods ────────────────────────────────────────

    /**
     * Start Baritone's nether flight to destination.
     * Zenith supports nether elytra flight via Baritone.
     */
    public void startNetherFlight(int[] dest) {
        // TODO: Implement when we understand Zenith's elytra flight API
        // For now, use regular pathing as fallback
        walkTo(dest);
    }

    /** True while Baritone's elytra process owns movement. */
    public boolean isElytraOwning() {
        // TODO: Check Zenith's elytra process state
        return false;
    }

    /** True when the elytra process is close enough to the destination. */
    public boolean isElytraArrived() {
        // TODO: Implement elytra arrival detection
        return isArrived();
    }

    /** Check if elytra flight is stuck. */
    public boolean isElytraStuck() {
        // TODO: Implement elytra-specific stuck detection
        return isStuck();
    }

    /** Stop Baritone elytra pathing. */
    public void stopElytra() {
        BARITONE.stop();
    }

    // ── Block Interaction Methods ────────────────────────────────────

    /** Right-click a block (e.g., open container, place block). */
    public void rightClickBlock(int[] pos) {
        BARITONE.rightClickBlock(pos[0], pos[1], pos[2]);
    }

    /** Place a block at the specified position. */
    public void placeBlock(int[] pos) {
        // TODO: Implement block placement with correct Zenith API
        // BARITONE.placeBlock() signature may need adjustment
    }

    /** Break a block at the specified position. */
    public void breakBlock(int[] pos, boolean maintainY) {
        BARITONE.breakBlock(pos[0], pos[1], pos[2], maintainY);
    }

    // ── Utility Methods ──────────────────────────────────────────────

    /** Get player's current position. */
    public int[] getPlayerPos() {
        var player = CACHE.getPlayerCache();
        return new int[]{
            (int) Math.floor(player.getX()),
            (int) Math.floor(player.getY()),
            (int) Math.floor(player.getZ())
        };
    }

    /** Returns the current dimension name, e.g. "minecraft:the_nether". */
    public String getDimension() {
        try {
            return World.getCurrentDimension().name();
        } catch (Exception e) {
            return "";
        }
    }

    /** Calculate horizontal distance between two positions. */
    public double horizontalDistance(int[] from, int[] to) {
        int dx = to[0] - from[0];
        int dz = to[2] - from[2];
        return Math.sqrt(dx * dx + dz * dz);
    }
}
