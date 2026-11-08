package com.zenith.plugin.stashmanager.kit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitEmptyShellOwnershipAuditTest {
    private static KitFulfillmentJournalStore.Snapshot checkpoint(
            String blocker, String resumeState,
            List<KitFulfillmentJournalStore.BorrowedBox> sources) {
        return new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, "cpvp", "BLOCKED",
                Map.of("totem_of_undying", 6), Map.of(), 25, 14,
                "totem_of_undying", 6, 42, new int[]{1, 130, 2},
                null, null, null, Map.of(), blocker, 100L, resumeState,
                resumeState == null ? 0L : 200L, sources, -1, List.of(),
                resumeState == null ? Map.of() : Map.of(13, 1, 14, 1, 26, 1,
                        27, 1, 28, 1, 33, 1));
    }

    private static KitFulfillmentJournalStore.BorrowedBox source(
            Map<String, Integer> contents) {
        return new KitFulfillmentJournalStore.BorrowedBox(
                new int[]{3, 130, 4}, "shulker_box", contents);
    }

    @Test void onlyEmptyShellCollisionWithTwoDifferentPendingSourcesCanBeAudited() {
        var pending = List.of(source(Map.of()), source(Map.of("totem_of_undying", 25)));
        assertTrue(KitEmptyShellOwnershipAudit.canAudit(checkpoint(
                "kit_owned_shell_identity_ambiguous", null, pending)));
        assertFalse(KitEmptyShellOwnershipAudit.canAudit(checkpoint(
                "kit_owned_shell_identity_ambiguous", null,
                List.of(source(Map.of()), source(Map.of())))));
        assertFalse(KitEmptyShellOwnershipAudit.canAudit(checkpoint(
                "kit_owned_shell_identity_ambiguous", null,
                List.of(source(Map.of("totem_of_undying", 25))))));
        assertFalse(KitEmptyShellOwnershipAudit.canAudit(checkpoint(
                "kit_owned_shell_identity_ambiguous", "WAIT_CARGO", pending)));
    }

    @Test void scheduledOwnershipAuditIsEligibleOnlyAtItsCargoBoundary() {
        var pending = List.of(source(Map.of()), source(Map.of("totem_of_undying", 25)));
        String marker = "resume_audited_empty_shell_ownership:42:44:"
                + "a".repeat(64) + ":" + "b".repeat(64);
        var scheduled = checkpoint(marker, "WAIT_CARGO", pending);
        assertTrue(KitEmptyShellOwnershipAudit.isScheduled(marker));
        assertNull(KitResumePolicy.blocker(scheduled));
        assertFalse(KitEmptyShellOwnershipAudit.canAudit(scheduled));
        assertFalse(KitEmptyShellOwnershipAudit.isScheduled("kit_owned_shell_identity_ambiguous"));
    }
}
