package com.zenith.plugin.stashmanager.organizer.lane;

// A chest -> hopper -> chest FIFO unit: items deposited at inputPos surface at
// outputPos once the hopper transfers them.
public record FifoLane(int[] inputPos, int[] hopperPos, int[] outputPos) {
}
