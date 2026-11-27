package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.MilitaryClass;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Policies;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Keys;
import com.colonysmp.util.Tools;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Trident;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/**
 * How citizens fight: who counts as an enemy, closing to reach, shooting from range, and striking. The damage
 * rules between classes (shields, flanking, charges, spear walls...) are applied when the blow lands, in the
 * military listener, from {@link com.colonysmp.core.CombatMath}.
 */
public final class Fighting {

    private final ColonySMP plugin;
    private final NpcManager m;

    public Fighting(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    // ───────────── enemies ─────────────

    /** Is this an enemy of this citizen's colony? */
    public boolean hostile(Npc self, Colony col, LivingEntity e) {
        if (e == null || e == self.body || plugin.prison().isDowned(e)) return false;
        if (e instanceof Player p) {
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return false;
            if (col.isMember(p.getUniqueId())) return false;
            if (m.aggressor(col, p)) return true;
            if (col.policies.posture == Policies.Posture.PASSIVE) return false;
            Colony pc = plugin.colonies().of(p);
            return pc != null && plugin.wars().atWar(pc, col);
        }
        Npc o = m.npc(e);
        if (o != null) {
            if (o.c.downed()) return false;
            Colony oc = plugin.colonies().colonyOf(o.c);
            if (oc == col) return false;
            return oc != null && plugin.wars().atWar(oc, col);
        }
        if (plugin.travelers().isTraveler(e)) return false;
        return e instanceof Enemy;
    }

    /** How far beyond the colony's land its fighters pursue, by posture. */
    public double reach(Colony col) {
        return switch (col.policies.posture) {
            case PASSIVE -> 2;
            case DEFENSIVE -> 8;
            case AGGRESSIVE -> 32;
        };
    }

    /** The nearest enemy in sight, within the colony's land and its posture's reach. */
    public LivingEntity target(Npc n, Colony col, long now, double range, boolean anywhere) {
        if (n.enemy != null && valid(n, col, n.enemy, anywhere) && now < n.enemyCheckAt + 40) return n.enemy;
        n.enemyCheckAt = now;
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        Location here = n.loc();
        for (Entity e : n.body.getNearbyEntities(range, 10, range)) {
            if (!(e instanceof LivingEntity le) || !valid(n, col, le, anywhere)) continue;
            double d = le.getLocation().distanceSquared(here);
            if (d > 16 && !n.body.hasLineOfSight(le)) continue;
            // not simply the nearest: whoever is at a citizen's throat, the leader, the nearly beaten
            double s = com.colonysmp.core.CombatMath.urgency(d, attackingOurs(col, le), le.isGlowing() && com.colonysmp.raid.RaidManager.isRaider(le), healthFraction(le));
            if (s < bd) {
                bd = s;
                best = le;
            }
        }
        n.enemy = best;
        return best;
    }

    /** Is this enemy going for one of the colony's people or members right now? */
    private boolean attackingOurs(Colony col, LivingEntity e) {
        if (!(e instanceof org.bukkit.entity.Mob mob)) return false;
        LivingEntity t = mob.getTarget();
        if (t == null) return false;
        if (t instanceof Player p) return col.isMember(p.getUniqueId());
        Npc o = m.npc(t);
        return o != null && col.id.equals(o.c.colonyId);
    }

    private static double healthFraction(LivingEntity e) {
        var max = e.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        double m = max == null ? 20 : max.getValue();
        return m <= 0 ? 1 : e.getHealth() / m;
    }

    /** Standing in a formation that mustn't be broken (hold, walls, volleys)? */
    private static boolean holding(Colony col, Citizen c) {
        var q = col.squad(c.squad);
        if (q == null || c.job != com.colonysmp.model.Job.SOLDIER) return false;
        return switch (q.order) {
            case HOLD, SHIELD_WALL, SPEAR_WALL, HOLD_FIRE, VOLLEY -> true;
            default -> false;
        };
    }

    private boolean valid(Npc n, Colony col, LivingEntity e, boolean anywhere) {
        if (e == null || !e.isValid() || e.isDead() || e.getWorld() != n.body.getWorld()) return false;
        if (!anywhere && col.territory.distanceTo(e.getX(), e.getZ()) > reach(col)) return false;
        return hostile(n, col, e);
    }

    // ───────────── fighting ─────────────

    /** Closes in and strikes, or shoots from range. */
    public void engage(Npc n, Colony col, LivingEntity t, long now) {
        Citizen c = n.c;
        MilitaryClass k = c.militaryClass;
        Location me = n.loc(), them = t.getLocation();
        double d = me.distance(them);
        double range = k != null && k.shooter() ? k.range : c.ranged != null ? 20 : 0;
        boolean canShoot = range > 0 && c.ammo > 0 && (c.ranged != null || k == MilitaryClass.JAVELINEER || k == MilitaryClass.SLINGER);
        n.doing("Fighting " + Guarding.label(t));
        if (canShoot && d > 4.5 && d < range && n.body.hasLineOfSight(t)) {
            n.mover.stop();
            CitizenBrain.face(n, them);
            int reload = k != null && k.reloadTicks > 0 ? k.reloadTicks : 30;
            if (now - n.lastShot >= reload) {
                n.lastShot = now;
                shoot(n, t, k);
            }
            m.showHand(n);
            return;
        }
        // an archer with nothing to hit back with doesn't wait to be cut down: back off and keep shooting
        if (canShoot && d <= 4.5 && c.weapon == null && n.mount == null && !holding(col, c)) {
            Vector away = me.toVector().subtract(them.toVector()).setY(0);
            if (away.lengthSquared() > 0.01) {
                Location to = Mover.safeSpot(me.clone().add(away.normalize().multiply(7)));
                if (to != null && to.distanceSquared(them) > d * d + 4) {
                    n.mover.moveTo(to, plugin.settings().runSpeed, 1.2);
                    n.doing("Falling back from " + Guarding.label(t));
                    int reload = k != null && k.reloadTicks > 0 ? k.reloadTicks : 30;
                    if (d > 2.2 && now - n.lastShot >= reload && n.body.hasLineOfSight(t)) {
                        n.lastShot = now;
                        shoot(n, t, k);
                    }
                    m.showHand(n);
                    return;
                }
            }
        }
        double reach = k != null && k.reach > 0 ? k.reach : 2.3;
        double speed = plugin.settings().runSpeed * (k != null ? k.speed : 1) * (n.mount != null ? 1.5 : 1);
        if (d > reach) {
            n.mover.moveTo(them, speed, Math.max(1.2, reach - 0.6));
            return;
        }
        n.mover.stop();
        CitizenBrain.face(n, them);
        int every = k != null && k.attackTicks > 0 ? k.attackTicks : 16;
        if (now - n.lastAttack >= every) {
            n.lastAttack = now;
            hit(n, col, t);
        }
        m.showHand(n);
    }

    /** Training and courage make blows land harder. */
    public static double strength(Citizen c) {
        return c.trait.combat() * (1 + Math.min(1, c.training) * 0.3);
    }

    private void hit(Npc n, Colony col, LivingEntity t) {
        Citizen c = n.c;
        double dmg = Tools.meleeDamage(c.weapon) * strength(c) * (1 + col.bonus(com.colonysmp.core.Research.Effect.DAMAGE));
        n.body.swingMainHand();
        t.damage(dmg, n.body);
        Fx.sound(n.loc(), "minecraft:entity.player.attack.strong", 0.7f, 1f);
        if (c.weapon != null && Tools.wear(c.weapon, 1)) {
            c.weapon = null;
            Fx.sound(n.loc(), "minecraft:entity.item.break", 0.8f, 1f);
            m.showHand(n);
        }
    }

    private void shoot(Npc n, LivingEntity t, MilitaryClass k) {
        Citizen c = n.c;
        Location eye = n.body.getEyeLocation();
        Location aim = t.getEyeLocation().subtract(0, 0.4, 0);
        double dist = eye.distance(aim);
        Vector dir = aim.toVector().subtract(eye.toVector());
        dir.setY(dir.getY() + dist * (k == MilitaryClass.JAVELINEER ? 0.18 : 0.12));
        dir.normalize();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double spread = 0.05 * (1.2 - Math.min(1, c.training) * 0.6);
        dir.add(new Vector(r.nextGaussian() * spread, r.nextGaussian() * spread * 0.5, r.nextGaussian() * spread)).normalize();
        Colony col = plugin.colonies().colonyOf(c);
        double base = (k != null && k.ranged > 0 ? k.ranged : 2.5) * strength(c) * (1 + (col == null ? 0 : col.bonus(com.colonysmp.core.Research.Effect.DAMAGE)));
        Projectile p;
        if (k == MilitaryClass.JAVELINEER) {
            Trident tr = n.body.launchProjectile(Trident.class, dir.multiply(1.8));
            tr.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            p = tr;
        } else if (k == MilitaryClass.SLINGER) {
            p = n.body.launchProjectile(Snowball.class, dir.multiply(1.9));
            if (p instanceof Snowball s) s.setItem(new org.bukkit.inventory.ItemStack(org.bukkit.Material.COBBLESTONE));
        } else {
            Arrow a = n.body.launchProjectile(Arrow.class, dir.multiply(k == MilitaryClass.CROSSBOWMAN ? 3.0 : k == MilitaryClass.LONGBOWMAN ? 2.6 : 2.2));
            a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            if (k == MilitaryClass.CROSSBOWMAN) a.setPierceLevel(1);
            if (c.ranged != null) {
                int power = c.ranged.getEnchantmentLevel(Enchantment.POWER);
                if (power > 0) base += 0.5 * power + 0.5;
            }
            a.setDamage(base);
            p = a;
        }
        p.getPersistentDataContainer().set(Keys.SHOT, PersistentDataType.DOUBLE, base);
        Fx.sound(eye, k == MilitaryClass.CROSSBOWMAN ? "minecraft:item.crossbow.shoot" : k == MilitaryClass.JAVELINEER ? "minecraft:item.trident.throw"
                : "minecraft:entity.arrow.shoot", 0.8f, 1f);
        c.ammo--;
        if (c.ranged != null && Tools.wear(c.ranged, 1)) {
            c.ranged = null;
            Fx.sound(eye, "minecraft:entity.item.break", 0.8f, 1f);
        }
        m.showHand(n);
    }
}
