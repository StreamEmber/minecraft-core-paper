package com.streamember.minecraft.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;

/** Java 21 HttpClient + Gson ile küçük JSON istemcisi. Bütün çağrılar asenkron. */
final class Http {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    HttpClient client() {
        return client;
    }

    CompletableFuture<JsonObject> getJson(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("Accept", "application/json").GET().build();
        return send(request);
    }

    CompletableFuture<JsonObject> postJson(String url, JsonObject body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("Accept", "application/json").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        return send(request);
    }

    private CompletableFuture<JsonObject> send(HttpRequest request) {
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(response -> {
            if (response.statusCode() / 100 != 2) throw new HttpStatusException(response.statusCode(), request.uri());
            String body = response.body();
            if (body == null || body.isBlank()) return new JsonObject();
            JsonElement element = JsonParser.parseString(body);
            return element instanceof JsonObject object ? object : new JsonObject();
        });
    }

    static String query(Map<String, String> values) {
        StringJoiner joiner = new StringJoiner("&");
        values.forEach((key, value) -> {
            if (value != null) joiner.add(encode(key) + "=" + encode(value));
        });
        return joiner.toString();
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Kökteki asıl hatayı kısa metne çevirir (log için). */
    static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause != cause.getCause()) cause = cause.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    static final class HttpStatusException extends RuntimeException {
        final int status;

        HttpStatusException(int status, URI uri) {
            super("HTTP " + status + " " + uri.getHost() + uri.getPath());
            this.status = status;
        }
    }
}
