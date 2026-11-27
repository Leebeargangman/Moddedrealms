package com.colonysmp.building;

import com.colonysmp.model.Feature;
import com.colonysmp.util.BlockPos;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds a fenced yard (pens, stables, training grounds, archery ranges): the ground tiles reachable from a point
 * without crossing a fence, wall or solid block, up to one block of step. It has to be closed all round and have
 * at least one gate.
 */
public final class PenScanner {

    public static final int MAX_TILES = 1600, MAX_SPAN = 48;
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    public record Pen(String error, List<BlockPos> tiles, BlockPos min, BlockPos max, List<BlockPos> gates,
                      Map<Feature, Integer> features, Map<Feature, List<BlockPos>> found) {
        public boolean enclosed() {
            return error == null;
        }

        public int count(Feature f) {
            return features.getOrDefault(f, 0);
        }
    }

    private PenScanner() {}

    /** A fence, wall or anything solid and too high to step over (not a banner: a Building Marker stands inside). */
    private static boolean barrier(Block b) {
        Material m = b.getType();
        if (Tag.BANNERS.isTagged(m)) return false;
        return Tag.FENCES.isTagged(m) || Tag.WALLS.isTagged(m) || Tag.FENCE_GATES.isTagged(m) || m == Material.IRON_BARS
                || m.name().endsWith("GLASS_PANE") || (m.isSolid() && !Tag.SLABS.isTagged(m) && !Tag.WOOL_CARPETS.isTagged(m));
    }

    /** Feet position on this column at about this height, or null. */
    private static Integer ground(World w, int x, int y, int z) {
        for (int dy = 1; dy >= -1; dy--) {
            Block feet = w.getBlockAt(x, y + dy, z);
            Block below = feet.getRelative(BlockFace.DOWN);
            if (!barrier(feet) && feet.getType() != Material.WATER && (below.getType().isSolid() && !Tag.BANNERS.isTagged(below.getType()) || below.getType() == Material.WATER)) {
                if (barrier(below) && (Tag.FENCES.isTagged(below.getType()) || Tag.WALLS.isTagged(below.getType()))) continue;
                return y + dy;
            }
        }
        return null;
    }

    public static Pen scan(Block start) {
        World w = start.getWorld();
        Block feet = start.getType().isSolid() && !Tag.BANNERS.isTagged(start.getType()) ? start.getRelative(BlockFace.UP) : start;
        Integer y0 = ground(w, feet.getX(), feet.getY(), feet.getZ());
        if (y0 == null) return fail("Click the ground inside the fenced area.");
        Set<Long> seen = new HashSet<>();
        List<BlockPos> tiles = new ArrayList<>();
        Set<BlockPos> edge = new LinkedHashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        BlockPos s = new BlockPos(feet.getX(), y0, feet.getZ());
        q.add(s);
        seen.add(col(s.x(), s.z()));
        int minX = s.x(), maxX = s.x(), minZ = s.z(), maxZ = s.z(), minY = s.y(), maxY = s.y();
        while (!q.isEmpty()) {
            BlockPos p = q.poll();
            tiles.add(p);
            if (tiles.size() > MAX_TILES) return fail("The area isn't fenced in (or is far too big). Close every gap with fences, walls or gates.");
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
            minY = Math.min(minY, p.y());
            maxY = Math.max(maxY, p.y());
            if (maxX - minX >= MAX_SPAN || maxZ - minZ >= MAX_SPAN) return fail("The area isn't fenced in (or is far too big). Close every gap with fences, walls or gates.");
            for (BlockFace f : SIDES) {
                int nx = p.x() + f.getModX(), nz = p.z() + f.getModZ();
                if (seen.contains(col(nx, nz))) continue;
                if (!w.isChunkLoaded(nx >> 4, nz >> 4)) return fail("Part of the area isn't loaded.");
                Block at = w.getBlockAt(nx, p.y(), nz);
                if (barrier(at) && barrier(at.getRelative(BlockFace.UP))) {
                    edge.add(BlockPos.of(at));
                    continue;
                }
                if (barrier(at) && (Tag.FENCES.isTagged(at.getType()) || Tag.WALLS.isTagged(at.getType()) || Tag.FENCE_GATES.isTagged(at.getType()))) {
                    edge.add(BlockPos.of(at));
                    continue;
                }
                Integer ny = ground(w, nx, p.y(), nz);
                if (ny == null) {
                    edge.add(BlockPos.of(at));
                    continue;
                }
                seen.add(col(nx, nz));
                q.add(new BlockPos(nx, ny, nz));
            }
        }
        List<BlockPos> gates = new ArrayList<>();
        Map<Feature, Integer> features = new EnumMap<>(Feature.class);
        Map<Feature, List<BlockPos>> found = new EnumMap<>(Feature.class);
        for (BlockPos e : edge) {
            Block b = e.block(w);
            if (Tag.FENCE_GATES.isTagged(b.getType())) gates.add(e);
        }
        // features in or around the yard: hay dummies, targets, troughs
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                for (int y = minY - 1; y <= maxY + 2; y++) {
                    Material m = w.getBlockAt(x, y, z).getType();
                    for (Feature f : Feature.values()) {
                        if (f == Feature.HAY || f == Feature.TARGET || f == Feature.COMPOSTER || f == Feature.BARREL || f == Feature.CONTAINER
                                || f == Feature.BEEHIVE || f == Feature.STANDING_STONE) {
                            if (f.matches(m)) {
                                features.merge(f, 1, Integer::sum);
                                found.computeIfAbsent(f, k -> new ArrayList<>()).add(new BlockPos(x, y, z));
                            }
                        }
                    }
                }
            }
        }
        return new Pen(null, tiles, new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY + 1, maxZ), gates, features, found);
    }

    private static long col(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static Pen fail(String why) {
        return new Pen(why, List.of(), new BlockPos(0, 0, 0), new BlockPos(0, 0, 0), List.of(), Map.of(), Map.of());
    }
}
