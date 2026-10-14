package com.zenith.plugin.stashmanager.orchestration;

import com.zenith.plugin.stashmanager.util.ItemIdentifier;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Refills finite keep targets and one replacement for unbounded gear rules. */
public record KeepReequipPlan(Map<String, Integer> requested,
                              Set<String> equipment,
                              Set<String> skippedUnlimited) {
    public KeepReequipPlan {
        requested = Map.copyOf(requested);
        equipment = Set.copyOf(equipment);
        skippedUnlimited = Set.copyOf(skippedUnlimited);
    }

    public static KeepReequipPlan from(Map<String, Integer> kept,
                                       Map<String, Integer> carriedAndEquipped) {
        Map<String, Integer> requested = new LinkedHashMap<>();
        Set<String> equipment = new LinkedHashSet<>();
        Set<String> skipped = new LinkedHashSet<>();
        if (kept == null) return new KeepReequipPlan(requested, equipment, skipped);
        kept.forEach((rawId, limit) -> {
            String id = ItemIdentifier.baseItemId(rawId);
            if (id == null || id.isBlank()) return;
            if (limit != null && limit <= 0) return;
            boolean wearable = equipmentSlot(id) >= 0;
            if (wearable) equipment.add(id);
            int target;
            if (limit == null) {
                if (!isUnboundedGear(id)) {
                    skipped.add(id);
                    return;
                }
                target = 1;
            } else {
                target = Math.max(0, limit);
            }
            int have = Math.max(0, carriedAndEquipped == null
                    ? 0 : carriedAndEquipped.getOrDefault(id, 0));
            if (target > have) requested.put(id, target - have);
        });
        return new KeepReequipPlan(requested, equipment, skipped);
    }

    /** Raw player inventory slot; -1 means the item stays in inventory. */
    public static int equipmentSlot(String itemId) {
        String id = ItemIdentifier.baseItemId(itemId);
        if (id == null) return -1;
        if (id.endsWith("_helmet") || id.equals("turtle_helmet")) return 5;
        if (id.endsWith("_chestplate") || id.equals("elytra")) return 6;
        if (id.endsWith("_leggings")) return 7;
        if (id.endsWith("_boots")) return 8;
        if (id.equals("totem_of_undying") || id.equals("shield")) return 45;
        return -1;
    }

    private static boolean isUnboundedGear(String id) {
        return equipmentSlot(id) >= 0 || id.endsWith("_sword") || id.endsWith("_pickaxe")
                || id.endsWith("_axe") || id.endsWith("_shovel") || id.endsWith("_hoe")
                || id.equals("bow") || id.equals("crossbow") || id.equals("trident")
                || id.equals("mace") || id.equals("fishing_rod") || id.equals("flint_and_steel")
                || id.equals("shears");
    }
}
