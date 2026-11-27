package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.building.Blueprint;
import com.colonysmp.building.MineableBlocks;
import com.colonysmp.core.Skill;
import com.colonysmp.model.Building;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Text;
import com.colonysmp.util.Tools;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Miners dig a staircase down from their mine's entrance to the configured depth, then a branch mine: a main
 * tunnel with side tunnels every four blocks, lit with torches. Ore showing in the walls is dug out as a vein.
 * What they dig goes into the mine's stock for the haulers.
 */
public final class Mining {

    /** One block to dig, where to stand to dig it, and extras. */
    record Cell(BlockPos pos, BlockPos stand, boolean torch, boolean floor) {}

    private static final BlockFace[] AROUND = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final ColonySMP plugin;
    private final NpcManager m;
    private final Map<String, List<Cell>> plans = new HashMap<>();

    Mining(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    void think(Npc n, Colony col, Building mine, long now) {
        World w = n.body.getWorld();
        if (mine.exhausted || mine.anchor == null) {
            m.brain().wanderAt(n, col, mine, now, mine.label() + " is exhausted: register a new mine entrance");
            return;
        }
        if (!m.ensureTool(n, col, Tools.Kind.PICKAXE, now)) return;
        if (n.c.tool == null) {
            m.brain().wanderAt(n, col, mine, now, "Needs a pickaxe: put one in storage, hand one over, or have a blacksmith make one");
            return;
        }
        // torches for the tunnels
        if (n.c.carried.get("torch") == 0 && mine.stock.available("torch") > 0) {
            n.c.carried.add("torch", mine.stock.take("torch", 16));
        }
        if (n.c.carried.get("torch") == 0 && m.fetch(n, col, List.of("torch"), 16, "torches") == NpcManager.Fetch.FETCHING) return;
        // ore showing in the walls first
        while (!n.veins.isEmpty()) {
            BlockPos v = n.veins.get(0);
            if (!v.loaded(w) || !MineableBlocks.isOre(v.block(w).getType()) || v.distSq(n.loc()) > 30) {
                n.veins.remove(0);
                continue;
            }
            n.working = true;
            if (now < n.actionAt) return;
            CitizenBrain.face(n, v.center(w));
            dig(n, col, mine, v.block(w), now);
            n.veins.remove(0);
            vein(w, v, n.veins);
            n.doing("Mining out an ore vein");
            return;
        }
        List<Cell> plan = plan(col, mine, w);
        int guard = 0;
        while (mine.dug < plan.size() && guard++ < 24) {
            Cell c = plan.get(mine.dug);
            if (!c.pos.loaded(w)) return;
            Block b = c.pos.block(w);
            if (b.getType().isAir() || !MineableBlocks.mineable(b.getType()) && !b.isLiquid()) {
                mine.dug++;
                continue;
            }
            break;
        }
        if (mine.dug >= plan.size()) {
            mine.exhausted = true;
            col.log(ColonyEvent.Type.PRODUCTION, plugin.sim().day(col), mine.label() + " is exhausted.");
            for (Player p : col.onlineMembers()) Text.send(p, "<yellow>" + mine.label() + " is exhausted. Register a new mine entrance with the Blueprint Book.");
            return;
        }
        Cell cell = plan.get(mine.dug);
        Location st = cell.stand.center(w);
        if (!n.mover.near(st, 1.4)) {
            n.mover.moveTo(st, plugin.settings().walkSpeed, 1.0);
            n.doing("Walking down " + mine.label());
            return;
        }
        n.mover.stop();
        n.working = true;
        if (now < n.actionAt) return;
        Block b = cell.pos.block(w);
        CitizenBrain.face(n, cell.pos.center(w));
        if (b.isLiquid()) {
            b.setType(Material.COBBLESTONE);
            n.actionAt = now + 10;
            return;
        }
        dig(n, col, mine, b, now);
        plug(w, cell.pos);
        if (cell.floor) floor(w, cell.pos.add(0, -1, 0));
        if (cell.torch) torch(n, w, cell.pos, mine.facing);
        if (b.getType().isAir()) mine.dug++;
        for (BlockFace f : AROUND) {
            BlockPos np = cell.pos.relative(f);
            if (np.loaded(w) && MineableBlocks.isOre(np.block(w).getType()) && !n.veins.contains(np)) n.veins.add(np);
        }
        n.doing(cell.pos.y() < mine.anchor.y() - 3 ? "Digging at depth " + cell.pos.y() : "Digging the mine shaft");
    }

    private void dig(Npc n, Colony col, Building mine, Block b, long now) {
        World w = b.getWorld();
        List<ItemStack> drops = new ArrayList<>(b.getDrops(n.c.tool));
        w.playEffect(b.getLocation(), Effect.STEP_SOUND, b.getType());
        b.setType(Material.AIR);
        m.toolBonus(n, drops);
        m.deposit(col, mine, drops, n.loc());
        m.wearTool(n, col);
        n.c.skills.gain(Skill.MINING, 0.7 * n.c.trait.learning());
        double eff = m.efficiency(col, n.c);
        n.actionAt = now + m.interval(plugin.settings().mineInterval, eff, n.c.tool);
    }

    private static void vein(World w, BlockPos from, List<BlockPos> veins) {
        for (BlockFace f : AROUND) {
            BlockPos np = from.relative(f);
            if (veins.size() < 16 && np.loaded(w) && MineableBlocks.isOre(np.block(w).getType()) && !veins.contains(np)) veins.add(np);
        }
    }

    /** Seals lava and water next to a freshly dug block. */
    private static void plug(World w, BlockPos p) {
        for (BlockFace f : AROUND) {
            Block nb = p.relative(f).block(w);
            if (nb.isLiquid()) nb.setType(Material.COBBLESTONE);
        }
    }

    private static void floor(World w, BlockPos p) {
        Block b = p.block(w);
        if (b.isPassable() || b.isLiquid()) b.setType(Material.COBBLESTONE);
    }

    private void torch(Npc n, World w, BlockPos head, BlockFace facing) {
        Block at = head.block(w);
        if (!at.getType().isAir()) return;
        BlockFace left = Blueprint.right(facing).getOppositeFace();
        Block wall = at.getRelative(left);
        if (!wall.getType().isOccluding()) return;
        if (n.c.carried.take("torch", 1) != 1) return;
        var data = Material.WALL_TORCH.createBlockData();
        if (data instanceof Directional d) d.setFacing(left.getOppositeFace());
        at.setBlockData(data, false);
    }

    /** The full dig plan of a mine (cached; it only depends on the entrance, its facing and the land). */
    List<Cell> plan(Colony col, Building mine, World w) {
        String key = col.id + ":" + mine.id + ":" + mine.anchor + ":" + mine.facing + ":" + col.territory.size();
        List<Cell> cached = plans.get(key);
        if (cached != null) return cached;
        List<Cell> out = new ArrayList<>();
        BlockPos o = mine.anchor;
        BlockFace f = mine.facing;
        BlockFace r = Blueprint.right(f);
        int target = Math.max(w.getMinHeight() + 6, Math.min(plugin.settings().mineTargetY, o.y() - 6));
        // staircase: each step one forward and one down, dug three high
        BlockPos prevStand = new BlockPos(o.x() - f.getModX(), o.y() + 1, o.z() - f.getModZ());
        int i = 0;
        BlockPos lastFeet = null;
        while (o.y() - i > target && i < 160) {
            int x = o.x() + f.getModX() * i, z = o.z() + f.getModZ() * i, y = o.y() - i;
            if (!col.territory.contains(x, z)) break;
            BlockPos feet = new BlockPos(x, y, z);
            out.add(new Cell(feet.add(0, 2, 0), prevStand, false, false));
            out.add(new Cell(feet.add(0, 1, 0), prevStand, i % 6 == 3, false));
            out.add(new Cell(feet, prevStand, false, true));
            prevStand = feet;
            lastFeet = feet;
            i++;
        }
        if (lastFeet == null) lastFeet = o;
        // branch mine at the bottom
        int len = plugin.settings().mineTunnelLength, branch = plugin.settings().mineBranchLength;
        BlockPos stand = lastFeet;
        for (int j = 1; j <= len; j++) {
            BlockPos feet = lastFeet.add(f.getModX() * j, 0, f.getModZ() * j);
            if (!col.territory.contains(feet)) break;
            out.add(new Cell(feet.add(0, 1, 0), stand, j % 8 == 0, false));
            out.add(new Cell(feet, stand, false, true));
            stand = feet;
            if (j % 4 == 0) {
                for (int side : new int[]{1, -1}) {
                    BlockPos bs = feet;
                    for (int k = 1; k <= branch; k++) {
                        BlockPos bf = feet.add(r.getModX() * k * side, 0, r.getModZ() * k * side);
                        if (!col.territory.contains(bf)) break;
                        out.add(new Cell(bf.add(0, 1, 0), bs, false, false));
                        out.add(new Cell(bf, bs, false, true));
                        bs = bf;
                    }
                }
            }
        }
        plans.put(key, out);
        return out;
    }
}
