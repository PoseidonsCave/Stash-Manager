package com.zenith.plugin.stashmanager.kit;

import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Read-only lane disposition for the apples displaced by a completed kit. */
public final class KitReturnPlaybook {
    public enum Status {
        WAITING_FOR_JOB,
        INVENTORY_UNAVAILABLE,
        INVENTORY_CHANGED,
        LANE_ASSIGNMENT_REQUIRED,
        SCAN_UNTRUSTED,
        TARGET_AUDIT_REQUIRED,
        NO_EXCESS
    }

    public record Lane(int x, int y, int z) { }

    public record Entry(int rawSlot, KitInventoryAppleOwnership.Slot expected,
                        Lane lane, Status status) { }

    public record Plan(Status status, int totalUnits, List<Entry> entries) {
        public Plan {
            entries = List.copyOf(entries);
        }
    }

    private KitReturnPlaybook() { }

    /**
     * A saved lane is a proposed destination, not transfer permission. Before
     * each click an executor must verify the exact live stack, target footprint,
     * free capacity, and server-confirmed inventory delta under a checkpoint.
     */
    public static Plan inspect(int delivered, int requested,
                               Map<Integer, KitInventoryAppleOwnership.Slot> excess,
                               int[] assignedAppleLane, boolean scanTrusted,
                               Map<Integer, ItemStack> liveInventory) {
        if (excess == null) throw new IllegalArgumentException("return evidence missing");
        if (delivered < requested) return new Plan(Status.WAITING_FOR_JOB, 0, List.of());
        if (excess.isEmpty()) return new Plan(Status.NO_EXCESS, 0, List.of());
        Lane lane = assignedAppleLane != null && assignedAppleLane.length == 3
                ? new Lane(assignedAppleLane[0], assignedAppleLane[1], assignedAppleLane[2])
                : null;
        List<Entry> entries = new ArrayList<>();
        int total = 0;
        for (var item : excess.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey)).toList()) {
            int raw = item.getKey();
            KitInventoryAppleOwnership.Slot expected = item.getValue();
            if (raw < 9 || raw > 44 || expected == null || expected.amount() < 1
                    || expected.amount() > 64 || expected.componentHash() == null
                    || expected.componentHash().isBlank()) {
                throw new IllegalArgumentException("invalid excess slot evidence");
            }
            total = Math.addExact(total, expected.amount());
            Status slotStatus;
            if (liveInventory == null) slotStatus = Status.INVENTORY_UNAVAILABLE;
            else if (!expected.equals(KitInventoryAppleOwnership.evidence(liveInventory.get(raw)))) {
                slotStatus = Status.INVENTORY_CHANGED;
            } else if (lane == null) slotStatus = Status.LANE_ASSIGNMENT_REQUIRED;
            else if (!scanTrusted) slotStatus = Status.SCAN_UNTRUSTED;
            else slotStatus = Status.TARGET_AUDIT_REQUIRED;
            entries.add(new Entry(raw, expected, lane, slotStatus));
        }
        Status overall = entries.stream().map(Entry::status)
                .filter(s -> s == Status.INVENTORY_UNAVAILABLE || s == Status.INVENTORY_CHANGED)
                .findFirst().orElseGet(() -> lane == null ? Status.LANE_ASSIGNMENT_REQUIRED
                        : !scanTrusted ? Status.SCAN_UNTRUSTED : Status.TARGET_AUDIT_REQUIRED);
        return new Plan(overall, total, entries);
    }
}
