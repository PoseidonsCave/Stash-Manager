package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.database.DatabaseManager;
import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.index.ContainerIndex;

import java.sql.SQLException;
import java.util.Map;

/** Admission before each intact-box transfer. The port still proves live contents and identity. */
public final class KitWorkingFillAdmission {
    private KitWorkingFillAdmission() { }

    public static String blocker(DatabaseManager database, ContainerIndex index,
                                 int[] regionPos1, int[] regionPos2,
                                 KitWorkingTransferJournal.Ticket ticket,
                                 boolean exclusiveAutomationIdle) throws SQLException {
        if (!exclusiveAutomationIdle) return "another automation or controller is active";
        if (database == null || !database.isInitialized() || index == null
                || !index.isDatabaseWriteHealthy()) return "database or index writes are unavailable";
        if (regionPos1 == null || regionPos1.length != 3
                || regionPos2 == null || regionPos2.length != 3)
            return "stash region is not configured";
        KitWorkingTransferJournal.validate(ticket);
        var scan = database.getLatestScanSummary(regionPos1, regionPos2).orElse(null);
        if (scan == null || !scan.completed() || scan.failed() != 0
                || scan.found() != scan.indexed()
                || !database.latestScanHasFullItemIdentity(regionPos1, regionPos2))
            return "complete current-identity scan required";
        int[] source = ticket.source();
        int[] destination = ticket.destination();
        if (!inside(source, regionPos1, regionPos2)
                || !inside(destination, regionPos1, regionPos2))
            return "transfer leaves the scanned region";
        ContainerEntry indexedSource = index.get(source[0], source[1], source[2]);
        if (indexedSource == null || !indexedSource.inventoryFootprintKnown()
                || indexedSource.timestamp() < scan.startedAtEpochMilli()
                || index.isImportChest(indexedSource) || index.isExportChest(indexedSource)
                || index.isBlacklistedChest(indexedSource)
                || index.isKitWorkingChest(indexedSource))
            return "source is untrusted or has a protected role";
        boolean boxIndexed = indexedSource.shulkerDetails().stream().anyMatch(detail ->
                detail.isPhysicalInstance() && detail.slot() == ticket.sourceSlot()
                && detail.items().equals(Map.of(ticket.itemId(), ticket.units())));
        if (!boxIndexed) return "exact source box is absent from the current index";
        ContainerEntry indexedDestination = index.get(destination[0], destination[1],
                destination[2]);
        if (indexedDestination == null || !indexedDestination.isDouble()
                || !indexedDestination.inventoryFootprintKnown()
                || indexedDestination.timestamp() < scan.startedAtEpochMilli()
                || !index.isKitWorkingChest(indexedDestination))
            return "destination is not a trusted working double chest";
        int partnerX = indexedDestination.inventoryX()
                + ("X".equals(indexedDestination.doubleChestAxis()) ? 1 : 0);
        int partnerZ = indexedDestination.inventoryZ()
                + ("Z".equals(indexedDestination.doubleChestAxis()) ? 1 : 0);
        if ((partnerX == indexedDestination.inventoryX()
                && partnerZ == indexedDestination.inventoryZ())
                || !index.isKitWorkingChest(indexedDestination.inventoryX(),
                    indexedDestination.inventoryY(), indexedDestination.inventoryZ())
                || !index.isKitWorkingChest(partnerX, indexedDestination.inventoryY(), partnerZ))
            return "working double-chest footprint is incomplete";
        boolean pending = database.loadKitWorkingSlots().stream().anyMatch(row ->
                row.x() == destination[0] && row.y() == destination[1]
                && row.z() == destination[2] && row.slot() == ticket.destinationSlot()
                && row.kitName().equals(ticket.kitName())
                && row.itemId().equals(ticket.itemId())
                && row.plannedUnits() == ticket.units()
                && "pending".equals(row.status()) && row.boxFingerprint() == null);
        return pending ? null : "destination slot is not reserved for this exact box";
    }

    private static boolean inside(int[] pos, int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (pos[i] < Math.min(a[i], b[i]) || pos[i] > Math.max(a[i], b[i]))
                return false;
        }
        return true;
    }
}
