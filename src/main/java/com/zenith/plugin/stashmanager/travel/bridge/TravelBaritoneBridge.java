package com.zenith.plugin.stashmanager.travel.bridge;

import com.zenith.feature.pathfinder.goals.GoalBlock;
import com.zenith.mc.block.BlockPos;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;

// Isolates travel code from Zenith Baritone API changes.
public final class TravelBaritoneBridge {

    private static final TravelBaritoneBridge INSTANCE = new TravelBaritoneBridge();

    public static TravelBaritoneBridge get() { return INSTANCE; }

    private TravelBaritoneBridge() {}

    private int ticksPathing = 0;
    private boolean wasPathing = false;

    // Walk to exact block position.
    public void walkToExact(int[] pos) {
        BARITONE.pathTo(new GoalBlock(new BlockPos(pos[0], pos[1], pos[2])));
    }

    // Stop all Baritone operations.
    public void cancelAll() {
        BARITONE.stop();
        ticksPathing = 0;
        wasPathing = false;
    }

    // Use the custom goal process as the authoritative pathing state.
    public boolean isPathing() {
        return BARITONE.getCustomGoalProcess().isActive();
    }

    public boolean isArrived() {
        // Treat a completed custom goal as arrival.
        return wasPathing && !BARITONE.getCustomGoalProcess().isActive();
    }

    public boolean isStuck() {
        // Fail after 10 seconds of continuous pathing.
        return ticksPathing > 200 && isPathing();
    }

    // Update pathing state once per travel tick.
    public void tick() {
        boolean pathing = isPathing();
        if (pathing) {
            ticksPathing++;
            wasPathing = true;
        } else {
            wasPathing = false;
            ticksPathing = 0;
        }
    }

    // Get player's current position.
    public int[] getPlayerPos() {
        var player = CACHE.getPlayerCache();
        return new int[]{
            (int) Math.floor(player.getX()),
            (int) Math.floor(player.getY()),
            (int) Math.floor(player.getZ())
        };
    }

}
