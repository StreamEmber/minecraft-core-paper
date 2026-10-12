package com.streamember.minecraft.api;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Her StreamEmber oyun modunun temel sınıfı (ayrı bir Paper eklentisi).
 *
 * <p>Modül yalnızca "şu aksiyon gelince oyunda ne olur" kısmını yazar; bağlantı, kimlik, presence,
 * yeniden bağlanma, tekrar eden aksiyonların elenmesi ve ana thread'e taşıma çekirdeğin işidir.</p>
 *
 * <pre>
 * public final class MyModule extends StreamEmberModule {
 *     protected void registerActions(ActionRegistry actions) {
 *         actions.on("npc.spawnEnemy", action -> ...);
 *     }
 * }
 * </pre>
 *
 * <p>Yaşam döngüsü: {@code onModuleEnable} → (etkinleşince) {@code onActivate} → aksiyonlar →
 * {@code onDeactivate} → {@code onModuleDisable}. Aynı anda yalnızca bir modül aktiftir
 * ({@code /se play <code>}).</p>
 */
public abstract class StreamEmberModule extends JavaPlugin {

    private final Map<String, ActionHandler> handlers = new LinkedHashMap<>();
    private ModuleManifest manifest;
    private StreamEmber core;
    private volatile GameSession session;

    /** Aksiyon işleyicilerini kaydet. id'ler {@code game-schema.json} ile aynı olmalı. */
    protected abstract void registerActions(ActionRegistry actions);

    /** Eklenti açılırken (dinleyiciler, komutlar…). */
    protected void onModuleEnable() {
    }

    /** Eklenti kapanırken (temizlik). */
    protected void onModuleDisable() {
    }

    /** Modül aktif oyun olduğunda. */
    protected void onActivate(GameSession session) {
    }

    /** Modül aktif oyun olmaktan çıktığında; oluşturulan her şeyi temizle. */
    protected void onDeactivate() {
    }

    /** Falcon'daki ayarlar değiştiğinde (ilk yüklemede de çağrılır). */
    protected void onSettingsChanged(JsonValues settings) {
    }

    @Override
    public final void onEnable() {
        try {
            manifest = ModuleManifest.load(this);
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Invalid module manifest: " + e.getMessage(), e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        core = StreamEmber.find().orElse(null);
        if (core == null) {
            getLogger().severe("StreamEmberCore is not running; this module needs it (depend: [StreamEmberCore]).");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        handlers.clear();
        registerActions(new ActionRegistry(handlers));
        onModuleEnable();
        core.registerModule(this);
    }

    @Override
    public final void onDisable() {
        if (core != null) {
            core.unregisterModule(this);
            core = null;
        }
        try {
            onModuleDisable();
        } finally {
            session = null;
        }
    }

    public final ModuleManifest manifest() {
        return manifest;
    }

    /** Jar'a gömülü oyun UUID'si. */
    public final String applicationUuid() {
        return manifest.applicationUuid();
    }

    /** Modül aktifse oturumu. */
    public final Optional<GameSession> session() {
        return Optional.ofNullable(session);
    }

    public final boolean isActive() {
        return session != null;
    }

    /** Kayıtlı işleyiciler (çekirdek kullanır). */
    public final Map<String, ActionHandler> actionHandlers() {
        return Collections.unmodifiableMap(handlers);
    }

    // ─── Çekirdeğin çağırdığı geçişler (ana thread) ──────────────────────────

    public final void activate(GameSession newSession) {
        session = newSession;
        onActivate(newSession);
    }

    public final void deactivate() {
        if (session == null) return;
        try {
            onDeactivate();
        } finally {
            session = null;
        }
    }

    public final void settingsChanged(JsonValues settings) {
        if (session != null) onSettingsChanged(settings);
    }
}
