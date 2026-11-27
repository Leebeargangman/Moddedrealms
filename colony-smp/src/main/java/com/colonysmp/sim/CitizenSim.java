package com.colonysmp.sim;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.core.GameTime;
import com.colonysmp.core.Good;
import com.colonysmp.core.Goods;
import com.colonysmp.core.Ledger;
import com.colonysmp.core.Mood;
import com.colonysmp.core.Needs;
import com.colonysmp.core.Research;
import com.colonysmp.core.Skill;
import com.colonysmp.core.TxnType;
import com.colonysmp.model.Bond;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Job;
import com.colonysmp.model.LifeStage;
import com.colonysmp.model.Policies;
import com.colonysmp.model.Status;
import com.colonysmp.util.Tools;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The rules of daily life, shared by citizens with and without bodies: the routine, hunger and meals, sleep,
 * healing, company, mood, and how fast someone works.
 */
public final class CitizenSim {

    /** What every citizen of a colony shares during one step. */
    public static final class Context {
        public long day;
        public double stability, taxRate, events;
        public int services, danger;
        public boolean school, protest, wages, entertained;
        public Policies.Hours hours;
        public Building restaurant;
        public double healBonus;
        /** Occupants per home, for crowding. */
        public final Map<Integer, Integer> occupants = new java.util.HashMap<>();
    }

    private final ColonySMP plugin;
    private Set<String> neverEat;

    CitizenSim(ColonySMP plugin) {
        this.plugin = plugin;
    }

    private Needs.Params params() {
        return plugin.settings().needs;
    }

    public void reload() {
        neverEat = null;
    }

    private Set<String> neverEat() {
        if (neverEat == null) {
            Set<String> s = new HashSet<>();
            for (Material m : plugin.settings().neverEat) s.add(Goods.key(m.name()));
            neverEat = s;
        }
        return neverEat;
    }

    public Context context(Colony c) {
        Context x = new Context();
        x.day = plugin.sim().day(c);
        x.stability = c.stability;
        x.wages = c.policies.economy == Policies.Economy.WAGES;
        x.taxRate = x.wages ? c.policies.taxRate : 0;
        x.events = c.modifierMood(x.day);
        x.hours = c.policies.hours;
        x.protest = c.protest;
        x.danger = plugin.wars().fighting(c) ? 2 : plugin.military().threatened(c) ? 1 : 0;
        x.restaurant = staffed(c, BuildingType.RESTAURANT);
        if (x.restaurant != null) x.services++;
        if (staffed(c, BuildingType.MARKET) != null) x.services++;
        if (staffed(c, BuildingType.CLINIC) != null) {
            x.services++;
            x.healBonus = 0.4;
        }
        if (staffed(c, BuildingType.INN) != null) x.services++;
        if (!c.valid(BuildingType.LIBRARY).isEmpty()) x.services++;
        x.entertained = staffed(c, BuildingType.TAVERN) != null;
        x.healBonus += c.bonus(Research.Effect.HEALING);
        x.events += c.bonus(Research.Effect.MOOD) + plugin.christmas().cheer(c);
        x.school = staffed(c, BuildingType.SCHOOL) != null;
        for (Citizen ct : c.citizens.values()) if (ct.home != 0) x.occupants.merge(ct.home, 1, Integer::sum);
        return x;
    }

    /** A working building of a type with at least one worker. */
    public Building staffed(Colony c, BuildingType t) {
        for (Building b : c.buildings.values()) {
            if (b.type != t || !b.valid) continue;
            for (Citizen ct : c.citizens.values()) if (ct.workplace == b.id && ct.status.free()) return b;
        }
        return null;
    }

    public LifeStage stage(Citizen c) {
        Settings s = plugin.settings();
        return c.stage(s.adultAge, s.elderAge);
    }

    // ───────────── background ─────────────

    /** Advances a citizen with no body through a chunk of time (at most an hour). */
    void background(Colony col, Citizen c, long ticks, long dayTime, Context ctx) {
        Needs.Params p = params();
        double hours = GameTime.hours(ticks);
        c.ageDays += hours / 24;
        if (c.downed()) return;
        if (c.status.confined()) {
            // prisoners and captives: fed by wardens once a day (see the prison)
            c.needs.decay(hours, false, false, false, p);
            c.needs.heal(hours, false, 0, p);
            if (c.needs.health <= 0) plugin.sim().population().die(col, c, "starved in captivity");
            return;
        }
        long workStart = ctx.hours.start, workEnd = ctx.hours.end, bedtime = plugin.settings().eveningEnd;
        double sleepH = GameTime.hours(GameTime.overlap(dayTime, ticks, bedtime, workStart));
        double workH = GameTime.hours(GameTime.overlap(dayTime, ticks, workStart, workEnd));
        double leisureH = Math.max(0, hours - sleepH - workH);
        LifeStage stage = stage(c);
        boolean able = canWork(col, c);
        if (!able || stage == LifeStage.CHILD && c.job != Job.STUDENT) {
            leisureH += workH;
            workH = 0;
        }
        // the exhausted nap whenever they are
        if (c.needs.exhausted(p) && sleepH < hours) {
            double nap = Math.min(workH + leisureH, 2);
            double fromWork = Math.min(workH, nap);
            workH -= fromWork;
            leisureH = Math.max(0, leisureH - (nap - fromWork));
            sleepH += nap;
        }
        boolean bed = c.home != 0 && c.bed != null;
        boolean heavy = c.job.heavy;
        c.needs.decay(workH, false, false, heavy, p);
        c.needs.decay(leisureH, false, false, false, p);
        c.needs.decay(sleepH, true, bed, false, p);
        if (c.needs.hungry(p) && (sleepH < hours || c.needs.starving())) eat(col, c, ctx);
        double healBonus = ctx.healBonus + (c.injured || c.ill() ? plugin.sim().health().treatmentBonus(col) : 0);
        c.needs.heal(hours, bed && sleepH > 0, healBonus, p);
        if (c.needs.health <= 0) {
            plugin.sim().population().die(col, c, c.needs.satiety <= 0 ? "starved" : c.ill() ? "died of " + c.illness.display.toLowerCase() : "died of their wounds");
            return;
        }
        updateInjury(c);
        double company = leisureH > 0 ? company(col, c) * leisureH / Math.max(hours, 0.01) : 0;
        c.needs.socialize(hours, company * c.trait.social(), p);
        if (c.trait.social() == 0) c.needs.social = Math.max(c.needs.social, 60);
        c.grief = Math.max(0, c.grief - hours / 24 * 4);
        c.needs.driftMood(mood(col, c, ctx).target(), hours);
        if (workH > 0 && plugin.settings().backgroundProduction) {
            double units = workH * efficiency(col, c, ctx);
            c.hoursToday += workH;
            plugin.sim().production().contribute(col, c, units, workH);
        }
        // what they're up to now, for the menus (a body reports its own)
        long end = Math.floorMod(dayTime + ticks, GameTime.TICKS_PER_DAY);
        String doing = GameTime.within(end, bedtime, workStart) ? (bed ? "Asleep at home" : "Sleeping rough")
                : GameTime.within(end, workStart, workEnd) && able && stage != LifeStage.CHILD && c.job.employed() ? "At work" : "Off work";
        if (!doing.equals(c.activity)) {
            c.activity = doing;
            c.activitySince = System.currentTimeMillis();
        }
    }

    /** Healthy enough to work at all (illness and injuries slow or stop work). */
    public boolean canWork(Colony col, Citizen c) {
        if (!c.status.free() || c.downed()) return false;
        if (c.needs.health < c.needs.maxHealth * 0.25) return false;
        if (c.injured && (c.job.heavy || c.job.military())) return false;
        if (c.ill() && col.policies.quarantine) return false;
        if (c.unpaidDays >= 3 && c.job.employed() && col.policies.economy == Policies.Economy.WAGES) return false;
        return true;
    }

    public void updateInjury(Citizen c) {
        double f = c.needs.healthFraction();
        if (!c.injured && f < plugin.settings().injuryThreshold) c.injured = true;
        else if (c.injured && f >= 0.8) c.injured = false;
    }

    /** Work speed: mood, rest, hunger and health, skill, trait, schooling, tools, illness and unrest. */
    public double efficiency(Colony col, Citizen c, Context ctx) {
        double f = c.needs.productivity(params());
        if (c.job.skill != null) f *= Skill.speed(c.level(c.job.skill));
        f *= c.trait.work(c.job);
        f *= 1 + Math.min(0.3, c.education / 100 * 0.3);
        // the tool makes the worker: a better one works faster, and the essential ones can't be done without
        if (c.job.tool != null) f *= c.tool != null && Tools.kind(c.tool) == c.job.tool ? Tools.speed(c.tool) : c.job.toolEssential() ? Tools.bareHands() : 0.8;
        if (c.ill()) f *= c.illness.work;
        if (ctx != null && ctx.protest) f *= 0.7;
        if (stage(c) == LifeStage.ELDER) f *= 0.8;
        f *= 1 + researched(col, c.job);
        if (c.motivatedUntil >= 0 && c.motivatedUntil >= (ctx != null ? ctx.day : plugin.sim().day(col))) f *= 1.15;
        f *= plugin.sim().winter().work(col, c);
        return Math.max(0.02, f);
    }

    public double efficiency(Colony col, Citizen c) {
        return efficiency(col, c, null) * (col.protest ? 0.7 : 1);
    }

    /** How much faster the colony's research makes a job. */
    public static double researched(Colony col, Job j) {
        if (col.research.isEmpty()) return 0;
        return switch (j) {
            case FARMER, ANIMAL_KEEPER, FISHER, BEEKEEPER, DRUID -> col.bonus(Research.Effect.WORK_FOOD);
            case MINER, FORESTER, BUILDER -> col.bonus(Research.Effect.WORK_INDUSTRY);
            case RESEARCHER -> col.bonus(Research.Effect.RESEARCH);
            default -> j.background == Job.Background.RECIPE ? col.bonus(Research.Effect.WORK_CRAFT) : 0;
        };
    }

    // ───────────── meals ─────────────

    /**
     * A meal: at a restaurant if there is one serving (and they can pay), otherwise from storage. Returns the
     * hunger points eaten.
     */
    public double eat(Colony col, Citizen c, Context ctx) {
        Needs.Params p = params();
        double want = c.needs.appetite(p) * c.trait.food();
        if (col.policies.rationing) want *= 0.7;
        if (want <= 0.5) return 0;
        double eaten = 0;
        if (ctx.restaurant != null && c.restaurantDay != ctx.day) {
            double got = eatFrom(col, c, ctx.restaurant.stock, want, ctx, 1.5, 2);
            if (got > 0) {
                c.restaurantDay = ctx.day;
                eaten += got;
            }
        }
        if (eaten < want) eaten += eatFrom(col, c, col.storage, want - eaten, ctx, 1.0, 0);
        if (eaten > 0) c.lastMealDay = ctx.day;
        return eaten;
    }

    /** Eats the best food in a stock up to some hunger points; pays for it under the wages economy. */
    public double eatFrom(Colony col, Citizen c, Ledger stock, double want, Context ctx, double priceFactor, int minQuality) {
        Needs.Params p = params();
        List<Good> foods = new ArrayList<>();
        for (Map.Entry<String, Long> e : stock.contents().entrySet()) {
            Good g = Goods.get(e.getKey());
            if (g.nutrition() <= 0 || g.quality() < minQuality || neverEat().contains(g.key()) || stock.available(g.key()) <= 0) continue;
            foods.add(g);
        }
        foods.sort((a, b) -> a.quality() != b.quality() ? b.quality() - a.quality() : Double.compare(b.nutrition(), a.nutrition()));
        double eaten = 0, cost = 0;
        for (Good g : foods) {
            if (eaten >= want - 0.01) break;
            long n = (long) Math.ceil((want - eaten) / g.nutrition());
            n = stock.take(g.key(), Math.max(1, n));
            if (n <= 0) continue;
            for (int i = 0; i < n; i++) c.needs.eat(g.nutrition(), g.quality(), p);
            eaten += n * g.nutrition();
            cost += n * g.value() * plugin.settings().mealPrice * priceFactor;
        }
        if (ctx.wages && cost > 0) {
            long pay = Math.min(c.wallet, Math.round(cost));
            if (pay > 0) {
                c.wallet -= pay;
                col.treasury.credit(pay, TxnType.SERVICES, "Meals", System.currentTimeMillis(), ctx.day);
            }
        }
        return eaten;
    }

    public double foodPoints(Ledger stock) {
        double pts = 0;
        for (Map.Entry<String, Long> e : stock.contents().entrySet()) {
            Good g = Goods.get(e.getKey());
            if (g.nutrition() <= 0 || neverEat().contains(g.key())) continue;
            pts += g.nutrition() * e.getValue();
        }
        return pts;
    }

    public boolean edible(String key) {
        Good g = Goods.get(key);
        return g.nutrition() > 0 && !neverEat().contains(key);
    }

    // ───────────── company and mood ─────────────

    /** 0-1: how much company they have in the evening (family at home, friends in the colony). */
    double company(Colony col, Citizen c) {
        double v = 0;
        for (Map.Entry<UUID, Bond> e : c.bonds.entrySet()) {
            Citizen o = col.citizens.get(e.getKey());
            if (o == null || !o.status.free()) continue;
            if (e.getValue().kind.family() && o.home == c.home && c.home != 0) v += 0.5;
            else if (e.getValue().kind == Bond.Kind.FRIEND) v += 0.25;
        }
        if (col.population() > 3) v += 0.2;
        return Math.min(1, v);
    }

    public Mood.Result mood(Colony col, Citizen c, Context ctx) {
        Mood.Inputs in = new Mood.Inputs();
        in.satiety = c.needs.satiety;
        in.energy = c.needs.energy;
        in.health = c.needs.healthFraction();
        in.social = c.needs.social;
        in.mealQuality = c.needs.mealQuality;
        Building home = col.building(c.home);
        if (home == null) in.housing = 0;
        else if (home.type == BuildingType.HOUSE) in.housing = home.level;
        else in.housing = 1;
        if (home != null && home.type == BuildingType.HOUSE) {
            int occ = ctx.occupants.getOrDefault(home.id, 1);
            in.overcrowded = home.crowded(occ);
        }
        in.ill = c.ill();
        in.danger = ctx.danger;
        LifeStage stage = stage(c);
        in.adult = stage == LifeStage.ADULT;
        in.employed = c.job.employed() && (c.workplace != 0 || !c.job.needsWorkplace());
        in.suitsTrait = c.trait.likes(c.job);
        in.dislikesJob = c.trait == com.colonysmp.model.Trait.LAZY && c.job.heavy;
        in.skilledJob = c.job.education > 0;
        in.longHours = ctx.hours == Policies.Hours.LONG && c.job.employed();
        in.unpaid = c.unpaidDays > 0;
        UUID partner = c.partner();
        if (partner != null) {
            Citizen pc = col.citizens.get(partner);
            in.partnerNearby = pc != null && pc.home == c.home && c.home != 0;
        }
        for (Map.Entry<UUID, Bond> e : c.bonds.entrySet()) {
            if (e.getValue().kind != Bond.Kind.CHILD && e.getValue().kind != Bond.Kind.PARENT) continue;
            Citizen o = col.citizens.get(e.getKey());
            if (o != null && o.home == c.home && c.home != 0) in.familyTogether = true;
        }
        in.bereaved = c.grief > 0;
        in.stability = ctx.stability;
        in.taxRate = ctx.taxRate;
        in.services = ctx.services + (stage == LifeStage.CHILD && ctx.school ? 1 : 0);
        in.events = ctx.events + (ctx.wages ? col.policies.wages.mood : -2) + (c.job == Job.SOLDIER && col.policies.military == Policies.Military.CONSCRIPTION ? -4 : 0);
        in.confined = c.status.confined();
        in.entertained = ctx.entertained;
        var cold = plugin.sim().winter().mood(col, c);
        if (cold != null) {
            in.warmth = cold.getKey();
            in.warmthMood = cold.getValue();
        }
        return Mood.target(in);
    }

    /** One-line reason for a citizen's mood (the worst factor). */
    public String why(Colony col, Citizen c) {
        Mood.Result r = mood(col, c, context(col));
        String w = r.worst();
        return w == null ? "Nothing is bothering them" : w;
    }

    public int population(Colony c) {
        int n = 0;
        for (Citizen ct : c.citizens.values()) if (ct.status == Status.CITIZEN) n++;
        return n;
    }
}
