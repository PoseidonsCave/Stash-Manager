package com.zenith.plugin.stashmanager.api;

import com.zenith.plugin.stashmanager.StashManagerConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Safety checks for the embedded, non-TLS HTTP API. */
public final class ApiExposurePolicy {
    private ApiExposurePolicy() { }

    public static void validate(StashManagerConfig config) {
        if (config.apiKey == null || config.apiKey.isBlank()) {
            throw new IllegalStateException("API key is required before starting the API server");
        }
        if (config.apiBindAddress == null || config.apiBindAddress.isBlank()) {
            throw new IllegalStateException("API bind address is required");
        }
        if (!isLoopback(config.apiBindAddress) && !config.apiAllowRemoteHttp) {
            throw new IllegalStateException("Remote plain-HTTP API bind requires explicit apiAllowRemoteHttp opt-in");
        }
    }

    public static boolean isLoopback(String address) {
        return "127.0.0.1".equals(address) || "::1".equals(address)
                || "[::1]".equals(address);
    }

    public static boolean acceptsBearer(String configuredKey, String authorization) {
        if (configuredKey == null || configuredKey.isBlank()
                || authorization == null || !authorization.startsWith("Bearer ")) return false;
        byte[] expected = configuredKey.getBytes(StandardCharsets.UTF_8);
        byte[] supplied = authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, supplied);
    }
}
