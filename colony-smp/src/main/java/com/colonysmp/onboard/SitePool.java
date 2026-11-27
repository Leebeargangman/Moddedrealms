package com.colonysmp.onboard;

import com.colonysmp.ColonySMP;
import com.colonysmp.model.Colony;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Keeps a few free places ready for starter camps, so "Found a Colony" doesn't make anyone wait: flat, dry ground
 * with trees close by, out in the wild (a ring around spawn), on nobody's land, with no builds on it. A background
 * search loads chunks asynchronously and checks one candidate at a time. Sites are checked again when taken.
 */
public final class SitePool {

    /** A camp site: the ground block under the Town Hall core. */
    public record Site(String world, int x, int y, int z) {
        Location ground(World w) {
            return new Location(w, x, y, z);
        }
    }

    /** The camp's footprint around the core (blocks), cleared and levelled when the camp is placed. */
    static final int MIN_X = -11, MAX_X = 11, MIN_Z = -5, MAX_Z = 13;

    private static final Set<Material> GROUND = Set.of(Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.PODZOL, Material.ROOTED_DIRT,
            Material.MYCELIUM, Material.MOSS_BLOCK, Material.SNOW_BLOCK, Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.CLAY, Material.MUD,
            Material.STONE, Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.TUFF, Material.CALCITE, Material.PACKED_MUD);

    private final ColonySMP plugin;
    private final File file;
    private final List<Site> ready = new ArrayList<>();
    private boolean searching;
    private long searchStarted;
    private int attempts, found;
    private String lastReject = "";

    public SitePool(ColonySMP plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "camp-sites.yml");
        load();
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("onboarding.sites");
        return s != null ? s : new YamlConfiguration();
    }

    public int size() {
        return ready.size();
    }

    public String status() {
        return ready.size() + " ready, " + found + " found in " + attempts + " tries" + (lastReject.isEmpty() ? "" : " (last miss: " + lastReject + ")");
    }

    // ───────────── searching ─────────────

    /** Every couple of seconds: look at one more candidate while the pool is short. */
    public void tick() {
        if (!plugin.settings().feature("starter-camp")) return;
        if (searching) {
            // a chunk load that never answered must not stop the search for good
            if (System.currentTimeMillis() - searchStarted > 60_000) searching = false;
            return;
        }
        if (ready.size() >= Math.max(1, cfg().getInt("pool", 3))) return;
        search(null);
    }

    /** Checks one random candidate; calls back with a site (also added to the pool) or null. */
    public void search(Consumer<Site> done) {
        World w = world();
        if (w == null) {
            lastReject = "no world " + cfg().getString("world", "world");
            if (done != null) done.accept(null);
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int min = Math.max(0, cfg().getInt("min-distance", 450)), max = Math.max(min + 50, cfg().getInt("max-distance", 1400));
        Location c = centre(w);
        double a = r.nextDouble() * Math.PI * 2, d = min + r.nextDouble() * (max - min);
        // the middle of a chunk, so the quick look below needs only that one chunk
        int x = ((int) Math.floor(c.getX() + Math.cos(a) * d) & ~15) + 8, z = ((int) Math.floor(c.getZ() + Math.sin(a) * d) & ~15) + 8;
        attempts++;
        String claim = claimProblem(w.getName(), x, z);
        if (claim != null || tooCloseToPool(w.getName(), x, z)) {
            lastReject = claim != null ? claim : "too close to another ready site";
            if (done != null) done.accept(null);
            return;
        }
        searching = true;
        searchStarted = System.currentTimeMillis();
        // a quick look at one chunk first (most places fail on water or hills), then the 5x5 chunks around it
        w.getChunkAtAsync(x >> 4, z >> 4, true).whenComplete((ch, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            String quick = err != null ? "chunk load failed" : quickLook(w, x, z);
            if (quick != null) {
                searching = false;
                lastReject = quick;
                if (done != null) done.accept(null);
                return;
            }
            searchStarted = System.currentTimeMillis();
            searching = true;
            evaluateLoaded(w, x, z, done);
        }));
    }

    /** Water or hills at nine points around the middle of a loaded chunk, or null if it's worth a proper look. */
    String quickLook(World w, int x, int z) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int dx = -6; dx <= 6; dx += 6) {
            for (int dz = -6; dz <= 6; dz += 6) {
                int g = ground(w, x + dx, z + dz);
                if (g == Integer.MIN_VALUE) return "water or no solid ground";
                lo = Math.min(lo, g);
                hi = Math.max(hi, g);
            }
        }
        if (hi - lo > cfg().getInt("max-height-difference", 4)) return "too hilly (" + (hi - lo) + " blocks)";
        if (lo < w.getSeaLevel() - 1) return "too low (below the sea)";
        return null;
    }

    private void evaluateLoaded(World w, int x, int z, Consumer<Site> done) {
        load(w, x, z, () -> {
            searching = false;
            Site s = evaluate(w, x, z);
            if (s != null) {
                found++;
                ready.add(s);
                save();
                if (plugin.settings().debug("onboarding")) plugin.getLogger().info("[camp] site ready at " + s);
            }
            if (done != null) done.accept(s);
        });
    }

    /** Loads (and if needed generates) the chunks a camp and its woods cover, then runs on the main thread. */
    void load(World w, int x, int z, Runnable then) {
        int r = 2;
        List<CompletableFuture<Chunk>> fs = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) fs.add(w.getChunkAtAsync((x >> 4) + dx, (z >> 4) + dz, true));
        CompletableFuture.allOf(fs.toArray(new CompletableFuture[0])).whenComplete((v, err) -> {
            Runnable run = () -> {
                // even when a load failed, go on: the check then fails (or loads what it needs) and the caller hears back
                if (err != null) lastReject = "chunk load failed: " + err.getMessage();
                then.run();
            };
            if (Bukkit.isPrimaryThread()) run.run();
            else Bukkit.getScheduler().runTask(plugin, run);
        });
    }

    World world() {
        return Bukkit.getWorld(cfg().getString("world", "world"));
    }

    private Location centre(World w) {
        String s = cfg().getString("centre", "");
        if (s != null && !s.isBlank()) {
            String[] f = s.split("[ ,]+");
            try {
                return new Location(w, Double.parseDouble(f[0]), 64, Double.parseDouble(f[f.length - 1]));
            } catch (RuntimeException ignored) {
                // fall back to the world spawn
            }
        }
        return w.getSpawnLocation();
    }

    private boolean tooCloseToPool(String world, int x, int z) {
        int gap = cfg().getInt("spacing", 200);
        for (Site s : ready) if (s.world().equals(world) && (long) (s.x() - x) * (s.x() - x) + (long) (s.z() - z) * (s.z() - z) < (long) gap * gap) return true;
        return false;
    }

    /** Why a colony can't be founded with its core here, or null (claims, no-colony zones, gaps between colonies). */
    String claimProblem(String world, int x, int z) {
        Colony probe = new Colony("__camp_probe__");
        probe.world = world;
        int r = plugin.settings().startRadius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                String err = plugin.territory().checkChunk(probe, world, (x >> 4) + dx, (z >> 4) + dz, true);
                if (err != null) return err;
            }
        }
        return null;
    }

    // ───────────── judging a place ─────────────

    /** The ground height of a column (ignoring trees and snow), or Integer.MIN_VALUE for water, lava or no ground. */
    static int ground(World w, int x, int z) {
        Block b = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        for (int i = 0; i < 24 && (Tag.LOGS.isTagged(b.getType()) || b.getType() == Material.SNOW || b.getType() == Material.POWDER_SNOW
                || Tag.LEAVES.isTagged(b.getType()) || b.getType() == Material.BAMBOO || b.getType() == Material.MUSHROOM_STEM
                || b.getType() == Material.BROWN_MUSHROOM_BLOCK || b.getType() == Material.RED_MUSHROOM_BLOCK || b.getType() == Material.PUMPKIN
                || b.getType() == Material.MELON || b.getType() == Material.MOSS_CARPET || b.getType() == Material.CACTUS); i++) {
            b = b.getRelative(0, -1, 0);
        }
        Material m = b.getType();
        if (m == Material.WATER || m == Material.LAVA || m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE || m == Material.SEAGRASS
                || m == Material.KELP || m == Material.KELP_PLANT || m == Material.TALL_SEAGRASS || b.isLiquid()) {
            return Integer.MIN_VALUE;
        }
        if (!GROUND.contains(m)) return Integer.MIN_VALUE;
        // water standing over it (a shallow pond) is no good either
        if (b.getRelative(0, 1, 0).isLiquid()) return Integer.MIN_VALUE;
        return b.getY();
    }

    /** Blocks that mean someone built here (or a village or ruin stands here): never level them. */
    static boolean artificial(Material m) {
        String n = m.name();
        if (Tag.PLANKS.isTagged(m) || Tag.WOOL.isTagged(m) || Tag.WOOL_CARPETS.isTagged(m) || Tag.BEDS.isTagged(m) || Tag.DOORS.isTagged(m)
                || Tag.FENCES.isTagged(m) || Tag.FENCE_GATES.isTagged(m) || Tag.STAIRS.isTagged(m) || Tag.SLABS.isTagged(m) || Tag.WALLS.isTagged(m)
                || Tag.ALL_SIGNS.isTagged(m) || Tag.BANNERS.isTagged(m) || Tag.RAILS.isTagged(m) || Tag.TRAPDOORS.isTagged(m)) {
            return true;
        }
        return n.contains("GLASS") || n.contains("BRICK") || n.contains("CONCRETE") || n.contains("TORCH") || n.contains("LANTERN") || n.contains("CHEST")
                || n.contains("SHULKER") || n.contains("FURNACE") || n.contains("GLAZED") || n.equals("COBBLESTONE") || n.equals("BARREL")
                || n.equals("CRAFTING_TABLE") || n.equals("BOOKSHELF") || n.equals("HAY_BLOCK") || n.equals("FARMLAND") || n.equals("DIRT_PATH")
                || n.equals("SMOOTH_STONE") || n.equals("BELL") || n.equals("ANVIL") || n.equals("LECTERN") || n.equals("COMPOSTER")
                || n.equals("LODESTONE") || n.equals("CAMPFIRE") || n.equals("LADDER") || n.equals("SCAFFOLDING") || n.equals("IRON_BARS")
                || n.equals("CHAIN") || n.equals("BEACON") || n.contains("STRIPPED") || n.equals("SPAWNER") || n.equals("TNT");
    }

    /**
     * Whether a camp fits with its core above (x, z): dry, flat ground, no builds on it or near it, and woods close
     * by for the woodcutter. Returns the site (ground height = the most common one) or null, with lastReject set.
     */
    Site evaluate(World w, int x, int z) {
        String claim = claimProblem(w.getName(), x, z);
        if (claim != null) return reject(claim);
        int[] counts = new int[w.getMaxHeight() - w.getMinHeight() + 1];
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        List<int[]> cols = new ArrayList<>();
        for (int dx = MIN_X; dx <= MAX_X; dx++) {
            for (int dz = MIN_Z; dz <= MAX_Z; dz++) {
                int g = ground(w, x + dx, z + dz);
                if (g == Integer.MIN_VALUE) return reject("water or no solid ground");
                lo = Math.min(lo, g);
                hi = Math.max(hi, g);
                counts[g - w.getMinHeight()]++;
                cols.add(new int[]{x + dx, z + dz, g});
            }
        }
        int maxStep = cfg().getInt("max-height-difference", 4);
        if (hi - lo > maxStep) return reject("too hilly (" + (hi - lo) + " blocks)");
        int base = lo;
        for (int y = lo; y <= hi; y++) if (counts[y - w.getMinHeight()] > counts[base - w.getMinHeight()]) base = y;
        if (base < w.getSeaLevel() - 1) return reject("too low (below the sea)");
        // anything built here or nearby?
        for (int[] c : cols) {
            for (int y = c[2] - 2; y <= c[2] + 9; y++) {
                if (artificial(w.getBlockAt(c[0], y, c[1]).getType())) return reject("someone built here");
            }
        }
        int near = cfg().getInt("clear-radius", 24);
        for (int dx = -near; dx <= near; dx += 2) {
            for (int dz = -near; dz <= near; dz += 2) {
                Block top = w.getHighestBlockAt(x + dx, z + dz, HeightMap.WORLD_SURFACE);
                if (artificial(top.getType()) || artificial(top.getRelative(0, -1, 0).getType())) return reject("a build or village nearby");
            }
        }
        // woods: tree tops around the camp, outside the footprint
        int canopy = 0, reach = 26;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (dx >= MIN_X - 1 && dx <= MAX_X + 1 && dz >= MIN_Z - 1 && dz <= MAX_Z + 1) continue;
                Material top = w.getHighestBlockAt(x + dx, z + dz, HeightMap.MOTION_BLOCKING).getType();
                if (Tag.LEAVES.isTagged(top)) canopy++;
            }
        }
        int need = cfg().getInt("min-tree-cover", 80);
        if (canopy < need) return reject("too few trees (" + canopy + "/" + need + ")");
        return new Site(w.getName(), x, base, z);
    }

    private Site reject(String why) {
        lastReject = why;
        return null;
    }

    // ───────────── taking a site ─────────────

    /**
     * The best ready site that still passes every check (loaded and checked again), or a fresh search when none is
     * ready. Calls back on the main thread with a site or null.
     */
    public void take(Consumer<Site> done) {
        if (ready.isEmpty()) {
            searchRepeatedly(cfg().getInt("search-tries-when-empty", 12), done);
            return;
        }
        Site s = ready.remove(0);
        save();
        World w = Bukkit.getWorld(s.world());
        if (w == null) {
            take(done);
            return;
        }
        load(w, s.x(), s.z(), () -> {
            Site again = evaluate(w, s.x(), s.z());
            if (again != null) done.accept(again);
            else take(done);
        });
    }

    private void searchRepeatedly(int left, Consumer<Site> done) {
        if (left <= 0) {
            done.accept(null);
            return;
        }
        search(s -> {
            if (s != null) {
                ready.remove(s);
                save();
                done.accept(s);
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> searchRepeatedly(left - 1, done));
            }
        });
    }

    // ───────────── file ─────────────

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String line : y.getStringList("sites")) {
            String[] f = line.split(" ");
            try {
                ready.add(new Site(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3])));
            } catch (RuntimeException ignored) {
                // a bad line: skip it
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> lines = new ArrayList<>();
        for (Site s : ready) lines.add(s.world() + " " + s.x() + " " + s.y() + " " + s.z());
        y.set("sites", lines);
        try {
            com.colonysmp.util.SafeFiles.write(file, y.saveToString());
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save " + file.getName() + ": " + e.getMessage());
        }
    }

    static String describe(Site s) {
        return String.format(Locale.ROOT, "%s %d %d %d", s.world(), s.x(), s.y(), s.z());
    }
}
