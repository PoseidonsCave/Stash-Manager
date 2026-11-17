package com.zenith.plugin.stashmanager.kit;

import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KitReturnPlaybookTest {
    private static ItemStack apples(int amount) {
        return new ItemStack(ItemRegistry.REGISTRY.get("enchanted_golden_apple").id(), amount);
    }

    @Test void completedKitStopsAtMissingLaneEvenWhenLiveInventoryMatches() {
        var first = apples(64);
        var second = apples(49);
        var plan = KitReturnPlaybook.inspect(25, 25,
                Map.of(16, KitInventoryAppleOwnership.evidence(first),
                        17, KitInventoryAppleOwnership.evidence(second)),
                null, true, Map.of(16, first, 17, second));
        assertEquals(KitReturnPlaybook.Status.LANE_ASSIGNMENT_REQUIRED, plan.status());
        assertEquals(113, plan.totalUnits());
        assertEquals(16, plan.entries().get(0).rawSlot());
        assertEquals(17, plan.entries().get(1).rawSlot());
    }

    @Test void inventoryMismatchTakesPriorityOverAssignedLane() {
        var expected = apples(64);
        var plan = KitReturnPlaybook.inspect(25, 25,
                Map.of(16, KitInventoryAppleOwnership.evidence(expected)),
                new int[]{1, 64, 2}, true, Map.of(16, apples(63)));
        assertEquals(KitReturnPlaybook.Status.INVENTORY_CHANGED, plan.status());
    }

    @Test void savedLaneStillRequiresIndependentLiveTargetAudit() {
        var expected = apples(64);
        var plan = KitReturnPlaybook.inspect(25, 25,
                Map.of(16, KitInventoryAppleOwnership.evidence(expected)),
                new int[]{1, 64, 2}, true, Map.of(16, expected));
        assertEquals(KitReturnPlaybook.Status.TARGET_AUDIT_REQUIRED, plan.status());
        assertEquals(new KitReturnPlaybook.Lane(1, 64, 2), plan.entries().getFirst().lane());
        assertEquals(KitReturnPlaybook.Status.SCAN_UNTRUSTED,
                KitReturnPlaybook.inspect(25, 25,
                        Map.of(16, KitInventoryAppleOwnership.evidence(expected)),
                        new int[]{1, 64, 2}, false, Map.of(16, expected)).status());
    }

    @Test void incompleteJobNeverReturnsAndBadEvidenceIsRejected() {
        var expected = apples(64);
        assertEquals(KitReturnPlaybook.Status.WAITING_FOR_JOB,
                KitReturnPlaybook.inspect(24, 25,
                        Map.of(16, KitInventoryAppleOwnership.evidence(expected)),
                        null, true, Map.of(16, expected)).status());
        assertThrows(IllegalArgumentException.class, () -> KitReturnPlaybook.inspect(25, 25,
                Map.of(45, KitInventoryAppleOwnership.evidence(expected)),
                new int[]{1, 64, 2}, true, Map.of(45, expected)));
    }
}
