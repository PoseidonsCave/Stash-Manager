package com.zenith.plugin.stashmanager.orchestration;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Pure planning rules for decomposing mixed/kit shulkers into exact-item bulk storage. */
public final class MixedShulkerPlaybook {
    private static final int MAX_TRANSFER_BATCH_SLOTS = 4;
    private static final int RESERVED_HEADROOM_SLOTS = 1;

    private MixedShulkerPlaybook() {}

    public enum AdmissionDecision {
        READY,
        RECOVER_EXISTING_CARGO,
        INSUFFICIENT_HEADROOM
    }

    public record InventoryAdmission(
            AdmissionDecision decision,
            int freeSlots,
            int existingCargoSlots,
            int sourceOccupiedSlots,
            int requiredFreeSlots) {
        public boolean ready() {
            return decision == AdmissionDecision.READY;
        }
    }

    /** Counts every mixed-box item as loose demand that reconciliation must repack. */
    public static Map<String, Long> aggregateDemand(
            Collection<? extends Map<String, Integer>> mixedContents) {
        Map<String, Long> demand = new TreeMap<>();
        if (mixedContents == null) return Map.of();
        for (Map<String, Integer> contents : mixedContents) {
            if (contents == null) continue;
            contents.forEach((rawItemId, rawQuantity) -> {
                String storageClass = StorageClassPolicy.exact(rawItemId);
                int quantity = rawQuantity == null ? 0 : Math.max(0, rawQuantity);
                if (storageClass != null && quantity > 0) {
                    demand.merge(storageClass, (long) quantity, MixedShulkerPlaybook::saturatingAdd);
                }
            });
        }
        return new LinkedHashMap<>(demand);
    }

    /** Minimum slots needed after normal stack compaction; live staging may use more. */
    public static int minimumStagingSlots(Map<String, Integer> contents) {
        long slots = 0;
        for (var entry : aggregateDemand(contents == null ? null : java.util.List.of(contents)).entrySet()) {
            int stackSize = LaneStorageCapacity.itemCapacityFor(entry.getKey()).maxStackSize();
            slots = saturatingAdd(slots, ceilingDivision(entry.getValue(), stackSize));
        }
        return slots > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) slots;
    }

    /** Reserves one extra slot for the empty shell recovered after decomposition. */
    public static int minimumStagingSlotsWithShell(Map<String, Integer> contents) {
        int contentSlots = minimumStagingSlots(contents);
        return contentSlots == Integer.MAX_VALUE ? Integer.MAX_VALUE : contentSlots + 1;
    }

    /**
     * Allows one mixed box into the bot only when it is the sole unprotected cargo and a
     * bounded unpack batch can retain one unused recovery slot.
     */
    public static InventoryAdmission assessInventoryAdmission(
            int freeSlots,
            int existingCargoSlots,
            int sourceOccupiedSlots) {
        int safeFreeSlots = Math.max(0, freeSlots);
        int safeCargoSlots = Math.max(0, existingCargoSlots);
        int safeSourceSlots = Math.max(1, Math.min(27, sourceOccupiedSlots));
        int requiredFreeSlots = Math.min(MAX_TRANSFER_BATCH_SLOTS, safeSourceSlots)
                + RESERVED_HEADROOM_SLOTS;
        AdmissionDecision decision = safeCargoSlots > 0
                ? AdmissionDecision.RECOVER_EXISTING_CARGO
                : safeFreeSlots < requiredFreeSlots
                        ? AdmissionDecision.INSUFFICIENT_HEADROOM
                        : AdmissionDecision.READY;
        return new InventoryAdmission(
                decision,
                safeFreeSlots,
                safeCargoSlots,
                safeSourceSlots,
                requiredFreeSlots);
    }

    /** Stages the current batch before the last safe inventory slot can be consumed. */
    public static boolean shouldStageBeforeNextTransfer(int freeSlots, int cargoSlots) {
        return cargoSlots > 0 && freeSlots <= RESERVED_HEADROOM_SLOTS;
    }

    public static int reservedHeadroomSlots() {
        return RESERVED_HEADROOM_SLOTS;
    }

    /** Frees inventory slots before scheduling any remaining mixed-box decomposition. */
    public static <T> List<T> inventoryRecoveryOrder(
            Collection<T> inventoryEvacuation,
            Collection<T> scheduledMixedBoxes,
            Collection<T> newlyDiscoveredMixedBoxes) {
        List<T> ordered = new ArrayList<>();
        if (inventoryEvacuation != null) ordered.addAll(inventoryEvacuation);
        if (scheduledMixedBoxes != null) ordered.addAll(scheduledMixedBoxes);
        if (newlyDiscoveredMixedBoxes != null) ordered.addAll(newlyDiscoveredMixedBoxes);
        return List.copyOf(ordered);
    }

    private static long ceilingDivision(long value, int divisor) {
        if (value <= 0) return 0;
        return 1L + (value - 1L) / Math.max(1, divisor);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
