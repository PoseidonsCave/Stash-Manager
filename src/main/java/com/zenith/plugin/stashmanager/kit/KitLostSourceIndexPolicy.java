package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.index.ContainerEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Correct only a uniquely identified lost shulker in a scan candidate, in memory. */
public final class KitLostSourceIndexPolicy {
    private KitLostSourceIndexPolicy() { }

    public static ContainerEntry withoutLostBox(ContainerEntry entry,
                                                 KitFulfillmentJournalStore.BorrowedBox lost,
                                                 String withdrawnItem, int withdrawnQuantity) {
        if (entry == null || lost == null || !entry.inventoryFootprintKnown()) return null;
        // Some write-offs happened before any cargo was withdrawn. Older checkpoints do not
        // record the item withdrawn when the loss occurred, so the current task may differ.
        // Match either the unchanged box or the legacy reconstructed pre-withdrawal box,
        // but reject the whole inventory if more than one physical box matches either form.
        Map<String, Integer> after = lost.contents();
        Map<String, Integer> before = null;
        if (withdrawnItem != null && withdrawnQuantity > 0) {
            before = new LinkedHashMap<>(after);
            before.merge(withdrawnItem, withdrawnQuantity, Integer::sum);
        }
        var details = entry.shulkerDetails();
        int matchingIndex = -1;
        Map<String, Integer> matchedContents = null;
        for (int i = 0; i < details.size(); i++) {
            var detail = details.get(i);
            if (!detail.isPhysicalInstance()
                    || !matchesBoxItem(detail.color(), lost.itemId())
                    || (!after.equals(detail.items())
                        && (before == null || !before.equals(detail.items())))) continue;
            if (matchingIndex >= 0) return null;
            matchingIndex = i;
            matchedContents = detail.items();
        }
        if (matchingIndex < 0 || entry.shulkerCount() < 1) return null;
        Map<String, Integer> items = new LinkedHashMap<>(entry.items());
        if (!subtract(items, lost.itemId(), 1)) return null;
        for (var item : matchedContents.entrySet()) {
            if (!subtract(items, item.getKey(), item.getValue())) return null;
        }
        var remaining = new ArrayList<>(details);
        remaining.remove(matchingIndex);
        return entry.withContents(items, entry.shulkerCount() - 1,
                remaining, entry.timestamp(), entry.directStackSlots());
    }

    private static boolean subtract(Map<String, Integer> items, String id, int amount) {
        if (id == null || amount <= 0 || items.getOrDefault(id, 0) < amount) return false;
        int remaining = items.get(id) - amount;
        if (remaining == 0) items.remove(id);
        else items.put(id, remaining);
        return true;
    }

    private static boolean matchesBoxItem(String color, String itemId) {
        if (color == null || itemId == null) return false;
        String box = itemId.startsWith("minecraft:") ? itemId.substring(10) : itemId;
        return ("unknown".equals(color) ? "shulker_box" : color + "_shulker_box")
                .equals(box);
    }
}
