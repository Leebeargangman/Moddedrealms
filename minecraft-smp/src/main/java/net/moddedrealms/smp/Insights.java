package net.moddedrealms.smp;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * What players do, for staff: how long new players stay, whether they come back, why they were kicked, and which
 * commands get used (and by newcomers in their first minutes). Counts only, in insights.yml; no chat, no IPs.
 * Read it with /mrsmp insights.
 */
public final class Insights implements Listener {
    private final SmpPlugin plugin;
    private final File file;
    private final YamlConfiguration y;
    private final Map<UUID, Long> joinedAt = new HashMap<>();
    private final Map<UUID, Boolean> newcomer = new HashMap<>();
    private boolean dirty;

    Insights(SmpPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "insights.yml");
        this.y = YamlConfiguration.loadConfiguration(file);
    }

    void start() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            joinedAt.put(p.getUniqueId(), System.currentTimeMillis());
            newcomer.put(p.getUniqueId(), false);
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 6000L, 6000L);
    }

    void save() {
        if (!dirty) return;
        dirty = false;
        try {
            net.moddedrealms.smp.SafeYaml.save(y, file);
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save insights.yml: " + e.getMessage());
        }
    }

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private void add(String path, long n) {
        y.set(path, y.getLong(path) + n);
        dirty = true;
    }

    /** Seconds this player has played here: earlier sessions plus the one under way. */
    public long playedSeconds(UUID u) {
        long s = y.getLong("players." + u + ".seconds");
        Long at = joinedAt.get(u);
        if (at != null) s += Math.max(0, (System.currentTimeMillis() - at) / 1000);
        return s;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        joinedAt.put(u, now);
        String base = "players." + u;
        boolean first = !y.contains(base + ".first");
        newcomer.put(u, first);
        String day = today();
        if (first) {
            y.set(base + ".first", now);
            y.set(base + ".first-day", day);
            add("days." + day + ".new", 1);
        } else {
            String lastDay = y.getString(base + ".last-day", "");
            if (!lastDay.equals(day)) add("days." + day + ".returning", 1);
        }
        y.set(base + ".name", p.getName());
        y.set(base + ".last-day", day);
        add(base + ".sessions", 1);
        add("days." + day + ".joins", 1);
        dirty = true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        Long at = joinedAt.remove(u);
        Boolean first = newcomer.remove(u);
        if (at == null) return;
        long secs = Math.max(0, (System.currentTimeMillis() - at) / 1000);
        String base = "players." + u;
        add(base + ".seconds", secs);
        if (Boolean.TRUE.equals(first)) {
            y.set(base + ".first-session", secs);
            String day = y.getString(base + ".first-day", today());
            add("days." + day + (secs < 60 ? ".first-under-1m" : secs < 300 ? ".first-1-5m" : secs < 1800 ? ".first-5-30m" : ".first-over-30m"), 1);
        }
        dirty = true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent e) {
        String why = SmpPlugin.MM.serialize(e.reason()).replaceAll("<[^>]*>", "").replaceAll("[^A-Za-z0-9 \\[\\]:,.'!-]", "").trim();
        if (why.length() > 60) why = why.substring(0, 60);
        if (why.isEmpty()) why = "no reason";
        add("kicks." + why.replace('.', ',').replace(':', ';'), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage();
        int sp = msg.indexOf(' ');
        String root = (sp < 0 ? msg : msg.substring(0, sp)).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/:_-]", "");
        if (root.length() < 2 || root.length() > 32) return;
        int colon = root.indexOf(':');
        if (colon > 0) root = "/" + root.substring(colon + 1);
        if (e.getPlayer().hasPermission("mrsmp.admin")) return; // staff
        root = root.substring(1);
        add("commands." + root, 1);
        Long at = joinedAt.get(e.getPlayer().getUniqueId());
        if (Boolean.TRUE.equals(newcomer.get(e.getPlayer().getUniqueId())) && at != null && System.currentTimeMillis() - at < 600_000L) {
            add("newcomer-commands." + root, 1);
        }
    }

    /** /mrsmp insights [days] */
    List<String> report(int days) {
        List<String> out = new ArrayList<>();
        LocalDate end = LocalDate.now(ZoneOffset.UTC);
        long newP = 0, ret = 0, joins = 0, u1 = 0, u5 = 0, u30 = 0, o30 = 0;
        for (int i = 0; i < days; i++) {
            String d = "days." + end.minusDays(i);
            newP += y.getLong(d + ".new");
            ret += y.getLong(d + ".returning");
            joins += y.getLong(d + ".joins");
            u1 += y.getLong(d + ".first-under-1m");
            u5 += y.getLong(d + ".first-1-5m");
            u30 += y.getLong(d + ".first-5-30m");
            o30 += y.getLong(d + ".first-over-30m");
        }
        long ended = u1 + u5 + u30 + o30;
        out.add("<gold>Last " + days + " day" + (days == 1 ? "" : "s") + ": <white>" + newP + "</white> new players, <white>" + ret
                + "</white> returning visits, <white>" + joins + "</white> joins.");
        if (ended > 0) {
            out.add("<gray>New players' first visit: <white>" + pct(u1, ended) + "</white> left within a minute, <white>" + pct(u5, ended) + "</white> 1-5 min, <white>"
                    + pct(u30, ended) + "</white> 5-30 min, <white>" + pct(o30, ended) + "</white> stayed over 30 min.");
        }
        // came back on another day
        ConfigurationSection ps = y.getConfigurationSection("players");
        int firsts = 0, back = 0;
        LocalDate from = end.minusDays(days - 1L);
        if (ps != null) {
            for (String k : ps.getKeys(false)) {
                String fd = ps.getString(k + ".first-day");
                if (fd == null || LocalDate.parse(fd).isBefore(from)) continue;
                firsts++;
                if (!fd.equals(ps.getString(k + ".last-day", fd))) back++;
            }
        }
        if (firsts > 0) out.add("<gray>Of those new players, <white>" + back + "</white> (" + pct(back, firsts) + ") came back on a later day.");
        out.add("<gold>Kicks: <gray>" + top("kicks", 5));
        out.add("<gold>Commands newcomers try first: <gray>" + top("newcomer-commands", 10));
        out.add("<gold>Most used commands: <gray>" + top("commands", 12));
        return out;
    }

    private static String pct(long a, long b) {
        return b == 0 ? "0%" : Math.round(a * 100.0 / b) + "%";
    }

    private String top(String section, int n) {
        ConfigurationSection s = y.getConfigurationSection(section);
        if (s == null) return "none yet";
        List<Map.Entry<String, Long>> all = new ArrayList<>();
        for (String k : s.getKeys(false)) all.add(Map.entry(k, s.getLong(k)));
        all.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(n, all.size()); i++) {
            if (i > 0) b.append(", ");
            b.append("<white>").append(SmpPlugin.MM.escapeTags(all.get(i).getKey())).append("</white> ").append(all.get(i).getValue());
        }
        return b.length() == 0 ? "none yet" : b.toString();
    }
}
