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
}
