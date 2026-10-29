package net.moddedrealms.testplayers;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;

/**
 * The AI for test players that have a model: every few seconds (sooner when someone talks to it) it describes
 * what the test player sees to the model and does what the model picks. The model runs on an Ollama this
 * server can reach, or on a staff member's own computer through the website (their connector).
 */
final class Brain {

    private static final String SYSTEM = """
            You control a simulated test player on a Minecraft test server. Staff use test players to see how the
            server copes with players moving around, so keep moving most of the time, and be friendly and brief when
            someone talks to you. You can't break or place blocks, fight, or use items.
            Pick one action:
            - wander: walk around randomly within "radius" blocks of where you are
            - goto: walk to "x", "z" (and "y" if you know it)
            - follow: follow the online player named "player"
            - walk: walk straight on in "direction" (north, south, east, west, north-east...) to explore new chunks
            - idle: stand still
            "say" is what you say in chat: when someone talked to you, answer them there (one short, friendly
            sentence); otherwise leave it "". Only use player names you can see in the context. Reply with ONLY JSON.""";

    private static final JsonObject SCHEMA = JsonParser.parseString("""
            {"type": "object",
             "properties": {
               "thought": {"type": "string", "maxLength": 200},
               "say": {"type": "string", "maxLength": 200},
               "action": {"type": "string", "enum": ["wander", "goto", "follow", "walk", "idle"]},
               "x": {"type": "number"}, "y": {"type": "number"}, "z": {"type": "number"},
               "player": {"type": "string", "maxLength": 16},
               "direction": {"type": "string", "maxLength": 16},
               "radius": {"type": "number"}
             },
             "required": ["thought", "say", "action"]}""").getAsJsonObject();

    private final TestPlayersPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Gson gson = new Gson();
    private int inFlight;

    Brain(TestPlayersPlugin plugin) {
        this.plugin = plugin;
    }

    int thinking() {
        return inFlight;
    }

    /** Called every second for each test player in the world. */
    void maybeThink(TestPlayer tp) {
        Settings s = plugin.settings();
        if (!tp.ai || tp.model == null || tp.thinking || inFlight >= s.aiMaxConcurrent()) return;
        if (System.currentTimeMillis() < tp.nextThinkAt) return;
        Mannequin m = tp.entity();
        if (m == null) return;

        JsonObject request = new JsonObject();
        request.addProperty("model", tp.model);
        request.addProperty("stream", false);
        request.add("format", SCHEMA);
        request.addProperty("keep_alive", "30m");
        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.6);
        options.addProperty("num_ctx", 4096);
        options.addProperty("num_predict", 200);
        request.add("options", options);
        JsonArray messages = new JsonArray();
        messages.add(message("system", SYSTEM));
        messages.add(message("user", observe(tp, m)));
        request.add("messages", messages);

        tp.thinking = true;
        inFlight++;
        long timeoutMs = s.aiTimeoutSeconds() * 1000L;
        CompletableFuture<JsonObject> answer = tp.staff != null ? plugin.link().relay(tp.staff, request, timeoutMs) : ollama(s.ollamaUrl(), request, timeoutMs);
        answer.whenComplete((body, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            inFlight--;
            tp.thinking = false;
            tp.nextThinkAt = System.currentTimeMillis() + s.aiIntervalSeconds() * 1000L;
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                tp.thought = "AI unavailable: " + cause.getMessage();
                return;
            }
            try {
                JsonObject d = JsonParser.parseString(body.getAsJsonObject("message").get("content").getAsString()).getAsJsonObject();
                act(tp, d);
            } catch (RuntimeException e) {
                tp.thought = "AI gave an answer I couldn't use";
            }
        }));
    }

    /** Someone talked to the test player: answer soon instead of at the next turn. */
    void heard(TestPlayer tp, String from, String text) {
        tp.inbox.addLast(new String[] {from, text});
        while (tp.inbox.size() > 6) tp.inbox.removeFirst();
        tp.nextThinkAt = Math.min(tp.nextThinkAt, System.currentTimeMillis() + 1000);
    }

    private CompletableFuture<JsonObject> ollama(String url, JsonObject request, long timeoutMs) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/api/chat"))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(request)))
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(res -> {
            if (res.statusCode() != 200) throw new IllegalStateException("Ollama answered HTTP " + res.statusCode() + ": " + res.body().substring(0, Math.min(200, res.body().length())));
            return JsonParser.parseString(res.body()).getAsJsonObject();
        });
    }

    private static JsonObject message(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    /** What the test player knows right now, as text for the model. */
    private String observe(TestPlayer tp, Mannequin m) {
        Location l = m.getLocation();
        List<String> lines = new ArrayList<>();
        if (tp.goal != null) lines.add("YOUR GOAL (set by staff, never forget it): " + tp.goal);
        lines.add("You are " + tp.name + " at x=" + l.getBlockX() + " y=" + l.getBlockY() + " z=" + l.getBlockZ() + " in " + l.getWorld().getName()
                + ", " + (l.getWorld().isDayTime() ? "day" : "night") + ". You are " + tp.doing() + ".");
        if (!tp.lastResult.isEmpty()) lines.add("Last result: " + tp.lastResult);
        lines.add("Ground height 16 blocks away: " + terrain(l));
        List<String> players = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (Entity e : m.getNearbyEntities(32, 16, 32)) {
            int d = (int) Math.round(e.getLocation().distance(l));
            if (e instanceof Player p) players.add(p.getName() + " (" + d + "m)");
            else if (e instanceof Mannequin other) {
                TestPlayer t = plugin.players().of(other);
                if (t != null) others.add(t.name + " (test player, " + d + "m)");
            } else if (e instanceof LivingEntity && others.size() < 12) others.add(e.getType().name().toLowerCase(Locale.ROOT) + " (" + d + "m)");
        }
        lines.add("Players near you: " + (players.isEmpty() ? "none" : String.join(", ", players)));
        if (!others.isEmpty()) lines.add("Also near you: " + String.join(", ", others));
        List<String> online = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (online.size() < 20) online.add(p.getName());
        lines.add("Online players: " + (online.isEmpty() ? "none" : String.join(", ", online)));
        if (tp.goal != null) lines.add("Remember your goal: " + tp.goal);
        // Chat goes last, quoted, with who to answer: small models weigh the end of the prompt most, and
        // with the chat further up they kept writing their reply into "thought" and leaving "say" empty.
        tp.answering = !tp.inbox.isEmpty();
        if (tp.inbox.isEmpty()) lines.add("Choose the next action.");
        else {
            String last = tp.inbox.getLast()[0];
            for (String[] msg : tp.inbox) lines.add(msg[0] + " just said to you: \"" + msg[1].replace('"', '\'') + "\"");
            lines.add("Reply to " + last + " in \"say\", then choose the next action.");
            tp.inbox.clear();
        }
        return String.join("\n", lines);
    }

    /** The ground level 16 blocks away in each direction (only from chunks already loaded), so it can find hills, valleys and water. */
    private static String terrain(Location l) {
        List<String> out = new ArrayList<>();
        String[] names = {"north", "east", "south", "west"};
        int[][] dirs = {{0, -16}, {16, 0}, {0, 16}, {-16, 0}};
        for (int i = 0; i < 4; i++) {
            int x = l.getBlockX() + dirs[i][0];
            int z = l.getBlockZ() + dirs[i][1];
            if (!l.getWorld().isChunkLoaded(x >> 4, z >> 4)) continue;
            org.bukkit.block.Block top = l.getWorld().getHighestBlockAt(x, z);
            out.add(names[i] + " y=" + top.getY() + (top.isLiquid() ? " (water)" : ""));
        }
        return out.isEmpty() ? "unknown" : String.join(", ", out) + " (you are at y=" + l.getBlockY() + ")";
    }

    /** Do what the model picked. Bad picks just leave the test player doing what it was doing. */
    private void act(TestPlayer tp, JsonObject d) {
        TestPlayers players = plugin.players();
        tp.thought = str(d, "thought");
        String action = str(d, "action");
        Mannequin m = tp.entity();
        if (m == null) return;
        try {
            switch (action) {
                case "wander" -> players.wander(tp, num(d, "radius", 24));
                case "goto" -> {
                    Location l = m.getLocation();
                    double y = d.has("y") ? num(d, "y", l.getY()) : l.getY();
                    players.goTo(tp, new Location(l.getWorld(), num(d, "x", l.getX()), y, num(d, "z", l.getZ())));
                }
                case "follow" -> players.follow(tp, str(d, "player"));
                case "walk" -> players.walk(tp, TestPlayers.parseDirection(str(d, "direction").isEmpty() ? "north" : str(d, "direction")));
                case "idle" -> players.idle(tp);
                default -> { }
            }
        } catch (IllegalArgumentException e) {
            tp.lastResult = action + " failed: " + e.getMessage();
        }
        // Answers at most every 5 s; anything said unprompted at most once a minute, so a chatty model
        // (or 50 of them) can't flood the chat.
        String say = str(d, "say");
        long gap = tp.answering ? 5_000 : 60_000;
        if (!say.isEmpty() && System.currentTimeMillis() - tp.lastSaidAt > gap) players.say(tp, say);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString().trim() : "";
    }

    private static double num(JsonObject o, String k, double fallback) {
        try {
            return o.has(k) ? o.get(k).getAsDouble() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
