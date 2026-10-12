package com.streamember.minecraft.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Map;
import java.util.Optional;

/**
 * Salt okunur JSON nesnesi görünümü; noktalı yol ile okunur ({@code "gameplay.maxSoldiers"}).
 * Aksiyon argümanları ({@link GameAction#arguments()}) ve oyun ayarları ({@link GameSession#settings()})
 * bu sınıfla okunur. Eksik ya da tipi uymayan değer için verilen varsayılan döner; asla hata atmaz.
 */
public final class JsonValues {

    private static final JsonValues EMPTY = new JsonValues(new JsonObject());

    private final JsonObject root;

    public JsonValues(JsonObject root) {
        this.root = root == null ? new JsonObject() : root;
    }

    public static JsonValues empty() {
        return EMPTY;
    }

    /** Kopya; değiştirilmesi bu görünümü etkilemez. */
    public JsonObject json() {
        return root.deepCopy();
    }

    public boolean has(String path) {
        return find(path) != null;
    }

    public Optional<JsonElement> get(String path) {
        JsonElement value = find(path);
        return value == null ? Optional.empty() : Optional.of(value.deepCopy());
    }

    public String getString(String path, String fallback) {
        JsonElement value = find(path);
        return value instanceof JsonPrimitive primitive ? primitive.getAsString() : fallback;
    }

    public int getInt(String path, int fallback) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, getLong(path, fallback)));
    }

    public long getLong(String path, long fallback) {
        JsonElement value = find(path);
        if (!(value instanceof JsonPrimitive primitive)) return fallback;
        try {
            // 1, 1.0 ve "1" aynı kabul edilir (Struct argümanları JSON'da 1.0 olarak gelebilir).
            return primitive.isNumber() ? Math.round(primitive.getAsDouble()) : Math.round(Double.parseDouble(primitive.getAsString().trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public double getDouble(String path, double fallback) {
        JsonElement value = find(path);
        if (!(value instanceof JsonPrimitive primitive)) return fallback;
        try {
            return primitive.isNumber() ? primitive.getAsDouble() : Double.parseDouble(primitive.getAsString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean getBoolean(String path, boolean fallback) {
        JsonElement value = find(path);
        if (!(value instanceof JsonPrimitive primitive)) return fallback;
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        String text = primitive.getAsString().trim();
        if (text.equalsIgnoreCase("true")) return true;
        if (text.equalsIgnoreCase("false")) return false;
        return fallback;
    }

    /** Alt nesne; yoksa boş görünüm. */
    public JsonValues getObject(String path) {
        JsonElement value = find(path);
        return value instanceof JsonObject object ? new JsonValues(object) : EMPTY;
    }

    @Override
    public String toString() {
        return root.toString();
    }

    private JsonElement find(String path) {
        if (path == null || path.isBlank()) return root;
        JsonElement current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof JsonObject object)) return null;
            current = object.get(part);
            if (current == null || current.isJsonNull()) return null;
        }
        return current;
    }

    /** {@code overlay} değerlerini {@code base} üzerine derinlemesine yazar (yeni nesne döner). */
    public static JsonObject merge(JsonObject base, JsonObject overlay) {
        JsonObject result = base == null ? new JsonObject() : base.deepCopy();
        if (overlay == null) return result;
        for (Map.Entry<String, JsonElement> entry : overlay.entrySet()) {
            JsonElement existing = result.get(entry.getKey());
            if (existing instanceof JsonObject existingObject && entry.getValue() instanceof JsonObject incoming) {
                result.add(entry.getKey(), merge(existingObject, incoming));
            } else {
                result.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        return result;
    }
}
