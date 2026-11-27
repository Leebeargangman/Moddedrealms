package com.colonysmp.api;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.core.Goods;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Perm;
import com.colonysmp.model.Policies;
import com.colonysmp.sim.Population;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The small, stable surface other plugins (ModdedrealmsSMP) use to work with colonies: who lives where, what a colony
 * can spare, delivering and taking plain goods, colony news, and places where no colony may be founded. Everything
 * here runs on the server thread. Nothing in it creates goods: {@link #take} removes them from storage and
 * {@link #deliver} only stores what the caller already took from somewhere else.
 */
public final class ColonyApi {
    private static volatile ColonySMP plugin;
    private static final Map<String, Settings.Zone> zones = new ConcurrentHashMap<>();
    private static final Deque<News> news = new ArrayDeque<>();
    private static final int NEWS_KEEP = 200;

    private ColonyApi() {
    }

    /** Called by ColonySMP once everything is loaded. */
    public static void attach(ColonySMP p) {
        plugin = p;
        Colony.logHook = ColonyApi::onLog;
    }

    public static void detach() {
        plugin = null;
        Colony.logHook = null;
        synchronized (news) {
            news.clear();
        }
    }

    /** True while ColonySMP is loaded and running. */
    public static boolean ready() {
        return plugin != null;
    }

    // ───────────── colonies ─────────────

    /** A colony as other plugins see it. */
    public record Summary(String id, String name, UUID owner, String world, int x, int y, int z, int population, int level,
                          int members, long day, long graceDays, boolean recruiting) {
    }

    private static Summary summary(Colony c) {
        int x = c.core == null ? 0 : c.core.x(), y = c.core == null ? 0 : c.core.y(), z = c.core == null ? 0 : c.core.z();
        return new Summary(c.id, c.name, c.owner, c.world, x, y, z, c.population(), c.level(), c.members.size(),
                plugin.sim().day(c), Population.graceDays(c), c.policies.recruitment != Policies.Recruitment.CLOSED);
    }

    /** Every colony with a Town Hall. */
    public static List<Summary> colonies() {
        List<Summary> out = new ArrayList<>();
        if (plugin == null) return out;
        for (Colony c : plugin.colonies().list()) if (c.core != null) out.add(summary(c));
        return out;
    }

    /** The colony this player belongs to, or null. */
    public static Summary of(UUID player) {
        if (plugin == null) return null;
        Colony c = plugin.colonies().of(player);
        return c == null ? null : summary(c);
    }

    /** The colony that holds this spot, or null. */
    public static Summary at(Location l) {
        if (plugin == null || l == null) return null;
        Colony c = plugin.colonies().at(l);
        return c == null ? null : summary(c);
    }

    /** True if the player may spend the colony's treasury and goods for money (owner and co-owners by default). */
    public static boolean maySell(Player p, String colonyId) {
        Colony c = colony(colonyId);
        return c != null && plugin.access().can(p, c, Perm.TREASURY) && plugin.access().can(p, c, Perm.STORAGE_WITHDRAW);
    }

    /** True if the player may put goods into the colony's storage. */
    public static boolean mayDeliver(Player p, String colonyId) {
        Colony c = colony(colonyId);
        return c != null && plugin.access().can(p, c, Perm.STORAGE_DEPOSIT);
    }

    // ───────────── goods ─────────────

    /**
     * Plain goods the colony can spare, by item: in storage, not set aside for a task, and food above the colony's
     * food reserve (no food at all during the founding grace). Colony-made goods with no vanilla item (flour, rope...)
     * are left out.
     */
    public static Map<Material, Long> spare(String colonyId) {
        Map<Material, Long> out = new LinkedHashMap<>();
        Colony c = colony(colonyId);
        if (c == null) return out;
        for (String key : c.storage.snapshot().keySet()) {
            if (!Goods.known(key) || Goods.get(key).custom()) continue;
            Material m = Material.matchMaterial(Goods.get(key).material());
            if (m == null || !m.isItem()) continue;
            if (c.inGrace() && plugin.sim().citizens().edible(key)) continue; // a new colony's provisions stay with it
            long n = plugin.sim().finances().spare(c, key);
            if (n > 0) out.merge(m, n, Long::sum);
        }
        return out;
    }

    /** Takes up to qty of a good the colony can spare (see {@link #spare}). Returns how many were taken. */
    public static long take(String colonyId, Material m, long qty, String memo) {
        Colony c = colony(colonyId);
        String key = key(m);
        if (c == null || key == null || qty <= 0) return 0;
        if (c.inGrace() && plugin.sim().citizens().edible(key)) return 0;
        long n = Math.min(qty, plugin.sim().finances().spare(c, key));
        n = c.storage.take(key, n);
        if (n > 0) {
            c.log(ColonyEvent.Type.ECONOMY, plugin.sim().day(c), memo);
            plugin.requestSave();
        }
        return n;
    }

    /** How many more of this item colony storage has room for. */
    public static long room(String colonyId, Material m) {
        Colony c = colony(colonyId);
        String key = key(m);
        return c == null || key == null ? 0 : c.storage.room(key);
    }

    /** Stores up to qty of an item the caller has already taken from elsewhere. Returns how many were stored. */
    public static long deliver(String colonyId, Material m, long qty, String memo) {
        Colony c = colony(colonyId);
        String key = key(m);
        if (c == null || key == null || qty <= 0) return 0;
        long n = c.storage.add(key, qty);
        if (n > 0) {
            c.log(ColonyEvent.Type.ECONOMY, plugin.sim().day(c), memo);
            plugin.requestSave();
        }
        return n;
    }

    private static String key(Material m) {
        if (m == null) return null;
        String key = Goods.key(m.name());
        return Goods.known(key) && !Goods.get(key).custom() ? key : null;
    }

    private static Colony colony(String id) {
        return plugin == null || id == null ? null : plugin.colonies().get(id);
    }

    // ───────────── news ─────────────

    /** Something that happened in a colony. Type is a {@link ColonyEvent.Type} name. */
    public record News(String colonyId, String colony, String type, String message, long day, long time) {
    }

    private static void onLog(Colony c, ColonyEvent e) {
        synchronized (news) {
            news.addLast(new News(c.id, c.name, e.type().name(), e.message(), e.day(), e.time()));
            while (news.size() > NEWS_KEEP) news.removeFirst();
        }
    }

    /** Colony news since the last call (oldest first). Only one reader is expected. */
    public static List<News> drainNews() {
        synchronized (news) {
            List<News> out = new ArrayList<>(news);
            news.clear();
            return out;
        }
    }

    // ───────────── new players ─────────────

    /** True when "Found a Colony" places a ready starter camp (features.starter-camp). */
    public static boolean starterCamps() {
        return plugin != null && plugin.settings().feature("starter-camp");
    }

    /**
     * Founds a colony with a starter camp for this player at a ready site and takes them there. Returns why it can't
     * at once, or null when it has started; {@code done} then gets null (founded) or why it failed, on the main thread.
     */
    public static String startColony(Player p, java.util.function.Consumer<String> done) {
        if (plugin == null) return "Colonies aren't running right now.";
        return plugin.camps().start(p, done);
    }

    /** Records a new-player funnel step (talked_to_rowan, chose_colony, chose_battles...) the first time it happens. */
    /** Playing with friends is on (features.friends). */
    public static boolean friends() {
        return plugin != null && plugin.friends().on();
    }

    /** Colonies with members online that this player could ask to join (empty if they have one already). */
    public static List<com.colonysmp.onboard.Friends.Joinable> joinable(Player p) {
        return plugin == null ? List.of() : plugin.friends().joinable(p);
    }

    /** Asks a colony's members to let this player in. Null when asked; otherwise why not. */
    public static String askToJoin(Player p, String colonyId) {
        if (plugin == null) return "Colonies aren't running.";
        return plugin.friends().ask(p, plugin.colonies().get(colonyId));
    }

    public static void funnel(UUID player, String step) {
        if (plugin != null) plugin.funnel().record(player, step);
    }

    /** The furthest funnel step this player has reached, or "". */
    public static String funnelStep(UUID player) {
        return plugin == null ? "" : plugin.funnel().furthest(player);
    }

    /** True when this player has seen a one-time hint before; marks it seen. Use for "first time" tips. */
    public static boolean firstTime(UUID player, String hint) {
        return plugin != null && plugin.funnel().firstTime(player, hint);
    }

    // ───────────── no-colony zones ─────────────

    /** Keeps colonies out of a circle of a world (a town, a spawn). Calling it again with the same key moves it. */
    public static void protect(String key, String world, int x, int z, int radius, String label) {
        zones.put(key, new Settings.Zone(key, world, x, z, Math.max(0, radius), label == null ? "a protected area" : label));
    }

    public static void unprotect(String key) {
        zones.remove(key);
    }

    /** The name of the zone (added through this API) that keeps colonies off this chunk, or null. */
    public static String zoneAt(String world, int cx, int cz) {
        for (Settings.Zone z : zones.values()) if (z.covers(world, cx, cz)) return z.label();
        return null;
    }

    // ───────────── the server's own plugin ─────────────

    /**
     * What ModdedrealmsSMP does for /colonyadmin: the season's events and reset (season.yml lives there), and
     * its own NPCs for cleanup. ColonySMP never depends on that plugin; it registers itself here instead.
     */
    public interface ServerHook {
        /** Season event names for /colonyadmin event. */
        List<String> seasonEvents();

        /** Starts or stops one; null when it worked, else why not. */
        String seasonEvent(String name, boolean start);

        /** Archives the season and resets what the SMP owns (confirm=false only describes what would happen). */
        List<String> seasonEnd(boolean confirm);

        /** Its NPCs that are duplicated or orphaned, one line each (removed when remove=true). */
        List<String> strayNpcs(boolean remove);

        /** Pays reward lines ColonySMP doesn't handle itself ("money:50", "key:common:1", "cosmetic:x"...). */
        default void reward(Player p, List<String> rewards) {
        }

        /** Those rewards in a few words ("$50, 1 Common Key"), or "". */
        default String describe(List<String> rewards) {
            return "";
        }
    }

    private static volatile ServerHook hook;

    public static void hook(ServerHook h) {
        hook = h;
    }

    /** The registered hook, or null. */
    public static ServerHook hook() {
        return hook;
    }
}
