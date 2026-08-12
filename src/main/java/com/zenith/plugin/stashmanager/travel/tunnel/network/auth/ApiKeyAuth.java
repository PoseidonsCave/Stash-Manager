package com.zenith.plugin.stashmanager.travel.tunnel.network.auth;

import java.net.URI;
import java.net.http.HttpRequest;

// Adds an X-API-Key header.
public final class ApiKeyAuth implements AuthProvider {

    private final String apiKey;

    public ApiKeyAuth(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override
    public void apply(HttpRequest.Builder builder, String method, URI uri, String body) {
        if (!apiKey.isEmpty()) {
            builder.header("X-API-Key", apiKey);
        }
    }
}