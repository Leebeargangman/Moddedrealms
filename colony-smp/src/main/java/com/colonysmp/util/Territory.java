package com.colonysmp.util;

import org.bukkit.Location;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The land a colony controls: a connected set of chunks. Colonies start with a few chunks around the Town
 * Hall and claim neighbouring chunks as they grow.
 */
public final class Territory {

    private final Set<Long> chunks = new HashSet<>();
    private int minCX = Integer.MAX_VALUE, maxCX = Integer.MIN_VALUE, minCZ = Integer.MAX_VALUE, maxCZ = Integer.MIN_VALUE;

    public static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static int cx(long key) {
        return (int) (key >> 32);
    }

    public static int cz(long key) {
        return (int) key;
    }

    public boolean containsChunk(int cx, int cz) {
        return chunks.contains(key(cx, cz));
    }

    public boolean contains(int x, int z) {
        return chunks.contains(key(x >> 4, z >> 4));
    }

    public boolean contains(Location l) {
        return contains(l.getBlockX(), l.getBlockZ());
    }

    public boolean contains(BlockPos p) {
        return contains(p.x(), p.z());
    }

    public boolean add(int cx, int cz) {
        if (!chunks.add(key(cx, cz))) return false;
        minCX = Math.min(minCX, cx);
        maxCX = Math.max(maxCX, cx);
        minCZ = Math.min(minCZ, cz);
        maxCZ = Math.max(maxCZ, cz);
        return true;
    }

    public boolean remove(int cx, int cz) {
        if (!chunks.remove(key(cx, cz))) return false;
        rebound();
        return true;
    }

    private void rebound() {
        minCX = minCZ = Integer.MAX_VALUE;
        maxCX = maxCZ = Integer.MIN_VALUE;
        for (long k : chunks) {
            minCX = Math.min(minCX, cx(k));
            maxCX = Math.max(maxCX, cx(k));
            minCZ = Math.min(minCZ, cz(k));
            maxCZ = Math.max(maxCZ, cz(k));
        }
    }

    public int size() {
        return chunks.size();
    }

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    public Collection<Long> keys() {
        return Collections.unmodifiableSet(chunks);
    }

    /** Shares an edge with a claimed chunk. */
    public boolean adjacent(int cx, int cz) {
        return containsChunk(cx + 1, cz) || containsChunk(cx - 1, cz) || containsChunk(cx, cz + 1) || containsChunk(cx, cz - 1);
    }

    /** Would the land still be in one piece without this chunk? */
    public boolean connectedWithout(int cx, int cz) {
        long gone = key(cx, cz);
        if (!chunks.contains(gone) || chunks.size() <= 1) return true;
        Long start = null;
        for (long k : chunks) {
            if (k != gone) {
                start = k;
                break;
            }
        }
        Set<Long> seen = new HashSet<>();
        ArrayDeque<Long> q = new ArrayDeque<>();
        q.add(start);
        seen.add(start);
        while (!q.isEmpty()) {
            long k = q.poll();
            int x = cx(k), z = cz(k);
            for (long n : new long[]{key(x + 1, z), key(x - 1, z), key(x, z + 1), key(x, z - 1)}) {
                if (n == gone || !chunks.contains(n) || !seen.add(n)) continue;
                q.add(n);
            }
        }
        return seen.size() == chunks.size() - 1;
    }

    // ───────────── geometry ─────────────

    public int minX() {
        return minCX << 4;
    }

    public int maxX() {
        return (maxCX << 4) + 15;
    }

    public int minZ() {
        return minCZ << 4;
    }

    public int maxZ() {
        return (maxCZ << 4) + 15;
    }

    public double centerX() {
        return (minX() + maxX() + 1) / 2.0;
    }

    public double centerZ() {
        return (minZ() + maxZ() + 1) / 2.0;
    }

    /** Horizontal distance from a point to the nearest claimed block (0 inside). */
    public double distanceTo(double x, double z) {
        if (contains((int) Math.floor(x), (int) Math.floor(z))) return 0;
        double best = Double.MAX_VALUE;
        for (long k : chunks) {
            double x0 = cx(k) << 4, z0 = cz(k) << 4;
            double dx = Math.max(Math.max(x0 - x, 0), x - (x0 + 16));
            double dz = Math.max(Math.max(z0 - z, 0), z - (z0 + 16));
            best = Math.min(best, dx * dx + dz * dz);
        }
        return best == Double.MAX_VALUE ? Double.MAX_VALUE : Math.sqrt(best);
    }

    /** Inside, or within a margin of the border. */
    public boolean near(double x, double z, double margin) {
        return distanceTo(x, z) <= margin;
    }

    /** Chunk distance to another territory (0 = touching or overlapping), for the gap between colonies. */
    public int chunkGap(int cx, int cz) {
        int best = Integer.MAX_VALUE;
        for (long k : chunks) {
            int d = Math.max(Math.abs(cx(k) - cx), Math.abs(cz(k) - cz));
            best = Math.min(best, d);
        }
        return best;
    }
}
