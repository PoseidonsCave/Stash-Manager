package com.zenith.plugin.stashmanager.travel.tunnel.network;

import com.zenith.plugin.stashmanager.StashManagerConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class TunnelNetworkConfigTest {
    @Test void credentialsAndCoordinatesRequireHttps() {
        var config = new StashManagerConfig();
        config.tunnelNetworkEnabled = true;
        config.tunnelNetworkEndpointUrl = "http://example.org/api";
        assertFalse(TunnelNetworkConfig.from(config).isConfigured());
        config.tunnelNetworkEndpointUrl = "https://example.org/api";
        assertTrue(TunnelNetworkConfig.from(config).isConfigured());
        config.tunnelNetworkEndpointUrl = "https://user:pass@example.org/api";
        assertFalse(TunnelNetworkConfig.from(config).isConfigured());
        config.tunnelNetworkEndpointUrl = "https://example.org/api?token=secret";
        assertFalse(TunnelNetworkConfig.from(config).isConfigured());
    }

    @Test void explicitConstructorCannotBypassTlsRequirement() {
        var unsafe = new TunnelNetworkConfig(true, URI.create("http://example.org"),
                "bearer_token", "secret", 1, true, true);
        assertFalse(unsafe.isConfigured());
    }
}
