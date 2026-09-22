package com.zenith.plugin.stashmanager.organizer;

import com.zenith.feature.player.ClickResult;
import com.zenith.feature.player.InputRequestFuture;

/** Classifies one exact shulker placement request before the organizer retries it. */
final class ShulkerPlacementCadence {
    static final int MAX_ATTEMPTS = 3;
    static final int SELECTION_SETTLE_TICKS = 5;
    static final int DISPATCH_TIMEOUT_TICKS = 10;
    static final int SERVER_ACK_TIMEOUT_TICKS = 60;
    static final int FINAL_ACK_GRACE_TICKS = 60;

    enum Evidence {
        PENDING,
        REJECTED,
        NO_CLICK,
        WRONG_ACTION,
        WRONG_TARGET,
        DISPATCHED
    }

    record Assessment(
            Evidence evidence,
            String action,
            int actualX,
            int actualY,
            int actualZ) {
        static Assessment withoutTarget(Evidence evidence, String action) {
            return new Assessment(evidence, action, Integer.MIN_VALUE,
                    Integer.MIN_VALUE, Integer.MIN_VALUE);
        }

        boolean hasActualTarget() {
            return actualX != Integer.MIN_VALUE;
        }
    }

    private ShulkerPlacementCadence() {}

    static Assessment assess(InputRequestFuture future, int[] expectedSupport) {
        if (future == null || !future.isDone()) {
            return Assessment.withoutTarget(Evidence.PENDING, "pending");
        }
        if (!future.getNow()) {
            return Assessment.withoutTarget(Evidence.REJECTED, "request_rejected");
        }
        if (!(future.getClickResult() instanceof ClickResult.RightClickResult click)) {
            return Assessment.withoutTarget(Evidence.NO_CLICK, "no_right_click");
        }

        String action = click.getType().name().toLowerCase();
        if (click.getType() != ClickResult.RightClickResult.RightClickType.USE_ITEM_ON_BLOCK) {
            return new Assessment(Evidence.WRONG_ACTION, action,
                    click.getBlockX(), click.getBlockY(), click.getBlockZ());
        }
        if (expectedSupport == null || expectedSupport.length < 3
                || click.getBlockX() != expectedSupport[0]
                || click.getBlockY() != expectedSupport[1]
                || click.getBlockZ() != expectedSupport[2]) {
            return new Assessment(Evidence.WRONG_TARGET, action,
                    click.getBlockX(), click.getBlockY(), click.getBlockZ());
        }
        return new Assessment(Evidence.DISPATCHED, action,
                click.getBlockX(), click.getBlockY(), click.getBlockZ());
    }

    static int retryBackoffTicks(int failedAttempts) {
        return Math.min(40, Math.max(1, failedAttempts) * 20);
    }
}
