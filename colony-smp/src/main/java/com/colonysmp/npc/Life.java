package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.GameTime;
import com.colonysmp.core.Needs;
import com.colonysmp.model.Building;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Job;
import com.colonysmp.sim.CitizenSim;
import com.colonysmp.util.Fx;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;

/**
 * Needs for citizens in body: the same rules as the background simulation ({@link CitizenSim}), applied as
 * they live: hunger and tiredness from what they're actually doing, sleep in their real bed, company from the
 * people actually around them, meals eaten where they actually are.
 */
final class Life {

    private final ColonySMP plugin;
    private final NpcManager m;

    Life(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    private Needs.Params p() {
        return plugin.settings().needs;
    }

    /** Brings a body's needs up to the present (called every think). */
    void tick(Npc n, Colony col, long gameNow) {
        Citizen c = n.c;
        if (n.lastLife < 0 || gameNow < n.lastLife) {
            n.lastLife = gameNow;
            return;
        }
        long dt = Math.min(gameNow - n.lastLife, 1200);
        if (dt < 10) return;
        n.lastLife = gameNow;
        double hours = GameTime.hours(dt);
        c.ageDays += hours / 24;
        // the body is the truth for health (damage happens to it)
        c.needs.health = Math.min(c.needs.maxHealth, n.body.getHealth());
        boolean asleep = n.sleeping || n.resting || n.patient;
        boolean bed = n.sleeping || n.patient;
        boolean heavy = n.working && c.job.heavy || n.enemy != null;
        c.needs.decay(hours, asleep, bed, heavy, p());
        CitizenSim.Context ctx = m.context(col);
        double bonus = n.patient ? ctx.healBonus + plugin.sim().health().treatmentBonus(col) : 0;
        c.needs.heal(hours, bed, bonus, p());
        double hp = Math.max(0, Math.min(c.needs.maxHealth, c.needs.health));
        var max = n.body.getAttribute(Attribute.MAX_HEALTH);
        if (max != null && Math.abs(max.getBaseValue() - c.needs.maxHealth) > 0.01) max.setBaseValue(c.needs.maxHealth);
        if (hp <= 0) {
            n.body.setHealth(0);
            return;
        }
        n.body.setHealth(hp);
        plugin.sim().citizens().updateInjury(c);
        // company: the people actually around them, when they have time for it
        double company = 0;
        if (!n.working && !asleep && c.trait.social() > 0) {
            int near = 0;
            for (Entity e : n.body.getNearbyEntities(6, 3, 6)) {
                Npc o = m.npc(e);
                if (o != null && col.id.equals(o.c.colonyId)) near++;
                else if (e instanceof org.bukkit.entity.Player pl && col.isMember(pl.getUniqueId())) near++;
            }
            company = Math.min(1, near * 0.3) * c.trait.social();
        }
        c.needs.socialize(hours, company, p());
        if (c.trait.social() == 0) c.needs.social = Math.max(c.needs.social, 60);
        c.grief = Math.max(0, c.grief - hours / 24 * 4);
        c.needs.driftMood(plugin.sim().citizens().mood(col, c, ctx).target(), hours);
        if (n.working) {
            c.hoursToday += hours;
            Building b = col.building(c.workplace);
            if (b != null) b.staffedHours += hours;
            if (c.job.skill != null && c.job.background != Job.Background.RECIPE) c.skills.gain(c.job.skill, hours * 10 * c.trait.learning());
            if (c.job == Job.WAREHOUSE_WORKER || c.job == Job.FOOD_DISTRIBUTOR) plugin.sim().logistics().courierHours(col, hours);
        }
        if (c.job == Job.GUARD && c.status.free() && !asleep) plugin.sim().order().guardHours(col, hours);
    }

    // ───────────── meals ─────────────

    /**
     * Walks to a meal and eats: the restaurant once a day if one is serving, otherwise storage. Returns true while
     * busy with it, false if there's nothing to eat.
     */
    boolean meal(Npc n, Colony col, long now, boolean urgent) {
        Citizen c = n.c;
        CitizenSim.Context ctx = m.context(col);
        double want = c.needs.appetite(p()) * c.trait.food() * (col.policies.rationing ? 0.7 : 1);
        if (want <= 0.5) return false;
        org.bukkit.World w = n.body.getWorld();
        Building r = ctx.restaurant;
        boolean restaurant = r != null && c.restaurantDay != ctx.day && !urgent && plugin.sim().citizens().foodPoints(r.stock) > 0
                && (!ctx.wages || c.wallet > 0);
        if (restaurant) {
            org.bukkit.Location at = m.place(w, r);
            if (at != null && !n.mover.near(at, 3)) {
                n.mover.moveTo(at, plugin.settings().walkSpeed, 2.5);
                n.doing("Going to " + r.label() + " for a meal");
                return true;
            }
            n.mover.stop();
            double got = plugin.sim().citizens().eatFrom(col, c, r.stock, want, ctx, 1.5, 2);
            c.restaurantDay = ctx.day;
            if (got > 0) {
                ate(n, ctx, "Ate a meal at " + r.label());
                return true;
            }
        }
        if (plugin.sim().citizens().foodPoints(col.storage) <= 0) {
            n.doing(urgent ? "Starving: there's no food in storage" : "Hungry: no food in storage");
            return false;
        }
        if (!m.goToStorage(n, col, urgent ? "Hurrying to storage for food" : "Going to storage for a meal")) return true;
        double got = plugin.sim().citizens().eatFrom(col, c, col.storage, want, ctx, 1.0, 0);
        if (got <= 0) return false;
        ate(n, ctx, got >= want * 0.99 ? "Ate a meal from storage" : "Ate a short ration: storage is low on food");
        return true;
    }

    private void ate(Npc n, CitizenSim.Context ctx, String what) {
        n.c.lastMealDay = ctx.day;
        Fx.sound(n.loc(), "minecraft:entity.generic.eat", 0.7f, 1f);
        n.body.getWorld().spawnParticle(Particle.ITEM, n.body.getEyeLocation(), 6, 0.15, 0.1, 0.15, 0.04, new org.bukkit.inventory.ItemStack(Material.BREAD));
        n.doing(what);
    }
}
