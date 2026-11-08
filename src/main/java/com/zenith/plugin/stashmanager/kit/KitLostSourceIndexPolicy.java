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
        if (entry == null || lost == null || withdrawnItem == null
                || withdrawnQuantity <= 0 || !entry.inventoryFootprintKnown()) return null;
        Map<String, Integer> before = new LinkedHashMap<>(lost.contents());
        before.merge(withdrawnItem, withdrawnQuantity, Integer::sum);
        var details = entry.shulkerDetails();
        int matchingIndex = -1;
        for (int i = 0; i < details.size(); i++) {
            var detail = details.get(i);
            if (!detail.isPhysicalInstance() || !before.equals(detail.items())
                    || !matchesBoxItem(detail.color(), lost.itemId())) continue;
            if (matchingIndex >= 0) return null;
            matchingIndex = i;
        }
        if (matchingIndex < 0 || entry.shulkerCount() < 1) return null;
        Map<String, Integer> items = new LinkedHashMap<>(entry.items());
        if (!subtract(items, lost.itemId(), 1)) return null;
        for (var item : before.entrySet()) {
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
