package com.streamember.minecraft.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * GCore'un gönderdiği aksiyon — EventContracts {@code GameAction} v2 (düz JSON).
 *
 * <pre>
 * { "id", "customerUuid", "applicationUuid", "action", "arguments": {…}, "quantity",
 *   "trigger": { "platform", "target", "roomId", "kind", "category", "event", "eventId" },
 *   "viewer": { "id", "username", "nickname", "avatarUrl" },
 *   "gift" | "comment" | "like" | "subscription" | "reward": {…} }
 * </pre>
 *
 * GCore bütün hesabı (eşleşme, birikim, cooldown, limit) zaten yaptı: aksiyon geldiği an çalıştırılır.
 */
public final class GameAction {

    private final JsonObject json;
    private final boolean test;

    public GameAction(JsonObject json, boolean test) {
        this.json = json == null ? new JsonObject() : json;
        this.test = test;
    }

    /** Deterministik aksiyon kimliği; çekirdek aynı id'yi ikinci kez çalıştırmaz. */
    public String id() {
        return text(json, "id");
    }

    /** Oyun şemasındaki {@code actions[].id}, ör. {@code soldier.spawnFriend}. */
    public String action() {
        return text(json, "action");
    }

    /** Kullanıcının bu aksiyon için ayarladığı argümanlar. */
    public JsonValues arguments() {
        return json.get("arguments") instanceof JsonObject object ? new JsonValues(object) : JsonValues.empty();
    }

    /**
     * Şemadaki argüman varsayılanlarıyla tamamlanmış kopya: ağdan gelen argümanlar önceliklidir,
     * eksik olanlar {@code defaults}'tan gelir (eski kayıtlar ve şemaya sonradan eklenen argümanlar için).
     */
    public GameAction withArgumentDefaults(JsonObject defaults) {
        if (defaults == null || defaults.size() == 0) return this;
        JsonObject copy = json.deepCopy();
        JsonObject network = json.get("arguments") instanceof JsonObject object ? object : new JsonObject();
        copy.add("arguments", JsonValues.merge(defaults, network));
        return new GameAction(copy, test);
    }

    /** Bu aksiyonun temsil ettiği birim (ör. 5'li gül kombosu = 5). En az 1. */
    public int quantity() {
        return Math.max(1, new JsonValues(json).getInt("quantity", 1));
    }

    /** Oyun ayarındaki eşleştirmenin id'si ({@code events[].id}). */
    public String ruleId() {
        return text(json, "ruleId");
    }

    public Viewer viewer() {
        JsonObject viewer = object(json, "viewer");
        return new Viewer(text(viewer, "id"), text(viewer, "username"), text(viewer, "nickname"), text(viewer, "avatarUrl"));
    }

    public Trigger trigger() {
        JsonObject trigger = object(json, "trigger");
        return new Trigger(text(trigger, "platform"), text(trigger, "target"), text(trigger, "roomId"),
                text(trigger, "kind"), text(trigger, "category"), text(trigger, "event"), text(trigger, "eventId"));
    }

    /** Hediye / bağış tetiklemelerinde. */
    public Optional<Gift> gift() {
        if (!(json.get("gift") instanceof JsonObject gift)) return Optional.empty();
        JsonValues values = new JsonValues(gift);
        return Optional.of(new Gift(text(gift, "id"), text(gift, "name"), text(gift, "imageUrl"),
                values.getInt("coins", 0), Math.max(1, values.getInt("count", 1)), values.getInt("totalCoins", 0)));
    }

    /** Yorum tetiklemelerinde yorum metni. */
    public Optional<String> comment() {
        return json.get("comment") instanceof JsonObject comment ? Optional.of(text(comment, "text")) : Optional.empty();
    }

    /** Beğeni / tepki tetiklemelerinde beğeni sayısı. */
    public OptionalInt likeCount() {
        return json.get("like") instanceof JsonObject like
                ? OptionalInt.of(Math.max(1, new JsonValues(like).getInt("count", 1)))
                : OptionalInt.empty();
    }

    /** {@code /se test} ile elle tetiklendi mi? */
    public boolean isTest() {
        return test;
    }

    /** Ham JSON'un kopyası. */
    public JsonObject json() {
        return json.deepCopy();
    }

    /**
     * Şablon doldurur: {@code {user}} (görünen ad), {@code {username}}, {@code {nickname}},
     * {@code {quantity}}, {@code {gift}}, {@code {comment}}.
     */
    public String render(String template) {
        if (template == null || template.isEmpty()) return "";
        Viewer viewer = viewer();
        return template
                .replace("{user}", viewer.displayName())
                .replace("{username}", viewer.username())
                .replace("{nickname}", viewer.nickname().isEmpty() ? viewer.displayName() : viewer.nickname())
                .replace("{quantity}", Integer.toString(quantity()))
                .replace("{gift}", gift().map(Gift::name).orElse(""))
                .replace("{comment}", comment().orElse(""));
    }

    /** Kısa açıklama: {@code "Zeynep → Rose ×5"}. */
    public String describe() {
        String detail = gift().map(g -> g.name().isEmpty() ? g.id() : g.name()).map(name -> name + " ×" + quantity())
                .or(() -> comment().map(text -> "\"" + text + "\""))
                .orElseGet(() -> {
                    OptionalInt likes = likeCount();
                    if (likes.isPresent()) return "♥ ×" + likes.getAsInt();
                    String kind = trigger().kind();
                    return kind.isEmpty() ? "" : kind;
                });
        return viewer().displayName() + (detail.isEmpty() ? "" : " → " + detail);
    }

    @Override
    public String toString() {
        return "GameAction{" + action() + ", id=" + id() + ", quantity=" + quantity() + "}";
    }

    public record Viewer(String id, String username, String nickname, String avatarUrl) {
        /** Takma ad, yoksa kullanıcı adı, o da yoksa "Viewer". */
        public String displayName() {
            if (!nickname.isBlank()) return nickname;
            if (!username.isBlank()) return username;
            return "Viewer";
        }
    }

    /**
     * Tetikleyici (EventContracts EVENT_IDENTITY.md): {@code kind} kanonik tür ({@code gift}, {@code like}, {@code comment},
     * elle tetiklemede {@code manual}), {@code category} ({@code support}, {@code audience}…), {@code event} noktalı katalog
     * kimliği ({@code tiktok.gift.5655}, {@code tiktok.like}, {@code streamember.manual}).
     */
    public record Trigger(String platform, String target, String roomId, String kind, String category, String event, String eventId) {
    }

    /** {@code coins}: tek hediyenin değeri; {@code totalCoins} = coins × count. */
    public record Gift(String id, String name, String imageUrl, int coins, int count, int totalCoins) {
    }

    static String text(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value instanceof JsonPrimitive primitive ? primitive.getAsString() : "";
    }

    static JsonObject object(JsonObject object, String key) {
        return object != null && object.get(key) instanceof JsonObject child ? child : new JsonObject();
    }
}
