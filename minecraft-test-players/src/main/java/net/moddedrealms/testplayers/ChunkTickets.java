package net.moddedrealms.testplayers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Keeps the chunks around each test player loaded and ticking, like a real player's. Chunks are loaded (and
 * generated) in the background first, then pinned with a plugin chunk ticket. Test players standing close
 * together share chunks: a chunk is let go when no test player needs it any more.
 */
final class ChunkTickets {

    private final Plugin plugin;
    private final Map<String, Map<Long, Integer>> wanted = new HashMap<>(); // world -> chunk -> test players needing it
    private final Map<String, Set<Long>> ticketed = new HashMap<>();        // world -> chunks we hold a ticket on

    ChunkTickets(Plugin plugin) {
        this.plugin = plugin;
    }

    static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    /** The test player is at loc: hold the chunks within radius of it, let go of the ones it left behind. */
    void update(TestPlayer tp, Location loc, int radius) {
        World world = loc.getWorld();
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        long k = key(cx, cz);
        if (k == tp.chunkKey && radius == tp.chunkRadius && world.getName().equals(tp.chunkWorld)) return;
        // Add the new square before dropping the old one, so shared chunks never unload in between.
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) acquire(world, x, z);
        }
        release(tp);
        tp.chunkKey = k;
        tp.chunkWorld = world.getName();
        tp.chunkRadius = radius;
    }

    /** Let go of everything the test player held. */
    void release(TestPlayer tp) {
        if (tp.chunkWorld == null) return;
        int radius = tp.chunkRadius;
        World world = Bukkit.getWorld(tp.chunkWorld);
        int cx = (int) (tp.chunkKey >> 32);
        int cz = (int) tp.chunkKey;
        tp.chunkWorld = null;
        tp.chunkKey = Long.MIN_VALUE;
        if (world == null) return;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) drop(world, x, z);
        }
    }

    private void acquire(World world, int x, int z) {
        Map<Long, Integer> counts = wanted.computeIfAbsent(world.getName(), w -> new HashMap<>());
        long k = key(x, z);
        if (counts.merge(k, 1, Integer::sum) > 1) return;
        world.getChunkAtAsync(x, z, true).thenAccept((Chunk c) -> {
            // Still needed once it's loaded? (The test player may have walked on already.)
            if (counts.getOrDefault(k, 0) > 0 && ticketed.computeIfAbsent(world.getName(), w -> new HashSet<>()).add(k)) {
                world.addPluginChunkTicket(x, z, plugin);
            }
        });
    }

    private void drop(World world, int x, int z) {
        Map<Long, Integer> counts = wanted.get(world.getName());
        if (counts == null) return;
        long k = key(x, z);
        Integer n = counts.get(k);
        if (n == null) return;
        if (n > 1) {
            counts.put(k, n - 1);
            return;
        }
        counts.remove(k);
        Set<Long> held = ticketed.get(world.getName());
        if (held != null && held.remove(k)) world.removePluginChunkTicket(x, z, plugin);
    }

    /** Chunks currently pinned for test players. */
    int held() {
        return ticketed.values().stream().mapToInt(Set::size).sum();
    }

    void clear() {
        for (World w : Bukkit.getWorlds()) w.removePluginChunkTickets(plugin);
        wanted.clear();
        ticketed.clear();
    }
}
