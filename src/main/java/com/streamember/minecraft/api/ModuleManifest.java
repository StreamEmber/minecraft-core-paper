package com.streamember.minecraft.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Modülün jar'ına gömülü kimliği ve şeması.
 *
 * <ul>
 *     <li>{@code streamember-module.json}: {@code applicationUuid} (Falcon'daki oyunun UUID'si — sabit,
 *     asla değişmez), {@code code}, {@code name}.</li>
 *     <li>{@code game-schema.json}: Falcon'daki oyun yapılandırma şemasının kopyası. Buradan aksiyon
 *     listesi, ayar varsayılanları ve aksiyon argümanlarının varsayılanları okunur.</li>
 * </ul>
 */
public final class ModuleManifest {

    public static final String MANIFEST_RESOURCE = "streamember-module.json";
    public static final String SCHEMA_RESOURCE = "game-schema.json";

    private final String applicationUuid;
    private final String code;
    private final String name;
    private final Set<String> actionIds;
    private final JsonObject settingDefaults;
    private final Map<String, JsonObject> argumentDefaults;

    private ModuleManifest(String applicationUuid, String code, String name, Set<String> actionIds,
                           JsonObject settingDefaults, Map<String, JsonObject> argumentDefaults) {
        this.applicationUuid = applicationUuid;
        this.code = code;
        this.name = name;
        this.actionIds = actionIds;
        this.settingDefaults = settingDefaults;
        this.argumentDefaults = argumentDefaults;
    }

    /** Oyunun Falcon UUID'si (EventFabric'te applicationUuid). */
    public String applicationUuid() {
        return applicationUuid;
    }

    /** Kısa kod, ör. {@code soldier-war}; {@code /se play <code>} ile kullanılır. */
    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    /** Şemadaki aksiyon id'leri. */
    public Set<String> actionIds() {
        return actionIds;
    }

    /** Şemadaki ayar alanlarının varsayılanları ({@code settings.fields[].path → default}). */
    public JsonObject settingDefaults() {
        return settingDefaults.deepCopy();
    }

    /** Bir aksiyonun argüman varsayılanları ({@code actions[].arguments[].path → default}). */
    public JsonObject argumentDefaults(String actionId) {
        JsonObject defaults = argumentDefaults.get(actionId);
        return defaults == null ? new JsonObject() : defaults.deepCopy();
    }

    public static ModuleManifest load(JavaPlugin plugin) {
        JsonObject manifest = read(plugin, MANIFEST_RESOURCE);
        String uuid = text(manifest, "applicationUuid").toLowerCase(Locale.ROOT);
        try {
            if (!UUID.fromString(uuid).toString().equals(uuid)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(MANIFEST_RESOURCE + ": applicationUuid is not a valid UUID: '" + uuid + "'");
        }
        String code = text(manifest, "code");
        if (code.isEmpty()) throw new IllegalStateException(MANIFEST_RESOURCE + ": code is missing");
        String name = text(manifest, "name");

        JsonObject schema = read(plugin, SCHEMA_RESOURCE);
        Set<String> actionIds = new LinkedHashSet<>();
        Map<String, JsonObject> argumentDefaults = new LinkedHashMap<>();
        for (JsonElement element : array(schema, "actions")) {
            if (!(element instanceof JsonObject action)) continue;
            String id = text(action, "id");
            if (id.isEmpty()) continue;
            actionIds.add(id);
            argumentDefaults.put(id, defaultsOf(array(action, "arguments")));
        }
        JsonObject settings = schema.get("settings") instanceof JsonObject object ? object : new JsonObject();
        JsonObject settingDefaults = defaultsOf(array(settings, "fields"));

        return new ModuleManifest(uuid, code, name.isEmpty() ? code : name,
                Collections.unmodifiableSet(actionIds), settingDefaults, Collections.unmodifiableMap(argumentDefaults));
    }

    /** {@code [{path:"a.b", default: 1}]} → {@code {"a":{"b":1}}} */
    private static JsonObject defaultsOf(JsonArray fields) {
        JsonObject result = new JsonObject();
        for (JsonElement element : fields) {
            if (!(element instanceof JsonObject field) || !field.has("default") || field.get("default").isJsonNull()) continue;
            String path = text(field, "path");
            if (path.isEmpty()) path = text(field, "id");
            if (path.isEmpty()) continue;
            String[] parts = path.split("\\.");
            JsonObject node = result;
            for (int i = 0; i < parts.length - 1; i++) {
                JsonObject child;
                if (node.get(parts[i]) instanceof JsonObject existing) {
                    child = existing;
                } else {
                    child = new JsonObject();
                    node.add(parts[i], child);
                }
                node = child;
            }
            node.add(parts[parts.length - 1], field.get("default").deepCopy());
        }
        return result;
    }

    private static JsonObject read(JavaPlugin plugin, String resource) {
        try (InputStream stream = plugin.getResource(resource)) {
            if (stream == null) throw new IllegalStateException(resource + " is missing from the module jar");
            JsonElement element = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            if (!(element instanceof JsonObject object)) throw new IllegalStateException(resource + " must be a JSON object");
            return object;
        } catch (IOException e) {
            throw new IllegalStateException(resource + " could not be read", e);
        }
    }

    private static JsonArray array(JsonObject object, String key) {
        return object.get(key) instanceof JsonArray array ? array : new JsonArray();
    }

    private static String text(JsonObject object, String key) {
        return GameAction.text(object, key).trim();
    }
}
