package com.streamember.minecraft.api;

import org.bukkit.Bukkit;

import java.util.Optional;

/**
 * StreamEmberCore servisi ({@link org.bukkit.plugin.ServicesManager} üzerinden). Modüller bunu
 * doğrudan kullanmak zorunda değildir: {@link StreamEmberModule} kayıt işlemini kendisi yapar.
 */
public interface StreamEmber {

    static Optional<StreamEmber> find() {
        return Optional.ofNullable(Bukkit.getServicesManager().load(StreamEmber.class));
    }

    /** {@link StreamEmberModule#onEnable()} çağırır. */
    void registerModule(StreamEmberModule module);

    /** {@link StreamEmberModule#onDisable()} çağırır; modül aktifse önce devre dışı bırakılır. */
    void unregisterModule(StreamEmberModule module);

    Optional<StreamEmberModule> activeModule();

    Optional<GameSession> session();

    Optional<String> customerUuid();
}
