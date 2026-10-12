package com.streamember.minecraft.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.streamember.minecraft.api.GameSession;
import com.streamember.minecraft.api.JsonValues;
import com.streamember.minecraft.api.StreamEmberModule;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Aktif modülün oturumu. Ayarlar ve bağlantı async thread'lerden güncellenir. */
final class ActiveSession implements GameSession {

    private final StreamEmberCorePlugin core;
    private final StreamEmberModule module;
    private volatile Identity identity;
    private volatile JsonValues settings;
    private volatile String revision = "schema";
    private volatile String settingsFingerprint = "";
    private volatile JsonArray mappings = new JsonArray();
    private volatile EventFabricConnection connection;

    ActiveSession(StreamEmberCorePlugin core, StreamEmberModule module, Identity identity) {
        this.core = core;
        this.module = module;
        this.identity = identity;
        JsonObject defaults = module.manifest().settingDefaults();
        this.settings = new JsonValues(defaults);
        // Falcon'dan gelen ayar şema varsayılanlarıyla aynıysa modüle ikinci kez bildirilmesin.
        this.settingsFingerprint = defaults.toString();
    }

    @Override
    public StreamEmberModule module() {
        return module;
    }

    @Override
    public String applicationUuid() {
        return module.applicationUuid();
    }

    @Override
    public Optional<String> customerUuid() {
        Identity current = identity;
        return current == null ? Optional.empty() : Optional.of(current.customerUuid());
    }

    Identity identity() {
        return identity;
    }

    void identity(Identity value) {
        identity = value;
    }

    @Override
    public JsonValues settings() {
        return settings;
    }

    @Override
    public String settingsRevision() {
        return revision;
    }

    /** Falcon public config'ini uygular; ayar gerçekten değiştiyse {@code true}. */
    boolean applyConfig(JsonObject config) {
        JsonObject userSettings = config.get("settings") instanceof JsonObject object ? object : new JsonObject();
        JsonObject merged = JsonValues.merge(module.manifest().settingDefaults(), userSettings);
        mappings = config.get("events") instanceof JsonArray events ? events : new JsonArray();
        revision = config.has("revision") && config.get("revision").isJsonPrimitive() ? config.get("revision").getAsString() : "unknown";
        String fingerprint = merged.toString();
        if (fingerprint.equals(settingsFingerprint)) return false;
        settingsFingerprint = fingerprint;
        settings = new JsonValues(merged);
        return true;
    }

    /** Falcon'daki eşleştirmeler ({@code events[]}); {@code /se test} argümanları için. */
    JsonArray mappings() {
        return mappings;
    }

    @Override
    public Optional<Player> targetPlayer() {
        return core.targetPlayer();
    }

    @Override
    public CompletableFuture<Boolean> resetGCore() {
        return core.resetGCore(this);
    }

    @Override
    public boolean isConnected() {
        EventFabricConnection current = connection;
        return current != null && current.isConnected();
    }

    void connection(EventFabricConnection value) {
        EventFabricConnection previous = connection;
        connection = value;
        if (previous != null && previous != value) previous.close();
    }

    void close() {
        connection(null);
    }
}
