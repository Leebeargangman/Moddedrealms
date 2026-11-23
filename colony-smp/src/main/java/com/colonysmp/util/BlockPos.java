package com.colonysmp.util;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

/** An immutable block position (the world is known from context: every colony lives in one world). */
public record BlockPos(int x, int y, int z) {

    public static BlockPos of(Block b) {
        return new BlockPos(b.getX(), b.getY(), b.getZ());
    }

    public static BlockPos of(Location l) {
        return new BlockPos(l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    public static BlockPos parse(String s) {
        if (s == null || s.isBlank()) return null;
        String[] p = s.split(",");
        if (p.length != 3) return null;
        try {
            return new BlockPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Block block(World w) {
        return w.getBlockAt(x, y, z);
    }

    /** Centre of the block, at its bottom. */
    public Location center(World w) {
        return new Location(w, x + 0.5, y, z + 0.5);
    }

    public BlockPos add(int dx, int dy, int dz) {
        return new BlockPos(x + dx, y + dy, z + dz);
    }

    public BlockPos relative(BlockFace f) {
        return new BlockPos(x + f.getModX(), y + f.getModY(), z + f.getModZ());
    }

    public double distSq(BlockPos o) {
        double dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distSq(Location l) {
        double dx = x + 0.5 - l.getX(), dy = y - l.getY(), dz = z + 0.5 - l.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    public double horizDistSq(Location l) {
        double dx = x + 0.5 - l.getX(), dz = z + 0.5 - l.getZ();
        return dx * dx + dz * dz;
    }

    public boolean loaded(World w) {
        return w != null && w.isChunkLoaded(x >> 4, z >> 4);
    }

    @Override
    public String toString() {
        return x + "," + y + "," + z;
    }
}
