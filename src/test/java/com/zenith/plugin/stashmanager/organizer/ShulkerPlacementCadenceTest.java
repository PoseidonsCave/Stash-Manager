package com.zenith.plugin.stashmanager.organizer;

import com.zenith.feature.player.ClickResult;
import com.zenith.feature.player.InputRequestFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShulkerPlacementCadenceTest {
    private static final int[] EXPECTED = {10, 64, -4};

    @Test
    void distinguishesPendingAndRejectedRequests() {
        assertEquals(ShulkerPlacementCadence.Evidence.PENDING,
                ShulkerPlacementCadence.assess(new InputRequestFuture(), EXPECTED).evidence());
        assertEquals(ShulkerPlacementCadence.Evidence.REJECTED,
                ShulkerPlacementCadence.assess(InputRequestFuture.rejected, EXPECTED).evidence());
    }

    @Test
    void requiresAnExecutedBlockClick() {
        InputRequestFuture noClick = accepted(ClickResult.None.INSTANCE);
        assertEquals(ShulkerPlacementCadence.Evidence.NO_CLICK,
                ShulkerPlacementCadence.assess(noClick, EXPECTED).evidence());

        InputRequestFuture useItem = accepted(ClickResult.RightClickResult.useItem());
        assertEquals(ShulkerPlacementCadence.Evidence.WRONG_ACTION,
                ShulkerPlacementCadence.assess(useItem, EXPECTED).evidence());
    }

    @Test
    void distinguishesWrongSupportFromDispatchedPlacement() {
        InputRequestFuture wrong = accepted(
                ClickResult.RightClickResult.useItemOnBlock(11, 64, -4, null));
        ShulkerPlacementCadence.Assessment wrongAssessment =
                ShulkerPlacementCadence.assess(wrong, EXPECTED);
        assertEquals(ShulkerPlacementCadence.Evidence.WRONG_TARGET,
                wrongAssessment.evidence());
        assertEquals(11, wrongAssessment.actualX());

        InputRequestFuture correct = accepted(
                ClickResult.RightClickResult.useItemOnBlock(10, 64, -4, null));
        assertEquals(ShulkerPlacementCadence.Evidence.DISPATCHED,
                ShulkerPlacementCadence.assess(correct, EXPECTED).evidence());
    }

    @Test
    void appliesBoundedRateLimitBackoff() {
        assertEquals(20, ShulkerPlacementCadence.retryBackoffTicks(1));
        assertEquals(40, ShulkerPlacementCadence.retryBackoffTicks(2));
        assertEquals(40, ShulkerPlacementCadence.retryBackoffTicks(10));
        assertEquals(3, ShulkerPlacementCadence.MAX_ATTEMPTS);
    }

    private static InputRequestFuture accepted(ClickResult clickResult) {
        InputRequestFuture future = new InputRequestFuture();
        future.setClickResult(clickResult);
        future.complete(true);
        return future;
    }
}
