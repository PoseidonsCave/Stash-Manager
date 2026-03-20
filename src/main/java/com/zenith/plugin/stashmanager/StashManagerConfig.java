package com.zenith.plugin.stashmanager;

import java.util.ArrayList;
import java.util.List;

// Persistent configuration, serialized via PluginAPI.registerConfig().
public class StashManagerConfig {
    public boolean enabled = true;
    public int[] pos1 = null;
    public int[] pos2 = null;
    public int scanDelayTicks = 5;
    public int openTimeoutTicks = 60;
    public int maxContainers = 2048;
    public int waypointDistance = 48;
    public List<int[]> supplyChests = new ArrayList<>();
}
