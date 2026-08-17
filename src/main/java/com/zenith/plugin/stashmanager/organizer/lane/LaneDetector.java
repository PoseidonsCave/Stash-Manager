package com.zenith.plugin.stashmanager.organizer.lane;

import com.zenith.mc.block.Direction;
import com.zenith.plugin.stashmanager.index.ContainerEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

// Detects chest -> hopper -> chest FIFO lanes from already-scanned container data.
// Read-only: identifies lanes without changing organizer/retriever behavior.
public final class LaneDetector {

    private LaneDetector() {}

    public static List<FifoLane> detectLanes(Collection<ContainerEntry> containers) {
        IndexedStorageGeometry storage = new IndexedStorageGeometry(containers);

        List<FifoLane> lanes = new ArrayList<>();
        for (ContainerEntry entry : containers) {
            if (!"minecraft:hopper".equals(entry.blockType()) || entry.hopperFacing() == null) continue;

            Direction facing;
            try {
                facing = Direction.valueOf(entry.hopperFacing());
            } catch (IllegalArgumentException e) {
                continue;
            }

            ContainerEntry input = storage.findAt(
                entry.x(), entry.y() + 1, entry.z(), facing.x(), facing.z());
            if (input == null) continue;

            ContainerEntry output = storage.findAt(
                entry.x() + facing.x(), entry.y() + facing.y(), entry.z() + facing.z(),
                facing.x(), facing.z());
            if (output == null) continue;

            lanes.add(new FifoLane(
                new int[]{input.x(), input.y(), input.z()},
                new int[]{entry.x(), entry.y(), entry.z()},
                new int[]{output.x(), output.y(), output.z()}
            ));
        }
        return lanes;
    }

}
