package com.colonysmp.sim;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.core.Goods;
import com.colonysmp.core.TxnType;
import com.colonysmp.model.Bond;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Job;
import com.colonysmp.model.Modifier;
import com.colonysmp.model.Policies;
import com.colonysmp.model.Status;
import com.colonysmp.util.Text;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stability and public order. Stability follows how content people are, whether they're fed, paid and housed,
 * and whether the streets are watched. Unhappy, poor or idle citizens steal and quarrel; guards deter and catch
 * them and wardens keep the prison. When stability stays low people protest (work slows), then riot.
 */
public final class Order {

    private final ColonySMP plugin;
    private final Map<String, Double> guardHours = new HashMap<>();

    Order(ColonySMP plugin) {
        this.plugin = plugin;
    }

    /** Guards on duty report their hours (in body or in the background). */
    public void guardHours(Colony c, double hours) {
        guardHours.merge(c.id, hours, Double::sum);
    }

    /** 0-1: how well the colony is watched (guard hours today against what its size needs). */
    public double coverage(Colony c) {
        double hours = guardHours.getOrDefault(c.id, 0.0);
        double needed = Math.max(4, c.population() * 0.8);
        return Math.min(1, hours / needed);
    }

    /** Where stability is heading, with the reasons (for the Town Hall). */
    public Map<String, Double> factors(Colony c) {
        Map<String, Double> f = new java.util.LinkedHashMap<>();
        double mood = 0;
        int pop = 0, unpaid = 0, homeless = 0, idle = 0;
        for (Citizen ct : c.citizens.values()) {
            if (!ct.status.free()) continue;
            pop++;
            mood += ct.needs.mood;
            if (ct.unpaidDays > 0) unpaid++;
            if (ct.home == 0) homeless++;
            if (ct.job == Job.UNEMPLOYED && plugin.sim().citizens().stage(ct) != com.colonysmp.model.LifeStage.CHILD) idle++;
        }
        if (pop == 0) return f;
        f.put("Mood", (mood / pop - 50) * 0.6);
        double food = plugin.sim().finances().foodDays(c);
        f.put("Food", food < 1 ? -15 : food < 3 ? -5 : food > 7 ? 5 : 0.0);
        if (unpaid > 0) f.put("Unpaid wages", -20.0 * unpaid / pop);
        if (homeless > 0) f.put("Homelessness", -15.0 * homeless / pop);
        if (idle > 0) f.put("Unemployment", -10.0 * idle / pop);
        if (c.policies.economy == Policies.Economy.WAGES) f.put("Taxes", -c.policies.taxRate * 30);
        if (c.policies.rationing) f.put("Rationing", -4.0);
        if (c.policies.military == Policies.Military.CONSCRIPTION) f.put("Conscription", -4.0);
        if (c.policies.hours == Policies.Hours.LONG) f.put("Long hours", -4.0);
        f.put("Watch", coverage(c) * 8 - 2);
        int crime = 0;
        for (Citizen ct : c.citizens.values()) if (ct.status == Status.RIOTER) crime++;
        if (crime > 0) f.put("Riots", -10.0);
        if (plugin.wars().fighting(c)) f.put("War", -6.0);
        if (plugin.sim().jobs().administrated(c)) f.put("Administration", 4.0);
        f.put("Base", 50.0);
        return f;
    }

    public double target(Colony c) {
        double t = 0;
        for (double v : factors(c).values()) t += v;
        return Math.max(0, Math.min(100, t));
    }

    // ───────────── the day ─────────────

    void daily(Colony c, long day) {
        Settings s = plugin.settings();
        if (c.population() == 0) {
            // nobody left to protest
            c.protest = false;
            return;
        }
        double cover = coverage(c);
        guardHours.remove(c.id);
        // stability drifts towards what the colony deserves
        double target = target(c);
        c.stability += (target - c.stability) * 0.35;
        c.stability = Math.max(0, Math.min(100, c.stability));
        crime(c, day, cover);
        disputes(c, day);
        unrest(c, day, cover);
        strikes(c, day);
    }

    /** Thieves: the poor, the miserable and the greedy, less often where guards watch. */
    private void crime(Colony c, long day, double cover) {
        Settings s = plugin.settings();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Citizen ct : new ArrayList<>(c.citizens.values())) {
            if (!ct.status.free() || plugin.sim().citizens().stage(ct) == com.colonysmp.model.LifeStage.CHILD) continue;
            double chance = s.theftChance * ct.trait.crime();
            if (ct.needs.mood < 35) chance *= 2;
            if (ct.needs.satiety < 25) chance *= 2;
            if (c.policies.economy == Policies.Economy.WAGES && ct.wallet < 3) chance *= 1.5;
            if (ct.job == Job.UNEMPLOYED) chance *= 1.5;
            if (ct.job.military()) chance *= 0.3;
            chance *= 1 - 0.7 * cover;
            if (r.nextDouble() >= chance) continue;
            String what;
            long taken = 0;
            boolean caught = r.nextDouble() < 0.15 + cover * 0.6;
            if (r.nextBoolean() && c.treasury.balance() > 20) {
                taken = Math.max(1, Math.min(c.treasury.balance() / 20, 15 + r.nextInt(20)));
                c.treasury.debit(taken, TxnType.THEFT, caught ? ct.name + " stole from the treasury" : "Stolen from the treasury (thief unknown)",
                        System.currentTimeMillis(), day);
                ct.wallet += taken;
                what = Text.value(taken) + " from the treasury";
            } else {
                // goods from storage: a handful of whatever is most valuable
                String best = null;
                double bestValue = 0;
                for (Map.Entry<String, Long> e : c.storage.contents().entrySet()) {
                    if (c.storage.available(e.getKey()) <= 0) continue;
                    double v = Goods.get(e.getKey()).value();
                    if (v > bestValue) {
                        bestValue = v;
                        best = e.getKey();
                    }
                }
                if (best == null) continue;
                taken = c.storage.take(best, Math.max(1, Math.min(8, (long) (40 / Math.max(1, bestValue)))));
                if (taken <= 0) continue;
                what = taken + " " + Goods.get(best).name() + " from storage";
            }
            ct.crimes++;
            if (!caught) {
                c.log(ColonyEvent.Type.CRIME, day, "A thief took " + what + ". Nobody saw who." + (cover < 0.3 ? " More guards would help." : ""));
                continue;
            }
            int days = 1 + Math.min(4, ct.crimes);
            if (plugin.prison().imprison(c, ct, day + days, "theft")) {
                c.log(ColonyEvent.Type.CRIME, day, ct.name + " was caught stealing " + what + " and jailed for " + days + " day" + (days == 1 ? "" : "s") + ".");
            } else {
                // no cell: a fine and a black mark
                long fine = Math.min(ct.wallet, 10L * ct.crimes);
                ct.wallet -= fine;
                if (fine > 0) c.treasury.credit(fine, TxnType.FINES, "Fine for " + ct.name, System.currentTimeMillis(), day);
                ct.needs.mood = Math.max(0, ct.needs.mood - 10);
                c.log(ColonyEvent.Type.CRIME, day, ct.name + " was caught stealing " + what + (fine > 0 ? " and fined " + Text.value(fine) : "")
                        + ". There's no prison cell to hold them.");
            }
            ct.remember(day, "Caught stealing");
        }
    }

    /** Rivals quarrel, sometimes coming to blows. */
    private void disputes(Colony c, long day) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Citizen a : new ArrayList<>(c.citizens.values())) {
            if (!a.status.free()) continue;
            for (Map.Entry<UUID, Bond> e : a.bonds.entrySet()) {
                if (e.getValue().kind != Bond.Kind.RIVAL || a.id.compareTo(e.getKey()) > 0) continue;
                Citizen b = c.citizens.get(e.getKey());
                if (b == null || !b.status.free() || r.nextDouble() > 0.15) continue;
                a.needs.mood = Math.max(0, a.needs.mood - 6);
                b.needs.mood = Math.max(0, b.needs.mood - 6);
                if (r.nextDouble() < 0.25) {
                    Citizen hurt = r.nextBoolean() ? a : b;
                    hurt.needs.health = Math.max(1, hurt.needs.health - hurt.needs.maxHealth * 0.4);
                    plugin.npcs().pushHealth(hurt);
                    plugin.sim().citizens().updateInjury(hurt);
                    c.log(ColonyEvent.Type.CRIME, day, a.name + " and " + b.name + " came to blows; " + hurt.name + " was hurt.");
                    c.addStability(-1);
                } else {
                    c.log(ColonyEvent.Type.CRIME, day, a.name + " and " + b.name + " had a bitter argument.");
                }
                break;
            }
        }
    }

    /** Protests when stability stays low; riots when it collapses. */
    private void unrest(Colony c, long day, double cover) {
        Settings s = plugin.settings();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (c.stability < s.protestBelow) c.lowDays++;
        else c.lowDays = 0;
        if (!c.protest && c.lowDays >= s.protestDays) {
            c.protest = true;
            c.log(ColonyEvent.Type.CRIME, day, "Citizens are protesting about the state of the colony: work is slowing down. ("
                    + worstFactor(c) + ")");
            for (Player p : c.onlineMembers()) Text.send(p, "<red>Your citizens are protesting! Work slows until stability recovers. Biggest problem: " + worstFactor(c));
        } else if (c.protest && c.stability >= s.protestBelow + 10) {
            c.protest = false;
            c.log(ColonyEvent.Type.CRIME, day, "The protests have ended.");
        }
        // riots
        List<Citizen> rioters = new ArrayList<>();
        for (Citizen ct : c.citizens.values()) if (ct.status == Status.RIOTER) rioters.add(ct);
        if (!rioters.isEmpty()) {
            // guards arrest rioters; the rest calm down when the riot ends
            for (Citizen ct : rioters) {
                if (r.nextDouble() < cover * 0.7 && plugin.prison().imprison(c, ct, day + 2, "rioting")) {
                    c.log(ColonyEvent.Type.CRIME, day, "Guards arrested " + ct.name + " for rioting.");
                } else if (day >= c.riotUntil || c.stability >= s.riotBelow + 10) {
                    ct.status = Status.CITIZEN;
                }
            }
            // what rioters break and take
            for (Citizen ct : rioters) {
                if (ct.status != Status.RIOTER) continue;
                for (Building b : c.buildings.values()) {
                    if (b.type != BuildingType.TOWN_HALL && r.nextDouble() < 0.1) b.condition = Math.max(0, b.condition - 10);
                }
                String best = null;
                for (String k : c.storage.contents().keySet()) if (plugin.sim().citizens().edible(k) && c.storage.available(k) > 0) best = k;
                if (best != null) c.storage.take(best, 4);
            }
            boolean over = true;
            for (Citizen ct : c.citizens.values()) if (ct.status == Status.RIOTER) over = false;
            if (over) {
                c.log(ColonyEvent.Type.CRIME, day, "The riot is over.");
                plugin.npcs().refreshColony(c);
            }
        } else if (c.protest && c.stability < s.riotBelow && r.nextDouble() < 0.5) {
            int n = 0;
            for (Citizen ct : c.citizens.values()) {
                if (!ct.status.free() || ct.job.military() || ct.trait == com.colonysmp.model.Trait.LOYAL) continue;
                if (ct.needs.mood < 25 && n < Math.max(1, c.population() / 4)) {
                    ct.status = Status.RIOTER;
                    plugin.sim().logistics().abandon(c, ct);
                    n++;
                }
            }
            if (n > 0) {
                c.riotUntil = day + 2;
                c.addModifier(new Modifier("riot", "Riots in the streets", -8, day + 3));
                c.log(ColonyEvent.Type.CRIME, day, n + " citizens are rioting! They won't work and are damaging buildings. Guards can arrest them.");
                for (Player p : c.onlineMembers()) Text.title(p, "<red>Riot!", "<gray>" + n + " citizens are rioting in " + Text.esc(c.name), 10, 60, 20);
                plugin.npcs().refreshColony(c);
            }
        }
    }

    /** Unpaid workers walk out. */
    private void strikes(Colony c, long day) {
        int striking = 0;
        for (Citizen ct : c.citizens.values()) {
            if (ct.status.free() && ct.job.employed() && ct.unpaidDays >= 3 && c.policies.economy == Policies.Economy.WAGES) striking++;
        }
        if (striking > 0) {
            c.log(ColonyEvent.Type.CRIME, day, striking + " unpaid worker" + (striking == 1 ? " is" : "s are") + " on strike until their wages are paid.");
        }
    }

    public String worstFactor(Colony c) {
        String worst = "nothing in particular";
        double low = 0;
        for (Map.Entry<String, Double> e : factors(c).entrySet()) {
            if (e.getKey().equals("Base")) continue;
            if (e.getValue() < low) {
                low = e.getValue();
                worst = e.getKey().toLowerCase();
            }
        }
        return worst;
    }
}
