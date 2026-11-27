package com.colonysmp.military;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.CombatMath;
import com.colonysmp.core.MilitaryClass;
import com.colonysmp.core.Morale;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Job;
import com.colonysmp.npc.Npc;
import com.colonysmp.npc.NpcManager;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Keys;
import com.colonysmp.util.Tools;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Applies the battle rules to every blow that involves a citizen fighter: class damage, shields and shield walls,
 * flanking, cavalry charges and the spears that break them, armour and what pierces it, morale, and evasion.
 */
public final class CombatListener implements Listener {

    private final ColonySMP plugin;

    public CombatListener(ColonySMP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof LivingEntity victim)) return;
        Entity src = NpcManager.source(e.getDamager());
        Npc atk = plugin.npcs().npc(src);
        Npc def = plugin.npcs().npc(victim);
        Double shot = e.getDamager() instanceof Projectile p ? p.getPersistentDataContainer().get(Keys.SHOT, PersistentDataType.DOUBLE) : null;
        if (atk == null && def == null && shot == null) return;
        CombatMath.Hit h = new CombatMath.Hit();
        h.attacker = atk != null && atk.c.job == Job.SOLDIER ? atk.c.militaryClass : null;
        h.defender = def != null && def.c.job == Job.SOLDIER ? def.c.militaryClass : null;
        h.ranged = e.getDamager() instanceof Projectile;
        h.baseDamage = shot != null ? shot : e.getDamage();
        Location vl = victim.getLocation(), al = src != null ? src.getLocation() : e.getDamager().getLocation();
        h.angle = CombatMath.angle(vl.getYaw(), al.getX() - vl.getX(), al.getZ() - vl.getZ());
        h.distance = vl.distance(al);
        h.heightAdvantage = al.getY() - vl.getY();
        h.applyArmor = def != null;
        if (atk != null) {
            h.attackerSpeed = atk.speed;
            h.attackerMounted = atk.mount != null && atk.mount.isValid();
            h.attackerMorale = atk.c.morale;
            if (atk.c.job != Job.SOLDIER) h.attackerMorale = 70;
            Material ground = atk.loc().getBlock().getType();
            if (ground == Material.WATER || atk.loc().clone().subtract(0, 1, 0).getBlock().getType() == Material.MUD) h.terrain = 0.4;
        }
        if (def != null) {
            Colony col = plugin.colonies().colonyOf(def.c);
            h.defenderMounted = def.mount != null && def.mount.isValid();
            h.defenderShield = def.c.shield != null && !def.c.downed();
            // worn armour is already counted by the game; only the class's training in armour is added here
            h.defenderArmor = 0;
            h.defenderMorale = def.c.job == Job.SOLDIER ? def.c.morale : 60;
            if (col != null) {
                h.shieldWall = plugin.military().brain().shieldWall(col, def);
                h.spearWall = plugin.military().brain().spearWall(col, def);
                plugin.military().markThreat(col);
            }
            // a nimble duelist sometimes isn't there when the blow lands
            MilitaryClass dk = h.defender;
            if (dk != null && dk.evasion > 0 && !h.ranged && ThreadLocalRandom.current().nextDouble() < dk.evasion) {
                e.setCancelled(true);
                Fx.sound(vl, "minecraft:entity.player.attack.nodamage", 0.6f, 1.3f);
                return;
            }
        } else if (victim instanceof Player p) {
            h.defenderShield = false;
            h.defenderMounted = p.isInsideVehicle();
        }
        CombatMath.Result r = CombatMath.resolve(h);
        e.setDamage(r.damage());
        if (def != null && r.shieldWear() > 0 && def.c.shield != null) {
            if (Tools.wear(def.c.shield, (int) Math.ceil(r.shieldWear()))) {
                def.c.shield = null;
                def.body.getEquipment().setItemInOffHand(null);
                Fx.sound(vl, "minecraft:item.shield.break", 0.9f, 1f);
            } else if (r.blocked()) {
                Fx.sound(vl, "minecraft:item.shield.block", 0.8f, 1f);
            }
        }
        if (r.countered() && atk != null && def != null) {
            LivingEntity hit = atk.mount != null && atk.mount.isValid() ? atk.mount : atk.body;
            hit.damage(r.counterDamage(), def.body);
            Fx.sound(vl, "minecraft:item.trident.hit", 0.9f, 0.8f);
        }
        if (r.knockback() > 0.5 && src != null) {
            Vector push = vl.toVector().subtract(al.toVector()).setY(0);
            if (push.lengthSquared() > 0.01) victim.setVelocity(push.normalize().multiply(r.knockback()).setY(0.25));
        }
        if (r.charge()) Fx.sound(vl, "minecraft:entity.horse.gallop", 1f, 0.8f);
        if (def != null && def.c.job == Job.SOLDIER && r.flanked()) {
            def.c.morale = Morale.apply(def.c.morale, Morale.Event.STRUCK_FROM_BEHIND, def.c.militaryClass == MilitaryClass.BERSERKER);
        }
        if (atk != null && def == null && victim instanceof Player) {
            Colony col = plugin.colonies().colonyOf(atk.c);
            if (col != null) plugin.military().markThreat(col);
        }
    }
}
