package com.zenith.plugin.stashmanager.travel.tunnel.network.dto;

/** Server capabilities advertised by the tunnel backend. */
public class CapabilitiesDto {
    public String service = "unknown";
    public String apiVersion = "1";
    public boolean uploadTunnels = true;
    public boolean downloadTunnels = true;
    public boolean hmacAuth = true;
    public int maxBatchSize = 100;
    public String message;
}