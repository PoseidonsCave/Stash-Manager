package com.zenith.plugin.stashmanager.organizer;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KitAuditedCargoBaselineTest {
    @Test void resolvedLooseSlotIsNotMisclassifiedAsPreexistingInventory() {
        assertEquals(Set.of(26), KitFulfillmentCoordinator.auditedCargoExclusions(26, Map.of()));
        assertEquals(Set.of(26, 27), KitFulfillmentCoordinator.auditedCargoExclusions(
                -1, Map.of(26, 1, 27, 1)));
        assertEquals(Set.of(), KitFulfillmentCoordinator.auditedCargoExclusions(-1, Map.of()));
    }
}
