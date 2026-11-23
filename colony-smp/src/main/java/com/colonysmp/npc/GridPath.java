package com.colonysmp.npc;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * A small block-grid A* for the places Paper's villager pathfinder won't go: ladders and vines (tower tops),
 * iron doors for key holders, fence gates, water and fresh tunnels. Returns the walkable standing points, or
 * the way to the closest reachable point when the destination can't be reached.
 */
final class GridPath {

    private static final int[][] FLAT = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAG = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private record Node(int x, int y, int z) {
        long key() {
            return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
        }
    }

    private static final class Open implements Comparable<Open> {
        final Node n;
        final double g, f;

        Open(Node n, double g, double f) {
            this.n = n;
            this.g = g;
            this.f = f;
        }

        @Override
        public int compareTo(Open o) {
            return Double.compare(f, o.f);
        }
    }

    private final World w;
    private final boolean keys;
    private final Predicate<Block> pass;
    private final Map<Long, Boolean> standCache = new HashMap<>();

    private GridPath(World w, boolean keys, Predicate<Block> pass) {
        this.w = w;
        this.keys = keys;
        this.pass = pass;
    }

    /**
     * @param arrive how close (horizontally) counts as arrived
     * @return standing points from just after {@code from} to the goal (or the closest point reached), or
     * null when no step gets any closer
     */
    static List<Location> find(Location from, Location to, double arrive, boolean ironKeys, Predicate<Block> pass, int maxNodes) {
        World w = from.getWorld();
        if (w == null || w != to.getWorld()) return null;
        GridPath g = new GridPath(w, ironKeys, pass);
        Node start = g.settle(from.getBlockX(), from.getBlockY(), from.getBlockZ());
        if (start == null) return null;
        double tx = to.getX(), ty = to.getY(), tz = to.getZ();
        double reach = Math.max(0.5, arrive);
        PriorityQueue<Open> open = new PriorityQueue<>();
        Map<Long, Double> best = new HashMap<>();
        Map<Long, Node> parent = new HashMap<>();
        open.add(new Open(start, 0, h(start, tx, ty, tz)));
        best.put(start.key(), 0.0);
        Node closest = start;
        double closestH = h(start, tx, ty, tz);
        int visited = 0;
        Node goal = null;
        while (!open.isEmpty() && visited < maxNodes) {
            Open o = open.poll();
            Node n = o.n;
            Double bg = best.get(n.key());
            if (bg != null && o.g > bg + 1e-9) continue;
            visited++;
            double hx = n.x + 0.5 - tx, hz = n.z + 0.5 - tz;
            if (hx * hx + hz * hz <= reach * reach && Math.abs(n.y - ty) < 2.5) {
                goal = n;
                break;
            }
            double hn = h(n, tx, ty, tz);
            if (hn < closestH) {
                closestH = hn;
                closest = n;
            }
            for (Step s : g.steps(n)) {
                double ng = o.g + s.cost;
                long k = s.to.key();
                Double prev = best.get(k);
                if (prev != null && prev <= ng) continue;
                best.put(k, ng);
                parent.put(k, n);
                open.add(new Open(s.to, ng, ng + h(s.to, tx, ty, tz)));
            }
        }
        Node end = goal != null ? goal : closest;
        if (end == start) return null;
        if (goal == null && h(start, tx, ty, tz) - closestH < 2) return null;
        List<Node> nodes = new ArrayList<>();
        for (Node c = end; c != null && c != start && !c.equals(start); c = parent.get(c.key())) nodes.add(c);
        Collections.reverse(nodes);
        List<Location> out = new ArrayList<>();
        Node prev = start;
        for (Node c : nodes) {
            // drops: walk off the edge first, then fall straight down
            if (c.y < prev.y && (c.x != prev.x || c.z != prev.z) && !g.climbable(prev.x, prev.y, prev.z)) {
                out.add(Mover.stand(new Location(w, c.x, prev.y, c.z)));
            }
            out.add(Mover.stand(new Location(w, c.x, c.y, c.z)));
            prev = c;
        }
        return out;
    }

    private static double h(Node n, double tx, double ty, double tz) {
        double dx = n.x + 0.5 - tx, dy = n.y - ty, dz = n.z + 0.5 - tz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private record Step(Node to, double cost) {}

    private List<Step> steps(Node n) {
        List<Step> out = new ArrayList<>(12);
        boolean climb = climbable(n.x, n.y, n.z);
        boolean water = water(n.x, n.y, n.z);
        for (int[] d : FLAT) {
            int x = n.x + d[0], z = n.z + d[1];
            if (!loaded(x, z)) continue;
            if (stand(x, n.y, z)) {
                out.add(new Step(new Node(x, n.y, z), cost(x, n.y, z, 1)));
                continue;
            }
            // step up one block (needs head room above where we stand now)
            if (stand(x, n.y + 1, z) && clear(n.x, n.y + 2, n.z)) {
                out.add(new Step(new Node(x, n.y + 1, z), cost(x, n.y + 1, z, 1.6)));
                continue;
            }
            // walk off an edge and drop up to three blocks
            if (!clear(x, n.y, z) || !clear(x, n.y + 1, z)) continue;
            for (int dy = 1; dy <= 3; dy++) {
                int y = n.y - dy;
                if (stand(x, y, z)) {
                    out.add(new Step(new Node(x, y, z), cost(x, y, z, 1 + 0.3 * dy)));
                    break;
                }
                if (!clear(x, y, z)) break;
            }
        }
        // diagonals on the flat, without cutting corners
        for (int[] d : DIAG) {
            int x = n.x + d[0], z = n.z + d[1];
            if (!loaded(x, z)) continue;
            if (stand(x, n.y, z) && clear(n.x + d[0], n.y, n.z) && clear(n.x + d[0], n.y + 1, n.z)
                    && clear(n.x, n.y, n.z + d[1]) && clear(n.x, n.y + 1, n.z + d[1])) {
                out.add(new Step(new Node(x, n.y, z), cost(x, n.y, z, 1.42)));
            }
        }
        // up and down ladders, vines and water
        if ((climb || water) && stand(n.x, n.y + 1, n.z) && (climbable(n.x, n.y + 1, n.z) || water(n.x, n.y + 1, n.z) || solidBelow(n.x, n.y + 1, n.z))) {
            out.add(new Step(new Node(n.x, n.y + 1, n.z), water ? 2.0 : 1.5));
        }
        if (stand(n.x, n.y - 1, n.z) && (climbable(n.x, n.y - 1, n.z) || water(n.x, n.y - 1, n.z))) {
            out.add(new Step(new Node(n.x, n.y - 1, n.z), 1.3));
        }
        return out;
    }

    private double cost(int x, int y, int z, double base) {
        double c = base;
        if (water(x, y, z)) c += 1.5;
        Material feet = w.getBlockAt(x, y, z).getType();
        if (Tag.DOORS.isTagged(feet) || Tag.FENCE_GATES.isTagged(feet)) c += 0.5;
        return c;
    }

    /** The nearest standing spot at or just below a position (the body may be mid-air or in a doorway). */
    private Node settle(int x, int y, int z) {
        for (int dy = 0; dy >= -3; dy--) if (stand(x, y + dy, z)) return new Node(x, y + dy, z);
        if (stand(x, y + 1, z)) return new Node(x, y + 1, z);
        return null;
    }

    /** Can a body stand with its feet in this block? */
    private boolean stand(int x, int y, int z) {
        long k = new Node(x, y, z).key();
        Boolean c = standCache.get(k);
        if (c != null) return c;
        boolean r = computeStand(x, y, z);
        standCache.put(k, r);
        return r;
    }

    private boolean computeStand(int x, int y, int z) {
        if (y <= w.getMinHeight() || y + 2 >= w.getMaxHeight()) return false;
        Block feet = w.getBlockAt(x, y, z);
        if (!through(feet)) {
            // low blocks (slabs, carpets, snow) are floors: stand on top if there's head room
            if (!low(feet) || !clear(x, y + 1, z) || !clear(x, y + 2, z)) return false;
            return true;
        }
        if (!clear(x, y + 1, z)) return false;
        if (climbable(x, y, z) || water(x, y, z)) return true;
        return solidBelow(x, y, z);
    }

    private boolean solidBelow(int x, int y, int z) {
        Block below = w.getBlockAt(x, y - 1, z);
        Material m = below.getType();
        if (m == Material.LAVA || m == Material.MAGMA_BLOCK || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE || m == Material.CACTUS) return false;
        // fences and walls are taller than a block: nobody stands on top of them
        if (Tag.FENCES.isTagged(m) || Tag.WALLS.isTagged(m) || Tag.FENCE_GATES.isTagged(m)) return false;
        if (m == Material.SCAFFOLDING || Tag.BEDS.isTagged(m)) return true;
        if (water(x, y - 1, z)) return true;
        // on a slab or carpet the feet are in that block, not above it
        if (low(below)) return false;
        return m.isSolid() && !below.isPassable();
    }

    /** Passable for walking (doors and gates count: they get opened). */
    private boolean through(Block b) {
        Material m = b.getType();
        if (m == Material.LAVA || m == Material.FIRE || m == Material.SOUL_FIRE || m == Material.SWEET_BERRY_BUSH
                || m == Material.COBWEB || m == Material.POWDER_SNOW || m == Material.WITHER_ROSE || m == Material.POINTED_DRIPSTONE) {
            return false;
        }
        if (Tag.WOODEN_DOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m)) return true;
        if (m == Material.IRON_DOOR) return keys || pass.test(b) || b.getBlockData() instanceof org.bukkit.block.data.Openable o && o.isOpen();
        if (Tag.TRAPDOORS.isTagged(m)) return b.isPassable();
        return b.isPassable() || m == Material.SCAFFOLDING;
    }

    private boolean clear(int x, int y, int z) {
        return through(w.getBlockAt(x, y, z));
    }

    private static boolean low(Block b) {
        Material m = b.getType();
        if (Tag.FENCES.isTagged(m) || Tag.WALLS.isTagged(m) || Tag.DOORS.isTagged(m)) return false;
        double top = b.getBoundingBox().getMaxY() - b.getY();
        return top > 0 && top <= 0.5;
    }

    private boolean climbable(int x, int y, int z) {
        Material m = w.getBlockAt(x, y, z).getType();
        return Tag.CLIMBABLE.isTagged(m);
    }

    private boolean water(int x, int y, int z) {
        Block b = w.getBlockAt(x, y, z);
        return b.getType() == Material.WATER || b.getType() == Material.BUBBLE_COLUMN
                || b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged() && b.isPassable();
    }

    private boolean loaded(int x, int z) {
        return w.isChunkLoaded(x >> 4, z >> 4);
    }
}
