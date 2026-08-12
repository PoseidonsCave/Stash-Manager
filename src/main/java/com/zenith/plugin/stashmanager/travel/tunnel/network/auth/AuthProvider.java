package com.zenith.plugin.stashmanager.travel.tunnel.network.auth;

import java.net.URI;
import java.net.http.HttpRequest;

// Applies authentication headers to outgoing tunnel-network requests.
@FunctionalInterface
public interface AuthProvider {

    void apply(HttpRequest.Builder builder, String method, URI uri, String body);
}