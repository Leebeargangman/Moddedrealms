package net.moddedrealms.smp.play;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

/**
 * Leaderboards: richest, most played, duel wins, best daily streak and biggest colonies. /top shows them in chat; a
 * floating board above the spawn fountain cycles through them. Recomputed every few minutes on the server thread
 * (the server is small); players on leaderboards.hide are left off.
 */
public final class Leaderboards implements TabExecutor, org.bukkit.event.Listener {
    public enum Board {
        MONEY("Richest (bank)", "money"), PLAYTIME("Most played", "playtime"), DUELS("Duel wins", "duels"), STREAK("Best daily streak", "streak"), COLONIES("Biggest colonies", "colonies");

        final String title, key;

        Board(String title, String key) {
            this.title = title;
            this.key = key;
        }

        static Board parse(String s) {
            for (Board b : values()) if (b.key.equalsIgnoreCase(s) || b.name().equalsIgnoreCase(s)) return b;
            return null;
        }
    }

    private final SmpPlugin plugin;
    private final NamespacedKey tag;
    private final Map<Board, List<String[]>> cache = new EnumMap<>(Board.class);
    private TextDisplay display;
    private int showing;

    public Leaderboards(SmpPlugin plugin) {
        this.plugin = plugin;
        this.tag = new NamespacedKey(plugin, "leaderboard");
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public void start() {
        Bukkit.getScheduler().runTaskLater(plugin, this::refresh, 200L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 20L * 60 * 5, 20L * 60 * 5);
        Bukkit.getScheduler().runTaskTimer(plugin, this::cycle, 300L, 20L * Math.max(3, cfg().getInt("leaderboards.board-seconds", 8)));
    }

    public void shutdown() {
        if (display != null && display.isValid()) display.remove();
        display = null;
    }

    private long refreshed;

    private void refresh() {
        refreshed = System.currentTimeMillis();
        Set<String> hide = new HashSet<>();
        for (String s : cfg().getStringList("leaderboards.hide")) hide.add(s.toLowerCase(Locale.ROOT));
        List<Object[]> money = new ArrayList<>(), play = new ArrayList<>();
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            String n = op.getName();
            if (n == null || hide.contains(n.toLowerCase(Locale.ROOT))) continue;
            try {
                if (plugin.town() != null && plugin.town().money.ready()) money.add(new Object[]{n, plugin.town().money.bankBalance(op)});
            } catch (RuntimeException ignored) {
            }
            try {
                play.add(new Object[]{n, (double) op.getStatistic(Statistic.PLAY_ONE_MINUTE)});
            } catch (RuntimeException ignored) {
            }
        }
        cache.put(Board.MONEY, top(money, v -> "$" + String.format(Locale.ROOT, "%,.0f", v)));
        cache.put(Board.PLAYTIME, top(play, v -> hours(v)));
        cache.put(Board.DUELS, top(fromUuids(plugin.duels() == null ? Map.of() : plugin.duels().allWins(), hide), v -> String.valueOf((long) (double) v)));
        cache.put(Board.STREAK, top(fromUuids(plugin.streaks() == null ? Map.of() : plugin.streaks().bests(), hide), v -> (long) (double) v + " days"));
        List<String[]> cols = new ArrayList<>();
        if (plugin.town() != null) {
            for (String[] c : plugin.town().coloniesBySize()) {
                if (cols.size() >= 10) break;
                cols.add(new String[]{c[0] + " <dark_gray>(" + c[2] + ")", c[1] + " people"});
            }
        }
        cache.put(Board.COLONIES, cols);
    }

    private static String hours(double ticks) {
        long mins = (long) (ticks / 20 / 60);
        return mins >= 60 ? (mins / 60) + "h " + (mins % 60) + "m" : mins + "m";
    }

    private static List<Object[]> fromUuids(Map<UUID, Integer> m, Set<String> hide) {
        List<Object[]> out = new ArrayList<>();
        for (var e : m.entrySet()) {
            if (e.getValue() <= 0) continue;
            String n = Bukkit.getOfflinePlayer(e.getKey()).getName();
            if (n == null || hide.contains(n.toLowerCase(Locale.ROOT))) continue;
            out.add(new Object[]{n, (double) e.getValue()});
        }
        return out;
    }

    private static List<String[]> top(List<Object[]> rows, java.util.function.DoubleFunction<String> fmt) {
        rows.sort((a, b) -> Double.compare((double) b[1], (double) a[1]));
        List<String[]> out = new ArrayList<>();
        for (Object[] r : rows) {
            if (out.size() >= 10) break;
            if ((double) r[1] <= 0) continue;
            out.add(new String[]{SmpPlugin.MM.escapeTags((String) r[0]), fmt.apply((double) r[1])});
        }
        return out;
    }

    private List<String> lines(Board b, int n) {
        List<String> out = new ArrayList<>();
        List<String[]> rows = cache.getOrDefault(b, List.of());
        if (rows.isEmpty()) out.add("<gray>Nobody yet. Be the first!");
        String[] medal = {"<gold>1.", "<white>2.", "<#cd7f32>3."};
        for (int i = 0; i < Math.min(n, rows.size()); i++) {
            out.add((i < 3 ? medal[i] : "<gray>" + (i + 1) + ".") + " <white>" + rows.get(i)[0] + " <gray>· <yellow>" + rows.get(i)[1]);
        }
        return out;
    }

    // ------------------------------------------------------------------ the board at spawn

    private Location boardSpot() {
        String s = cfg().getString("leaderboards.board", "fountain");
        if (s == null || s.isBlank() || s.equalsIgnoreCase("off")) return null;
        if (s.equalsIgnoreCase("fountain")) {
            if (plugin.town() == null) return null;
            World w = Bukkit.getWorld(plugin.town().townWorld());
            if (w == null) return null;
            int x = (int) Math.floor(plugin.town().centerX()), z = (int) Math.floor(plugin.town().centerZ());
            if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
            return new Location(w, x + 0.5, w.getHighestBlockYAt(x, z) + cfg().getDouble("leaderboards.board-height", 3.2), z + 0.5);
        }
        String[] f = s.split(",");
        try {
            World w = Bukkit.getWorld(f[0].trim());
            return w == null ? null : new Location(w, Double.parseDouble(f[1].trim()), Double.parseDouble(f[2].trim()), Double.parseDouble(f[3].trim()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void cycle() {
        Location at = boardSpot();
        if (at == null) {
            shutdown();
            return;
        }
        if (display == null || !display.isValid()) {
            // a board left over from a crash
            for (Entity e : at.getWorld().getNearbyEntities(at, 4, 6, 4)) if (e.getPersistentDataContainer().has(tag, PersistentDataType.BYTE)) e.remove();
            display = at.getWorld().spawn(at, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setShadowed(true);
                d.setDefaultBackground(false);
                d.setBackgroundColor(org.bukkit.Color.fromARGB(110, 0, 0, 0));
                d.setLineWidth(220);
                d.setViewRange(1.5f);
                d.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            });
        }
        Board[] all = Board.values();
        Board b = all[showing++ % all.length];
        if (b == Board.COLONIES && cache.getOrDefault(b, List.of()).isEmpty()) b = all[showing++ % all.length];
        StringBuilder t = new StringBuilder("<gold><bold>" + b.title + "</bold></gold>\n");
        for (String l : lines(b, 5)) t.append(l).append('\n');
        t.append("<dark_gray>/top ").append(b.key);
        display.text(SmpPlugin.mm(t.toString()));
    }

    // ------------------------------------------------------------------ /top

    /** Essentials owns /top (teleport up); players who can't use that get the leaderboards instead. */
    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void onTopCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent e) {
        String m = e.getMessage();
        String low = m.toLowerCase(Locale.ROOT);
        if (!(low.equals("/top") || low.startsWith("/top "))) return;
        if (e.getPlayer().hasPermission("essentials.top")) return;
        e.setMessage("/leaderboard" + m.substring(4));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        Board b = a.length > 0 ? Board.parse(a[0]) : null;
        if (b == null) {
            sender.sendMessage(SmpPlugin.mm("<gold>Leaderboards:</gold> <white>/top money</white> <gray>·</gray> <white>/top playtime</white> <gray>·</gray> <white>/top duels</white> "
                    + "<gray>·</gray> <white>/top streak</white> <gray>·</gray> <white>/top colonies</white>"));
            if (a.length == 0) b = Board.MONEY;
            else return true;
        }
        if (cache.isEmpty() || System.currentTimeMillis() - refreshed > 60_000L) refresh();
        sender.sendMessage(SmpPlugin.mm("<gold><bold>" + b.title + "</bold>"));
        for (String l : lines(b, 10)) sender.sendMessage(SmpPlugin.mm(" " + l));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) for (Board b : Board.values()) if (b.key.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(b.key);
        return out;
    }

    /** For the guide: one line, e.g. "Richest: Steve $12,000". */
    public Component headline(Board b) {
        List<String[]> rows = cache.getOrDefault(b, List.of());
        return rows.isEmpty() ? Component.empty() : SmpPlugin.mm("<gray>" + b.title + ": <white>" + rows.get(0)[0] + " <yellow>" + rows.get(0)[1]);
    }
}
