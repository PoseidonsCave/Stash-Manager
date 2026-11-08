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
    // Optional, site-specific walkable approach to a staircase/ladder used before an
    // upward organizer cargo trip. Leave unset unless an operator has verified it in world.
    public int[] organizerVerticalWaypoint = new int[0];
    // Ordered, operator-verified standing cells for multi-floor cargo trips. When set,
    // this supersedes organizerVerticalWaypoint for the exact destination below.
    // Each stage is an exact Baritone goal; unset destination never activates it.
    public int[][] organizerVerticalWaypoints = new int[0][];
    public int[] organizerVerticalRouteDestination = new int[0];
    // Optional reusable, operator-verified floor transitions. A rule applies only when the
    // starting Y lies in originYRange and the destination lies in targetBounds (inclusive).
    // Unlike the legacy exact-destination route, this can serve a whole bank of upper-floor
    // chests without letting a path to an unrelated floor inherit its ladder waypoints.
    public List<OrganizerVerticalRouteRule> organizerVerticalRoutes = new ArrayList<>();

    public static class OrganizerVerticalRouteRule {
        public String dimension = null; // e.g. OVERWORLD; must match the current dimension
        public int[] originYRange = null; // [minY, maxY]
        public int[] targetBounds = null; // [minX, minY, minZ, maxX, maxY, maxZ]
        public int[][] waypoints = null;  // exact, ordered standing cells
    }
    // Minimum loose item count to justify packing into a shulker box
    public int condenseMinItems = 1;
    // Opt-in: craft an empty box only when no usable packing box is available.
    public boolean craftShulkersEnabled = false;
    // Only these extra, explicitly assigned chests may supply crafting ingredients outside
    // the organizer's managed lanes/imports. They are not general import chests.
    public List<int[]> craftMaterialChests = new ArrayList<>();

    // PostgreSQL database
    public boolean databaseEnabled = false;
    public String databaseUrl = "jdbc:postgresql://localhost:5432/stashmanager";
    public String databaseUser = "stashmanager";
    public String databasePassword = "";
    public int databasePoolSize = 3;

    // Embedded API server
    public boolean apiEnabled = false;
    public String apiBindAddress = "127.0.0.1";
    public int apiPort = 8585;
    public int apiThreads = 2;
    public String apiKey = "";
    // The embedded API is plain HTTP. Remote binding requires an explicit opt-in and
    // a trusted TLS-terminating proxy/private network; a nonblank key is always required.
    public boolean apiAllowRemoteHttp = false;
    // Detailed world/pose/debug commands can expose coordinates and inventories.
    public boolean diagnosticCommandsEnabled = false;
    // Webhook titles remain useful without sending stash coordinates, item details,
    // or exception text to a third-party channel by default.
    public boolean webhookIncludeDetailedPayload = false;

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
