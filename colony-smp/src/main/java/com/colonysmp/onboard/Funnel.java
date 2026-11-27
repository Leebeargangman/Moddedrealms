package com.colonysmp.onboard;

import com.colonysmp.ColonySMP;
import com.colonysmp.txn.Migrations;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Where new players drop off: one row per player per step, the first time they reach it (plugins/ColonySMP/funnel.db).
 * The same file keeps which one-time hints a player has seen and what their colony looked like when they logged off
 * (for the "while you were away" summary). Everything is held in memory and written on a background thread.
 */
public final class Funnel implements Listener {

    /** The steps, in the order a new player meets them. SMP reports the Rowan steps through ColonyApi. */
    public static final List<String> STEPS = List.of("first_join", "talked_to_rowan", "chose_colony", "chose_battles", "asked_friend", "joined_friend",
            "colony_founded", "opened_menu", "first_job_assigned", "first_building", "played_together", "reached_5_minutes", "left_server",
            "returned_next_day");

    private static final List<Migrations.Step> SCHEMA = List.of(
            new Migrations.Step(1, "funnel steps, hints seen, logoff snapshots", List.of(
                    "CREATE TABLE funnel (player TEXT NOT NULL, step TEXT NOT NULL, time INTEGER NOT NULL, PRIMARY KEY (player, step))",
                    "CREATE INDEX funnel_step ON funnel (step, time)",
                    "CREATE TABLE seen (player TEXT NOT NULL, hint TEXT NOT NULL, PRIMARY KEY (player, hint))",
                    "CREATE TABLE away (player TEXT PRIMARY KEY, colony TEXT NOT NULL, time INTEGER NOT NULL, data TEXT NOT NULL)")));

    /** A player's colony when they logged off: when, the colony, and "good=count;..." plus "#pop=n". */
    public record Away(String colony, long time, String data) {}

    private final ColonySMP plugin;
    private final Map<UUID, Map<String, Long>> steps = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> seen = new ConcurrentHashMap<>();
    private final Map<UUID, Away> away = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ColonySMP-funnel");
        t.setDaemon(true);
        return t;
    });
    private Connection conn;
    private final ZoneId zone;

    public Funnel(ColonySMP plugin) {
        this.plugin = plugin;
        ZoneId z;
        try {
            z = ZoneId.of(plugin.getConfig().getString("onboarding.timezone", "Europe/London"));
        } catch (RuntimeException e) {
            z = ZoneId.of("Europe/London");
        }
        this.zone = z;
    }

    public void open() {
        File file = new File(plugin.getDataFolder(), "funnel.db");
        try {
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA busy_timeout=5000");
            }
            Migrations.apply(conn, SCHEMA);
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT player, step, time FROM funnel")) {
                while (rs.next()) steps.computeIfAbsent(uuid(rs.getString(1)), k -> new ConcurrentHashMap<>()).put(rs.getString(2), rs.getLong(3));
            }
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT player, hint FROM seen")) {
                while (rs.next()) seen.computeIfAbsent(uuid(rs.getString(1)), k -> ConcurrentHashMap.newKeySet()).add(rs.getString(2));
            }
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT player, colony, time, data FROM away")) {
                while (rs.next()) away.put(uuid(rs.getString(1)), new Away(rs.getString(2), rs.getLong(3), rs.getString(4)));
            }
            steps.remove(NIL);
            seen.remove(NIL);
            away.remove(NIL);
        } catch (ClassNotFoundException | SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Couldn't open funnel.db: funnel tracking, hints and away summaries are off until it's fixed", e);
            conn = null;
        }
    }

    public void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) plugin.getLogger().warning("funnel.db: some writes didn't finish in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {
            // closing anyway
        }
        conn = null;
    }

    private static final UUID NIL = new UUID(0, 0);

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return NIL;
        }
    }

    private void write(String sql, Object... args) {
        if (conn == null || io.isShutdown()) return;
        io.execute(() -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("funnel.db write failed: " + e.getMessage());
            }
        });
    }

    // ───────────── steps ─────────────

    public boolean tracking() {
        return conn != null && plugin.settings().feature("funnel");
    }

    /** Records a step the first time a player reaches it. Unknown step names are ignored. */
    public void record(UUID player, String step) {
        if (player == null || step == null || !tracking()) return;
        String s = step.toLowerCase(Locale.ROOT);
        if (!STEPS.contains(s)) return;
        // only players we saw arrive count, so old players opening a menu don't skew the numbers
        Map<String, Long> mine = steps.get(player);
        if (!s.equals("first_join") && (mine == null || !mine.containsKey("first_join"))) return;
        if (mine == null) mine = steps.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        long now = System.currentTimeMillis();
        if (mine.putIfAbsent(s, now) != null) return;
        write("INSERT OR IGNORE INTO funnel (player, step, time) VALUES (?, ?, ?)", player.toString(), s, now);
        if (plugin.settings().debug("onboarding")) plugin.getLogger().info("[funnel] " + player + " " + s);
    }

    public boolean has(UUID player, String step) {
        Map<String, Long> mine = steps.get(player);
        return mine != null && mine.containsKey(step);
    }

    /** The furthest step (in {@link #STEPS} order) this player has reached, or "" if none. */
    public String furthest(UUID player) {
        Map<String, Long> mine = steps.get(player);
        if (mine == null) return "";
        String best = "";
        for (String s : STEPS) if (mine.containsKey(s) && !s.equals("left_server")) best = s;
        return best;
    }

    /** How many players who first joined since the given time reached each step, in step order. */
    public Map<String, Integer> counts(long since) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (String s : STEPS) out.put(s, 0);
        for (Map<String, Long> mine : steps.values()) {
            Long first = mine.get("first_join");
            if (first == null || first < since) continue;
            for (String s : mine.keySet()) out.computeIfPresent(s, (k, v) -> v + 1);
        }
        return out;
    }

    // ───────────── hints ─────────────

    /** Marks a hint as seen; true if this is the first time (show it now). */
    public boolean firstTime(UUID player, String hint) {
        if (conn == null) return false;
        Set<String> mine = seen.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet());
        if (!mine.add(hint)) return false;
        write("INSERT OR IGNORE INTO seen (player, hint) VALUES (?, ?)", player.toString(), hint);
        return true;
    }

    public boolean seen(UUID player, String hint) {
        Set<String> mine = seen.get(player);
        return mine != null && mine.contains(hint);
    }

    // ───────────── logoff snapshots ─────────────

    public void away(UUID player, Away a) {
        if (conn == null) return;
        if (a == null) {
            if (away.remove(player) != null) write("DELETE FROM away WHERE player = ?", player.toString());
            return;
        }
        away.put(player, a);
        write("INSERT OR REPLACE INTO away (player, colony, time, data) VALUES (?, ?, ?, ?)", player.toString(), a.colony(), a.time(), a.data());
    }

    public Away away(UUID player) {
        return away.get(player);
    }

    // ───────────── events ─────────────

    private LocalDate day(long millis) {
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!p.hasPlayedBefore()) {
            record(p.getUniqueId(), "first_join");
            return;
        }
        Map<String, Long> mine = steps.get(p.getUniqueId());
        Long first = mine == null ? null : mine.get("first_join");
        if (first != null && !mine.containsKey("returned_next_day") && LocalDate.now(zone).isAfter(day(first))) {
            record(p.getUniqueId(), "returned_next_day");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        record(e.getPlayer().getUniqueId(), "left_server");
    }

    /** Every 20 seconds: new players who've now played five minutes. */
    public void tick() {
        if (!tracking()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Map<String, Long> mine = steps.get(p.getUniqueId());
            if (mine == null || !mine.containsKey("first_join") || mine.containsKey("reached_5_minutes")) continue;
            if (p.getStatistic(Statistic.PLAY_ONE_MINUTE) >= 5 * 60 * 20) record(p.getUniqueId(), "reached_5_minutes");
        }
    }

    /** /colonyadmin funnel lines. */
    public List<String> report(String window) {
        long now = System.currentTimeMillis();
        String w = window == null ? "7d" : window.toLowerCase(Locale.ROOT);
        long since = switch (w) {
            case "24h", "1d", "day" -> now - 24 * 3600_000L;
            case "all", "ever" -> 0;
            default -> now - 7 * 24 * 3600_000L;
        };
        String label = since == 0 ? "all time" : w.equals("24h") || w.equals("1d") || w.equals("day") ? "the last 24 hours" : "the last 7 days";
        Map<String, Integer> c = counts(since);
        int base = c.getOrDefault("first_join", 0);
        List<String> out = new ArrayList<>();
        out.add("<aqua><bold>New-player funnel</bold></aqua> <gray>(" + label + ", " + base + " new players)");
        if (base == 0) {
            out.add("<gray>Nobody new yet in this window.");
            return out;
        }
        Map<String, String> names = new HashMap<>();
        for (String s : STEPS) names.put(s, s.replace('_', ' '));
        for (String s : STEPS) {
            int n = c.get(s);
            int pct = Math.round(n * 100f / base);
            int bar = Math.round(pct / 10f);
            out.add(" <white>" + String.format("%-20s", names.get(s)) + "</white> <green>" + "█".repeat(bar) + "<dark_gray>" + "█".repeat(10 - bar)
                    + " <white>" + n + "</white> <gray>(" + pct + "%)");
        }
        return out;
    }
}
