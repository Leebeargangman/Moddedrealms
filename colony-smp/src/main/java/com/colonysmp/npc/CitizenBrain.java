package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.building.BuildingManager;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Job;
import com.colonysmp.model.LifeStage;
import com.colonysmp.model.Policies;
import com.colonysmp.model.Status;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Text;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Villager;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Decides what each citizen in body does, by priority: danger first (flee, shelter, or take up arms), then
 * urgent needs (starving, exhausted, badly hurt), then the day's routine: work in working hours, meals and
 * company in the evening, sleep at night. Each job has its own behaviour.
 */
public final class CitizenBrain {

    private final ColonySMP plugin;
    private final NpcManager m;
    final Life life;
    public final Farming farming;
    public final Logging logging;
    public final Mining mining;
    public final Construction construction;
    public final Guarding guarding;
    public final Schooling schooling;
    public final Fishing fishing;
    public final Herding herding;
    public final Doctoring doctoring;
    public final StationWork stations;
    public final Hauling hauling;
    public final Specialists specialists;

    CitizenBrain(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
        this.life = new Life(plugin, m);
        this.farming = new Farming(plugin, m);
        this.logging = new Logging(plugin, m);
        this.mining = new Mining(plugin, m);
        this.construction = new Construction(plugin, m);
        this.guarding = new Guarding(plugin, m);
        this.schooling = new Schooling(plugin, m);
        this.fishing = new Fishing(plugin, m);
        this.herding = new Herding(plugin, m);
        this.doctoring = new Doctoring(plugin, m);
        this.stations = new StationWork(plugin, m);
        this.hauling = new Hauling(plugin, m);
        this.specialists = new Specialists(plugin, m);
    }

    /** Cached plans that depend on a colony's buildings are dropped. */
    void forget(Colony col) {
        farming.forget(col);
        fishing.forget(col);
    }

    void think(Npc n, long now) {
        Colony col = plugin.colonies().colonyOf(n.c);
        if (col == null) {
            m.despawn(n);
            return;
        }
        m.rename(n);
        life.tick(n, col, plugin.sim().gameTime(col));
        n.working = false;
        // what the background simulation was waiting for no longer applies to a body; behaviours set their own
        n.c.waiting = null;
        if (n.c.downed()) {
            n.mover.stop();
            n.doing("Downed");
            return;
        }
        switch (n.c.status) {
            case CAPTIVE, PRISONER -> plugin.prison().think(n, col, now);
            case RIOTER -> riot(n, col, now);
            case CITIZEN -> citizen(n, col, now);
        }
        m.showHand(n);
    }

    private void citizen(Npc n, Colony col, long now) {
        Citizen c = n.c;
        World w = n.body.getWorld();
        NpcManager.Phase ph = m.phase(col, w);
        var p = plugin.settings().needs;
        // (the watch sleeps when it can, whatever the hour)
        if (n.sleeping && ph != NpcManager.Phase.NIGHT && c.job != Job.GUARD) wake(n);
        if (n.resting && c.job != Job.GUARD && (ph == NpcManager.Phase.WORK && !c.needs.exhausted(p) || c.needs.energy > p.tired() + 20)) n.resting = false;
        if (n.patient && !c.injured && c.needs.healthFraction() > 0.8 && !c.ill()) n.patient = false;
        // the army and the watch have their own orders
        if (c.job == Job.SOLDIER) {
            if (n.sleeping && plugin.military().alert(col)) wake(n);
            if (!n.sleeping && plugin.military().think(n, col, now)) return;
            if (n.sleeping) return;
        }
        if (c.job == Job.GUARD) {
            guard(n, col, now);
            return;
        }
        // 1. danger
        if (danger(n, col, now)) return;
        // 2. urgent needs
        if (c.needs.satiety < 15 && life.meal(n, col, now, true)) return;
        if (n.patient || (c.injured || c.needs.healthFraction() < 0.5) && clinic(n, col, now)) return;
        if (c.needs.exhausted(p) && ph != NpcManager.Phase.NIGHT || n.resting && ph != NpcManager.Phase.NIGHT) {
            nap(n, col, now);
            return;
        }
        LifeStage stage = plugin.sim().citizens().stage(c);
        if (stage == LifeStage.CHILD) {
            child(n, col, now, ph);
            return;
        }
        switch (ph) {
            case WORK -> {
                if (c.needs.hungry(p) && life.meal(n, col, now, false)) return;
                if (!plugin.sim().citizens().canWork(col, c)) {
                    unable(n, col, now);
                    return;
                }
                if (col.protest && Math.floorMod(c.id.hashCode(), 3) == 0) {
                    protest(n, col, now);
                    return;
                }
                work(n, col, now);
            }
            case LEISURE -> leisure(n, col, now);
            case NIGHT -> sleep(n, col, now);
        }
    }

    /**
     * The watch. An enemy in sight comes first, then someone down who can be helped up; with no alarm sounding
     * a guard also looks after themselves (a meal, the clinic, a bed when tired, at any hour); otherwise they
     * answer the alarm or walk their rounds.
     */
    private void guard(Npc n, Colony col, long now) {
        Citizen c = n.c;
        var p = plugin.settings().needs;
        boolean alert = plugin.military().alert(col);
        if (n.sleeping && (alert || c.needs.energy > 85 || c.needs.satiety < 15)) wake(n);
        if (n.sleeping) {
            n.doing("Sleeping off duty");
            return;
        }
        if (guarding.fight(n, col, now)) return;
        if (!c.injured && guarding.rescue(n, col, now)) return;
        if (!alert) {
            if (c.needs.satiety < 15 && life.meal(n, col, now, true)) return;
            if (n.patient || (c.injured || c.needs.healthFraction() < 0.5) && clinic(n, col, now)) return;
            if (c.needs.hungry(p) && life.meal(n, col, now, false)) return;
            // tired: to bed, and (with no bed to wake rested in) stay down until properly rested
            if (c.needs.tired(p) || n.resting && c.needs.energy < 70) {
                sleep(n, col, now);
                return;
            }
        } else if (c.needs.starving() && life.meal(n, col, now, true)) {
            return;
        }
        n.patient = false;
        n.resting = false;
        guarding.duty(n, col, now);
    }

    // ───────────── work ─────────────

    private void work(Npc n, Colony col, long now) {
        Citizen c = n.c;
        Building b = col.building(c.workplace);
        if (c.job.needsWorkplace()) {
            if (b == null) {
                String where = c.job.workplaces().isEmpty() ? "" : " (" + Text.a(c.job.workplaces().get(0).display) + ")";
                idle(n, col, now, "No workplace" + where + " with a free place");
                return;
            }
            if (!b.valid) {
                wanderAt(n, col, b, now, b.label() + " failed its inspection: " + b.problem);
                return;
            }
        }
        // a load in hand is finished first
        if (hauling.busy(n, col, now)) return;
        if (b != null && !m.hasCourier(col) && hauling.selfHaul(n, col, b, now)) return;
        switch (c.job) {
            case FARMER -> farming.think(n, col, b, now);
            case ANIMAL_KEEPER -> herding.think(n, col, b, now);
            case FORESTER -> logging.think(n, col, b, now);
            case MINER -> mining.think(n, col, b, now);
            case FISHER -> fishing.think(n, col, b, now);
            case BUILDER -> {
                if (!construction.think(n, col, b, now) && !m.returnCarried(n, col)) stations.attend(n, col, b, now, "No construction orders: waiting at " + b.label());
            }
            case WAREHOUSE_WORKER, FOOD_DISTRIBUTOR -> {
                if (!hauling.think(n, col, null, now)) stations.attend(n, col, b, now, "Nothing to carry: waiting at " + b.label());
            }
            case TEACHER -> schooling.teach(n, col, b, now);
            case STUDENT -> {
                if (!schooling.study(n, col, now)) idle(n, col, now, "No school with a teacher");
            }
            case DOCTOR -> doctoring.think(n, col, b, now);
            case WARDEN -> {
                if (!plugin.prison().warden(n, col, b, now)) stations.attend(n, col, b, now, "Keeping " + b.label());
            }
            case RESEARCHER -> specialists.research(n, col, b, now);
            case BARD -> specialists.perform(n, col, b, now);
            case DRUID -> specialists.tend(n, col, b, now);
            case ENCHANTER -> specialists.enchant(n, col, b, now);
            case BEEKEEPER -> specialists.bees(n, col, b, now);
            case UNEMPLOYED -> {
                // idle hands carry: the jobless help move goods rather than stand about
                if (!hauling.think(n, col, null, now)) idle(n, col, now, "Unemployed: waiting for a job");
            }
            default -> {
                if (c.job.background == Job.Background.RECIPE) stations.craft(n, col, b, now);
                else stations.attend(n, col, b, now, null);
            }
        }
    }

    /** Can't work (unpaid, ill under quarantine, too weak): strike, rest at home or recover. */
    private void unable(Npc n, Colony col, long now) {
        Citizen c = n.c;
        if (c.unpaidDays >= 3 && col.policies.economy == Policies.Economy.WAGES) {
            strike(n, col, now, "On strike: unpaid for " + c.unpaidDays + " days");
            return;
        }
        if (c.ill() && col.policies.quarantine) {
            homeRest(n, col, now, "Staying home ill (quarantine)");
            return;
        }
        homeRest(n, col, now, "Too weak to work");
    }

    // ───────────── danger ─────────────

    /** Civilians run from monsters and enemies; in a war they shelter or take up arms as policy says. */
    private boolean danger(Npc n, Colony col, long now) {
        LivingEntity threat = null;
        double best = 64;
        for (Entity e : n.body.getNearbyEntities(8, 4, 8)) {
            if (!(e instanceof LivingEntity le) || le.isDead()) continue;
            boolean hostile = e instanceof Enemy && !NpcManager.isBody(e) || guarding.hostile(n, col, le);
            if (!hostile) continue;
            double d = e.getLocation().distanceSquared(n.loc());
            if (d < best) {
                best = d;
                threat = le;
            }
        }
        boolean war = plugin.wars().fighting(col) || plugin.military().threatened(col);
        Policies.Emergency policy = col.policies.emergency;
        if (war && policy == Policies.Emergency.MILITIA && plugin.sim().citizens().stage(n.c) == LifeStage.ADULT && !n.c.injured) {
            if (n.sleeping) wake(n);
            return guarding.militia(n, col, now);
        }
        if (threat == null) {
            if (war && policy == Policies.Emergency.SHELTER) {
                homeRest(n, col, now, "Sheltering from the fighting");
                return true;
            }
            return false;
        }
        if (n.sleeping) return false; // safe in bed
        // raise the alarm where it happened: the watch comes running
        plugin.military().callWatch(col, threat.getLocation());
        String[] where = new String[1];
        Location to = refuge(n, col, threat, where);
        if (to == null) {
            Vector away = n.loc().toVector().subtract(threat.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
            away.normalize().multiply(8);
            to = Mover.safeSpot(n.loc().add(away));
        }
        if (to != null) n.mover.moveTo(to, plugin.settings().runSpeed, 1.5);
        n.doing("Fleeing from " + Guarding.label(threat) + (where[0] == null ? "" : " " + where[0]));
        return true;
    }

    /**
     * Somewhere safer than here to run to: behind an armed defender, home, or the Town Hall, whichever is
     * nearest and doesn't mean running past the threat. Null when there's nowhere better than "away".
     */
    private Location refuge(Npc n, Colony col, LivingEntity threat, String[] where) {
        Location me = n.loc(), danger = threat.getLocation();
        World w = n.body.getWorld();
        double mine = me.distanceSquared(danger);
        Location best = null;
        double bd = 48 * 48;
        for (Npc o : m.of(col)) {
            if (o == n || !o.c.job.military() || !o.c.status.free() || o.c.downed() || o.sleeping || o.body.getWorld() != w) continue;
            Location l = o.loc();
            double d = l.distanceSquared(me);
            // a defender who is nearer to me than to the danger, and not already at my elbow
            if (d < 9 || d >= bd || d >= l.distanceSquared(danger)) continue;
            bd = d;
            best = l;
            where[0] = "to " + o.c.name;
        }
        Location home = n.c.bed != null ? n.c.bed.center(w) : null, hall = col.coreLocation();
        for (Location l : new Location[]{home, hall}) {
            if (l == null || l.getWorld() != w) continue;
            double d = l.distanceSquared(me);
            if (d < 9 || d >= bd || l.distanceSquared(danger) <= mine) continue;
            bd = d;
            best = l;
            where[0] = l == home ? "for home" : "to the Town Hall";
        }
        return best == null ? null : Mover.safeSpot(best);
    }

    // ───────────── needs ─────────────

    /** Badly hurt: to a clinic bed, if there's a clinic with a doctor. False if there isn't. */
    private boolean clinic(Npc n, Colony col, long now) {
        Building cl = plugin.sim().citizens().staffed(col, BuildingType.CLINIC);
        if (cl == null || cl.beds.isEmpty()) {
            n.patient = false;
            return false;
        }
        World w = n.body.getWorld();
        BlockPos bed = cl.beds.get(Math.floorMod(n.c.id.hashCode(), cl.beds.size()));
        Location bl = bed.center(w);
        if (!n.mover.near(bl, 1.9)) {
            n.mover.moveTo(bl, plugin.settings().walkSpeed * 0.8, 1.4);
            n.doing("Going to " + cl.label() + " to be treated");
            return true;
        }
        n.mover.stop();
        n.patient = true;
        n.doing("Being treated at " + cl.label());
        return true;
    }

    private void nap(Npc n, Colony col, long now) {
        if (n.c.bed != null && BuildingManager.bedExists(n.body.getWorld(), n.c.bed)) {
            sleep(n, col, now);
            if (n.sleeping) {
                n.doing("Napping: exhausted");
                return;
            }
        }
        n.mover.stop();
        n.resting = true;
        n.doing("Resting: exhausted");
    }

    private void homeRest(Npc n, Colony col, long now, String label) {
        World w = n.body.getWorld();
        Location at = n.c.bed != null ? n.c.bed.center(w) : col.coreLocation();
        if (at == null) return;
        if (!n.mover.near(at, 3)) {
            n.mover.moveTo(at, plugin.settings().walkSpeed, 2);
            n.doing(label);
            return;
        }
        n.mover.stop();
        n.resting = true;
        n.doing(label);
    }

    // ───────────── routine ─────────────

    private void leisure(Npc n, Colony col, long now) {
        Citizen c = n.c;
        var p = plugin.settings().needs;
        if ((c.needs.hungry(p) || c.lastMealDay != m.context(col).day) && life.meal(n, col, now, false)) return;
        if (m.returnCarried(n, col)) return;
        // the bard's evening is the tavern's busiest hour
        if (c.job == Job.BARD && plugin.sim().citizens().canWork(col, c)) {
            Building tavern = col.building(c.workplace);
            if (tavern != null && tavern.valid) {
                specialists.perform(n, col, tavern, now);
                return;
            }
        }
        // somewhere with people: the tavern, an inn, the restaurant, the market, the Town Hall
        World w = n.body.getWorld();
        Location spot = null;
        String label = "Spending the evening at the Town Hall";
        // at Christmas, half the colony is to be found round the tree
        if (plugin.christmas().decorated(col) && Math.floorMod(c.id.hashCode() + m.context(col).day, 2) == 0) {
            Location tree = plugin.christmas().treeLocation(col);
            if (tree != null && tree.getWorld() == w) {
                wander(n, tree, 5, now, "Admiring the Christmas tree");
                return;
            }
        }
        Building library = c.needs.social >= 70 || c.trait.social() <= 0 ? plugin.sim().trades().library(col) : null;
        if (library != null && c.education < 70 && Math.floorMod(c.id.hashCode() + m.context(col).day, 3) != 0 && m.place(w, library) != null) {
            wander(n, m.place(w, library), 3, now, "Reading at " + library.label());
            return;
        }
        if (c.needs.social < 70 && c.trait.social() > 0) {
            for (BuildingType t : new BuildingType[]{BuildingType.TAVERN, BuildingType.INN, BuildingType.RESTAURANT, BuildingType.MARKET}) {
                Building b = plugin.sim().citizens().staffed(col, t);
                if (b == null) continue;
                spot = m.place(w, b);
                label = "Spending the evening at " + b.label();
                break;
            }
            if (spot == null) spot = col.coreLocation();
        } else if (c.bed != null) {
            spot = c.bed.center(w);
            label = "Relaxing at home";
        }
        if (spot == null) spot = col.coreLocation();
        if (spot == null) return;
        wander(n, spot, 5, now, label);
    }

    public void sleep(Npc n, Colony col, long now) {
        if (n.sleeping) {
            n.doing("Sleeping");
            return;
        }
        if (m.returnCarried(n, col)) return;
        World w = n.body.getWorld();
        BlockPos bed = n.c.bed;
        if (bed == null || !BuildingManager.bedExists(w, bed)) {
            Location core = col.coreLocation();
            if (core != null && !n.mover.near(core, 6)) {
                n.mover.moveTo(m.spotNear(core, 2, 5), plugin.settings().walkSpeed, 1.5);
                n.doing("Homeless: looking for somewhere to sleep");
                return;
            }
            n.mover.stop();
            n.resting = true;
            n.doing("Sleeping rough: no bed in a registered house");
            return;
        }
        Location bl = bed.center(w);
        if (!n.mover.near(bl, 1.9)) {
            n.mover.moveTo(bl, plugin.settings().walkSpeed, 1.4);
            n.doing("Going to bed");
            return;
        }
        n.mover.stop();
        if (n.body instanceof Villager v) {
            try {
                if (v.sleep(bl)) {
                    n.sleeping = true;
                    n.resting = false;
                    n.doing("Sleeping");
                    return;
                }
            } catch (IllegalStateException | IllegalArgumentException ignored) {
                // the bed is taken or broken: rest beside it
            }
        }
        n.resting = true;
        n.doing("Resting: the bed is occupied");
    }

    public void wake(Npc n) {
        if (n.body instanceof Villager v && v.isSleeping()) {
            try {
                v.wakeup();
            } catch (IllegalStateException ignored) {
                // already up
            }
        }
        n.sleeping = false;
        n.resting = false;
        n.mover.sync();
    }

    private void child(Npc n, Colony col, long now, NpcManager.Phase ph) {
        if (ph == NpcManager.Phase.NIGHT) {
            sleep(n, col, now);
            return;
        }
        var p = plugin.settings().needs;
        if (n.c.needs.hungry(p) && life.meal(n, col, now, false)) return;
        if (ph == NpcManager.Phase.WORK && schooling.study(n, col, now)) return;
        World w = n.body.getWorld();
        Location home = n.c.bed != null ? n.c.bed.center(w) : col.coreLocation();
        if (plugin.christmas().decorated(col)) {
            Location tree = plugin.christmas().treeLocation(col);
            if (tree != null && tree.getWorld() == w) {
                wander(n, tree, 7, now, "Playing in the snow by the Christmas tree");
                plugin.christmas().snowball(n, col);
                return;
            }
        }
        if (home == null) return;
        wander(n, home, 7, now, "Playing");
        if (ThreadLocalRandom.current().nextInt(40) == 0) w.spawnParticle(Particle.HEART, n.loc().add(0, 1.2, 0), 1, 0.2, 0.2, 0.2, 0);
    }

    // ───────────── unrest ─────────────

    private void protest(Npc n, Colony col, long now) {
        strike(n, col, now, "Protesting: " + plugin.sim().order().worstFactor(col));
    }

    private void strike(Npc n, Colony col, long now, String label) {
        Location core = col.coreLocation();
        if (core == null) return;
        wander(n, core, 6, now, label);
        if (ThreadLocalRandom.current().nextInt(12) == 0) {
            n.body.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, n.loc().add(0, 2.1, 0), 3, 0.3, 0.2, 0.3, 0);
        }
    }

    private void riot(Npc n, Colony col, long now) {
        if (n.sleeping) wake(n);
        // even rioters eat (they help themselves)
        if (n.c.needs.satiety < 15 && life.meal(n, col, now, true)) return;
        Location target = m.storagePoint(col, n.loc());
        if (target == null) target = col.coreLocation();
        if (target == null) return;
        n.mover.moveTo(n.idleSpot != null && now < n.idleUntil ? n.idleSpot : target, plugin.settings().runSpeed, 3);
        if (n.idleSpot == null || now > n.idleUntil) {
            n.idleSpot = m.spotNear(target, 2, 8);
            n.idleUntil = now + 100;
        }
        n.doing("Rioting!");
        if (ThreadLocalRandom.current().nextInt(6) == 0) {
            n.body.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, n.loc().add(0, 2.1, 0), 4, 0.3, 0.2, 0.3, 0);
        }
    }

    // ───────────── idling ─────────────

    /** Wanders around the Town Hall. */
    public void idle(Npc n, Colony col, long now, String label) {
        Location core = col.coreLocation();
        if (core == null) return;
        wander(n, core, 8, now, label);
    }

    /** Waits around a workplace. */
    public void wanderAt(Npc n, Colony col, Building b, long now, String label) {
        World w = n.body.getWorld();
        Location at = b == null ? null : m.place(w, b);
        if (at == null) {
            idle(n, col, now, label);
            return;
        }
        wander(n, at, 3, now, label);
    }

    /** Wanders around a point. */
    public void wander(Npc n, Location center, int radius, long now, String label) {
        if (n.idleSpot == null || now > n.idleUntil || n.idleSpot.getWorld() != center.getWorld()
                || n.idleSpot.distanceSquared(center) > radius * radius * 2.0) {
            n.idleSpot = m.spotNear(center, 1, radius);
            n.idleUntil = now + 160 + ThreadLocalRandom.current().nextInt(240);
        }
        n.mover.moveTo(n.idleSpot, plugin.settings().walkSpeed * 0.7, 1.2);
        n.doing(label);
    }

    /** Turns the body to look at a point. */
    public static void face(Npc n, Location at) {
        Location l = n.loc();
        double dx = at.getX() - l.getX(), dz = at.getZ() - l.getZ();
        if (dx * dx + dz * dz < 1e-4) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        n.body.setRotation(yaw, 0);
    }
}
