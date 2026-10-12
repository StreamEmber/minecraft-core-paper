package com.streamember.minecraft.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.streamember.minecraft.api.GameAction;
import com.streamember.minecraft.api.JsonValues;
import com.streamember.minecraft.api.StreamEmberModule;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /streamember} ({@code /se}):
 * <pre>
 * status                          durum: kimlik, aktif modül, bağlantı, ayar revizyonu, hedef oyuncu
 * play &lt;code&gt;                     modülü etkinleştir (tek aktif modül)
 * stop                            aktif modülü kapat
 * target [oyuncu]                 aksiyonların hedeflediği oyuncu (yayıncı)
 * reload                          config.yml + kimlik + ayarları yeniden yükle, yeniden bağlan
 * reset                           GCore sayaç/limit/birikimlerini sıfırla
 * test &lt;action&gt; [adet] [json]    aksiyonu yerelde tetikle (EventFabric gerekmez)
 * </pre>
 */
final class StreamEmberCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("status", "play", "stop", "target", "reload", "reset", "test");

    private final StreamEmberCorePlugin core;

    StreamEmberCommand(StreamEmberCorePlugin core) {
        this.core = core;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "play" -> play(sender, args);
            case "stop" -> {
                core.stop();
                sender.sendMessage(Messages.ok("Active module stopped."));
            }
            case "target" -> target(sender, args);
            case "reload" -> {
                core.reloadAll(sender);
                sender.sendMessage(Messages.ok("Reloading config, customer identity and game settings…"));
            }
            case "reset" -> reset(sender);
            case "test" -> test(sender, args);
            default -> sender.sendMessage(Messages.error("Usage: /" + label + " <" + String.join("|", SUBCOMMANDS) + ">"));
        }
        return true;
    }

    private void status(CommandSender sender) {
        Identity identity = core.identity();
        ActiveSession session = core.activeSession();
        sender.sendMessage(Messages.info("Status"));
        sender.sendMessage(Messages.row("Customer", identity == null ? "unknown" : identity.customerUuid() + " (" + identity.source().name().toLowerCase(Locale.ROOT) + ")", identity != null));
        sender.sendMessage(Messages.row("Modules", core.modules().isEmpty() ? "none" : String.join(", ", core.modules().keySet()), !core.modules().isEmpty()));
        if (session == null) {
            sender.sendMessage(Messages.row("Active", "none — /se play <code>", false));
        } else {
            StreamEmberModule module = session.module();
            sender.sendMessage(Messages.row("Active", module.manifest().code() + " · " + module.manifest().name(), true));
            sender.sendMessage(Messages.row("Application", module.applicationUuid(), true));
            sender.sendMessage(Messages.row("EventFabric", session.isConnected() ? "connected" : "not connected", session.isConnected()));
            sender.sendMessage(Messages.row("Settings", session.settingsRevision(), !session.settingsRevision().equals("schema")));
        }
        String target = core.targetPlayer().map(Player::getName).orElse("nobody online");
        String configured = core.configuredTarget();
        sender.sendMessage(Messages.row("Target", target + (configured.isEmpty() ? " (auto)" : configured.equals(target) ? "" : " (chosen " + configured + " is offline)"), core.targetPlayer().isPresent()));
    }

    private void play(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Messages.error("Usage: /se play <" + String.join("|", core.modules().keySet()) + ">"));
            return;
        }
        StreamEmberModule module = core.modules().get(args[1].toLowerCase(Locale.ROOT));
        if (module == null) {
            sender.sendMessage(Messages.error("Unknown module '" + args[1] + "'. Installed: " + String.join(", ", core.modules().keySet())));
            return;
        }
        core.activate(module, sender);
        sender.sendMessage(Messages.ok("Active module: " + module.manifest().code() + " (" + module.manifest().name() + ")"));
    }

    private void target(CommandSender sender, String[] args) {
        String name;
        if (args.length >= 2) {
            name = args[1].equals("-") ? "" : args[1];
        } else if (sender instanceof Player player) {
            name = player.getName();
        } else {
            sender.sendMessage(Messages.error("Usage: /se target <player|->"));
            return;
        }
        core.targetPlayer(name);
        sender.sendMessage(Messages.ok(name.isEmpty() ? "Target: automatic (first online player)." : "Target player: " + name));
    }

    private void reset(CommandSender sender) {
        ActiveSession session = core.activeSession();
        if (session == null) {
            sender.sendMessage(Messages.error("No active module."));
            return;
        }
        session.resetGCore().thenAccept(ok -> Bukkit.getScheduler().runTask(core, () ->
                sender.sendMessage(ok ? Messages.ok("GCore state reset.") : Messages.error("GCore reset failed (see console)."))));
    }

    /**
     * Aksiyonu EventFabric'e gitmeden çalıştırır. Argümanlar: şema varsayılanları ← Falcon'daki bu aksiyona ait
     * ilk eşleştirmenin argümanları ← komutta verilen JSON.
     */
    private void test(CommandSender sender, String[] args) {
        ActiveSession session = core.activeSession();
        if (session == null) {
            sender.sendMessage(Messages.error("No active module. /se play <code>"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Messages.error("Usage: /se test <" + String.join("|", session.module().manifest().actionIds()) + "> [quantity] [json]"));
            return;
        }
        String actionId = args[1];
        int quantity = 1;
        int jsonStart = 2;
        if (args.length > 2) {
            try {
                quantity = Math.max(1, Math.min(100, Integer.parseInt(args[2])));
                jsonStart = 3;
            } catch (NumberFormatException ignored) {
                // üçüncü parça JSON
            }
        }
        JsonObject arguments = session.module().manifest().argumentDefaults(actionId);
        for (JsonElement element : session.mappings()) {
            if (element instanceof JsonObject mapping && actionId.equals(text(mapping, "action")) && mapping.get("arguments") instanceof JsonObject configured) {
                arguments = JsonValues.merge(arguments, configured);
                break;
            }
        }
        if (args.length > jsonStart) {
            String raw = String.join(" ", Arrays.copyOfRange(args, jsonStart, args.length));
            try {
                if (!(JsonParser.parseString(raw) instanceof JsonObject override)) throw new JsonParseException("not an object");
                arguments = JsonValues.merge(arguments, override);
            } catch (JsonParseException e) {
                sender.sendMessage(Messages.error("Arguments must be a JSON object, e.g. {\"count\":2}"));
                return;
            }
        }

        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 2);
        json.addProperty("id", "test-" + UUID.randomUUID());
        json.addProperty("customerUuid", core.customerUuid().orElse(""));
        json.addProperty("applicationUuid", session.applicationUuid());
        json.addProperty("action", actionId);
        json.add("arguments", arguments);
        json.addProperty("quantity", quantity);
        JsonObject trigger = new JsonObject();
        trigger.addProperty("platform", "streamember");
        trigger.addProperty("kind", "manual");
        trigger.addProperty("event", "streamember.manual");
        json.add("trigger", trigger);
        JsonObject viewer = new JsonObject();
        viewer.addProperty("id", sender instanceof Player player ? player.getUniqueId().toString() : "console");
        viewer.addProperty("username", sender.getName());
        viewer.addProperty("nickname", sender.getName());
        json.add("viewer", viewer);

        core.dispatcher().run(session, new GameAction(json, true));
        sender.sendMessage(Messages.ok("Test action: " + actionId + " ×" + quantity + " " + arguments));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(SUBCOMMANDS, args[0]);
        if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "play" -> {
                    return filter(new ArrayList<>(core.modules().keySet()), args[1]);
                }
                case "target" -> {
                    List<String> names = new ArrayList<>();
                    names.add("-");
                    Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
                    return filter(names, args[1]);
                }
                case "test" -> {
                    ActiveSession session = core.activeSession();
                    return session == null ? List.of() : filter(new ArrayList<>(session.module().manifest().actionIds()), args[1]);
                }
                default -> {
                    return List.of();
                }
            }
        }
        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
