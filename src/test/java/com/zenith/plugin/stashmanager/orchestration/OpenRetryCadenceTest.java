package com.zenith.plugin.stashmanager.orchestration;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OpenRetryCadenceTest {
    @Test
    void settlesForOneTickThenRetriesUntilButNotAtTimeout() {
        List<Integer> interactionTicks = new ArrayList<>();
        for (int tick = 0; tick <= 400; tick++) {
            if (OpenRetryCadence.shouldInteract(tick, 400, 20)) {
                interactionTicks.add(tick);
            }
        }

        assertEquals(20, interactionTicks.size());
        assertEquals(1, interactionTicks.getFirst());
        assertEquals(380, interactionTicks.getLast());
        assertFalse(interactionTicks.contains(400));
    }
}
