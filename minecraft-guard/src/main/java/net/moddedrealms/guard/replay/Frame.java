package net.moddedrealms.guard.replay;

import org.bukkit.Material;

/** One tick of one entity. In a saved clip, {@code tick} is relative to the clip start. */
public final class Frame {
    public static final int SNEAK = 1, SPRINT = 2, SWIM = 4, GLIDE = 8, SWING = 16, HURT = 32, SLEEP = 64, OFFHAND_SWING = 128;

    public long tick;
    public String world;
    public double x, y, z;
    public float yaw, pitch;
    public int flags;
    public Material main, off, head, chest, legs, feet;

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }
}
