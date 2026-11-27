package com.colonysmp.sim;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.core.Goods;
import com.colonysmp.core.Skill;
import com.colonysmp.core.TxnType;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Job;
import com.colonysmp.model.LifeStage;
import com.colonysmp.model.Policies;
import com.colonysmp.util.Text;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * A colony's daily money: wages (under the wages economy) less tax, building upkeep, the army's upkeep, and what
 * citizens spend at the market. Payments only happen when the money is there; unpaid wages and upkeep have
 * consequences rather than creating debt out of nothing.
 */
public final class Finances {

    private final ColonySMP plugin;

    Finances(ColonySMP plugin) {
        this.plugin = plugin;
    }

    /** What a citizen earns in a day before tax. */
    public long wage(Colony c, Citizen ct) {
        if (c.policies.economy != Policies.Economy.WAGES || !ct.status.free() || !ct.job.employed()) return 0;
        double w = ct.job.wage * c.policies.wages.factor * plugin.settings().wageMultiplier;
        if (ct.job == Job.SOLDIER && ct.militaryClass != null) w = Math.max(w, ct.militaryClass.upkeep * plugin.settings().upkeepPerClass);
        return Math.max(0, Math.round(w));
    }

    /** Everything the colony pays out per day, for the economy view. */
    public long dailyCosts(Colony c) {
        long total = 0;
        for (Citizen ct : c.citizens.values()) total += wage(c, ct);
        total += upkeep(c);
        return total;
    }

    public long upkeep(Colony c) {
        double total = 0;
        boolean engineer = hasWorking(c, Job.ENGINEER);
        for (Building b : c.buildings.values()) total += b.type.upkeep(b.level);
        total *= plugin.settings().upkeepMultiplier * (engineer ? 0.75 : 1) * (hasWorking(c, Job.TREASURER) ? 0.9 : 1);
        total *= Math.max(0.2, 1 - c.bonus(com.colonysmp.core.Research.Effect.UPKEEP));
        if (c.policies.economy == Policies.Economy.COMMUNAL) {
            // without wages, soldiers still cost their keep
            for (Citizen ct : c.citizens.values()) {
                if (ct.status.free() && ct.job == Job.SOLDIER && ct.militaryClass != null) total += ct.militaryClass.upkeep * plugin.settings().upkeepPerClass * 0.5;
            }
        }
        return Math.round(total);
    }

    private static boolean hasWorking(Colony c, Job j) {
        for (Citizen ct : c.citizens.values()) if (ct.job == j && ct.workplace != 0 && ct.status.free()) return true;
        return false;
    }

    void daily(Colony c, long newDay) {
        Settings s = plugin.settings();
        long now = System.currentTimeMillis();
        // wages, upkeep and the market's takings belong to the day that just ended
        long day = newDay - 1;
        // wages, with tax withheld (one entry in the books for the whole payroll)
        long gross = 0, paid = 0, tax = 0;
        int unpaid = 0, workers = 0;
        boolean wagesModel = c.policies.economy == Policies.Economy.WAGES;
        double taxRate = c.policies.taxRate * (hasWorking(c, Job.TREASURER) ? 1.1 : 1);
        long budget = c.treasury.balance();
        for (Citizen ct : c.citizens.values()) {
            long w = wage(c, ct);
            if (w <= 0) {
                ct.unpaidDays = 0;
                continue;
            }
            // half pay for a day mostly not worked (sick, injured, no workplace)
            if (ct.hoursToday < 2 && ct.job.needsWorkplace()) w = Math.max(1, w / 2);
            long t = Math.min(w, Math.round(w * taxRate));
            long net = w - t;
            gross += w;
            if (budget - paid >= net) {
                ct.wallet += net;
                paid += net;
                tax += t;
                workers++;
                ct.unpaidDays = 0;
            } else {
                ct.unpaidDays++;
                unpaid++;
            }
        }
        if (paid > 0) c.treasury.debit(paid, TxnType.WAGES, workers + " workers paid (" + tax + " withheld in tax)", now, day);
        if (unpaid > 0) {
            c.log(ColonyEvent.Type.ECONOMY, newDay, unpaid + " citizen" + (unpaid == 1 ? " was" : "s were") + " not paid: the treasury is empty. Unpaid workers lose heart, then stop working.");
        }
        // upkeep
        long up = upkeep(c);
        if (up > 0) {
            long got = c.treasury.debitUpTo(up, TxnType.UPKEEP, "Building upkeep", now, day);
            if (got < up) {
                for (Building b : c.buildings.values()) {
                    if (b.type == BuildingType.TOWN_HALL) continue;
                    b.condition = Math.max(0, b.condition - 3);
                }
                c.log(ColonyEvent.Type.ECONOMY, newDay, "Upkeep went unpaid (" + (up - got) + " short): buildings are falling into disrepair.");
            }
        }
        // citizens spend at the market (money comes back to the treasury; a little luxury lifts mood)
        Building market = plugin.sim().citizens().staffed(c, BuildingType.MARKET);
        if (market != null && wagesModel) {
            long spent = 0;
            for (Citizen ct : c.citizens.values()) {
                if (!ct.status.free() || ct.wallet < 6) continue;
                long spend = Math.max(1, ct.wallet / 5);
                ct.wallet -= spend;
                spent += spend;
            }
            if (spent > 0) c.treasury.credit(spent, TxnType.SERVICES, "Market sales", now, day);
        }
        // wear and repair
        for (Building b : c.buildings.values()) {
            if (b.condition < 100 && b.staffedHours > 0 && b.type == BuildingType.ENGINEERING_WORKSHOP) b.condition = Math.min(100, b.condition + 5);
            b.staffedHours = 0;
        }
        plugin.sim().logistics().resetDay(c);
        warnLow(c, newDay, gross, paid);
    }

    private void warnLow(Colony c, long day, long gross, long paid) {
        long costs = dailyCosts(c);
        if (costs <= 0 || c.treasury.balance() >= costs * 2) return;
        for (Player p : c.onlineMembers()) {
            Text.send(p, "<gold>The treasury holds " + Text.value(c.treasury.balance()) + ", about "
                    + Math.max(0, c.treasury.balance() / Math.max(1, costs)) + " day(s) of wages and upkeep. Sell surplus at a Trading Post or deposit valuables.");
        }
    }

    /** Engineers keep buildings in repair as they work. */
    public void maintain(Colony c, double units) {
        double repair = units * 2;
        for (Building b : c.buildings.values()) {
            if (repair <= 0) break;
            if (b.condition >= 100) continue;
            double r = Math.min(repair, 100 - b.condition);
            b.condition += r;
            repair -= r;
        }
    }

    /** Food points the whole colony eats in a day. */
    public double foodPerDay(Colony c) {
        double perDay = 0;
        Settings s = plugin.settings();
        for (Citizen ct : c.citizens.values()) {
            double need = (s.needs.hungerAwake() * 14 + s.needs.hungerAsleep() * 10) / s.needs.satietyPerPoint() * ct.trait.food();
            if (plugin.sim().citizens().stage(ct) == LifeStage.CHILD) need *= 0.6;
            perDay += need;
        }
        return perDay;
    }

    private double foodPoints(Colony c) {
        double pts = plugin.sim().citizens().foodPoints(c.storage);
        for (Building b : c.buildings.values()) if (b.type == BuildingType.RESTAURANT) pts += plugin.sim().citizens().foodPoints(b.stock);
        return pts;
    }

    /** Days of food in storage for the current population. */
    public double foodDays(Colony c) {
        double perDay = foodPerDay(c);
        return perDay <= 0 ? 0 : foodPoints(c) / perDay;
    }

    /**
     * How much of a good may leave the colony (sold, traded, given): food is held back so what's left covers the
     * food reserve policy.
     */
    public long spare(Colony c, String good) {
        long have = c.storage.available(good);
        if (!plugin.sim().citizens().edible(good)) return have;
        double per = Goods.get(good).nutrition();
        double reserve = foodPerDay(c) * c.policies.foodReserveDays;
        double others = foodPoints(c) - per * c.storage.get(good);
        long keep = (long) Math.ceil(Math.max(0, reserve - others) / per);
        return Math.max(0, have - keep);
    }

    /** Value of everything in storage at base prices, apart from the valuables that are the treasury (for the economy view). */
    public long stockValue(Colony c) {
        double v = 0;
        for (Map.Entry<String, Long> e : c.storage.contents().entrySet()) {
            if (!com.colonysmp.core.Treasury.isValuable(e.getKey())) v += Goods.get(e.getKey()).value() * e.getValue();
        }
        return Math.round(v);
    }

    public static int skill(Citizen c, Skill s) {
        return c.level(s);
    }
}
