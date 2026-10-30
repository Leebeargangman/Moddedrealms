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
            You play a simulated player (a "test player") on a Minecraft server, living in a small community with
            the other test players. Stay in character: your personality shapes what you choose and how you talk.
            Keep busy most of the time, and be brief in chat. You can't break blocks, fight or use items.
            Pick one action:
            - wander: walk around randomly within "radius" blocks of where you are
            - goto: walk to "x", "z" (and "y" if you know it)
            - follow: follow the player or test player named "player"
            - walk: walk straight on in "direction" (north, south, east, west, north-east...) to explore new chunks
            - idle: stand still
            - build: build a "structure" (hut, house, tower, wall or campfire) out of "material" (wood, spruce,
              stone, brick or cherry) next to you; only inside a build zone, and it takes a minute or two
            "say" is what you say in chat: when someone talked to you, answer them there (one short, friendly
            sentence); otherwise leave it "". Only use player names you can see in the context. Reply with ONLY JSON.""";

    private static final JsonObject SCHEMA = JsonParser.parseString("""
            {"type": "object",
             "properties": {
               "thought": {"type": "string", "maxLength": 200},
               "say": {"type": "string", "maxLength": 200},
               "action": {"type": "string", "enum": ["wander", "goto", "follow", "walk", "idle", "build"]},
               "structure": {"type": "string", "enum": ["hut", "house", "tower", "wall", "campfire"]},
               "material": {"type": "string", "enum": ["wood", "spruce", "stone", "brick", "cherry"]},
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
        // Busy building (or walking to build): let it finish, unless someone talks to it.
        if (busy(tp) && tp.inbox.isEmpty()) return;
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

    /**
     * Another test player said something nearby. They can hold a conversation, but each one answers another
     * test player at most every 30 seconds, so two models can't chat back and forth nonstop.
     */
    void heardTestPlayer(TestPlayer tp, String from, String text) {
        long now = System.currentTimeMillis();
        if (now - tp.lastBotHeardAt < 30_000) return;
        tp.lastBotHeardAt = now;
        heard(tp, from + " (test player)", text);
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
        if (tp.personality != null) lines.add("YOUR PERSONALITY: " + tp.personality);
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
        Builder builder = plugin.builder();
        String zone = builder.zoneHint(l);
        lines.add("Building: " + (zone == null ? "not allowed in this world" : zone)
                + (builder.buildCount(tp) > 0 ? "; you have built " + builder.buildCount(tp) + " thing(s) so far" : ""));
        List<String> builds = builder.near(l, 64);
        if (!builds.isEmpty()) lines.add("Builds near you: " + String.join(", ", builds));
        if (tp.goal != null) lines.add("Remember your goal: " + tp.goal + " (talking about it doesn't get it done: your actions do)");
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
            // While building it only answers chat: a new action would leave the build half done.
            if (!busy(tp)) switch (action) {
                case "wander" -> players.wander(tp, num(d, "radius", 24));
                case "goto" -> {
                    Location l = m.getLocation();
                    double y = d.has("y") ? num(d, "y", l.getY()) : l.getY();
                    players.goTo(tp, new Location(l.getWorld(), num(d, "x", l.getX()), y, num(d, "z", l.getZ())));
                }
                case "follow" -> players.follow(tp, str(d, "player").isEmpty() ? nearest(tp, m) : str(d, "player"));
                case "walk" -> players.walk(tp, TestPlayers.parseDirection(str(d, "direction").isEmpty() ? "north" : str(d, "direction")));
                case "idle" -> players.idle(tp);
                case "build" -> {
                    // A pause between builds, so AI test players don't cover the zone in huts.
                    long wait = plugin.getConfig().getLong("building.cooldown-seconds", 120) * 1000 - (System.currentTimeMillis() - tp.lastBuiltAt);
                    if (wait > 0) throw new IllegalArgumentException("you just finished a build; you can start another in " + (wait / 1000 + 1) + " seconds");
                    // Small models often leave out what to build: pick something (mostly huts) rather than fail.
                    String structure = str(d, "structure");
                    if (!Builder.STRUCTURES.contains(structure)) {
                        String[] picks = {"hut", "hut", "hut", "house", "tower", "campfire", "wall"};
                        structure = picks[java.util.concurrent.ThreadLocalRandom.current().nextInt(picks.length)];
                    }
                    String material = str(d, "material");
                    if (!Builder.MATERIALS.contains(material)) material = Builder.MATERIALS.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(Builder.MATERIALS.size()));
                    tp.lastResult = plugin.builder().startOrApproach(tp, structure, material);
                }
                default -> { }
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            tp.lastResult = action + " failed: " + e.getMessage();
        }
        // Answers at most every 5 s; anything said unprompted at most once a minute, so a chatty model
        // (or 50 of them) can't flood the chat.
        String say = str(d, "say");
        long gap = tp.answering ? 5_000 : 60_000;
        if (!say.isEmpty() && System.currentTimeMillis() - tp.lastSaidAt > gap) players.say(tp, say);
    }

    private static boolean busy(TestPlayer tp) {
        return tp.mode == TestPlayer.Mode.BUILD || tp.pendingBuild != null;
    }

    /** The nearest player or other test player within 32 blocks (for "follow" without a name), or "". */
    private String nearest(TestPlayer tp, Mannequin m) {
        String best = "";
        double bestD = 32 * 32;
        for (Entity e : m.getNearbyEntities(32, 16, 32)) {
            String name = e instanceof Player p ? p.getName() : e instanceof Mannequin o && plugin.players().of(o) != null ? plugin.players().of(o).name : null;
            double d = e.getLocation().distanceSquared(m.getLocation());
            if (name != null && d < bestD) {
                bestD = d;
                best = name;
            }
        }
        if (best.isEmpty()) throw new IllegalArgumentException("nobody is near enough to follow");
        return best;
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
