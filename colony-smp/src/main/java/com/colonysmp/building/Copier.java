package com.colonysmp.building;

import com.colonysmp.ColonySMP;
import com.colonysmp.model.BuildOrder;
import com.colonysmp.model.Colony;
import com.colonysmp.model.CustomBlueprint;
import com.colonysmp.util.BlockPos;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Copies a wand selection (a 3D box) into a blueprint the Builders can build again. */
public final class Copier {

    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public int dx() {
            return maxX - minX + 1;
        }

        public int dy() {
            return maxY - minY + 1;
        }

        public int dz() {
            return maxZ - minZ + 1;
        }

        public long volume() {
            return (long) dx() * dy() * dz();
        }

        public String size() {
            return dx() + "x" + dy() + "x" + dz();
        }
    }

    private Copier() {}

    public static Box box(BlockPos a, BlockPos b) {
        return new Box(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
                Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
    }

    /** Why this box can't be copied, or null. */
    public static String check(ColonySMP plugin, World w, Box box) {
        int max = plugin.settings().blueprintMaxSize;
        if (box.dx() > max || box.dy() > max || box.dz() > max) return "Too big: copies can be at most " + max + " blocks on each side (this is " + box.size() + ").";
        if (box.volume() < 2) return "Select at least two blocks: left-click one corner and right-click the opposite corner (top and bottom).";
        for (int cx = box.minX >> 4; cx <= box.maxX >> 4; cx++) {
            for (int cz = box.minZ >> 4; cz <= box.maxZ >> 4; cz++) {
                if (!w.isChunkLoaded(cx, cz)) return "Part of the selection isn't loaded. Stand closer.";
            }
        }
        return null;
    }

    /** Is a name usable for a new copy in this colony? */
    public static String checkName(ColonySMP plugin, Colony c, String name) {
        if (name.length() < 2 || name.length() > 24) return "Blueprint names must be 2 to 24 characters.";
        if (!name.matches("[A-Za-z0-9 '_\\-]+")) return "Use letters, numbers, spaces and ' _ - only.";
        if (c.blueprints.containsKey(CustomBlueprint.key(name))) return "Your colony already has a blueprint called that. Delete it first or pick another name.";
        for (com.colonysmp.model.BuildingType t : com.colonysmp.model.BuildingType.values()) {
            if (t.display.equalsIgnoreCase(name.trim())) return "That's the name of a standard plan. Pick another name.";
        }
        if (c.blueprints.size() >= plugin.settings().blueprintMax) return "Your colony already has " + plugin.settings().blueprintMax + " blueprints. Delete one first (/colony blueprints).";
        return null;
    }

    /**
     * Copies the box as seen by someone looking {@code facing}: the side nearest them becomes the front.
     * Returns null (with the reason in err[0]) if it has too many blocks.
     */
    public static CustomBlueprint copy(ColonySMP plugin, World w, Box box, BlockFace facing, String name, String[] err) {
        BlockFace r = Blueprint.right(facing);
        int sx = r.getModX() != 0 ? box.dx() : box.dz();
        int sz = facing.getModX() != 0 ? box.dx() : box.dz();
        int sy = box.dy();
        // world position of local (0, 0, 0): the front-left-bottom corner
        int bx = r.getModX() == 1 || facing.getModX() == 1 ? box.minX : box.maxX;
        int bz = r.getModZ() == 1 || facing.getModZ() == 1 ? box.minZ : box.maxZ;
        List<String> palette = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        palette.add("minecraft:air");
        index.put("minecraft:air", 0);
        int[] blocks = new int[sx * sy * sz];
        int solid = 0, floor = 0;
        int limit = plugin.settings().blueprintMaxBlocks;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int wx = bx + r.getModX() * x + facing.getModX() * z;
                    int wz = bz + r.getModZ() * x + facing.getModZ() * z;
                    Block b = w.getBlockAt(wx, box.minY + y, wz);
                    Material m = b.getType();
                    int id = 0;
                    if (!m.isAir()) {
                        String data = b.getBlockData().getAsString();
                        Integer have = index.get(data);
                        if (have == null) {
                            have = palette.size();
                            palette.add(data);
                            index.put(data, have);
                        }
                        id = have;
                        if (++solid > limit) {
                            err[0] = "That has more than " + limit + " blocks. Copy something smaller.";
                            return null;
                        }
                        if (y == 0 && m.isSolid()) floor++;
                    }
                    blocks[(y * sz + z) * sx + x] = id;
                }
            }
        }
        if (solid == 0) {
            err[0] = "There's nothing but air in that selection.";
            return null;
        }
        // a solid bottom layer is the building's floor or foundation: it replaces the ground it's built on
        int sink = floor >= sx * sz * 0.6 ? 1 : 0;
        CustomBlueprint cb = new CustomBlueprint(name.trim(), sx, sy, sz, facing, sink, palette, blocks);
        cb.created = System.currentTimeMillis();
        return cb;
    }

    /** Removes a copy, cancelling orders to build it. Returns how many orders were cancelled. */
    public static int delete(Colony c, CustomBlueprint cb) {
        c.blueprints.remove(CustomBlueprint.key(cb.name));
        int n = 0;
        for (var it = c.buildQueue.iterator(); it.hasNext(); ) {
            BuildOrder j = it.next();
            if (j.custom != null && CustomBlueprint.key(j.custom).equals(CustomBlueprint.key(cb.name))) {
                it.remove();
                n++;
            }
        }
        return n;
    }
}
