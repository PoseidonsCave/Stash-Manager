package com.zenith.plugin.stashmanager.organizer;

import com.zenith.plugin.stashmanager.util.ItemIdentifier;

import java.util.Map;
import java.util.Set;

/** A kept passive supply may be swapped into the selected shulker's main slot. */
final class OrganizerHotbarExchangePolicy {
    private static final Set<String> PASSIVE_SUPPLIES = Set.of(
            "enchanted_golden_apple", "golden_apple", "golden_carrot",
            "cooked_beef", "cooked_chicken", "cooked_porkchop", "bread",
            "firework_rocket", "totem_of_undying");

    private OrganizerHotbarExchangePolicy() { }

    static int protectedSupplySlot(Map<Integer, String> hotbarItems,
                                   Set<Integer> protectedSlots, int heldRawSlot) {
        int heldCandidate = -1;
        for (int raw = 36; raw <= 44; raw++) {
            String id = hotbarItems.get(raw);
            if (!protectedSlots.contains(raw) || id == null
                    || !PASSIVE_SUPPLIES.contains(ItemIdentifier.baseItemId(id))) continue;
            if (raw != heldRawSlot) return raw;
            heldCandidate = raw;
        }
        return heldCandidate;
    }
}
