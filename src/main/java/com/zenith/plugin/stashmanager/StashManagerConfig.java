package com.zenith.plugin.stashmanager;

import java.util.ArrayList;
import java.util.List;

// Persistent configuration, serialized via PluginAPI.registerConfig().
public class StashManagerConfig {
    public boolean enabled = true;
    public int[] pos1 = null;
    public int[] pos2 = null;
    public int scanDelayTicks = 5;
    // 2b2t/container responses regularly exceed three seconds under load. The scanner also
    // enforces this 20-second minimum at runtime for existing config files created with 60.
    public int openTimeoutTicks = 400;
    public int maxContainers = 2048;
    public int waypointDistance = 48;
    // Long stash jobs yield shared automation, then wait this long before resuming.
    public int scanPreemptionCooldownSeconds = 300;
    // A controlling proxy client must switch to spectator inside this window or the paused
    // scan/organize checkpoint is discarded. Completed world changes are not rolled back.
    public int proxyControlGraceSeconds = 600;
    public List<int[]> supplyChests = new ArrayList<>();

    // Return-to-start: pathfind the bot back to its initial position after scanning
    public boolean returnToStart = true;

    // Organizer
    public boolean organizerEnabled = true;
    // Zenith's own InventoryManager only executes a queued action every actionDelayTicks (5
    // by default) — submitting faster than that gets silently rejected, so this must stay
    // at or above that value.
    public int organizerClickCooldownTicks = 6;
    public int organizerOpenTimeoutTicks = 60;
    // Dense shelving/silo layouts can need well over 20s for Baritone to compute+execute a
    // path without block-breaking rights — raised from 400 after repeated real-world timeouts.
    public int organizerWalkTimeoutTicks = 1200;
    // Minimum loose item count to justify packing into a shulker box
    public int condenseMinItems = 1;

    // PostgreSQL database
    public boolean databaseEnabled = false;
    public String databaseUrl = "jdbc:postgresql://localhost:5432/stashmanager";
    public String databaseUser = "stashmanager";
    public String databasePassword = "";
    public int databasePoolSize = 3;

    // Embedded API server
    public boolean apiEnabled = false;
    public String apiBindAddress = "0.0.0.0";
    public int apiPort = 8585;
    public int apiThreads = 2;
    public String apiKey = "";

    // Plugin updates
    public boolean updateCheckOnLoad = true;
    public boolean updateAutoDownload = false;

    // Bedrock-floor tunnel travel system
    public boolean tunnelsEnabled = true;
    // Y level at which horizontal tunnels run (above bedrock, below lava lakes).
    public int tunnelFloorY = 8;
    // Automatically scan loaded chunks for pre-existing tunnels.
    public boolean tunnelScanLoadedChunks = true;
    // Dig a new tunnel when no existing route is found.
    public boolean tunnelBuildWhenNoExisting = true;
    // Maximum extra blocks the bot will walk to reach an existing tunnel entry.
    public int tunnelMaxEntryDetour = 500;

    // Tunnel network API (generic — targets any compatible REST backend)
    public boolean tunnelNetworkEnabled = false;
    public String tunnelNetworkEndpointUrl = "";
    // Auth method: "none", "bearer_token", "api_key", "hmac".
    public String tunnelNetworkAuthMethod = "bearer_token";
    public String tunnelNetworkAuthCredential = "";
    public int tunnelNetworkSyncIntervalMinutes = 30;
    public boolean tunnelNetworkUploadDiscoveries = true;
    public boolean tunnelNetworkDownloadRoutes = true;
}
