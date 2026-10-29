package net.moddedrealms.testplayers;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Mannequin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

/**
 * Test players building small structures (a hut, a house, a tower...) block by block. They only build inside
 * build zones the admins set, never replace a block that's already there, never place a block where a player or
 * mob stands, and everything a test player placed can be undone. Builds are remembered in builds.yml.
 */
final class Builder {

    static final List<String> STRUCTURES = List.of("hut", "house", "tower", "wall", "campfire");
    static final List<String> MATERIALS = List.of("wood", "spruce", "stone", "brick", "cherry");

    /** A box (all heights) where test players may build: "world,x1,z1,x2,z2". */
    record Zone(String name, String world, int x1, int z1, int x2, int z2) {
        boolean contains(World w, int x, int z) {
            return w.getName().equals(world) && x >= x1 && x <= x2 && z >= z1 && z <= z2;
        }

        String spec() {
            return world + "," + x1 + "," + z1 + "," + x2 + "," + z2;
        }

        static Zone parse(String name, String spec) {
            String[] p = spec.split(",");
            if (p.length != 5) return null;
            try {
                int ax = Integer.parseInt(p[1].trim());
                int az = Integer.parseInt(p[2].trim());
                int bx = Integer.parseInt(p[3].trim());
                int bz = Integer.parseInt(p[4].trim());
                return new Zone(name, p[0].trim(), Math.min(ax, bx), Math.min(az, bz), Math.max(ax, bx), Math.max(az, bz));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private enum Role { FLOOR, WALL, PILLAR, ROOF, WINDOW, LIGHT, SEAT, FIRE }

    private record Cell(int x, int y, int z, Role role) { }

    /** A structure: cells relative to its corner, the door (if any) on the z = 0 side. */
    private record Template(int w, int d, List<Cell> cells) { }

    private record Palette(Material floor, Material wall, Material pillar, Material roof) {
        Material of(Role r) {
            return switch (r) {
                case FLOOR -> floor;
                case WALL -> wall;
                case PILLAR, SEAT -> pillar;
                case ROOF -> roof;
                case WINDOW -> Material.GLASS_PANE;
                case LIGHT -> Material.TORCH;
                case FIRE -> Material.CAMPFIRE;
            };
        }
    }

    private static final Map<String, Palette> PALETTES = Map.of(
            "wood", new Palette(Material.OAK_PLANKS, Material.OAK_PLANKS, Material.OAK_LOG, Material.OAK_SLAB),
            "spruce", new Palette(Material.SPRUCE_PLANKS, Material.SPRUCE_PLANKS, Material.SPRUCE_LOG, Material.SPRUCE_SLAB),
            "stone", new Palette(Material.STONE_BRICKS, Material.COBBLESTONE, Material.STONE_BRICKS, Material.STONE_BRICK_SLAB),
            "brick", new Palette(Material.BRICKS, Material.BRICKS, Material.STONE_BRICKS, Material.BRICK_SLAB),
            "cherry", new Palette(Material.CHERRY_PLANKS, Material.CHERRY_PLANKS, Material.CHERRY_LOG, Material.CHERRY_SLAB));

    private static final Map<String, Template> TEMPLATES = Map.of(
            "hut", house(5, 3),
            "house", house(7, 4),
            "tower", tower(),
            "wall", wall(),
            "campfire", campfire());

    /** A square house: floor, walls with a door gap at the front and windows, a flat slab roof, a torch inside. */
    private static Template house(int size, int wallHeight) {
        List<Cell> c = new ArrayList<>();
        int m = size - 1;
        int door = size / 2;
        for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) c.add(new Cell(x, 0, z, Role.FLOOR));
        for (int y = 1; y <= wallHeight; y++) {
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    boolean edgeX = x == 0 || x == m;
                    boolean edgeZ = z == 0 || z == m;
                    if (!edgeX && !edgeZ) continue;
                    if (edgeX && edgeZ) c.add(new Cell(x, y, z, Role.PILLAR));
                    else if (z == 0 && x == door && y <= 2) continue; // the doorway
                    else if (y == 2 && window(size, edgeX ? z : x)) c.add(new Cell(x, y, z, Role.WINDOW));
                    else c.add(new Cell(x, y, z, Role.WALL));
                }
            }
        }
        for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) c.add(new Cell(x, wallHeight + 1, z, Role.ROOF));
        c.add(new Cell(1, 1, size - 2, Role.LIGHT));
        return new Template(size, size, c);
    }

    private static boolean window(int size, int along) {
        return size <= 5 ? along == size / 2 : along == 2 || along == size - 3;
    }

    /** A solid 3x3 tower, eight blocks high, with a 5x5 lookout platform and torches on its corners. */
    private static Template tower() {
        List<Cell> c = new ArrayList<>();
        for (int y = 0; y < 8; y++) {
            for (int x = 1; x <= 3; x++) {
                for (int z = 1; z <= 3; z++) c.add(new Cell(x, y, z, (x != 2 && z != 2) ? Role.PILLAR : Role.WALL));
            }
        }
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) c.add(new Cell(x, 8, z, Role.FLOOR));
        for (int[] k : new int[][] {{0, 0}, {4, 0}, {0, 4}, {4, 4}}) c.add(new Cell(k[0], 9, k[1], Role.LIGHT));
        return new Template(5, 5, c);
    }

    /** A nine-block wall, three high, with posts and a slab cap. */
    private static Template wall() {
        List<Cell> c = new ArrayList<>();
        for (int y = 0; y < 3; y++) for (int x = 0; x < 9; x++) c.add(new Cell(x, y, 0, x % 4 == 0 ? Role.PILLAR : Role.WALL));
        for (int x = 0; x < 9; x++) c.add(new Cell(x, 3, 0, Role.ROOF));
        return new Template(9, 1, c);
    }

    /** A campfire with four log seats around it. */
    private static Template campfire() {
        List<Cell> c = new ArrayList<>();
        c.add(new Cell(2, 0, 2, Role.FIRE));
        for (int[] k : new int[][] {{0, 2}, {4, 2}, {2, 0}, {2, 4}}) c.add(new Cell(k[0], 0, k[1], Role.SEAT));
        return new Template(5, 5, c);
    }

    // ------------------------------------------------------------ state

    private record Placed(String world, int x, int y, int z, Material type) { }

    record Built(String owner, String structure, String world, int x, int y, int z) { }

    private static final class Job {
        final String structure;
        final List<Block> blocks = new ArrayList<>();
        final List<Material> types = new ArrayList<>();
        final Location center;
        Location stand; // just outside the doorway, where it builds from
        int minX, minZ, maxX, maxZ; // footprint, reserved while it's being built
        int next;
        double credit;
        long blockedSince;

        Job(String structure, Location center) {
            this.structure = structure;
            this.center = center;
        }
    }

    private final TestPlayersPlugin plugin;
    private final File file;
    private final Map<String, Job> jobs = new HashMap<>();             // test player id -> its build in progress
    private final Map<String, List<Placed>> placed = new HashMap<>();  // test player id -> every block it placed
    private final List<Built> builds = new ArrayList<>();
    private final Map<String, Zone> zones = new LinkedHashMap<>();
    private boolean dirty;

    Builder(TestPlayersPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "builds.yml");
    }

    // ------------------------------------------------------------ zones

    void loadZones() {
        zones.clear();
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("building.zones");
        if (s == null) return;
        for (String name : s.getKeys(false)) {
            Zone z = Zone.parse(name, s.getString(name, ""));
            if (z != null) zones.put(name.toLowerCase(Locale.ROOT), z);
            else plugin.getLogger().warning("building.zones." + name + " should look like \"world,x1,z1,x2,z2\"");
        }
    }

    Zone addZone(String name, World w, int x1, int z1, int x2, int z2) {
        if (!name.matches("^[A-Za-z0-9_-]{1,32}$")) throw new IllegalArgumentException("Zone names are letters, numbers, _ or -.");
        Zone z = new Zone(name, w.getName(), Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
        long area = (long) (z.x2 - z.x1 + 1) * (z.z2 - z.z1 + 1);
        if (area > 1_000_000) throw new IllegalArgumentException("That zone is " + area + " blocks across; keep it under 1,000,000 (e.g. 1000 x 1000).");
        zones.put(name.toLowerCase(Locale.ROOT), z);
        plugin.getConfig().set("building.zones." + name, z.spec());
        plugin.saveConfig();
        return z;
    }

    void removeZone(String name) {
        Zone z = zones.remove(name.toLowerCase(Locale.ROOT));
        if (z == null) throw new IllegalArgumentException("No build zone called " + name + ".");
        plugin.getConfig().set("building.zones." + z.name(), null);
        plugin.saveConfig();
    }

    List<Zone> zones() {
        return new ArrayList<>(zones.values());
    }

    Zone zoneAt(Location l) {
        for (Zone z : zones.values()) if (z.contains(l.getWorld(), l.getBlockX(), l.getBlockZ())) return z;
        return null;
    }

    /** A spot 6 blocks inside the nearest zone in l's world (to walk to before building), or null. */
    Location intoNearestZone(Location l) {
        Zone best = null;
        double bestD = Double.MAX_VALUE;
        for (Zone z : zones.values()) {
            if (!z.world().equals(l.getWorld().getName())) continue;
            double cx = Math.max(z.x1(), Math.min(l.getX(), z.x2()));
            double cz = Math.max(z.z1(), Math.min(l.getZ(), z.z2()));
            double d = Math.hypot(cx - l.getX(), cz - l.getZ());
            if (d < bestD) {
                bestD = d;
                best = z;
            }
        }
        if (best == null) return null;
        double x = Math.max(best.x1() + 6, Math.min(l.getX(), best.x2() - 6));
        double z = Math.max(best.z1() + 6, Math.min(l.getZ(), best.z2() - 6));
        return new Location(l.getWorld(), x + 0.5, l.getY(), z + 0.5);
    }

    /** "inside zone X", "zone X is 40 blocks away (north-east)", or null when there are no zones in this world. */
    String zoneHint(Location l) {
        Zone in = zoneAt(l);
        if (in != null) return "you are inside build zone \"" + in.name() + "\": you can build here";
        Zone best = null;
        double bestD = Double.MAX_VALUE;
        double bx = 0;
        double bz = 0;
        for (Zone z : zones.values()) {
            if (!z.world().equals(l.getWorld().getName())) continue;
            double cx = Math.max(z.x1(), Math.min(l.getX(), z.x2()));
            double cz = Math.max(z.z1(), Math.min(l.getZ(), z.z2()));
            double d = Math.hypot(cx - l.getX(), cz - l.getZ());
            if (d < bestD) {
                bestD = d;
                best = z;
                bx = cx;
                bz = cz;
            }
        }
        if (best == null) return null;
        float yaw = (float) Math.toDegrees(Math.atan2(-(bx - l.getX()), bz - l.getZ()));
        return "the nearest build zone (\"" + best.name() + "\") starts " + Math.round(bestD) + " blocks " + TestPlayer.compass(yaw)
                + " of you, at x=" + Math.round(bx) + " z=" + Math.round(bz) + ": you can only build inside it";
    }

    // ------------------------------------------------------------ starting a build

    boolean building(TestPlayer tp) {
        return jobs.containsKey(tp.id);
    }

    String progress(TestPlayer tp) {
        Job j = jobs.get(tp.id);
        return j == null ? null : "building a " + j.structure + " (" + j.next + "/" + j.blocks.size() + ")";
    }

    /**
     * Build now if it's inside a build zone; if it's outside one, walk into the nearest zone first and build
     * when it gets there. Used by the command, the website and the AI.
     */
    String startOrApproach(TestPlayer tp, String structure, String material) {
        String s = structure.toLowerCase(Locale.ROOT).trim();
        String mat = material == null || material.isBlank() ? "wood" : material.toLowerCase(Locale.ROOT).trim();
        if (!TEMPLATES.containsKey(s)) throw new IllegalArgumentException("Structures: " + String.join(", ", STRUCTURES) + ".");
        if (!PALETTES.containsKey(mat)) throw new IllegalArgumentException("Materials: " + String.join(", ", MATERIALS) + ".");
        Mannequin m = tp.entity();
        if (m == null) throw new IllegalStateException(tp.name + " isn't in the world.");
        Location into = zoneAt(m.getLocation()) == null ? intoNearestZone(m.getLocation()) : null;
        if (into == null) return start(tp, s, mat);
        plugin.players().goTo(tp, into);
        tp.pendingBuild = new String[] {s, mat};
        return tp.name + " is walking into the build zone (" + into.getBlockX() + " " + into.getBlockZ() + ") to build a " + mat + " " + s + " there.";
    }

    /** Find a clear, flat spot near the test player (inside a zone) and start building there. */
    String start(TestPlayer tp, String structure, String material) {
        String s = structure.toLowerCase(Locale.ROOT).trim();
        String mat = material == null || material.isBlank() ? "wood" : material.toLowerCase(Locale.ROOT).trim();
        Template t = TEMPLATES.get(s);
        Palette p = PALETTES.get(mat);
        if (t == null) throw new IllegalArgumentException("Structures: " + String.join(", ", STRUCTURES) + ".");
        if (p == null) throw new IllegalArgumentException("Materials: " + String.join(", ", MATERIALS) + ".");
        Mannequin m = tp.entity();
        if (m == null) throw new IllegalStateException(tp.name + " isn't in the world.");
        if (zones.isEmpty()) throw new IllegalArgumentException("There are no build zones yet. An admin adds one with /testplayer zone add <name> <radius>.");
        int max = plugin.getConfig().getInt("building.max-builds-per-player", 6);
        long mine = builds.stream().filter(b -> b.owner().equals(tp.id)).count();
        if (mine >= max) throw new IllegalArgumentException(tp.name + " has built " + mine + " things already (building.max-builds-per-player).");

        Location l = m.getLocation();
        Job job = site(l, t, p, s);
        if (job == null) {
            String where = zoneAt(l) == null ? "it isn't inside a build zone" : "there's no clear, flat enough spot near it (move it to flatter ground)";
            throw new IllegalArgumentException(tp.name + " can't build a " + s + " here: " + where + ".");
        }
        plugin.players().idle(tp); // stops anything else, including an older build
        jobs.put(tp.id, job);
        tp.mode = TestPlayer.Mode.BUILD;
        tp.bestDist = Double.MAX_VALUE;
        tp.ticksSinceBetter = 0;
        tp.buildStatus = "building a " + s + " (0/" + job.blocks.size() + ")";
        tp.stuckTicks = 0;
        m.getEquipment().setItemInMainHand(new ItemStack(p.wall()));
        return "Building a " + mat + " " + s + " at " + job.center.getBlockX() + " " + job.center.getBlockY() + " " + job.center.getBlockZ()
                + " (" + job.blocks.size() + " blocks).";
    }

    /** Spiral out from the test player looking for a spot where the whole structure fits in a zone, on flat ground, in air. */
    private Job site(Location l, Template t, Palette p, String structure) {
        // First a spot at about its own height (one it can walk to), then anywhere.
        Job near = site(l, t, p, structure, 6);
        return near != null ? near : site(l, t, p, structure, Integer.MAX_VALUE);
    }

    private Job site(Location l, Template t, Palette p, String structure, int maxRise) {
        World w = l.getWorld();
        for (int r = 4; r <= 40; r += 3) {
            for (int i = 0; i < 16; i++) {
                double a = i * Math.PI / 8;
                int cx = (int) Math.round(l.getX() + Math.cos(a) * r);
                int cz = (int) Math.round(l.getZ() + Math.sin(a) * r);
                // Door toward the test player: rotation 0..3 = door facing north, east, south, west.
                double dx = l.getX() - cx;
                double dz = l.getZ() - cz;
                int rot = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? 1 : 3) : (dz > 0 ? 2 : 0);
                Job j = fit(w, t, p, structure, rot, cx, cz);
                if (j != null && Math.abs(j.center.getY() - l.getY()) <= maxRise) return j;
            }
        }
        return null;
    }

    private Job fit(World w, Template t, Palette p, String structure, int rot, int cx, int cz) {
        boolean swap = rot % 2 == 1;
        int fw = swap ? t.d() : t.w();
        int fd = swap ? t.w() : t.d();
        int minX = cx - fw / 2;
        int minZ = cz - fd / 2;
        // Another test player is already building here (or right next to it).
        for (Job other : jobs.values()) {
            if (other.center.getWorld() == w && minX - 2 <= other.maxX && minX + fw + 1 >= other.minX && minZ - 2 <= other.maxZ && minZ + fd + 1 >= other.minZ) return null;
        }
        int[][] ground = new int[fw][fd];
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int x = 0; x < fw; x++) {
            for (int z = 0; z < fd; z++) {
                int bx = minX + x;
                int bz = minZ + z;
                if (zoneAt(new Location(w, bx, 0, bz)) == null) return null;
                if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return null;
                Block top = w.getHighestBlockAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (top.isLiquid() || unsuitableGround(top.getType())) return null;
                ground[x][z] = top.getY();
                lo = Math.min(lo, top.getY());
                hi = Math.max(hi, top.getY());
            }
        }
        if (hi - lo > 2) return null; // up to two blocks of slope, filled in with a foundation
        int baseY = hi + 1;
        Job job = new Job(structure, new Location(w, cx + 0.5, baseY, cz + 0.5));
        job.minX = minX;
        job.minZ = minZ;
        job.maxX = minX + fw - 1;
        job.maxZ = minZ + fd - 1;
        // Stand two blocks out from the door side (rotation 0..3 = north, east, south, west), out of the way.
        int[][] out = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        int sx = cx + out[rot][0] * (fw / 2 + 2);
        int sz = cz + out[rot][1] * (fd / 2 + 2);
        job.stand = w.isChunkLoaded(sx >> 4, sz >> 4)
                ? new Location(w, sx + 0.5, w.getHighestBlockYAt(sx, sz, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1, sz + 0.5)
                : job.center.clone();
        // Level the ground first where it's lower.
        for (int x = 0; x < fw; x++) {
            for (int z = 0; z < fd; z++) {
                for (int y = ground[x][z] + 1; y < baseY; y++) if (!add(job, w.getBlockAt(minX + x, y, minZ + z), p.floor())) return null;
            }
        }
        List<Cell> cells = new ArrayList<>(t.cells());
        cells.sort((a, b) -> Integer.compare(a.y(), b.y())); // bottom up, so nothing floats
        for (Cell c : cells) {
            int lx;
            int lz;
            switch (rot) {
                case 1 -> { lx = t.d() - 1 - c.z(); lz = c.x(); }
                case 2 -> { lx = t.w() - 1 - c.x(); lz = t.d() - 1 - c.z(); }
                case 3 -> { lx = c.z(); lz = t.w() - 1 - c.x(); }
                default -> { lx = c.x(); lz = c.z(); }
            }
            if (!add(job, w.getBlockAt(minX + lx, baseY + c.y(), minZ + lz), p.of(c.role()))) return null;
        }
        return job;
    }

    private static boolean unsuitableGround(Material m) {
        String n = m.name();
        return n.endsWith("_LEAVES") || n.endsWith("_LOG") || n.equals("CACTUS") || n.equals("BAMBOO");
    }

    /** Queue a block if its spot is free (air, grass, flowers...); false if something is already there. */
    private static boolean add(Job job, Block b, Material type) {
        if (!(b.getType().isAir() || (b.isReplaceable() && !b.isLiquid()))) return false;
        job.blocks.add(b);
        job.types.add(type);
        return true;
    }

    // ------------------------------------------------------------ building, a few blocks a second

    /** The next block to place, or null if it isn't building (anymore). */
    Block next(TestPlayer tp) {
        Job j = jobs.get(tp.id);
        if (j == null) return null;
        if (j.next >= j.blocks.size()) {
            finish(tp, j);
            return null;
        }
        return j.blocks.get(j.next);
    }

    /** Where to stand while building, or null if it isn't building. */
    Location stand(TestPlayer tp) {
        Job j = jobs.get(tp.id);
        return j == null ? null : j.stand;
    }

    /** It's standing close to the next block: place it when the build speed allows. */
    void work(TestPlayer tp, Mannequin m) {
        Job j = jobs.get(tp.id);
        if (j == null || j.next >= j.blocks.size()) return;
        j.credit = Math.min(j.credit + plugin.getConfig().getDouble("building.blocks-per-second", 3) / 20.0, 2);
        if (j.credit < 1) return;
        Block b = j.blocks.get(j.next);
        Material type = j.types.get(j.next);
        if (!(b.getType().isAir() || (b.isReplaceable() && !b.isLiquid()))) {
            j.next++; // something got there first (a player built there): leave it alone
            return;
        }
        // Never put a block where someone (a player, a mob, a test player) is standing.
        BoundingBox box = BoundingBox.of(b);
        if (!b.getWorld().getNearbyEntities(box).isEmpty()) {
            if (j.blockedSince == 0) j.blockedSince = System.currentTimeMillis();
            if (System.currentTimeMillis() - j.blockedSince > 10_000) {
                j.next++;
                j.blockedSince = 0;
            }
            return;
        }
        j.blockedSince = 0;
        j.credit -= 1;
        b.setType(type, true);
        j.next++;
        tp.buildStatus = "building a " + j.structure + " (" + j.next + "/" + j.blocks.size() + ")";
        placed.computeIfAbsent(tp.id, k -> new ArrayList<>()).add(new Placed(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), type));
        dirty = true;
        ItemStack hand = m.getEquipment().getItemInMainHand();
        if (hand.getType() != type) m.getEquipment().setItemInMainHand(new ItemStack(type));
        m.swingMainHand();
        b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), b.getBlockData().getSoundGroup().getPlaceSound(), 1f, 0.9f);
    }

    private void finish(TestPlayer tp, Job j) {
        jobs.remove(tp.id);
        Location c = j.center;
        builds.add(new Built(tp.id, j.structure, c.getWorld().getName(), c.getBlockX(), c.getBlockY(), c.getBlockZ()));
        dirty = true;
        tp.lastResult = "built a " + j.structure + " at " + c.getBlockX() + " " + c.getBlockY() + " " + c.getBlockZ();
        tp.lastBuiltAt = System.currentTimeMillis();
        tp.mode = TestPlayer.Mode.IDLE;
        Mannequin m = tp.entity();
        if (m != null) m.getEquipment().setItemInMainHand(null);
        save();
    }

    /** Stop building (another order came in): keeps what's built so far. */
    void cancel(TestPlayer tp) {
        Job j = jobs.remove(tp.id);
        if (j == null) return;
        tp.lastResult = "stopped building a " + j.structure + " (" + j.next + "/" + j.blocks.size() + " placed)";
        Mannequin m = tp.entity();
        if (m != null) m.getEquipment().setItemInMainHand(null);
    }

    /** Take away every block the test player placed that's still there unchanged. Returns how many. */
    int undo(TestPlayer tp) {
        cancel(tp);
        List<Placed> list = placed.remove(tp.id);
        builds.removeIf(b -> b.owner().equals(tp.id));
        dirty = true;
        if (list == null) return 0;
        int n = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            Placed p = list.get(i);
            World w = Bukkit.getWorld(p.world());
            if (w == null) continue;
            Block b = w.getBlockAt(p.x(), p.y(), p.z());
            if (b.getType() == p.type()) {
                b.setType(Material.AIR, false);
                n++;
            }
        }
        save();
        return n;
    }

    /** Builds within radius of l, nearest first, for the AI ("Load3's hut, 12m"). */
    List<String> near(Location l, double radius) {
        List<String> out = new ArrayList<>();
        builds.stream()
                .filter(b -> b.world().equals(l.getWorld().getName()))
                .sorted((a, b) -> Double.compare(Math.hypot(a.x() - l.getX(), a.z() - l.getZ()), Math.hypot(b.x() - l.getX(), b.z() - l.getZ())))
                .forEach(b -> {
                    double d = Math.hypot(b.x() - l.getX(), b.z() - l.getZ());
                    if (d <= radius && out.size() < 8) {
                        TestPlayer owner = plugin.players().get(b.owner());
                        out.add((owner != null ? owner.name : b.owner()) + "'s " + b.structure() + " (" + Math.round(d) + "m)");
                    }
                });
        return out;
    }

    int buildCount() {
        return builds.size();
    }

    int buildCount(TestPlayer tp) {
        return (int) builds.stream().filter(b -> b.owner().equals(tp.id)).count();
    }

    /** A removed test player: forget its builds (the blocks stay unless it was undone first). */
    void forget(TestPlayer tp) {
        cancel(tp);
        placed.remove(tp.id);
        builds.removeIf(b -> b.owner().equals(tp.id));
        dirty = true;
    }

    // ------------------------------------------------------------ builds.yml

    void save() {
        if (!dirty) return;
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, List<Placed>> e : placed.entrySet()) {
            List<String> rows = new ArrayList<>();
            for (Placed p : e.getValue()) rows.add(p.world() + "," + p.x() + "," + p.y() + "," + p.z() + "," + p.type().name());
            y.set("placed." + e.getKey(), rows);
        }
        List<String> rows = new ArrayList<>();
        for (Built b : builds) rows.add(b.owner() + "," + b.structure() + "," + b.world() + "," + b.x() + "," + b.y() + "," + b.z());
        y.set("builds", rows);
        try {
            y.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save builds.yml", e);
        }
    }

    void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("placed");
        if (s != null) {
            for (String id : s.getKeys(false)) {
                List<Placed> list = new ArrayList<>();
                for (String row : s.getStringList(id)) {
                    String[] p = row.split(",");
                    Material m = p.length == 5 ? Material.matchMaterial(p[4]) : null;
                    if (m == null) continue;
                    try {
                        list.add(new Placed(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), m));
                    } catch (NumberFormatException ignored) {
                        // skip a damaged line
                    }
                }
                placed.put(id, list);
            }
        }
        for (String row : y.getStringList("builds")) {
            String[] p = row.split(",");
            if (p.length != 6) continue;
            try {
                builds.add(new Built(p[0], p[1], p[2], Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5])));
            } catch (NumberFormatException ignored) {
                // skip a damaged line
            }
        }
    }
}
