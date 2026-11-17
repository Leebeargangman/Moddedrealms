package net.moddedrealms.testplayers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Finding a way to walk somewhere, the way a player picks a route: A* over the blocks, stepping up one block at
 * a time, dropping down at most three, never into lava, swimming only when it has to. Only loaded chunks are
 * searched and the search is capped, so a far or impossible target just comes back null (then the test player
 * heads straight for it and goes around what's in the way).
 */
final class Path {

    /** A spot to stand: the block its feet are in. */
    record Step(int x, int y, int z) {
        Location at(World w) {
            return new Location(w, x + 0.5, y, z + 0.5);
        }
    }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private Path() { }

    /**
     * A route from where it stands to within reach blocks (sideways) of (tx, ty, tz); ty may be NaN for any
     * height. Null if there's none within maxNodes.
     */
    static List<Step> find(Location from, double tx, double ty, double tz, double reach, int maxNodes) {
        World w = from.getWorld();
        Step start = stand(w, from.getBlockX(), from.getBlockY(), from.getBlockZ());
        if (start == null) start = new Step(from.getBlockX(), from.getBlockY(), from.getBlockZ());
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        Map<Long, Step> parent = new HashMap<>();
        Map<Long, Double> cost = new HashMap<>();
        Map<Long, Step> steps = new HashMap<>();
        long sk = key(start);
        steps.put(sk, start);
        cost.put(sk, 0.0);
        parent.put(sk, null);
        open.add(new double[] {h(start, tx, tz), sk});
        Step best = start;
        double bestH = Double.MAX_VALUE;
        int n = 0;
        while (!open.isEmpty() && n++ < maxNodes) {
            double[] top = open.poll();
            long k = (long) top[1];
            Step s = steps.get(k);
            double d = Math.hypot(s.x + 0.5 - tx, s.z + 0.5 - tz);
            if (d <= reach && (Double.isNaN(ty) || Math.abs(s.y - ty) <= 4)) return build(parent, s);
            if (d < bestH) {
                bestH = d;
                best = s;
            }
            double g = cost.get(k);
            for (int[] dir : DIRS) {
                int nx = s.x + dir[0];
                int nz = s.z + dir[1];
                if (!w.isChunkLoaded(nx >> 4, nz >> 4)) continue;
                if (dir[0] != 0 && dir[1] != 0) {
                    // Diagonal only if both sides are open (no cutting corners through walls).
                    if (!clear(w, s.x + dir[0], s.y, s.z) || !clear(w, s.x, s.y, s.z + dir[1])) continue;
                }
                Step next = stand(w, nx, s.y, nz);
                if (next == null) continue;
                if (next.y > s.y && !clear(w, s.x, s.y + 2, s.z)) continue; // no room to jump
                // Stepping down: it walks across at its own height first, so that needs room too (low leaves...).
                if (next.y < s.y && (!clear(w, nx, s.y, nz) || !clear(w, nx, s.y + 1, nz))) continue;
                long nk = key(next);
                double step = (dir[0] != 0 && dir[1] != 0 ? 1.414 : 1) + (next.y > s.y ? 0.5 : 0) + (next.y < s.y ? 0.2 * (s.y - next.y) : 0)
                        + (water(w, next) ? 4 : 0);
                double ng = g + step;
                Double old = cost.get(nk);
                if (old != null && old <= ng) continue;
                cost.put(nk, ng);
                parent.put(nk, s);
                steps.put(nk, next);
                open.add(new double[] {ng + h(next, tx, tz), nk});
            }
        }
        // No full route: the part that gets closest, if it gets meaningfully closer.
        if (best != start && bestH < Math.hypot(start.x + 0.5 - tx, start.z + 0.5 - tz) - 4) return build(parent, best);
        return null;
    }

    private static double h(Step s, double tx, double tz) {
        double dx = Math.abs(s.x + 0.5 - tx);
        double dz = Math.abs(s.z + 0.5 - tz);
        return Math.max(dx, dz) + 0.414 * Math.min(dx, dz);
    }

    private static List<Step> build(Map<Long, Step> parent, Step end) {
        List<Step> out = new ArrayList<>();
        for (Step s = end; s != null; s = parent.get(key(s))) out.add(s);
        Collections.reverse(out);
        if (!out.isEmpty()) out.removeFirst(); // where it's standing
        return out;
    }

    /** Where it would stand in column (x, z) coming from height y: up one, level, or down to three. Null if nowhere. */
    private static Step stand(World w, int x, int y, int z) {
        for (int ny = y + 1; ny >= y - 3; ny--) {
            if (ny <= w.getMinHeight() || ny >= w.getMaxHeight() - 2) continue;
            if (!clear(w, x, ny, z) || !clear(w, x, ny + 1, z)) continue;
            Block below = w.getBlockAt(x, ny - 1, z);
            Material bt = below.getType();
            if (bt == Material.LAVA || bt == Material.MAGMA_BLOCK || bt == Material.CAMPFIRE || bt == Material.FIRE) return null;
            if (w.getBlockAt(x, ny, z).getType() == Material.LAVA) return null;
            boolean solid = !below.isPassable() && !bt.name().endsWith("_FENCE") && !bt.name().endsWith("_WALL") && !bt.name().endsWith("_FENCE_GATE");
            if (solid || bt == Material.WATER) return new Step(x, ny, z);
            if (ny < y + 1 && !below.isPassable()) return null;
        }
        return null;
    }

    private static boolean clear(World w, int x, int y, int z) {
        Block b = w.getBlockAt(x, y, z);
        Material t = b.getType();
        if (t == Material.LAVA || t == Material.FIRE || t == Material.SWEET_BERRY_BUSH || t == Material.POWDER_SNOW || t == Material.COBWEB) return false;
        return b.isPassable() || t == Material.WATER;
    }

    private static boolean water(World w, Step s) {
        return w.getBlockAt(s.x, s.y, s.z).getType() == Material.WATER || w.getBlockAt(s.x, s.y - 1, s.z).getType() == Material.WATER;
    }

    private static long key(Step s) {
        return ((long) (s.x & 0x3FFFFFF) << 38) | ((long) (s.z & 0x3FFFFFF) << 12) | (s.y & 0xFFF);
    }
}
