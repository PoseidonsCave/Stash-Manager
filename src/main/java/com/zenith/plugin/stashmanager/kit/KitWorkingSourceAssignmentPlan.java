package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.database.DatabaseManager;
import com.zenith.plugin.stashmanager.index.ContainerEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only candidate selection. Every chosen indexed box is rechecked in a live GUI. */
public final class KitWorkingSourceAssignmentPlan {
    private KitWorkingSourceAssignmentPlan() { }

    public record Assignment(DatabaseManager.KitWorkingSlotRow destination,
                             int[] source, int sourceSlot) {
        public Assignment { source = source.clone(); }
        @Override public int[] source() { return source.clone(); }
    }

    public record Report(List<Assignment> assignments, Map<String, Integer> missingBoxes) {
        public Report {
            assignments = List.copyOf(assignments);
            missingBoxes = Map.copyOf(missingBoxes);
        }
        public boolean ready() { return !assignments.isEmpty() && missingBoxes.isEmpty(); }
    }

    public static Report assign(List<DatabaseManager.KitWorkingSlotRow> slots,
                                List<ContainerEntry> indexedContainers,
                                Set<Long> excludedInventoryKeys,
                                long scanStartedAtEpochMilli) {
        if (slots == null || slots.isEmpty() || indexedContainers == null
                || excludedInventoryKeys == null || scanStartedAtEpochMilli <= 0)
            throw new IllegalArgumentException("pending slots and trusted source index required");
        List<DatabaseManager.KitWorkingSlotRow> orderedSlots = slots.stream()
                .sorted(Comparator.comparing(DatabaseManager.KitWorkingSlotRow::kitName)
                        .thenComparingInt(DatabaseManager.KitWorkingSlotRow::x)
                        .thenComparingInt(DatabaseManager.KitWorkingSlotRow::y)
                        .thenComparingInt(DatabaseManager.KitWorkingSlotRow::z)
                        .thenComparingInt(DatabaseManager.KitWorkingSlotRow::slot))
                .toList();
        Map<Long, ContainerEntry> physical = new LinkedHashMap<>();
        indexedContainers.stream().filter(entry -> entry != null
                && entry.inventoryFootprintKnown()
                && entry.timestamp() >= scanStartedAtEpochMilli
                && !excludedInventoryKeys.contains(entry.inventoryKey()))
                .sorted(Comparator.comparingInt(ContainerEntry::inventoryX)
                        .thenComparingInt(ContainerEntry::inventoryY)
                        .thenComparingInt(ContainerEntry::inventoryZ)
                        .thenComparingInt(ContainerEntry::x)
                        .thenComparingInt(ContainerEntry::z))
                .forEach(entry -> physical.putIfAbsent(entry.inventoryKey(), entry));
        List<Candidate> candidates = new ArrayList<>();
        for (ContainerEntry entry : physical.values()) {
            for (ContainerEntry.ShulkerDetail detail : entry.shulkerDetails()) {
                if (!detail.isPhysicalInstance() || detail.slot() >= (entry.isDouble() ? 54 : 27))
                    continue;
                candidates.add(new Candidate(new int[]{entry.x(), entry.y(), entry.z()},
                        detail.slot(), detail.items()));
            }
        }
        candidates.sort(Comparator.comparingInt((Candidate c) -> c.position()[0])
                .thenComparingInt(c -> c.position()[1])
                .thenComparingInt(c -> c.position()[2])
                .thenComparingInt(Candidate::slot));
        Set<String> used = new HashSet<>();
        List<Assignment> result = new ArrayList<>();
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (var slot : orderedSlots) {
            if (!"pending".equals(slot.status()) || slot.boxFingerprint() != null)
                throw new IllegalArgumentException("source assignment needs pending working slots");
            Candidate chosen = null;
            for (Candidate candidate : candidates) {
                String key = candidate.position()[0] + ":" + candidate.position()[1]
                        + ":" + candidate.position()[2] + ":" + candidate.slot();
                if (!used.contains(key)
                        && candidate.contents().equals(Map.of(slot.itemId(), slot.plannedUnits()))) {
                    chosen = candidate;
                    used.add(key);
                    break;
                }
            }
            if (chosen == null) missing.merge(slot.itemId() + " x" + slot.plannedUnits(), 1, Integer::sum);
            else result.add(new Assignment(slot, chosen.position(), chosen.slot()));
        }
        return missing.isEmpty() ? new Report(result, Map.of())
                : new Report(List.of(), missing);
    }

    private record Candidate(int[] position, int slot, Map<String, Integer> contents) { }
}
