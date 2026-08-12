package com.zenith.plugin.stashmanager.travel.tunnel.network.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

// Adds timestamped HMAC headers for request signing.
public final class HmacAuth implements AuthProvider {

    private final String secret;

    public HmacAuth(String secret) {
        this.secret = secret == null ? "" : secret.trim();
    }

    @Override
    public void apply(HttpRequest.Builder builder, String method, URI uri, String body) {
        if (secret.isEmpty()) return;
        String timestamp = Instant.now().toString();
        String payload = timestamp + "\n" + method + "\n" + uri.getPath() + "\n" + (body == null ? "" : body);
        builder.header("X-Timestamp", timestamp);
        builder.header("X-Signature", sign(payload));
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            return "";
        }
    }
}