package com.zenith.plugin.stashmanager.travel.tunnel.network;

import com.zenith.plugin.stashmanager.StashManagerConfig;

import java.net.URI;

// Configuration snapshot for the tunnel network sync client.
public final class TunnelNetworkConfig {

    public final boolean enabled;
    public final URI endpointUri;
    public final String authMethod;
    public final String authCredential;
    public final int syncIntervalMinutes;
    public final boolean uploadDiscoveries;
    public final boolean downloadRoutes;

    public TunnelNetworkConfig(boolean enabled,
                               URI endpointUri,
                               String authMethod,
                               String authCredential,
                               int syncIntervalMinutes,
                               boolean uploadDiscoveries,
                               boolean downloadRoutes) {
        this.enabled = enabled;
        this.endpointUri = endpointUri;
        this.authMethod = authMethod;
        this.authCredential = authCredential;
        this.syncIntervalMinutes = syncIntervalMinutes;
        this.uploadDiscoveries = uploadDiscoveries;
        this.downloadRoutes = downloadRoutes;
    }

    public TunnelNetworkConfig(StashManagerConfig config) {
        this(
            config.tunnelNetworkEnabled,
            normalizeEndpoint(config.tunnelNetworkEndpointUrl),
            normalizeAuthMethod(config.tunnelNetworkAuthMethod),
            config.tunnelNetworkAuthCredential == null ? "" : config.tunnelNetworkAuthCredential,
            Math.max(1, config.tunnelNetworkSyncIntervalMinutes),
            config.tunnelNetworkUploadDiscoveries,
            config.tunnelNetworkDownloadRoutes
        );
    }

    public static TunnelNetworkConfig from(StashManagerConfig config) {
        return new TunnelNetworkConfig(config);
    }

    public boolean isConfigured() {
        return enabled && endpointUri != null;
    }

    private static URI normalizeEndpoint(String endpointUrl) {
        if (endpointUrl == null) return null;
        String value = endpointUrl.trim();
        if (value.isEmpty()) return null;
        if (!value.endsWith("/")) value = value + "/";
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalizeAuthMethod(String authMethod) {
        if (authMethod == null || authMethod.isBlank()) return "none";
        return authMethod.trim().toLowerCase();
    }
}