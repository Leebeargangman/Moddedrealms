package com.colonysmp.sim;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Research;
import com.colonysmp.core.Skill;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Feature;
import com.colonysmp.model.Job;
import com.colonysmp.model.LifeStage;
import com.colonysmp.npc.Npc;
import com.colonysmp.util.Text;
import com.colonysmp.util.Tools;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The rarer trades: enchanters, beekeepers, druids and the readers of a library. The rules here are shared by
 * citizens with a body (who do the work where it stands) and the background simulation.
 */
public final class Trades {

    /** Work hours to lay one enchantment. */
    public static final double ENCHANT_HOURS = 4;
    public static final String LAPIS = "lapis_lazuli", BOTTLE = "glass_bottle", HONEY = "honey_bottle", COMB = "honeycomb";
    /** Education a library can bring an adult to. */
    private static final double LIBRARY_CAP = 70;

    private final ColonySMP plugin;

    Trades(ColonySMP plugin) {
        this.plugin = plugin;
    }

    // ───────────── enchanting ─────────────

    /** The strongest enchantment a tower's enchanter lays: 1, 2 in a level 2 tower, one more with Arcane Studies. */
    public int enchantLevel(Colony c, Building tower) {
        return Math.min(3, 1 + (tower.level >= 2 ? 1 : 0) + (int) c.bonus(Research.Effect.ENCHANTING));
    }

    /** The enchantment that suits an item, or null if it takes none. */
    public static Enchantment enchantmentFor(ItemStack it) {
        if (it == null || it.getType().isAir() || it.getType().getMaxDurability() <= 0) return null;
        return switch (Tools.kind(it)) {
            case PICKAXE, SHOVEL, HOE, SHEARS -> Enchantment.EFFICIENCY;
            case AXE -> Tools.tier(it.getType()) >= 4 ? Enchantment.SHARPNESS : Enchantment.EFFICIENCY;
            case SWORD -> Enchantment.SHARPNESS;
            case BOW -> Enchantment.POWER;
            case HELMET, CHESTPLATE, LEGGINGS, BOOTS -> Enchantment.PROTECTION;
            case CROSSBOW, TRIDENT, MACE, SHIELD, FISHING_ROD -> Enchantment.UNBREAKING;
            case OTHER -> null;
        };
    }

    private static boolean plain(ItemStack it) {
        return it != null && it.getEnchantments().isEmpty() && enchantmentFor(it) != null;
    }

    /** Something still unenchanted: gear in use first (arms, then tools), then the armoury's spares. */
    private ItemStack candidate(Colony c, Citizen[] holder) {
        for (Citizen ct : c.citizens.values()) {
            if (!ct.status.free()) continue;
            for (ItemStack it : new ItemStack[]{ct.weapon, ct.ranged, ct.tool, ct.armor[2], ct.armor[1], ct.armor[3], ct.armor[0], ct.shield}) {
                if (plain(it)) {
                    holder[0] = ct;
                    return it;
                }
            }
        }
        ItemStack best = null;
        double bs = Double.NEGATIVE_INFINITY;
        for (ItemStack it : c.equipment.live()) {
            if (!plain(it) || it.getAmount() != 1) continue;
            double s = Tools.score(it);
            if (s > bs) {
                bs = s;
                best = it;
            }
        }
        return best;
    }

    /**
     * An enchanter's work (efficiency-weighted hours) at their tower. Every few hours one piece of the colony's
     * gear is enchanted, at a cost in lapis from the tower's stock. Returns false when there's nothing to do.
     */
    public boolean enchant(Colony c, Building tower, Citizen ct, double units) {
        Citizen[] holder = new Citizen[1];
        ItemStack target = candidate(c, holder);
        if (target == null) {
            tower.waiting = "Everything the colony owns is already enchanted";
            ct.waiting = tower.waiting;
            return false;
        }
        if (tower.stock.available(LAPIS) < 2) {
            tower.waiting = "Waiting for lapis lazuli";
            ct.waiting = tower.waiting;
            return false;
        }
        tower.waiting = null;
        ct.waiting = null;
        tower.progress += units;
        if (tower.progress < ENCHANT_HOURS) return true;
        tower.progress -= ENCHANT_HOURS;
        Enchantment ench = enchantmentFor(target);
        int level = Math.min(enchantLevel(c, tower), ench.getMaxLevel());
        // two lapis a level: a weaker enchantment if the tower is short
        level = (int) Math.max(1, Math.min(level, tower.stock.available(LAPIS) / 2));
        if (!tower.stock.takeExact(LAPIS, level * 2L)) return true;
        target.addUnsafeEnchantment(ench, level);
        String what = Text.nice(target.getType().name()).toLowerCase();
        String whose = holder[0] == null ? "a spare " + what + " in the armoury" : holder[0].name + "'s " + what;
        c.log(ColonyEvent.Type.PRODUCTION, plugin.sim().day(c), ct.name + " enchanted " + whose + " with " + Text.nice(ench.getKey().getKey()) + " " + level + ".");
        if (holder[0] != null) {
            Npc n = plugin.npcs().npc(holder[0]);
            if (n != null) {
                n.shownHand = null;
                plugin.npcs().refresh(n);
            }
        }
        plugin.requestSave();
        return true;
    }

    // ───────────── bees ─────────────

    /**
     * An apiary while nobody is there in body: each hive fills about every other day, sooner when well kept.
     * Honey is bottled while the apiary has bottles; otherwise the comb is cut.
     */
    void bees(Colony c, Building apiary, Citizen ct, double hours, double units) {
        int hives = apiary.feature(Feature.BEEHIVE);
        if (hives <= 0) {
            ct.waiting = "No hives at " + apiary.label();
            return;
        }
        double days = hours / 24;
        double care = Math.min(1, units / Math.max(0.01, hives * 0.6 * days));
        apiary.produce += days * hives * 0.5 * care * (1 + 0.1 * (apiary.level - 1)) * (1 + 0.02 * ct.level(Skill.HUSBANDRY));
        ct.skills.gain(Skill.HUSBANDRY, hours * 10 * ct.trait.learning());
        ct.waiting = null;
        long made = 0;
        while (apiary.produce >= 1) {
            if (!harvest(apiary)) {
                ct.waiting = apiary.label() + " is full: the honey is waiting for haulers";
                apiary.produce = Math.min(apiary.produce, 2);
                break;
            }
            apiary.produce -= 1;
            made++;
        }
        c.producedToday += made;
    }

    /** One full hive's worth into the apiary's stock: a bottle of honey if there's a bottle, else three combs. */
    public boolean harvest(Building apiary) {
        if (apiary.stock.available(BOTTLE) > 0 && apiary.stock.room(HONEY) >= 1 && apiary.stock.takeExact(BOTTLE, 1)) {
            apiary.stock.force(HONEY, 1);
            return true;
        }
        if (apiary.stock.room(COMB) < 3) return false;
        apiary.stock.add(COMB, 3);
        return true;
    }

    // ───────────── druids ─────────────

    /** Druids at work in working groves. */
    public int druids(Colony c) {
        int n = 0;
        for (Citizen ct : c.citizens.values()) {
            if (ct.job != Job.DRUID || !ct.status.free()) continue;
            Building b = c.building(ct.workplace);
            if (b != null && b.valid && b.type == BuildingType.DRUID_GROVE) n++;
        }
        return n;
    }

    /**
     * How much faster fields grow in the background: a quarter for the first druid and a little more for each
     * after, the research on irrigation, and a good-harvest spell of weather.
     */
    public double growthBonus(Colony c, long day) {
        int d = druids(c);
        double bonus = d <= 0 ? 0 : Math.min(0.6, 0.25 + 0.1 * (d - 1));
        bonus += c.bonus(Research.Effect.HARVEST);
        if (c.boon("harvest", day)) bonus += 0.3;
        return bonus;
    }

    /** How much more a harvest brings in: irrigation and good weather. */
    public double yieldBonus(Colony c, long day) {
        return c.bonus(Research.Effect.HARVEST) + (c.boon("harvest", day) ? 0.3 : 0);
    }

    // ───────────── libraries ─────────────

    /** Once a day: adults with a library to read in teach themselves a little, up to a point. */
    void daily(Colony c, long day) {
        int level = 0;
        for (Building b : c.valid(BuildingType.LIBRARY)) level = Math.max(level, b.level);
        if (level <= 0) return;
        double rate = 0.6 * level * (1 + c.bonus(Research.Effect.LEARNING));
        List<Citizen> readers = new ArrayList<>();
        for (Citizen ct : c.citizens.values()) {
            if (!ct.status.free() || ct.education >= LIBRARY_CAP) continue;
            if (plugin.sim().citizens().stage(ct) == LifeStage.CHILD) continue;
            readers.add(ct);
        }
        for (Citizen ct : readers) {
            // not everyone reads every evening
            if (ThreadLocalRandom.current().nextDouble() > 0.6) continue;
            ct.education = Math.min(LIBRARY_CAP, ct.education + rate * ct.trait.learning());
        }
    }

    /** A library to spend an evening in, or null. */
    public Building library(Colony c) {
        List<Building> l = c.valid(BuildingType.LIBRARY);
        return l.isEmpty() ? null : l.get(0);
    }
}
