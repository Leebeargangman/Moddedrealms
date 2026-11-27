package com.colonysmp.util;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Particles and sounds. Sounds use string keys so they survive Sound registry changes between versions. */
public final class Fx {

    private Fx() {}

    public static void sound(Location at, String key, float volume, float pitch) {
        World w = at.getWorld();
        if (w != null) w.playSound(at, key, SoundCategory.NEUTRAL, volume, pitch);
    }

    public static void sound(Player p, String key, float volume, float pitch) {
        p.playSound(p.getLocation(), key, SoundCategory.MASTER, volume, pitch);
    }

    public static void particle(Location at, Particle type, int count, double spread) {
        World w = at.getWorld();
        if (w != null) w.spawnParticle(type, at, count, spread, spread, spread, 0);
    }

    public static void dust(Player p, double x, double y, double z, Color color, float size) {
        p.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, new Particle.DustOptions(color, size));
    }

    public static void dust(Location at, Color color, float size, int count, double spread) {
        World w = at.getWorld();
        if (w != null) w.spawnParticle(Particle.DUST, at, count, spread, spread, spread, 0, new Particle.DustOptions(color, size));
    }

    /** Dotted line of dust visible to one player. */
    public static void line(Player p, double x1, double y1, double z1, double x2, double y2, double z2, Color c, double step, float size) {
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int n = Math.max(1, (int) Math.ceil(len / step));
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            dust(p, x1 + dx * t, y1 + dy * t, z1 + dz * t, c, size);
        }
    }

    /** Outline of a box (block coordinates, inclusive) visible to one player. */
    public static void box(Player p, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Color c, double step, float size, boolean verticals) {
        double x1 = minX, y1 = minY, z1 = minZ, x2 = maxX + 1, y2 = maxY + 1, z2 = maxZ + 1;
        line(p, x1, y1, z1, x2, y1, z1, c, step, size);
        line(p, x1, y1, z2, x2, y1, z2, c, step, size);
        line(p, x1, y1, z1, x1, y1, z2, c, step, size);
        line(p, x2, y1, z1, x2, y1, z2, c, step, size);
        if (verticals) {
            line(p, x1, y2, z1, x2, y2, z1, c, step, size);
            line(p, x1, y2, z2, x2, y2, z2, c, step, size);
            line(p, x1, y2, z1, x1, y2, z2, c, step, size);
            line(p, x2, y2, z1, x2, y2, z2, c, step, size);
            line(p, x1, y1, z1, x1, y2, z1, c, step, size);
            line(p, x2, y1, z1, x2, y2, z1, c, step, size);
            line(p, x1, y1, z2, x1, y2, z2, c, step, size);
            line(p, x2, y1, z2, x2, y2, z2, c, step, size);
        }
    }
}
