package net.moddedrealms.testplayers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Linking the test players to an AI from in game: /testplayer ai link ollama|lmstudio|openai|openrouter|groq|
 * anthropic|pc|off, and /testplayer ai test. API keys are never typed in a command (servers log commands):
 * you're asked to paste the key in chat, and that message is taken out before anyone sees it or it's logged.
 */
final class AiLink implements Listener {

    private record Pending(String provider, String baseUrl, String model, String label, long until) { }

    /** name -> {provider in config, API address, default model, what to call it}. */
    private static final Map<String, String[]> PRESETS = Map.of(
            "openai", new String[] {"openai", "https://api.openai.com/v1", "gpt-4o-mini", "OpenAI"},
            "openrouter", new String[] {"openai", "https://openrouter.ai/api/v1", "meta-llama/llama-3.1-8b-instruct", "OpenRouter"},
            "groq", new String[] {"openai", "https://api.groq.com/openai/v1", "llama-3.1-8b-instant", "Groq"},
            "together", new String[] {"openai", "https://api.together.xyz/v1", "meta-llama/Meta-Llama-3.1-8B-Instruct-Turbo", "Together"},
            "anthropic", new String[] {"anthropic", "", "claude-haiku-5-5", "Claude (Anthropic)"});

    static final List<String> PROVIDERS = List.of("ollama", "lmstudio", "openai", "openrouter", "groq", "together", "anthropic", "pc", "off");

    private final TestPlayersPlugin plugin;
    private final Map<UUID, Pending> waiting = new ConcurrentHashMap<>();

    AiLink(TestPlayersPlugin plugin) {
        this.plugin = plugin;
    }

    /** /testplayer ai link <provider> [...] */
    void link(CommandSender sender, String[] a) {
        if (a.length < 3) {
            usage(sender);
            return;
        }
        String which = a[2].toLowerCase(Locale.ROOT);
        String arg3 = a.length > 3 ? a[3] : null;
        String arg4 = a.length > 4 ? a[4] : null;
        for (int i = 3; i < a.length; i++) {
            if (looksLikeKey(a[i])) {
                sender.sendMessage(Component.text("That looks like an API key. Keys don't go in commands: servers write every command to their log. "
                        + "Run /testplayer ai link " + which + " and paste the key in chat when asked. If that was a real key, make a new one: this one is in the log now.", NamedTextColor.RED));
                return;
            }
        }
        switch (which) {
            case "ollama" -> {
                String url = arg3 != null && arg3.startsWith("http") ? arg3.replaceAll("/+$", "") : plugin.getConfig().getString("ai.ollama-url", "http://127.0.0.1:11434");
                String model = arg3 != null && !arg3.startsWith("http") ? arg3 : arg4 != null ? arg4 : plugin.getConfig().getString("ai.model", "");
                plugin.getConfig().set("ai.provider", "ollama");
                plugin.getConfig().set("ai.ollama-url", url);
                plugin.getConfig().set("ai.model", model == null ? "" : model);
                save();
                ok(sender, "Linked to Ollama at " + url + ". Checking it answers...");
                plugin.llm().ollamaModels(url).whenComplete((names, err) -> sync(() -> {
                    if (err != null) {
                        bad(sender, "Ollama at " + url + " isn't answering (" + cause(err) + "). Is it running, and can this server reach it? "
                                + "(On a host like Minehut there's no Ollama: use /testplayer ai link pc <your website name> to think on your own PC, or an API.)");
                        return;
                    }
                    String m = plugin.llm().modelFor(new TestPlayer("x"));
                    ok(sender, "Ollama is up with " + names.size() + " model(s): " + String.join(", ", names.subList(0, Math.min(8, names.size()))) + ".");
                    if (names.stream().noneMatch(n -> n.equals(m) || n.equals(m + ":latest")))
                        bad(sender, "It doesn't have " + m + " yet. On that machine run: ollama pull " + m + "   (or link another: /testplayer ai link ollama " + (names.isEmpty() ? "<model>" : names.getFirst()) + ")");
                    else ok(sender, "Using " + m + ". Free players get their AI on by themselves; /testplayer ai all on for everyone. /testplayer ai test to try it.");
                }));
            }
            case "lmstudio", "local" -> {
                String url = arg3 != null && arg3.startsWith("http") ? arg3.replaceAll("/+$", "") : "http://127.0.0.1:1234/v1";
                String model = arg3 != null && !arg3.startsWith("http") ? arg3 : arg4 != null ? arg4 : "";
                plugin.getConfig().set("ai.provider", "openai");
                plugin.getConfig().set("ai.api.base-url", url);
                plugin.getConfig().set("ai.model", model);
                save();
                if (!plugin.llm().localApi()) {
                    bad(sender, url + " isn't on this machine or your local network, so it counts as a paid API (with the spending caps). For a paid API use /testplayer ai link openai.");
                }
                ok(sender, "Linked to the local AI server at " + url + ". Checking it answers...");
                plugin.llm().apiModels().whenComplete((names, err) -> sync(() -> {
                    if (err != null) {
                        bad(sender, url + " isn't answering (" + cause(err) + "). Start the server in LM Studio (Developer tab) and load a model.");
                        return;
                    }
                    if (model.isEmpty() && !names.isEmpty()) {
                        plugin.getConfig().set("ai.model", names.getFirst());
                        save();
                    }
                    ok(sender, "It answered with model(s): " + String.join(", ", names.subList(0, Math.min(8, names.size()))) + ". Using "
                            + plugin.llm().modelFor(new TestPlayer("x")) + ". /testplayer ai test to try it.");
                }));
            }
            case "pc" -> {
                if (arg3 == null) {
                    bad(sender, "Give your name on the website: /testplayer ai link pc <name> [model]. The model runs on your own PC through the website's connector (free, works on Minehut).");
                    return;
                }
                plugin.getConfig().set("ai.provider", "pc");
                plugin.getConfig().set("ai.pc-staff", arg3);
                if (arg4 != null) plugin.getConfig().set("ai.model", arg4);
                save();
                ok(sender, "Test players will think on " + arg3 + "'s PC through the website (model " + plugin.llm().modelFor(new TestPlayer("x")) + ").");
                if (!plugin.link().connected()) bad(sender, "The website link isn't set up yet: set website.enabled, url and manager-key in config.yml, then /testplayer reload. Website: " + plugin.link().status());
                else ok(sender, "Keep the connector running on that PC. /testplayer ai test to try it.");
            }
            case "off", "none" -> {
                plugin.getConfig().set("ai.provider", "none");
                save();
                ok(sender, "AI unlinked. Test players carry on with their own routines.");
            }
            default -> {
                String[] p = PRESETS.get(which);
                if (p == null) {
                    usage(sender);
                    return;
                }
                String model = arg3 != null ? arg3 : p[2];
                if (!(sender instanceof Player player)) {
                    plugin.getConfig().set("ai.provider", p[0]);
                    if (!p[1].isEmpty()) plugin.getConfig().set("ai.api.base-url", p[1]);
                    plugin.getConfig().set("ai.model", model);
                    save();
                    ok(sender, "Set to " + p[3] + " (" + model + "). Now put the key in config.yml under ai.api.key and run /testplayer reload "
                            + "(keys aren't taken as command arguments: commands are logged). Or run this command in game and paste the key in chat.");
                    return;
                }
                waiting.put(player.getUniqueId(), new Pending(p[0], p[1], model, p[3], System.currentTimeMillis() + 90_000));
                player.sendMessage(Component.text("Paste your " + p[3] + " API key in chat now. ", NamedTextColor.GOLD)
                        .append(Component.text("It's taken out of chat before anyone sees it, isn't logged, and is only ever stored in config.yml. Type cancel to stop.", NamedTextColor.GRAY)));
            }
        }
    }

    /**
     * The AI & API form on the Bots page: provider, model, address, staff PC, a new key (or none to keep the one
     * set) and the spending caps. Saves config.yml; returns what it did. The key is never echoed or logged.
     */
    String applyFromWebsite(JsonObject a, String who) {
        var c = plugin.getConfig();
        String which = str(a, "provider").toLowerCase(Locale.ROOT);
        String model = str(a, "model");
        String url = str(a, "url");
        if (!model.isEmpty() && !model.matches("^[\\w.:/-]{1,80}$")) throw new IllegalArgumentException("That isn't a model name.");
        if (!url.isEmpty() && !url.matches("^https?://[\\w.:\\[\\]-]+(/[\\w./-]*)?$")) throw new IllegalArgumentException("That isn't a web address (http://host:port).");
        String label;
        switch (which) {
            case "ollama" -> {
                c.set("ai.provider", "ollama");
                if (!url.isEmpty()) c.set("ai.ollama-url", url.replaceAll("/+$", ""));
                label = "Ollama";
            }
            case "lmstudio", "local" -> {
                c.set("ai.provider", "openai");
                c.set("ai.api.base-url", url.isEmpty() ? "http://127.0.0.1:1234/v1" : url.replaceAll("/+$", ""));
                label = "the local AI server";
            }
            case "pc" -> {
                String staff = str(a, "staff").isEmpty() ? who : str(a, "staff");
                if (!staff.matches("^[\\w.-]{1,32}$")) throw new IllegalArgumentException("That isn't a website name.");
                c.set("ai.provider", "pc");
                c.set("ai.pc-staff", staff);
                label = staff + "'s PC";
            }
            case "off", "none" -> {
                c.set("ai.provider", "none");
                label = "nothing";
            }
            default -> {
                String[] p = PRESETS.get(which);
                if (p == null) throw new IllegalArgumentException("Pick a provider: " + String.join(", ", PROVIDERS) + ".");
                c.set("ai.provider", p[0]);
                if (!p[1].isEmpty()) c.set("ai.api.base-url", p[1]);
                if (model.isEmpty()) model = p[2];
                label = p[3];
            }
        }
        c.set("ai.model", model);
        String key = str(a, "key");
        if (!key.isEmpty()) {
            if (key.contains(" ") || key.length() < 16 || key.length() > 400) throw new IllegalArgumentException("That doesn't look like an API key; nothing was saved.");
            c.set("ai.api.key", key);
            c.set("ai.api.enabled", true);
        }
        if (a.has("clearKey") && a.get("clearKey").getAsBoolean()) c.set("ai.api.key", "");
        if (a.has("caps") && a.get("caps").isJsonObject()) {
            JsonObject caps = a.getAsJsonObject("caps");
            capInt(caps, "maxRequestsPerHour", "ai.api.max-requests-per-hour", 0, 100_000);
            capInt(caps, "maxTokensPerDay", "ai.api.max-tokens-per-day", 0, 100_000_000);
            capInt(caps, "maxPlayers", "ai.api.max-players", 0, 200);
            capInt(caps, "minSecondsPerPlayer", "ai.api.min-seconds-per-player", 10, 3600);
            capInt(caps, "maxAnswerTokens", "ai.api.max-answer-tokens", 50, 1000);
            capNum(caps, "maxDollarsPerDay", "ai.api.max-dollars-per-day");
            capNum(caps, "pricePerMillionIn", "ai.api.price-per-million-input-tokens");
            capNum(caps, "pricePerMillionOut", "ai.api.price-per-million-output-tokens");
        }
        save();
        plugin.getLogger().info("AI link changed on the website by " + who + ": " + label + (model.isEmpty() ? "" : " (" + model + ")") + (key.isEmpty() ? "" : ", new API key"));
        return "Linked to " + label + (model.isEmpty() ? "" : " with " + plugin.llm().modelFor(new TestPlayer("x"))) + (key.isEmpty() ? "" : ", key saved") + ". " + plugin.llm().usage();
    }

    private void capInt(JsonObject caps, String k, String path, long lo, long hi) {
        if (!caps.has(k) || caps.get(k).isJsonNull()) return;
        try {
            long v = caps.get(k).getAsLong();
            plugin.getConfig().set(path, Math.max(lo, Math.min(hi, v)));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(k + " should be a number.");
        }
    }

    private void capNum(JsonObject caps, String k, String path) {
        if (!caps.has(k) || caps.get(k).isJsonNull()) return;
        try {
            plugin.getConfig().set(path, Math.max(0, Math.min(10_000, caps.get(k).getAsDouble())));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(k + " should be a number.");
        }
    }

    /** The link test, with the answer going to the Bots page instead of a player. */
    void testForWebsite(String who) {
        TestPlayer probe = new TestPlayer("~check");
        probe.model = "default";
        long t0 = System.currentTimeMillis();
        JsonObject schema = JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"reply\":{\"type\":\"string\",\"maxLength\":60}},\"required\":[\"reply\"]}").getAsJsonObject();
        String where = plugin.llm().placeFor(probe);
        plugin.llm().chat(probe, "You are checking a connection. Reply with ONLY JSON.", "Say hi to the Minecraft server in a few words, as \"reply\".", schema)
                .whenComplete((text, err) -> sync(() -> {
                    plugin.llm().release(probe);
                    if (err != null) {
                        plugin.link().aiTest(false, "No answer from " + where + ": " + cause(err), who);
                        return;
                    }
                    String reply = text;
                    try {
                        reply = Llm.parse(text).get("reply").getAsString();
                    } catch (RuntimeException ignored) {
                        // shown as it came
                    }
                    if (reply.length() > 80) reply = reply.substring(0, 80);
                    plugin.link().aiTest(true, where + " answered in " + String.format(Locale.ROOT, "%.1f", (System.currentTimeMillis() - t0) / 1000.0) + "s: \"" + reply + "\"", who);
                }));
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString().trim() : "";
    }

    private static boolean looksLikeKey(String s) {
        return s.matches("^(sk-|sk_|gsk_|xai-|AIza|or-).{12,}") || s.length() >= 40 && !s.contains("/") && !s.startsWith("http");
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(Component.text("Link the test players to an AI:", NamedTextColor.GOLD));
        sender.sendMessage(Component.text(" /testplayer ai link ollama [url] [model]   - Ollama (free, your own machine)", NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" /testplayer ai link lmstudio [url] [model] - LM Studio or any local OpenAI-style server (free)", NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" /testplayer ai link pc <website name> [model] - your own PC through the website (free, works on Minehut)", NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" /testplayer ai link openai|openrouter|groq|together|anthropic [model] - an API key (asked for in chat, with spending caps)", NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" /testplayer ai link off | /testplayer ai test | /testplayer api", NamedTextColor.GRAY));
    }

    /** A player was asked for their API key: this is it. Taken out of chat first, before anything else sees it. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Pending p = waiting.get(e.getPlayer().getUniqueId());
        if (p == null) return;
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        boolean keyish = !text.contains(" ") && text.length() >= 16;
        if (System.currentTimeMillis() > p.until() && !keyish) {
            waiting.remove(e.getPlayer().getUniqueId());
            return; // an ordinary message long after: let it through
        }
        e.setCancelled(true);
        waiting.remove(e.getPlayer().getUniqueId());
        Player player = e.getPlayer();
        if (text.equalsIgnoreCase("cancel")) {
            sync(() -> player.sendMessage(Component.text("Cancelled: no key saved.", NamedTextColor.GRAY)));
            return;
        }
        if (!keyish || text.length() > 400) {
            sync(() -> bad(player, "That doesn't look like an API key, so nothing was saved (and your message wasn't sent). Run the link command again to retry."));
            return;
        }
        if (System.currentTimeMillis() > p.until()) {
            sync(() -> bad(player, "That took too long, so the key wasn't saved (and wasn't sent to chat). Run the link command again."));
            return;
        }
        sync(() -> {
            plugin.getConfig().set("ai.provider", p.provider());
            if (!p.baseUrl().isEmpty()) plugin.getConfig().set("ai.api.base-url", p.baseUrl());
            plugin.getConfig().set("ai.model", p.model());
            plugin.getConfig().set("ai.api.key", text);
            plugin.getConfig().set("ai.api.enabled", true);
            save();
            ok(player, "Key saved (" + text.length() + " characters; it's not shown anywhere). Linked to " + p.label() + " with " + p.model() + ".");
            player.sendMessage(Component.text("Spending caps: " + caps() + ". Change them under ai.api in config.yml. Testing it now (1 small request)...", NamedTextColor.GRAY));
            test(player);
        });
    }

    private String caps() {
        var c = plugin.getConfig();
        String s = c.getInt("ai.api.max-requests-per-hour", 120) + " requests/hour, " + String.format(Locale.ROOT, "%,d", c.getLong("ai.api.max-tokens-per-day", 200_000))
                + " tokens/day, " + c.getInt("ai.api.max-players", 4) + " test players on it at once, one request per player every " + c.getInt("ai.api.min-seconds-per-player", 60) + "s";
        if (c.getDouble("ai.api.max-dollars-per-day", 0) > 0) s += ", $" + c.getDouble("ai.api.max-dollars-per-day") + "/day";
        return s;
    }

    /** /testplayer ai test: one small request, to see the link works (counts against the caps like any other). */
    void test(CommandSender sender) {
        TestPlayer probe = new TestPlayer("~check");
        probe.model = "default";
        long t0 = System.currentTimeMillis();
        JsonObject schema = JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"reply\":{\"type\":\"string\",\"maxLength\":60}},\"required\":[\"reply\"]}").getAsJsonObject();
        String where = plugin.llm().placeFor(probe);
        plugin.llm().chat(probe, "You are checking a connection. Reply with ONLY JSON.", "Say hi to the Minecraft server in a few words, as \"reply\".", schema)
                .whenComplete((text, err) -> sync(() -> {
                    plugin.llm().release(probe);
                    if (err != null) {
                        bad(sender, "No answer from " + where + ": " + cause(err));
                        return;
                    }
                    String reply = text;
                    try {
                        reply = Llm.parse(text).get("reply").getAsString();
                    } catch (RuntimeException ignored) {
                        // shown as it came
                    }
                    if (reply.length() > 80) reply = reply.substring(0, 80);
                    ok(sender, where + " answered in " + String.format(Locale.ROOT, "%.1f", (System.currentTimeMillis() - t0) / 1000.0) + "s: \"" + reply + "\"");
                    if (plugin.llm().paid()) sender.sendMessage(Component.text("API budget: " + plugin.llm().usage(), NamedTextColor.GRAY));
                }));
    }

    private void save() {
        plugin.saveConfig();
        plugin.reload();
    }

    private void sync(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(plugin, r);
    }

    private static String cause(Throwable err) {
        Throwable c = err.getCause() != null ? err.getCause() : err;
        String m = c.getMessage();
        return m == null || m.isBlank() ? c.getClass().getSimpleName() : m;
    }

    private static void ok(CommandSender s, String text) {
        s.sendMessage(Component.text(text, NamedTextColor.GREEN));
    }

    private static void bad(CommandSender s, String text) {
        s.sendMessage(Component.text(text, NamedTextColor.RED));
    }
}
