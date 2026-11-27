package com.colonysmp.building;

import com.colonysmp.model.Feature;
import com.colonysmp.util.BlockPos;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the enclosed room around a point by flood-filling its air. A room is enclosed when the fill stays
 * small: any hole to the outside makes it leak past the limits. Everything solid touching the air (walls,
 * furniture, workstations) is checked for building features.
 */
public final class RoomScanner {

    public static final int MAX_CELLS = 3000, MAX_SPAN = 32, MAX_HEIGHT = 16;
    private static final BlockFace[] DIRS = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN};

    public record Room(String error, Set<BlockPos> interior, BlockPos min, BlockPos max, int floor,
                       List<BlockPos> beds, List<BlockPos> doors, List<BlockPos> ironDoors, Map<Feature, Integer> features,
                       Map<Feature, List<BlockPos>> found) {
        public boolean enclosed() {
            return error == null;
        }

        public int count(Feature f) {
            return features.getOrDefault(f, 0);
        }

        public List<BlockPos> positions(Feature f) {
            return found.getOrDefault(f, List.of());
        }
    }

    private RoomScanner() {}

    /** Can air flow through this block? Doors, panes, fences and beds count as walls; banners don't (a Building Marker stands inside). */
    public static boolean open(Block b) {
        Material m = b.getType();
        if (m.isAir() || Tag.BANNERS.isTagged(m)) return true;
        if (Tag.DOORS.isTagged(m) || Tag.TRAPDOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m) || Tag.FENCES.isTagged(m)
                || Tag.WALLS.isTagged(m) || Tag.BEDS.isTagged(m) || m == Material.IRON_BARS || m.name().endsWith("GLASS_PANE")) {
            return false;
        }
        return !m.isSolid();
    }

    public static Room scan(Block start) {
        World w = start.getWorld();
        if (!open(start)) return fail("Stand inside the room, or click its floor or a wall from inside.");
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        BlockPos s = BlockPos.of(start);
        seen.add(s);
        queue.add(s);
        int minX = s.x(), maxX = s.x(), minY = s.y(), maxY = s.y(), minZ = s.z(), maxZ = s.z();
        Set<BlockPos> walls = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            for (BlockFace f : DIRS) {
                BlockPos n = p.relative(f);
                if (seen.contains(n) || walls.contains(n)) continue;
                if (!w.isChunkLoaded(n.x() >> 4, n.z() >> 4)) return fail("Part of the room isn't loaded.");
                if (n.y() < w.getMinHeight() || n.y() >= w.getMaxHeight()) return fail("The room is open to the sky or the void.");
                Block nb = n.block(w);
                if (!open(nb)) {
                    walls.add(n);
                    continue;
                }
                seen.add(n);
                if (seen.size() > MAX_CELLS) return fail("The room isn't enclosed (or is far too big). Close every gap with walls, a roof and doors.");
                minX = Math.min(minX, n.x());
                maxX = Math.max(maxX, n.x());
                minY = Math.min(minY, n.y());
                maxY = Math.max(maxY, n.y());
                minZ = Math.min(minZ, n.z());
                maxZ = Math.max(maxZ, n.z());
                if (maxX - minX >= MAX_SPAN || maxZ - minZ >= MAX_SPAN || maxY - minY >= MAX_HEIGHT) {
                    return fail("The room isn't enclosed (or is far too big). Close every gap with walls, a roof and doors.");
                }
                queue.add(n);
            }
        }
        List<BlockPos> beds = new ArrayList<>(), doors = new ArrayList<>(), iron = new ArrayList<>();
        Map<Feature, Integer> features = new EnumMap<>(Feature.class);
        Map<Feature, List<BlockPos>> found = new EnumMap<>(Feature.class);
        Set<BlockPos> bedSeen = new HashSet<>(), doorSeen = new HashSet<>();
        for (BlockPos wp : walls) {
            Block b = wp.block(w);
            Material m = b.getType();
            if (Tag.BEDS.isTagged(m) && b.getBlockData() instanceof Bed bed) {
                BlockPos head = bed.getPart() == Bed.Part.HEAD ? wp : wp.relative(bed.getFacing());
                if (!bedSeen.add(head)) continue;
                beds.add(head);
                add(features, found, Feature.BED, head);
                continue;
            }
            if (Tag.DOORS.isTagged(m) && b.getBlockData() instanceof Bisected bi) {
                BlockPos lower = bi.getHalf() == Bisected.Half.TOP ? wp.add(0, -1, 0) : wp;
                if (!doorSeen.add(lower)) continue;
                if (m == Material.IRON_DOOR) {
                    iron.add(lower);
                    add(features, found, Feature.IRON_DOOR, lower);
                } else {
                    doors.add(lower);
                    add(features, found, Feature.DOOR, lower);
                }
                continue;
            }
            for (Feature f : Feature.values()) {
                if (f == Feature.BED || f == Feature.DOOR || f == Feature.IRON_DOOR) continue;
                if (f.matches(m)) add(features, found, f, wp);
            }
        }
        int floor = 0;
        for (BlockPos p : seen) {
            if (p.y() != minY && seen.contains(p.add(0, -1, 0))) continue;
            Block below = p.add(0, -1, 0).block(w);
            if (!open(below)) floor++;
        }
        return new Room(null, seen, new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), floor, beds, doors, iron, features, found);
    }

    private static void add(Map<Feature, Integer> features, Map<Feature, List<BlockPos>> found, Feature f, BlockPos p) {
        features.merge(f, 1, Integer::sum);
        found.computeIfAbsent(f, k -> new ArrayList<>()).add(p);
    }

    private static Room fail(String why) {
        return new Room(why, Set.of(), new BlockPos(0, 0, 0), new BlockPos(0, 0, 0), 0, List.of(), List.of(), List.of(), Map.of(), Map.of());
    }

    /** Is there a free standing spot (feet and head open, solid below) at this position? */
    public static boolean standable(World w, int x, int y, int z) {
        Block feet = w.getBlockAt(x, y, z);
        Block head = w.getBlockAt(x, y + 1, z);
        Block below = w.getBlockAt(x, y - 1, z);
        return feet.isPassable() && head.isPassable() && !feet.isLiquid() && (below.getType().isSolid() || Tag.BEDS.isTagged(below.getType()));
    }
}
