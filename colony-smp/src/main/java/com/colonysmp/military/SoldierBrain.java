package com.colonysmp.military;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.MilitaryClass;
import com.colonysmp.core.Morale;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Job;
import com.colonysmp.model.Squad;
import com.colonysmp.model.War;
import com.colonysmp.npc.CitizenBrain;
import com.colonysmp.npc.Fighting;
import com.colonysmp.npc.Mover;
import com.colonysmp.npc.Npc;
import com.colonysmp.npc.NpcManager;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * Soldiers in body: they follow their squad's order (defend, hold, follow, patrol, charge, shield or spear wall,
 * volley, focus, flank, retreat, protect), keep their place in the squad's formation, drill at their training
 * ground when there's nothing to fight, and break and run when their morale does. Medics treat the wounded and
 * sappers blow breaches during a siege.
 */
public final class SoldierBrain {

    private final ColonySMP plugin;
    private final MilitaryManager mm;

    SoldierBrain(ColonySMP plugin, MilitaryManager mm) {
        this.plugin = plugin;
        this.mm = mm;
    }

    private NpcManager npcs() {
        return plugin.npcs();
    }

    private Fighting fighting() {
        return npcs().brain().guarding.fighting();
    }

    /** False when off duty: the citizen's ordinary routine (meals, sleep) takes over. */
    boolean think(Npc n, Colony col, long now) {
        Citizen c = n.c;
        MilitaryClass k = c.militaryClass;
        Squad q = col.squad(c.squad);
        Squad.Order order = q == null ? Squad.Order.DEFEND : q.order;
        boolean alert = mm.alert(col);
        if ((now + n.thinkOffset) % 20 == 0) morale(n, col);
        if (Morale.state(c.morale) == Morale.State.BROKEN) {
            rout(n, col, q);
            return true;
        }
        // worn out with no alarm raised: they fall out and rest (the ordinary routine naps or sleeps)
        if (!alert && n.enemy == null && order != Squad.Order.RETREAT && (n.resting || c.needs.exhausted(plugin.settings().needs))) return false;
        // nor does a standing order (hold, patrol, follow) starve anyone: the famished fall out to eat
        if (!alert && n.enemy == null && c.needs.satiety < 15 && npcs().hasFood(col)) return false;
        if (c.horse && n.mount == null && k != null && k.mounted()) mm.mount(n, col);
        if (n.enemy == null && mm.wantsGear(col, c, alert) && !n.gearChecked) {
            if (!npcs().brain().guarding.gear(n, col, alert)) return true;
        }
        if (k == MilitaryClass.MEDIC && medic(n, col, now)) return true;
        if ((k == MilitaryClass.SAPPER || k == MilitaryClass.SIEGE_ENGINEER) && sapper(n, col, now)) return true;
        double sight = k != null ? Math.max(18, k.sight) : 18;
        switch (order) {
            case RETREAT -> {
                Location to = rally(col, q);
                if (to != null) n.mover.moveTo(to, run(n), 2);
                n.enemy = null;
                n.doing("Retreating");
                return true;
            }
            case FOLLOW, PROTECT -> {
                Entity lead = q.follow == null ? null : Bukkit.getEntity(q.follow);
                if (lead == null || !lead.isValid() || lead.getWorld() != n.body.getWorld()) {
                    return defend(n, col, q, now, alert, sight);
                }
                LivingEntity t = fighting().target(n, col, now, order == Squad.Order.PROTECT ? 10 : 14, true);
                if (t != null && t.getLocation().distanceSquared(lead.getLocation()) < 16 * 16) {
                    fight(n, col, t, now, order);
                    return true;
                }
                n.enemy = null;
                Location slot = order == Squad.Order.PROTECT ? near(lead.getLocation(), n, 2.5) : slot(col, q, n, lead.getLocation(), lead.getLocation().getYaw(), true);
                hold(n, slot, run(n), order == Squad.Order.PROTECT ? "Protecting " + name(lead) : "Following " + name(lead));
                return true;
            }
            case FOCUS -> {
                Entity e = q.focus == null ? null : Bukkit.getEntity(q.focus);
                if (e instanceof LivingEntity le && le.isValid() && !le.isDead() && le.getWorld() == n.body.getWorld()) {
                    n.enemy = le;
                    fight(n, col, le, now, order);
                    return true;
                }
                q.focus = null;
                return charge(n, col, q, now);
            }
            case CHARGE -> {
                return charge(n, col, q, now);
            }
            case FLANK -> {
                Entity e = q.focus == null ? null : Bukkit.getEntity(q.focus);
                LivingEntity t = e instanceof LivingEntity le && le.isValid() ? le : fighting().target(n, col, now, 48, true);
                if (t == null) return charge(n, col, q, now);
                Location side = flankPoint(t, n);
                if (side != null && n.loc().distanceSquared(t.getLocation()) > 9 && n.loc().distanceSquared(side) > 4 && n.enemy == null) {
                    n.mover.moveTo(side, run(n), 1.5);
                    n.doing("Flanking " + name(t));
                    if (n.loc().distanceSquared(side) < 9) n.enemy = t;
                    return true;
                }
                fight(n, col, t, now, order);
                return true;
            }
            case HOLD, SHIELD_WALL, SPEAR_WALL, HOLD_FIRE, VOLLEY -> {
                Location base = rally(col, q);
                if (base == null) return defend(n, col, q, now, alert, sight);
                n.braced = order == Squad.Order.SPEAR_WALL;
                double reach = k != null && k.reach > 0 ? k.reach + 1 : 3.3;
                boolean shoots = order != Squad.Order.HOLD_FIRE && k != null && k.shooter() && c.ammo > 0;
                LivingEntity t = fighting().target(n, col, now, shoots ? k.range : reach + 2, true);
                if (t != null && (shoots || t.getLocation().distanceSquared(n.loc()) <= reach * reach)) {
                    if (shoots && t.getLocation().distanceSquared(n.loc()) > 16) {
                        fighting().engage(n, col, t, now);
                    } else if (t.getLocation().distanceSquared(n.loc()) <= reach * reach) {
                        fighting().engage(n, col, t, now);
                    }
                    n.doing(order.display + ": engaging " + name(t));
                    return true;
                }
                n.enemy = null;
                hold(n, slot(col, q, n, base, q.yaw, false), plugin.settings().walkSpeed * 1.4, order.display);
                return true;
            }
            case PATROL -> {
                LivingEntity t = fighting().target(n, col, now, 16, false);
                if (t != null) {
                    fight(n, col, t, now, order);
                    return true;
                }
                n.enemy = null;
                patrol(n, col, q, now);
                return true;
            }
            default -> {
                return defend(n, col, q, now, alert, sight);
            }
        }
    }

    // ───────────── orders ─────────────

    /** The default order: fight anything hostile on the colony's land; drill by day; rest at night. */
    private boolean defend(Npc n, Colony col, Squad q, long now, boolean alert, double sight) {
        LivingEntity t = fighting().target(n, col, now, sight, false);
        if (t != null) {
            fight(n, col, t, now, Squad.Order.DEFEND);
            return true;
        }
        n.enemy = null;
        n.braced = false;
        NpcManager.Phase ph = npcs().phase(col, n.body.getWorld());
        if (alert) {
            Location base = rally(col, q);
            if (base != null) hold(n, slot(col, q, n, base, q == null ? 0 : q.yaw, false), plugin.settings().walkSpeed * 1.4, "Standing to arms");
            return true;
        }
        if (ph == NpcManager.Phase.NIGHT || ph == NpcManager.Phase.LEISURE) return false;
        if (n.c.needs.hungry(plugin.settings().needs)) return false;
        drill(n, col, now);
        return true;
    }

    private boolean charge(Npc n, Colony col, Squad q, long now) {
        LivingEntity t = fighting().target(n, col, now, 48, true);
        if (t != null) {
            fight(n, col, t, now, Squad.Order.CHARGE);
            return true;
        }
        n.enemy = null;
        Location base = rally(col, q);
        if (base != null) hold(n, slot(col, q, n, base, q == null ? 0 : q.yaw, false), run(n), "Charging (no enemy in sight)");
        return true;
    }

    private void fight(Npc n, Colony col, LivingEntity t, long now, Squad.Order order) {
        mm.markThreat(col);
        n.braced = order == Squad.Order.SPEAR_WALL;
        fighting().engage(n, col, t, now);
        n.working = true;
    }

    private void patrol(Npc n, Colony col, Squad q, long now) {
        World w = n.body.getWorld();
        List<BlockPos> pts = q.patrol;
        if (pts.isEmpty()) {
            Location base = rally(col, q);
            if (base != null) npcs().brain().wander(n, base, 12, now, "Patrolling");
            return;
        }
        if (n.waypoint < 0 || n.waypoint >= pts.size()) n.waypoint = 0;
        Location wp = pts.get(n.waypoint).center(w);
        if (!n.mover.near(wp, 2)) {
            n.mover.moveTo(wp, plugin.settings().walkSpeed, 1.5);
            n.doing("Patrolling");
            return;
        }
        n.waypoint = (n.waypoint + 1) % pts.size();
    }

    /** Drill at the class's training building (training rises), else stand by at the barracks. */
    private void drill(Npc n, Colony col, long now) {
        MilitaryClass k = n.c.militaryClass;
        World w = n.body.getWorld();
        Building ground = null;
        BuildingType t = k == null || k.training == null ? BuildingType.TRAINING_GROUND : BuildingType.parse(k.training);
        for (Building b : col.valid(t)) {
            if (ground == null || b.anchor != null && ground.anchor != null && b.anchor.distSq(n.loc()) < ground.anchor.distSq(n.loc())) ground = b;
        }
        Location at = ground == null ? null : npcs().place(w, ground);
        if (at == null && ground != null && ground.anchor != null) at = ground.anchor.center(w);
        if (at == null) {
            for (Building b : col.valid(BuildingType.BARRACKS)) at = npcs().place(w, b);
        }
        if (at == null) at = col.coreLocation();
        if (at == null) return;
        if (!n.mover.near(at, 6)) {
            n.mover.moveTo(at, plugin.settings().walkSpeed, 4);
            n.doing("Going to drill");
            return;
        }
        npcs().brain().wander(n, at, 4, now, ground != null ? "Drilling at " + ground.label() + " (training " + Math.round(n.c.training * 100) + "%)"
                : "Drilling (no " + t.display.toLowerCase() + ")");
        n.working = true;
        mm.train(col, n.c, 0.01);
        if ((now + n.thinkOffset) % 40 == 0) {
            n.body.swingMainHand();
            if (ground != null) Fx.sound(n.loc(), "minecraft:entity.player.attack.sweep", 0.3f, 1.2f);
        }
    }

    // ───────────── specialists ─────────────

    /** Medics treat wounded soldiers nearby with medicine they carry. True while doing so. */
    private boolean medic(Npc n, Colony col, long now) {
        if (n.c.ammo <= 0) return false;
        Npc hurt = null;
        double best = 12 * 12;
        for (Npc o : npcs().of(col)) {
            if (o == n || !o.c.job.military() || o.c.downed() || o.body.getHealth() >= o.c.needs.maxHealth * 0.6) continue;
            double d = o.loc().distanceSquared(n.loc());
            if (d < best) {
                best = d;
                hurt = o;
            }
        }
        if (hurt == null) return false;
        if (!n.mover.near(hurt.loc(), 1.8)) {
            n.mover.moveTo(hurt.loc(), run(n), 1.5);
            n.doing("Running to treat " + hurt.c.name);
            return true;
        }
        n.mover.stop();
        CitizenBrain.face(n, hurt.loc());
        if (now < n.actionAt) return true;
        n.actionAt = now + 40;
        n.c.ammo--;
        double heal = plugin.settings().medicineHeal * (n.c.militaryClass.heal > 0 ? n.c.militaryClass.heal / 2 : 1);
        hurt.body.setHealth(Math.min(hurt.c.needs.maxHealth, hurt.body.getHealth() + heal));
        hurt.c.needs.health = hurt.body.getHealth();
        hurt.loc().getWorld().spawnParticle(Particle.HEART, hurt.loc().add(0, 1.8, 0), 3, 0.3, 0.2, 0.3, 0);
        n.doing("Treated " + hurt.c.name);
        return true;
    }

    /** Sappers carry siege charges to the enemy's defences during a siege and blow a breach. */
    private boolean sapper(Npc n, Colony col, long now) {
        if (n.c.ammo <= 0) return false;
        War w = plugin.wars().warOf(col);
        if (w == null || w.phase != War.Phase.ACTIVE || w.goal != War.Goal.SIEGE || !w.attackers.contains(col.id)) return false;
        Colony def = plugin.colonies().get(w.defender);
        Location core = def == null ? null : def.coreLocation();
        if (core == null || core.getWorld() != n.body.getWorld()) return false;
        boolean close = n.loc().distanceSquared(core) < 10 * 10;
        if (!close && !n.mover.blocked()) {
            n.mover.moveTo(core, run(n), 6);
            n.doing("Carrying a siege charge to " + def.name);
            return true;
        }
        if (now < n.actionAt) return true;
        n.actionAt = now + 100;
        n.c.ammo--;
        Location at = n.loc().add(n.loc().getDirection().setY(0).normalize());
        TNTPrimed tnt = at.getWorld().spawn(at, TNTPrimed.class, x -> {
            x.setFuseTicks(60);
            x.setSource(n.body);
        });
        Fx.sound(at, "minecraft:entity.tnt.primed", 1f, 1f);
        // and get clear
        Vector back = n.loc().toVector().subtract(core.toVector()).setY(0);
        if (back.lengthSquared() < 0.01) back = new Vector(1, 0, 0);
        Location away = Mover.safeSpot(n.loc().add(back.normalize().multiply(8)));
        if (away != null) n.mover.moveTo(away, run(n), 1.5);
        n.doing("Lit a siege charge!");
        return tnt.isValid();
    }

    // ───────────── morale ─────────────

    /** Once a second: morale settles toward the baseline, steadied by standard bearers and commanders nearby. */
    private void morale(Npc n, Colony col) {
        Citizen c = n.c;
        double aura = 0;
        int enemiesClose = 0;
        for (Entity e : n.body.getNearbyEntities(12, 6, 12)) {
            Npc o = npcs().npc(e);
            if (o != null && col.id.equals(o.c.colonyId) && o.c.militaryClass != null && o.c.militaryClass.aura > 0) aura += o.c.militaryClass.aura;
            if (e instanceof LivingEntity le && e.getLocation().distanceSquared(n.loc()) < 16 && fighting().hostile(n, col, le)) enemiesClose++;
        }
        double base = Morale.baseline(c.militaryClass, c.training, c.needs.mood, c.needs.satiety > 20);
        boolean fearless = c.militaryClass == MilitaryClass.BERSERKER;
        if (enemiesClose >= 3) c.morale = Morale.apply(c.morale, Morale.Event.SURROUNDED, fearless);
        c.morale = Morale.tick(c.morale, base + c.trait.morale(), Math.min(2, aura), n.enemy != null);
        Squad q = col.squad(c.squad);
        if (q != null) q.morale = q.morale * 0.9 + c.morale * 0.1;
    }

    private void rout(Npc n, Colony col, Squad q) {
        Location to = col.coreLocation();
        if (to == null) to = rally(col, q);
        if (to != null) n.mover.moveTo(to, run(n), 4);
        n.enemy = null;
        n.doing("Routed! Fleeing (morale " + Math.round(n.c.morale) + ")");
        if (Math.floorMod(n.c.id.hashCode() + (int) plugin.tick(), 20) == 0) {
            n.body.getWorld().spawnParticle(Particle.SMOKE, n.loc().add(0, 2, 0), 3, 0.2, 0.2, 0.2, 0.01);
        }
    }

    // ───────────── formations ─────────────

    private Location rally(Colony col, Squad q) {
        if (q != null && q.rally != null && q.world != null) {
            World w = Bukkit.getWorld(q.world);
            if (w != null) {
                Location l = q.rally.center(w);
                l.setYaw(q.yaw);
                return l;
            }
        }
        for (Building b : col.valid(BuildingType.BARRACKS)) {
            World w = col.world();
            if (w != null && b.anchor != null) return b.anchor.center(w);
        }
        return col.coreLocation();
    }

    /** This soldier's place in the squad's formation around a point facing a direction. */
    private Location slot(Colony col, Squad q, Npc n, Location base, float yaw, boolean behind) {
        if (q == null) return near(base, n, 3);
        List<Citizen> members = mm.members(col, q);
        int i = Math.max(0, members.indexOf(n.c)), count = Math.max(1, members.size());
        double rad = Math.toRadians(yaw);
        Vector fwd = new Vector(-Math.sin(rad), 0, Math.cos(rad));
        Vector right = new Vector(-Math.cos(rad), 0, -Math.sin(rad));
        double side, back;
        switch (q.formation) {
            case COLUMN -> {
                side = (i % 2 - 0.5) * 1.5;
                back = (i / 2) * 1.5;
            }
            case WEDGE -> {
                int r = (int) Math.floor((Math.sqrt(8.0 * i + 1) - 1) / 2);
                int j = i - r * (r + 1) / 2;
                side = (j - r / 2.0) * 1.5;
                back = r * 1.5;
            }
            case SQUARE -> {
                double a = 2 * Math.PI * i / count;
                double radius = Math.max(2, count * 0.35);
                Location l = base.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius);
                return l;
            }
            case LOOSE -> {
                int per = Math.min(5, count);
                side = (i % per - (per - 1) / 2.0) * 3;
                back = (i / per) * 3;
            }
            default -> {
                int per = Math.min(8, count);
                side = (i % per - (per - 1) / 2.0) * 1.5;
                back = (i / per) * 1.5;
            }
        }
        MilitaryClass k = n.c.militaryClass;
        if (k != null && k.shooter() && q.order == Squad.Order.SHIELD_WALL) back += 2;
        if (behind) back += 2.5;
        Location l = base.clone().add(right.clone().multiply(side)).subtract(fwd.clone().multiply(back));
        l.setYaw(yaw);
        return l;
    }

    private Location near(Location base, Npc n, double r) {
        double a = Math.floorMod(n.c.id.hashCode(), 360) * Math.PI / 180;
        return base.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
    }

    private void hold(Npc n, Location slot, double speed, String label) {
        if (slot == null) return;
        Location safe = Mover.safeSpot(slot);
        if (safe == null) safe = slot;
        if (!n.mover.near(safe, 0.9)) {
            n.mover.moveTo(safe, speed, 0.6);
        } else {
            n.mover.stop();
            n.body.setRotation(slot.getYaw(), 0);
        }
        n.doing(label);
    }

    private Location flankPoint(LivingEntity t, Npc n) {
        Location tl = t.getLocation();
        double rad = Math.toRadians(tl.getYaw());
        Vector right = new Vector(-Math.cos(rad), 0, -Math.sin(rad));
        Vector toMe = n.loc().toVector().subtract(tl.toVector());
        int side = toMe.dot(right) >= 0 ? 1 : -1;
        return Mover.safeSpot(tl.clone().add(right.multiply(5 * side)));
    }

    private double run(Npc n) {
        MilitaryClass k = n.c.militaryClass;
        return plugin.settings().runSpeed * (k != null ? k.speed : 1) * (n.mount != null ? 1.5 : 1);
    }

    private static String name(Entity e) {
        if (e instanceof Player p) return p.getName();
        if (e instanceof LivingEntity le) return com.colonysmp.npc.Guarding.label(le);
        return e.getType().name().toLowerCase();
    }

    /** Is this soldier holding a shield wall right now (in place, not chasing)? */
    public boolean shieldWall(Colony col, Npc n) {
        Squad q = col.squad(n.c.squad);
        return q != null && q.order == Squad.Order.SHIELD_WALL && !n.mover.moving() && n.c.shield != null && n.c.job == Job.SOLDIER;
    }

    public boolean spearWall(Colony col, Npc n) {
        Squad q = col.squad(n.c.squad);
        return n.braced || q != null && q.order == Squad.Order.SPEAR_WALL && !n.mover.moving();
    }
}
