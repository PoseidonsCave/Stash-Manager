package com.zenith.plugin.stashmanager.kit;

import java.util.List;
import java.util.Map;

/** Fail-closed admission for reconciling one exported box after a close timeout. */
public final class KitExportAudit {
    private KitExportAudit() { }

    public static String blocker(KitFulfillmentJournalStore.Snapshot snapshot,
                                 int expectedFullBoxes, int observedFullBoxes,
                                 int matchingPlayerBoxes, int currentItemLooseCount,
                                 int cursorCount) {
        if (snapshot == null || !"BLOCKED".equals(snapshot.state())
                || !"kit_export_failed:export_close_timeout".equals(snapshot.blocker())
                || snapshot.resumeState() != null
                || snapshot.borrowedSourceWorksite() != null
                || snapshot.kitWorksite() != null
                || !snapshot.sourceBoxesToReturn().isEmpty()
                || !snapshot.writtenOffSources().isEmpty()
                || !snapshot.currentBoxMissing().isEmpty()
                || !snapshot.packedContents().equals(snapshot.recipe())
                || snapshot.completedBoxes() < 0
                || snapshot.completedBoxes() + snapshot.incompleteBoxes()
                    >= snapshot.requestedBoxes()) {
            return "checkpoint is not an auditable completed-box export timeout";
        }
        if (expectedFullBoxes != snapshot.completedBoxes() + 1
                || observedFullBoxes != expectedFullBoxes) {
            return "exact full-kit count in the export chest does not equal the next box number";
        }
        if (matchingPlayerBoxes != 0 || cursorCount != 0) {
            return "the completed kit is still in inventory or on the cursor";
        }
        if (currentItemLooseCount != 0) {
            return "the checkpoint item is still loose in inventory";
        }
        return null;
    }

    /** Move only the witnessed delivery across the checkpoint boundary; never start a job here. */
    public static KitFulfillmentJournalStore.Snapshot reconciledSnapshot(
            KitFulfillmentJournalStore.Snapshot snapshot, long nowEpochMilli) {
        return new KitFulfillmentJournalStore.Snapshot(
                KitFulfillmentJournalStore.SCHEMA_VERSION, snapshot.kitName(), "BLOCKED",
                snapshot.recipe(), Map.of(), snapshot.requestedBoxes(),
                snapshot.completedBoxes() + 1, null, 0, -1, snapshot.exportPosition(),
                null, null, null, Map.of(), "export_delivery_audited",
                nowEpochMilli, null, 0, List.of(), -1,
                snapshot.writtenOffSources(), Map.of(), snapshot.incompleteBoxes(),
                snapshot.missingTotals(), Map.of());
    }
}
