package net.moddedrealms.testplayers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Breaking blocks the way a player does: walk within reach, swing, the block cracks (players nearby see the
 * cracks and hear it) for as long as that block takes with the tool in hand, then it drops what the game says
 * it drops for that tool, straight into the test player's inventory. Trees, the town mine, grass for seeds and
 * nether ores are all done this way.
 */
final class Gather {

    /** A list of blocks to break, in order. "after" runs for each broken block (to follow ore veins...). */
    static final class Work {
        final String label;
        final Deque<Block> todo = new ArrayDeque<>();
        Block current;
        int progress;
        int need;
        Consumer<Block> after;
        Location via;           // go in this way (the mine entrance)
        Location go;            // walk here first...
        Runnable arrive;        // ...and do this there (open the storage chest, place something)
        long giveUpAt;
        int broken;
        int tooHigh;
        int unreachable;
        Material curType;       // what the current block was when it started on it
        int pillar;             // ticks into a jump-and-place (0 = not pillaring)
        Block pillarAt;
        final List<Block> pillars = new ArrayList<>(); // blocks it put under itself, to take back after
        String done;            // lastResult when finished

        Work(String label) {
            this.label = label;
            this.giveUpAt = System.currentTimeMillis() + 4 * 60_000;
        }
    }

    private static final Set<Material> ORES = new HashSet<>();

    static {
        for (Material m : Material.values()) if (m.name().endsWith("_ORE")) ORES.add(m);
        ORES.add(Material.ANCIENT_DEBRIS);
    }

    private final TestPlayersPlugin plugin;
    private final Map<String, Work> works = new HashMap<>();
    /** Blocks someone couldn't reach (too high, no way there): left alone for 10 minutes. */
    private final Map<Block, Long> cantReach = new HashMap<>();

    Gather(TestPlayersPlugin plugin) {
        this.plugin = plugin;
    }

    boolean busy(TestPlayer tp) {
        return works.containsKey(tp.id);
    }

    void start(TestPlayer tp, Work w) {
        if (w.todo.isEmpty() && w.go == null) return;
        if (cantReach.size() > 500) cantReach.values().removeIf(t -> t < System.currentTimeMillis());
        works.put(tp.id, w);
        tp.mode = TestPlayer.Mode.WORK;
        tp.buildStatus = w.label;
        tp.stuckTicks = 0;
        tp.bestDist = Double.MAX_VALUE;
        tp.ticksSinceBetter = 0;
    }

    void stop(TestPlayer tp) {
        Work w = works.remove(tp.id);
        if (w != null && w.current != null) crack(w.current, -1, tp.entity());
    }

    /** Every tick while the test player is working: walk to the next block, then break it. */
    void step(TestPlayer tp, Mannequin m, Location loc, double speed) {
        Work w = works.get(tp.id);
        if (w == null) {
            tp.mode = TestPlayer.Mode.IDLE;
            return;
        }
        long now = System.currentTimeMillis();
        if (w.current != null && w.current.getWorld() != loc.getWorld() || !w.todo.isEmpty() && w.todo.peek().getWorld() != loc.getWorld()) {
            finish(tp, w); // it went through a portal: this work is in the other world
            return;
        }
        if (w.go != null) {
            if (w.go.getWorld() != loc.getWorld() || now > w.giveUpAt) {
                finish(tp, w);
                return;
            }
            if (TestPlayers.hdist(w.go, loc) > 2.2) {
                plugin.players().walkToward(tp, m, loc, w.go.getX(), w.go.getZ(), speed);
                return;
            }
            TestPlayers.brakeNow(m);
            m.lookAt(w.go.getX(), w.go.getY(), w.go.getZ(), io.papermc.paper.entity.LookAnchor.EYES);
            w.go = null;
            if (w.arrive != null) w.arrive.run();
            return;
        }
        if (w.current == null) {
            while (!w.todo.isEmpty() && w.current == null) {
                Block b = w.todo.poll();
                if (!b.getType().isAir() && !b.isLiquid()) ensureTool(tp, b.getType());
                if (!b.getType().isAir() && !b.isLiquid() && Items.canHarvest(tp, b.getType()) && b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)
                        && !skipped(b)) w.current = b;
            }
            if (w.current == null && w.via != null && loc.getY() < w.via.getY() - 2.5 && now < w.giveUpAt + 60_000) {
                // Done down there: back up the shaft the way it came.
                plugin.players().walkToward(tp, m, loc, w.via.getX(), w.via.getZ(), speed);
                if (++tp.ticksSinceBetter > 900) {
                    m.teleport(w.via); // wedged somewhere off the shaft for 45 s: out of sight underground anyway
                    tp.ticksSinceBetter = 0;
                }
                return;
            }
            if (w.current == null && !w.pillars.isEmpty()) {
                // Down off its pillar, taking the blocks back.
                Block top = w.pillars.removeLast();
                if (top.getType() == Material.DIRT || top.getType() == Material.COBBLESTONE) {
                    w.current = top;
                    w.progress = 0;
                    w.need = Items.breakTicks(tp, top.getType());
                    w.curType = top.getType();
                    w.broken--; // not part of what it came for
                    return;
                }
            }
            if (w.current == null || now > w.giveUpAt) {
                finish(tp, w);
                return;
            }
            w.progress = 0;
            w.need = Items.breakTicks(tp, w.current.getType());
            w.curType = w.current.getType();
            hold(tp, m, w.current.getType());
            tp.bestDist = Double.MAX_VALUE;
            tp.ticksSinceBetter = 0;
        }
        Block b = w.current;
        if (b.getType() != w.curType) {
            w.current = null; // someone else broke it (or changed it)
            return;
        }
        Location spot = b.getLocation().add(0.5, 0.5, 0.5);
        double h = TestPlayers.hdist(spot, loc);
        if (w.pillar > 0) {
            // Jump and put a block under its feet, like a player reaching the top of a tree.
            if (++w.pillar >= 6) {
                Block at = w.pillarAt;
                Material use = Items.count(tp, Material.DIRT) > 0 ? Material.DIRT : Material.COBBLESTONE;
                if (at.isReplaceable() && !at.isLiquid() && m.getLocation().getY() >= at.getY() + 0.9 && Items.take(tp, use, 1)) {
                    at.setType(use);
                    w.pillars.add(at);
                    m.swingMainHand();
                    at.getWorld().playSound(at.getLocation(), at.getBlockData().getSoundGroup().getPlaceSound(), 1f, 0.9f);
                }
                w.pillar = 0;
            }
            return;
        }
        if (plugin.getConfig().getBoolean("survival.debug", false)) {
            tp.thought = "work: " + b.getType() + " at " + b.getX() + " " + b.getY() + " " + b.getZ() + ", h=" + Math.round(h * 10) / 10.0
                    + " up=" + Math.round((spot.getY() - loc.getY()) * 10) / 10.0 + " progress " + w.progress + "/" + w.need + " stuck " + tp.ticksSinceBetter;
        }
        double up = spot.getY() - loc.getY();
        if (h <= 3.2 && up > 6) {
            // Too high to reach (the top of a tall tree): pillar up on dirt or cobblestone if it has some
            // (and isn't already 6 up), else leave it, like players do.
            boolean canPillar = Items.count(tp, Material.DIRT) + Items.count(tp, Material.COBBLESTONE) > 0 && w.pillars.size() < 9
                    && m.isOnGround() && loc.getBlock().isReplaceable() && !loc.getBlock().isLiquid() && loc.getBlock().getRelative(0, 2, 0).isPassable() && up < 14;
            if (canPillar) {
                TestPlayers.brakeNow(m);
                m.setVelocity(new org.bukkit.util.Vector(0, 0.42, 0));
                w.pillar = 1;
                w.pillarAt = loc.getBlock();
                return;
            }
            cantReach.put(w.current, System.currentTimeMillis() + 600_000);
            w.current = null;
            w.tooHigh++;
            return;
        }
        if (w.via != null && loc.getY() - spot.getY() > 3.5 && TestPlayers.hdist(loc, w.via) > 1.2 && h > 1.5) {
            // Underground (the mine): in by the entrance, not by standing on the grass above it.
            plugin.players().walkToward(tp, m, loc, w.via.getX(), w.via.getZ(), speed);
            if (++tp.ticksSinceBetter > 600) finish(tp, w);
            return;
        }
        if (h > 3.2 || up > 6 || -up > 3.5) {
            // Walk over (it can't break what it can't reach). Leaves or dirt in the way get broken, like a
            // player would; it gives up on a block it can't get to.
            if (h < tp.bestDist - 0.3) {
                tp.bestDist = h;
                tp.ticksSinceBetter = 0;
            } else if (tp.ticksSinceBetter > 30 && tp.ticksSinceBetter % 20 == 0) {
                Block in = inTheWay(tp, loc, spot);
                if (in != null) {
                    w.todo.addFirst(b);
                    w.current = in;
                    w.progress = 0;
                    w.need = Items.breakTicks(tp, in.getType());
                    hold(tp, m, in.getType());
                    return;
                }
            }
            plugin.players().walkToward(tp, m, loc, spot.getX(), spot.getZ(), speed);
            if (++tp.ticksSinceBetter > 400) {
                cantReach.put(w.current, System.currentTimeMillis() + 600_000);
                w.current = null;
                w.unreachable++;
                tp.ticksSinceBetter = 0;
            }
            return;
        }
        tp.ticksSinceBetter = 0;
        TestPlayers.brakeNow(m);
        m.lookAt(spot.getX(), spot.getY(), spot.getZ(), io.papermc.paper.entity.LookAnchor.EYES);
        if (w.progress % 5 == 0) m.swingMainHand();
        w.progress++;
        if (w.progress % 4 == 0) crack(b, (float) w.progress / w.need, m);
        if (w.progress < w.need) return;
        // Broken: what the game drops for this block and this tool.
        Material tool = toolFor(tp, b.getType());
        Collection<ItemStack> drops = b.getDrops(tool == null ? new ItemStack(Material.AIR) : new ItemStack(tool));
        for (ItemStack d : drops) Items.add(tp, d.getType(), d.getAmount());
        b.getWorld().playSound(spot, b.getBlockData().getSoundGroup().getBreakSound(), 1f, 0.9f);
        b.getWorld().spawnParticle(org.bukkit.Particle.BLOCK, spot, 20, 0.3, 0.3, 0.3, b.getBlockData());
        crack(b, -1, m);
        b.setType(Material.AIR, true);
        w.broken++;
        w.current = null;
        if (w.after != null) w.after.accept(b);
    }

    private void finish(TestPlayer tp, Work w) {
        works.remove(tp.id);
        tp.mode = TestPlayer.Mode.IDLE;
        if (w.current != null) crack(w.current, -1, tp.entity());
        if (w.arrive != null && w.done == null && w.broken == 0) {
            // a visit (the storage chest...): what it did there is the result
        } else tp.lastResult = (w.done != null ? w.done.replace("{n}", String.valueOf(w.broken)) : w.label + ": " + w.broken + " blocks")
                + (w.tooHigh + w.unreachable > 0 ? " (left " + (w.tooHigh > 0 ? w.tooHigh + " too high" : "") + (w.tooHigh > 0 && w.unreachable > 0 ? ", " : "")
                + (w.unreachable > 0 ? w.unreachable + " it couldn't get to" : "") + ")" : "")
                + (now() > w.giveUpAt ? " (ran out of time)" : "");
        Mannequin m = tp.entity();
        if (m != null) m.getEquipment().setItemInMainHand(null);
        // It went for materials in the middle of a build: back to it.
        if (plugin.builder().building(tp)) plugin.builder().resume(tp);
    }

    /** A natural block (leaves, a log, dirt, stone, a plant) right in its way toward a spot, at feet or head height. */
    private static Block inTheWay(TestPlayer tp, Location loc, Location spot) {
        double dx = spot.getX() - loc.getX();
        double dz = spot.getZ() - loc.getZ();
        double d = Math.hypot(dx, dz);
        if (spot.getY() < loc.getY() - 3 && d < 3) {
            // Up on top of something (a tree's leaves): dig down.
            Block under = loc.getBlock().getRelative(BlockFace.DOWN);
            if (!under.isPassable() && natural(under)) return under;
        }
        if (d < 1e-3) return null;
        for (double step : new double[] {0.8, 1.5}) {
            int x = (int) Math.floor(loc.getX() + dx / d * step);
            int z = (int) Math.floor(loc.getZ() + dz / d * step);
            for (int dy = 1; dy >= 0; dy--) {
                Block b = loc.getWorld().getBlockAt(x, loc.getBlockY() + dy, z);
                if (!b.isPassable() && natural(b) && Items.canHarvest(tp, b.getType())) return b;
            }
        }
        return null;
    }

    /** Blocks the world made (not something a player built): fine to break on the way. */
    static boolean natural(Block b) {
        if (b.getBlockData() instanceof Leaves l) return !l.isPersistent();
        String n = b.getType().name();
        return Items.isLog(b.getType()) || n.equals("DIRT") || n.equals("GRASS_BLOCK") || n.equals("COARSE_DIRT") || n.equals("PODZOL")
                || n.equals("STONE") || n.equals("DEEPSLATE") || n.equals("GRAVEL") || n.equals("SAND") || n.equals("ANDESITE")
                || n.equals("DIORITE") || n.equals("GRANITE") || n.equals("TUFF") || n.equals("NETHERRACK") || n.endsWith("_ORE")
                || n.equals("SNOW_BLOCK") || n.equals("CLAY") || n.equals("MOSS_BLOCK") || n.equals("MUD");
    }

    /** Hold the right tool (crafting one if it can) for this block. */
    private void hold(TestPlayer tp, Mannequin m, Material block) {
        String kind = kindFor(block);
        if (kind != null && Items.tool(tp, kind) < 1) Items.craftTool(tp, kind); // upgrade when it can
        Material tool = toolFor(tp, block);
        m.getEquipment().setItemInMainHand(tool == null ? null : new ItemStack(tool));
    }

    /** Make the tool this block needs if it has none (a wooden pickaxe from its logs...). */
    private static void ensureTool(TestPlayer tp, Material block) {
        String kind = kindFor(block);
        if (kind != null && Items.tool(tp, kind) < 0) Items.craftTool(tp, kind);
    }

    private static String kindFor(Material block) {
        String n = block.name();
        if (Items.isLog(block) || n.endsWith("_PLANKS")) return "AXE";
        if (n.contains("DIRT") || n.equals("GRASS_BLOCK") || n.contains("SAND") || n.equals("GRAVEL") || n.equals("CLAY")) return "SHOVEL";
        if (n.contains("LEAVES") || n.contains("GRASS") || n.contains("FERN") || n.contains("FLOWER")) return null;
        return "PICKAXE";
    }

    private static Material toolFor(TestPlayer tp, Material block) {
        String kind = kindFor(block);
        return kind == null ? null : Items.toolItem(tp, kind);
    }

    /** Show the cracks to players near the block (progress -1 clears them). */
    private static void crack(Block b, float progress, Mannequin m) {
        if (m == null) return;
        for (Player p : b.getLocation().getNearbyPlayers(32)) {
            if (progress < 0) p.sendBlockDamage(b.getLocation(), 0f, m.getEntityId());
            else p.sendBlockDamage(b.getLocation(), Math.min(0.99f, progress), m.getEntityId());
        }
    }

    // ------------------------------------------------------------ trees

    /**
     * The nearest natural tree within radius of a spot: a log with natural leaves on it (leaves a player
     * placed are "persistent", so a log cabin is never mistaken for a tree). Null if none.
     */
    Work tree(Location around, int radius, Location near, boolean replant) {
        World w = around.getWorld();
        Block best = null;
        double bestD = Double.MAX_VALUE;
        int cx = around.getBlockX();
        int cz = around.getBlockZ();
        for (int x = cx - radius; x <= cx + radius; x += 2) {
            for (int z = cz - radius; z <= cz + radius; z += 2) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                Block top = w.getHighestBlockAt(x, z);
                // Highest block is usually leaves: look down the column for a trunk.
                for (int y = top.getY(); y > top.getY() - 14; y--) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!Items.isLog(b.getType())) continue;
                    if (skipped(b) || claimed(b)) break;
                    double d = near.distanceSquared(b.getLocation());
                    if (d < bestD && naturalTree(b)) {
                        bestD = d;
                        best = b;
                    }
                    break;
                }
            }
        }
        if (best == null) return null;
        List<Block> logs = trunk(best);
        logs.removeIf(this::skipped);
        if (logs.isEmpty()) return null;
        Work work = new Work("chopping a " + logs.getFirst().getType().name().toLowerCase().replace("_log", "").replace('_', ' ') + " tree");
        work.todo.addAll(logs);
        work.done = "chopped down a tree ({n} logs)";
        Block stump = logs.getFirst();
        Material sapling = Material.matchMaterial(stump.getType().name().replace("_LOG", "_SAPLING"));
        work.after = b -> {
            if (replant && b.equals(stump) && sapling != null && b.getRelative(BlockFace.DOWN).getType().name().matches("GRASS_BLOCK|DIRT|PODZOL|COARSE_DIRT")) {
                // Leaves drop saplings; it plants one where the tree stood, like a tidy lumberjack.
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (b.getType().isAir()) b.setType(sapling);
                }, 40);
            }
        };
        return work;
    }

    /** Logs with natural (non-persistent) leaves within two blocks: a real tree. */
    private static boolean naturalTree(Block log) {
        Block up = log;
        for (int i = 0; i < 12 && Items.isLog(up.getType()); i++) up = up.getRelative(BlockFace.UP);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 1; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    Block b = up.getRelative(dx, dy, dz);
                    if (b.getBlockData() instanceof Leaves leaves && !leaves.isPersistent()) return true;
                }
            }
        }
        return false;
    }

    /** The tree's logs, bottom up (up to 40, branches included). */
    private static List<Block> trunk(Block start) {
        Block base = start;
        while (Items.isLog(base.getRelative(BlockFace.DOWN).getType())) base = base.getRelative(BlockFace.DOWN);
        List<Block> out = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        Deque<Block> q = new ArrayDeque<>();
        q.add(base);
        seen.add(base);
        while (!q.isEmpty() && out.size() < 40) {
            Block b = q.poll();
            out.add(b);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Block n = b.getRelative(dx, dy, dz);
                        if (Items.isLog(n.getType()) && seen.add(n)) q.add(n);
                    }
                }
            }
        }
        out.sort((a, b) -> a.getY() - b.getY());
        return out;
    }

    /** Clear a building plot: the trees on it (logs and their leaves), bottom up. Null if there are none. */
    Work clearArea(World w, int[] box) {
        List<Block> logs = new ArrayList<>();
        List<Block> leaves = new ArrayList<>();
        for (int x = box[0]; x <= box[2]; x++) {
            for (int z = box[1]; z <= box[3]; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
                int ground = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.OCEAN_FLOOR);
                for (int y = ground - 14; y <= ground + 1; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (claimed(b)) continue;
                    if (Items.isLog(b.getType()) && naturalTree(b)) logs.add(b);
                    else if (b.getBlockData() instanceof Leaves l && !l.isPersistent() && y < ground + 2) leaves.add(b);
                }
            }
        }
        // (The "ground" there is the top of the tree: the trunk and the leaves are all below it.)
        if (logs.isEmpty()) return null;
        Work work = new Work("clearing trees off a building plot");
        work.done = "cleared a plot ({n} blocks)";
        logs.sort((a, b) -> a.getY() - b.getY());
        leaves.sort((a, b) -> a.getY() - b.getY());
        work.todo.addAll(logs);
        leaves.stream().limit(Math.max(0, 140 - logs.size())).forEach(work.todo::add);
        work.giveUpAt = System.currentTimeMillis() + 8 * 60_000;
        return work;
    }

    // ------------------------------------------------------------ the town mine

    /** Floor of the town mine: iron is commonest around here. */
    static final int MINE_FLOOR = 16;

    /**
     * The next stretch of the town's mine: straight shafts from the mine entrance, a staircase down to
     * MINE_FLOOR and then a tunnel. A shaft that reaches the edge of the town (or 28 blocks of tunnel) is done,
     * and the next one heads off the other way. Straight shafts mean the way out is always straight back.
     * Ores it uncovers are mined too, lava and water get plugged with cobblestone, and it puts up torches.
     */
    Work mine(TestPlayer tp, Town t) {
        World w = plugin.getServer().getWorld(t.world);
        if (w == null || t.mineDx == 0 && t.mineDz == 0) return null;
        Work work = new Work("mining in the " + t.name + " mine");
        work.done = "mined {n} blocks";
        int stairs = Math.max(0, t.mineY - MINE_FLOOR);
        for (int i = 0; i < 4 && work.todo.size() < 12; i++) {
            int x = t.mineX + t.mineDx * t.mineStep;
            int z = t.mineZ + t.mineDz * t.mineStep;
            boolean down = t.mineStep < stairs;
            int y = down ? t.mineY - t.mineStep : MINE_FLOOR;
            if (!plugin.builder().inZone(w, x, z) || t.mineStep > stairs + 28) {
                // This shaft is done: the next one heads off at right angles (and every fourth from a new entrance).
                int ndx = -t.mineDz;
                t.mineDz = t.mineDx;
                t.mineDx = ndx;
                t.mineShafts++;
                if (t.mineShafts % 4 == 0) {
                    t.mineX += 5;
                    t.mineY = w.getHighestBlockYAt(t.mineX, t.mineZ, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
                }
                t.mineStep = 0;
                if (i > 0) break;
                continue;
            }
            for (int h = 0; h < (down ? 3 : 2); h++) work.todo.add(w.getBlockAt(x, y + h, z));
            t.mineStep++;
        }
        work.via = new Location(w, t.mineX - t.mineDx + 0.5, t.mineY + 1, t.mineZ - t.mineDz + 0.5);
        work.after = b -> {
            for (BlockFace f : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
                Block n = b.getRelative(f);
                if (ORES.contains(n.getType())) work.todo.addFirst(n);                  // follow the vein
                else if (n.isLiquid() && Items.take(tp, Material.COBBLESTONE, 1)) n.setType(Material.COBBLESTONE); // plug it
            }
            Block floor = b.getRelative(BlockFace.DOWN);
            if (work.broken % 12 == 6 && floor.getType().isSolid() && b.getType().isAir()) {
                if (Items.count(tp, Material.TORCH) > 0 || Items.makeTorches(tp)) {
                    Items.take(tp, Material.TORCH, 1);
                    b.setType(Material.TORCH);
                }
            }
        };
        return work;
    }

    // ------------------------------------------------------------ seeds and the nether

    /** Break grass around a spot for wheat seeds (grass sometimes drops them). */
    Work seeds(Location around) {
        Work work = new Work("collecting seeds from the grass");
        work.done = "pulled up {n} tufts of grass";
        World w = around.getWorld();
        for (int dx = -10; dx <= 10 && work.todo.size() < 24; dx++) {
            for (int dz = -10; dz <= 10 && work.todo.size() < 24; dz++) {
                Block b = w.getHighestBlockAt(around.getBlockX() + dx, around.getBlockZ() + dz).getRelative(BlockFace.UP);
                if (b.getType() == Material.SHORT_GRASS || b.getType() == Material.TALL_GRASS) work.todo.add(b);
            }
        }
        return work.todo.isEmpty() ? null : work;
    }

    /** Nether ores and glowstone near a spot, nearest first. */
    Work netherLoot(Location around) {
        Work work = new Work("mining in the nether");
        work.done = "mined {n} blocks in the nether";
        World w = around.getWorld();
        List<Block> found = new ArrayList<>();
        for (int dx = -14; dx <= 14; dx++) {
            for (int dz = -14; dz <= 14; dz++) {
                for (int dy = -6; dy <= 8; dy++) {
                    Block b = w.getBlockAt(around.getBlockX() + dx, around.getBlockY() + dy, around.getBlockZ() + dz);
                    Material t = b.getType();
                    if (t == Material.NETHER_QUARTZ_ORE || t == Material.NETHER_GOLD_ORE || t == Material.GLOWSTONE) found.add(b);
                }
            }
        }
        found.sort((a, b) -> Double.compare(a.getLocation().distanceSquared(around), b.getLocation().distanceSquared(around)));
        found.stream().limit(16).forEach(work.todo::add);
        return work.todo.isEmpty() ? null : work;
    }

    /** Nether portal blocks near a spot (for explorers to remember), at most one per portal. */
    static Location portalNear(Location around, int radius) {
        World w = around.getWorld();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = around.getBlockX() + dx;
                int z = around.getBlockZ() + dz;
                // A checkerboard of columns is enough: a portal is at least 2 wide, whichever way it faces.
                if (radius > 4 && ((x + z) & 1) != 0) continue;
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = around.getBlockY() - 10; y <= around.getBlockY() + 10; y++) {
                    if (w.getBlockAt(x, y, z).getType() == Material.NETHER_PORTAL) return new Location(w, x + 0.5, y, z + 0.5);
                }
            }
        }
        return null;
    }

    /** Someone else is already on it (chopping that tree). */
    private boolean claimed(Block b) {
        for (Work w : works.values()) if (b.equals(w.current) || w.todo.contains(b)) return true;
        return false;
    }

    private boolean skipped(Block b) {
        Long until = cantReach.get(b);
        if (until == null) return false;
        if (until > System.currentTimeMillis()) return true;
        cantReach.remove(b);
        return false;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    static boolean chance(double p) {
        return ThreadLocalRandom.current().nextDouble() < p;
    }
}
