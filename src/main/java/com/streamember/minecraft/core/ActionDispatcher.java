package com.streamember.minecraft.core;

import com.google.gson.JsonObject;
import com.streamember.minecraft.api.ActionHandler;
import com.streamember.minecraft.api.GameAction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Aksiyonları aktif modülün işleyicisine taşır: aynı id bir kez çalışır, işleyici her zaman
 * ana thread'de çağrılır, hata modülü düşürmez.
 */
final class ActionDispatcher {

    private static final int REMEMBERED_IDS = 4096;

    private final StreamEmberCorePlugin core;
    private final Set<String> seen = Collections.newSetFromMap(new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > REMEMBERED_IDS;
        }
    });
    private final Set<String> unknownWarned = new HashSet<>();

    ActionDispatcher(StreamEmberCorePlugin core) {
        this.core = core;
    }

    /** EventFabric thread'inden çağrılır. */
    void fromNetwork(ActiveSession session, JsonObject json) {
        GameAction action = new GameAction(json, false);
        String id = action.id();
        if (id.isEmpty()) return;
        synchronized (seen) {
            if (!seen.add(id)) return;
        }
        String application = json.has("applicationUuid") ? json.get("applicationUuid").getAsString() : "";
        if (!application.isEmpty() && !application.equalsIgnoreCase(session.applicationUuid())) return;
        try {
            core.getServer().getScheduler().runTask(core, () -> run(session, action));
        } catch (RuntimeException e) {
            // çekirdek kapanıyor
        }
    }

    /** Ana thread. */
    void run(ActiveSession session, GameAction action) {
        if (core.activeSession() != session) return; // modül bu arada değişti
        ActionHandler handler = session.module().actionHandlers().get(action.action());
        if (handler == null) {
            if (unknownWarned.add(action.action())) {
                core.getLogger().warning("Module '" + session.module().manifest().code() + "' has no handler for action '"
                        + action.action() + "'. Check game-schema.json and registerActions().");
            }
            return;
        }
        // Şemadaki argüman varsayılanları tamamlanır (Falcon/EventFabric yalnız kaydedilmiş argümanları taşır).
        action = action.withArgumentDefaults(session.module().manifest().argumentDefaults(action.action()));
        if (core.coreConfig().debug()) core.getLogger().info("[debug] " + action + " " + action.describe());
        announce(action);
        try {
            handler.handle(action);
        } catch (Throwable error) {
            core.getLogger().log(Level.SEVERE, "Action '" + action.action() + "' failed in module '"
                    + session.module().manifest().code() + "'", error);
        }
    }

    private void announce(GameAction action) {
        if (!core.coreConfig().announceActions()) return;
        Player player = core.targetPlayer().orElse(null);
        if (player == null) return;
        player.sendActionBar(Component.text()
                .append(Component.text(action.describe(), NamedTextColor.GOLD))
                .append(Component.text("  ▸  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(action.action() + (action.quantity() > 1 ? " ×" + action.quantity() : ""), NamedTextColor.GREEN))
                .append(Component.text(action.isTest() ? "  (test)" : "", NamedTextColor.GRAY))
                .build());
    }
}
