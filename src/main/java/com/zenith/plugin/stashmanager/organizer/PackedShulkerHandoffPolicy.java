package com.zenith.plugin.stashmanager.organizer;

import java.util.Objects;

/** A packing transaction may hand off one uniquely identified collected shell. */
final class PackedShulkerHandoffPolicy {
    private PackedShulkerHandoffPolicy() { }

    static boolean uniquePickupCandidate(String pickupItemId, String candidateItemId,
                                         int matchingPickupItems, boolean soleMovableShulker) {
        if (pickupItemId != null) {
            return Objects.equals(pickupItemId, candidateItemId) && matchingPickupItems == 1;
        }
        return soleMovableShulker;
    }

    static boolean maySubmit(int confirmedTransfers) {
        return confirmedTransfers == 0;
    }
}
