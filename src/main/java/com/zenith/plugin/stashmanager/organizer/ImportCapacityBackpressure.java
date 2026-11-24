package com.zenith.plugin.stashmanager.organizer;

import java.util.Collection;
import java.util.HashSet;

/** Do not repack lane-less cargo when every registered import was live-rejected. */
final class ImportCapacityBackpressure {
    private ImportCapacityBackpressure() { }

    static boolean stopBeforeRepack(Collection<Long> registeredInventories,
                                    Collection<Long> rejectedInventories,
                                    boolean laneBackedCargoPending) {
        if (registeredInventories == null || rejectedInventories == null
                || laneBackedCargoPending) return false;
        var registered = new HashSet<>(registeredInventories);
        return !registered.isEmpty() && rejectedInventories.containsAll(registered);
    }
}
