package com.colonysmp.colony;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Keeps colonies off land a WorldGuard region protects (spawn, arenas, staff builds). WorldGuard is optional: it is
 * looked up at runtime, and without it (or if its API ever changes) nothing is blocked.
 * Regions listed in colonies.worldguard-allowed (e.g. "__global__" or a wilderness region) don't count.
 */
public final class WorldGuardCheck {

    private final Logger log;
    private final java.util.Set<String> allowed;
    private boolean broken;
    private Object container;
    private Method adaptWorld, getManager, getRegions, vector, getId;
    private Class<?> regionClass;
    private java.lang.reflect.Constructor<?> cuboidCtor;

    public WorldGuardCheck(Logger log, java.util.Collection<String> allowed) {
        this.log = log;
        java.util.Set<String> a = new java.util.HashSet<>();
        for (String s : allowed) a.add(s.toLowerCase(java.util.Locale.ROOT));
        a.add("__global__");
        this.allowed = a;
    }

    private boolean ready() {
        if (broken) return false;
        if (container != null) return true;
        Plugin wg = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (wg == null || !wg.isEnabled()) return false;
        try {
            ClassLoader cl = wg.getClass().getClassLoader();
            Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard", true, cl);
            Object instance = wgClass.getMethod("getInstance").invoke(null);
            Object platform = wgClass.getMethod("getPlatform").invoke(instance);
            Object regions = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", true, cl);
            Class<?> weWorld = Class.forName("com.sk89q.worldedit.world.World", true, cl);
            Class<?> bv3 = Class.forName("com.sk89q.worldedit.math.BlockVector3", true, cl);
            regionClass = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion", true, cl);
            Class<?> cuboidClass = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion", true, cl);
            adaptWorld = adapter.getMethod("adapt", World.class);
            getManager = regions.getClass().getMethod("get", weWorld);
            vector = bv3.getMethod("at", int.class, int.class, int.class);
            cuboidCtor = cuboidClass.getConstructor(String.class, bv3, bv3);
            getId = regionClass.getMethod("getId");
            container = regions;
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            broken = true;
            log.warning("WorldGuard is installed but its API couldn't be read, so colonies won't check its regions: " + e);
            return false;
        }
    }

    /** The id of a WorldGuard region overlapping this chunk, or null. */
    public String regionAt(String world, int cx, int cz) {
        World w = Bukkit.getWorld(world);
        if (w == null || !ready()) return null;
        try {
            Object manager = getManager.invoke(container, adaptWorld.invoke(null, w));
            if (manager == null) return null;
            if (getRegions == null) getRegions = manager.getClass().getMethod("getApplicableRegions", regionClass);
            Object min = vector.invoke(null, cx << 4, w.getMinHeight(), cz << 4);
            Object max = vector.invoke(null, (cx << 4) + 15, w.getMaxHeight() - 1, (cz << 4) + 15);
            Object probe = cuboidCtor.newInstance("colonysmp_probe", min, max);
            Object set = getRegions.invoke(manager, probe);
            for (Object r : (Iterable<?>) set) {
                String id = String.valueOf(getId.invoke(r));
                if (!allowed.contains(id.toLowerCase(java.util.Locale.ROOT))) return id;
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            log.warning("Couldn't read WorldGuard regions, so colonies won't check them any more: " + e);
            return null;
        }
    }
}
