package com.streamember.minecraft.core;

import org.bukkit.configuration.file.FileConfiguration;

/** {@code plugins/StreamEmberCore/config.yml} */
record CoreConfig(
        String eventFabricUrl,
        String falconUrl,
        String launcherUrl,
        String customerUuid,
        int presenceIntervalSeconds,
        int settingsRefreshSeconds,
        boolean announceActions,
        int idleShutdownMinutes,
        boolean debug) {

    static CoreConfig from(FileConfiguration config) {
        return new CoreConfig(
                url(config.getString("eventfabric-url", "https://eventfabric.streamember.com")),
                url(config.getString("falcon-url", "https://falcon.streamember.com")),
                url(config.getString("launcher-url", "http://127.0.0.1:47880")),
                config.getString("customer-uuid", "").trim(),
                Math.max(5, config.getInt("presence-interval-seconds", 15)),
                Math.max(10, config.getInt("settings-refresh-seconds", 60)),
                config.getBoolean("announce-actions", true),
                Math.max(0, Math.min(1440, config.getInt("idle-shutdown-minutes", 5))),
                config.getBoolean("debug", false));
    }

    /** {@code https://…} → {@code wss://…} */
    String eventFabricWsUrl() {
        return eventFabricUrl.replaceFirst("(?i)^http", "ws");
    }

    private static String url(String value) {
        String text = value == null ? "" : value.trim();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text;
    }
}
