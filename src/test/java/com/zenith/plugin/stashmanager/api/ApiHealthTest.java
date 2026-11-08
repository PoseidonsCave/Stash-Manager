package com.zenith.plugin.stashmanager.api;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.StashManagerModule;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import com.zenith.plugin.stashmanager.organizer.StashOrganizer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ApiHealthTest {
    @Test
    void apiAndMetricsDistinguishFailureFromCompletionAndExposeWriteFreshness() throws Exception {
        StashManagerConfig config = new StashManagerConfig();
        config.apiKey = "test-key";
        ContainerIndex index = new ContainerIndex();
        StashOrganizer organizer = new StashOrganizer(config, index);
        var fail = StashOrganizer.class.getDeclaredMethod("markFailed", String.class, StashOrganizer.State.class);
        fail.setAccessible(true);
        fail.invoke(organizer, "inventory_transfer_unavailable_with_cargo", StashOrganizer.State.SHULKER_FETCH_TAKE);
        StashManagerModule module = new StashManagerModule(config, index);
        module.setOrganizer(organizer);
        ApiHandler handler = new ApiHandler(config, module, index, null);

        Exchange response = new Exchange();
        handler.handleOrganizer(response);
        assertEquals(200, response.code);
        var body = JsonParser.parseString(response.text()).getAsJsonObject();
        assertEquals("FAILED", body.get("state").getAsString());
        assertTrue(body.get("failed").getAsBoolean());
        assertFalse(body.get("active").getAsBoolean());
        assertEquals("SHULKER_FETCH_TAKE", body.get("last_failure_state").getAsString());
        assertEquals("inventory_transfer_unavailable_with_cargo", body.get("last_failure_reason").getAsString());

        response = new Exchange();
        handler.handleMetrics(response);
        assertTrue(response.text().contains("stash_organizer_failed 1\n"));
        assertTrue(response.text().contains("stash_organizer_active 0\n"));
        assertTrue(response.text().contains("stash_database_last_write_success_timestamp_seconds 0.0\n"));

        response = new Exchange();
        handler.handleStatus(response);
        body = JsonParser.parseString(response.text()).getAsJsonObject();
        assertEquals(0, body.get("database_last_write_attempt").getAsLong());
        assertEquals(0, body.get("database_last_write_success").getAsLong());
        assertEquals(0, body.get("last_inventory_observation").getAsLong());
    }

    private static class Exchange extends HttpExchange {
        final Headers headers = new Headers();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        int code;
        Exchange() { headers.set("Authorization", "Bearer test-key"); }
        String text() { return output.toString(StandardCharsets.UTF_8); }
        @Override public Headers getRequestHeaders() { return headers; }
        @Override public Headers getResponseHeaders() { return headers; }
        @Override public URI getRequestURI() { return URI.create("/api/v1/organizer"); }
        @Override public String getRequestMethod() { return "GET"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getResponseBody() { return output; }
        @Override public void sendResponseHeaders(int status, long length) { code = status; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public int getResponseCode() { return code; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream input, OutputStream output) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
