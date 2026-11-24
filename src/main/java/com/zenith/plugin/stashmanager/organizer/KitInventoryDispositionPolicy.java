package com.zenith.plugin.stashmanager.organizer;

import com.zenith.plugin.stashmanager.orchestration.StorageClassPolicy;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only routing of unowned kit-inventory cargo. The lane map must come from the
 * persisted assignments, not a newly inferred lane. A plan never authorizes moving a
 * journal-ambiguous source box or bypassing live container/stack verification.
 */
public final class KitInventoryDispositionPolicy {
    public enum Route {
        ASSIGNED_LANE,
        SAME_ITEM_SHULKER_IN_IMPORT,
        NEEDS_REGISTERED_IMPORT,
        SHULKER_IDENTITY_REVIEW,
        NON_TRANSFERABLE_SLOT_REVIEW
    }

    public record Decision(int rawSlot, String itemId, String storageClass, Route route) { }

    private KitInventoryDispositionPolicy() { }

    public static List<Decision> plan(
            List<KitInventoryAdmissionPolicy.Slot> unexpected,
            Map<String, int[]> persistedLaneAssignments,
            List<int[]> registeredImportChests) {
        if (unexpected == null || persistedLaneAssignments == null
                || registeredImportChests == null) {
            throw new IllegalArgumentException("Inventory disposition requires persisted routing inputs");
        }
        List<Decision> decisions = new ArrayList<>();
        for (var slot : unexpected) {
            if (slot == null || slot.amount() <= 0) continue;
            String storageClass = StorageClassPolicy.route(slot.itemId());
            Route route;
            if (slot.raw() < 9 || slot.raw() > 44) {
                route = Route.NON_TRANSFERABLE_SLOT_REVIEW;
            } else if (ItemIdentifier.baseItemId(slot.itemId()).contains("shulker_box")) {
                // Contents and job ownership, not box color, determine its destination.
                route = Route.SHULKER_IDENTITY_REVIEW;
            } else if (persistedLaneAssignments.containsKey(storageClass)
                    && persistedLaneAssignments.get(storageClass) != null) {
                route = Route.ASSIGNED_LANE;
            } else if (!registeredImportChests.isEmpty()) {
                route = Route.SAME_ITEM_SHULKER_IN_IMPORT;
            } else {
                route = Route.NEEDS_REGISTERED_IMPORT;
            }
            decisions.add(new Decision(slot.raw(), slot.itemId(), storageClass, route));
        }
        return List.copyOf(decisions);
    }
}
