package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KitInventorySourceRecoveryTest {
    @Test void expectedRecoveredBoxKeepsOnlyUntakenContents() {
        assertEquals(Map.of("respawn_anchor", 388, "glowstone", 64),
                KitFulfillmentCoordinator.contentsAfterTaking(
                        Map.of("respawn_anchor", 420, "glowstone", 64),
                        "respawn_anchor", 32));
        assertEquals(Map.of(), KitFulfillmentCoordinator.contentsAfterTaking(
                Map.of("respawn_anchor", 32), "respawn_anchor", 32));
        assertEquals(Map.of(), KitFulfillmentCoordinator.contentsAfterTaking(
                Map.of("respawn_anchor", 31), "respawn_anchor", 32));
    }
}
