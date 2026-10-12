package com.streamember.minecraft.core;

import com.google.gson.JsonObject;
import com.streamember.minecraft.api.GameSession;
import com.streamember.minecraft.api.StreamEmber;
import com.streamember.minecraft.api.StreamEmberModule;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * StreamEmberCore: modüllerin EventFabric, launcher ve Falcon ile tek noktadan konuştuğu çekirdek.
 *
 * <ul>
 *     <li>Kimlik: müşteri UUID'si (config → launcher → ortam değişkeni). Oyun UUID'si modülün jar'ında gömülü.</li>
 *     <li>Aynı anda tek aktif modül ({@code /se play <code>}); seçim {@code state.yml}'de saklanır.</li>
 *     <li>Aktif modül için: presence (EventFabric GET + launcher), Falcon ayarları, EventFabric WebSocket.</li>
 *     <li>Gelen aksiyonlar {@link ActionDispatcher} ile modülün işleyicisine ana thread'de verilir.</li>
 * </ul>
 */
public final class StreamEmberCorePlugin extends JavaPlugin implements StreamEmber {

    private final Map<String, StreamEmberModule> modules = new LinkedHashMap<>();
    private CoreConfig config;
    private StateStore state;
    private Http http;
    private ActionDispatcher dispatcher;
    private volatile Identity identity;
    private volatile ActiveSession active;
    private volatile boolean resolvingIdentity;
    private boolean warnedNoIdentity;
    private boolean started;
    private IdleShutdown idleShutdown;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = CoreConfig.from(getConfig());
        state = new StateStore(new File(getDataFolder(), "state.yml"), getLogger());
        http = new Http();
        dispatcher = new ActionDispatcher(this);

        getServer().getServicesManager().register(StreamEmber.class, this, this, ServicePriority.Normal);

        StreamEmberCommand command = new StreamEmberCommand(this);
        PluginCommand pluginCommand = Objects.requireNonNull(getCommand("streamember"), "command 'streamember' missing from plugin.yml");
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);

        resolveIdentity(null);

        idleShutdown = new IdleShutdown(this);
        idleShutdown.start(config.idleShutdownMinutes());

        // Sunucu açılıp bütün eklentiler (modüller) yüklendikten sonra ilk tick'te.
        getServer().getScheduler().runTask(this, this::autoActivate);
        long presenceTicks = config.presenceIntervalSeconds() * 20L;
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::heartbeat, presenceTicks, presenceTicks);
        long settingsTicks = config.settingsRefreshSeconds() * 20L;
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            ActiveSession session = active;
            if (session != null) refreshSettings(session, false);
        }, settingsTicks, settingsTicks);
    }

    @Override
    public void onDisable() {
        if (idleShutdown != null) idleShutdown.stop();
        deactivate();
        modules.clear();
        getServer().getServicesManager().unregisterAll(this);
    }

    // ─── StreamEmber (modül API'si) ─────────────────────────────────────────

    @Override
    public void registerModule(StreamEmberModule module) {
        String code = module.manifest().code();
        for (StreamEmberModule other : modules.values()) {
            if (other != module && other.applicationUuid().equals(module.applicationUuid())) {
                getLogger().severe("Module '" + code + "' uses the same applicationUuid as '" + other.manifest().code()
                        + "' (" + module.applicationUuid() + "); it is not registered.");
                return;
            }
        }
        modules.put(code, module);
        checkHandlers(module);
        getLogger().info("Module registered: " + code + " (" + module.manifest().name() + ", " + module.applicationUuid() + ")");

        // /plugman load ile sonradan gelen modül: seçili olan buysa hemen etkinleştir.
        if (started && active == null && (code.equals(state.activeModule()) || modules.size() == 1)) {
            activate(module, null);
        }
    }

    @Override
    public void unregisterModule(StreamEmberModule module) {
        ActiveSession session = active;
        if (session != null && session.module() == module) deactivate();
        modules.values().remove(module);
    }

    @Override
    public Optional<StreamEmberModule> activeModule() {
        ActiveSession session = active;
        return session == null ? Optional.empty() : Optional.of(session.module());
    }

    @Override
    public Optional<GameSession> session() {
        return Optional.ofNullable(active);
    }

    @Override
    public Optional<String> customerUuid() {
        Identity current = identity;
        return current == null ? Optional.empty() : Optional.of(current.customerUuid());
    }

    // ─── Etkinleştirme ──────────────────────────────────────────────────────

    private void autoActivate() {
        started = true;
        if (active != null) return;
        String wanted = state.activeModule();
        StreamEmberModule module = wanted.isEmpty() ? null : modules.get(wanted);
        if (module == null && modules.size() == 1) module = modules.values().iterator().next();
        if (module != null) {
            activate(module, null);
        } else if (modules.isEmpty()) {
            getLogger().info("No StreamEmber modules installed yet.");
        } else {
            getLogger().info("Several modules installed (" + String.join(", ", modules.keySet()) + "). Choose one with /se play <code>.");
        }
    }

    /** Ana thread. */
    void activate(StreamEmberModule module, CommandSender by) {
        ActiveSession current = active;
        if (current != null && current.module() == module) return;
        deactivate();

        ActiveSession session = new ActiveSession(this, module, identity);
        active = session;
        state.activeModule(module.manifest().code());
        if (by instanceof Player player && state.targetPlayer().isEmpty()) state.targetPlayer(player.getName());

        try {
            module.activate(session);
        } catch (Throwable error) {
            getLogger().log(Level.SEVERE, "Module '" + module.manifest().code() + "' failed to activate", error);
        }
        getLogger().info("Active module: " + module.manifest().code() + describeIdentity());
        connect(session);
        refreshSettings(session, true);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> reportPresence(session));
    }

    /** Ana thread. */
    void deactivate() {
        ActiveSession session = active;
        if (session == null) return;
        active = null;
        session.close();
        try {
            session.module().deactivate();
        } catch (Throwable error) {
            getLogger().log(Level.SEVERE, "Module '" + session.module().manifest().code() + "' failed to deactivate", error);
        }
    }

    /** {@code /se stop}: aktif modülü kapatır ve seçimi unutur. */
    void stop() {
        deactivate();
        state.activeModule("");
    }

    private void connect(ActiveSession session) {
        Identity current = session.identity();
        if (current == null) {
            session.connection(null);
            if (resolvingIdentity || warnedNoIdentity) return; // launcher'a soruluyor ya da zaten uyarıldı
            warnedNoIdentity = true;
            getLogger().warning("No customer UUID (launcher not running / not signed in, customer-uuid empty in config.yml, "
                    + Identity.ENVIRONMENT_VARIABLE + " not set). Live actions are off; /se test still works.");
            return;
        }
        String customer = current.customerUuid();
        session.connection(null);
        EventFabricConnection connection = new EventFabricConnection(http.client(), config.eventFabricWsUrl(), customer,
                session.applicationUuid(), json -> dispatcher.fromNetwork(session, json), getLogger(), config.debug());
        session.connection(connection);
        connection.start();
    }

    // ─── Kimlik ─────────────────────────────────────────────────────────────

    /** Async; bittiğinde (ana thread) değiştiyse aktif oturumu yeni kimlikle yeniden bağlar. */
    void resolveIdentity(CommandSender reportTo) {
        if (resolvingIdentity) return;
        resolvingIdentity = true;
        Identity.resolve(config, http).whenComplete((result, error) -> {
            Identity resolved = result == null ? null : result.orElse(null);
            // Bayrak, sonuç ana thread'de uygulanırken kapanır: arada etkinleşen modül
            // "kimlik yok" sanıp yanlış uyarı vermesin.
            runSync(() -> {
                resolvingIdentity = false;
                applyIdentity(resolved, reportTo);
            });
        });
    }

    private void applyIdentity(Identity resolved, CommandSender reportTo) {
        Identity previous = identity;
        identity = resolved;
        boolean changed = !Objects.equals(previous == null ? null : previous.customerUuid(), resolved == null ? null : resolved.customerUuid());
        if (changed) {
            getLogger().info(resolved == null ? "Customer UUID unknown." : "Customer " + resolved.customerUuid() + " (from " + resolved.source().name().toLowerCase() + ").");
            ActiveSession session = active;
            if (session != null) {
                session.identity(resolved);
                connect(session);
                refreshSettings(session, false);
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> reportPresence(session));
            }
        }
        if (resolved != null) {
            warnedNoIdentity = false;
        } else if (active != null) {
            connect(active); // kimlik hiçbir yerden gelmedi: bir kez uyar
        }
        if (reportTo != null) reportTo.sendMessage(Messages.prefix().append(Messages.text("Customer: " + (resolved == null ? "unknown" : resolved.customerUuid() + " (" + resolved.source().name().toLowerCase() + ")"))));
    }

    private String describeIdentity() {
        Identity current = identity;
        if (current != null) return " (customer " + current.customerUuid() + ")";
        return resolvingIdentity ? " (resolving customer UUID…)" : " (no customer UUID)";
    }

    // ─── Presence, ayarlar, reset ───────────────────────────────────────────

    /** Async: presence + kimlik yoksa / launcher'dan geliyorsa yeniden sorgula. */
    private void heartbeat() {
        Identity current = identity;
        if (current == null || current.source() == Identity.Source.LAUNCHER) resolveIdentity(null);
        ActiveSession session = active;
        if (session != null) reportPresence(session);
    }

    /** Async. EventFabric: son bildirilen oyun o müşterinin olaylarına bağlanır. */
    private void reportPresence(ActiveSession session) {
        Identity current = session.identity();
        if (current == null || active != session) return;
        String url = config.eventFabricUrl() + "/presence?" + Http.query(Map.of(
                "customerUuid", current.customerUuid(),
                "applicationUuid", session.applicationUuid(),
                "source", "minecraft"));
        http.getJson(url).whenComplete((ok, error) -> {
            if (error != null && config.debug()) getLogger().info("[debug] Presence failed: " + Http.describe(error));
        });
        if (current.source() == Identity.Source.LAUNCHER) {
            JsonObject body = new JsonObject();
            body.addProperty("applicationUuid", session.applicationUuid());
            http.postJson(config.launcherUrl() + "/api/active-application", body).exceptionally(error -> null);
        }
    }

    /** Async. Falcon public config → ayarlar; değiştiyse modüle (ana thread) bildirilir. */
    void refreshSettings(ActiveSession session, boolean notifyAlways) {
        Identity current = session.identity();
        if (current == null) {
            if (notifyAlways) runSync(() -> notifySettings(session));
            return;
        }
        String url = config.falconUrl() + "/public/game/config/" + Http.encode(current.customerUuid()) + "/" + Http.encode(session.applicationUuid());
        http.getJson(url).whenComplete((json, error) -> {
            if (error != null) {
                getLogger().warning("Game settings could not be loaded from Falcon (" + Http.describe(error) + "); using schema defaults.");
                if (notifyAlways) runSync(() -> notifySettings(session));
                return;
            }
            boolean changed = session.applyConfig(json);
            if (changed || notifyAlways) runSync(() -> notifySettings(session));
        });
    }

    private void notifySettings(ActiveSession session) {
        if (active != session) return;
        try {
            session.module().settingsChanged(session.settings());
        } catch (Throwable error) {
            getLogger().log(Level.SEVERE, "Module '" + session.module().manifest().code() + "' failed to apply settings", error);
        }
    }

    CompletableFuture<Boolean> resetGCore(ActiveSession session) {
        Identity current = session.identity();
        if (current == null) return CompletableFuture.completedFuture(false);
        String url = config.eventFabricUrl() + "/gcore/reset?" + Http.query(Map.of(
                "customerUuid", current.customerUuid(),
                "applicationUuid", session.applicationUuid()));
        return http.getJson(url).thenApply(json -> true).exceptionally(error -> {
            getLogger().warning("GCore reset failed: " + Http.describe(error));
            return false;
        });
    }

    // ─── Hedef oyuncu ───────────────────────────────────────────────────────

    /** Seçili oyuncu çevrimiçiyse o; değilse tek oyuncu; o da yoksa ilk oyuncu. */
    Optional<Player> targetPlayer() {
        String name = state.targetPlayer();
        if (!name.isEmpty()) {
            Player chosen = Bukkit.getPlayerExact(name);
            if (chosen != null) return Optional.of(chosen);
        }
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        return online.stream().findFirst().map(player -> (Player) player);
    }

    void targetPlayer(String name) {
        state.targetPlayer(name);
    }

    String configuredTarget() {
        return state.targetPlayer();
    }

    // ─── Komutların kullandığı yardımcılar ──────────────────────────────────

    void reloadAll(CommandSender sender) {
        reloadConfig();
        config = CoreConfig.from(getConfig());
        if (idleShutdown != null) idleShutdown.start(config.idleShutdownMinutes());
        identity = null;
        ActiveSession session = active;
        if (session != null) session.identity(null);
        resolveIdentity(sender); // kimlik çözülünce bağlantı ve ayarlar yenilenir
    }

    ActiveSession activeSession() {
        return active;
    }

    CoreConfig coreConfig() {
        return config;
    }

    Identity identity() {
        return identity;
    }

    Map<String, StreamEmberModule> modules() {
        return modules;
    }

    ActionDispatcher dispatcher() {
        return dispatcher;
    }

    private void runSync(Runnable task) {
        if (!isEnabled()) return;
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        try {
            Bukkit.getScheduler().runTask(this, task);
        } catch (RuntimeException ignored) {
            // kapanıyor
        }
    }

    private void checkHandlers(StreamEmberModule module) {
        Set<String> declared = module.manifest().actionIds();
        Set<String> handled = module.actionHandlers().keySet();
        List<String> missing = new ArrayList<>();
        for (String id : declared) if (!handled.contains(id)) missing.add(id);
        List<String> extra = new ArrayList<>();
        for (String id : handled) if (!declared.contains(id)) extra.add(id);
        if (!missing.isEmpty()) getLogger().warning("Module '" + module.manifest().code() + "': no handler for " + missing + " (declared in game-schema.json).");
        if (!extra.isEmpty()) getLogger().warning("Module '" + module.manifest().code() + "': handlers " + extra + " are not in game-schema.json.");
    }
}
