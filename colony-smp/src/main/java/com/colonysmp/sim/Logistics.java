package com.colonysmp.sim;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Goods;
import com.colonysmp.core.Ledger;
import com.colonysmp.core.Reservation;
import com.colonysmp.model.Building;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Job;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moves goods between central storage and the workplaces that make or need them. Workplaces worked by citizens
 * in body are served by couriers (or their own workers) walking with real loads; everywhere else the transfer is
 * a record, so a large colony doesn't fill the roads with walkers. Either way the goods are reserved at the
 * source, carried by exactly one person, and never duplicated or lost.
 */
public final class Logistics {

    /** One load: from a workplace (or central storage, id 0) to another. */
    public static final class Haul {
        public final int from, to;
        public final String good;
        public final long qty;
        public final String reservation;
        public final UUID carrier;
        public boolean picked;

        Haul(int from, int to, String good, long qty, String reservation, UUID carrier) {
            this.from = from;
            this.to = to;
            this.good = good;
            this.qty = qty;
            this.reservation = reservation;
            this.carrier = carrier;
        }

        public String describe(Colony c) {
            String a = from == 0 ? "storage" : c.building(from) == null ? "?" : c.building(from).label();
            String b = to == 0 ? "storage" : c.building(to) == null ? "?" : c.building(to).label();
            return qty + " " + Goods.get(good).name() + " from " + a + " to " + b;
        }
    }

    private final ColonySMP plugin;
    private final Map<UUID, Haul> hauls = new HashMap<>();
    private final Map<String, Double> courierHours = new HashMap<>();

    Logistics(ColonySMP plugin) {
        this.plugin = plugin;
    }

    public void courierHours(Colony c, double hours) {
        courierHours.merge(c.id, hours, Double::sum);
    }

    public double courierHoursToday(Colony c) {
        return courierHours.getOrDefault(c.id, 0.0);
    }

    public void resetDay(Colony c) {
        courierHours.remove(c.id);
    }

    // ───────────── background transfers ─────────────

    void background(Colony c, double hours) {
        boolean couriers = false;
        for (Citizen ct : c.citizens.values()) {
            if (ct.status.free() && (ct.job == Job.WAREHOUSE_WORKER || ct.job == Job.FOOD_DISTRIBUTOR)) couriers = true;
        }
        for (Building b : c.buildings.values()) {
            if (!b.valid || plugin.npcs().worked(c, b)) continue;
            // no couriers at all: the workplace's own workers carry things, a few loads an hour
            long budget = couriers ? Long.MAX_VALUE : Math.max(64, (long) (hours * 3 * 64 * Math.max(1, c.workers(b).size())));
            for (Map.Entry<String, Long> e : plugin.sim().production().surplus(c, b).entrySet()) {
                if (budget <= 0) break;
                long n = Math.min(Math.min(e.getValue(), budget), c.storage.room(e.getKey()));
                if (n <= 0) continue;
                n = b.stock.take(e.getKey(), n);
                long added = c.storage.add(e.getKey(), n);
                if (added < n) b.stock.force(e.getKey(), n - added);
                budget -= added;
            }
            for (Map.Entry<String, Long> e : plugin.sim().production().wants(c, b).entrySet()) {
                if (budget <= 0) break;
                long n = Math.min(Math.min(e.getValue(), budget), Math.min(c.storage.available(e.getKey()), b.stock.room(e.getKey())));
                if (n <= 0) continue;
                n = c.storage.take(e.getKey(), n);
                long added = b.stock.add(e.getKey(), n);
                if (added < n) c.storage.force(e.getKey(), n - added);
                budget -= added;
            }
        }
    }

    // ───────────── loads for walking carriers ─────────────

    public Haul current(Citizen carrier) {
        return hauls.get(carrier.id);
    }

    /**
     * The next load for a carrier: a courier serves every workplace, a worker only their own. Food
     * distributors take food first. Returns null when nothing needs carrying.
     */
    public Haul next(Colony c, Citizen carrier, Building only) {
        Haul have = hauls.get(carrier.id);
        if (have != null) return have;
        long cap = (long) Goods.stack("stone") * Citizen.CARRY_SLOTS;
        boolean food = carrier.job == Job.FOOD_DISTRIBUTOR;
        List<Building> places = new ArrayList<>();
        if (only != null) places.add(only);
        else places.addAll(c.buildings.values());
        Haul best = null;
        long bestScore = 0;
        for (Building b : places) {
            if (!b.valid || taken(b.id)) continue;
            for (Map.Entry<String, Long> e : plugin.sim().production().wants(c, b).entrySet()) {
                long n = Math.min(Math.min(e.getValue(), cap), c.storage.available(e.getKey()));
                n = Math.min(n, (long) Goods.stack(e.getKey()) * Citizen.CARRY_SLOTS);
                if (n <= 0) continue;
                long score = n * 2 + (food && plugin.sim().citizens().edible(e.getKey()) ? 10_000 : 0);
                if (score > bestScore) {
                    bestScore = score;
                    best = new Haul(0, b.id, e.getKey(), n, null, carrier.id);
                }
            }
            for (Map.Entry<String, Long> e : plugin.sim().production().surplus(c, b).entrySet()) {
                long n = Math.min(Math.min(e.getValue(), (long) Goods.stack(e.getKey()) * Citizen.CARRY_SLOTS), c.storage.room(e.getKey()));
                if (n <= 0) continue;
                long score = n + (b.stock.full() ? 5_000 : 0) + (food && plugin.sim().citizens().edible(e.getKey()) ? 10_000 : 0);
                if (score > bestScore) {
                    bestScore = score;
                    best = new Haul(b.id, 0, e.getKey(), n, null, carrier.id);
                }
            }
        }
        if (best == null) return null;
        Ledger src = best.from == 0 ? c.storage : c.building(best.from).stock;
        Reservation r = src.reserve("haul:" + (best.from == 0 ? best.to : best.from), best.good, best.qty, System.currentTimeMillis());
        if (r == null) return null;
        Haul h = new Haul(best.from, best.to, best.good, best.qty, r.id, carrier.id);
        hauls.put(carrier.id, h);
        return h;
    }

    private boolean taken(int building) {
        for (Haul h : hauls.values()) if (h.from == building || h.to == building) return true;
        return false;
    }

    /** At the source: the reserved goods go into the carrier's satchel. */
    public boolean pickUp(Colony c, Citizen carrier, Haul h) {
        Ledger src = h.from == 0 ? c.storage : c.building(h.from) == null ? null : c.building(h.from).stock;
        if (src == null) {
            abandon(c, carrier);
            return false;
        }
        long got = src.consume(h.reservation, h.good, h.qty);
        if (got <= 0) {
            abandon(c, carrier);
            return false;
        }
        carrier.carried.force(h.good, got);
        carrier.carryTask = "haul";
        h.picked = true;
        return true;
    }

    /** At the destination: the load goes in (whatever doesn't fit goes back to central storage). */
    public void dropOff(Colony c, Citizen carrier, Haul h) {
        Ledger dst = h.to == 0 ? c.storage : c.building(h.to) == null ? c.storage : c.building(h.to).stock;
        long have = carrier.carried.get(h.good);
        long n = carrier.carried.take(h.good, have);
        long added = dst.add(h.good, n);
        if (added < n) c.storage.force(h.good, n - added);
        hauls.remove(carrier.id);
        carrier.carryTask = null;
        carrier.skills.gain(com.colonysmp.core.Skill.LOGISTICS, 0.5 * carrier.trait.learning());
    }

    /** Gives up a load: unreserves it, or puts what was picked up into central storage. */
    public void abandon(Colony c, Citizen carrier) {
        Haul h = hauls.remove(carrier.id);
        if (h == null) return;
        if (!h.picked) {
            Ledger src = h.from == 0 ? c.storage : c.building(h.from) == null ? null : c.building(h.from).stock;
            if (src != null) src.release(h.reservation);
        }
        dumpCarried(c, carrier);
    }

    /** Everything a citizen carries goes to central storage (on load, death, job change). */
    public void dumpCarried(Colony c, Citizen carrier) {
        for (Map.Entry<String, Long> e : carrier.carried.snapshot().entrySet()) c.storage.force(e.getKey(), e.getValue());
        carrier.carried.clear();
        carrier.carryTask = null;
    }

    /** A workplace is gone: loads to or from it are cancelled. */
    public void forget(Colony c, Building b) {
        for (Haul h : new ArrayList<>(hauls.values())) {
            if (h.from != b.id && h.to != b.id) continue;
            Citizen ct = c.citizens.get(h.carrier);
            if (ct != null) abandon(c, ct);
            else hauls.values().remove(h);
        }
    }

    /** After a restart: loads in progress are gone, so their reservations are freed and satchels emptied. */
    public void recover(Colony c) {
        for (Ledger l : plugin.colonies().ledgers(c)) {
            for (Reservation r : new ArrayList<>(l.reservations())) if (r.owner.startsWith("haul:")) l.release(r.id);
        }
        for (Citizen ct : c.citizens.values()) if (!ct.carried.isEmpty()) dumpCarried(c, ct);
    }

    public List<String> describe(Colony c) {
        List<String> out = new ArrayList<>();
        for (Haul h : hauls.values()) {
            Citizen ct = c.citizens.get(h.carrier);
            if (ct == null) continue;
            out.add(ct.name + ": " + h.describe(c) + (h.picked ? " (carrying)" : " (on the way to pick up)"));
        }
        return out;
    }
}
