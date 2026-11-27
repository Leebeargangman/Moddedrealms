package com.colonysmp.colony;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Goods;
import com.colonysmp.core.Recipes;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Job;
import com.colonysmp.model.Perm;
import com.colonysmp.core.TxnType;
import com.colonysmp.util.Keys;
import com.colonysmp.util.Messages;
import com.colonysmp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tiers: Camp, Village, Town, City, Kingdom (the Town Hall's levels 1-5) and Empire (comes with empires). Each tier
 * has its needs and costs in config (tiers.levels), unlocks jobs and building types, and a tier-up is one click once
 * the needs are met: title, sound, fireworks and a line for the whole server. Behind features.tiers: when it's off
 * the Town Hall upgrades the old way and nothing is locked.
 */
public final class Tiers {

    public static final int CAMP = 1, KINGDOM = 5, EMPIRE = 6;

    /** One tier's needs (to reach it from the one below) and what it opens up. */
    public record Tier(int n, String name, String colour, int settlers, int maxSettlers, Map<BuildingType, Integer> buildings,
                       long money, Map<String, Integer> goods, Set<Job> jobs, Set<BuildingType> types, List<String> unlocks) {
        public String coloured() {
            return colour + name;
        }
    }

    /** One line of the tier-up checklist. */
    public record Check(String label, boolean done) {}

    private static final String[] NAMES = {"", "Camp", "Village", "Town", "City", "Kingdom", "Empire"};

    private final ColonySMP plugin;
    private final Tier[] tiers = new Tier[EMPIRE + 1];
    private final Map<Job, Integer> jobTier = new EnumMap<>(Job.class);
    private final Map<BuildingType, Integer> typeTier = new EnumMap<>(BuildingType.class);
    private boolean announce = true;
    private int fireworks = 4;

    public Tiers(ColonySMP plugin) {
        this.plugin = plugin;
        reload();
    }

    public boolean on() {
        return plugin.settings().feature("tiers");
    }

    /** Reads tiers: from config.yml. A tier missing from the server's file comes from the jar's copy. */
    public void reload() {
        FileConfiguration cfg = plugin.getConfig();
        var def = cfg.getDefaults();
        announce = cfg.getBoolean("tiers.announce");
        fireworks = Math.max(0, Math.min(12, cfg.getInt("tiers.fireworks")));
        jobTier.clear();
        typeTier.clear();
        for (int n = CAMP; n <= EMPIRE; n++) {
            String path = "tiers.levels." + n;
            ConfigurationSection s = cfg.isSet(path) ? cfg.getConfigurationSection(path) : def == null ? null : def.getConfigurationSection(path);
            tiers[n] = read(n, s);
            for (Job j : tiers[n].jobs()) jobTier.put(j, n);
            for (BuildingType t : tiers[n].types()) typeTier.put(t, n);
        }
    }

    private Tier read(int n, ConfigurationSection s) {
        if (s == null) return new Tier(n, NAMES[n], "<white>", 0, 0, Map.of(), 0, Map.of(), Set.of(), Set.of(), List.of());
        Map<BuildingType, Integer> buildings = new EnumMap<>(BuildingType.class);
        ConfigurationSection bs = s.getConfigurationSection("buildings");
        if (bs != null) {
            for (String k : bs.getKeys(false)) {
                BuildingType t = BuildingType.parse(k);
                if (t == null) plugin.getLogger().warning("tiers.levels." + n + ".buildings: no building type called " + k);
                else buildings.put(t, Math.max(1, bs.getInt(k)));
            }
        }
        Map<String, Integer> goods = new LinkedHashMap<>();
        ConfigurationSection gs = s.getConfigurationSection("goods");
        if (gs != null) for (String k : gs.getKeys(false)) goods.put(k.toLowerCase(Locale.ROOT), Math.max(1, gs.getInt(k)));
        Set<Job> jobs = EnumSet.noneOf(Job.class);
        for (String k : s.getStringList("jobs")) {
            Job j = Job.parse(k);
            if (j == null) plugin.getLogger().warning("tiers.levels." + n + ".jobs: no job called " + k);
            else jobs.add(j);
        }
        Set<BuildingType> types = EnumSet.noneOf(BuildingType.class);
        for (String k : s.getStringList("building-types")) {
            BuildingType t = BuildingType.parse(k);
            if (t == null) plugin.getLogger().warning("tiers.levels." + n + ".building-types: no building type called " + k);
            else types.add(t);
        }
        return new Tier(n, s.getString("name", NAMES[n]), s.getString("colour", "<white>"), Math.max(0, s.getInt("settlers")),
                Math.max(0, s.getInt("max-settlers")), buildings, Math.max(0, s.getLong("money")), goods, jobs, types, s.getStringList("unlocks"));
    }

    // ───────────── reading ─────────────

    public Tier get(int n) {
        return tiers[Math.max(CAMP, Math.min(EMPIRE, n))];
    }

    /** The colony's tier (1-5; 6 once empires exist). */
    public int tier(Colony c) {
        return Math.max(CAMP, Math.min(KINGDOM, c.level()));
    }

    public Tier of(Colony c) {
        return get(tier(c));
    }

    /** "the Village of Oakmere". */
    public String title(Colony c) {
        return Messages.compose("tiers.title", "tier", of(c).name(), "colony", Text.esc(c.name));
    }

    /** The tier a job needs: its own entry, or the lowest tier that opens a building where it can work. */
    public int jobTier(Job j) {
        int own = jobTier.getOrDefault(j, CAMP);
        if (!j.needsWorkplace()) return own;
        int place = Integer.MAX_VALUE;
        for (BuildingType t : BuildingType.values()) {
            if (t.slots(j, t.maxLevel) <= 0) continue;
            place = Math.min(place, typeTier(t));
        }
        return Math.max(own, place == Integer.MAX_VALUE ? CAMP : place);
    }

    public int typeTier(BuildingType t) {
        return typeTier.getOrDefault(t, CAMP);
    }

    /** Why this colony can't take on a job yet, or null. */
    public String jobLocked(Colony c, Job j) {
        if (!on() || j == Job.UNEMPLOYED || j == Job.STUDENT) return null;
        int need = jobTier(j);
        return tier(c) >= need ? null : Messages.get("tiers.locked-job", "job", j.display, "tier", get(need).name());
    }

    /** Why this colony can't register this kind of building yet, or null. */
    public String typeLocked(Colony c, BuildingType t) {
        if (!on()) return null;
        int need = typeTier(t);
        return tier(c) >= need ? null : Messages.get("tiers.locked-building", "building", t.display, "tier", get(need).name());
    }

    /** Why the colony can't take one more settler at this tier, or null. */
    public String full(Colony c) {
        if (!on()) return null;
        int cap = of(c).maxSettlers();
        if (cap <= 0 || c.citizens.size() < cap) return null;
        int next = tier(c) + 1;
        return Messages.get("tiers.full", "tier", of(c).name(), "max", cap, "next", next <= KINGDOM ? get(next).name() : of(c).name());
    }

    // ───────────── tiering up ─────────────

    /** The checklist for the next tier (empty at the top). */
    public List<Check> checks(Colony c) {
        List<Check> out = new ArrayList<>();
        int next = tier(c) + 1;
        if (next > KINGDOM) return out;
        Tier t = get(next);
        int pop = c.population();
        out.add(new Check(Messages.get("tiers.check.settlers", "have", pop, "need", t.settlers()), pop >= t.settlers()));
        for (Map.Entry<BuildingType, Integer> e : t.buildings().entrySet()) {
            int have = c.valid(e.getKey()).size();
            out.add(new Check(Messages.get("tiers.check.building", "building", e.getKey().display, "have", have, "need", e.getValue()), have >= e.getValue()));
        }
        long bal = c.treasury.balance();
        if (t.money() > 0) out.add(new Check(Messages.compose("tiers.check.money", "need", Text.value(t.money()), "have", Text.value(bal)), bal >= t.money()));
        for (Map.Entry<String, Integer> e : t.goods().entrySet()) {
            long have = available(c, e.getKey());
            out.add(new Check(Messages.get("tiers.check.good", "good", goodName(e.getKey()), "have", have, "need", e.getValue()), have >= e.getValue()));
        }
        return out;
    }

    /** Why the colony can't move up a tier now, or null. */
    public String canTierUp(Colony c) {
        int next = tier(c) + 1;
        if (next > KINGDOM) return Messages.get(next == EMPIRE && tier(c) == KINGDOM ? "tiers.empire-later" : "tiers.top");
        if (c.townHall() == null) return Messages.get("tiers.no-hall");
        for (Check ch : checks(c)) if (!ch.done()) return Messages.compose("tiers.not-yet", "tier", get(next).name(), "what", ch.label());
        return null;
    }

    /** Moves the colony up a tier: pays, raises the Town Hall and celebrates. Returns why not, or null. */
    public String tierUp(Player p, Colony c) {
        if (p != null && !plugin.access().can(p, c, Perm.MANAGE_BUILDINGS)) return Messages.get("tiers.no-permission");
        String why = canTierUp(c);
        if (why != null) return why;
        Building th = c.townHall();
        int old = tier(c), next = old + 1;
        Tier t = get(next);
        long day = plugin.sim().day(c);
        Map<String, Long> goods = plugin.buildings().concreteGoods(c, t.goods());
        if (goods == null) return Messages.get("tiers.goods-gone");
        var res = goods.isEmpty() ? null : c.storage.reserve("tier:" + next, goods, System.currentTimeMillis());
        if (!goods.isEmpty() && res == null) return Messages.get("tiers.goods-promised");
        if (t.money() > 0 && c.treasury.debit(t.money(), TxnType.CONSTRUCTION, "Became " + Text.a(t.name()), System.currentTimeMillis(), day) == null) {
            if (res != null) c.storage.release(res.id);
            return Messages.get("tiers.no-money");
        }
        if (res != null) c.storage.consume(res.id);
        th.level = next;
        th.stock.setCapacity(th.type.stockSlots(th.level), 0);
        c.log(ColonyEvent.Type.CONSTRUCTION, day, Text.plainOf(Messages.compose("tiers.log", "colony", Text.esc(c.name), "tier", t.name(),
                "by", p == null ? "" : Text.esc(p.getName()))));
        plugin.buildings().changed(c);
        plugin.requestSave();
        celebrate(c, old, next);
        return null;
    }

    /** Title, sound and fireworks for the members, and a line for everyone. */
    public void celebrate(Colony c, int from, int to) {
        Tier a = get(from), b = get(to);
        String title = Messages.compose("tiers.up-title", "tier", b.coloured());
        String sub = Messages.compose("tiers.up-subtitle", "colony", Text.esc(c.name));
        for (Player m : c.onlineMembers()) {
            Text.title(m, title, sub, 10, 80, 20);
            m.playSound(m.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            m.playSound(m.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 0.8f);
            for (String line : b.unlocks()) Text.raw(m, Messages.compose("tiers.unlock-line", "text", line));
        }
        if (announce) {
            Bukkit.broadcast(Text.mm(Messages.compose("tiers.announce", "old", a.name(), "colony", Text.esc(c.name), "tier", b.coloured())));
        }
        fireworks(c);
    }

    private void fireworks(Colony c) {
        World w = c.world();
        Location core = c.coreLocation();
        if (w == null || core == null || fireworks <= 0 || !w.isChunkLoaded(core.getBlockX() >> 4, core.getBlockZ() >> 4)) return;
        Color[] colors = {Color.YELLOW, Color.ORANGE, Color.WHITE, Color.AQUA, Color.LIME};
        for (int i = 0; i < fireworks; i++) {
            int n = i;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                var r = java.util.concurrent.ThreadLocalRandom.current();
                Location at = core.clone().add(r.nextInt(-5, 6), 8 + r.nextInt(6), r.nextInt(-5, 6));
                FireworkEffect fx = FireworkEffect.builder().with(n % 2 == 0 ? FireworkEffect.Type.BALL_LARGE : FireworkEffect.Type.STAR)
                        .withColor(colors[r.nextInt(colors.length)]).withFade(colors[r.nextInt(colors.length)]).trail(true).flicker(true).build();
                try {
                    Firework fw = w.spawn(at, Firework.class, CreatureSpawnEvent.SpawnReason.CUSTOM, f -> {
                        FireworkMeta meta = f.getFireworkMeta();
                        meta.addEffect(fx);
                        meta.setPower(0);
                        f.setFireworkMeta(meta);
                        f.getPersistentDataContainer().set(Keys.MERCHANT, PersistentDataType.STRING, "festival");
                    });
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (fw.isValid()) fw.detonate();
                    }, 2L);
                } catch (IllegalArgumentException ignored) {
                    // no fireworks here
                }
            }, 10L * i);
        }
    }

    // ───────────── helpers ─────────────

    private static long available(Colony c, String good) {
        if (Recipes.isGroup(good)) {
            long have = 0;
            for (String m : Recipes.members(good)) have += c.storage.available(m);
            return have;
        }
        return c.storage.available(good);
    }

    private static String goodName(String good) {
        return Recipes.isGroup(good) ? Recipes.groupName(good) : Goods.get(good).name();
    }
}
