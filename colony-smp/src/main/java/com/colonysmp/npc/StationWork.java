package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.building.RoomScanner;
import com.colonysmp.core.GameTime;
import com.colonysmp.core.Recipe;
import com.colonysmp.core.Recipes;
import com.colonysmp.model.Building;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Job;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Workers at stations: millers, bakers, butchers, cooks, smiths, carpenters, masons and craftsworkers stand at
 * their workplace's furnaces, anvils and tables and work through its batches (the same recipes, inputs and
 * outputs as in the background); service workers keep their workplace open while they're there.
 */
public final class StationWork {

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    private final ColonySMP plugin;
    private final NpcManager m;

    StationWork(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    /** Works through the workplace's batches at one of its stations. */
    void craft(Npc n, Colony col, Building b, long now) {
        World w = n.body.getWorld();
        plugin.sim().production().plan(col, b);
        Recipe r = Recipes.get(b.recipe);
        if (r == null || b.reservation == null) {
            n.c.waiting = b.waiting;
            m.brain().wanderAt(n, col, b, now, b.waiting == null ? "Nothing to make at " + b.label() : b.waiting);
            return;
        }
        Location spot = spot(n, w, b);
        if (spot != null && !n.mover.near(spot, 1.3)) {
            n.mover.moveTo(spot, plugin.settings().walkSpeed, 1.0);
            n.doing("Going to work at " + b.label());
            return;
        }
        n.mover.stop();
        n.working = true;
        BlockPos st = station(n, b);
        if (st != null) CitizenBrain.face(n, st.center(w));
        n.doing(r.name + " at " + b.label() + " (" + Math.min(99, Math.round(b.progress / r.hours * 100)) + "%)");
        if (now < n.actionAt) return;
        long ticks = Math.round(plugin.settings().stationInterval * 20);
        n.actionAt = now + ticks;
        double units = GameTime.hours(ticks) * m.efficiency(col, n.c);
        plugin.sim().production().work(col, b, n.c, units);
        effects(w, st, b);
    }

    /** Keeps a service workplace open (restaurants, markets, the Town Hall's offices...). */
    void attend(Npc n, Colony col, Building b, long now, String label) {
        if (b == null) {
            m.brain().idle(n, col, now, label == null ? "No workplace" : label);
            return;
        }
        World w = n.body.getWorld();
        if (n.c.job == Job.ENGINEER && repair(n, col, now)) return;
        Location at = spot(n, w, b);
        if (at == null) at = m.place(w, b);
        if (at == null) return;
        if (!n.mover.near(at, 3)) {
            n.mover.moveTo(at, plugin.settings().walkSpeed, 2);
            n.doing("Going to work at " + b.label());
            return;
        }
        // a label means they're only waiting there (nothing to do), not working
        n.working = label == null;
        m.brain().wander(n, at, 2, now, label != null ? label : describe(n, b));
    }

    private String describe(Npc n, Building b) {
        return switch (n.c.job) {
            case RESTAURANT_WORKER -> "Serving meals at " + b.label();
            case INNKEEPER -> "Keeping " + b.label();
            case MERCHANT -> "Trading at " + b.label();
            case SHOPKEEPER -> "Minding the stalls at " + b.label();
            case ADMINISTRATOR -> "Running the colony's affairs at " + b.label();
            case TREASURER -> "Keeping the books at " + b.label();
            case ENGINEER -> "Drawing up repairs at " + b.label();
            case WARDEN -> "Keeping watch over " + b.label();
            default -> "Working at " + b.label();
        };
    }

    /** Engineers walk to buildings in disrepair and mend them. */
    private boolean repair(Npc n, Colony col, long now) {
        World w = n.body.getWorld();
        Building worst = null;
        for (Building o : col.buildings.values()) {
            if (o.condition >= 90 || o.anchor == null || !o.anchor.loaded(w)) continue;
            if (worst == null || o.condition < worst.condition) worst = o;
        }
        if (worst == null) return false;
        Location at = m.place(w, worst);
        if (at == null) return false;
        if (!n.mover.near(at, 3)) {
            n.mover.moveTo(at, plugin.settings().walkSpeed, 2.5);
            n.doing("Going to repair " + worst.label() + " (" + Math.round(worst.condition) + "%)");
            return true;
        }
        n.mover.stop();
        n.working = true;
        n.doing("Repairing " + worst.label() + " (" + Math.round(worst.condition) + "%)");
        if (now < n.actionAt) return true;
        long ticks = 40;
        n.actionAt = now + ticks;
        double units = GameTime.hours(ticks) * m.efficiency(col, n.c) * 10;
        worst.condition = Math.min(100, worst.condition + units * 2);
        Fx.sound(at, "minecraft:block.anvil.use", 0.4f, 1.2f);
        return true;
    }

    /** The station this worker uses (each worker their own, if there are enough). */
    BlockPos station(Npc n, Building b) {
        if (b.stations.isEmpty()) return null;
        int idx = Math.max(0, b.workers.indexOf(n.c.id));
        return b.stations.get(idx % b.stations.size());
    }

    /** A standing spot beside the worker's station, or somewhere inside. */
    Location spot(Npc n, World w, Building b) {
        BlockPos st = station(n, b);
        if (st != null && st.loaded(w)) {
            for (int dy = 0; dy >= -1; dy--) {
                for (BlockFace f : SIDES) {
                    Block s = st.relative(f).block(w).getRelative(0, dy, 0);
                    if (RoomScanner.standable(w, s.getX(), s.getY(), s.getZ())) return s.getLocation().add(0.5, 0, 0.5);
                }
            }
        }
        return m.place(w, b);
    }

    private static void effects(World w, BlockPos st, Building b) {
        if (st == null || !st.loaded(w)) return;
        Material t = st.block(w).getType();
        Location at = st.center(w).add(0, 0.6, 0);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (Tag.ANVIL.isTagged(t) || t == Material.SMITHING_TABLE) {
            w.spawnParticle(Particle.CRIT, at, 4, 0.2, 0.1, 0.2, 0.05);
            if (r.nextInt(3) == 0) Fx.sound(at, "minecraft:block.anvil.use", 0.3f, 1.1f);
        } else if (t == Material.FURNACE || t == Material.SMOKER || t == Material.BLAST_FURNACE || t == Material.CAMPFIRE) {
            w.spawnParticle(Particle.SMOKE, at, 3, 0.15, 0.1, 0.15, 0.01);
            if (r.nextInt(4) == 0) Fx.sound(at, "minecraft:block.furnace.fire_crackle", 0.4f, 1f);
        } else if (t == Material.GRINDSTONE || t == Material.STONECUTTER) {
            if (r.nextInt(3) == 0) Fx.sound(at, t == Material.GRINDSTONE ? "minecraft:block.grindstone.use" : "minecraft:ui.stonecutter.take_result", 0.3f, 1f);
        } else {
            w.spawnParticle(Particle.HAPPY_VILLAGER, at, 1, 0.2, 0.1, 0.2, 0);
        }
    }
}
