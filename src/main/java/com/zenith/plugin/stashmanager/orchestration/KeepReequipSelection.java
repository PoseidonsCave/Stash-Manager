package com.zenith.plugin.stashmanager.orchestration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Selects one safe equip move without replacing worn items or guessing between gear types. */
public final class KeepReequipSelection {
    private KeepReequipSelection() {}

    public record Swap(int source, int target, String itemId) {}

    public static Swap next(Map<Integer, String> slots, Set<String> allowed) {
        for (int target : List.of(5, 6, 7, 8, 45)) {
            if (slots.containsKey(target)) continue;
            int source = -1;
            String selected = null;
            boolean ambiguous = false;
            for (int slot = 9; slot <= 44; slot++) {
                String id = slots.get(slot);
                if (id == null || !allowed.contains(id)
                        || KeepReequipPlan.equipmentSlot(id) != target) continue;
                if (selected != null && !selected.equals(id)) {
                    ambiguous = true;
                    break;
                }
                selected = id;
                source = slot;
            }
            if (!ambiguous && source >= 0) return new Swap(source, target, selected);
        }
        return null;
    }

    public static List<Integer> ambiguousTargets(Map<Integer, String> slots, Set<String> allowed) {
        List<Integer> ambiguous = new ArrayList<>();
        for (int target : List.of(5, 6, 7, 8, 45)) {
            if (slots.containsKey(target)) continue;
            Set<String> types = new HashSet<>();
            for (int slot = 9; slot <= 44; slot++) {
                String id = slots.get(slot);
                if (id != null && allowed.contains(id)
                        && KeepReequipPlan.equipmentSlot(id) == target) types.add(id);
            }
            if (types.size() > 1) ambiguous.add(target);
        }
        return List.copyOf(ambiguous);
    }
}
