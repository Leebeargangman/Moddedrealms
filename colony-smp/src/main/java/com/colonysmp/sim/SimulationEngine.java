package com.colonysmp.sim;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.GameTime;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Runs every colony, loaded or not. Citizens with a body are updated by it as they walk around; everyone else
 * is advanced here in steps: needs, meals and sleep, work for the colony's production, hauling. Once a day the
 * colony pays wages and upkeep, inspects its buildings, ages its people, and reckons with illness, crime and
 * its mood.
 *
 * <p>Time is the colony world's game time, so nothing happens while the server is down (the world doesn't move
 * either), and a colony far from any player keeps going at the same pace as one with players in it.
 */
public final class SimulationEngine {

    private final ColonySMP plugin;
    private final CitizenSim citizens;
    private final Production production;
    private final Logistics logistics;
    private final Finances finances;
    private final Reports reports;
    private final Population population;
    private final Health health;
    private final Order order;
    private final Housing housing;
    private final Jobs jobs;
    private final Academy academy;
    private final Trades trades;
    private int cursor;
    /** While an admin fast-forwards a colony: the simulated day, so everything logged in the run agrees. */
    private Colony forwarding;
    private long forwardDay;
    /** Performance: totals for the admin view. */
    private long steps, stepNanos, worstNanos;

    public SimulationEngine(ColonySMP plugin) {
        this.plugin = plugin;
        this.citizens = new CitizenSim(plugin);
        this.production = new Production(plugin);
        this.logistics = new Logistics(plugin);
        this.finances = new Finances(plugin);
        this.reports = new Reports(plugin);
        this.population = new Population(plugin);
        this.health = new Health(plugin);
        this.order = new Order(plugin);
        this.housing = new Housing(plugin);
        this.jobs = new Jobs(plugin);
        this.academy = new Academy(plugin);
        this.trades = new Trades(plugin);
    }

    public Academy academy() {
        return academy;
    }

    public Trades trades() {
        return trades;
    }

    public CitizenSim citizens() {
        return citizens;
    }

    public Production production() {
        return production;
    }

    public Logistics logistics() {
        return logistics;
    }

    public Finances finances() {
        return finances;
    }

    public Population population() {
        return population;
    }

    public Health health() {
        return health;
    }

    public Order order() {
        return order;
    }

    public Housing housing() {
        return housing;
    }

    public Jobs jobs() {
        return jobs;
    }

    public Reports reports() {
        return reports;
    }

    // ───────────── clocks ─────────────

    public long gameTime(Colony c) {
        World w = c.world();
        return w == null ? Math.max(0, c.lastSim) : w.getGameTime();
    }

    public long dayTime(Colony c) {
        World w = c.world();
        return w == null ? 6000 : w.getTime();
    }

    public long day(Colony c) {
        if (c == forwarding) return forwardDay;
        return GameTime.day(gameTime(c));
    }

    // ───────────── ticking ─────────────

    /** Every second: daily rollovers for every colony, then background steps within the time budget. */
    public void tick() {
        List<Colony> all = plugin.colonies().list();
        if (all.isEmpty()) return;
        for (Colony c : all) {
            try {
                rollover(c);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "Daily update failed for " + c.name, ex);
            }
        }
        long budget = plugin.settings().stepBudgetMs * 1_000_000L;
        long start = System.nanoTime();
        long stepTicks = plugin.settings().stepSeconds * 20L;
        for (int i = 0; i < all.size(); i++) {
            Colony c = all.get(Math.floorMod(cursor + i, all.size()));
            long now = gameTime(c);
            if (c.lastSim >= 0 && now - c.lastSim < stepTicks) continue;
            step(c);
            if (System.nanoTime() - start > budget) {
                cursor = Math.floorMod(cursor + i + 1, all.size());
                return;
            }
        }
        cursor = Math.floorMod(cursor + 1, all.size());
    }

    /** Advances one colony to the present. */
    public void step(Colony c) {
        long now = gameTime(c);
        if (c.lastSim < 0 || now < c.lastSim) {
            c.lastSim = now;
            return;
        }
        long elapsed = now - c.lastSim;
        if (elapsed <= 0) return;
        long cap = (long) (plugin.settings().maxCatchUpHours * GameTime.TICKS_PER_HOUR);
        if (elapsed > cap) {
            c.log(ColonyEvent.Type.SIMULATION, day(c), "Caught up " + Math.round(cap / 1000.0) + " of " + Math.round(elapsed / 1000.0)
                    + " hours (the rest was skipped to keep the server responsive).");
            elapsed = cap;
        }
        c.lastSim = now;
        // workers without a body can't walk to the stores: they are issued the best tool there is, if there is one
        for (com.colonysmp.model.Citizen ct : c.citizens.values()) {
            if (Jobs.wantsTool(ct) && ct.status.free() && plugin.npcs() != null && plugin.npcs().npc(ct) == null) jobs.issueTool(c, ct);
        }
        long t0 = System.nanoTime();
        try {
            simulate(c, elapsed, Math.floorMod(dayTime(c) - elapsed, GameTime.TICKS_PER_DAY));
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Simulation step failed for " + c.name, ex);
        }
        long took = System.nanoTime() - t0;
        c.lastStepMs = took / 1e6;
        steps++;
        stepNanos += took;
        worstNanos = Math.max(worstNanos, took);
    }

    /**
     * Simulates a span of time for the citizens without bodies and the colony's background systems, in chunks
     * of at most an hour so the daily routine stays right.
     */
    void simulate(Colony c, long ticks, long dayTimeStart) {
        long done = 0;
        while (done < ticks) {
            long chunk = Math.min(GameTime.TICKS_PER_HOUR, ticks - done);
            long dt = Math.floorMod(dayTimeStart + done, GameTime.TICKS_PER_DAY);
            double hours = GameTime.hours(chunk);
            CitizenSim.Context ctx = citizens.context(c);
            for (Citizen ct : new ArrayList<>(c.citizens.values())) {
                if (plugin.npcs().embodied(ct)) continue;
                citizens.background(c, ct, chunk, dt, ctx);
            }
            production.background(c, hours, chunk, dt);
            logistics.background(c, hours);
            plugin.military().background(c, hours);
            done += chunk;
        }
    }

    private void rollover(Colony c) {
        long day = day(c);
        if (c.lastDay == Long.MIN_VALUE) {
            c.lastDay = day;
            return;
        }
        if (day <= c.lastDay) return;
        // bring the background up to date first so the day's figures are complete
        step(c);
        int missed = (int) Math.min(3, day - c.lastDay);
        for (int i = missed - 1; i >= 0; i--) daily(c, day - i);
        c.lastDay = day;
    }

    /** Everything that happens once a day (at the colony world's midnight-ish day change). */
    public void daily(Colony c, long day) {
        if (c.core == null) return;
        if (c != forwarding) c.clampDays(day);
        plugin.buildings().dailyCheck(c, day);
        housing.assign(c);
        jobs.daily(c, day);
        finances.daily(c, day);
        health.daily(c, day);
        population.daily(c, day);
        order.daily(c, day);
        plugin.military().daily(c, day);
        plugin.prison().daily(c, day);
        plugin.economy().daily(c, day);
        plugin.diplomacy().daily(c, day);
        plugin.travelers().daily(c, day);
        trades.daily(c, day);
        plugin.requests().daily(c, day);
        plugin.happenings().daily(c, day);
        plugin.raids().daily(c, day);
        plugin.christmas().daily(c, day);
        c.modifiers.removeIf(m -> m.untilDay() < day);
        c.boons.values().removeIf(until -> until < day);
        report(c, day);
        for (Citizen ct : c.citizens.values()) ct.hoursToday = 0;
        c.producedToday = 0;
        c.incomeToday = 0;
        c.expensesToday = 0;
        plugin.quests().check(c);
        plugin.milestones().check(c);
        plugin.requestSave();
    }

    private void report(Colony c, long day) {
        double food = finances.foodDays(c);
        double mood = 0;
        int pop = 0;
        for (Citizen ct : c.citizens.values()) {
            if (!ct.status.free()) continue;
            mood += ct.needs.mood;
            pop++;
        }
        mood = pop == 0 ? 0 : mood / pop;
        // the day just ended (its wages and upkeep are booked to it)
        long income = 0, expenses = 0;
        for (var e : c.treasury.yesterday(day).entrySet()) {
            if (e.getValue() > 0) income += e.getValue();
            else expenses -= e.getValue();
        }
        c.addStats(new Colony.DayStats(day, pop, food, c.treasury.balance(), mood, c.stability, c.producedToday, income, expenses));
        reports.daily(c, day, food, mood, income, expenses);
    }

    // ───────────── admin ─────────────

    /** Fast-forwards a colony's background simulation by some hours (testing offline progress). */
    public void fastForward(Colony c, double hours) {
        long ticks = (long) (hours * GameTime.TICKS_PER_HOUR);
        long dt = dayTime(c);
        long done = 0;
        forwardDay = day(c);
        forwarding = c;
        try {
            while (done < ticks) {
                long chunk = Math.min(GameTime.TICKS_PER_DAY - Math.floorMod(dt + done, GameTime.TICKS_PER_DAY), ticks - done);
                simulate(c, chunk, Math.floorMod(dt + done, GameTime.TICKS_PER_DAY));
                done += chunk;
                if (Math.floorMod(dt + done, GameTime.TICKS_PER_DAY) == 0) daily(c, ++forwardDay);
            }
        } finally {
            forwarding = null;
        }
    }

    public String perf() {
        double avg = steps == 0 ? 0 : stepNanos / 1e6 / steps;
        return String.format(java.util.Locale.ROOT, "%d steps, %.2f ms average, %.2f ms worst", steps, avg, worstNanos / 1e6);
    }
}
