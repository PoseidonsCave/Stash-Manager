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

    // Return-to-start: pathfind the bot back to its initial position after scanning
    public boolean returnToStart = true;

    // Organizer
    public boolean organizerEnabled = true;
    public int organizerClickCooldownTicks = 3;
    public int organizerOpenTimeoutTicks = 60;
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

    // Webhook (n8n, etc.) — POST JSON on scan completion
    public String webhookUrl = "";

    // Plugin updates
    public boolean updateCheckOnLoad = true;
    public boolean updateAutoDownload = false;

    // Bedrock-floor tunnel travel system
    public boolean tunnelsEnabled = true;
    /** Y level at which horizontal tunnels run (above bedrock, below lava lakes). */
    public int tunnelFloorY = 8;
    /** Automatically scan loaded chunks for pre-existing tunnels. */
    public boolean tunnelScanLoadedChunks = true;
    /** Dig a new tunnel when no existing route is found. */
    public boolean tunnelBuildWhenNoExisting = true;
    /** Maximum extra blocks the bot will walk to reach an existing tunnel entry. */
    public int tunnelMaxEntryDetour = 500;

    // Tunnel network API (generic — targets any compatible REST backend)
    public boolean tunnelNetworkEnabled = false;
    public String tunnelNetworkEndpointUrl = "";
    /** Auth method: "none", "bearer_token", "api_key", "hmac". */
    public String tunnelNetworkAuthMethod = "bearer_token";
    public String tunnelNetworkAuthCredential = "";
    public int tunnelNetworkSyncIntervalMinutes = 30;
    public boolean tunnelNetworkUploadDiscoveries = true;
    public boolean tunnelNetworkDownloadRoutes = true;

    // ── Stealth / operation timing ────────────────────────────────────────────
    /** Minimum random delay (ticks) added between successive inventory-click operations. */
    public int stealthClickDelayMinTicks = 1;
    /** Maximum random delay (ticks) added between successive inventory-click operations. */
    public int stealthClickDelayMaxTicks = 4;
    /** Extra pause (ticks) between opening successive containers in a deposit run. 0 = no gap. */
    public int stealthChestGapTicks = 5;
}
