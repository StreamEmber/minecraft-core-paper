package com.streamember.minecraft.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * EventFabric gerçek zamanlı bağlantısı ({@code /events/ws?format=json&customerUuid=…&applicationUuid=…}).
 *
 * <ul>
 *     <li>Yalnızca {@code packetType: action} frame'leri iletilir; ham olay frame'leri yok sayılır.</li>
 *     <li>Son cursor saklanır; koparsa {@code replay=true&cursor=…} ile kaçırılanlar istenir
 *     (EventFabric'in tutma süresi kısa olduğundan uzun kopmalarda aksiyon kaçabilir — uyarı loglanır).</li>
 *     <li>Yeniden bağlanma: 1, 2, 5, 10, 20, 30 sn. 20 sn'de bir ping.</li>
 *     <li>Gönderimler tek thread'de sıralanır (WebSocket aynı anda tek gönderim kabul eder).</li>
 * </ul>
 */
final class EventFabricConnection implements WebSocket.Listener {

    private static final int[] BACKOFF_SECONDS = {1, 2, 5, 10, 20, 30};

    private final HttpClient client;
    private final String wsBaseUrl;
    private final String customerUuid;
    private final String applicationUuid;
    private final Consumer<JsonObject> onAction;
    private final Logger logger;
    private final boolean debug;
    private final ScheduledExecutorService executor;
    private final StringBuilder buffer = new StringBuilder();

    private volatile WebSocket socket;
    private volatile boolean closed;
    private volatile String cursor;
    private volatile boolean everConnected;
    private final AtomicInteger attempts = new AtomicInteger();

    EventFabricConnection(HttpClient client, String wsBaseUrl, String customerUuid, String applicationUuid,
                          Consumer<JsonObject> onAction, Logger logger, boolean debug) {
        this.client = client;
        this.wsBaseUrl = wsBaseUrl;
        this.customerUuid = customerUuid;
        this.applicationUuid = applicationUuid;
        this.onAction = onAction;
        this.logger = logger;
        this.debug = debug;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "StreamEmber-EventFabric");
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        executor.execute(this::connect);
        executor.scheduleAtFixedRate(() -> sendNow("{\"type\":\"ping\"}"), 20, 20, TimeUnit.SECONDS);
    }

    boolean isConnected() {
        return socket != null;
    }

    void close() {
        closed = true;
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "bye").orTimeout(2, TimeUnit.SECONDS);
            } catch (RuntimeException ignored) {
                current.abort();
            }
        }
        executor.shutdownNow();
    }

    private void connect() {
        if (closed) return;
        Map<String, String> query = new LinkedHashMap<>();
        query.put("format", "json");
        query.put("customerUuid", customerUuid);
        query.put("applicationUuid", applicationUuid);
        String resumeFrom = cursor;
        if (resumeFrom != null && !resumeFrom.isBlank()) {
            query.put("replay", "true");
            query.put("cursor", resumeFrom);
        }
        URI uri = URI.create(wsBaseUrl + "/events/ws?" + Http.query(query));
        if (debug) logger.info("[debug] Connecting to " + uri);
        client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, this)
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        // İlk 3 deneme ve sonra her 10 denemede bir uyar; konsol dolmasın.
                        int failures = attempts.get() + 1;
                        if (failures <= 3 || failures % 10 == 0 || debug) {
                            logger.warning("EventFabric connection failed (attempt " + failures + "): " + Http.describe(error));
                        }
                        scheduleReconnect();
                    }
                });
    }

    private void scheduleReconnect() {
        if (closed) return;
        int delay = BACKOFF_SECONDS[Math.min(attempts.getAndIncrement(), BACKOFF_SECONDS.length - 1)];
        try {
            executor.schedule(this::connect, delay, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // kapanıyor
        }
    }

    private void sendNow(String text) {
        WebSocket current = socket;
        if (current == null || closed) return;
        try {
            current.sendText(text, true).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (debug) logger.info("[debug] Send failed: " + Http.describe(e));
        }
    }

    private void send(String text) {
        try {
            executor.execute(() -> sendNow(text));
        } catch (RejectedExecutionException ignored) {
            // kapanıyor
        }
    }

    // ─── WebSocket.Listener (sıralı çağrılır) ───────────────────────────────

    @Override
    public void onOpen(WebSocket webSocket) {
        if (closed) {
            webSocket.abort();
            return;
        }
        socket = webSocket;
        attempts.set(0);
        logger.info((everConnected ? "Reconnected" : "Connected") + " to EventFabric (application " + applicationUuid + ").");
        everConnected = true;
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        buffer.append(data);
        if (last) {
            String text = buffer.toString();
            buffer.setLength(0);
            try {
                handle(text);
            } catch (RuntimeException e) {
                logger.warning("EventFabric frame could not be handled: " + Http.describe(e));
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        dropped(webSocket, "closed (" + statusCode + (reason == null || reason.isBlank() ? "" : " " + reason) + ")");
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        dropped(webSocket, "error: " + Http.describe(error));
    }

    private void dropped(WebSocket webSocket, String why) {
        if (socket == webSocket) socket = null;
        buffer.setLength(0);
        if (closed) return;
        logger.warning("EventFabric connection " + why + "; reconnecting.");
        scheduleReconnect();
    }

    private void handle(String text) {
        JsonElement element = JsonParser.parseString(text);
        if (!(element instanceof JsonObject frame)) return;
        String frameCursor = text(frame, "cursor");
        if (!frameCursor.isEmpty()) cursor = frameCursor;

        switch (text(frame, "packetType")) {
            case "action" -> {
                if (frame.get("action") instanceof JsonObject action) onAction.accept(action);
            }
            case "system" -> {
                if (frame.get("system") instanceof JsonObject system) handleSystem(system);
            }
            default -> {
                // packetType=event: ham olaylar oyuna gerekmez (GCore aksiyona çevirir).
            }
        }
    }

    private void handleSystem(JsonObject system) {
        String type = text(system, "type");
        if (debug) logger.info("[debug] system: " + system);
        if (system.get("cursorExpired") instanceof JsonPrimitive expired && expired.getAsBoolean()) {
            String reason = text(system, "cursorExpiredReason");
            if (reason.equals("boot_changed")) {
                // EventFabric yeniden başladı: eski cursor geçersiz, sunucuda zaten tekrar edilecek bir şey yok.
                logger.info("EventFabric was restarted; live actions resumed.");
            } else {
                logger.warning("Replay window passed (" + reason + "); actions sent while disconnected may have been missed.");
            }
        }
        if (type.equals("replay.completed") && system.get("hasMore") instanceof JsonPrimitive more && more.getAsBoolean()) {
            String next = cursor;
            if (next != null && !next.isBlank()) {
                JsonObject command = new JsonObject();
                command.addProperty("type", "replay.next");
                command.addProperty("cursor", next);
                command.addProperty("limit", 100);
                send(command.toString());
            }
        } else if (type.equals("command.rejected")) {
            logger.warning("EventFabric rejected a command: " + text(system, "message"));
        }
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value instanceof JsonPrimitive primitive ? primitive.getAsString() : "";
    }
}
