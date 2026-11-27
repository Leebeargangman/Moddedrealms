package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Skill;
import com.colonysmp.model.Building;
import com.colonysmp.model.Colony;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Tools;
import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Foresters fell natural trees around their forestry hut (inside the colony's land and a margin around it),
 * top down, replant a sapling, and gather the saplings, sticks and apples that fall as the leaves decay.
 * Logs go into the hut's stock for the haulers.
 */
public final class Logging {

    private static final int RADIUS = 32;

    private final ColonySMP plugin;
    private final NpcManager m;
    /** Known tree trunks per forestry hut. */
    private final Map<String, Set<BlockPos>> trees = new HashMap<>();

    Logging(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    void think(Npc n, Colony col, Building hut, long now) {
        World w = n.body.getWorld();
        if (!m.needTool(n, col, hut, Tools.Kind.AXE, now)) return;
        Set<BlockPos> claimed = m.claimed.computeIfAbsent(col.id, k -> new HashSet<>());
        if (n.work != null) {
            chop(n, col, hut, w, now, claimed);
            return;
        }
        if (n.target != null && !isLog(n.target.block(w))) {
            claimed.remove(n.target);
            n.target = null;
        }
        if (n.target == null) {
            BlockPos t = find(n, col, hut, w, claimed);
            if (t == null) {
                m.brain().wanderAt(n, col, hut, now, "Looking for trees to fell");
                return;
            }
            n.target = t;
            claimed.add(t);
        }
        Location bl = n.target.center(w);
        if (!n.mover.near(bl, 2.7)) {
            n.mover.moveTo(bl, plugin.settings().walkSpeed, 2.0);
            n.doing("Walking to a tree");
            return;
        }
        n.mover.stop();
        n.work = collect(w, n.target);
        n.workIdx = 0;
        n.jobKey = n.target.block(w).getType().name();
        n.doing("Felling a tree");
    }

    private void chop(Npc n, Colony col, Building hut, World w, long now, Set<BlockPos> claimed) {
        n.working = true;
        if (n.workIdx >= n.work.size()) {
            BlockPos base = n.target;
            if (base != null) {
                replant(w, base, n.jobKey, col, hut);
                sweep(col, hut, base.center(w));
                claimed.remove(base);
                Set<BlockPos> known = trees.get(col.id + ":" + hut.id);
                if (known != null) known.remove(base);
            }
            n.work = null;
            n.target = null;
            n.jobKey = null;
            return;
        }
        if (now < n.actionAt) {
            BlockPos next = n.work.get(n.workIdx);
            if (next.loaded(w)) m.workHit(n, next.block(w));
            return;
        }
        BlockPos lp = n.work.get(n.workIdx++);
        if (!lp.loaded(w)) return;
        Block b = lp.block(w);
        if (!isLog(b)) return;
        CitizenBrain.face(n, lp.center(w));
        List<ItemStack> drops = new ArrayList<>(b.getDrops(n.c.tool));
        w.playEffect(b.getLocation(), Effect.STEP_SOUND, b.getType());
        b.setType(Material.AIR);
        m.toolBonus(n, drops);
        m.deposit(col, hut, drops, n.loc());
        m.wearTool(n, col);
        n.c.skills.gain(Skill.FORESTRY, 0.8 * n.c.trait.learning());
        double eff = m.efficiency(col, n.c);
        n.actionAt = now + m.interval(plugin.settings().chopInterval, eff, n.c.tool);
    }

    static boolean isLog(Block b) {
        Material m = b.getType();
        return Tag.LOGS.isTagged(m) && m.name().endsWith("_LOG") && !m.name().startsWith("STRIPPED");
    }

    /** Nearest known unclaimed trunk around the hut, discovering new ones by sampling columns. */
    private BlockPos find(Npc n, Colony col, Building hut, World w, Set<BlockPos> claimed) {
        Set<BlockPos> known = trees.computeIfAbsent(col.id + ":" + hut.id, k -> new HashSet<>());
        BlockPos c = hut.anchor != null ? hut.anchor : col.core;
        if (c == null) return null;
        int margin = plugin.settings().workMargin;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 40 && known.size() < 64; i++) {
            int x = c.x() + r.nextInt(-RADIUS, RADIUS + 1), z = c.z() + r.nextInt(-RADIUS, RADIUS + 1);
            if (!w.isChunkLoaded(x >> 4, z >> 4) || !col.territory.near(x, z, margin)) continue;
            com.colonysmp.model.Colony other = plugin.colonies().at(w.getName(), x, z);
            if (other != null && other != col) continue;
            BlockPos base = sample(w, col, x, z);
            if (base != null) known.add(base);
        }
        Location here = n.loc();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        Iterator<BlockPos> it = known.iterator();
        while (it.hasNext()) {
            BlockPos p = it.next();
            if (!p.loaded(w) || !isLog(p.block(w))) {
                it.remove();
                continue;
            }
            if (claimed.contains(p)) continue;
            double d = p.distSq(here);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private BlockPos sample(World w, Colony col, int x, int z) {
        int top = w.getHighestBlockYAt(x, z);
        Block b = w.getBlockAt(x, top, z);
        if (!Tag.LEAVES.isTagged(b.getType()) && !isLog(b)) return null;
        for (int dy = 0; dy <= 10; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    Block c = w.getBlockAt(x + dx, top - dy, z + dz);
                    if (!isLog(c)) continue;
                    Block base = c;
                    int guard = 0;
                    while (isLog(base.getRelative(BlockFace.DOWN)) && guard++ < 40) base = base.getRelative(BlockFace.DOWN);
                    Material ground = base.getRelative(BlockFace.DOWN).getType();
                    if (!soil(ground)) return null;
                    if (!natural(c)) return null;
                    BlockPos bp = BlockPos.of(base);
                    for (Building bl : col.buildings.values()) if (bl.contains(bp)) return null;
                    return bp;
                }
            }
        }
        return null;
    }

    private static boolean soil(Material ground) {
        return Tag.DIRT.isTagged(ground) || ground == Material.GRASS_BLOCK || ground == Material.MUD || ground == Material.MOSS_BLOCK;
    }

    /** A real tree has leaves near the top of its trunk that weren't placed by a player. */
    private static boolean natural(Block trunk) {
        Block top = trunk;
        int guard = 0;
        while (isLog(top.getRelative(BlockFace.UP)) && guard++ < 40) top = top.getRelative(BlockFace.UP);
        int leaves = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    Block l = top.getRelative(dx, dy, dz);
                    if (Tag.LEAVES.isTagged(l.getType()) && l.getBlockData() instanceof Leaves lv && !lv.isPersistent()) leaves++;
                }
            }
        }
        return leaves >= 4;
    }

    /** Every log of the tree, top first. */
    static List<BlockPos> collect(World w, BlockPos base) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        q.add(base);
        seen.add(base);
        while (!q.isEmpty() && out.size() < 160) {
            BlockPos p = q.poll();
            if (!isLog(p.block(w))) continue;
            out.add(p);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos nb = p.add(dx, dy, dz);
                        if (seen.contains(nb) || Math.abs(nb.x() - base.x()) > 8 || Math.abs(nb.z() - base.z()) > 8 || nb.y() < base.y()) continue;
                        seen.add(nb);
                        q.add(nb);
                    }
                }
            }
        }
        out.sort(Comparator.comparingInt(BlockPos::y).reversed());
        return out;
    }

    /** A sapling of the same wood goes where the tree stood (from the hut's stock, or a fresh one from the tree). */
    private void replant(World w, BlockPos base, String logType, Colony col, Building hut) {
        if (logType == null) return;
        Block b = base.block(w);
        if (!b.getType().isAir()) return;
        if (!soil(b.getRelative(BlockFace.DOWN).getType())) return;
        Material sapling = sapling(logType);
        if (sapling == null) return;
        String key = NpcManager.good(sapling);
        // a sapling from the hut's stock (saplings fall as the leaves decay), else from storage
        if (hut.stock.take(key, 1) == 1 || col.storage.take(key, 1) == 1) b.setType(sapling);
    }

    static Material sapling(String log) {
        String wood = log.replace("_LOG", "");
        return switch (wood) {
            case "MANGROVE" -> Material.matchMaterial("MANGROVE_PROPAGULE");
            case "CRIMSON", "WARPED" -> null;
            default -> Material.matchMaterial(wood + "_SAPLING");
        };
    }

    /** Picks up saplings, sticks and apples that fall as the leaves decay. */
    private void sweep(Colony col, Building hut, Location around) {
        for (long delay : new long[]{600, 1800, 3600}) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (plugin.colonies().get(col.id) == null || around.getWorld() == null) return;
                if (!around.getWorld().isChunkLoaded(around.getBlockX() >> 4, around.getBlockZ() >> 4)) return;
                List<ItemStack> got = new ArrayList<>();
                for (Entity e : around.getWorld().getNearbyEntities(around, 7, 12, 7)) {
                    if (!(e instanceof Item item)) continue;
                    Material t = item.getItemStack().getType();
                    if (Tag.SAPLINGS.isTagged(t) || t == Material.STICK || t == Material.APPLE || t.name().equals("MANGROVE_PROPAGULE")) {
                        got.add(item.getItemStack().clone());
                        item.remove();
                    }
                }
                Building still = col.building(hut.id);
                m.deposit(col, still, got, around);
            }, delay);
        }
    }
}
