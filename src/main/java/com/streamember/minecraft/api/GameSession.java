package com.streamember.minecraft.api;

import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Aktif modülün oturumu: kimlik, Falcon'dan gelen ayarlar ve hedef oyuncu.
 * Modül etkinleştiğinde {@link StreamEmberModule#onActivate(GameSession)} ile verilir.
 */
public interface GameSession {

    StreamEmberModule module();

    /** Modülün gömülü UUID'si. */
    String applicationUuid();

    /** Yayıncının StreamEmber müşteri UUID'si; bilinmiyorsa (launcher kapalı, ayar yok) boş. */
    Optional<String> customerUuid();

    /**
     * Oyun ayarları: şema varsayılanları + Falcon'daki kullanıcı ayarı ({@code settings} bölümü).
     * Her okumada güncel değer döner; ayar değişince {@link StreamEmberModule#onSettingsChanged(JsonValues)} de çağrılır.
     */
    JsonValues settings();

    /** Falcon ayar revizyonu ({@code default}, {@code r3}…); ayar alınamadıysa {@code schema}. */
    String settingsRevision();

    /**
     * Aksiyonların hedeflediği oyuncu (yayıncı): {@code /se target} ile seçilen, yoksa sunucudaki tek oyuncu,
     * o da yoksa ilk oyuncu.
     */
    Optional<Player> targetPlayer();

    /** GCore birikim, limit ve kuyruklarını sıfırlar (bölüm bitti, oyun yeniden başladı…). */
    CompletableFuture<Boolean> resetGCore();

    /** EventFabric bağlantısı açık mı? */
    boolean isConnected();
}
