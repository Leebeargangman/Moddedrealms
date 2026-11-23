package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.model.Building;
import com.colonysmp.model.Colony;
import com.colonysmp.util.Fx;
import org.bukkit.Location;
import org.bukkit.Particle;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Doctors see the sick and wounded who come to their clinic (and anyone hurt close by), treating them with
 * medicine; with no patients they prepare medicine at the brewing stand.
 */
public final class Doctoring {

    /** Game ticks between treatments of the same patient. */
    private static final long COURSE = 1000;

    private final ColonySMP plugin;
    private final NpcManager m;
    private final Map<UUID, Long> treated = new HashMap<>();

    Doctoring(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    void think(Npc n, Colony col, Building clinic, long now) {
        // someone bleeding out can't wait for the round of the beds
        if (m.brain().guarding.rescue(n, col, now)) return;
        long game = plugin.sim().gameTime(col);
        Npc patient = null;
        double best = Double.MAX_VALUE;
        for (Npc o : m.of(col)) {
            if (o == n || !o.c.status.free()) continue;
            boolean needs = o.c.ill() && !o.c.treated || o.c.injured || o.patient && o.c.needs.healthFraction() < 1;
            if (!needs) continue;
            Long last = treated.get(o.c.id);
            if (last != null && game - last < COURSE && game >= last) continue;
            boolean here = o.patient || clinic.inside(o.loc().getX(), o.loc().getY(), o.loc().getZ()) || o.loc().distanceSquared(n.loc()) < 100;
            if (!here) continue;
            double d = o.loc().distanceSquared(n.loc()) - (o.patient ? 1000 : 0);
            if (d < best) {
                best = d;
                patient = o;
            }
        }
        if (patient == null) {
            m.brain().stations.craft(n, col, clinic, now);
            return;
        }
        Location at = patient.loc();
        if (!n.mover.near(at, 2)) {
            n.mover.moveTo(at, plugin.settings().walkSpeed, 1.6);
            n.doing("Going to see " + patient.c.name);
            return;
        }
        n.mover.stop();
        n.working = true;
        CitizenBrain.face(n, at);
        if (now < n.actionAt) return;
        n.actionAt = now + m.interval(3, m.efficiency(col, n.c));
        if (plugin.sim().health().treat(col, n.c, patient.c)) {
            treated.put(patient.c.id, game);
            patient.body.setHealth(Math.min(patient.c.needs.maxHealth, patient.c.needs.health));
            at.getWorld().spawnParticle(Particle.HEART, at.clone().add(0, 1.8, 0), 3, 0.3, 0.2, 0.3, 0);
            Fx.sound(at, "minecraft:entity.villager.work_cleric", 0.8f, 1f);
            n.doing("Treated " + patient.c.name);
        } else {
            n.doing("Can't treat " + patient.c.name + ": no medicine");
            treated.put(patient.c.id, game);
        }
    }
}
