# StreamEmber Core · Minecraft (Paper)

Canlı yayın etkileşimlerini (TikTok hediyesi, beğeni, yorum…) Minecraft oyun modlarına ileten Paper eklentisi
(`StreamEmberCore`, `streamember-core.jar`). Oyun modları ayrı repolardadır (`minecraft-<ad>-paper`) ve bu
çekirdeğin API'sine (`com.streamember.minecraft.api`) karşı derlenir; yeni mod için
[`minecraft-mod-template`](https://github.com/StreamEmber/minecraft-mod-template).

```
İzleyici hediyesi ─► EventFabric (normalize) ─► GCore (oyun ayarına göre hesap) ─► GameAction
                                                                                      │ WebSocket
                                       StreamEmberCore (Paper) ◄──────────────────────┘
                                             │  aynı id bir kez · ana thread
                                             ▼
                                   Aktif mod: actions.on("soldier.spawnFriend", …)
```

Gereken: JDK 21+, Paper 1.21.11.

## Derleme

```powershell
.\build.ps1            # derle → %MINECRAFT_SERVER_PLUGINS_PATH%\streamember-core.jar (sonra sunucuyu yeniden başlat)
.\build.ps1 -NoCopy    # yalnız build\libs\streamember-core.jar
```

Gradle: `gradlew deploy` / `gradlew jar`; internetsiz: `-PpaperLibs="D:\Games\MinecaftServer\libraries"`.

## Yayın

`gradle.properties` → `version` artırılır, `CHANGELOG.md`'ye `## <sürüm> (tarih)` bölümü eklenir, `main`'e push edilir.
CI bu sürümün release'i yoksa derler ve `v<sürüm>` release'ine `streamember-core.jar` + `.sha256` koyar.
Oyun repoları derlerken son release'i indirir. API kırılırsa major sürüm artar.

## Kimlikler

- **Oyun (application) UUID'si** her modülün jar'ına gömülüdür: `src/main/resources/streamember-module.json`.
  Falcon'daki oyunun UUID'siyle aynıdır ve asla değişmez. Sunucu ayarından değiştirilemez.
- **Müşteri (yayıncı) UUID'si** sırayla şuradan alınır:
  1. `plugins/StreamEmberCore/config.yml` → `customer-uuid` (elle verildiyse)
  2. Launcher'ın yerel API'si: `GET http://127.0.0.1:47880/api/customer` (launcher açık ve giriş yapılmışsa)
  3. `STREAMEMBER_CUSTOMER_UUID` ortam değişkeni

Launcher'dan gelen kimlik 15 sn'de bir yeniden kontrol edilir. Hesap değişirse bağlantı kendiliğinden yenilenir.

## Çekirdeğin yaptıkları

- **Tek aktif modül.** Aynı anda bir oyun modu çalışır. Seçim `/se play <code>` ile yapılır ve
  `state.yml`'de saklanır. Tek modül kuruluysa kendiliğinden etkinleşir.
- **Presence.** `GET {eventfabric}/presence?customerUuid=…&applicationUuid=…&source=minecraft`
  çağrısını 15 sn'de bir yapar. Kimlik launcher'dan geldiyse launcher'a da
  `POST /api/active-application` bildirir.
- **Oyun ayarları.** `GET {falcon}/public/game/config/{customer}/{application}` adresinden okunur,
  60 sn'de bir yenilenir. Şemadaki varsayılanların üzerine birleştirilir; Falcon'a ulaşılamazsa
  şema varsayılanları kullanılır.
- **Aksiyonlar.** `wss://{eventfabric}/events/ws?format=json&customerUuid=…&applicationUuid=…`
  bağlantısıyla gelir.
  - Yalnızca `packetType: action` frame'leri işlenir.
  - Aynı `id` ikinci kez çalışmaz.
  - İşleyici her zaman ana thread'de çağrılır.
  - Koparsa son cursor'dan `replay` ile yeniden bağlanır (1–30 sn arası bekleme).
- **Reset.** `GET {eventfabric}/gcore/reset?customerUuid=…&applicationUuid=…`

## Komutlar (`/streamember`, kısaca `/se`; op)

| Komut | |
|---|---|
| `/se status` | Kimlik, modüller, aktif modül, EventFabric bağlantısı, ayar revizyonu, hedef oyuncu |
| `/se play <code>` | Modülü etkinleştir |
| `/se stop` | Aktif modülü kapat |
| `/se target [oyuncu\|-]` | Aksiyonların hedeflediği oyuncu (yayıncı); `-` otomatik seçer |
| `/se reload` | `config.yml`, kimlik ve ayarları yeniden yükle, yeniden bağlan |
| `/se reset` | GCore sayaç, limit ve birikimlerini sıfırla |
| `/se test <aksiyon> [adet] [json]` | Aksiyonu EventFabric olmadan çalıştır, ör. `/se test soldier.spawnEnemy 3 {"radius":12}` |

`/se test` argümanları sırayla birleştirir: şema varsayılanları, Falcon'daki o aksiyona ait
eşleştirmenin argümanları, komutta verilen JSON.

