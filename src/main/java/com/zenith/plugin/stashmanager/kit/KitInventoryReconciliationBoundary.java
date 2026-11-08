package com.zenith.plugin.stashmanager.kit;

import java.util.Set;

/** Selects the only two safe inventory-only handoff boundaries for kit preparation. */
public final class KitInventoryReconciliationBoundary {
    private KitInventoryReconciliationBoundary() { }

    /** Null means an existing kit checkpoint owns inventory but is not reviewable. */
    public static Selection select(KitFulfillmentJournalStore.Snapshot checkpoint) {
        if (checkpoint == null) return new Selection(false, Set.of());
        if (!KitResumePolicy.canAuditLostBorrowedNoCargo(checkpoint)
                || checkpoint.resumeState() != null) return null;
        return new Selection(true, Set.of(checkpoint.ownedShellSlot()));
    }

    /** Check ownership again on every recovery tick, including after a restart. */
    public static boolean matchesActive(KitFulfillmentJournalStore.Snapshot checkpoint,
                                        Set<Integer> pinnedSlots) {
        if (pinnedSlots == null) return false;
        if (checkpoint == null) return pinnedSlots.isEmpty();
        Selection reviewed = select(checkpoint);
        if (reviewed != null && reviewed.hasOwnedShell()) {
            return reviewed.pinnedSlots().equals(pinnedSlots);
        }
        // A separate retirement flow deliberately stages the former shell intact.
        return pinnedSlots.isEmpty() && "BLOCKED".equals(checkpoint.state())
                && "kit_retirement_inventory_staging".equals(checkpoint.blocker())
                && checkpoint.resumeState() == null
                && checkpoint.borrowedSourceWorksite() == null
                && checkpoint.kitWorksite() == null
                && checkpoint.sourceBoxesToReturn().isEmpty();
    }

    public record Selection(boolean hasOwnedShell, Set<Integer> pinnedSlots) { }
}
