package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Skill;
import com.colonysmp.model.Building;
import com.colonysmp.model.Colony;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Text;
import com.colonysmp.util.Tools;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Fishers stand on the shore by their fishery with a rod and land fish (and the odd bit of junk or treasure). */
public final class Fishing {

    /** Where to stand, and the water to cast into. */
    record Spot(BlockPos stand, BlockPos water) {}

    private record Shore(long at, List<Spot> spots) {}

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    private static final int REACH = 12;
    private final ColonySMP plugin;
    private final NpcManager m;
    private final Map<String, Shore> shores = new HashMap<>();

    Fishing(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    void forget(Colony col) {
        shores.keySet().removeIf(k -> k.startsWith(col.id + ":"));
    }

    void think(Npc n, Colony col, Building fishery, long now) {
        World w = n.body.getWorld();
        List<Spot> spots = shore(col, fishery, w, now);
        if (spots.isEmpty()) {
            m.brain().wanderAt(n, col, fishery, now, "No open water near " + fishery.label());
            return;
        }
        if (!m.ensureTool(n, col, Tools.Kind.FISHING_ROD, now)) return;
        if (n.c.tool == null) {
            m.brain().wanderAt(n, col, fishery, now, "Needs a fishing rod: put one in storage, hand one over, or have a workshop make one");
            return;
        }
        Spot spot = spots.get(Math.floorMod(n.c.id.hashCode(), spots.size()));
        Location stand = spot.stand.center(w).subtract(0, 0.5, 0);
        if (!n.mover.near(stand, 1.2)) {
            n.mover.moveTo(stand, plugin.settings().walkSpeed, 0.9);
            n.doing("Walking to the water");
            return;
        }
        n.mover.stop();
        n.working = true;
        Location water = spot.water.center(w).add(0, 0.4, 0);
        CitizenBrain.face(n, water);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextInt(3) == 0) w.spawnParticle(Particle.FISHING, water, 2, 0.15, 0, 0.15, 0.01);
        n.doing("Fishing at " + fishery.label());
        if (now < n.actionAt) return;
        double eff = m.efficiency(col, n.c) * (1 + 0.1 * (fishery.level - 1));
        n.actionAt = now + m.interval(plugin.settings().fishInterval * (0.7 + r.nextDouble() * 0.6), eff, n.c.tool);
        if (n.actionAt - now > 20 * 60 * 10) return;
        ItemStack catchOf = loot(r);
        w.spawnParticle(Particle.SPLASH, water, 12, 0.3, 0.1, 0.3, 0.1);
        Fx.sound(water, "minecraft:entity.fishing_bobber.splash", 0.7f, 1f);
        List<ItemStack> haul = new ArrayList<>(List.of(catchOf));
        m.toolBonus(n, haul);
        m.deposit(col, fishery, haul, stand);
        m.wearTool(n, col);
        n.c.skills.gain(Skill.FISHING, 2 * n.c.trait.learning());
        n.doing("Caught " + (catchOf.getAmount() > 1 ? catchOf.getAmount() + " " : "a ") + Text.nice(catchOf.getType().name()).toLowerCase());
    }

    /** One catch: mostly fish, sometimes junk, rarely treasure. */
    public static ItemStack loot(ThreadLocalRandom r) {
        double x = r.nextDouble();
        if (x < 0.55) return new ItemStack(Material.COD);
        if (x < 0.80) return new ItemStack(Material.SALMON);
        if (x < 0.83) return new ItemStack(Material.TROPICAL_FISH);
        if (x < 0.85) return new ItemStack(Material.PUFFERFISH);
        if (x < 0.97) {
            Material[] junk = {Material.STICK, Material.STRING, Material.BONE, Material.LEATHER, Material.BOWL, Material.LILY_PAD, Material.INK_SAC, Material.KELP};
            return new ItemStack(junk[r.nextInt(junk.length)]);
        }
        Material[] treasure = {Material.NAME_TAG, Material.SADDLE, Material.NAUTILUS_SHELL};
        return new ItemStack(treasure[r.nextInt(treasure.length)]);
    }

    /** Shore spots near the fishery (water with dry ground beside it), refreshed every 5 minutes. */
    private List<Spot> shore(Colony col, Building fishery, World w, long now) {
        String key = col.id + ":" + fishery.id;
        Shore s = shores.get(key);
        if (s != null && now - s.at < 6000 && now >= s.at) return s.spots;
        List<Spot> out = new ArrayList<>();
        BlockPos c = fishery.anchor;
        if (c != null) {
            outer:
            for (int x = c.x() - REACH; x <= c.x() + REACH; x++) {
                for (int z = c.z() - REACH; z <= c.z() + REACH; z++) {
                    if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                    Block top = w.getHighestBlockAt(x, z);
                    if (top.getType() != Material.WATER || Math.abs(top.getY() - c.y()) > 8) continue;
                    int wet = 0;
                    for (BlockFace f : SIDES) if (top.getRelative(f).getType() == Material.WATER) wet++;
                    if (wet < 2) continue;
                    for (BlockFace f : SIDES) {
                        Block land = top.getRelative(f);
                        Block feet = land.getRelative(BlockFace.UP);
                        if (land.getType().isSolid() && feet.isPassable() && !feet.isLiquid() && feet.getRelative(BlockFace.UP).isPassable()) {
                            out.add(new Spot(BlockPos.of(feet), BlockPos.of(top)));
                            if (out.size() >= 24) break outer;
                            break;
                        }
                    }
                }
            }
        }
        shores.put(key, new Shore(now, out));
        return out;
    }
}
