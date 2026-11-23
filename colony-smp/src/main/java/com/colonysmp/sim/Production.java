package com.colonysmp.sim;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Category;
import com.colonysmp.core.Good;
import com.colonysmp.core.Goods;
import com.colonysmp.core.Ledger;
import com.colonysmp.core.MilitaryClass;
import com.colonysmp.core.Recipe;
import com.colonysmp.core.Recipes;
import com.colonysmp.core.Reservation;
import com.colonysmp.core.Skill;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Job;
import com.colonysmp.model.LifeStage;
import com.colonysmp.model.Policies;
import com.colonysmp.npc.Fishing;
import com.colonysmp.util.Tools;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Turns work into goods. Workplaces with recipes pick what's most needed (stock targets and the colony's
 * priority), reserve the inputs in their local stock, and finish a batch when enough work has gone in. Farms,
 * pens and fisheries have their own models for when nobody is there in body. Everything is only made from real
 * inputs; nothing appears from nothing, and nothing is made that there's no room to store.
 */
public final class Production {

    /** Work hours for one farm tile (harvest and replant). */
    private static final double TILE_HOURS = 0.03;

    private final ColonySMP plugin;

    Production(ColonySMP plugin) {
        this.plugin = plugin;
    }

    // ───────────── work from citizens ─────────────

    /** A citizen's work (in efficiency-weighted hours) goes to their workplace. */
    public void contribute(Colony col, Citizen c, double units, double hours) {
        Building b = col.building(c.workplace);
        if (c.job.needsWorkplace() && (b == null || !b.valid)) {
            c.waiting = b == null ? "No workplace" : b.label() + " failed its inspection: " + b.problem;
            return;
        }
        if (b != null) b.staffedHours += hours;
        switch (c.job.background) {
            case FARM -> farm(col, b, c, units);
            case PEN -> pen(col, b, c, hours, units);
            case FISHING -> fish(col, b, c, units);
            case RECIPE -> work(col, b, c, units);
            case SERVICE -> {
                xp(c, hours);
                if (c.job == Job.ENGINEER) plugin.sim().finances().maintain(col, hours * plugin.sim().citizens().efficiency(col, c));
            }
            case HAUL -> {
                plugin.sim().logistics().courierHours(col, hours);
                xp(c, hours);
            }
            case TEACH -> xp(c, hours);
            case STUDY -> plugin.sim().population().study(col, c, hours);
            case HEAL -> {
                xp(c, hours);
                if (!plugin.sim().health().patients(col)) work(col, b, c, units);
            }
            // (citizens in body gain their skill as they work; here it comes with the hours)
            case RESEARCH -> {
                if (plugin.sim().academy().work(col, c, units)) xp(c, hours);
            }
            case ENTERTAIN, GROW -> xp(c, hours);
            case ENCHANT -> {
                if (plugin.sim().trades().enchant(col, b, c, units)) xp(c, hours);
            }
            case BEES -> plugin.sim().trades().bees(col, b, c, hours, units);
            case GUARD -> plugin.sim().order().guardHours(col, hours);
            case TRAIN -> plugin.military().train(col, c, hours);
            case NONE -> {
                if (c.job.employed()) c.waiting = "Needs the area loaded to work (" + c.job.display.toLowerCase() + ")";
            }
        }
    }

    private void xp(Citizen c, double hours) {
        if (c.job.skill == null) return;
        int lvl = c.skills.gain(c.job.skill, hours * 10 * c.trait.learning());
        if (lvl > 0) {
            Colony col = plugin.colonies().colonyOf(c);
            c.remember(col == null ? 0 : plugin.sim().day(col), "Reached " + c.job.skill.display + " level " + lvl);
        }
    }

    /** Background models that move with time rather than with work. */
    void background(Colony col, double hours, long ticks, long dayTime) {
        World w = col.world();
        double tended = 1 + plugin.sim().trades().growthBonus(col, plugin.sim().day(col));
        for (Building b : col.buildings.values()) {
            if (!b.valid || b.type != BuildingType.FARM) continue;
            // crops only grow (in this model) while the world isn't growing them itself
            if (worldGrows(w, b)) continue;
            b.growth = Math.min(1.5, b.growth + hours / 24 * (1 + 0.1 * (b.level - 1)) * tended);
        }
    }

    // ───────────── farms, pens and fisheries ─────────────

    /** Vanilla only grows crops in loaded chunks with a player within 128 blocks. */
    private static boolean worldGrows(World w, Building b) {
        if (w == null || b.anchor == null || !b.anchor.loaded(w)) return false;
        for (Player p : w.getPlayers()) {
            double dx = p.getLocation().getX() - b.anchor.x(), dz = p.getLocation().getZ() - b.anchor.z();
            if (dx * dx + dz * dz < 128 * 128) return true;
        }
        return false;
    }

    private void farm(Colony col, Building b, Citizen c, double units) {
        if (worldGrows(col.world(), b)) {
            c.waiting = "Waiting to work the fields in person";
            return;
        }
        int tiles = b.tiles.size();
        if (tiles == 0) return;
        double ripe = Math.min(1, b.growth) * tiles;
        double canDo = units / TILE_HOURS;
        int harvested = (int) Math.min(ripe, canDo);
        if (harvested <= 0) {
            c.waiting = null;
            return;
        }
        Map<String, Double> mix = cropMix(b);
        double bonus = (1 + 0.02 * c.level(Skill.FARMING)) * (1 + plugin.sim().trades().yieldBonus(col, plugin.sim().day(col)));
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : mix.entrySet()) {
            double n = harvested * e.getValue();
            switch (e.getKey()) {
                case "carrots" -> out.merge("carrot", round(n * 1.7 * bonus), Long::sum);
                case "potatoes" -> out.merge("potato", round(n * 1.7 * bonus), Long::sum);
                case "beetroots" -> {
                    out.merge("beetroot", round(n * bonus), Long::sum);
                    out.merge("beetroot_seeds", round(n * 0.5), Long::sum);
                }
                case "nether_wart" -> out.merge("nether_wart", round(n * 1.5 * bonus), Long::sum);
                default -> {
                    out.merge("wheat", round(n * bonus), Long::sum);
                    out.merge("wheat_seeds", round(n * 0.7), Long::sum);
                }
            }
        }
        int stored = 0;
        for (Map.Entry<String, Long> e : out.entrySet()) {
            long added = b.stock.add(e.getKey(), e.getValue());
            stored += added;
            if (added < e.getValue()) c.waiting = b.label() + " is full: the harvest is waiting for haulers";
        }
        if (stored == 0) return;
        b.growth = Math.max(0, b.growth - harvested / (double) tiles);
        col.producedToday += stored;
        c.skills.gain(Skill.FARMING, harvested * TILE_HOURS * 10 * c.trait.learning());
        c.waiting = null;
    }

    private static long round(double v) {
        long n = (long) Math.floor(v);
        if (ThreadLocalRandom.current().nextDouble() < v - n) n++;
        return n;
    }

    /** Crop shares on a farm: the preferred crop, or what was growing last time anyone looked. */
    private static Map<String, Double> cropMix(Building b) {
        Map<String, Double> mix = new LinkedHashMap<>();
        if (b.crop != null) {
            mix.put(b.crop, 1.0);
            return mix;
        }
        int total = 0;
        for (int n : b.counts.values()) total += n;
        if (total == 0) {
            mix.put("wheat", 0.5);
            mix.put("carrots", 0.25);
            mix.put("potatoes", 0.25);
            return mix;
        }
        for (Map.Entry<String, Integer> e : b.counts.entrySet()) mix.put(e.getKey(), e.getValue() / (double) total);
        return mix;
    }

    private void pen(Colony col, Building b, Citizen c, double hours, double units) {
        int animals = 0;
        for (int n : b.counts.values()) animals += n;
        if (animals == 0) {
            c.waiting = "No animals counted in " + b.label() + " (lead some in while the area is loaded)";
            return;
        }
        double days = hours / 24;
        double care = Math.min(1, units / Math.max(0.01, animals * 0.1 * days));
        // feed: wheat for cows and sheep, seeds for chickens, carrots for pigs
        double fedShare = feed(b, "wheat", (b.counts.getOrDefault("cow", 0) + b.counts.getOrDefault("sheep", 0)) * 0.25 * days)
                * 0.5 + feed(b, "wheat_seeds", b.counts.getOrDefault("chicken", 0) * 0.25 * days) * 0.3
                + feed(b, "carrot", b.counts.getOrDefault("pig", 0) * 0.25 * days) * 0.2;
        double rate = care * (0.5 + 0.5 * Math.min(1, fedShare)) * (1 + 0.1 * (b.level - 1)) * (1 + 0.02 * c.level(Skill.HUSBANDRY));
        b.produce += days * rate;
        if (b.produce < 0.1) return;
        double d = b.produce;
        b.produce = 0;
        long eggs = round(b.counts.getOrDefault("chicken", 0) * 2 * d);
        long wool = round(b.counts.getOrDefault("sheep", 0) * 1 * d);
        long milk = round(b.counts.getOrDefault("cow", 0) * 1 * d);
        long made = b.stock.add("egg", eggs) + b.stock.add("white_wool", wool);
        if (milk > 0) {
            long buckets = Math.min(milk, b.stock.available("bucket"));
            if (buckets > 0 && b.stock.takeExact("bucket", buckets)) made += b.stock.add("milk_bucket", buckets);
        }
        col.producedToday += made;
        c.skills.gain(Skill.HUSBANDRY, hours * 10 * c.trait.learning());
        c.waiting = null;
    }

    /** Uses feed from a pen's stock; returns the share of the need that was met. */
    private static double feed(Building b, String good, double need) {
        if (need <= 0) return 1;
        long want = (long) Math.ceil(need);
        long got = b.stock.take(good, want);
        return got / (double) want;
    }

    private void fish(Colony col, Building b, Citizen c, double units) {
        double catches = units * 4 * (1 + 0.1 * (b.level - 1));
        long n = round(catches);
        int stored = 0;
        for (int i = 0; i < n; i++) {
            ItemStack loot = Fishing.loot(ThreadLocalRandom.current());
            String good = com.colonysmp.util.ItemGoods.goodOf(loot);
            if (good == null) {
                if (col.equipment.add(loot)) stored++;
                continue;
            }
            if (b.stock.add(good, loot.getAmount()) > 0) stored++;
            else {
                c.waiting = b.label() + " is full: the catch is waiting for haulers";
                break;
            }
            if (c.tool != null && Tools.wear(c.tool, 1)) c.tool = null;
        }
        col.producedToday += stored;
        c.skills.gain(Skill.FISHING, units * 10 * c.trait.learning());
    }

    // ───────────── recipes ─────────────

    /** Adds work to a workplace's current batch. Returns the work used (0 when there's nothing to do). */
    public double work(Colony col, Building b, Citizen c, double units) {
        if (b == null || !b.valid) return 0;
        plan(col, b);
        Recipe r = Recipes.get(b.recipe);
        if (r == null || b.reservation == null) {
            c.waiting = b.waiting;
            return 0;
        }
        c.waiting = null;
        b.progress += units;
        if (r.skill != null) {
            int lvl = c.skills.gain(r.skill, units * 10 * c.trait.learning());
            if (lvl > 0) c.remember(plugin.sim().day(col), "Reached " + r.skill.display + " level " + lvl);
        }
        if (b.progress >= r.hours) complete(col, b, r, c);
        return units;
    }

    /** Picks and reserves the next batch if none is under way. */
    public void plan(Colony col, Building b) {
        if (b.recipe != null && b.reservation != null && b.stock.reservation(b.reservation) != null && Recipes.get(b.recipe) != null) return;
        if (b.reservation != null) b.stock.release(b.reservation);
        b.recipe = null;
        b.reservation = null;
        b.progress = 0;
        List<Recipe> ranked = ranked(col, b);
        if (ranked.isEmpty()) {
            b.waiting = idleReason(col, b);
            return;
        }
        for (Recipe r : ranked) {
            Map<String, Long> goods = Recipes.resolve(r, b.stock, k -> true);
            if (goods == null) continue;
            Reservation res = b.stock.reserve("prod:" + b.id, goods, System.currentTimeMillis());
            if (res == null) continue;
            b.recipe = r.id;
            b.reservation = res.id;
            b.waiting = null;
            return;
        }
        Recipe best = ranked.get(0);
        String missing = Recipes.missing(best, combined(col, b), usable(col));
        b.waiting = missing == null ? "Waiting for supplies to be carried in" : "Waiting for " + missing + " (" + best.name.toLowerCase() + ")";
    }

    private String idleReason(Colony col, Building b) {
        List<Recipe> all = Recipes.of(b.type.station());
        if (all.isEmpty()) return "Nothing to make here";
        if (col.storage.full()) return "Colony storage is full";
        return "Nothing needed right now (stocks are at their targets)";
    }

    /** Central storage plus the workplace's own stock, for "what's missing" messages. */
    private static Ledger combined(Colony col, Building b) {
        Ledger l = new Ledger("tmp");
        for (Map.Entry<String, Long> e : col.storage.contents().entrySet()) l.force(e.getKey(), col.storage.available(e.getKey()));
        for (Map.Entry<String, Long> e : b.stock.contents().entrySet()) l.force(e.getKey(), b.stock.available(e.getKey()));
        return l;
    }

    private void complete(Colony col, Building b, Recipe r, Citizen c) {
        // room for every output first: nothing is made that can't be kept
        int equipment = 0;
        for (Map.Entry<String, Integer> e : r.outputs.entrySet()) {
            if (isEquipment(e.getKey())) equipment += e.getValue();
            else if (b.stock.room(e.getKey()) < e.getValue()) {
                b.waiting = b.label() + " is full: waiting for haulers to take its goods";
                b.progress = r.hours;
                return;
            }
        }
        if (equipment > 0 && col.equipment.size() + equipment > col.equipment.capacity()) {
            b.waiting = "The armoury is full";
            b.progress = r.hours;
            return;
        }
        if (!b.stock.consume(b.reservation)) {
            b.recipe = null;
            b.reservation = null;
            return;
        }
        long made = 0;
        int level = r.skill == null ? 0 : c.level(r.skill);
        for (Map.Entry<String, Integer> e : r.outputs.entrySet()) {
            int n = e.getValue();
            if (e.getKey().equals(r.primary()) && ThreadLocalRandom.current().nextDouble() < level * 0.02) n++;
            if (isEquipment(e.getKey())) {
                Material m = Material.matchMaterial(e.getKey().toUpperCase(java.util.Locale.ROOT));
                for (int i = 0; i < n && m != null; i++) col.equipment.force(new ItemStack(m));
                if (m != null) col.log(ColonyEvent.Type.PRODUCTION, plugin.sim().day(col), c.name + " made " + n + " " + Goods.get(e.getKey()).name() + " at " + b.label() + ".");
            } else {
                b.stock.force(e.getKey(), n);
            }
            made += n;
        }
        col.producedToday += made;
        b.progress = Math.max(0, b.progress - r.hours);
        b.recipe = null;
        b.reservation = null;
        plan(col, b);
    }

    public static boolean isEquipment(String key) {
        Material m = Material.matchMaterial(key.toUpperCase(java.util.Locale.ROOT));
        return m != null && m.getMaxDurability() > 0;
    }

    // ───────────── what to make ─────────────

    /** The recipes a workplace could usefully make now, most needed first. */
    public List<Recipe> ranked(Colony col, Building b) {
        List<Recipe> out = new ArrayList<>();
        for (Recipe r : Recipes.of(b.type.station())) {
            if (r.minLevel > b.level || b.disabled.contains(r.id)) continue;
            if (!wanted(col, b, r)) continue;
            out.add(r);
        }
        out.sort(Comparator.comparingDouble(r -> score(col, b, r)));
        return out;
    }

    private boolean wanted(Colony col, Building b, Recipe r) {
        String key = r.primary();
        if (isEquipment(key)) return demand(col, key) > 0;
        if (r.onDemand) return demand(col, key) > 0;
        if (col.storage.room(key) <= 0) return false;
        return stock(col, key) < max(col, key);
    }

    private double score(Colony col, Building b, Recipe r) {
        String key = r.primary();
        double s;
        if (isEquipment(key) || r.onDemand) s = -2 + 0.1 / Math.max(1, demand(col, key));
        else {
            long have = stock(col, key);
            s = have / (double) Math.max(1, max(col, key));
            if (have < min(col, key)) s -= 1;
        }
        Good g = Goods.get(key);
        Policies.Priority p = col.policies.priority;
        if (p == Policies.Priority.FOOD && g.edible() || p == Policies.Priority.MATERIALS && material(g.category())
                || p == Policies.Priority.MILITARY && (g.category() == Category.WEAPON || g.category() == Category.ARMOR || g.category() == Category.AMMO)
                || p == Policies.Priority.TRADE && g.value() >= 4) s -= 0.4;
        return s;
    }

    private static boolean material(Category c) {
        return c == Category.STONE || c == Category.WOOD || c == Category.METAL || c == Category.MATERIAL;
    }

    /** All of a good the colony has: central storage and every workplace's stock. */
    public long stock(Colony col, String key) {
        long n = col.storage.get(key);
        for (Building b : col.buildings.values()) n += b.stock.get(key);
        return n;
    }

    public long max(Colony col, String key) {
        int[] t = col.target(key);
        if (t != null && t[1] > 0) return t[1];
        Good g = Goods.get(key);
        int pop = Math.max(2, col.population());
        if (g.edible() && g.quality() >= 1) return Math.max(64, pop * 14L);
        if (g.category() == Category.SEED) return 128;
        if (g.category() == Category.AMMO) return 64 + 32L * plugin.military().soldiers(col);
        if (g.category() == Category.MEDICAL) return Math.max(8, pop / 2);
        // crushed stone is a by-product: a little on hand is plenty
        if (key.equals("gravel") || key.equals("sand") || key.equals("flint")) return 64;
        return 256;
    }

    public long min(Colony col, String key) {
        int[] t = col.target(key);
        return t == null ? 0 : t[0];
    }

    /** Goods the colony is willing to spend from central storage (it keeps its minimum stock targets). */
    public Predicate<String> usable(Colony col) {
        return k -> col.storage.available(k) > min(col, k);
    }

    // ───────────── demand for tools and arms ─────────────

    /** How many more of an item (tools, weapons, armour, ammunition) the colony needs. */
    public int demand(Colony col, String key) {
        Material m = Material.matchMaterial(key.toUpperCase(java.util.Locale.ROOT));
        if (m == null) return 0;
        Tools.Kind kind = Tools.kind(m);
        int need = 0, users = 0;
        for (Citizen c : col.citizens.values()) {
            if (!c.status.free()) continue;
            switch (kind) {
                case HOE, AXE, PICKAXE, SHEARS, FISHING_ROD, SHOVEL -> {
                    if (c.job.tool == kind) {
                        users++;
                        if (c.tool == null || Tools.kind(c.tool) != kind) need++;
                    }
                    if (kind == Tools.Kind.AXE && fighter(c, MilitaryClass.Arms.MELEE) && c.weapon == null) need++;
                }
                case SWORD, TRIDENT, MACE -> {
                    if (fighter(c, MilitaryClass.Arms.MELEE) && c.weapon == null) need++;
                }
                case BOW -> {
                    if (fighter(c, MilitaryClass.Arms.BOW) && c.ranged == null) need++;
                }
                case CROSSBOW -> {
                    if (fighter(c, MilitaryClass.Arms.CROSSBOW) && c.ranged == null) need++;
                }
                case SHIELD -> {
                    if (fighter(c, MilitaryClass.Arms.SHIELD) && c.shield == null) need++;
                }
                case BOOTS, LEGGINGS, CHESTPLATE, HELMET -> {
                    int slot = switch (kind) {
                        case BOOTS -> 0;
                        case LEGGINGS -> 1;
                        case CHESTPLATE -> 2;
                        default -> 3;
                    };
                    if (c.job.military() && c.armor[slot] == null) need++;
                }
                default -> {
                }
            }
        }
        if (users > 0) need++; // a spare
        need -= col.equipment.count(it -> Tools.kind(it) == kind);
        return Math.max(0, need);
    }

    private static boolean fighter(Citizen c, MilitaryClass.Arms arms) {
        if (c.job == Job.GUARD) return arms == MilitaryClass.Arms.MELEE;
        return c.job == Job.SOLDIER && c.militaryClass != null && c.militaryClass.arms.contains(arms);
    }

    // ───────────── what workplaces want carried ─────────────

    /**
     * Goods a workplace wants brought from central storage: the inputs of its most needed batch that aren't
     * there yet, for up to two batches; seeds for farms, feed and buckets for pens.
     */
    public Map<String, Long> wants(Colony col, Building b) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (!b.valid) return out;
        switch (b.type) {
            case FARM -> {
                Map<String, Double> mix = cropMix(b);
                for (String crop : mix.keySet()) {
                    String seed = switch (crop) {
                        case "carrots" -> "carrot";
                        case "potatoes" -> "potato";
                        case "beetroots" -> "beetroot_seeds";
                        case "nether_wart" -> "nether_wart";
                        default -> "wheat_seeds";
                    };
                    long want = 32 - b.stock.get(seed);
                    if (want > 0) out.put(seed, want);
                }
                return out;
            }
            case ANIMAL_PEN, STABLES -> {
                int grazers = b.counts.getOrDefault("cow", 0) + b.counts.getOrDefault("sheep", 0) + b.counts.getOrDefault("horse", 0);
                if (grazers > 0 && b.stock.get("wheat") < 32) out.put("wheat", 32 - b.stock.get("wheat"));
                if (b.counts.getOrDefault("chicken", 0) > 0 && b.stock.get("wheat_seeds") < 16) out.put("wheat_seeds", 16 - b.stock.get("wheat_seeds"));
                if (b.counts.getOrDefault("pig", 0) > 0 && b.stock.get("carrot") < 16) out.put("carrot", 16 - b.stock.get("carrot"));
                if (b.counts.getOrDefault("cow", 0) > 0 && b.stock.get("bucket") < 4) out.put("bucket", 4 - b.stock.get("bucket"));
                return out;
            }
            case RESTAURANT -> {
                // prepared food for the tables
                long have = 0;
                for (Map.Entry<String, Long> e : b.stock.contents().entrySet()) if (Goods.get(e.getKey()).quality() >= 2) have += e.getValue();
                long want = Math.max(0, 8L * b.level * 4 - have);
                List<String> foods = new ArrayList<>();
                for (Map.Entry<String, Long> e : col.storage.contents().entrySet()) {
                    Good g = Goods.get(e.getKey());
                    if (g.quality() >= 2 && g.nutrition() > 0 && plugin.sim().citizens().edible(g.key())) foods.add(g.key());
                }
                foods.sort((x, y) -> Integer.compare(Goods.get(y).quality(), Goods.get(x).quality()));
                for (String f : foods) {
                    if (want <= 0) break;
                    long n = Math.min(want, col.storage.available(f));
                    if (n > 0) out.put(f, n);
                    want -= n;
                }
                return out;
            }
            case CLINIC -> {
                if (b.stock.get(Goods.MEDICINE) < 4 && col.storage.available(Goods.MEDICINE) > 0) out.put(Goods.MEDICINE, 4 - b.stock.get(Goods.MEDICINE));
            }
            case ENCHANTERS_TOWER -> {
                long want = Math.min(12 - b.stock.get(Trades.LAPIS), col.storage.available(Trades.LAPIS));
                if (want > 0) out.put(Trades.LAPIS, want);
                return out;
            }
            case APIARY -> {
                long want = Math.min(8 - b.stock.get(Trades.BOTTLE), col.storage.available(Trades.BOTTLE));
                if (want > 0) out.put(Trades.BOTTLE, want);
                return out;
            }
            default -> {
            }
        }
        if (Recipes.of(b.type.station()).isEmpty()) return out;
        Predicate<String> usable = usable(col);
        int batches = 0;
        for (Recipe r : ranked(col, b)) {
            if (batches >= 2) break;
            Map<String, Long> goods = Recipes.resolve(r, centralPlusLocal(col, b), usable.or(k -> b.stock.available(k) > 0));
            if (goods == null) continue;
            batches++;
            for (Map.Entry<String, Long> e : goods.entrySet()) {
                long missing = e.getValue() * 2 - b.stock.available(e.getKey()) - out.getOrDefault(e.getKey(), 0L);
                if (missing > 0) out.merge(e.getKey(), Math.min(missing, col.storage.available(e.getKey())), Long::sum);
            }
            out.values().removeIf(v -> v <= 0);
            if (!out.isEmpty()) break;
        }
        return out;
    }

    private static Ledger centralPlusLocal(Colony col, Building b) {
        return combined(col, b);
    }

    /** How much of a good a workplace keeps for its own work (the rest is carried to storage). */
    public long keep(Colony col, Building b, String key) {
        switch (b.type) {
            case FARM -> {
                return key.endsWith("_seeds") || key.equals("carrot") || key.equals("potato") || key.equals("nether_wart") ? 32 : 0;
            }
            case ANIMAL_PEN, STABLES -> {
                return key.equals("wheat") || key.equals("wheat_seeds") || key.equals("carrot") || key.equals("bucket") ? 32 : 0;
            }
            case RESTAURANT -> {
                return Goods.get(key).quality() >= 2 ? 64 : 0;
            }
            case CLINIC -> {
                if (key.equals(Goods.MEDICINE)) return 8;
            }
            case ENCHANTERS_TOWER -> {
                return key.equals(Trades.LAPIS) ? 16 : 0;
            }
            case APIARY -> {
                return key.equals(Trades.BOTTLE) ? 16 : 0;
            }
            default -> {
            }
        }
        long keep = 0;
        for (Recipe r : Recipes.of(b.type.station())) {
            if (r.minLevel > b.level || b.disabled.contains(r.id)) continue;
            for (Map.Entry<String, Integer> e : r.inputs.entrySet()) {
                String k = e.getKey();
                boolean match = k.equals(key) || Recipes.isGroup(k) && Recipes.members(k).contains(key)
                        || k.equals(Recipe.FUEL) && Recipes.FUEL_UNITS.containsKey(key);
                if (match) keep = Math.max(keep, k.equals(Recipe.FUEL) ? 16 : e.getValue() * 2L);
            }
        }
        return keep;
    }

    /** Goods in a workplace's stock that should go to central storage. */
    public Map<String, Long> surplus(Colony col, Building b) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : b.stock.contents().entrySet()) {
            long extra = b.stock.available(e.getKey()) - keep(col, b, e.getKey());
            if (extra > 0) out.put(e.getKey(), extra);
        }
        return out;
    }

    /** Recipe goods that no workplace uses any more are left alone; this lists every good the workplace may hold. */
    public Set<String> inputs(Building b) {
        Set<String> out = new HashSet<>();
        for (Recipe r : Recipes.of(b.type.station())) {
            for (String k : r.inputs.keySet()) {
                if (Recipes.isGroup(k)) out.addAll(Recipes.members(k));
                else if (k.equals(Recipe.FUEL)) out.addAll(Recipes.FUEL_UNITS.keySet());
                else out.add(k);
            }
        }
        return out;
    }

    /** Is a citizen old enough and healthy enough for a job? (for job assignment) */
    public boolean suitable(Citizen c, Job j, LifeStage stage) {
        if (stage == LifeStage.CHILD) return j == Job.STUDENT || j == Job.UNEMPLOYED;
        if (stage == LifeStage.ELDER && (j.heavy || j.military())) return false;
        return c.education >= j.education;
    }
}
