package com.colonysmp.npc;

import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Colony;
import com.colonysmp.util.BlockPos;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Where the inside of a fenced yard is: its ground tiles, its gates, and the spots well away from them. */
public final class Pens {

    private Pens() {}

    private static long col(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** The tile columns of a yard (cached on the building until its tiles change). */
    private static Set<Long> columns(Building pen) {
        Set<Long> cols = pen.tileColumns;
        if (cols == null || pen.tileColumnsFor != pen.tiles.size()) {
            cols = new HashSet<>(pen.tiles.size() * 2);
            for (BlockPos t : pen.tiles) cols.add(col(t.x(), t.z()));
            pen.tileColumns = cols;
            pen.tileColumnsFor = pen.tiles.size();
        }
        return cols;
    }

    /** Is this point on the yard's ground (inside the fence), not merely inside its bounding box? */
    public static boolean inside(Building pen, Location l) {
        if (pen.tiles.isEmpty()) return pen.inside(l.getX(), l.getY(), l.getZ());
        if (pen.min != null && (l.getY() < pen.min.y() - 2 || l.getY() > pen.max.y() + 3)) return false;
        Set<Long> cols = columns(pen);
        if (cols.contains(col(l.getBlockX(), l.getBlockZ()))) return true;
        // a small animal pressed against the inside of the fence has its centre in the fence's own column (a fence
        // post is thin): it is inside if a tile is within reach of its body. An animal outside the fence can get no
        // closer than a quarter of a block to the fence's column, so it never passes this test.
        double x = l.getX(), z = l.getZ(), m = EDGE;
        return cols.contains(col(floor(x + m), floor(z))) || cols.contains(col(floor(x - m), floor(z)))
                || cols.contains(col(floor(x), floor(z + m))) || cols.contains(col(floor(x), floor(z - m)));
    }

    /** How far into the fence's column an animal standing inside may reach (see {@link #inside}). */
    static final double EDGE = 0.3;

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    private static boolean yard(Building b) {
        return b.type == BuildingType.ANIMAL_PEN || b.type == BuildingType.STABLES || b.type.shape == BuildingType.Shape.PEN;
    }

    /** The yard a gate belongs to, or null. */
    public static Building penOf(Colony col, Block gate) {
        for (Building b : col.buildings.values()) {
            if (!yard(b)) continue;
            for (BlockPos d : b.doors) if (d.x() == gate.getX() && d.z() == gate.getZ() && Math.abs(d.y() - gate.getY()) <= 1) return b;
        }
        // a gate that was added after the yard was registered: any yard with a tile right beside it
        for (Building b : col.buildings.values()) {
            if (!yard(b) || b.tiles.isEmpty()) continue;
            Set<Long> cols = columns(b);
            if (cols.contains(col(gate.getX() + 1, gate.getZ())) || cols.contains(col(gate.getX() - 1, gate.getZ()))
                    || cols.contains(col(gate.getX(), gate.getZ() + 1)) || cols.contains(col(gate.getX(), gate.getZ() - 1))) return b;
        }
        return null;
    }

    /** A standing point a step or two inside the yard from one of its gates, or null. */
    public static Location insideOf(Colony col, Block gate) {
        Building pen = penOf(col, gate);
        if (pen == null) return null;
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        BlockPos first = null, second = null;
        for (int[] s : sides) {
            BlockPos a = tile(pen, gate.getX() + s[0], gate.getZ() + s[1]);
            if (a == null) continue;
            BlockPos b = tile(pen, gate.getX() + 2 * s[0], gate.getZ() + 2 * s[1]);
            if (first == null || b != null && second == null) {
                first = a;
                second = b;
            }
        }
        BlockPos at = second != null ? second : first;
        return at == null ? null : at.center(gate.getWorld());
    }

    private static BlockPos tile(Building pen, int x, int z) {
        if (!columns(pen).contains(col(x, z))) return null;
        for (BlockPos t : pen.tiles) if (t.x() == x && t.z() == z) return t;
        return null;
    }

    /** A random spot inside the yard, at least two blocks from every gate where the yard is big enough. */
    public static Location interiorSpot(World w, Building pen) {
        if (pen.tiles.isEmpty()) return null;
        List<BlockPos> good = new ArrayList<>();
        for (BlockPos t : pen.tiles) {
            boolean nearGate = false;
            for (BlockPos g : pen.doors) {
                if (Math.abs(g.x() - t.x()) <= 2 && Math.abs(g.z() - t.z()) <= 2) {
                    nearGate = true;
                    break;
                }
            }
            if (!nearGate) good.add(t);
        }
        List<BlockPos> from = good.isEmpty() ? pen.tiles : good;
        return from.get(ThreadLocalRandom.current().nextInt(from.size())).center(w);
    }
}
