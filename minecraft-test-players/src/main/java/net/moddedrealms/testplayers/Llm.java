package net.moddedrealms.testplayers;

import com.google.gson.Gson;
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
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Talks to the language model that drives a test player:
 *   ollama    - an Ollama this server can reach (ai.ollama-url)
 *   relay     - a staff member's own computer through the website (their connector), set per test player
 *   openai    - any OpenAI-compatible API with a key (OpenAI, OpenRouter, Groq, LM Studio...)
 *   anthropic - Claude, with an Anthropic API key
 *
 * The two paid kinds go through a budget that can't be talked round: a cap on requests per hour and on tokens
 * per day (counted from what the API reports it used, and saved across restarts), a minimum gap between one test
 * player's requests, a short answer length, and only a few test players allowed on the API at once. When a cap
 * is reached the test players carry on with their built-in routines until the hour or day rolls over.
 */
final class Llm {

    /** Why there's no answer this time (the budget said no, or nothing is set up): not an error worth logging. */
    static final class Skipped extends RuntimeException {
        Skipped(String why) {
            super(why, null, false, false);
        }
    }

    private final TestPlayersPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Gson gson = new Gson();
    private final File usageFile;

    // Settings (config.yml "ai")
    private String provider;
    private String defaultModel;
    private String apiKey;
    private String apiBaseUrl;
    private boolean apiEnabled;
    private int maxRequestsPerHour;
    private long maxTokensPerDay;
    private int maxAnswerTokens;
    private int minSecondsPerPlayer;
    private int maxApiPlayers;
    private double pricePerMillionIn;
    private double pricePerMillionOut;
    private double maxDollarsPerDay;
    private String relayStaff;      // provider "pc": think on this staff member's computer through the website
    private volatile boolean ollamaUp; // the last check found Ollama answering

    // Usage
    private final Deque<Long> recentRequests = new ArrayDeque<>();
    private String day = LocalDate.now().toString();
    private long tokensToday;
    private long inputToday;
    private long outputToday;
    private int requestsToday;
    private String warnedAbout;
    private final Set<String> apiPlayers = new HashSet<>();

    Llm(TestPlayersPlugin plugin) {
        this.plugin = plugin;
        this.usageFile = new File(plugin.getDataFolder(), "ai-usage.yml");
        loadUsage();
    }

    void reload() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("ai");
        if (c == null) c = new YamlConfiguration();
        provider = c.getString("provider", "ollama").toLowerCase(Locale.ROOT).trim();
        defaultModel = c.getString("model", "").trim();
        ConfigurationSection a = c.getConfigurationSection("api");
        if (a == null) a = new YamlConfiguration();
        apiKey = a.getString("key", "").trim();
        apiBaseUrl = a.getString("base-url", "https://api.openai.com/v1").trim().replaceAll("/+$", "");
        apiEnabled = a.getBoolean("enabled", true);
        maxRequestsPerHour = Math.max(0, a.getInt("max-requests-per-hour", 120));
        maxTokensPerDay = Math.max(0, a.getLong("max-tokens-per-day", 200_000));
        maxAnswerTokens = Math.max(50, Math.min(1000, a.getInt("max-answer-tokens", 220)));
        minSecondsPerPlayer = Math.max(10, a.getInt("min-seconds-per-player", 60));
        maxApiPlayers = Math.max(0, a.getInt("max-players", 4));
        pricePerMillionIn = Math.max(0, a.getDouble("price-per-million-input-tokens", 0));
        pricePerMillionOut = Math.max(0, a.getDouble("price-per-million-output-tokens", 0));
        maxDollarsPerDay = Math.max(0, a.getDouble("max-dollars-per-day", 0));
        relayStaff = c.getString("pc-staff", "").trim();
        apiPlayers.clear();
        if (provider.equals("ollama")) checkOllama();
    }

    boolean paid() {
        return (provider.equals("openai") || provider.equals("anthropic")) && !localApi();
    }

    /** An OpenAI-style server on this machine or the local network (LM Studio, llama.cpp, vLLM...): free, no key. */
    boolean localApi() {
        if (!provider.equals("openai")) return false;
        try {
            String h = URI.create(apiBaseUrl).getHost();
            return h != null && (h.equals("localhost") || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") || h.matches("^172\\.(1[6-9]|2\\d|3[01])\\..*"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Is an AI linked that can answer? (So new free players get their AI switched on: free.ai-by-default.)
     * A paid API needs its key; Ollama has to have answered the last check; a PC needs a staff name.
     */
    boolean ready() {
        return switch (provider) {
            case "openai", "anthropic" -> localApi() || !apiKey.isEmpty() && apiEnabled && !modelFor(new TestPlayer("x")).isEmpty();
            case "ollama" -> ollamaUp;
            case "pc" -> !relayStaff.isEmpty() && plugin.link().connected();
            default -> false;
        };
    }

    /** Ask Ollama what models it has (in the background). Completes with the names, or fails. */
    CompletableFuture<java.util.List<String>> ollamaModels(String url) {
        HttpRequest r = HttpRequest.newBuilder(URI.create(url.replaceAll("/+$", "") + "/api/tags")).timeout(Duration.ofSeconds(8)).GET().build();
        return http.sendAsync(r, HttpResponse.BodyHandlers.ofString()).thenApply(res -> {
            if (res.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + res.statusCode());
            java.util.List<String> names = new java.util.ArrayList<>();
            JsonArray models = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("models");
            if (models != null) for (JsonElement m : models) names.add(m.getAsJsonObject().get("name").getAsString());
            return names;
        });
    }

    /** Models an OpenAI-style API lists (LM Studio and the like), for checking a local link. */
    CompletableFuture<java.util.List<String>> apiModels() {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiBaseUrl + "/models")).timeout(Duration.ofSeconds(8)).GET();
        if (!apiKey.isEmpty()) b.header("Authorization", "Bearer " + apiKey);
        return http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).thenApply(res -> {
            if (res.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + res.statusCode());
            java.util.List<String> names = new java.util.ArrayList<>();
            JsonArray data = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("data");
            if (data != null) for (JsonElement m : data) names.add(m.getAsJsonObject().get("id").getAsString());
            return names;
        });
    }

    private void checkOllama() {
        ollamaModels(plugin.settings().ollamaUrl()).whenComplete((names, err) -> ollamaUp = err == null);
    }

    String provider() {
        return provider;
    }

    /** The model a test player uses: its own, or the provider's default. */
    String modelFor(TestPlayer tp) {
        if (tp.model != null && !tp.model.isBlank() && !tp.model.equals("default")) return tp.model;
        if (!defaultModel.isEmpty()) return defaultModel;
        return switch (provider) {
            case "anthropic" -> "claude-haiku-5-5";
            case "openai" -> ""; // model names differ per service: set ai.model
            default -> "qwen2.5:3b";
        };
    }

    /** The model's name for status lines ("no model set" when the config needs one). */
    String labelFor(TestPlayer tp) {
        String m = modelFor(tp);
        return m.isEmpty() ? "(no model set: ai.model in config.yml)" : m;
    }

    /** Where a test player's thinking happens, for status lines ("Claude API", "Leebear's PC"...). */
    String placeFor(TestPlayer tp) {
        if (tp.staff != null) return tp.staff + "'s PC";
        return switch (provider) {
            case "anthropic" -> "the Claude API";
            case "openai" -> (localApi() ? "the local AI server at " : "the API at ") + URI.create(apiBaseUrl).getHost();
            case "pc" -> relayStaff.isEmpty() ? "a PC (set ai.pc-staff)" : relayStaff + "'s PC";
            case "none" -> "nothing (no AI linked)";
            default -> "Ollama at " + plugin.settings().ollamaUrl();
        };
    }

    /**
     * Ask the model; the answer is the text it wrote (we ask for JSON). Fails with Skipped when the budget or
     * the setup says no: the caller just carries on without an answer.
     */
    CompletableFuture<String> chat(TestPlayer tp, String system, String user, JsonObject schema) {
        String model = modelFor(tp);
        long timeoutMs = plugin.settings().aiTimeoutSeconds() * 1000L;
        String staff = tp.staff != null ? tp.staff : provider.equals("pc") && !relayStaff.isEmpty() ? relayStaff : null;
        if (staff != null) return ollamaStyle(plugin.link().relay(staff, ollamaRequest(model, system, user, schema), timeoutMs));
        if (provider.equals("none") || provider.equals("pc")) return CompletableFuture.failedFuture(new Skipped("no AI linked (/testplayer ai link)"));
        if (provider.equals("ollama")) return ollamaStyle(post(plugin.settings().ollamaUrl() + "/api/chat", ollamaRequest(model, system, user, schema), timeoutMs, null));
        if (model.isEmpty()) return CompletableFuture.failedFuture(new Skipped("set ai.model in config.yml (the model name your API uses)"));
        String no = budgetSaysNo(tp);
        if (no != null) return CompletableFuture.failedFuture(new Skipped(no));
        synchronized (this) {
            tp.lastApiAt = System.currentTimeMillis();
            recentRequests.addLast(tp.lastApiAt);
            requestsToday++;
        }
        if (provider.equals("anthropic")) {
            JsonObject req = new JsonObject();
            req.addProperty("model", model);
            req.addProperty("max_tokens", maxAnswerTokens);
            req.addProperty("system", system + "\nReply with ONLY a JSON object with these fields: " + fields(schema));
            JsonArray messages = new JsonArray();
            messages.add(message("user", user));
            req.add("messages", messages);
            req.addProperty("temperature", 0.7);
            return post("https://api.anthropic.com/v1/messages", req, timeoutMs, "anthropic").thenApply(body -> {
                JsonObject usage = body.getAsJsonObject("usage");
                if (usage != null) count(usage.get("input_tokens"), usage.get("output_tokens"));
                JsonArray content = body.getAsJsonArray("content");
                return content == null || content.isEmpty() ? "" : content.get(0).getAsJsonObject().get("text").getAsString();
            });
        }
        JsonObject req = new JsonObject();
        req.addProperty("model", model);
        req.addProperty("max_tokens", maxAnswerTokens);
        req.addProperty("temperature", 0.7);
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_object");
        req.add("response_format", format);
        JsonArray messages = new JsonArray();
        messages.add(message("system", system + "\nReply with ONLY a JSON object with these fields: " + fields(schema)));
        messages.add(message("user", user));
        req.add("messages", messages);
        return post(apiBaseUrl + "/chat/completions", req, timeoutMs, "openai").thenApply(body -> {
            JsonObject usage = body.getAsJsonObject("usage");
            if (usage != null) count(usage.get("prompt_tokens"), usage.get("completion_tokens"));
            JsonArray choices = body.getAsJsonArray("choices");
            return choices == null || choices.isEmpty() ? "" : choices.get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
        });
    }

    /** null if this request may go out, else why not. Runs on the main thread. */
    private synchronized String budgetSaysNo(TestPlayer tp) {
        rollDay();
        if (!apiEnabled) return "the AI API is paused (/testplayer api on)";
        if (localApi()) return null; // a model on your own machine: nothing to pay for
        if (apiKey.isEmpty()) return "no API key set (/testplayer ai link)";
        long now = System.currentTimeMillis();
        while (!recentRequests.isEmpty() && now - recentRequests.peekFirst() > 3_600_000) recentRequests.removeFirst();
        if (!tp.id.startsWith("~") && !apiPlayers.contains(tp.id)) { // "~check" (/testplayer ai test) doesn't take a place
            if (apiPlayers.size() >= maxApiPlayers) return "only " + maxApiPlayers + " test players may use the API at once (ai.api.max-players)";
            apiPlayers.add(tp.id);
        }
        if (now - tp.lastApiAt < minSecondsPerPlayer * 1000L) return "waiting between API requests";
        if (recentRequests.size() >= maxRequestsPerHour) return warn("hour", "the AI API reached " + maxRequestsPerHour + " requests this hour");
        if (tokensToday >= maxTokensPerDay) return warn("tokens", "the AI API used its " + maxTokensPerDay + " tokens for today");
        if (maxDollarsPerDay > 0 && dollarsToday() >= maxDollarsPerDay) return warn("dollars", "the AI API reached its $" + maxDollarsPerDay + " for today");
        return null;
    }

    private String warn(String what, String text) {
        String k = day + what + (what.equals("hour") ? String.valueOf(System.currentTimeMillis() / 3_600_000) : "");
        if (!k.equals(warnedAbout)) {
            warnedAbout = k;
            plugin.getLogger().warning(text + "; test players carry on without it until it resets.");
        }
        return text;
    }

    /** A test player stopped using AI (or left): let another one have its API place. */
    void release(TestPlayer tp) {
        apiPlayers.remove(tp.id);
    }

    private synchronized void count(JsonElement in, JsonElement out) {
        long i = in != null && in.isJsonPrimitive() ? in.getAsLong() : 0;
        long o = out != null && out.isJsonPrimitive() ? out.getAsLong() : 0;
        rollDay();
        inputToday += i;
        outputToday += o;
        tokensToday += i + o;
        saveUsage();
    }

    private double dollarsToday() {
        return inputToday / 1e6 * pricePerMillionIn + outputToday / 1e6 * pricePerMillionOut;
    }

    private synchronized void rollDay() {
        String today = LocalDate.now().toString();
        if (today.equals(day)) return;
        day = today;
        tokensToday = inputToday = outputToday = 0;
        requestsToday = 0;
        saveUsage();
    }

    /** "1,234 of 200,000 tokens today, 3 of 120 requests this hour" (never the key). */
    synchronized String usage() {
        rollDay();
        long now = System.currentTimeMillis();
        while (!recentRequests.isEmpty() && now - recentRequests.peekFirst() > 3_600_000) recentRequests.removeFirst();
        if (!paid()) return switch (provider) {
            case "ollama" -> "Ollama (free) at " + plugin.settings().ollamaUrl() + (ollamaUp ? "" : " - not answering");
            case "openai" -> "local AI server (free) at " + apiBaseUrl;
            case "pc" -> relayStaff.isEmpty() ? "a staff PC: none set" : relayStaff + "'s PC through the website (free)" + (plugin.link().connected() ? "" : " - website not connected");
            default -> "no AI linked (/testplayer ai link)";
        };
        String s = (apiEnabled ? "" : "PAUSED, ") + (apiKey.isEmpty() ? "no key set, " : "key set, ") + provider + " " + (defaultModel.isEmpty() ? "" : defaultModel + ", ")
                + String.format(Locale.ROOT, "%,d of %,d tokens today, %d of %d requests this hour, %d of %d test players on it",
                        tokensToday, maxTokensPerDay, recentRequests.size(), maxRequestsPerHour, apiPlayers.size(), maxApiPlayers);
        if (pricePerMillionIn > 0 || pricePerMillionOut > 0) s += String.format(Locale.ROOT, ", about $%.3f today", dollarsToday()) + (maxDollarsPerDay > 0 ? " of $" + maxDollarsPerDay : "");
        return s;
    }

    void setApiEnabled(boolean on) {
        apiEnabled = on;
        plugin.getConfig().set("ai.api.enabled", on);
        plugin.saveConfig();
    }

    // ------------------------------------------------------------ requests

    private JsonObject ollamaRequest(String model, String system, String user, JsonObject schema) {
        JsonObject request = new JsonObject();
        request.addProperty("model", model);
        request.addProperty("stream", false);
        request.add("format", schema);
        request.addProperty("keep_alive", "30m");
        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.7);
        options.addProperty("num_ctx", 4096);
        options.addProperty("num_predict", 220);
        request.add("options", options);
        JsonArray messages = new JsonArray();
        messages.add(message("system", system));
        messages.add(message("user", user));
        request.add("messages", messages);
        return request;
    }

    private static CompletableFuture<String> ollamaStyle(CompletableFuture<JsonObject> answer) {
        return answer.thenApply(body -> body.getAsJsonObject("message").get("content").getAsString());
    }

    private CompletableFuture<JsonObject> post(String url, JsonObject body, long timeoutMs, String auth) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeoutMs)).header("Content-Type", "application/json");
        if ("openai".equals(auth) && !apiKey.isEmpty()) b.header("Authorization", "Bearer " + apiKey);
        if ("anthropic".equals(auth)) b.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
        return http.sendAsync(b.POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build(), HttpResponse.BodyHandlers.ofString()).thenApply(res -> {
            if (res.statusCode() / 100 != 2) {
                // The body can echo what was sent; keep it short and never include the key.
                String text = res.body().replace(apiKey.isEmpty() ? "\u0000" : apiKey, "***");
                throw new IllegalStateException("HTTP " + res.statusCode() + ": " + text.substring(0, Math.min(200, text.length())));
            }
            return JsonParser.parseString(res.body()).getAsJsonObject();
        });
    }

    private static JsonObject message(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    /** "thought (text), say (text), action (one of: wander, goto...)" from a JSON schema, for APIs without schemas. */
    private static String fields(JsonObject schema) {
        StringBuilder sb = new StringBuilder();
        for (var e : schema.getAsJsonObject("properties").entrySet()) {
            JsonObject p = e.getValue().getAsJsonObject();
            if (sb.length() > 0) sb.append(", ");
            sb.append('"').append(e.getKey()).append('"');
            if (p.has("enum")) {
                sb.append(" (one of: ");
                JsonArray en = p.getAsJsonArray("enum");
                for (int i = 0; i < en.size(); i++) sb.append(i > 0 ? ", " : "").append(en.get(i).getAsString());
                sb.append(')');
            } else sb.append(" (").append(p.get("type").getAsString()).append(')');
        }
        return sb.toString();
    }

    /** The JSON object in a model's answer (APIs sometimes wrap it in text or a code block). */
    static JsonObject parse(String answer) {
        int a = answer.indexOf('{');
        int b = answer.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalArgumentException("no JSON in the answer");
        return JsonParser.parseString(answer.substring(a, b + 1)).getAsJsonObject();
    }

    // ------------------------------------------------------------ ai-usage.yml

    private void loadUsage() {
        if (!usageFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(usageFile);
        if (!LocalDate.now().toString().equals(y.getString("day"))) return;
        day = y.getString("day");
        tokensToday = y.getLong("tokens");
        inputToday = y.getLong("input");
        outputToday = y.getLong("output");
        requestsToday = y.getInt("requests");
    }

    private void saveUsage() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("day", day);
        y.set("tokens", tokensToday);
        y.set("input", inputToday);
        y.set("output", outputToday);
        y.set("requests", requestsToday);
        try {
            y.save(usageFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save ai-usage.yml", e);
        }
    }
}
