package com.streamember.minecraft.core;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Boşta kapanma: sunucuda belirli bir süre hiç oyuncu yoksa sunucu kendini kapatır.
 *
 * <ul>
 *     <li>Sayaç sunucu açılınca başlar: launcher sunucuyu açıp istemciyi başlatırken oyuncu vazgeçerse
 *     sunucu arkada açık kalmaz. İstemcinin açılması zaman alabildiği için süre cömerttir (varsayılan 5 dk).</li>
 *     <li>Son oyuncu çıkınca sayaç yeniden başlar: istemci çökerse oyuncu süre içinde geri bağlanabilir;
 *     oyunu kapatıp gittiyse sunucu kaynak tüketmeye devam etmez.</li>
 *     <li>Bir oyuncu bağlanınca sayaç durur.</li>
 * </ul>
 *
 * {@code idle-shutdown-minutes: 0} kapatır (ör. geliştirme sunucusu).
 */
final class IdleShutdown {

    private static final long CHECK_TICKS = 20L * 15;

    private final Plugin plugin;
    private BukkitTask task;
    private int minutes;
    private long emptySince;

    IdleShutdown(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Ana thread. Süre değiştiyse sayaç baştan başlar. */
    void start(int minutes) {
        stop();
        this.minutes = minutes;
        if (minutes <= 0) {
            plugin.getLogger().info("Idle shutdown is disabled (idle-shutdown-minutes: 0).");
            return;
        }
        emptySince = Bukkit.getOnlinePlayers().isEmpty() ? System.currentTimeMillis() : 0L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::check, CHECK_TICKS, CHECK_TICKS);
        plugin.getLogger().info("Idle shutdown: the server stops after " + minutes + " minute(s) without players.");
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    private void check() {
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            emptySince = 0L;
            return;
        }
        long now = System.currentTimeMillis();
        if (emptySince == 0L) {
            emptySince = now;
            plugin.getLogger().info("No players online; the server stops in " + minutes + " minute(s) unless someone joins.");
            return;
        }
        if (now - emptySince < minutes * 60_000L) return;
        plugin.getLogger().info("No players for " + minutes + " minute(s); stopping the server.");
        stop();
        Bukkit.shutdown();
    }
}
