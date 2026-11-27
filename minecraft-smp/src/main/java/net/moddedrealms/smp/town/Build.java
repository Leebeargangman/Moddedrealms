package net.moddedrealms.smp.town;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;

/**
 * Small toolkit for generated buildings. Coordinates are local to an origin and a facing: the building's front is
 * local -z, and {@code front} says which way that is in the world. Directional blocks are turned to match, and
 * glass panes / bars / fences are joined up at the end ({@link #finish()}).
 */
final class Build {
    final World w;
    final int ox, oy, oz;
    final BlockFace front;
    private final List<Block> joins;

    Build(World w, int ox, int oy, int oz, BlockFace front) {
        this(w, ox, oy, oz, front, new ArrayList<>());
    }

    private Build(World w, int ox, int oy, int oz, BlockFace front, List<Block> joins) {
        this.joins = joins;
        this.w = w;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.front = front;
    }

    /** Same origin, facing another way. */
    Build turned(BlockFace f) {
        return new Build(w, ox, oy, oz, f, joins);
    }

    Build at(int dx, int dy, int dz) {
        int[] p = rot(dx, dz);
        return new Build(w, ox + p[0], oy + dy, oz + p[1], front, joins);
    }

    private int[] rot(int x, int z) {
        return switch (front) {
            case SOUTH -> new int[]{-x, -z};
            case EAST -> new int[]{-z, x};
            case WEST -> new int[]{z, -x};
            default -> new int[]{x, z};
        };
    }

    /** A local face (NORTH = the front) in world terms. */
    BlockFace face(BlockFace local) {
        if (local == BlockFace.UP || local == BlockFace.DOWN) return local;
        BlockFace[] order = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
        int li = idx(order, local), fi = idx(order, front);
        return order[(li + fi) % 4];
    }

    private static int idx(BlockFace[] a, BlockFace f) {
        for (int i = 0; i < a.length; i++) if (a[i] == f) return i;
        return 0;
    }

    Block block(int x, int y, int z) {
        int[] p = rot(x, z);
        return w.getBlockAt(ox + p[0], oy + y, oz + p[1]);
    }

    void set(int x, int y, int z, Material m) {
        Block b = block(x, y, z);
        b.setType(m, false);
        if (m == Material.GLASS_PANE || m == Material.IRON_BARS || m.name().endsWith("_FENCE") || m.name().endsWith("STAINED_GLASS_PANE")) joins.add(b);
    }

    void data(int x, int y, int z, BlockData d) {
        block(x, y, z).setBlockData(d, false);
    }

    void fill(int x0, int y0, int z0, int x1, int y1, int z1, Material m) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) set(x, y, z, m);
    }

    /** Hollow box walls (no floor/ceiling). */
    void walls(int x0, int y0, int z0, int x1, int y1, int z1, Material m) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                set(x, y, z0, m);
                set(x, y, z1, m);
            }
            for (int z = z0; z <= z1; z++) {
                set(x0, y, z, m);
                set(x1, y, z, m);
            }
        }
    }

    void facing(int x, int y, int z, Material m, BlockFace localFacing) {
        BlockData d = m.createBlockData();
        if (d instanceof Directional dir) dir.setFacing(face(localFacing));
        else if (d instanceof Rotatable rr) rr.setRotation(face(localFacing));
        data(x, y, z, d);
    }

    void stair(int x, int y, int z, Material m, BlockFace localFacing, boolean upsideDown) {
        Stairs s = (Stairs) m.createBlockData();
        s.setFacing(face(localFacing));
        s.setHalf(upsideDown ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
        data(x, y, z, s);
    }

    void slab(int x, int y, int z, Material m, boolean top) {
        Slab s = (Slab) m.createBlockData();
        s.setType(top ? Slab.Type.TOP : Slab.Type.BOTTOM);
        data(x, y, z, s);
    }

    void log(int x, int y, int z, Material m, Axis localAxis) {
        if (!(m.createBlockData() instanceof Orientable o)) {
            set(x, y, z, m);
            return;
        }
        Axis a = localAxis;
        if (a != Axis.Y && (front == BlockFace.EAST || front == BlockFace.WEST)) a = a == Axis.X ? Axis.Z : Axis.X;
        o.setAxis(a);
        data(x, y, z, o);
    }

    void lantern(int x, int y, int z, boolean hanging) {
        Lantern l = (Lantern) Material.LANTERN.createBlockData();
        l.setHanging(hanging);
        data(x, y, z, l);
    }

    /** Door facing out of the front (local NORTH). */
    void door(int x, int y, int z, Material m, BlockFace localFacing) {
        Door lower = (Door) m.createBlockData();
        lower.setFacing(face(localFacing));
        lower.setHalf(Bisected.Half.BOTTOM);
        Door upper = (Door) m.createBlockData();
        upper.setFacing(face(localFacing));
        upper.setHalf(Bisected.Half.TOP);
        data(x, y, z, lower);
        data(x, y + 1, z, upper);
    }

    /** Bed with its head towards localHead. */
    void bed(int x, int y, int z, Material m, BlockFace localHead) {
        Bed foot = (Bed) m.createBlockData();
        foot.setFacing(face(localHead));
        foot.setPart(Bed.Part.FOOT);
        Bed head = (Bed) m.createBlockData();
        head.setFacing(face(localHead));
        head.setPart(Bed.Part.HEAD);
        data(x, y, z, foot);
        int hx = x + localHead.getModX(), hz = z + localHead.getModZ();
        data(hx, y, hz, head);
    }

    /** Gable roof of stairs over x0..x1 (ridge along local z), starting at height y. */
    void gable(int x0, int x1, int z0, int z1, int y, Material stairs, Material fill) {
        int left = x0 - 1, right = x1 + 1, h = 0;
        while (left <= right) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                if (left == right) {
                    slab(left, y + h, z, slabOf(stairs), false);
                } else {
                    stair(left, y + h, z, stairs, BlockFace.EAST, false);
                    stair(right, y + h, z, stairs, BlockFace.WEST, false);
                    for (int x = left + 1; x < right; x++) if (z == z0 || z == z1) set(x, y + h, z, fill);
                }
            }
            left++;
            right--;
            h++;
        }
    }

    static Material slabOf(Material stairs) {
        Material m = Material.matchMaterial(stairs.name().replace("_STAIRS", "_SLAB"));
        return m == null ? Material.STONE_SLAB : m;
    }

    /** Join panes, bars and fences to their neighbours. */
    void finish() {
        for (Block b : joins) {
            if (!(b.getBlockData() instanceof MultipleFacing mf)) continue;
            for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
                Material n = b.getRelative(f).getType();
                boolean join = n.isOccluding() || n == Material.IRON_BARS || n.name().endsWith("GLASS_PANE") || n.name().endsWith("_FENCE")
                        || n.name().endsWith("_FENCE_GATE") || n.name().endsWith("_WALL");
                if (mf.getAllowedFaces().contains(f)) mf.setFace(f, join);
            }
            b.setBlockData(mf, false);
        }
        joins.clear();
    }
}
