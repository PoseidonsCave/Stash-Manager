package com.zenith.plugin.stashmanager.travel.tunnel.network.auth;

import java.net.URI;
import java.net.http.HttpRequest;

// Adds a standard Authorization: Bearer token header.
public final class BearerTokenAuth implements AuthProvider {

    private final String token;

    public BearerTokenAuth(String token) {
        this.token = token == null ? "" : token.trim();
    }

    @Override
    public void apply(HttpRequest.Builder builder, String method, URI uri, String body) {
        if (!token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
    }
}