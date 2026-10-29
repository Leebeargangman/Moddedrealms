package net.moddedrealms.testplayers;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Shows this server's test players on the website's Bots page and takes staff commands from there, speaking
 * the same protocol as the AI bot hosts (ai-bot/manager/src/link.js): long-poll /api/botlink/poll for
 * commands, post events and a snapshot to /api/botlink/events. The website treats this server as one more
 * bot host. Also relays model requests to a staff member's own computer (/api/botlink/llm).
 */
final class WebsiteLink {

    private static final Pattern HOST = Pattern.compile("^[\\w.-]{1,32}$");
    private static final Pattern COME = Pattern.compile("^come(?:\\s+(-?\\d+)[ ,]+(-?\\d+)(?:[ ,]+(-?\\d+))?)?$");

    private final TestPlayersPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Gson gson = new Gson();
    private final ConcurrentLinkedQueue<JsonObject> events = new ConcurrentLinkedQueue<>();
    private volatile boolean running;
    private volatile String status = "off";
    private BukkitTask sendTask;
    private BukkitTask modelsTask;
    private volatile JsonObject ollama = new JsonObject();
    private int generation; // bumps on restart so an old poll loop stops

    WebsiteLink(TestPlayersPlugin plugin) {
        this.plugin = plugin;
    }

    String status() {
        return status;
    }

    void start() {
        stop();
        Settings s = plugin.settings();
        if (!s.websiteEnabled()) {
            status = "off (website.enabled is false)";
            return;
        }
        if (s.managerKey().isEmpty() || !HOST.matcher(s.hostName()).matches() || !s.websiteUrl().startsWith("http")) {
            status = "not set up: check website.url, website.manager-key and website.host-name";
            plugin.getLogger().warning("Website link " + status);
            return;
        }
        running = true;
        status = "connecting";
        int gen = ++generation;
        poll(gen);
        sendTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flush, 20, 60);
        modelsTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refreshModels, 0, 600);
    }

    void stop() {
        running = false;
        generation++;
        if (sendTask != null) sendTask.cancel();
        if (modelsTask != null) modelsTask.cancel();
        sendTask = modelsTask = null;
    }

    private HttpRequest.Builder request(String path) {
        Settings s = plugin.settings();
        return HttpRequest.newBuilder(URI.create(s.websiteUrl() + path))
                .header("Authorization", "Bearer " + s.managerKey())
                .header("X-Manager-Name", s.hostName())
                .header("Content-Type", "application/json");
    }

    // ------------------------------------------------------------ commands from the Bots page

    private void poll(int gen) {
        if (!running || gen != generation) return;
        HttpRequest req = request("/api/botlink/poll").timeout(Duration.ofSeconds(40)).GET().build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
            if (!running || gen != generation) return;
            long retryTicks = 0;
            if (err != null) {
                status = "can't reach the website (" + err.getClass().getSimpleName() + ")";
                retryTicks = 100;
            } else if (res.statusCode() == 401) {
                status = "the website refused website.manager-key (it must equal BOT_MANAGER_KEY)";
                retryTicks = 600;
            } else if (res.statusCode() != 200) {
                status = "the website answered HTTP " + res.statusCode();
                retryTicks = 100;
            } else {
                status = "connected as \"" + plugin.settings().hostName() + "\"";
                JsonArray items = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("items");
                if (items != null && !items.isEmpty()) Bukkit.getScheduler().runTask(plugin, () -> items.forEach(i -> handle(i.getAsJsonObject())));
            }
            if (retryTicks == 0) poll(gen);
            else Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> poll(gen), retryTicks);
        });
    }

    private void handle(JsonObject item) {
        String id = str(item, "id");
        String command = str(item, "command");
        JsonObject args = item.has("args") && item.get("args").isJsonObject() ? item.getAsJsonObject("args") : new JsonObject();
        JsonObject staff = item.has("staff") && item.get("staff").isJsonObject() ? item.getAsJsonObject("staff") : new JsonObject();
        String who = str(staff, "name");
        JsonObject reply = new JsonObject();
        reply.addProperty("type", "result");
        reply.addProperty("id", id);
        reply.addProperty("command", command);
        reply.addProperty("bot", args.has("bot") ? str(args, "bot") : null);
        reply.addProperty("who", who);
        try {
            JsonElement result = run(command, args, staff);
            reply.addProperty("ok", true);
            reply.add("result", result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            reply.addProperty("ok", false);
            reply.addProperty("error", e.getMessage());
        }
        events.add(reply);
        plugin.getLogger().info("[website] " + who + ": " + command + (args.has("bot") ? " " + str(args, "bot") : ""));
    }

    private JsonElement run(String command, JsonObject args, JsonObject staff) {
        TestPlayers players = plugin.players();
        String who = str(staff, "name");
        switch (command) {
            case "createBot" -> {
                String name = str(args, "name");
                if (name.isEmpty()) name = players.batchNames("Test", 2).getFirst(); // the first free TestN
                TestPlayer tp = players.create(name);
                applyAi(tp, args.has("ai") && args.get("ai").isJsonObject() ? args.getAsJsonObject("ai") : new JsonObject(), who);
                if (args.has("goal") && !str(args, "goal").isEmpty()) tp.goal = cleanGoal(str(args, "goal"));
                players.spawn(tp, spawnPoint());
                JsonObject o = new JsonObject();
                o.addProperty("id", tp.id);
                o.addProperty("name", tp.name);
                return o;
            }
            case "startBot", "rejoin" -> {
                TestPlayer tp = bot(args);
                if (command.equals("rejoin")) players.despawn(tp, false);
                Location at = tp.lastKnown != null && tp.lastKnown.isWorldLoaded() ? tp.lastKnown : spawnPoint();
                players.spawn(tp, at);
                return text("starting");
            }
            case "stopBot" -> {
                players.despawn(bot(args), false);
                return text("stopped");
            }
            case "removeBot" -> {
                players.despawn(bot(args), true);
                return text("removed");
            }
            case "updateBot" -> {
                TestPlayer tp = bot(args);
                JsonObject changes = args.has("changes") && args.get("changes").isJsonObject() ? args.getAsJsonObject("changes") : new JsonObject();
                if (changes.has("ai") && changes.get("ai").isJsonObject()) applyAi(tp, changes.getAsJsonObject("ai"), who);
                if (changes.has("goal")) tp.goal = changes.get("goal").isJsonNull() || str(changes, "goal").isEmpty() ? null : cleanGoal(str(changes, "goal"));
                players.relabel(tp);
                tp.nextThinkAt = 0;
                return text("saved");
            }
            case "message" -> {
                TestPlayer tp = bot(args);
                String reply = message(tp, str(args, "text"), staff);
                if (reply != null) reply(tp, who, reply);
                return text("sent");
            }
            case "order" -> {
                TestPlayer tp = bot(args);
                JsonObject a = args.has("args") && args.get("args").isJsonObject() ? args.getAsJsonObject("args") : new JsonObject();
                reply(tp, who, order(tp, str(args, "action"), a));
                return text("ordered");
            }
            default -> throw new IllegalArgumentException("Test players can't do \"" + command + "\" (they have no Minecraft account or inventory).");
        }
    }

    /** The "AI" settings from the Bots page: off, a model on this server's Ollama, or a staff member's computer. */
    private void applyAi(TestPlayer tp, JsonObject ai, String who) {
        boolean enabled = !ai.has("enabled") || ai.get("enabled").getAsBoolean();
        String model = str(ai, "model");
        if (!enabled || model.isEmpty()) {
            tp.ai = false;
            if (!enabled) tp.model = null;
            return;
        }
        if (!model.matches("^[\\w.:/-]{1,64}$")) throw new IllegalArgumentException("That isn't a model name.");
        tp.model = model;
        tp.staff = "connector".equals(str(ai, "source")) ? (str(ai, "connector").isEmpty() ? who : str(ai, "connector")) : null;
        tp.ai = true;
        tp.nextThinkAt = 0;
    }

    /** Staff typed in the bot's chat box, or pressed Stop task / Play on its own / Status / Come to me. */
    private String message(TestPlayer tp, String text, JsonObject staff) {
        TestPlayers players = plugin.players();
        String t = text.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        Mannequin m = tp.entity();
        if (lower.equals("status")) return status(tp);
        if (m == null) return tp.name + " isn't in the world. Press Start first.";
        if (lower.equals("stop")) {
            tp.ai = false;
            players.idle(tp);
            return "Stopped. I'll stand here until told otherwise.";
        }
        if (lower.equals("auto")) {
            if (tp.model != null) {
                tp.ai = true;
                tp.nextThinkAt = 0;
                players.relabel(tp);
                return "Playing on my own with " + tp.model + ".";
            }
            players.wander(tp, 32);
            return "I have no AI model, so I'll wander around here.";
        }
        Matcher come = COME.matcher(lower);
        if (come.matches()) {
            if (come.group(1) != null) {
                Location l = m.getLocation();
                double x = Double.parseDouble(come.group(1));
                double y = come.group(3) != null ? Double.parseDouble(come.group(2)) : l.getY();
                double z = Double.parseDouble(come.group(3) != null ? come.group(3) : come.group(2));
                players.goTo(tp, new Location(l.getWorld(), x + 0.5, y, z + 0.5));
                return "On my way to " + (long) x + " " + (long) z + ".";
            }
            Player p = str(staff, "minecraft").isEmpty() ? null : Bukkit.getPlayerExact(str(staff, "minecraft"));
            if (p == null) return "I can't see you. Send your coordinates (x z) instead.";
            players.follow(tp, p.getName());
            return "Coming to you, " + p.getName() + ".";
        }
        if (lower.startsWith("say ")) {
            players.say(tp, t.substring(4));
            return null;
        }
        if (tp.ai && tp.model != null) {
            plugin.brain().heard(tp, str(staff, "name") + " (staff, on the website)", t);
            return null; // the model answers in chat
        }
        String[] w = lower.split("\\s+");
        try {
            switch (w[0]) {
                case "follow" -> {
                    players.follow(tp, w.length > 1 ? w[1] : str(staff, "minecraft"));
                    return "Following " + tp.follow + ".";
                }
                case "wander" -> {
                    players.wander(tp, w.length > 1 ? Double.parseDouble(w[1]) : 24);
                    return "Wandering around here.";
                }
                case "walk" -> {
                    players.walk(tp, TestPlayers.parseDirection(w.length > 1 ? w[1] : "north"));
                    return "Walking " + TestPlayer.compass(tp.walkYaw) + ".";
                }
                default -> { }
            }
        } catch (NumberFormatException e) {
            return "That needs a number.";
        }
        return "I have no AI model, so I only understand: status, stop, auto, come [x z], follow <player>, wander [radius], walk <direction>, say <text>.";
    }

    /** Orders from the bot detail view and team tools, named like the AI bots' skills. */
    private String order(TestPlayer tp, String action, JsonObject a) {
        TestPlayers players = plugin.players();
        Mannequin m = tp.entity();
        if (m == null) throw new IllegalStateException(tp.name + " isn't in the world.");
        Location l = m.getLocation();
        switch (action) {
            case "goto", "come_to" -> {
                if (a.has("name") && !a.has("x")) {
                    players.follow(tp, str(a, "name"));
                    return "Coming to " + str(a, "name") + ".";
                }
                double y = a.has("y") && !a.get("y").isJsonNull() ? a.get("y").getAsDouble() : l.getY();
                players.goTo(tp, new Location(l.getWorld(), a.get("x").getAsDouble(), y, a.get("z").getAsDouble()));
                return "Going to " + a.get("x").getAsLong() + " " + a.get("z").getAsLong() + ".";
            }
            case "follow_player" -> {
                players.follow(tp, str(a, "name"));
                return "Following " + tp.follow + ".";
            }
            case "travel_far", "explore" -> {
                players.walk(tp, (float) (Math.random() * 360));
                return "Walking " + TestPlayer.compass(tp.walkYaw) + " to load new chunks.";
            }
            case "wait", "stop" -> {
                players.idle(tp);
                return "Standing still.";
            }
            default -> throw new IllegalArgumentException("Test players can't " + action.replace('_', ' ') + ".");
        }
    }

    private String status(TestPlayer tp) {
        Mannequin m = tp.entity();
        if (m == null) return tp.name + " is stopped.";
        Location l = m.getLocation();
        return tp.name + " at " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ() + ", " + tp.doing()
                + (tp.ai && tp.model != null ? ", AI " + tp.model : ", no AI")
                + ", moved " + Math.round(tp.blocksMoved) + " blocks.";
    }

    private void reply(TestPlayer tp, String to, String text) {
        JsonObject ev = new JsonObject();
        ev.addProperty("type", "reply");
        ev.addProperty("bot", tp.id);
        ev.addProperty("botName", tp.name);
        ev.addProperty("to", to);
        ev.addProperty("text", text);
        events.add(ev);
    }

    /** A test player said something in chat: show it in its chat box on the Bots page. */
    void said(TestPlayer tp, String text) {
        if (!running) return;
        JsonObject ev = new JsonObject();
        ev.addProperty("type", "said");
        ev.addProperty("bot", tp.id);
        ev.addProperty("text", text);
        events.add(ev);
    }

    /** Someone in the game spoke to a test player (shown in its chat box). */
    void heard(TestPlayer tp, String from, String text) {
        if (!running) return;
        JsonObject ev = new JsonObject();
        ev.addProperty("type", "chat");
        ev.addProperty("bot", tp.id);
        ev.addProperty("from", from);
        ev.addProperty("text", text);
        events.add(ev);
    }

    private TestPlayer bot(JsonObject args) {
        TestPlayer tp = plugin.players().get(str(args, "bot"));
        if (tp == null) throw new IllegalArgumentException("No test player \"" + str(args, "bot") + "\" on this server.");
        return tp;
    }

    /** website.spawn-at ("world,x,y,z"), or the first world's spawn. */
    private Location spawnPoint() {
        String[] p = plugin.settings().spawnAt().split(",");
        if (p.length == 4) {
            World w = Bukkit.getWorld(p[0].trim());
            try {
                if (w != null) return new Location(w, Double.parseDouble(p[1].trim()), Double.parseDouble(p[2].trim()), Double.parseDouble(p[3].trim()));
            } catch (NumberFormatException ignored) {
                // fall through to the world spawn
            }
        }
        return Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    private static String cleanGoal(String g) {
        String t = g.replaceAll("\\s+", " ").trim();
        return t.length() > 500 ? t.substring(0, 500) : t;
    }

    // ------------------------------------------------------------ snapshot and events to the website

    private void flush() {
        if (!running) return;
        JsonObject body = new JsonObject();
        JsonArray evs = new JsonArray();
        for (JsonObject e; evs.size() < 400 && (e = events.poll()) != null; ) evs.add(e);
        body.add("events", evs);
        body.add("snapshot", snapshot());
        HttpRequest req = request("/api/botlink/events").timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build();
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).thenAccept(res -> {
            // The first long-poll can take 25 s to come back; a snapshot getting through shows the link works sooner.
            if (res.statusCode() == 200) status = "connected as \"" + plugin.settings().hostName() + "\"";
            else if (res.statusCode() == 401) status = "the website refused website.manager-key (it must equal BOT_MANAGER_KEY)";
        });
    }

    private JsonObject snapshot() {
        Settings s = plugin.settings();
        TestPlayers players = plugin.players();
        JsonObject snap = new JsonObject();
        snap.addProperty("host", s.hostName());
        snap.addProperty("kind", "testplayers");
        snap.addProperty("server", Bukkit.getName() + " " + Bukkit.getMinecraftVersion());
        snap.addProperty("maxBots", s.maxPlayers());
        long[] mem = systemMemoryMb();
        snap.addProperty("freeMemMb", mem[1]);
        snap.addProperty("totalMemMb", mem[0]);
        snap.addProperty("reserveMemMb", 0);
        snap.add("gpu", null);
        snap.add("ollama", ollama);
        double[] tps = Bukkit.getTPS();
        JsonObject perf = new JsonObject();
        perf.addProperty("tps", Math.round(tps[0] * 100) / 100.0);
        perf.addProperty("mspt", Math.round(Bukkit.getAverageTickTime() * 100) / 100.0);
        perf.addProperty("players", Bukkit.getOnlinePlayers().size());
        perf.addProperty("testPlayers", players.spawnedCount());
        perf.addProperty("chunksHeld", players.chunksHeld());
        int loaded = 0;
        int entities = 0;
        for (World w : Bukkit.getWorlds()) {
            loaded += w.getLoadedChunks().length;
            entities += w.getEntityCount();
        }
        perf.addProperty("loadedChunks", loaded);
        perf.addProperty("entities", entities);
        Runtime rt = Runtime.getRuntime();
        perf.addProperty("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / 1048576);
        perf.addProperty("heapMaxMb", rt.maxMemory() / 1048576);
        snap.add("perf", perf);

        JsonArray bots = new JsonArray();
        for (TestPlayer tp : players.all()) {
            JsonObject b = new JsonObject();
            Mannequin m = tp.entity();
            b.addProperty("id", tp.id);
            b.addProperty("name", tp.name);
            b.addProperty("account", "test player");
            b.addProperty("server", Bukkit.getName() + " " + Bukkit.getMinecraftVersion());
            b.addProperty("state", m != null ? "online" : tp.wanted ? "starting" : "stopped");
            b.add("offlineReason", null);
            b.addProperty("model", tp.model == null ? "rules only" : tp.model + (tp.staff != null ? " (" + tp.staff + "'s computer)" : ""));
            b.addProperty("mode", "test");
            b.addProperty("queue", "game");
            b.addProperty("autonomous", tp.ai);
            b.addProperty("task", tp.doing());
            b.addProperty("thought", tp.thought);
            if (tp.goal != null) {
                JsonObject g = new JsonObject();
                g.addProperty("text", tp.goal);
                b.add("goal", g);
            }
            JsonObject stats = new JsonObject();
            stats.addProperty("deaths", tp.deaths);
            stats.addProperty("blocksMoved", Math.round(tp.blocksMoved));
            b.add("stats", stats);
            if (m != null) {
                Location l = m.getLocation();
                JsonObject pos = new JsonObject();
                pos.addProperty("x", l.getBlockX());
                pos.addProperty("y", l.getBlockY());
                pos.addProperty("z", l.getBlockZ());
                b.add("position", pos);
                b.addProperty("dimension", l.getWorld().getName());
                b.addProperty("health", m.getHealth());
                b.addProperty("food", 20);
            }
            b.add("inventory", new JsonObject());
            b.add("history", new JsonArray());
            b.add("nearby", new JsonArray());
            bots.add(b);
        }
        snap.add("bots", bots);
        snap.add("teams", new JsonArray());
        snap.add("accounts", new JsonArray());
        return snap;
    }

    /** The models this server's Ollama has, so the Bots page can offer them. */
    private void refreshModels() {
        JsonObject o = new JsonObject();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(plugin.settings().ollamaUrl() + "/api/tags")).timeout(Duration.ofSeconds(4)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonArray models = new JsonArray();
            for (JsonElement e : JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("models")) {
                JsonObject m = new JsonObject();
                m.addProperty("name", str(e.getAsJsonObject(), "name"));
                m.addProperty("sizeMb", e.getAsJsonObject().get("size").getAsLong() / 1048576);
                models.add(m);
            }
            o.addProperty("up", true);
            o.add("models", models);
        } catch (IOException | RuntimeException e) {
            o.addProperty("up", false);
            o.add("models", new JsonArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        o.add("loaded", new JsonArray());
        o.add("pulling", new JsonArray());
        ollama = o;
    }

    /** [total, available] MB of the machine (Linux), or the JVM's view elsewhere. */
    private static long[] systemMemoryMb() {
        try {
            String info = Files.readString(Path.of("/proc/meminfo"));
            Matcher t = Pattern.compile("MemTotal:\\s+(\\d+)").matcher(info);
            Matcher a = Pattern.compile("MemAvailable:\\s+(\\d+)").matcher(info);
            if (t.find() && a.find()) return new long[] {Long.parseLong(t.group(1)) / 1024, Long.parseLong(a.group(1)) / 1024};
        } catch (IOException | RuntimeException ignored) {
            // not Linux
        }
        Runtime rt = Runtime.getRuntime();
        return new long[] {rt.maxMemory() / 1048576, (rt.maxMemory() - rt.totalMemory() + rt.freeMemory()) / 1048576};
    }

    // ------------------------------------------------------------ model requests through the website

    /** Run a model on a staff member's own computer (their connector), through the website. */
    CompletableFuture<JsonObject> relay(String staff, JsonObject request, long timeoutMs) {
        if (!running) return CompletableFuture.failedFuture(new IllegalStateException("the website link is off, so " + staff + "'s computer can't be reached"));
        JsonObject body = new JsonObject();
        body.addProperty("staff", staff);
        body.add("request", request);
        body.addProperty("timeoutMs", timeoutMs);
        HttpRequest req = request("/api/botlink/llm").timeout(Duration.ofMillis(timeoutMs + 10_000)).POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(res -> {
            JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
            if (res.statusCode() != 200 || !o.get("ok").getAsBoolean()) throw new IllegalStateException(o.has("error") ? o.get("error").getAsString() : "HTTP " + res.statusCode());
            return o.getAsJsonObject("response");
        });
    }

    private static JsonElement text(String s) {
        return new com.google.gson.JsonPrimitive(s);
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString().trim() : "";
    }
}
