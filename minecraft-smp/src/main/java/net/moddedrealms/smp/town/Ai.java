package net.moddedrealms.smp.town;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The OpenAI connection behind the Herald and the court, with a spending cap it can't go over.
 *
 * <p>The key is read from plugins/ModdedrealmsSMP/openai.key (one line), never from chat or a command, so it can't
 * end up in a log. Every answer reports how many tokens it used; that times the configured prices is added to this
 * month's and today's spend (ai-usage.yml). Before each request the worst case (the whole prompt plus the longest
 * allowed answer) is checked against what's left of both caps; if it doesn't fit, the request isn't sent and the
 * caller falls back to its scripted text. The default prices are set high on purpose, so the real bill comes in
 * under the cap.
 */
final class Ai {
    private static final List<String> PREFERRED = List.of("gpt-6-luna", "gpt-6.1-luna", "gpt-5.6-luna", "gpt-5.5-mini", "gpt-5-nano", "gpt-5-mini",
            "gpt-4.1-nano", "gpt-4.1-mini", "gpt-4o-mini");

    private final TownManager t;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final File keyFile, usageFile;
    private String key = "";
    private String model = "";
    private String lastError = "";
    private double month, day;
    private String monthKey = "", dayKey = "";
    private int calls;
    private boolean busy;

    Ai(TownManager t) {
        this.t = t;
        this.keyFile = new File(t.plugin().getDataFolder(), "openai.key");
        this.usageFile = new File(t.plugin().getDataFolder(), "ai-usage.yml");
        loadUsage();
        reload();
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    /** Read the key file again and pick a model. */
    void reload() {
        key = "";
        try {
            if (keyFile.exists()) key = Files.readString(keyFile.toPath(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            lastError = "can't read openai.key";
        }
        String m = cfg().getString("town.ai.model", "auto").trim();
        model = m.equalsIgnoreCase("auto") ? "" : m;
        if (model.isEmpty() && !key.isEmpty() && enabled()) pickModel();
    }

    boolean enabled() {
        return cfg().getBoolean("town.ai.enabled", true);
    }

    boolean ready() {
        return enabled() && !key.isEmpty() && !model.isEmpty();
    }

    /** Ask the API which models this key can use and take the cheapest we know of. */
    private void pickModel() {
        HttpRequest r = HttpRequest.newBuilder(URI.create(base() + "/models")).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + key).GET().build();
        http.sendAsync(r, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> Bukkit.getScheduler().runTask(t.plugin(), () -> {
            if (err != null || res.statusCode() != 200) {
                lastError = err != null ? "can't reach OpenAI" : "OpenAI said " + res.statusCode() + " (is the key right?)";
                return;
            }
            try {
                JsonArray data = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("data");
                java.util.Set<String> have = new java.util.HashSet<>();
                for (JsonElement e : data) have.add(e.getAsJsonObject().get("id").getAsString());
                for (String p : PREFERRED) {
                    if (have.contains(p)) {
                        model = p;
                        break;
                    }
                }
                if (model.isEmpty()) {
                    for (String h : have) if (h.contains("nano") || h.contains("luna")) model = h;
                }
                if (model.isEmpty()) lastError = "no small model found; set town.ai.model";
                else t.plugin().getLogger().info("AI: using model " + model + ".");
            } catch (RuntimeException ex) {
                lastError = "odd reply to the model list";
            }
        }));
    }

    private String base() {
        return cfg().getString("town.ai.base-url", "https://api.openai.com/v1").replaceAll("/+$", "");
    }

    // ------------------------------------------------------------------ budget

    private void loadUsage() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(usageFile);
        monthKey = y.getString("month", "");
        month = y.getDouble("month-usd");
        dayKey = y.getString("day", "");
        day = y.getDouble("day-usd");
        calls = y.getInt("calls");
    }

    private void saveUsage() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("month", monthKey);
        y.set("month-usd", month);
        y.set("day", dayKey);
        y.set("day-usd", day);
        y.set("calls", calls);
        try {
            y.save(usageFile);
        } catch (IOException ignored) {
        }
    }

    private void roll() {
        LocalDate d = LocalDate.now(ZoneOffset.UTC);
        String m = d.getYear() + "-" + d.getMonthValue(), dd = d.toString();
        if (!m.equals(monthKey)) {
            monthKey = m;
            month = 0;
            calls = 0;
        }
        if (!dd.equals(dayKey)) {
            dayKey = dd;
            day = 0;
        }
    }

    private double monthCap() {
        return cfg().getDouble("town.ai.monthly-budget-usd", 6.0);
    }

    private double dayCap() {
        return cfg().getDouble("town.ai.daily-budget-usd", 0.40);
    }

    private double cost(long in, long out) {
        return in * cfg().getDouble("town.ai.price-input-per-million", 0.50) / 1e6 + out * cfg().getDouble("town.ai.price-output-per-million", 2.00) / 1e6;
    }

    String status() {
        roll();
        return (ready() ? "<green>on</green> (model " + model + ")" : "<red>off</red>" + (key.isEmpty() ? " (no openai.key)" : model.isEmpty() ? " (no model yet)" : ""))
                + "<gray>, spent <white>$" + String.format("%.3f", month) + "</white> of $" + String.format("%.2f", monthCap()) + " this month ($"
                + String.format("%.3f", day) + " of $" + String.format("%.2f", dayCap()) + " today), " + calls + " requests"
                + (lastError.isEmpty() ? "" : ". <red>Last problem: " + lastError);
    }

    // ------------------------------------------------------------------ asking

    /**
     * Ask for a reply; done gets the text, or null (no key, over budget, busy, an error). Runs off the main thread and
     * calls done back on it.
     */
    void ask(String system, String user, int maxOut, Consumer<String> done) {
        roll();
        if (!ready()) {
            done.accept(null);
            return;
        }
        if (busy) {
            done.accept(null);
            return;
        }
        long inEstimate = (system.length() + user.length()) / 3 + 50;
        double worst = cost(inEstimate, maxOut);
        if (month + worst > monthCap() || day + worst > dayCap()) {
            lastError = "budget reached (scripted text until it resets)";
            done.accept(null);
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_completion_tokens", maxOut);
        JsonArray msgs = new JsonArray();
        JsonObject s = new JsonObject();
        s.addProperty("role", "system");
        s.addProperty("content", system);
        msgs.add(s);
        JsonObject u = new JsonObject();
        u.addProperty("role", "user");
        u.addProperty("content", user);
        msgs.add(u);
        body.add("messages", msgs);
        HttpRequest r = HttpRequest.newBuilder(URI.create(base() + "/chat/completions")).timeout(Duration.ofSeconds(40))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        busy = true;
        http.sendAsync(r, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> Bukkit.getScheduler().runTask(t.plugin(), () -> {
            busy = false;
            if (err != null) {
                lastError = "can't reach OpenAI";
                done.accept(null);
                return;
            }
            String text = null;
            try {
                JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
                if (o.has("usage") && o.get("usage").isJsonObject()) {
                    JsonObject us = o.getAsJsonObject("usage");
                    long in = us.has("prompt_tokens") ? us.get("prompt_tokens").getAsLong() : inEstimate;
                    long out = us.has("completion_tokens") ? us.get("completion_tokens").getAsLong() : maxOut;
                    double c = cost(in, out);
                    month += c;
                    day += c;
                    calls++;
                    saveUsage();
                }
                if (res.statusCode() != 200) {
                    lastError = "OpenAI said " + res.statusCode() + (o.has("error") ? ": " + o.getAsJsonObject("error").get("message").getAsString() : "");
                } else {
                    JsonElement content = o.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content");
                    text = content == null || content.isJsonNull() ? null : content.getAsString();
                    if (text != null && text.isBlank()) text = null;
                    lastError = text == null ? "empty answer (raise town.ai.max-answer-tokens?)" : "";
                }
            } catch (RuntimeException ex) {
                lastError = "odd reply from OpenAI";
            }
            done.accept(text);
        }));
    }

    /** The first {...} block of a reply, as JSON, or null. */
    static JsonObject json(String text) {
        if (text == null) return null;
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            return JsonParser.parseString(text.substring(a, b + 1)).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Plain text from a player or the AI, safe to show: no MiniMessage tags, bounded length. */
    static String clean(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("[<>]", "").replaceAll("[\\r\\n\\t]+", " ").trim();
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
