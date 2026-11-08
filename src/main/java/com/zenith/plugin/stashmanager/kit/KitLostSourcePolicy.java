package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.index.ContainerEntry;

import java.util.List;
import java.util.Map;

/** Operator-attested loss is a narrow checkpoint repair, not proof of despawn. */
public final class KitLostSourcePolicy {
    private KitLostSourcePolicy() { }

    /** The physical slot may be ambiguous, but the source inventory must not be. */
    public static ContainerEntry borrowedOrigin(List<ContainerEntry> entries,
                                                String boxItemId,
                                                Map<String, Integer> contents) {
        if (entries == null || boxItemId == null || contents == null || contents.isEmpty()) {
            return null;
        }
        String box = boxItemId.startsWith("minecraft:")
                ? boxItemId.substring("minecraft:".length()) : boxItemId;
        ContainerEntry origin = null;
        for (ContainerEntry entry : entries) {
            if (entry == null || !entry.inventoryFootprintKnown()) continue;
            boolean matches = entry.shulkerDetails().stream().anyMatch(detail ->
                    detail.isPhysicalInstance() && contents.equals(detail.items())
                            && ("unknown".equals(detail.color()) ? "shulker_box"
                                    : detail.color() + "_shulker_box").equals(box));
            if (!matches) continue;
            if (origin != null && origin.inventoryKey() != entry.inventoryKey()) return null;
            if (origin == null) origin = entry;
        }
        return origin;
    }

    /** Attestation clears only the absent borrowed worksite; loose cargo remains unaudited. */
    public static String borrowedBlocker(KitFulfillmentJournalStore.Snapshot snapshot,
                                         int declaredLastKnownUnits, boolean worksiteLoaded,
                                         boolean worksiteAir, boolean ownedShellMatches,
                                         int inventorySourceMatches, String recoveryBlocker,
                                         ContainerEntry origin) {
        if (snapshot == null || !"BLOCKED".equals(snapshot.state())
                || snapshot.resumeState() != null
                || !"kit_cargo_retrieval_incomplete".equals(snapshot.blocker())
                || snapshot.kitWorksite() != null
                || snapshot.borrowedSourceWorksite() == null
                || snapshot.borrowedSourceItemId() == null
                || snapshot.borrowedSourceContents().isEmpty()
                || !snapshot.sourceBoxesToReturn().isEmpty()
                || snapshot.currentItem() == null || snapshot.currentQuantity() <= 0
                || snapshot.completedBoxes() + snapshot.incompleteBoxes()
                    >= snapshot.requestedBoxes()) {
            return "checkpoint is not at the absent borrowed-source boundary";
        }
        long expected = snapshot.borrowedSourceContents().values().stream()
                .mapToLong(Integer::longValue).sum();
        if (expected <= 0 || declaredLastKnownUnits != expected) {
            return "declared last-known units do not match the checkpoint";
        }
        if (!"journaled_box_absent_or_already_held:borrowed_source".equals(recoveryBlocker)) {
            return "worksite recovery has not confirmed an absent borrowed source";
        }
        if (!worksiteLoaded || !worksiteAir) {
            return "journaled borrowed worksite is not loaded and empty";
        }
        if (!ownedShellMatches) return "packed kit shell is missing or ambiguous";
        if (inventorySourceMatches != 0) {
            return "borrowed source is still present or inventory identity is unknown";
        }
        if (origin == null) return "indexed source inventory is missing or ambiguous";
        return null;
    }

    public static String blocker(KitFulfillmentJournalStore.Snapshot snapshot,
                                 int declaredUnits, boolean worksiteLoaded,
                                 boolean worksiteAir, int ownedShellSlot,
                                 int cargoSlot, int inventorySourceMatches,
                                 String recoveryBlocker) {
        if (snapshot == null || !"BLOCKED".equals(snapshot.state())
                || snapshot.resumeState() != null
                || !"kit_pack_failed:shell_contents_changed".equals(snapshot.blocker())
                || snapshot.kitWorksite() == null
                || snapshot.borrowedSourceWorksite() != null
                || snapshot.sourceBoxesToReturn().size() != 1
                || snapshot.currentItem() == null || snapshot.currentQuantity() <= 0
                || snapshot.auditedCargoSlot() < 9
                || snapshot.completedBoxes() + snapshot.incompleteBoxes()
                    >= snapshot.requestedBoxes()) {
            return "checkpoint is not at the audited misplaced-source boundary";
        }
        var source = snapshot.sourceBoxesToReturn().getFirst();
        long expectedUnits = source.contents().values().stream()
                .mapToLong(Integer::longValue).sum();
        if (expectedUnits <= 0 || declaredUnits != expectedUnits) {
            return "declared source unit count does not match the checkpoint";
        }
        if (recoveryBlocker == null
                || !(recoveryBlocker.equals("possible_dropped_box_collected_needs_identity_review:kit_box")
                    || recoveryBlocker.equals("journaled_box_absent_or_already_held:kit_box"))) {
            return "worksite recovery has not confirmed an absent kit worksite";
        }
        if (!worksiteLoaded || !worksiteAir) return "journaled worksite is not loaded and empty";
        if (ownedShellSlot != snapshot.ownedShellSlot()) {
            return "packed kit shell is missing or ambiguous";
        }
        if (cargoSlot != snapshot.auditedCargoSlot()) {
            return "loose kit cargo is missing or ambiguous";
        }
        if (inventorySourceMatches != 0) {
            return "borrowed source is still present or inventory identity is unknown";
        }
        return null;
    }
}
