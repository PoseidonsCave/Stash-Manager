package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KitLostSourcePolicyTest {
    private static final String RECOVERY =
            "possible_dropped_box_collected_needs_identity_review:kit_box";

    private static KitFulfillmentJournalStore.Snapshot blocked() {
        return new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("diamond_boots", 1), Map.of(), 10, 0,
                "diamond_boots", 1, 40, new int[]{1, 64, 2},
                new int[]{3, 64, 4}, null, null, Map.of(),
                "kit_pack_failed:shell_contents_changed", 100L, null, 0L,
                List.of(new KitFulfillmentJournalStore.BorrowedBox(
                        new int[]{5, 64, 6}, "shulker_box",
                        Map.of("diamond_boots", 2, "other_boots", 20))), 26);
    }

    @Test void requiresExactLossCountAndUnambiguousRemainingCargo() {
        assertNull(KitLostSourcePolicy.blocker(blocked(), 22, true, true,
                40, 26, 0, RECOVERY));
        assertEquals("declared source unit count does not match the checkpoint",
                KitLostSourcePolicy.blocker(blocked(), 21, true, true,
                        40, 26, 0, RECOVERY));
        assertEquals("packed kit shell is missing or ambiguous",
                KitLostSourcePolicy.blocker(blocked(), 22, true, true,
                        -1, 26, 0, RECOVERY));
        assertEquals("loose kit cargo is missing or ambiguous",
                KitLostSourcePolicy.blocker(blocked(), 22, true, true,
                        40, -1, 0, RECOVERY));
        assertEquals("borrowed source is still present or inventory identity is unknown",
                KitLostSourcePolicy.blocker(blocked(), 22, true, true,
                        40, 26, 1, RECOVERY));
    }

    @Test void refusesAnUnobservedOrReoccupiedWorksite() {
        assertEquals("journaled worksite is not loaded and empty",
                KitLostSourcePolicy.blocker(blocked(), 22, false, false,
                        40, 26, 0, RECOVERY));
        assertEquals("journaled worksite is not loaded and empty",
                KitLostSourcePolicy.blocker(blocked(), 22, true, false,
                        40, 26, 0, RECOVERY));
        assertEquals("worksite recovery has not confirmed an absent kit worksite",
                KitLostSourcePolicy.blocker(blocked(), 22, true, true,
                        40, 26, 0, null));
    }

    @Test void reconciledSourceCannotBeWrittenOffTwice() {
        var original = blocked();
        var settled = new KitFulfillmentJournalStore.Snapshot(
                original.schemaVersion(), original.kitName(), original.state(),
                original.recipe(), original.packedContents(), original.requestedBoxes(),
                original.completedBoxes(), original.currentItem(), original.currentQuantity(),
                original.ownedShellSlot(), original.exportPosition(), null, null, null,
                Map.of(), "lost_borrowed_source_operator_attested", 101L, null, 0L,
                List.of(), 26, original.sourceBoxesToReturn());
        assertEquals("checkpoint is not at the audited misplaced-source boundary",
                KitLostSourcePolicy.blocker(settled, 22, true, true,
                        40, 26, 0, RECOVERY));
        assertEquals(1, settled.writtenOffSources().size());
        assertEquals(true, KitResumePolicy.canResumeReconciledLoss(settled));
    }
}
