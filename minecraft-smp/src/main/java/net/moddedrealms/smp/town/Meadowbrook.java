package net.moddedrealms.smp.town;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.moddedrealms.smp.SmpPlugin;
import net.moddedrealms.smp.npc.NpcDef;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.EntityTransformer;

/**
 * Meadowbrook, the market city: generated on a flat site (levelled first), saved beforehand and reversible.
 * <pre>
 *            north (industry: farm, wool factory, lumber mill, workshops)   (homes)
 *   west     ----------- cross street --- SQUARE + clock tower --- cross street -----------   east
 *            (civic: courthouse, Herald, bank, sheriff, hotel, town hall)   (business district)
 *                                         south gate (station)
 * </pre>
 * Main avenue and cross street meet at the square; a ring street runs 31-35 blocks out; a wall with four gates
 * surrounds it all. Plots (business, home, industry) are registered with {@link Plots}; the sheriff's office
 * becomes the town's jail; seats in the courthouse and other anchors are saved for later systems.
 */
public final class Meadowbrook {
    static final String ID = "meadowbrook";
    static final int R = 64;
    private static final int DOWN = 8, UP = 32;

    private final TownManager t;
    private final NamespacedKey key;
    private final File file;
    private final Random r = new Random(1234);
    private World w;
    private int cx, cy, cz;
    private UUID clock;

    Meadowbrook(TownManager t) {
        this.t = t;
        this.key = new NamespacedKey(t.plugin(), "meadowbrook");
        this.file = new File(t.plugin().getDataFolder(), "meadowbrook.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        String c = y.getString("clock");
        clock = c == null ? null : UUID.fromString(c);
    }

    private File structureFile() {
        return new File(t.plugin().getDataFolder(), "meadowbrook-undo.nbt");
    }

    private File metaFile() {
        return new File(t.plugin().getDataFolder(), "meadowbrook-undo.yml");
    }

    public boolean built() {
        return structureFile().exists();
    }

    public YamlConfiguration data() {
        return YamlConfiguration.loadConfiguration(file);
    }

    // ------------------------------------------------------------------ layout helpers

    private Block at(int dx, int dy, int dz) {
        return w.getBlockAt(cx + dx, cy + dy, cz + dz);
    }

    private void set(int dx, int dy, int dz, Material m) {
        at(dx, dy, dz).setType(m, false);
    }

    private static boolean square(int dx, int dz) {
        return dx * dx + dz * dz <= 14 * 14;
    }

    private static boolean street(int dx, int dz) {
        int ax = Math.abs(dx), az = Math.abs(dz);
        return ax <= 3 || az <= 3 || ax >= 31 && ax <= 35 || az >= 31 && az <= 35 || square(dx, dz);
    }

    private Build build(int dx, int dz, BlockFace front) {
        return new Build(w, cx + dx, cy, cz + dz, front);
    }

    /** Direction from a rectangle (town-relative) to the closest street. */
    private BlockFace faceStreet(int x0, int z0, int x1, int z1) {
        int mx = (x0 + x1) / 2, mz = (z0 + z1) / 2;
        BlockFace best = BlockFace.NORTH;
        int bd = 99;
        int[][] dirs = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}};
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (int i = 0; i < 4; i++) {
            int sx = dirs[i][0] > 0 ? x1 : dirs[i][0] < 0 ? x0 : mx, sz = dirs[i][1] > 0 ? z1 : dirs[i][1] < 0 ? z0 : mz;
            for (int k = 1; k <= 18; k++) {
                if (street(sx + dirs[i][0] * k, sz + dirs[i][1] * k)) {
                    if (k < bd) {
                        bd = k;
                        best = faces[i];
                    }
                    break;
                }
            }
        }
        return best;
    }

    /** Builder for a building inside a rectangle, front towards `f`, starting `inset` blocks in from that edge. */
    private Build inRect(int x0, int z0, int x1, int z1, BlockFace f, int inset) {
        int mx = (x0 + x1) / 2, mz = (z0 + z1) / 2;
        return switch (f) {
            case SOUTH -> build(mx, z1 - inset, f);
            case EAST -> build(x1 - inset, mz, f);
            case WEST -> build(x0 + inset, mz, f);
            default -> build(mx, z0 + inset, f);
        };
    }

    // ------------------------------------------------------------------ palettes and generic buildings

    private record Palette(Material wall, Material frame, Material roof, Material roofFill, Material floor, Material base) {}

    private static final Palette[] HOMES = {
            new Palette(Material.OAK_PLANKS, Material.STRIPPED_OAK_LOG, Material.DARK_OAK_STAIRS, Material.DARK_OAK_PLANKS, Material.SPRUCE_PLANKS, Material.COBBLESTONE),
            new Palette(Material.SPRUCE_PLANKS, Material.SPRUCE_LOG, Material.DEEPSLATE_TILE_STAIRS, Material.DEEPSLATE_TILES, Material.OAK_PLANKS, Material.STONE_BRICKS),
            new Palette(Material.BIRCH_PLANKS, Material.STRIPPED_SPRUCE_LOG, Material.SPRUCE_STAIRS, Material.SPRUCE_PLANKS, Material.BIRCH_PLANKS, Material.MOSSY_COBBLESTONE),
            new Palette(Material.WHITE_TERRACOTTA, Material.DARK_OAK_LOG, Material.BRICK_STAIRS, Material.BRICKS, Material.DARK_OAK_PLANKS, Material.STONE_BRICKS),
            new Palette(Material.BRICKS, Material.STRIPPED_DARK_OAK_LOG, Material.DARK_OAK_STAIRS, Material.DARK_OAK_PLANKS, Material.SPRUCE_PLANKS, Material.POLISHED_ANDESITE),
            new Palette(Material.MUD_BRICKS, Material.STRIPPED_SPRUCE_LOG, Material.MUD_BRICK_STAIRS, Material.PACKED_MUD, Material.OAK_PLANKS, Material.COBBLESTONE)};

    private static final Palette SHOP = new Palette(Material.BRICKS, Material.STONE_BRICKS, Material.DARK_OAK_STAIRS, Material.DARK_OAK_PLANKS, Material.SPRUCE_PLANKS, Material.POLISHED_ANDESITE);
    private static final Palette CIVIC = new Palette(Material.STONE_BRICKS, Material.POLISHED_ANDESITE, Material.DEEPSLATE_TILE_STAIRS, Material.DEEPSLATE_TILES, Material.POLISHED_ANDESITE, Material.POLISHED_DEEPSLATE);

    enum Inside { NONE, HOME, SHOP, RESTAURANT, BAKERY, STORE }

    /**
     * A building with its front (and door) on local z=0, w wide (odd) and d deep, each storey 4 high, with a gable roof.
     * Returns the height of the top of the walls.
     */
    private int building(Build b, int width, int depth, int floors, Palette p, Inside inside, boolean bigWindows) {
        int hx = width / 2, top = floors * 4;
        b.fill(-hx, -1, 0, hx, -1, depth - 1, p.base());
        b.fill(-hx, 0, 0, hx, 0, depth - 1, p.floor());
        b.fill(-hx + 1, 1, 1, hx - 1, top + 6, depth - 2, Material.AIR);
        for (int y = 1; y <= top; y++) {
            for (int x = -hx; x <= hx; x++) {
                for (int z = 0; z < depth; z++) {
                    boolean edge = x == -hx || x == hx || z == 0 || z == depth - 1;
                    if (!edge) continue;
                    boolean corner = (x == -hx || x == hx) && (z == 0 || z == depth - 1);
                    boolean floorBand = y % 4 == 0;
                    Material m = corner ? p.frame() : floorBand ? p.frame() : y == 1 && p.wall() != p.base() ? p.wall() : p.wall();
                    if (corner) b.log(x, y, z, p.frame(), Axis.Y);
                    else if (floorBand && p.frame().name().contains("LOG")) b.log(x, y, z, p.frame(), x == -hx || x == hx ? Axis.Z : Axis.X);
                    else b.set(x, y, z, m);
                }
            }
            if (y % 4 == 0 && y < top) b.fill(-hx + 1, y, 1, hx - 1, y, depth - 2, p.floor());
        }
        // windows
        for (int f = 0; f < floors; f++) {
            int y0 = f * 4 + 2;
            for (int x = -hx + 2; x <= hx - 2; x += 2) {
                if (f == 0 && Math.abs(x) <= 1) continue;
                b.set(x, y0, 0, Material.GLASS_PANE);
                if (bigWindows && f == 0) b.set(x, y0 + 1, 0, Material.GLASS_PANE);
                b.set(x, y0, depth - 1, Material.GLASS_PANE);
            }
            for (int z = 2; z <= depth - 3; z += 3) {
                b.set(-hx, y0, z, Material.GLASS_PANE);
                b.set(hx, y0, z, Material.GLASS_PANE);
            }
        }
        b.door(0, 1, 0, Material.SPRUCE_DOOR, BlockFace.NORTH);
        b.lantern(-1, 3, -1, false);
        b.set(-1, 2, -1, Material.AIR);
        b.gable(-hx, hx, 0, depth - 1, top + 1, p.roof(), p.roofFill());
        b.lantern(0, top - 1, depth / 2, true);
        // stairs between floors (a ladder in a back corner keeps it simple and compact)
        for (int y = 1; y < top; y++) b.facing(hx - 1, y, depth - 2, Material.LADDER, BlockFace.NORTH);
        for (int f = 1; f < floors; f++) b.set(hx - 1, f * 4, depth - 2, Material.AIR);
        furnish(b, hx, depth, floors, inside);
        return top;
    }

    private void furnish(Build b, int hx, int depth, int floors, Inside inside) {
        switch (inside) {
            case HOME -> {
                b.set(-hx + 1, 1, depth - 2, Material.CRAFTING_TABLE);
                b.facing(-hx + 1, 1, depth - 3, Material.FURNACE, BlockFace.EAST);
                b.set(-hx + 1, 1, 1, Material.BARREL);
                b.set(1, 1, depth / 2, Material.OAK_FENCE);
                b.set(1, 2, depth / 2, Material.WHITE_CARPET);
                b.stair(2, 1, depth / 2, Material.OAK_STAIRS, BlockFace.EAST, false);
                b.set(hx - 1, 1, 1, Material.POTTED_POPPY);
                int by = floors > 1 ? 5 : 1;
                b.bed(-hx + 1, by, 1, Material.RED_BED, BlockFace.NORTH);
                b.bed(-hx + 2, by, 1, Material.RED_BED, BlockFace.NORTH);
                if (floors > 1) {
                    b.bed(hx - 3, by, 1, Material.LIGHT_BLUE_BED, BlockFace.NORTH);
                    b.set(0, by, depth - 2, Material.CHEST);
                } else {
                    b.bed(hx - 2, by, 1, Material.LIGHT_BLUE_BED, BlockFace.SOUTH);
                }
                b.set(0, 1, 2, Material.RED_CARPET);
                b.set(0, 1, 3, Material.RED_CARPET);
            }
            case SHOP, STORE -> {
                for (int x = -hx + 1; x <= hx - 1; x++) {
                    if (Math.abs(x) <= 1) continue;
                    b.set(x, 1, depth - 3, Material.DARK_OAK_PLANKS);
                }
                for (int x = -hx + 1; x <= hx - 1; x++) {
                    b.set(x, 1, depth - 2, Material.BARREL);
                    b.set(x, 2, depth - 2, inside == Inside.STORE ? Material.CHISELED_BOOKSHELF : Material.BOOKSHELF);
                }
            }
            case RESTAURANT -> {
                for (int[] tb : new int[][]{{-2, 2}, {2, 2}, {-2, 5}, {2, 5}}) {
                    if (tb[1] >= depth - 2) continue;
                    b.set(tb[0], 1, tb[1], Material.SPRUCE_FENCE);
                    b.set(tb[0], 2, tb[1], Material.WHITE_CARPET);
                    b.stair(tb[0] - 1, 1, tb[1], Material.SPRUCE_STAIRS, BlockFace.WEST, false);
                    b.stair(tb[0] + 1, 1, tb[1], Material.SPRUCE_STAIRS, BlockFace.EAST, false);
                }
                b.facing(-hx + 1, 1, depth - 2, Material.SMOKER, BlockFace.EAST);
                b.facing(-hx + 2, 1, depth - 2, Material.FURNACE, BlockFace.NORTH);
                b.set(-hx + 3, 1, depth - 2, Material.CAULDRON);
                b.set(hx - 1, 1, depth - 2, Material.BARREL);
            }
            case BAKERY -> {
                b.facing(-hx + 1, 1, depth - 2, Material.SMOKER, BlockFace.NORTH);
                b.facing(-hx + 2, 1, depth - 2, Material.SMOKER, BlockFace.NORTH);
                for (int x = -hx + 1; x <= hx - 1; x++) if (Math.abs(x) > 1) b.set(x, 1, depth - 4, Material.SPRUCE_PLANKS);
                b.set(hx - 1, 1, depth - 2, Material.HAY_BLOCK);
                b.set(hx - 2, 1, depth - 2, Material.COMPOSTER);
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ build

    public String check(World world, int x, int y, int z) {
        this.w = world;
        this.cx = x;
        this.cy = y;
        this.cz = z;
        int tiles = 0, lo = 999, hi = -999, water = 0, cols = 0;
        for (int bx = (x - R - 6) >> 4; bx <= (x + R + 6) >> 4; bx++)
            for (int bz = (z - R - 6) >> 4; bz <= (z + R + 6) >> 4; bz++)
                for (var s : w.getChunkAt(bx, bz).getTileEntities()) if (Math.abs(s.getX() - x) <= R + 6 && Math.abs(s.getZ() - z) <= R + 6) tiles++;
        for (int dx = -R; dx <= R; dx += 4) {
            for (int dz = -R; dz <= R; dz += 4) {
                int top = w.getHighestBlockYAt(x + dx, z + dz, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
                lo = Math.min(lo, top);
                hi = Math.max(hi, top);
                if (w.getBlockAt(x + dx, top, z + dz).isLiquid()) water++;
                cols++;
            }
        }
        int ents = 0;
        for (Entity e : w.getNearbyEntities(new BoundingBox(x - R - 6, y - DOWN, z - R - 6, x + R + 7, y + UP, z + R + 7))) if (!(e instanceof Player) && !t.isOurs(e)) ents++;
        return "Meadowbrook site " + x + " " + y + " " + z + ": ground " + lo + ".." + hi + " (town floor " + y + "), " + (100 * water / Math.max(1, cols))
                + "% water, " + tiles + " block entities, " + ents + " entities in the area.";
    }

    public String build(World world, int x, int y, int z) throws IOException {
        if (built()) throw new IOException("Meadowbrook is already built. Run /mrtown meadowbrook undo first.");
        this.w = world;
        this.cx = x;
        this.cy = y;
        this.cz = z;
        int[] a = {cx - R - 6, cy - DOWN, cz - R - 6, cx + R + 6, cy + UP, cz + R + 6};
        long t0 = System.currentTimeMillis();
        Structure st = Bukkit.getStructureManager().createStructure();
        st.fill(new Location(w, a[0], a[1], a[2]), new BlockVector(a[3] - a[0] + 1, a[4] - a[1] + 1, a[5] - a[2] + 1), true);
        Bukkit.getStructureManager().saveStructure(structureFile(), st);
        YamlConfiguration meta = new YamlConfiguration();
        meta.set("world", w.getName());
        meta.set("area", List.of(a[0], a[1], a[2], a[3], a[4], a[5]));
        meta.set("centre", List.of(x, y, z));
        meta.set("created", System.currentTimeMillis());
        meta.save(metaFile());
        BoundingBox box = new BoundingBox(a[0], a[1], a[2], a[3] + 1, a[4] + 1, a[5] + 1);
        for (Entity e : w.getNearbyEntities(box)) if (!(e instanceof Player) && (!(e instanceof LivingEntity) || e instanceof ArmorStand)) e.remove();

        try {
            return buildAll(box, x, y, z, t0);
        } catch (RuntimeException ex) {
            t.plugin().getLogger().log(java.util.logging.Level.SEVERE, "Meadowbrook build failed, undoing", ex);
            undo();
            throw new IOException("Building failed (" + ex + "), so everything was put back as it was.");
        }
    }

    private String buildAll(BoundingBox box, int x, int y, int z, long t0) throws IOException {
        YamlConfiguration d = new YamlConfiguration();
        d.set("centre", List.of(x, y, z));
        d.set("world", w.getName());
        level();
        streets();
        squareAndClock(d);
        wall();
        List<Plots.Plot> plots = new ArrayList<>();
        business(plots);
        homes(plots);
        industry(plots, d);
        civic(d);
        lamps();
        for (Entity e : w.getNearbyEntities(box)) {
            if (e instanceof LivingEntity le && !(e instanceof Player) && !t.isOurs(e) && t.plugin().npcs().idOf(e) == null) lift(le);
        }
        // Register: police zone + jail, plots, protection.
        Jail jail = t.addZone(new TownManager.Zone(ID, "Meadowbrook", w.getName(), cx + 0.5, cz + 0.5, t.plugin().getConfig().getDouble("town.meadowbrook.police-radius", 200),
                t.plugin().getConfig().getDouble("town.meadowbrook.town-radius", 85)));
        List<Location> cells = new ArrayList<>();
        for (String s : d.getStringList("jail.cells")) cells.add(loc(s));
        Location rel = loc(d.getString("jail.release"));
        String boardId = d.getString("jail.board");
        jail.setup(cells, rel, null, boardId == null ? null : UUID.fromString(boardId));
        t.plots().registerTown(ID, w, new BoundingBox(cx - R - 1, cy - DOWN, cz - R - 1, cx + R + 2, cy + UP, cz + R + 2));
        for (Plots.Plot p : plots) t.plots().addPlot(p);
        d.set("clock", clock == null ? null : clock.toString());
        d.save(file);
        return "Meadowbrook built around " + x + " " + y + " " + z + " in " + (System.currentTimeMillis() - t0) + "ms: " + plots.size() + " plots, "
                + cells.size() + " cells. Undo with /mrtown meadowbrook undo.";
    }

    private Location loc(String s) {
        if (s == null) return null;
        String[] p = s.split(",");
        return new Location(w, Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]), p.length > 3 ? Float.parseFloat(p[3]) : 0, 0);
    }

    private String str(double x, double y, double z, float yaw) {
        return x + "," + y + "," + z + "," + yaw;
    }

    /** Flatten the site: floor at cy, clear above, fill below. */
    private void level() {
        for (int dx = -R - 5; dx <= R + 5; dx++) {
            for (int dz = -R - 5; dz <= R + 5; dz++) {
                boolean inside = Math.abs(dx) <= R && Math.abs(dz) <= R;
                int edge = Math.max(Math.abs(dx), Math.abs(dz)) - R; // 1..5 outside
                for (int dy = UP; dy >= 1; dy--) {
                    if (!inside && dy <= edge - 1) break; // gentle slope down outside the wall
                    Block b = at(dx, dy, dz);
                    if (!b.getType().isAir()) b.setType(Material.AIR, false);
                }
                if (!inside) continue;
                for (int dy = -DOWN; dy < 0; dy++) {
                    Block b = at(dx, dy, dz);
                    if (b.isPassable() || b.isLiquid()) b.setType(dy >= -3 ? Material.DIRT : Material.STONE, false);
                }
                set(dx, 0, dz, Material.GRASS_BLOCK);
            }
        }
    }

    private void streets() {
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                if (!street(dx, dz)) continue;
                int ax = Math.abs(dx), az = Math.abs(dz);
                Material m;
                if (square(dx, dz)) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    m = d > 13 ? Material.POLISHED_ANDESITE : ((dx + dz) & 1) == 0 ? Material.STONE_BRICKS : Material.POLISHED_ANDESITE;
                    if (d >= 6 && d < 7) m = Material.CHISELED_STONE_BRICKS;
                } else if (ax <= 3) {
                    m = ax == 3 ? Material.SMOOTH_STONE : ax == 0 && az % 6 == 0 ? Material.CHISELED_STONE_BRICKS : Material.STONE_BRICKS;
                } else if (az <= 3) {
                    m = az == 3 ? Material.SMOOTH_STONE : az == 0 && ax % 6 == 0 ? Material.CHISELED_STONE_BRICKS : Material.STONE_BRICKS;
                } else {
                    boolean side = ax == 31 || ax == 35 || az == 31 || az == 35;
                    m = side ? Material.SMOOTH_STONE : Material.ANDESITE;
                }
                set(dx, 0, dz, m);
            }
        }
    }

    private void squareAndClock(YamlConfiguration d) {
        // Clock tower: 7x7, 22 high, with a bell and lanterns on top.
        Build b = build(0, 0, BlockFace.NORTH);
        b.fill(-3, 0, -3, 3, 0, 3, Material.POLISHED_DEEPSLATE);
        for (int y = 1; y <= 20; y++) {
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    boolean edge = Math.abs(x) == 3 || Math.abs(z) == 3;
                    boolean corner = Math.abs(x) == 3 && Math.abs(z) == 3;
                    if (!edge) continue;
                    Material m = corner ? Material.POLISHED_DEEPSLATE : y % 6 == 0 ? Material.CHISELED_STONE_BRICKS : Material.STONE_BRICKS;
                    if (!corner && y >= 15 && y <= 17 && (x == 0 || z == 0)) m = Material.AIR;
                    b.set(x, y, z, m);
                }
            }
        }
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) b.set(x, 21, z, Material.DEEPSLATE_TILES);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) b.set(x, 22, z, Material.DEEPSLATE_TILES);
        b.set(0, 23, 0, Material.LIGHTNING_ROD);
        b.set(0, 18, 0, Material.BELL);
        for (int[] c : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) b.lantern(c[0], 21, c[1], false);
        b.set(0, 1, -3, Material.AIR);
        b.set(0, 2, -3, Material.AIR);
        clock = w.spawn(new Location(w, cx + 0.5, cy + 12.5, cz - 3.6), TextDisplay.class, td -> {
            td.text(SmpPlugin.mm("<gold><bold>MEADOWBROOK"));
            td.setBillboard(Display.Billboard.CENTER);
            td.setAlignment(TextDisplay.TextAlignment.CENTER);
            td.setShadowed(true);
            td.setBackgroundColor(Color.fromARGB(150, 20, 20, 20));
            td.setPersistent(true);
            td.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        }).getUniqueId();
        // Market stalls on the diagonals, benches and planters.
        String[] awnings = {"RED", "YELLOW", "LIME", "LIGHT_BLUE"};
        int i = 0;
        for (int[] c : new int[][]{{9, 9}, {-9, 9}, {9, -9}, {-9, -9}}) {
            Material wool = Material.matchMaterial(awnings[i++] + "_WOOL");
            for (int x = -1; x <= 1; x++) {
                set(c[0] + x, 1, c[1], Material.SPRUCE_PLANKS);
                set(c[0] + x, 4, c[1], wool);
                set(c[0] + x, 4, c[1] - 1, wool);
                set(c[0] + x, 4, c[1] + 1, wool);
            }
            for (int s : new int[]{-1, 1}) for (int y = 1; y <= 3; y++) set(c[0] + s, y, c[1] + 1, Material.SPRUCE_FENCE);
            set(c[0], 2, c[1], Material.BARREL);
        }
        for (int[] c : new int[][]{{5, -11}, {-5, -11}, {11, 5}, {-11, 5}}) {
            Build bb = build(c[0], c[1], BlockFace.NORTH);
            for (int k = -1; k <= 1; k++) bb.stair(k, 1, 0, Material.SPRUCE_STAIRS, BlockFace.SOUTH, false);
        }
        for (int[] c : new int[][]{{11, -6}, {-11, -6}, {6, 11}, {-6, 11}}) {
            set(c[0], 0, c[1], Material.GRASS_BLOCK);
            set(c[0], 1, c[1], Material.AIR);
            w.generateTree(at(c[0], 1, c[1]).getLocation(), TreeType.AZALEA);
        }
        hologram(0.5, 25.5, 0.5, "<gold><bold>MEADOWBROOK</bold>\n<gray>Market City", 1.4f);
        npc("landagent", "<green>Land Agent Pim", "<gray>Plots for sale and rent", 0.5, 16.5, 180, "#3A6B35",
                Map.of(EquipmentSlot.HAND, Material.MAP, EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE, EquipmentSlot.LEGS, Material.LEATHER_LEGGINGS), "command:plot list meadowbrook");
        npc("mb_crier", "<yellow>Town Crier Bess", "<gray>News of Meadowbrook", -16.5, 0.5, 270, "#B5651D",
                Map.of(EquipmentSlot.HAND, Material.BELL, EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE), null);
        d.set("square", str(cx + 0.5, cy + 1, cz + 8.5, 0));
    }

    private void wall() {
        for (int dx = -R - 1; dx <= R + 1; dx++) {
            for (int dz = -R - 1; dz <= R + 1; dz++) {
                if (Math.abs(dx) != R + 1 && Math.abs(dz) != R + 1) continue;
                boolean gate = Math.abs(dx) <= 3 || Math.abs(dz) <= 3;
                // foundations down to the ground (the wall may stand on a slope or the shore)
                for (int dy = 0; dy >= -DOWN; dy--) {
                    Block b = at(dx, dy, dz);
                    if (dy < 0 && !b.isPassable() && !b.isLiquid()) break;
                    b.setType(Material.STONE_BRICKS, false);
                }
                if (gate) {
                    set(dx, 0, dz, Material.STONE_BRICKS);
                    boolean post = Math.abs(dx) == 3 || Math.abs(dz) == 3;
                    if (post) for (int dy = 1; dy <= 5; dy++) set(dx, dy, dz, Material.POLISHED_ANDESITE);
                    else set(dx, 5, dz, Material.STONE_BRICK_SLAB);
                    if (post) set(dx, 6, dz, Material.LANTERN);
                    continue;
                }
                set(dx, 1, dz, Material.STONE_BRICKS);
                set(dx, 2, dz, (dx + dz) % 9 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
                set(dx, 3, dz, Material.STONE_BRICK_WALL);
            }
        }
    }

    private void lamps() {
        for (int k = -R + 4; k <= R - 4; k += 8) {
            if (Math.abs(k) <= 4 || Math.abs(k) >= 30 && Math.abs(k) <= 36) continue; // not in crossings
            for (int[] p : new int[][]{{-3, k}, {3, k}, {k, -3}, {k, 3}}) {
                if (square(p[0], p[1])) continue;
                if (at(p[0], 1, p[1]).getType() != Material.AIR) continue;
                set(p[0], 1, p[1], Material.POLISHED_DEEPSLATE_WALL);
                set(p[0], 2, p[1], Material.DARK_OAK_FENCE);
                set(p[0], 3, p[1], Material.DARK_OAK_FENCE);
                set(p[0], 4, p[1], Material.LANTERN);
            }
        }
    }

    // ------------------------------------------------------------------ quarters

    /** The 2x2 plot grid of a quarter's lot, skipping the corner the square takes. */
    private List<int[]> lotPlots(int sx, int sz, boolean outerX, boolean outerZ) {
        int[][] xs = outerX ? new int[][]{{36, 49}, {51, 64}} : new int[][]{{4, 16}, {18, 30}};
        int[][] zs = outerZ ? new int[][]{{36, 49}, {51, 64}} : new int[][]{{4, 16}, {18, 30}};
        List<int[]> out = new ArrayList<>();
        for (int[] xr : xs) {
            for (int[] zr : zs) {
                if (!outerX && !outerZ && xr[0] == 4 && zr[0] == 4) continue;
                int x0 = sx > 0 ? xr[0] : -xr[1], x1 = sx > 0 ? xr[1] : -xr[0];
                int z0 = sz > 0 ? zr[0] : -zr[1], z1 = sz > 0 ? zr[1] : -zr[0];
                out.add(new int[]{x0, z0, x1, z1});
            }
        }
        return out;
    }

    private Plots.Plot plot(String id, int[] rect, Plots.Kind kind, boolean built, boolean npc, String label, double price, double rent) {
        Plots.Plot p = new Plots.Plot();
        p.id = id;
        p.town = ID;
        p.world = w.getName();
        p.x0 = cx + rect[0];
        p.z0 = cz + rect[1];
        p.x1 = cx + rect[2];
        p.z1 = cz + rect[3];
        p.floor = cy;
        p.kind = kind;
        p.built = built;
        p.npc = npc;
        p.label = label == null ? "" : label;
        p.price = price;
        p.rent = rent;
        return p;
    }

    private void bare(int[] rect) {
        for (int x = rect[0]; x <= rect[2]; x++) {
            for (int z = rect[1]; z <= rect[3]; z++) {
                boolean edge = x == rect[0] || x == rect[2] || z == rect[1] || z == rect[3];
                set(x, 0, z, edge ? Material.COARSE_DIRT : Material.GRASS_BLOCK);
            }
        }
        for (int[] c : new int[][]{{rect[0], rect[1]}, {rect[2], rect[1]}, {rect[0], rect[3]}, {rect[2], rect[3]}}) {
            set(c[0], 1, c[1], Material.OAK_FENCE);
            set(c[0], 2, c[1], Material.TORCH);
        }
    }

    private void business(List<Plots.Plot> out) {
        var c = t.plugin().getConfig();
        double builtPrice = c.getDouble("town.plots.business-built-price", 60000), barePrice = c.getDouble("town.plots.business-bare-price", 25000);
        double builtRent = c.getDouble("town.plots.business-built-rent", 1500), bareRent = c.getDouble("town.plots.business-bare-rent", 600);
        List<int[]> rects = new ArrayList<>();
        rects.addAll(lotPlots(1, 1, false, false));
        rects.addAll(lotPlots(1, 1, true, false));
        rects.addAll(lotPlots(1, 1, false, true));
        rects.addAll(lotPlots(1, 1, true, true));
        String[][] npcShops = {{"The Copper Kettle", "RESTAURANT", "Chef Margot"}, {"Meadowbrook General Store", "STORE", "Shopkeeper Alden"}, {"Bramble Bakery", "BAKERY", "Baker Wynn"}};
        int n = 0;
        for (int[] rc : rects) {
            n++;
            String id = "b" + n;
            BlockFace f = faceStreet(rc[0], rc[1], rc[2], rc[3]);
            if (n <= 3) {
                String[] s = npcShops[n - 1];
                Build b = inRect(rc[0], rc[1], rc[2], rc[3], f, 1);
                building(b, 11, 10, 2, SHOP, Inside.valueOf(s[1]), true);
                awning(b, 11);
                b.finish();
                npcAt(b, "mb_shop" + n, "<gold>" + s[2], "<gray>" + s[0], 0, 6, s[1].equals("RESTAURANT") ? Material.BOWL : s[1].equals("BAKERY") ? Material.BREAD : Material.EMERALD);
                out.add(plot(id, rc, Plots.Kind.BUSINESS, true, true, s[0], 0, 0));
            } else if (n % 2 == 0) {
                Build b = inRect(rc[0], rc[1], rc[2], rc[3], f, 1);
                building(b, 11, 10, 2, SHOP, Inside.SHOP, true);
                awning(b, 11);
                b.finish();
                out.add(plot(id, rc, Plots.Kind.BUSINESS, true, false, null, builtPrice, builtRent));
            } else {
                bare(rc);
                out.add(plot(id, rc, Plots.Kind.BUSINESS, false, false, null, barePrice, bareRent));
            }
        }
    }

    private void awning(Build b, int width) {
        Material[] stripes = {Material.RED_WOOL, Material.WHITE_WOOL};
        for (int x = -width / 2; x <= width / 2; x++) b.set(x, 4, -1, stripes[Math.abs(x) % 2]);
    }

    private void homes(List<Plots.Plot> out) {
        var c = t.plugin().getConfig();
        double builtPrice = c.getDouble("town.plots.home-built-price", 35000), barePrice = c.getDouble("town.plots.home-bare-price", 15000);
        double builtRent = c.getDouble("town.plots.home-built-rent", 800), bareRent = c.getDouble("town.plots.home-bare-rent", 300);
        List<int[]> rects = new ArrayList<>();
        rects.addAll(lotPlots(1, -1, false, false));
        rects.addAll(lotPlots(1, -1, true, false));
        rects.addAll(lotPlots(1, -1, false, true));
        rects.addAll(lotPlots(1, -1, true, true));
        int n = 0;
        for (int[] rc : rects) {
            n++;
            String id = "h" + n;
            BlockFace f = faceStreet(rc[0], rc[1], rc[2], rc[3]);
            boolean forSale = n % 4 == 0;
            boolean bare = n == 8 || n == 12;
            if (bare) {
                this.bare(rc);
                out.add(plot(id, rc, Plots.Kind.HOME, false, false, null, barePrice, bareRent));
                continue;
            }
            Build b = inRect(rc[0], rc[1], rc[2], rc[3], f, 2);
            Palette p = HOMES[r.nextInt(HOMES.length)];
            building(b, 9, 8, 1 + r.nextInt(2), p, Inside.HOME, false);
            // little garden: flowers by the door, a fence line at the front
            Material[] fl = {Material.POPPY, Material.DANDELION, Material.CORNFLOWER, Material.ALLIUM, Material.OXEYE_DAISY};
            for (int x = -4; x <= 4; x++) {
                if (Math.abs(x) <= 1) continue;
                b.set(x, 1, -1, fl[r.nextInt(fl.length)]);
            }
            b.finish();
            out.add(plot(id, rc, Plots.Kind.HOME, true, !forSale, forSale ? null : "Family home", forSale ? builtPrice : 0, forSale ? builtRent : 0));
        }
    }

    private void industry(List<Plots.Plot> out, YamlConfiguration d) {
        // Inner lot: wheat fields with water channels.
        for (int x = -30; x <= -4; x++) {
            for (int z = -30; z <= -4; z++) {
                if (square(x, z)) continue;
                boolean channel = (x + 30) % 9 == 4;
                if (channel) {
                    set(x, 0, z, Material.WATER);
                    continue;
                }
                set(x, 0, z, Material.FARMLAND);
                Block crop = at(x, 1, z);
                crop.setType(Material.WHEAT, false);
                if (crop.getBlockData() instanceof Ageable ag) {
                    ag.setAge(r.nextInt(ag.getMaximumAge() + 1));
                    crop.setBlockData(ag, false);
                }
            }
        }
        // Wool factory with a sheep pasture.
        Build wf = build(-50, -6, BlockFace.SOUTH);
        building(wf, 21, 12, 1, new Palette(Material.WHITE_TERRACOTTA, Material.SPRUCE_LOG, Material.SPRUCE_STAIRS, Material.SPRUCE_PLANKS, Material.SPRUCE_PLANKS, Material.COBBLESTONE), Inside.NONE, true);
        for (int x = -9; x <= 9; x += 3) {
            wf.facing(x, 1, 4, Material.LOOM, BlockFace.SOUTH);
            wf.set(x, 1, 8, Material.WHITE_WOOL);
            wf.set(x, 2, 8, Material.WHITE_WOOL);
        }
        wf.finish();
        hologram(-49.5, 7.5, -12.5, "<white><bold>Meadowbrook Wool Mill", 1f);
        for (int x = -62; x <= -38; x++) {
            for (int z = -29; z <= -20; z++) {
                boolean edge = x == -62 || x == -38 || z == -29 || z == -20;
                set(x, 0, z, Material.GRASS_BLOCK);
                if (edge) set(x, 1, z, Material.OAK_FENCE);
            }
        }
        set(-50, 1, -20, Material.OAK_FENCE_GATE);
        for (int i = 0; i < 6; i++) {
            Location l = new Location(w, cx - 55 + i * 3 + 0.5, cy + 1, cz - 25 + 0.5);
            w.spawn(l, Sheep.class, s -> s.setPersistent(true));
        }
        npc("mb_millowner", "<white>Millmaster Rhea", "<gray>Wool Mill (town business)", cx - 49.5, cz - 4.5, 0, "#DDDDDD",
                Map.of(EquipmentSlot.HAND, Material.SHEARS, EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE), null);
        out.add(plot("i5", new int[]{-64, -30, -36, -4}, Plots.Kind.INDUSTRY, true, true, "Meadowbrook Wool Mill", 0, 0));
        // Lumber mill.
        Build lm = build(-17, -36, BlockFace.SOUTH);
        building(lm, 17, 12, 1, new Palette(Material.SPRUCE_PLANKS, Material.STRIPPED_SPRUCE_LOG, Material.SPRUCE_STAIRS, Material.SPRUCE_PLANKS, Material.SPRUCE_PLANKS, Material.COBBLESTONE), Inside.NONE, true);
        for (int x = -6; x <= 6; x += 3) lm.set(x, 1, 5, Material.STONECUTTER);
        lm.finish();
        for (int x = -28; x <= -6; x += 2) {
            for (int y = 1; y <= 2; y++) {
                Block b = at(x, y, -54);
                b.setType(Material.OAK_LOG, false);
                if (b.getBlockData() instanceof org.bukkit.block.data.Orientable o) {
                    o.setAxis(Axis.Z);
                    b.setBlockData(o, false);
                }
            }
        }
        hologram(-16.5, 7.5, -37.5, "<gold><bold>Brookside Lumber", 1f);
        npc("mb_lumber", "<gold>Foreman Garrick", "<gray>Brookside Lumber (town business)", cx - 16.5, cz - 34.5, 0, "#6B4A2B",
                Map.of(EquipmentSlot.HAND, Material.IRON_AXE, EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE), null);
        out.add(plot("i6", new int[]{-30, -64, -4, -36}, Plots.Kind.INDUSTRY, true, true, "Brookside Lumber", 0, 0));
        // Workshops for players: 2 built warehouses, 2 bare.
        var c = t.plugin().getConfig();
        int n = 0;
        for (int[] rc : lotPlots(-1, -1, true, true)) {
            n++;
            BlockFace f = faceStreet(rc[0], rc[1], rc[2], rc[3]);
            if (n % 2 == 1) {
                Build b = inRect(rc[0], rc[1], rc[2], rc[3], f, 1);
                building(b, 11, 11, 1, new Palette(Material.STONE_BRICKS, Material.SPRUCE_LOG, Material.SPRUCE_STAIRS, Material.SPRUCE_PLANKS, Material.SPRUCE_PLANKS, Material.COBBLESTONE), Inside.NONE, false);
                b.finish();
                out.add(plot("i" + n, rc, Plots.Kind.INDUSTRY, true, false, null, c.getDouble("town.plots.industry-built-price", 50000), c.getDouble("town.plots.industry-built-rent", 1200)));
            } else {
                bare(rc);
                out.add(plot("i" + n, rc, Plots.Kind.INDUSTRY, false, false, null, c.getDouble("town.plots.industry-bare-price", 20000), c.getDouble("town.plots.industry-bare-rent", 500)));
            }
        }
        d.set("industry.wool", str(cx - 49.5, cy + 1, cz - 4.5, 0));
    }

    private void civic(YamlConfiguration d) {
        // Courthouse: south-west inner lot, facing north onto the cross street.
        Build ct = build(-20, 12, BlockFace.NORTH);
        building(ct, 19, 18, 2, CIVIC, Inside.NONE, true);
        ct.fill(-8, 4, 1, 8, 4, 16, Material.AIR); // double-height courtroom
        for (int x = -9; x <= 9; x += 3) for (int y = 1; y <= 7; y++) ct.set(x, y, -1, Material.QUARTZ_PILLAR);
        for (int x = -10; x <= 10; x++) ct.slab(x, 8, -1, Material.SMOOTH_QUARTZ_SLAB, false);
        // judge's bench at the back, raised
        ct.fill(-3, 1, 14, 3, 1, 16, Material.POLISHED_ANDESITE);
        ct.fill(-3, 2, 14, 3, 2, 14, Material.DARK_OAK_PLANKS);
        ct.stair(0, 2, 16, Material.DARK_OAK_STAIRS, BlockFace.SOUTH, false);
        // tables: defence (left) and prosecution (right)
        for (int x : new int[]{-4, -3, 3, 4}) ct.slab(x, 1, 10, Material.DARK_OAK_SLAB, true);
        // jury box along the east wall
        for (int z = 7; z <= 12; z++) {
            ct.stair(7, 1, z, Material.SPRUCE_STAIRS, BlockFace.EAST, false);
            ct.stair(8, 2, z, Material.SPRUCE_STAIRS, BlockFace.EAST, false);
            ct.set(8, 1, z, Material.SPRUCE_PLANKS);
        }
        // public gallery benches
        for (int z = 3; z <= 6; z++) for (int x = -7; x <= 4; x++) if (Math.abs(x) > 1) ct.stair(x, 1, z, Material.DARK_OAK_STAIRS, BlockFace.NORTH, false);
        ct.finish();
        hologram(-19.5, 12.5, 10.5, "<gold><bold>⚖ MEADOWBROOK COURTHOUSE ⚖", 1.2f);
        npc("mb_judge", "<gold>Judge Harlow", "<gray>Courthouse", cx - 19.5, cz + 12 + 15.5, 180, "#1A1A1A",
                Map.of(EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE, EquipmentSlot.LEGS, Material.LEATHER_LEGGINGS, EquipmentSlot.HAND, Material.WRITABLE_BOOK), null);
        t.plugin().npcs().get("mb_judge").y = cy + 2;
        t.plugin().npcs().save();
        d.set("court.judge", str(cx - 19.5, cy + 2, cz + 12 + 15.5, 180));
        d.set("court.defendant", str(cx - 19.5 - 3.5, cy + 1, cz + 12 + 9.5, 180));
        d.set("court.defence", str(cx - 19.5 - 4.5, cy + 1, cz + 12 + 9.5, 180));
        d.set("court.prosecution", str(cx - 19.5 + 3.5, cy + 1, cz + 12 + 9.5, 180));
        List<String> jury = new ArrayList<>();
        for (int z = 7; z <= 12; z++) jury.add(str(cx - 19.5 + 7, cy + 1, cz + 12 + z + 0.5, 90));
        d.set("court.jury", jury);
        d.set("court.gallery", str(cx - 19.5, cy + 1, cz + 12 + 4.5, 180));

        // Herald (newspaper) and bank branch: west outer lot, facing north.
        Build hd = build(-57, 6, BlockFace.NORTH);
        building(hd, 11, 10, 2, new Palette(Material.DARK_OAK_PLANKS, Material.STRIPPED_DARK_OAK_LOG, Material.DEEPSLATE_TILE_STAIRS, Material.DEEPSLATE_TILES, Material.SPRUCE_PLANKS, Material.STONE_BRICKS), Inside.NONE, true);
        for (int x = -4; x <= 4; x += 2) hd.facing(x, 1, 6, Material.LOOM, BlockFace.SOUTH);
        hd.set(-3, 1, 3, Material.LECTERN);
        hd.set(3, 1, 3, Material.CARTOGRAPHY_TABLE);
        hd.finish();
        hologram(-56.5, 11.5, 5.5, "<white><bold>THE MEADOWBROOK HERALD", 1f);
        npc("mb_editor", "<white>Editor Penrose", "<gray>The Meadowbrook Herald", cx - 56.5, cz + 6 + 4.5, 180, "#3A3A3A",
                Map.of(EquipmentSlot.HAND, Material.PAPER, EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE), "command:herald");
        Build bk = build(-43, 6, BlockFace.NORTH);
        building(bk, 11, 10, 2, new Palette(Material.QUARTZ_BRICKS, Material.QUARTZ_PILLAR, Material.DEEPSLATE_TILE_STAIRS, Material.DEEPSLATE_TILES, Material.SMOOTH_QUARTZ, Material.POLISHED_DEEPSLATE), Inside.NONE, true);
        for (int x = -4; x <= 4; x++) if (Math.abs(x) > 1) {
            bk.set(x, 1, 5, Material.DARK_OAK_PLANKS);
            bk.set(x, 2, 5, Material.IRON_BARS);
        }
        bk.fill(-1, 1, 8, 1, 3, 8, Material.IRON_BLOCK);
        bk.finish();
        hologram(-42.5, 11.5, 5.5, "<gold><bold>$ MEADOWBROOK SAVINGS & LOANS $", 1f);
        npc("mb_banker", "<gold>Banker Oakes", "<gray>Loans · Savings", cx - 42.5, cz + 6 + 6.5, 180, "#2E4A2E",
                Map.of(EquipmentSlot.HAND, Material.GOLD_INGOT, EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE), "command:loan");

        // Sheriff's office with three cells: west outer lot, south half, facing south onto the ring street.
        Build sh = build(-50, 30, BlockFace.SOUTH);
        building(sh, 25, 10, 1, CIVIC, Inside.NONE, false);
        List<String> cells = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            int x0 = -11 + k * 4;
            for (int z = 5; z <= 8; z++) sh.set(x0 + 3, 1, z, Material.STONE_BRICKS);
            for (int z = 5; z <= 8; z++) for (int y = 2; y <= 3; y++) sh.set(x0 + 3, y, z, Material.STONE_BRICKS);
            for (int x = x0; x <= x0 + 2; x++) for (int y = 1; y <= 3; y++) sh.set(x, y, 5, Material.IRON_BARS);
            sh.slab(x0, 1, 8, Material.SPRUCE_SLAB, false);
            Block cellBlock = sh.block(x0 + 1, 1, 7);
            cells.add(str(cellBlock.getX() + 0.5, cellBlock.getY(), cellBlock.getZ() + 0.5, 0));
        }
        sh.set(5, 1, 3, Material.LECTERN);
        sh.fill(4, 1, 6, 8, 1, 6, Material.DARK_OAK_PLANKS);
        sh.finish();
        Block out = sh.block(0, 1, -2);
        d.set("jail.cells", cells);
        d.set("jail.release", str(out.getX() + 0.5, out.getY(), out.getZ() + 0.5, 0));
        hologram(-49.5, 7.5, 31.5, "<blue><bold>★ SHERIFF ★</bold>\n<gray>Meadowbrook Watch", 1f);
        Block sheriffSpot = sh.block(6, 1, 4);
        npc("mb_sheriff", "<blue>Sheriff Calloway", "<gray>Meadowbrook Watch", sheriffSpot.getX() + 0.5, sheriffSpot.getZ() + 0.5, 0, "#1F2A44",
                Map.of(EquipmentSlot.HEAD, Material.IRON_HELMET, EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE, EquipmentSlot.HAND, Material.IRON_SWORD), null);

        // Grand Hotel: south-west lot (4 floors).
        Build ht = build(-17, 37, BlockFace.NORTH);
        building(ht, 21, 18, 4, new Palette(Material.RED_TERRACOTTA, Material.DARK_OAK_LOG, Material.DEEPSLATE_TILE_STAIRS, Material.DEEPSLATE_TILES, Material.DARK_OAK_PLANKS, Material.STONE_BRICKS), Inside.NONE, true);
        for (int f = 1; f < 4; f++) {
            int y = f * 4 + 1;
            for (int x = -8; x <= 8; x += 4) ht.bed(x, y, 3, Material.WHITE_BED, BlockFace.NORTH);
            for (int x = -8; x <= 8; x += 4) ht.bed(x, y, 14, Material.WHITE_BED, BlockFace.SOUTH);
        }
        ht.fill(-3, 1, 4, 3, 1, 4, Material.DARK_OAK_PLANKS);
        ht.finish();
        hologram(-16.5, 20.5, 36.5, "<gold><bold>THE GRAND MEADOW HOTEL", 1.1f);
        npc("mb_hotel", "<gold>Concierge Ivy", "<gray>The Grand Meadow Hotel (town business)", cx - 16.5, cz + 37 + 6.5, 180, "#7A1F1F",
                Map.of(EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE, EquipmentSlot.HAND, Material.BELL), null);

        // Town hall with a little park beside it.
        Build th = build(-50, 40, BlockFace.NORTH);
        building(th, 19, 12, 2, CIVIC, Inside.NONE, true);
        th.finish();
        hologram(-49.5, 12.5, 39.5, "<white><bold>TOWN HALL", 1f);
        npc("mb_mayor", "<gold>Mayor Thornbury", "<gray>Town Hall", cx - 49.5, cz + 40 + 6.5, 180, "#4A2E5A",
                Map.of(EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE, EquipmentSlot.LEGS, Material.LEATHER_LEGGINGS, EquipmentSlot.HEAD, Material.GOLDEN_HELMET), null);
        for (int x = -62; x <= -38; x++) {
            for (int z = 54; z <= 63; z++) {
                set(x, 0, z, Material.GRASS_BLOCK);
                if ((x * 7 + z * 3) % 11 == 0) set(x, 1, z, Material.SHORT_GRASS);
            }
        }
        for (int[] tr : new int[][]{{-58, 58}, {-50, 60}, {-42, 57}}) w.generateTree(at(tr[0], 1, tr[1]).getLocation(), TreeType.BIRCH);
        Leaves hedge = (Leaves) Material.OAK_LEAVES.createBlockData();
        hedge.setPersistent(true);
        for (int x = -62; x <= -38; x++) at(x, 1, 53).setBlockData(hedge, false);
        d.set("station", str(cx + 0.5, cy + 1, cz + R + 2.5, 0));
    }

    // ------------------------------------------------------------------ NPCs, holograms, clock

    private void npc(String id, String name, String title, double x, double z, float yaw, String leather, Map<EquipmentSlot, Material> gear, String action) {
        NpcDef d = new NpcDef();
        d.id = id;
        d.name = name;
        d.title = title;
        d.world = w.getName();
        d.x = x;
        d.y = cy + 1;
        d.z = z;
        d.yaw = yaw;
        d.group = ID;
        d.action = action;
        d.equipment.putAll(gear);
        if (leather != null) d.leatherColor = Color.fromRGB(Integer.parseInt(leather.substring(1), 16));
        t.plugin().npcs().put(d);
    }

    /** A shopkeeper standing behind the counter of a generated shop. */
    private void npcAt(Build b, String id, String name, String title, int lx, int lz, Material hand) {
        Block spot = b.block(lx, 1, lz);
        float yaw = switch (b.front) {
            case SOUTH -> 0;
            case EAST -> 270;
            case WEST -> 90;
            default -> 180;
        };
        npc(id, name, title, spot.getX() + 0.5, spot.getZ() + 0.5, yaw, "#6B4A2B", Map.of(EquipmentSlot.HAND, hand, EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE), null);
    }

    private void hologram(double dx, double dy, double dz, String text, float scale) {
        w.spawn(new Location(w, cx + dx, cy + dy, cz + dz), TextDisplay.class, d -> {
            d.text(SmpPlugin.mm(text));
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setBackgroundColor(Color.fromARGB(100, 0, 0, 0));
            d.setPersistent(true);
            if (scale != 1f) {
                var tr = d.getTransformation();
                d.setTransformation(new org.bukkit.util.Transformation(tr.getTranslation(), tr.getLeftRotation(), new org.joml.Vector3f(scale), tr.getRightRotation()));
            }
            d.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /** The clock on the tower shows the in-game time (called every few seconds). */
    void tickClock() {
        if (clock == null) return;
        Entity e = Bukkit.getEntity(clock);
        if (!(e instanceof TextDisplay td)) return;
        long time = (e.getWorld().getTime() + 6000) % 24000;
        int h = (int) (time / 1000), m = (int) (time % 1000 * 60 / 1000);
        td.text(SmpPlugin.mm("<gold><bold>" + String.format("%02d:%02d", h, m) + "</bold>\n<gray>Meadowbrook"));
    }

    private void lift(LivingEntity e) {
        Location l = e.getLocation();
        int x = l.getBlockX(), z = l.getBlockZ();
        if (w.getBlockAt(x, l.getBlockY(), z).isPassable() && w.getBlockAt(x, l.getBlockY() + 1, z).isPassable()) return;
        for (int y = l.getBlockY() + 1; y <= cy + UP; y++) {
            if (w.getBlockAt(x, y, z).isPassable() && w.getBlockAt(x, y + 1, z).isPassable() && !w.getBlockAt(x, y - 1, z).isPassable()) {
                e.teleport(new Location(w, l.getX(), y, l.getZ()));
                return;
            }
        }
    }

    // ------------------------------------------------------------------ undo

    public String undo() throws IOException {
        if (!built()) throw new IOException("Meadowbrook isn't built.");
        YamlConfiguration meta = YamlConfiguration.loadConfiguration(metaFile());
        World uw = Bukkit.getWorld(meta.getString("world", ""));
        if (uw == null) throw new IOException("World not loaded.");
        List<Integer> a = meta.getIntegerList("area");
        BoundingBox box = new BoundingBox(a.get(0), a.get(1), a.get(2), a.get(3) + 1, a.get(4) + 1, a.get(5) + 1);
        for (Player p : Bukkit.getOnlinePlayers()) {
            TownData d = t.data(p);
            if (d.jailed() && ID.equals(d.jailTown)) t.release(p, "The Meadowbrook jail is closing.");
        }
        Structure st = Bukkit.getStructureManager().loadStructure(structureFile());
        for (Entity e : uw.getNearbyEntities(box)) {
            if (e.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) e.remove();
            else if (e instanceof Sheep) e.remove();
        }
        t.plugin().npcs().removeGroup(ID);
        t.plots().removeTown(ID);
        t.removeZone(ID);
        for (int y = a.get(4); y >= a.get(1); y--) for (int x = a.get(0); x <= a.get(3); x++) for (int z = a.get(2); z <= a.get(5); z++) uw.getBlockAt(x, y, z).setType(Material.AIR, false);
        EntityTransformer onlyLoose = (region, x, y, z, entity, allowed) -> !(entity instanceof LivingEntity) || entity instanceof ArmorStand;
        st.place(new Location(uw, a.get(0), a.get(1), a.get(2)), true, StructureRotation.NONE, Mirror.NONE, 0, 1.0f, new Random(), List.of(), List.of(onlyLoose));
        File done = new File(t.plugin().getDataFolder(), "meadowbrook-undone-" + System.currentTimeMillis() + ".nbt");
        structureFile().renameTo(done);
        metaFile().delete();
        file.delete();
        clock = null;
        return "Meadowbrook removed and the land restored. Snapshot kept as " + done.getName() + ".";
    }

}
