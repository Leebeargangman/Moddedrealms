package com.colonysmp.world;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.civ.Settlement;
import com.colonysmp.civ.SettlementType;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.block.data.type.Slab;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * Puts a settlement's buildings in the world, and takes care where. A building only ever goes on natural
 * ground: if anything in its footprint looks made by hand, or the ground is too steep or wet, that building is
 * simply left out. Everything placed is written down as a box on the settlement, which is how its buildings are
 * recognised and protected afterwards. Buildings grow with the settlement's level and crumble when it falls.
 */
public final class SettlementBuilder {

    private enum Kind { BUILD, GROW, RUIN }

    /** Offsets from the centre where buildings may stand: an inner ring, then an outer one. */
    private static final int[][] SLOTS = {{10, 0}, {-10, 0}, {0, 10}, {0, -10}, {8, 8}, {-8, 8}, {8, -8}, {-8, -8},
            {17, 5}, {-17, 5}, {17, -5}, {-17, -5}, {5, 17}, {-5, 17}, {5, -17}, {-5, -17}, {14, 13}, {-14, 13}, {14, -13}, {-14, -13}};

    private final ColonySMP plugin;
    private final CivManager civs;
    private final Map<String, Kind> queue = new LinkedHashMap<>();
    private final java.util.Set<String> loading = new java.util.HashSet<>();

    SettlementBuilder(ColonySMP plugin, CivManager civs) {
        this.plugin = plugin;
        this.civs = civs;
    }

    public void queueBuild(Settlement s) {
        queue.put(s.id, Kind.BUILD);
    }

    public void queueGrowth(Settlement s) {
        queue.putIfAbsent(s.id, Kind.GROW);
    }

    public void queueRuin(Settlement s) {
        queue.put(s.id, Kind.RUIN);
    }

    public int queued() {
        return queue.size();
    }

    /** Every second: one job whose ground is loaded. A new settlement's chunks are fetched; growth and decay wait for someone to be near. */
    public void tick() {
        for (Settlement s : civs.world().settlements.values()) {
            if (s.alive() && !s.built && !queue.containsKey(s.id)) queue.put(s.id, Kind.BUILD); // after a restart
        }
        for (Map.Entry<String, Kind> e : new ArrayList<>(queue.entrySet())) {
            Settlement s = civs.world().settlements.get(e.getKey());
            World w = s == null ? null : Bukkit.getWorld(s.world);
            if (s == null || w == null) {
                queue.remove(e.getKey());
                continue;
            }
            if (!loaded(w, s)) {
                if (e.getValue() == Kind.BUILD && loading.add(s.id)) fetch(w, s);
                continue;
            }
            queue.remove(e.getKey());
            try {
                switch (e.getValue()) {
                    case BUILD -> build(w, s);
                    case GROW -> grow(w, s);
                    case RUIN -> ruin(w, s);
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not " + e.getValue().name().toLowerCase() + " the buildings of " + s.name, ex);
            }
            plugin.requestSave();
            return;
        }
    }

    private static boolean loaded(World w, Settlement s) {
        int r = s.radius + 6;
        for (int cx = (s.x - r) >> 4; cx <= (s.x + r) >> 4; cx++) {
            for (int cz = (s.z - r) >> 4; cz <= (s.z + r) >> 4; cz++) if (!w.isChunkLoaded(cx, cz)) return false;
        }
        return true;
    }

    private void fetch(World w, Settlement s) {
        int r = s.radius + 6;
        List<CompletableFuture<?>> all = new ArrayList<>();
        for (int cx = (s.x - r) >> 4; cx <= (s.x + r) >> 4; cx++) {
            for (int cz = (s.z - r) >> 4; cz <= (s.z + r) >> 4; cz++) all.add(w.getChunkAtAsync(cx, cz));
        }
        CompletableFuture.allOf(all.toArray(new CompletableFuture[0])).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            loading.remove(s.id);
            if (err != null || queue.get(s.id) != Kind.BUILD || !loaded(w, s)) return;
            queue.remove(s.id);
            try {
                build(w, s);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not build " + s.name, ex);
            }
            plugin.requestSave();
        }));
    }

    // ───────────── choosing ground ─────────────

    /** Blocks that were there before anybody came: anything else in a footprint means "somebody built here". */
    public static boolean natural(Material m) {
        if (m.isAir() || m == Material.WATER) return true;
        if (Tag.DIRT.isTagged(m) || Tag.BASE_STONE_OVERWORLD.isTagged(m) || Tag.SAND.isTagged(m) || Tag.LEAVES.isTagged(m) || Tag.LOGS.isTagged(m)
                || Tag.FLOWERS.isTagged(m) || Tag.SNOW.isTagged(m) || Tag.ICE.isTagged(m) || Tag.SAPLINGS.isTagged(m) || Tag.TERRACOTTA.isTagged(m)
                || Tag.COAL_ORES.isTagged(m) || Tag.IRON_ORES.isTagged(m) || Tag.COPPER_ORES.isTagged(m)) return true;
        return switch (m) {
            case GRASS_BLOCK, GRAVEL, CLAY, SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN, DEAD_BUSH, VINE, SANDSTONE, RED_SANDSTONE, MOSS_BLOCK, MOSS_CARPET,
                 PUMPKIN, MELON, SUGAR_CANE, CACTUS, BAMBOO, KELP, KELP_PLANT, SEAGRASS, TALL_SEAGRASS, BROWN_MUSHROOM, RED_MUSHROOM, SWEET_BERRY_BUSH,
                 COBWEB, LILY_PAD, SNOW_BLOCK, POWDER_SNOW, CALCITE, DRIPSTONE_BLOCK, BEE_NEST, COCOA, GLOW_LICHEN, MANGROVE_ROOTS, MUDDY_MANGROVE_ROOTS,
                 BUSH, FIREFLY_BUSH, LEAF_LITTER, WILDFLOWERS, PINK_PETALS, SHORT_DRY_GRASS, TALL_DRY_GRASS, CACTUS_FLOWER -> true;
            default -> false;
        };
    }

    private static boolean soft(Material m) {
        return m.isAir() || Tag.LEAVES.isTagged(m) || Tag.LOGS.isTagged(m) || Tag.FLOWERS.isTagged(m) || Tag.SAPLINGS.isTagged(m) || m == Material.SNOW
                || !m.isSolid() && m != Material.WATER && m != Material.LAVA && natural(m);
    }

    /** The y of the ground block in a column (under trees, plants and snow). */
    public static int ground(World w, int x, int z) {
        int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        while (y > w.getMinHeight() + 1 && soft(w.getBlockAt(x, y, z).getType())) y--;
        return y;
    }

    /**
     * Why a settlement can't stand here, or null if it can. Looks only at loaded, generated ground, so call it
     * for chunks that are loaded.
     */
    public String siteProblem(World w, int x, int z, boolean admin) {
        Settings.Civs cfg = plugin.settings().civs;
        if (!w.getWorldBorder().isInside(new Location(w, x, 64, z))) return "outside the world border";
        if (w.getEnvironment() != World.Environment.NORMAL) return "settlements stand in the overworld";
        if (!admin) {
            Location spawn = w.getSpawnLocation();
            double sx = spawn.getX() - x, sz = spawn.getZ() - z;
            if (sx * sx + sz * sz < (double) cfg.minFromSpawn * cfg.minFromSpawn) return "too close to spawn";
            for (int i = 0; i < cfg.avoid.size(); i++) {
                int[] a = cfg.avoid.get(i);
                if (cfg.avoidWorlds.get(i).equals(w.getName()) && Math.hypot(a[0] - x, a[1] - z) < a[2] + 30) return "too close to a place the config says to avoid";
            }
        }
        // player colonies, and room around them to grow
        int pad = admin ? 40 : cfg.minFromColony;
        for (int dx = -pad; dx <= pad; dx += 16) {
            for (int dz = -pad; dz <= pad; dz += 16) if (plugin.colonies().at(w.getName(), x + dx, z + dz) != null) return "too close to a colony's land";
        }
        Settlement near = civs.world().nearest(w.getName(), x, z, admin ? 70 : cfg.spacing * 0.6);
        if (near != null) return "too close to " + near.name;
        int[] heights = new int[81];
        int wet = 0, made = 0, n = 0;
        for (int dx = -20; dx <= 20; dx += 5) {
            for (int dz = -20; dz <= 20; dz += 5) {
                if (!w.isChunkLoaded((x + dx) >> 4, (z + dz) >> 4)) return "the ground there isn't loaded";
                int y = ground(w, x + dx, z + dz);
                heights[n++] = y;
                Block top = w.getBlockAt(x + dx, y, z + dz);
                Material above = top.getRelative(BlockFace.UP).getType();
                if (above == Material.WATER || above == Material.LAVA || top.getType() == Material.LAVA) wet++;
                for (int up = 0; up <= 3; up++) if (!natural(w.getBlockAt(x + dx, y + up, z + dz).getType())) made++;
            }
        }
        if (made > 0) return "somebody has built there";
        if (wet > 30) return "too much water";
        Arrays.sort(heights);
        if (heights[72] - heights[8] > 7) return "the ground is too steep";
        return null;
    }

    // ───────────── building ─────────────

    /** The woods and stones a settlement is built of, from the land it stands on. */
    private record Palette(Material log, Material planks, Material slab, Material fence, Material stone, Material path, Material wool) {}

    private static Palette palette(World w, Settlement s) {
        String biome = w.getBiome(s.x, s.y, s.z).getKey().getKey();
        Material wool = switch (Math.floorMod(s.civ.hashCode(), 6)) {
            case 0 -> Material.RED_WOOL;
            case 1 -> Material.BLUE_WOOL;
            case 2 -> Material.GREEN_WOOL;
            case 3 -> Material.YELLOW_WOOL;
            case 4 -> Material.PURPLE_WOOL;
            default -> Material.ORANGE_WOOL;
        };
        if (s.type == SettlementType.BANDIT_CAMP) wool = Material.BLACK_WOOL;
        if (biome.contains("desert") || biome.contains("badlands") || biome.contains("beach")) {
            return new Palette(Material.CUT_SANDSTONE, Material.SMOOTH_SANDSTONE, Material.SMOOTH_SANDSTONE_SLAB, Material.BIRCH_FENCE, Material.SANDSTONE, Material.SMOOTH_SANDSTONE, wool);
        }
        if (biome.contains("taiga") || biome.contains("snow") || biome.contains("frozen") || biome.contains("grove") || biome.contains("peak") || biome.contains("windswept")) {
            return new Palette(Material.SPRUCE_LOG, Material.SPRUCE_PLANKS, Material.SPRUCE_SLAB, Material.SPRUCE_FENCE, Material.COBBLESTONE, Material.DIRT_PATH, wool);
        }
        if (biome.contains("savanna")) return new Palette(Material.ACACIA_LOG, Material.ACACIA_PLANKS, Material.ACACIA_SLAB, Material.ACACIA_FENCE, Material.COBBLESTONE, Material.DIRT_PATH, wool);
        if (biome.contains("dark_forest")) return new Palette(Material.DARK_OAK_LOG, Material.DARK_OAK_PLANKS, Material.DARK_OAK_SLAB, Material.DARK_OAK_FENCE, Material.COBBLESTONE, Material.DIRT_PATH, wool);
        if (biome.contains("birch")) return new Palette(Material.BIRCH_LOG, Material.BIRCH_PLANKS, Material.BIRCH_SLAB, Material.BIRCH_FENCE, Material.COBBLESTONE, Material.DIRT_PATH, wool);
        return new Palette(Material.OAK_LOG, Material.OAK_PLANKS, Material.OAK_SLAB, Material.OAK_FENCE, Material.COBBLESTONE, Material.DIRT_PATH, wool);
    }

    private static void set(World w, int x, int y, int z, Material m) {
        w.getBlockAt(x, y, z).setType(m, false);
    }

    private static void top(World w, int x, int y, int z, Material slab) {
        Block b = w.getBlockAt(x, y, z);
        b.setType(slab, false);
        if (b.getBlockData() instanceof Slab sl) {
            sl.setType(Slab.Type.TOP);
            b.setBlockData(sl, false);
        }
    }

    /**
     * Levels a footprint ready to build on and returns its floor height, or null if the ground is unfit (steep,
     * wet, or touched by hand). Nothing is changed unless the whole footprint is fit.
     */
    private Integer prepare(World w, int x0, int z0, int sx, int sz, int height) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        long sum = 0;
        for (int x = x0; x < x0 + sx; x++) {
            for (int z = z0; z < z0 + sz; z++) {
                int y = ground(w, x, z);
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
                sum += y;
                Material above = w.getBlockAt(x, y + 1, z).getType();
                if (above == Material.WATER || above == Material.LAVA) return null;
                if (plugin.colonies().at(w.getName(), x, z) != null) return null;
                for (int up = 0; up <= height + 2; up++) if (!natural(w.getBlockAt(x, y + up, z).getType())) return null;
            }
        }
        if (hi - lo > 3) return null;
        int floor = (int) Math.round(sum / (double) (sx * sz));
        for (int x = x0; x < x0 + sx; x++) {
            for (int z = z0; z < z0 + sz; z++) {
                for (int y = floor - 3; y < floor; y++) if (soft(w.getBlockAt(x, y, z).getType()) || w.getBlockAt(x, y, z).getType() == Material.WATER) set(w, x, y, z, Material.DIRT);
                for (int y = floor + 1; y <= floor + height + 6; y++) if (w.getBlockAt(x, y, z).getType() != Material.AIR) set(w, x, y, z, Material.AIR);
            }
        }
        return floor;
    }

    private static void box(Settlement s, int x1, int y1, int z1, int x2, int y2, int z2) {
        s.structures.add(x1 + "," + y1 + "," + z1 + "," + x2 + "," + y2 + "," + z2);
    }

    private boolean overlaps(Settlement s, int x0, int z0, int sx, int sz) {
        for (String b : s.structures) {
            int[] a = parse(b);
            if (a != null && x0 - 1 <= a[3] && x0 + sx >= a[0] && z0 - 1 <= a[5] && z0 + sz >= a[2]) return true;
        }
        return false;
    }

    static int[] parse(String box) {
        String[] p = box.split(",");
        if (p.length != 6) return null;
        try {
            int[] a = new int[6];
            for (int i = 0; i < 6; i++) a[i] = Integer.parseInt(p[i]);
            return a;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void build(World w, Settlement s) {
        if (s.built) return;
        Palette pal = palette(w, s);
        Random r = new Random(s.id.hashCode() * 31L + s.x);
        // the market first: it is where the merchant stands, so it is tried in a few spots before giving up
        boolean market = false;
        int[][] tries = {{0, 0}, {5, 0}, {-5, 0}, {0, 5}, {0, -5}, {6, 6}, {-6, -6}};
        for (int[] t : tries) {
            if (stall(w, s, pal, s.x + t[0] - 2, s.z + t[1] - 2)) {
                market = true;
                break;
            }
        }
        if (!market) {
            // no fit ground for even a stall: the merchant trades from open ground, and nothing else is built
            s.marketX = s.x;
            s.marketZ = s.z;
            s.marketY = ground(w, s.x, s.z) + 1;
        }
        s.y = s.marketY;
        int houses = s.type == SettlementType.BANDIT_CAMP ? 3 : 2 + 2 * s.level;
        int slot = 0;
        slot = feature(w, s, pal, r, slot);
        for (int placed = 0; placed < houses && slot < SLOTS.length; slot++) {
            int x = s.x + SLOTS[slot][0] - 2, z = s.z + SLOTS[slot][1] - 2;
            boolean ok = s.type == SettlementType.BANDIT_CAMP ? tent(w, s, pal, x, z) : house(w, s, pal, r, x, z);
            if (ok) placed++;
        }
        if (s.type == SettlementType.FORTIFIED_KINGDOM || s.type == SettlementType.MILITARY_SETTLEMENT || s.type == SettlementType.BANDIT_CAMP) {
            palisade(w, s, s.type == SettlementType.FORTIFIED_KINGDOM ? Material.COBBLESTONE_WALL : pal.fence());
        }
        s.built = true;
        s.builtLevel = s.level;
        plugin.getLogger().info("Built " + s.name + " (" + s.type.display + ") at " + s.x + ", " + s.y + ", " + s.z + " in " + s.world + ": " + s.structures.size() + " structures.");
    }

    /** More houses for a settlement that has grown a level. */
    private void grow(World w, Settlement s) {
        if (!s.built || s.level <= s.builtLevel) return;
        Palette pal = palette(w, s);
        Random r = new Random(s.id.hashCode() * 17L + s.level);
        s.radius = 22 + 4 * s.level;
        int want = 2 * (s.level - s.builtLevel), placed = 0;
        for (int slot = 0; slot < SLOTS.length && placed < want; slot++) {
            int x = s.x + SLOTS[slot][0] - 2, z = s.z + SLOTS[slot][1] - 2;
            if (overlaps(s, x, z, 5, 5)) continue;
            if (house(w, s, pal, r, x, z)) placed++;
        }
        s.builtLevel = s.level;
    }

    private void path(World w, Settlement s, Palette pal, int x, int z) {
        int steps = Math.max(Math.abs(x - s.marketX), Math.abs(z - s.marketZ));
        for (int i = 2; i < steps - 1; i++) {
            int px = s.marketX + (x - s.marketX) * i / steps, pz = s.marketZ + (z - s.marketZ) * i / steps;
            int y = ground(w, px, pz);
            Block b = w.getBlockAt(px, y, pz);
            Material m = b.getType();
            if ((m == Material.GRASS_BLOCK || m == Material.DIRT || m == Material.PODZOL || m == Material.COARSE_DIRT || m == Material.SAND)
                    && !b.getRelative(BlockFace.UP).getType().isSolid() && plugin.colonies().at(w.getName(), px, pz) == null) {
                Material above = b.getRelative(BlockFace.UP).getType();
                if (above != Material.AIR && natural(above) && above != Material.WATER) b.getRelative(BlockFace.UP).setType(Material.AIR, false);
                b.setType(m == Material.SAND ? Material.SMOOTH_SANDSTONE : pal.path() == Material.DIRT_PATH ? Material.DIRT_PATH : pal.path(), false);
            }
        }
    }

    /** The market stall: four posts, an awning in the people's colour, barrels, and room for the merchant. */
    private boolean stall(World w, Settlement s, Palette pal, int x0, int z0) {
        Integer f = prepare(w, x0, z0, 5, 5, 4);
        if (f == null) return false;
        for (int x = x0; x < x0 + 5; x++) for (int z = z0; z < z0 + 5; z++) set(w, x, f, z, pal.planks());
        for (int[] c : new int[][]{{0, 0}, {4, 0}, {0, 4}, {4, 4}}) {
            for (int y = 1; y <= 3; y++) set(w, x0 + c[0], f + y, z0 + c[1], pal.fence());
        }
        for (int x = x0; x < x0 + 5; x++) for (int z = z0; z < z0 + 5; z++) set(w, x, f + 4, z, (x + z) % 2 == 0 ? pal.wool() : Material.WHITE_WOOL);
        set(w, x0 + 1, f + 1, z0, Material.BARREL);
        set(w, x0 + 3, f + 1, z0, Material.BARREL);
        set(w, x0 + 2, f + 1, z0, Material.COMPOSTER);
        set(w, x0 + 4, f + 1, z0 + 2, Material.HAY_BLOCK);
        set(w, x0, f + 1, z0 + 2, Material.LANTERN);
        s.marketX = x0 + 2;
        s.marketY = f + 1;
        s.marketZ = z0 + 2;
        box(s, x0, f, z0, x0 + 4, f + 4, z0 + 4);
        return true;
    }

    /** A cottage: log corners, plank walls, windows, an open doorway facing the market, a lantern and a bed of hay. */
    private boolean house(World w, Settlement s, Palette pal, Random r, int x0, int z0) {
        if (overlaps(s, x0, z0, 5, 5)) return false;
        Integer f = prepare(w, x0, z0, 5, 5, 5);
        if (f == null) return false;
        for (int x = x0; x < x0 + 5; x++) {
            for (int z = z0; z < z0 + 5; z++) {
                boolean edgeX = x == x0 || x == x0 + 4, edgeZ = z == z0 || z == z0 + 4;
                set(w, x, f, z, pal.planks());
                for (int y = 1; y <= 3; y++) {
                    if (edgeX && edgeZ) set(w, x, f + y, z, pal.log());
                    else if (edgeX || edgeZ) set(w, x, f + y, z, y == 2 && (x == x0 + 2 || z == z0 + 2) ? Material.GLASS_PANE : pal.planks());
                }
                set(w, x, f + 4, z, pal.planks());
            }
        }
        for (int x = x0 - 1; x <= x0 + 5; x++) {
            for (int z = z0 - 1; z <= z0 + 5; z++) {
                boolean ring = x == x0 - 1 || x == x0 + 5 || z == z0 - 1 || z == z0 + 5;
                if (ring && w.getBlockAt(x, f + 4, z).getType() == Material.AIR) top(w, x, f + 4, z, pal.slab());
            }
        }
        for (int x = x0 + 1; x <= x0 + 3; x++) for (int z = z0 + 1; z <= z0 + 3; z++) set(w, x, f + 5, z, pal.slab());
        // the doorway is in the wall nearest the market
        int dx = s.marketX - (x0 + 2), dz = s.marketZ - (z0 + 2);
        int doorX = x0 + 2, doorZ = z0 + 2;
        if (Math.abs(dx) >= Math.abs(dz)) doorX = dx > 0 ? x0 + 4 : x0;
        else doorZ = dz > 0 ? z0 + 4 : z0;
        set(w, doorX, f + 1, doorZ, Material.AIR);
        set(w, doorX, f + 2, doorZ, Material.AIR);
        set(w, x0 + 1, f + 1, z0 + 1, Material.HAY_BLOCK);
        set(w, x0 + 1, f + 2, z0 + 1, Material.WHITE_CARPET);
        set(w, x0 + 3, f + 1, z0 + 3, r.nextBoolean() ? Material.CRAFTING_TABLE : Material.BARREL);
        set(w, x0 + 3, f + 1, z0 + 1, Material.LANTERN);
        box(s, x0 - 1, f, z0 - 1, x0 + 5, f + 5, z0 + 5);
        path(w, s, pal, doorX, doorZ);
        return true;
    }

    /** An outlaw's tent: a ridge of dark wool over two low walls. */
    private boolean tent(World w, Settlement s, Palette pal, int x0, int z0) {
        if (overlaps(s, x0, z0, 3, 4)) return false;
        Integer f = prepare(w, x0, z0, 3, 4, 3);
        if (f == null) return false;
        for (int z = z0; z < z0 + 4; z++) {
            set(w, x0, f + 1, z, pal.wool());
            set(w, x0 + 2, f + 1, z, pal.wool());
            set(w, x0 + 1, f + 2, z, pal.wool());
        }
        set(w, x0 + 1, f + 1, z0 + 3, Material.HAY_BLOCK);
        box(s, x0, f, z0, x0 + 2, f + 2, z0 + 3);
        return true;
    }

    /** What the settlement is known for: a field, a mine head, a forge, a chapel, a watchtower. Returns the next free slot. */
    private int feature(World w, Settlement s, Palette pal, Random r, int slot) {
        int x = s.x + SLOTS[slot][0] - 3, z = s.z + SLOTS[slot][1] - 3;
        boolean done = switch (s.type) {
            case FARMING_VILLAGE, FORTIFIED_KINGDOM, ISOLATED_COMMUNITY -> field(w, s, x, z, r);
            case MINING_SETTLEMENT -> mine(w, s, pal, x, z);
            case INDUSTRIAL_TOWN, MILITARY_SETTLEMENT -> forge(w, s, pal, x, z);
            case RELIGIOUS_SETTLEMENT -> shrine(w, s, pal, x, z);
            case TRADING_TOWN, FISHING_VILLAGE, COASTAL_SETTLEMENT, FRONTIER_OUTPOST -> yard(w, s, pal, x, z);
            case BANDIT_CAMP -> fire(w, s, x + 3, z + 3);
        };
        if (done) slot++;
        if (s.type == SettlementType.MILITARY_SETTLEMENT || s.type == SettlementType.FORTIFIED_KINGDOM || s.type == SettlementType.FRONTIER_OUTPOST || s.type == SettlementType.BANDIT_CAMP) {
            if (tower(w, s, pal, s.x + SLOTS[slot][0] - 1, s.z + SLOTS[slot][1] - 1)) slot++;
        }
        return slot;
    }

    private boolean field(World w, Settlement s, int x0, int z0, Random r) {
        Integer f = prepare(w, x0, z0, 7, 7, 2);
        if (f == null) return false;
        for (int x = x0; x < x0 + 7; x++) {
            for (int z = z0; z < z0 + 7; z++) {
                boolean edge = x == x0 || x == x0 + 6 || z == z0 || z == z0 + 6;
                if (edge) {
                    set(w, x, f, z, Material.OAK_LOG);
                } else if (x == x0 + 3 && z == z0 + 3) {
                    set(w, x, f, z, Material.WATER);
                } else {
                    Block soil = w.getBlockAt(x, f, z);
                    soil.setType(Material.FARMLAND, false);
                    if (soil.getBlockData() instanceof Farmland fl) {
                        fl.setMoisture(fl.getMaximumMoisture());
                        soil.setBlockData(fl, false);
                    }
                    Block crop = w.getBlockAt(x, f + 1, z);
                    crop.setType(Material.WHEAT, false);
                    if (crop.getBlockData() instanceof Ageable a) {
                        a.setAge(r.nextInt(a.getMaximumAge() + 1));
                        crop.setBlockData(a, false);
                    }
                }
            }
        }
        box(s, x0, f - 1, z0, x0 + 6, f + 1, z0 + 6);
        return true;
    }

    private boolean mine(World w, Settlement s, Palette pal, int x0, int z0) {
        Integer f = prepare(w, x0, z0, 5, 5, 4);
        if (f == null) return false;
        for (int x = x0; x < x0 + 5; x++) for (int z = z0; z < z0 + 5; z++) set(w, x, f, z, Material.COBBLESTONE);
        for (int[] c : new int[][]{{1, 1}, {3, 1}, {1, 3}, {3, 3}}) for (int y = 1; y <= 3; y++) set(w, x0 + c[0], f + y, z0 + c[1], pal.log());
        for (int x = x0 + 1; x <= x0 + 3; x++) for (int z = z0 + 1; z <= z0 + 3; z++) set(w, x, f + 4, z, pal.slab());
        // the shaft: a few blocks down, lined, with a ladder
        for (int y = f; y >= f - 4; y--) {
            set(w, x0 + 2, y, z0 + 2, Material.AIR);
            Block l = w.getBlockAt(x0 + 2, y, z0 + 2);
            if (y < f + 1 && w.getBlockAt(x0 + 2, y, z0 + 1).getType().isSolid()) {
                l.setType(Material.LADDER, false);
                if (l.getBlockData() instanceof Directional d) {
                    d.setFacing(BlockFace.SOUTH);
                    l.setBlockData(d, false);
                }
            }
        }
        set(w, x0 + 4, f + 1, z0 + 4, Material.COBBLESTONE);
        set(w, x0 + 4, f + 1, z0 + 3, Material.COBBLESTONE);
        set(w, x0 + 4, f + 2, z0 + 4, Material.LANTERN);
        box(s, x0, f - 5, z0, x0 + 4, f + 4, z0 + 4);
        return true;
    }

    private boolean forge(World w, Settlement s, Palette pal, int x0, int z0) {
        Integer f = prepare(w, x0, z0, 5, 5, 4);
        if (f == null) return false;
        for (int x = x0; x < x0 + 5; x++) for (int z = z0; z < z0 + 5; z++) set(w, x, f, z, Material.STONE_BRICKS);
        for (int[] c : new int[][]{{0, 0}, {4, 0}, {0, 4}, {4, 4}}) for (int y = 1; y <= 3; y++) set(w, x0 + c[0], f + y, z0 + c[1], pal.log());
        for (int x = x0; x < x0 + 5; x++) for (int z = z0; z < z0 + 5; z++) set(w, x, f + 4, z, pal.slab());
        set(w, x0 + 2, f + 1, z0 + 1, Material.BLAST_FURNACE);
        set(w, x0 + 2, f + 1, z0 + 3, Material.ANVIL);
        set(w, x0 + 1, f + 1, z0 + 1, Material.CAULDRON);
        set(w, x0 + 3, f + 1, z0 + 1, Material.SMITHING_TABLE);
        box(s, x0, f, z0, x0 + 4, f + 4, z0 + 4);
        return true;
    }

    private boolean shrine(World w, Settlement s, Palette pal, int x0, int z0) {
        Integer f = prepare(w, x0, z0, 5, 5, 6);
        if (f == null) return false;
        for (int x = x0; x < x0 + 5; x++) for (int z = z0; z < z0 + 5; z++) set(w, x, f, z, Material.STONE_BRICKS);
        for (int[] c : new int[][]{{0, 0}, {4, 0}, {0, 4}, {4, 4}}) for (int y = 1; y <= 4; y++) set(w, x0 + c[0], f + y, z0 + c[1], Material.STONE_BRICKS);
        for (int x = x0; x < x0 + 5; x++) for (int z = z0; z < z0 + 5; z++) set(w, x, f + 5, z, Material.STONE_BRICK_SLAB);
        set(w, x0 + 2, f + 1, z0 + 2, Material.LECTERN);
        set(w, x0 + 2, f + 4, z0 + 2, Material.BELL);
        set(w, x0 + 1, f + 1, z0 + 2, Material.CANDLE);
        set(w, x0 + 3, f + 1, z0 + 2, Material.CANDLE);
        box(s, x0, f, z0, x0 + 4, f + 5, z0 + 4);
        return true;
    }

    /** A store yard: stacked barrels, hay and a rack, fenced on three sides. */
    private boolean yard(World w, Settlement s, Palette pal, int x0, int z0) {
        Integer f = prepare(w, x0, z0, 5, 5, 3);
        if (f == null) return false;
        for (int x = x0; x < x0 + 5; x++) {
            for (int z = z0; z < z0 + 5; z++) {
                set(w, x, f, z, pal.planks());
                if ((x == x0 || x == x0 + 4 || z == z0) && !(x == x0 + 2 && z == z0)) set(w, x, f + 1, z, pal.fence());
            }
        }
        set(w, x0 + 1, f + 1, z0 + 3, Material.BARREL);
        set(w, x0 + 2, f + 1, z0 + 3, Material.BARREL);
        set(w, x0 + 1, f + 2, z0 + 3, Material.BARREL);
        set(w, x0 + 3, f + 1, z0 + 3, s.type.coastal() ? Material.DRIED_KELP_BLOCK : Material.HAY_BLOCK);
        set(w, x0 + 3, f + 1, z0 + 2, pal.log());
        box(s, x0, f, z0, x0 + 4, f + 3, z0 + 4);
        return true;
    }

    private boolean fire(World w, Settlement s, int x, int z) {
        Integer f = prepare(w, x - 1, z - 1, 3, 3, 2);
        if (f == null) return false;
        set(w, x, f + 1, z, Material.CAMPFIRE);
        set(w, x - 1, f + 1, z + 1, Material.OAK_LOG);
        box(s, x - 1, f, z - 1, x + 1, f + 1, z + 1);
        return true;
    }

    private boolean tower(World w, Settlement s, Palette pal, int x0, int z0) {
        if (overlaps(s, x0, z0, 3, 3)) return false;
        Integer f = prepare(w, x0, z0, 3, 3, 9);
        if (f == null) return false;
        for (int y = 0; y <= 6; y++) {
            for (int x = x0; x < x0 + 3; x++) {
                for (int z = z0; z < z0 + 3; z++) {
                    boolean corner = (x == x0 || x == x0 + 2) && (z == z0 || z == z0 + 2);
                    if (y == 0 || y == 6) set(w, x, f + y, z, pal.planks());
                    else if (corner) set(w, x, f + y, z, pal.log());
                }
            }
        }
        for (int x = x0 - 1; x <= x0 + 3; x++) {
            for (int z = z0 - 1; z <= z0 + 3; z++) {
                boolean ring = x == x0 - 1 || x == x0 + 3 || z == z0 - 1 || z == z0 + 3;
                if (ring) {
                    top(w, x, f + 6, z, pal.slab());
                    set(w, x, f + 7, z, pal.fence());
                }
            }
        }
        set(w, x0 + 1, f + 7, z0 + 1, Material.LANTERN);
        box(s, x0 - 1, f, z0 - 1, x0 + 3, f + 8, z0 + 3);
        return true;
    }

    /** A ring of wall or fence at the settlement's edge, with a gap for a gate on each side. Skips any ground that isn't fit. */
    private void palisade(World w, Settlement s, Material m) {
        int R = s.radius - 2, placed = 0;
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                if ((int) Math.round(Math.sqrt(dx * dx + dz * dz)) != R) continue;
                if (Math.abs(dx) <= 1 || Math.abs(dz) <= 1) continue; // the four gates
                int x = s.x + dx, z = s.z + dz, y = ground(w, x, z);
                if (plugin.colonies().at(w.getName(), x, z) != null) continue;
                Material above = w.getBlockAt(x, y + 1, z).getType();
                if (above == Material.WATER || above == Material.LAVA) continue;
                boolean fit = true;
                for (int up = 0; up <= 3; up++) if (!natural(w.getBlockAt(x, y + up, z).getType())) fit = false;
                if (!fit) continue;
                for (int up = 1; up <= 2; up++) w.getBlockAt(x, y + up, z).setType(m, true);
                placed++;
            }
        }
        if (placed > 0) s.structures.add("ring," + R + "," + m.name());
    }

    // ───────────── falling down and clearing away ─────────────

    /** A fallen settlement's buildings decay: gaps in the walls, moss on the stone, cobwebs in the corners. They are no longer protected. */
    private void ruin(World w, Settlement s) {
        Random r = new Random(s.id.hashCode() ^ 0x5eedL);
        for (String b : s.structures) {
            int[] a = parse(b);
            if (a == null) continue;
            for (int x = a[0]; x <= a[3]; x++) {
                for (int y = a[1] + 1; y <= a[4]; y++) {
                    for (int z = a[2]; z <= a[5]; z++) {
                        Block bl = w.getBlockAt(x, y, z);
                        Material m = bl.getType();
                        if (m.isAir()) {
                            if (r.nextInt(60) == 0 && bl.getRelative(BlockFace.DOWN).getType().isSolid()) bl.setType(Material.COBWEB, false);
                        } else if (m == Material.COBBLESTONE || m == Material.STONE_BRICKS) {
                            if (r.nextInt(3) == 0) bl.setType(m == Material.COBBLESTONE ? Material.MOSSY_COBBLESTONE : Material.MOSSY_STONE_BRICKS, false);
                        } else if (m == Material.WHEAT || m == Material.LANTERN || m == Material.WHITE_CARPET || m == Material.CANDLE || r.nextInt(100) < 35 + 8 * (y - a[1])) {
                            bl.setType(Material.AIR, false);
                        }
                    }
                }
            }
        }
        civs.bodies().despawn(s);
        s.builtLevel = -1;
        plugin.getLogger().info(s.name + " has fallen: its buildings are now ruins.");
    }

    /** Removes what was built (admin removal). The levelled ground stays. */
    public void clear(Settlement s) {
        World w = Bukkit.getWorld(s.world);
        if (w == null) return;
        for (String b : s.structures) {
            int[] a = parse(b);
            if (a == null) {
                String[] p = b.split(",");
                if (p.length == 3 && p[0].equals("ring")) {
                    int R = Integer.parseInt(p[1]);
                    Material m = Material.matchMaterial(p[2]);
                    for (int dx = -R; dx <= R; dx++) {
                        for (int dz = -R; dz <= R; dz++) {
                            if ((int) Math.round(Math.sqrt(dx * dx + dz * dz)) != R || !w.isChunkLoaded((s.x + dx) >> 4, (s.z + dz) >> 4)) continue;
                            int y = ground(w, s.x + dx, s.z + dz);
                            for (int up = -1; up <= 3; up++) if (w.getBlockAt(s.x + dx, y + up, s.z + dz).getType() == m) w.getBlockAt(s.x + dx, y + up, s.z + dz).setType(Material.AIR, true);
                        }
                    }
                }
                continue;
            }
            for (int x = a[0]; x <= a[3]; x++) {
                for (int z = a[2]; z <= a[5]; z++) {
                    if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                    for (int y = a[1]; y <= a[4]; y++) {
                        Block bl = w.getBlockAt(x, y, z);
                        if (y == a[1] && bl.getType().isSolid()) bl.setType(Material.DIRT, false);
                        else if (y > a[1]) bl.setType(Material.AIR, false);
                    }
                }
            }
        }
        s.structures.clear();
        s.built = false;
    }

    /** Is this block part of a living settlement's buildings? */
    public Settlement owner(Block b) {
        for (Settlement s : civs.world().settlements.values()) {
            if (!s.alive() || !s.built || !s.world.equals(b.getWorld().getName())) continue;
            int dx = b.getX() - s.x, dz = b.getZ() - s.z;
            if (Math.abs(dx) > s.radius + 8 || Math.abs(dz) > s.radius + 8) continue;
            for (String box : s.structures) {
                int[] a = parse(box);
                if (a != null) {
                    if (b.getX() >= a[0] && b.getX() <= a[3] && b.getZ() >= a[2] && b.getZ() <= a[5] && b.getY() >= a[1] - 2 && b.getY() <= a[4] + 1) return s;
                } else {
                    String[] p = box.split(",");
                    if (p.length == 3 && p[0].equals("ring") && b.getType().name().equals(p[2])
                            && Math.abs((int) Math.round(Math.sqrt(dx * dx + dz * dz)) - Integer.parseInt(p[1])) <= 0) return s;
                }
            }
        }
        return null;
    }

    static BlockData data(Material m) {
        return m.createBlockData();
    }
}
