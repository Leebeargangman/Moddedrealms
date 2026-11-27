package net.moddedrealms.smp.town;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Fence;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.Wall;

/**
 * Puts generated buildings right after they were placed without block updates: fences, panes, bars and walls are
 * joined to their neighbours and stairs take their corner shapes, the way the game would do it if a player had placed
 * them; and beds whose head ended up inside a wall are moved back into the room with the wall patched.
 */
final class Repair {
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    int shapes, beds;

    private Repair() {
    }

    /** Fix everything in a box (inclusive corners). Chunks are loaded as needed. */
    static Repair area(World w, int x0, int y0, int z0, int x1, int y1, int z1) {
        Repair r = new Repair();
        int ax = Math.min(x0, x1), bx = Math.max(x0, x1), ay = Math.max(w.getMinHeight(), Math.min(y0, y1)),
                by = Math.min(w.getMaxHeight() - 1, Math.max(y0, y1)), az = Math.min(z0, z1), bz = Math.max(z0, z1);
        for (int x = ax; x <= bx; x++)
            for (int z = az; z <= bz; z++)
                for (int y = ay; y <= by; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (m.isAir()) continue;
                    if (Tag.BED.contains(m)) r.bed(b);
                    else r.shape(b);
                }
        return r;
    }

    // ------------------------------------------------------------------ shapes

    private void shape(Block b) {
        BlockData d = b.getBlockData();
        BlockData before = d.clone();
        if (d instanceof Stairs s) stairs(b, s);
        else if (d instanceof Wall wl) wall(b, wl);
        else if (d instanceof Fence || d instanceof MultipleFacing mf && pane(b.getType())) {
            MultipleFacing f = (MultipleFacing) d;
            for (BlockFace side : SIDES) if (f.getAllowedFaces().contains(side)) f.setFace(side, connects(b, side));
        } else if (d instanceof Gate g) {
            // a gate set into a wall sits lower
            BlockFace l = rotate(g.getFacing(), 1), rr = rotate(g.getFacing(), 3);
            g.setInWall(b.getRelative(l).getBlockData() instanceof Wall || b.getRelative(rr).getBlockData() instanceof Wall);
        } else return;
        if (!d.equals(before)) {
            b.setBlockData(d, false);
            shapes++;
        }
    }

    private static boolean pane(Material m) {
        return m == Material.IRON_BARS || m.name().endsWith("GLASS_PANE");
    }

    private static boolean woodFence(Material m) {
        return m.name().endsWith("_FENCE") && m != Material.NETHER_BRICK_FENCE;
    }

    /** Whether the fence/pane/bars/wall at b joins towards side. */
    private static boolean connects(Block b, BlockFace side) {
        Block n = b.getRelative(side);
        Material self = b.getType(), m = n.getType();
        BlockData nd = n.getBlockData();
        if (nd instanceof Gate g) return g.getFacing() != side && g.getFacing() != side.getOppositeFace();
        if (self.name().endsWith("_FENCE")) {
            if (m.name().endsWith("_FENCE")) return woodFence(self) == woodFence(m);
            return sturdy(m);
        }
        if (pane(self)) return pane(m) || nd instanceof Wall || sturdy(m);
        // walls
        return nd instanceof Wall || pane(m) || sturdy(m);
    }

    /** Close enough to "has a full face": opaque full blocks, minus the ones the game doesn't join to. */
    private static boolean sturdy(Material m) {
        if (!m.isOccluding()) return false;
        return !Tag.LEAVES.contains(m) && m != Material.PUMPKIN && m != Material.CARVED_PUMPKIN && m != Material.JACK_O_LANTERN && m != Material.MELON;
    }

    private void wall(Block b, Wall w) {
        int n = 0;
        boolean[] c = new boolean[4];
        for (int i = 0; i < 4; i++) {
            c[i] = connects(b, SIDES[i]);
            if (c[i]) n++;
        }
        Block above = b.getRelative(BlockFace.UP);
        boolean tallAbove = sturdy(above.getType()) || above.getBlockData() instanceof Wall;
        for (int i = 0; i < 4; i++) w.setHeight(SIDES[i], !c[i] ? Wall.Height.NONE : tallAbove ? Wall.Height.TALL : Wall.Height.LOW);
        boolean straight = n == 2 && (c[0] && c[2] || c[1] && c[3]);
        w.setUp(!straight || above.getBlockData() instanceof Wall && ((Wall) above.getBlockData()).isUp() || tallAbove && !straight);
    }

    /** The game's own stair-shape rule. */
    private static void stairs(Block b, Stairs s) {
        BlockFace dir = s.getFacing();
        Stairs.Shape shape = Stairs.Shape.STRAIGHT;
        if (b.getRelative(dir).getBlockData() instanceof Stairs f && f.getHalf() == s.getHalf()) {
            BlockFace d1 = f.getFacing();
            if (axis(d1) != axis(dir) && canTake(b, s, d1.getOppositeFace())) {
                shape = d1 == ccw(dir) ? Stairs.Shape.OUTER_LEFT : Stairs.Shape.OUTER_RIGHT;
            }
        }
        if (shape == Stairs.Shape.STRAIGHT && b.getRelative(dir.getOppositeFace()).getBlockData() instanceof Stairs k && k.getHalf() == s.getHalf()) {
            BlockFace d2 = k.getFacing();
            if (axis(d2) != axis(dir) && canTake(b, s, d2)) {
                shape = d2 == ccw(dir) ? Stairs.Shape.INNER_LEFT : Stairs.Shape.INNER_RIGHT;
            }
        }
        s.setShape(shape);
    }

    private static boolean canTake(Block b, Stairs s, BlockFace face) {
        return !(b.getRelative(face).getBlockData() instanceof Stairs n) || n.getFacing() != s.getFacing() || n.getHalf() != s.getHalf();
    }

    private static int axis(BlockFace f) {
        return f == BlockFace.NORTH || f == BlockFace.SOUTH ? 0 : 1;
    }

    private static BlockFace ccw(BlockFace f) {
        return rotate(f, 3);
    }

    private static BlockFace rotate(BlockFace f, int quarterTurnsClockwise) {
        int i = 0;
        for (; i < 4; i++) if (SIDES[i] == f) break;
        return SIDES[(i + quarterTurnsClockwise) % 4];
    }

    // ------------------------------------------------------------------ beds

    /**
     * A bed head sitting in a wall: beyond the head is open air, and above it is the wall (or a window). The bed moves
     * one block back into the room (the foot steps back, the head takes the foot's place) and the hole gets the wall
     * block found above it.
     */
    private void bed(Block b) {
        if (!(b.getBlockData() instanceof Bed bd) || bd.getPart() != Bed.Part.HEAD) return;
        BlockFace f = bd.getFacing();
        Block beyond = b.getRelative(f), above = b.getRelative(BlockFace.UP);
        boolean wallAbove = sturdy(above.getType()) || pane(above.getType());
        if (!wallAbove || !beyond.isPassable() || beyond.getType().isSolid()) return;
        Block foot = b.getRelative(f.getOppositeFace());
        if (!(foot.getBlockData() instanceof Bed fb) || fb.getPart() != Bed.Part.FOOT) return;
        Block newFoot = foot.getRelative(f.getOppositeFace());
        if (!newFoot.isReplaceable() && !newFoot.getType().isAir()) return;
        Material patch = null;
        for (int dy = 1; dy <= 3 && patch == null; dy++) {
            Material m = b.getRelative(0, dy, 0).getType();
            if (sturdy(m) && !m.name().endsWith("_LOG") && !m.name().endsWith("_WOOD")) patch = m;
        }
        if (patch == null) patch = sturdy(above.getType()) ? above.getType() : Material.OAK_PLANKS;
        Material bedType = b.getType();
        // clear the old bed without drops, then place it again one block further in
        b.setType(Material.AIR, false);
        foot.setType(Material.AIR, false);
        Bed nf = (Bed) bedType.createBlockData();
        nf.setFacing(f);
        nf.setPart(Bed.Part.FOOT);
        Bed nh = (Bed) bedType.createBlockData();
        nh.setFacing(f);
        nh.setPart(Bed.Part.HEAD);
        newFoot.setBlockData(nf, false);
        foot.setBlockData(nh, false);
        b.setType(patch, false);
        beds++;
    }

    /** Material groups used above (kept here so the repair has no other dependencies). */
    private static final class Tag {
        static final Set<Material> BED = EnumSet.noneOf(Material.class);
        static final Set<Material> LEAVES = EnumSet.noneOf(Material.class);

        static {
            for (Material m : Material.values()) {
                if (m.isLegacy() || !m.isBlock()) continue;
                if (m.name().endsWith("_BED")) BED.add(m);
                if (m.name().endsWith("_LEAVES")) LEAVES.add(m);
            }
        }
    }
}
