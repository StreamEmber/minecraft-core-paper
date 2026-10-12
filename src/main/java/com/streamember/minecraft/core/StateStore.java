package com.streamember.minecraft.core;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

/** Çalışırken değişen seçimler ({@code state.yml}); config.yml'e yazılmaz, yorumları bozulmaz. */
final class StateStore {

    private final File file;
    private final Logger logger;
    private final YamlConfiguration yaml;

    StateStore(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
        this.yaml = YamlConfiguration.loadConfiguration(file);
    }

    /** {@code /se play} ile seçilen modülün kodu. */
    String activeModule() {
        return yaml.getString("active-module", "");
    }

    void activeModule(String code) {
        yaml.set("active-module", code == null ? "" : code);
        save();
    }

    /** {@code /se target} ile seçilen oyuncu adı. */
    String targetPlayer() {
        return yaml.getString("target-player", "");
    }

    void targetPlayer(String name) {
        yaml.set("target-player", name == null ? "" : name);
        save();
    }

    private void save() {
        try {
            yaml.save(file);
        } catch (IOException e) {
            logger.warning("state.yml could not be saved: " + e.getMessage());
        }
    }
}
