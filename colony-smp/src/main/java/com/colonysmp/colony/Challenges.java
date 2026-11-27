package com.colonysmp.colony;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Recipes;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.util.Messages;
import com.colonysmp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Daily colony challenges (features.daily-challenges): at midnight (onboarding.timezone) one challenge from
 * challenges.pool is picked for every colony, its target scaled to the colony's tier. Progress counts from where
 * the colony stood when the day began. A colony that finishes gets challenges.rewards (members online share the
 * personal ones); the first to finish gets challenges.best-rewards when the day ends. A board at challenges.board
 * shows the challenge and the leaders; /colony challenge shows a colony's own progress.
 */
public final class Challenges {

    /** One kind of challenge from the pool. */
    public record Challenge(String kind, String good, int amount, String text) {}

    /** A colony's standing today. */
    public record Standing(Colony colony, long have, long need, long finishedAt) {
        public double fraction() {
            return need <= 0 ? 1 : Math.min(1, (double) have / need);
        }

        public boolean done() {
            return finishedAt > 0;
        }
    }

    private final ColonySMP plugin;
    private final File file;
    private final YamlConfiguration y;
    private final NamespacedKey boardKey;
    private List<Challenge> pool = List.of();
    private TextDisplay board;

    public Challenges(ColonySMP plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "challenges.yml");
        this.y = YamlConfiguration.loadConfiguration(file);
        this.boardKey = new NamespacedKey(plugin, "challenge_board");
        reload();
    }

    public boolean on() {
        return plugin.settings().feature("daily-challenges");
    }

    public void reload() {
        List<Challenge> out = new ArrayList<>();
        var cfg = plugin.getConfig();
        List<Map<?, ?>> raw = cfg.isSet("challenges.pool") ? cfg.getMapList("challenges.pool")
                : cfg.getDefaults() == null ? List.of() : cfg.getDefaults().getMapList("challenges.pool");
        for (Map<?, ?> m : raw) {
            String kind = String.valueOf(m.get("kind")).toLowerCase(Locale.ROOT);
            Object good = m.get("good"), amount = m.get("amount"), text = m.get("text");
            int n;
            try {
                n = Math.max(1, Integer.parseInt(String.valueOf(amount)));
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("challenges.pool: " + m + " needs a whole-number amount");
                continue;
            }
            if (!List.of("gain", "settlers", "buildings", "requests", "treasury", "raids").contains(kind)) {
                plugin.getLogger().warning("challenges.pool: unknown kind " + kind);
                continue;
            }
            if (kind.equals("gain") && good == null) {
                plugin.getLogger().warning("challenges.pool: a gain challenge needs a good");
                continue;
            }
            out.add(new Challenge(kind, good == null ? null : String.valueOf(good).toLowerCase(Locale.ROOT), n, text == null ? kind : String.valueOf(text)));
        }
        pool = out;
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(plugin.getConfig().getString("onboarding.timezone", "Europe/London"));
        } catch (Exception e) {
            return ZoneId.of("Europe/London");
        }
    }

    private String today() {
        return LocalDate.now(zone()).toString();
    }

    private void save() {
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save challenges.yml: " + e.getMessage());
        }
    }

    // ───────────── today's challenge ─────────────

    /** Today's challenge (null when off or the pool is empty). */
    public Challenge current() {
        if (!on() || pool.isEmpty()) return null;
        int i = y.getInt("index", -1);
        if (i < 0 || i >= pool.size() || !today().equals(y.getString("date"))) return null;
        Challenge ch = pool.get(i);
        return ch.text().equals(y.getString("text", ch.text())) ? ch : null;
    }

    /** The target for this colony: the challenge's amount, more for higher tiers. */
    public long target(Challenge ch, Colony c) {
        double per = Math.max(0, plugin.getConfig().getDouble("challenges.scale-per-tier"));
        int tier = Math.max(1, c.level());
        return Math.max(1, Math.round(ch.amount() * (1 + per * (tier - 1))));
    }

    private long measure(Challenge ch, Colony c) {
        return switch (ch.kind()) {
            case "gain" -> {
                long n = 0;
                List<String> goods = Recipes.isGroup(ch.good()) ? Recipes.members(ch.good()) : List.of(ch.good());
                for (String g : goods) n += c.storage.get(g);
                yield n;
            }
            case "settlers" -> c.population();
            case "buildings" -> c.buildings.size();
            case "requests" -> c.requestsFilled;
            case "treasury" -> c.treasury.balance();
            case "raids" -> c.raidsRepelled;
            default -> 0;
        };
    }

    private String path(Colony c) {
        return "colonies." + c.id;
    }

    /** Where this colony stands today (null when there's no challenge). */
    public Standing standing(Colony c) {
        Challenge ch = current();
        if (ch == null || c.core == null) return null;
        String p = path(c);
        if (!y.isSet(p + ".base")) {
            y.set(p + ".base", measure(ch, c));
            y.set(p + ".target", target(ch, c));
            y.set("dirty", true);
        }
        long need = y.getLong(p + ".target", target(ch, c));
        long have = Math.max(0, measure(ch, c) - y.getLong(p + ".base"));
        return new Standing(c, Math.min(have, need), need, y.getLong(p + ".done"));
    }

    /** Every colony today, finished first (earliest first), then by progress. */
    public List<Standing> ranking() {
        List<Standing> out = new ArrayList<>();
        for (Colony c : plugin.colonies().list()) {
            Standing s = standing(c);
            if (s != null) out.add(s);
        }
        out.sort(Comparator.comparing((Standing s) -> !s.done()).thenComparingLong(s -> s.done() ? s.finishedAt() : 0)
                .thenComparing(Comparator.comparingDouble(Standing::fraction).reversed()));
        return out;
    }

    // ───────────── the clock ─────────────

    /** Every 30 seconds: a new day picks a new challenge; colonies that have done it are rewarded. */
    public void tick() {
        if (!on()) {
            removeBoard();
            return;
        }
        if (pool.isEmpty()) return;
        String day = today();
        // a new day, or the pool was changed under today's challenge (staff edited it): pick again
        int idx = y.getInt("index", -1);
        boolean stale = idx < 0 || idx >= pool.size() || !pool.get(idx).text().equals(y.getString("text", pool.get(idx).text()));
        if (!day.equals(y.getString("date")) || stale) newDay(day);
        Challenge ch = current();
        if (ch == null) return;
        boolean changed = false;
        for (Colony c : plugin.colonies().list()) {
            Standing s = standing(c);
            if (s == null || s.done() || s.have() < s.need()) continue;
            finish(c, ch);
            changed = true;
        }
        if (changed || y.getBoolean("dirty", true)) {
            y.set("dirty", false);
            save();
        }
        showBoard();
    }

    private void newDay(String day) {
        // yesterday's best: the first colony to finish
        String bestId = null;
        long bestAt = Long.MAX_VALUE;
        ConfigurationSection cs = y.getConfigurationSection("colonies");
        if (cs != null && y.getString("date") != null) {
            for (String id : cs.getKeys(false)) {
                long at = cs.getLong(id + ".done");
                if (at > 0 && at < bestAt) {
                    bestAt = at;
                    bestId = id;
                }
            }
        }
        if (bestId != null) {
            Colony best = plugin.colonies().get(bestId);
            if (best != null) rewardBest(best);
        }
        int last = y.getInt("index", -1);
        int i = Math.floorMod(day.hashCode(), pool.size());
        if (pool.size() > 1 && i == last) i = (i + 1) % pool.size();
        y.set("date", day);
        y.set("index", i);
        y.set("text", pool.get(i).text());
        y.set("colonies", null);
        y.set("best-paid", false);
        save();
        Challenge ch = pool.get(i);
        plugin.getLogger().info("Today's colony challenge: " + ch.text());
        for (Player p : Bukkit.getOnlinePlayers()) {
            Colony c = plugin.colonies().of(p);
            if (c == null) continue;
            Text.send(p, Messages.get("challenges.new", "text", ch.text(), "target", target(ch, c)));
        }
    }

    private void finish(Colony c, Challenge ch) {
        y.set(path(c) + ".done", System.currentTimeMillis());
        y.set("dirty", true);
        long day = plugin.sim().day(c);
        c.log(ColonyEvent.Type.COLONY, day, "Finished the daily challenge: " + ch.text() + ".");
        List<String> rewards = plugin.getConfig().getStringList("challenges.rewards");
        List<Player> online = c.onlineMembers();
        String said = pay(c, online, rewards, "Daily challenge");
        boolean first = ranking().stream().filter(Standing::done).count() == 1;
        for (Player m : online) {
            Text.title(m, Messages.get("challenges.done-title"), Messages.get("challenges.done-subtitle", "text", ch.text()), 10, 60, 20);
            Text.send(m, Messages.compose("challenges.done", "text", Text.esc(ch.text()), "rewards", Text.esc(said)));
            m.playSound(m.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.1f);
        }
        if (first && plugin.getConfig().getBoolean("challenges.announce-first")) {
            Bukkit.broadcast(Text.mm(Messages.compose("challenges.first", "colony", Text.esc(c.name), "text", Text.esc(ch.text()))));
        }
        plugin.getLogger().info(c.name + " finished the daily challenge (" + ch.text() + ").");
    }

    private void rewardBest(Colony c) {
        if (y.getBoolean("best-paid")) return;
        y.set("best-paid", true);
        List<String> rewards = plugin.getConfig().getStringList("challenges.best-rewards");
        List<Player> online = c.onlineMembers();
        String said = pay(c, online, rewards, "Best colony of the daily challenge");
        c.log(ColonyEvent.Type.COLONY, plugin.sim().day(c), "Was the first colony to finish yesterday's challenge.");
        if (plugin.getConfig().getBoolean("challenges.announce-first")) {
            Bukkit.broadcast(Text.mm(Messages.compose("challenges.best", "colony", Text.esc(c.name), "rewards", Text.esc(said))));
        }
    }

    /** Colony rewards once; personal ones to every member online (the first also gets the colony's items). */
    private String pay(Colony c, List<Player> online, List<String> rewards, String memo) {
        if (rewards.isEmpty()) return "";
        if (online.isEmpty()) return plugin.quests().pay(c, null, rewards, memo);
        String said = plugin.quests().pay(c, online.get(0), rewards, memo);
        List<String> personal = new ArrayList<>();
        for (String r : rewards) {
            String k = r.split(":")[0].toLowerCase(Locale.ROOT);
            if (!k.equals("treasury") && !k.equals("good") && !k.equals("item")) personal.add(r);
        }
        for (int i = 1; i < online.size() && !personal.isEmpty(); i++) plugin.quests().pay(c, online.get(i), personal, memo);
        return said;
    }

    /** /colonyadmin challenge next: ends today's challenge now (the first finisher gets the bonus) and picks another. */
    public String skip() {
        if (!on() || pool.isEmpty()) return null;
        newDay(today());
        Challenge ch = current();
        return ch == null ? null : ch.text();
    }

    // ───────────── telling ─────────────

    /** Lines for /colony challenge. */
    public List<String> describe(Colony c) {
        List<String> out = new ArrayList<>();
        Challenge ch = current();
        if (ch == null) {
            out.add(Messages.get(on() ? "challenges.none-yet" : "challenges.off"));
            return out;
        }
        out.add(Messages.get("challenges.header", "text", ch.text()));
        Standing s = c == null ? null : standing(c);
        if (s != null) {
            out.add(s.done() ? Messages.get("challenges.yours-done")
                    : Messages.compose("challenges.yours", "bar", Text.bar(s.fraction(), 20, "<green>|", "<dark_gray>|"), "have", s.have(), "need", s.need()));
        }
        int n = 0;
        for (Standing o : ranking()) {
            if (o.have() <= 0 && !o.done()) continue;
            if (n++ >= 5) break;
            out.add(Messages.compose("challenges.rank", "n", n, "colony", Text.esc(o.colony().name),
                    "state", o.done() ? "<green>✔" : "<gray>" + Math.round(o.fraction() * 100) + "%"));
        }
        out.add(Messages.get("challenges.reset"));
        return out;
    }

    /** One line for menus ("Today: Bring in 128 logs · 45%"), or null. */
    public String menuLine(Colony c) {
        Challenge ch = current();
        Standing s = ch == null ? null : standing(c);
        if (s == null) return null;
        return Messages.compose("challenges.menu", "text", Text.esc(ch.text()), "state", s.done() ? "<green>✔ done" : Math.round(s.fraction() * 100) + "%");
    }

    private Location boardSpot() {
        String spec = plugin.getConfig().getString("challenges.board", "");
        if (spec == null || spec.isBlank()) return null;
        String[] f = spec.split(",");
        if (f.length < 4) return null;
        World w = Bukkit.getWorld(f[0].trim());
        if (w == null) return null;
        try {
            return new Location(w, Double.parseDouble(f[1].trim()), Double.parseDouble(f[2].trim()), Double.parseDouble(f[3].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void showBoard() {
        Location at = boardSpot();
        if (at == null || !at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
            board = null;
            return;
        }
        if (board == null || !board.isValid()) {
            for (Entity e : at.getWorld().getNearbyEntities(at, 2, 2, 2)) {
                if (e instanceof TextDisplay td && td.getPersistentDataContainer().has(boardKey, PersistentDataType.BYTE)) td.remove();
            }
            board = at.getWorld().spawn(at, TextDisplay.class, td -> {
                td.setPersistent(false);
                td.setBillboard(Display.Billboard.CENTER);
                td.setLineWidth(220);
                td.setShadowed(true);
                td.getPersistentDataContainer().set(boardKey, PersistentDataType.BYTE, (byte) 1);
            });
        }
        Challenge ch = current();
        StringBuilder b = new StringBuilder(Messages.get("challenges.board-title")).append("<reset>\n");
        b.append(Messages.get("challenges.board-text", "text", ch == null ? "-" : ch.text())).append("<reset>\n");
        int n = 0;
        for (Standing s : ranking()) {
            if (s.have() <= 0 && !s.done()) continue;
            if (n++ >= 5) break;
            b.append(Messages.compose("challenges.rank", "n", n, "colony", Text.esc(s.colony().name),
                    "state", s.done() ? "<green>✔" : "<gray>" + Math.round(s.fraction() * 100) + "%")).append("<reset>\n");
        }
        if (n == 0) b.append(Messages.get("challenges.board-empty")).append("<reset>\n");
        b.append(Messages.get("challenges.board-footer"));
        board.text(Text.mm(b.toString()));
    }

    private void removeBoard() {
        if (board != null && board.isValid()) board.remove();
        board = null;
    }

    public void shutdown() {
        removeBoard();
        save();
    }
}
