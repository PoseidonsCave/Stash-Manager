package com.zenith.plugin.stashmanager.travel.tunnel.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.plugin.stashmanager.travel.tunnel.network.auth.ApiKeyAuth;
import com.zenith.plugin.stashmanager.travel.tunnel.network.auth.AuthProvider;
import com.zenith.plugin.stashmanager.travel.tunnel.network.auth.BearerTokenAuth;
import com.zenith.plugin.stashmanager.travel.tunnel.network.auth.HmacAuth;
import com.zenith.plugin.stashmanager.travel.tunnel.network.dto.CapabilitiesDto;
import com.zenith.plugin.stashmanager.travel.tunnel.network.dto.TunnelDto;
import com.zenith.plugin.stashmanager.travel.tunnel.network.dto.TunnelSubmissionDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Generic HTTP client for tunnel network backends. */
public final class TunnelNetworkClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("StashManager/TunnelNetworkClient");
    private static final Gson GSON = new GsonBuilder().create();

    private final TunnelNetworkConfig config;
    private final AuthProvider authProvider;
    private final HttpClient httpClient;

    public TunnelNetworkClient(TunnelNetworkConfig config) {
        this.config = config;
        this.authProvider = createAuthProvider(config);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public Optional<CapabilitiesDto> fetchCapabilities() {
        if (!config.isConfigured()) return Optional.empty();
        HttpResponse<String> response = send("GET", resolve("capabilities"), null);
        if (!isSuccess(response)) return Optional.empty();
        try {
            return Optional.ofNullable(GSON.fromJson(response.body(), CapabilitiesDto.class));
        } catch (Exception e) {
            LOGGER.debug("Failed to parse tunnel capabilities: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public List<TunnelDto> downloadTunnels() {
        if (!config.isConfigured() || !config.downloadRoutes) return List.of();
        HttpResponse<String> response = send("GET", resolve("tunnels"), null);
        if (!isSuccess(response)) return List.of();
        try {
            JsonElement element = JsonParser.parseString(response.body());
            if (element.isJsonArray()) {
                return parseTunnelArray(element.getAsJsonArray());
            }
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                if (object.has("tunnels") && object.get("tunnels").isJsonArray()) {
                    return parseTunnelArray(object.getAsJsonArray("tunnels"));
                }
                if (object.has("routes") && object.get("routes").isJsonArray()) {
                    return parseTunnelArray(object.getAsJsonArray("routes"));
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to parse tunnel download payload: {}", e.getMessage());
        }
        return List.of();
    }

    public boolean uploadTunnels(TunnelSubmissionDto submission) {
        if (!config.isConfigured() || !config.uploadDiscoveries || submission.tunnels.isEmpty()) return false;
        String body = GSON.toJson(submission);
        HttpResponse<String> response = send("POST", resolve("tunnels"), body);
        return isSuccess(response);
    }

    private List<TunnelDto> parseTunnelArray(JsonArray array) {
        List<TunnelDto> tunnels = new ArrayList<>();
        for (JsonElement element : array) {
            try {
                tunnels.add(GSON.fromJson(element, TunnelDto.class));
            } catch (Exception ignored) {
                // Skip malformed entries.
            }
        }
        return tunnels;
    }

    private HttpResponse<String> send(String method, URI uri, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json");

            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            }

            authProvider.apply(builder, method, uri, body == null ? "" : body);
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.debug("Tunnel network request interrupted: {}", e.getMessage());
        } catch (IOException e) {
            LOGGER.debug("Tunnel network request failed: {}", e.getMessage());
        } catch (Exception e) {
            LOGGER.debug("Tunnel network request error: {}", e.getMessage());
        }
        return null;
    }

    private boolean isSuccess(HttpResponse<String> response) {
        return response != null && response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private URI resolve(String path) {
        return config.endpointUri.resolve(path);
    }

    private static AuthProvider createAuthProvider(TunnelNetworkConfig config) {
        return switch (config.authMethod) {
            case "bearer_token" -> new BearerTokenAuth(config.authCredential);
            case "api_key" -> new ApiKeyAuth(config.authCredential);
            case "hmac" -> new HmacAuth(config.authCredential);
            default -> (builder, method, uri, body) -> { };
        };
    }
}