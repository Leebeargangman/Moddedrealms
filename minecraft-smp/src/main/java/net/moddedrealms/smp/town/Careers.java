package net.moddedrealms.smp.town;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.moddedrealms.smp.SmpPlugin;
import net.moddedrealms.smp.town.Businesses.Biz;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Careers: what makes a job more than clicking. Workers climb ranks with experience (Trainee, Hand, Senior,
 * Foreman), each paying more; slacking earns strikes (a task left to rot, dozing off on shift, a delivery not made,
 * a customer left waiting, trouble with the law), and three live strikes get you fired, by any business, town-run or
 * not. Strikes wear off after a week, and a clean, busy shift earns one back. A business you were fired from won't
 * take you back for a day. Owners can also warn and fire by hand.
 */
final class Careers {
    enum Rank {
        TRAINEE("Trainee", 0, 1.0), HAND("Hand", 25, 1.15), SENIOR("Senior hand", 100, 1.3), FOREMAN("Foreman", 300, 1.5);

        final String label;
        final int tasks;
        final double pay;

        Rank(String label, int tasks, double pay) {
            this.label = label;
            this.tasks = tasks;
            this.pay = pay;
        }

        static Rank of(int tasks) {
            Rank r = TRAINEE;
            for (Rank x : values()) if (tasks >= x.tasks) r = x;
            return r;
        }

        Rank next() {
            return ordinal() + 1 < values().length ? values()[ordinal() + 1] : null;
        }
    }

    private final TownManager t;
    private final File file;
    private final YamlConfiguration y;
    private boolean dirty;

    Careers(TownManager t) {
        this.t = t;
        this.file = new File(t.plugin().getDataFolder(), "careers.yml");
        this.y = YamlConfiguration.loadConfiguration(file);
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    void save() {
        if (!dirty) return;
        dirty = false;
        try {
            y.save(file);
        } catch (IOException e) {
            t.plugin().getLogger().warning("Couldn't save careers.yml: " + e.getMessage());
        }
    }

    private String base(UUID u) {
        return "workers." + u;
    }

    int tasks(UUID u, Biz b) {
        return y.getInt(base(u) + ".tasks." + b.id);
    }

    Rank rank(UUID u, Biz b) {
        return Rank.of(tasks(u, b));
    }

    /** The pay multiplier for this worker here. */
    double payFactor(UUID u, Biz b) {
        return rank(u, b).pay;
    }

    /** A task done: experience, and a rank-up now and then. */
    void taskDone(Player p, Biz b) {
        String k = base(p.getUniqueId()) + ".tasks." + b.id;
        Rank before = Rank.of(y.getInt(k));
        y.set(k, y.getInt(k) + 1);
        y.set(base(p.getUniqueId()) + ".name", p.getName());
        dirty = true;
        Rank now = Rank.of(y.getInt(k));
        if (now != before) {
            p.sendMessage(SmpPlugin.mm("<gold>★ Promoted to <white>" + now.label + "</white> at " + b.name + "! <gray>Pay is now <green>×" + now.pay + "</green>."));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
            if (now == Rank.FOREMAN) t.news(p.getName() + " made foreman at " + b.name + ".");
        }
    }

    // ------------------------------------------------------------------ strikes

    /** Live strikes (the last week). */
    List<String> strikes(UUID u) {
        long week = cfg().getLong("town.business.strike-days", 7) * 86_400_000L;
        long now = System.currentTimeMillis();
        List<String> out = new ArrayList<>();
        List<String> keep = new ArrayList<>();
        for (String s : y.getStringList(base(u) + ".strikes")) {
            int i = s.indexOf('|');
            if (i < 0) continue;
            try {
                if (now - Long.parseLong(s.substring(0, i)) < week) {
                    out.add(s.substring(i + 1));
                    keep.add(s);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (keep.size() != y.getStringList(base(u) + ".strikes").size()) {
            y.set(base(u) + ".strikes", keep);
            dirty = true;
        }
        return out;
    }

    /** A strike against an employee of b. Three live strikes and they're fired. */
    void strike(UUID u, Biz b, String why) {
        if (b == null || u.equals(b.owner) || !b.workers.contains(u)) return;
        List<String> list = new ArrayList<>(y.getStringList(base(u) + ".strikes"));
        list.add(System.currentTimeMillis() + "|" + why);
        y.set(base(u) + ".strikes", list);
        y.set(base(u) + ".clean", 0);
        dirty = true;
        int n = strikes(u).size();
        int max = cfg().getInt("town.business.strikes-to-fire", 3);
        Player p = Bukkit.getPlayer(u);
        if (n >= max) {
            fire(u, b, "three strikes: " + why);
            return;
        }
        if (p != null) {
            p.sendMessage(SmpPlugin.mm("<red>⚠ Strike " + n + "/" + max + " at " + b.name + ": <white>" + why + "</white>. <gray>" + (max - n == 1
                    ? "One more and you're fired." : (max - n) + " more and you're fired.") + " Strikes wear off after a week."));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        }
        if (b.owner != null && !b.owner.equals(u)) {
            Player o = Bukkit.getPlayer(b.owner);
            if (o != null) o.sendMessage(SmpPlugin.mm("<gold>[" + b.name + "] <gray>" + name(u) + " got strike " + n + "/" + max + ": " + why + "."));
        }
    }

    /** A busy shift with no strikes wins one back. */
    void cleanShift(Player p, Biz b, int tasks) {
        if (tasks < cfg().getInt("town.business.clean-shift-tasks", 10)) return;
        List<String> list = new ArrayList<>(y.getStringList(base(p.getUniqueId()) + ".strikes"));
        if (list.isEmpty()) return;
        list.remove(list.size() - 1);
        y.set(base(p.getUniqueId()) + ".strikes", list);
        dirty = true;
        p.sendMessage(SmpPlugin.mm("<green>Good shift! One strike wiped off your record at " + b.name + "."));
    }

    void fire(UUID u, Biz b, String why) {
        if (!b.workers.remove(u)) return;
        t.businesses().work().clockOut(u);
        t.businesses().save();
        y.set(base(u) + ".fired." + b.id, System.currentTimeMillis() + cfg().getLong("town.business.rehire-hours", 24) * 3_600_000L);
        y.set(base(u) + ".strikes", null);
        dirty = true;
        save();
        Player p = Bukkit.getPlayer(u);
        if (p != null) {
            p.sendMessage(SmpPlugin.mm("<red><bold>You're fired!</bold></red> <gray>" + b.name + " let you go (" + why + "). They won't take you back for a day. <white>/jobs</white> lists who's hiring."));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.9f, 0.8f);
        }
        if (b.owner != null) {
            Player o = Bukkit.getPlayer(b.owner);
            if (o != null && !o.getUniqueId().equals(u)) o.sendMessage(SmpPlugin.mm("<gold>[" + b.name + "] <gray>" + name(u) + " was fired (" + why + ")."));
        }
        t.news(name(u) + " was fired from " + b.name + ".");
    }

    /** Null when they may work here, otherwise why not. */
    String barred(UUID u, Biz b) {
        long until = y.getLong(base(u) + ".fired." + b.id);
        if (until > System.currentTimeMillis()) {
            long h = Math.max(1, (until - System.currentTimeMillis()) / 3_600_000L);
            return b.name + " fired you recently and won't take you back for another " + h + " hour" + (h == 1 ? "" : "s") + ".";
        }
        return null;
    }

    private static String name(UUID u) {
        OfflinePlayer o = Bukkit.getOfflinePlayer(u);
        return o.getName() == null ? "?" : o.getName();
    }

    /** Lines for /job. */
    List<String> card(UUID u, Biz b) {
        List<String> out = new ArrayList<>();
        Rank r = rank(u, b);
        Rank nx = r.next();
        int done = tasks(u, b);
        out.add("<gray>Rank: <white>" + r.label + "</white> <dark_gray>(" + done + " tasks" + (nx == null ? ", top rank" : ", " + (nx.tasks - done) + " to " + nx.label) + ")</dark_gray> · pay <green>×" + r.pay);
        List<String> s = strikes(u);
        int max = cfg().getInt("town.business.strikes-to-fire", 3);
        out.add(s.isEmpty() ? "<gray>Record: <green>clean" : "<gray>Strikes: <red>" + s.size() + "/" + max + "</red> <dark_gray>(" + String.join(", ", s) + ")");
        return out;
    }
}
