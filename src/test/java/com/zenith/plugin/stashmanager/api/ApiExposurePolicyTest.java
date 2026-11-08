package com.zenith.plugin.stashmanager.api;

import com.zenith.plugin.stashmanager.StashManagerConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

class ApiExposurePolicyTest {
    @Test void blankKeyNeverStartsOrAuthenticates() {
        var config = new StashManagerConfig();
        config.apiEnabled = true;
        config.apiPort = 0;
        assertThrows(IllegalStateException.class, () -> ApiExposurePolicy.validate(config));
        assertFalse(ApiExposurePolicy.acceptsBearer("", "Bearer "));
        assertFalse(ApiExposurePolicy.acceptsBearer(null, "Bearer anything"));
    }

    @Test void remotePlainHttpRequiresExplicitOptIn() {
        var config = new StashManagerConfig();
        config.apiKey = "secret";
        assertDoesNotThrow(() -> ApiExposurePolicy.validate(config));
        config.apiBindAddress = "0.0.0.0";
        assertThrows(IllegalStateException.class, () -> ApiExposurePolicy.validate(config));
        config.apiAllowRemoteHttp = true;
        assertDoesNotThrow(() -> ApiExposurePolicy.validate(config));
        assertFalse(ApiExposurePolicy.isLoopback("127.0.0.2"));
    }

    @Test void bearerComparisonRequiresExactToken() {
        assertTrue(ApiExposurePolicy.acceptsBearer("secret", "Bearer secret"));
        assertFalse(ApiExposurePolicy.acceptsBearer("secret", "Bearer secret-suffix"));
        assertFalse(ApiExposurePolicy.acceptsBearer("secret", "Basic secret"));
    }

    @Test void worldAuditIsNotAnHttpRoute() throws Exception {
        var config = new StashManagerConfig();
        config.apiEnabled = true;
        config.apiKey = "secret";
        config.apiPort = 0;
        try (var server = new ApiServer(config, null, null, null)) {
            assertTrue(server.start());
            var client = HttpClient.newHttpClient();
            var audit = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.boundPort() + "/api/v1/world-audit"))
                    .header("Authorization", "Bearer secret").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, audit.statusCode());
            var status = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.boundPort() + "/api/v1/status"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, status.statusCode());
        }
    }

    @Test void everySensitiveHttpRouteRejectsMissingBearerToken() throws Exception {
        var config = new StashManagerConfig();
        config.apiEnabled = true;
        config.apiKey = "test-key";
        config.apiPort = 0;
        try (var server = new ApiServer(config, null, null, null)) {
            assertTrue(server.start());
            var client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.boundPort();
            for (String path : new String[]{"status", "containers", "search", "stats", "metrics",
                    "organizer", "regions", "chest-roles", "kits", "profiles"}) {
                var response = client.send(HttpRequest.newBuilder(
                        URI.create(base + "/api/v1/" + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(401, response.statusCode(), path);
            }
            var webhook = client.send(HttpRequest.newBuilder(
                    URI.create(base + "/api/v1/webhook/test"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, webhook.statusCode(), "webhook/test");
        }
    }
}
