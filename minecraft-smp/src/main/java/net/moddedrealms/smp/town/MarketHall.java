package net.moddedrealms.smp.town;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.moddedrealms.smp.SmpPlugin;
import net.moddedrealms.smp.npc.NpcDef;
import net.moddedrealms.smp.town.Market.Cat;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;
import org.bukkit.util.BoundingBox;

/**
 * The market hall east of the spawn plaza: an open timber hall on stone posts under a long half-pitched roof. Eight
 * vendors line the sides (one per kind of ware), the broker's booth with the price board stands in the middle, and
 * twelve stalls in four rows are for players to rent. Saved first and reversible with /mrtown market undo, like the
 * bank. Local coordinates: x across (-20..20), z from the front (0, facing the plaza) to the back (30).
 */
public final class MarketHall {
    static final String ID = "market";
    private static final int HX = 20, DEPTH = 30;
    /** Vendor stalls: side (-1 left, +1 right) and first z of a 5-wide stall. */
    private static final int[][] VENDOR_SPOTS = {{-1, 1}, {-1, 6}, {-1, 20}, {-1, 25}, {1, 1}, {1, 6}, {1, 20}, {1, 25}};
    private static final String[][] VENDOR_LOOK = {
            // name, title, skin, canopy wool
            {"<white>Bram Ironhand", "<gray>Smith · ores & gems", "worker_2", "GRAY"},
            {"<white>Hedda Thorne", "<gray>Farmer · produce", "farmer", "LIME"},
            {"<white>Oswin Cleaver", "<gray>Butcher · meat & fish", "worker_4", "RED"},
            {"<white>Mabel Crust", "<gray>Baker · bread & hot food", "chef", "YELLOW"},
            {"<white>Rowan Ash", "<gray>Woodcutter · timber", "worker_1", "BROWN"},
            {"<white>Gareth Stone", "<gray>Mason · stone & sand", "worker_3", "LIGHT_GRAY"},
            {"<white>Wynn Weaver", "<gray>Weaver · wool, hides & paper", "merchant_3", "PINK"},
            {"<white>Old Ysolde", "<gray>Curios · mob drops", "enchanter", "PURPLE"},
    };
    private static final String[][] CRIES = {
            {"Iron, gold, gems! Fair weight, fair price!", "Ingots fresh from the forge!"},
            {"Wheat and roots, picked this morning!", "Pumpkins! Melons! Who'll have some?"},
            {"Fresh meat! Fresh fish!", "Best cuts in the valley, here!"},
            {"Hot bread! Pies! Come and get it!", "Cakes for your sweetheart!"},
            {"Logs and planks, seasoned and dry!", "Build yourself a house, start here!"},
            {"Stone, brick and glass! Build to last!", "Cobble by the cartload!"},
            {"Wool, leather and fine paper!", "Soft as a cloud, this wool!"},
            {"Pearls and tears and stranger things...", "Curious? Come closer, dearie."},
    };

    private final TownManager t;
    final NamespacedKey key;
    private World w;
    private int cx, cy, cz;
    private BlockFace front = BlockFace.WEST;
    private UUID board;
    private final Map<Cat, Long> lastCry = new EnumMap<>(Cat.class);
    private final Random r = new Random();

    MarketHall(TownManager t) {
        this.t = t;
        this.key = new NamespacedKey(t.plugin(), "market");
        load();
    }

    private File structureFile() {
        return new File(t.plugin().getDataFolder(), "market-undo.nbt");
    }

    private File metaFile() {
        return new File(t.plugin().getDataFolder(), "market-undo.yml");
    }

    public boolean built() {
        return structureFile().exists() && w != null;
    }

    private void load() {
        if (!metaFile().exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(metaFile());
        w = Bukkit.getWorld(y.getString("world", ""));
        List<Integer> c = y.getIntegerList("centre");
        if (c.size() == 3) {
            cx = c.get(0);
            cy = c.get(1);
            cz = c.get(2);
        }
        front = BlockFace.valueOf(y.getString("front", "WEST"));
        String b = y.getString("board");
        board = b == null ? null : UUID.fromString(b);
    }

    Build plan() {
        return new Build(w, cx, cy, cz, front);
    }

    World world() {
        return w;
    }

    // ------------------------------------------------------------------ geometry other parts use

    /** Inside the hall (with a block to spare around it). */
    public boolean inside(Location l) {
        if (!built() || l.getWorld() != w) return false;
        return box().contains(l.toVector());
    }

    BoundingBox box() {
        Build b = plan();
        Block a = b.block(-HX - 1, -1, -1), c = b.block(HX + 1, 20, DEPTH + 1);
        return BoundingBox.of(a, c);
    }

    /** The twelve player stalls: {side, first z}. Counter at x = side*6, keeper at side*7, back at side*8. */
    static final int[][] STALLS = {{-1, 3}, {-1, 6}, {-1, 9}, {-1, 19}, {-1, 22}, {-1, 25}, {1, 3}, {1, 6}, {1, 9}, {1, 19}, {1, 22}, {1, 25}};

    /** The counter block of stall n (1-based). */
    Block stallCounter(int n) {
        int[] s = STALLS[n - 1];
        return plan().block(s[0] * 6, 1, s[1] + 1);
    }

    /** Where a stall's keeper stands, facing the aisle. */
    Location keeperSpot(int n) {
        int[] s = STALLS[n - 1];
        Block b = plan().block(s[0] * 7, 1, s[1] + 1);
        return new Location(w, b.getX() + 0.5, b.getY(), b.getZ() + 0.5, yaw(s[0] > 0 ? BlockFace.WEST : BlockFace.EAST), 0);
    }

    /** Which stall (1-based) a block belongs to, or 0. */
    int stallAt(Block bl) {
        if (!built() || bl.getWorld() != w) return 0;
        Build b = plan();
        for (int n = 1; n <= STALLS.length; n++) {
            int[] s = STALLS[n - 1];
            for (int dx = 6; dx <= 8; dx++) for (int dz = 0; dz <= 2; dz++) for (int dy = 1; dy <= 4; dy++) {
                if (b.block(s[0] * dx, dy, s[1] + dz).equals(bl)) return n;
            }
        }
        return 0;
    }

    /** Where a stall's floating sign goes. */
    Location stallSign(int n) {
        int[] s = STALLS[n - 1];
        Block b = plan().block(s[0] * 6, 1, s[1] + 1);
        BlockFace out = plan().face(s[0] > 0 ? BlockFace.WEST : BlockFace.EAST);
        return new Location(w, b.getX() + 0.5 + out.getModX() * 0.6, b.getY() + 2.1, b.getZ() + 0.5 + out.getModZ() * 0.6);
    }

    /** Random walkable floor spot in the aisles, for shoppers. */
    Location aisleSpot() {
        Build b = plan();
        for (int i = 0; i < 10; i++) {
            int x, z;
            switch (r.nextInt(3)) {
                case 0 -> {
                    x = -4 + r.nextInt(9);
                    z = 1 + r.nextInt(DEPTH - 1);
                    if (Math.abs(x) <= 2 && z >= 13 && z <= 17) continue; // the broker's booth
                }
                case 1 -> {
                    x = (r.nextBoolean() ? 1 : -1) * (10 + r.nextInt(5));
                    z = 1 + r.nextInt(DEPTH - 1);
                }
                default -> {
                    x = -15 + r.nextInt(31);
                    z = 13 + r.nextInt(5);
                }
            }
            Block feet = b.block(x, 1, z);
            if (feet.isPassable() && feet.getRelative(0, 1, 0).isPassable()) return feet.getLocation().add(0.5, 0, 0.5);
        }
        return null;
    }

    Location entrance() {
        Block b = plan().block(0, 1, -2);
        return b.getLocation().add(0.5, 0, 0.5);
    }

    static float yaw(BlockFace f) {
        return switch (f) {
            case SOUTH -> 0;
            case WEST -> 90;
            case NORTH -> 180;
            default -> 270;
        };
    }

    private float worldYaw(BlockFace local) {
        return yaw(plan().face(local));
    }

    public boolean nearVendor(Player p, Cat c) {
        var d = t.plugin().npcs().get("mk_" + c.vendor);
        Location h = d == null ? null : d.home();
        return h != null && h.getWorld() == p.getWorld() && h.distanceSquared(p.getLocation()) <= 49;
    }

    // ------------------------------------------------------------------ check / build / undo

    /** The plaza's east gate (just outside it), if the plaza was generated. */
    private int[] plazaGate() {
        File pf = new File(t.plugin().getDataFolder(), "plaza-undo.yml");
        if (!pf.exists()) return null;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(pf);
        if (!y.getString("world", "").equals(w.getName())) return null;
        return new int[]{y.getInt("x") + 24, y.getInt("y"), y.getInt("z")};
    }

    /**
     * The path from the plaza gate to the front of the hall as boxes (x0, z0, x1, z1), each 3 wide: east or west along
     * the gate's z, then north or south to the hall's front. Each is snapshotted on its own, so the undo never reaches
     * into a neighbouring building.
     */
    private List<int[]> pathLegs() {
        List<int[]> out = new ArrayList<>();
        int[] g = plazaGate();
        if (g == null) return out;
        Block e = plan().block(0, 0, -5);
        int ex = e.getX(), ez = e.getZ(), gx = g[0], gz = g[2];
        if (Math.abs(ex - gx) + Math.abs(ez - gz) > 120) return out; // too far for a path
        out.add(new int[]{Math.min(gx, ex) - 1, gz - 1, Math.max(gx, ex) + 1, gz + 1});
        if (ez != gz) out.add(new int[]{ex - 1, Math.min(gz, ez) - 1, ex + 1, Math.max(gz, ez) + 1});
        return out;
    }

    private File pathFile(int i) {
        return new File(t.plugin().getDataFolder(), "market-path-" + i + "-undo.nbt");
    }

    private void path(List<int[]> legs) {
        int gy = cy;
        for (int[] l : legs) {
            for (int x = l[0]; x <= l[2]; x++) {
                for (int z = l[1]; z <= l[3]; z++) {
                    if (box().contains(x + 0.5, cy + 0.5, z + 0.5)) continue;
                    for (int y = gy + 1; y <= gy + 4; y++) {
                        Block up = w.getBlockAt(x, y, z);
                        if (!up.getType().isAir() && !org.bukkit.Tag.LOGS.isTagged(up.getType())) up.setType(Material.AIR, false);
                    }
                    for (int y = gy - 1; y >= gy - 10; y--) {
                        Block bl = w.getBlockAt(x, y, z);
                        if (solid(bl) && !bl.isLiquid() && y < gy - 1) break;
                        bl.setType(Material.STONE_BRICKS, false);
                    }
                    int h = hash(x, z);
                    w.getBlockAt(x, gy, z).setType(h % 5 == 0 ? Material.COBBLESTONE : h % 7 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS, false);
                }
            }
            // a lantern post every 10 blocks along the outer edge
            boolean alongX = l[2] - l[0] > l[3] - l[1];
            int len = alongX ? l[2] - l[0] : l[3] - l[1];
            for (int i = 5; i < len - 2; i += 10) {
                int x = alongX ? l[0] + i : l[2] + 1, z = alongX ? l[3] + 1 : l[1] + i;
                Block post = w.getBlockAt(x, gy + 1, z);
                if (box().contains(x + 0.5, gy + 1.5, z + 0.5) || !post.getType().isAir() && !post.isPassable()) continue;
                if (!solid(w.getBlockAt(x, gy, z))) w.getBlockAt(x, gy, z).setType(Material.STONE_BRICKS, false);
                post.setType(Material.COBBLESTONE_WALL, false);
                w.getBlockAt(x, gy + 2, z).setType(Material.SPRUCE_FENCE, false);
                org.bukkit.block.data.type.Lantern lt = (org.bukkit.block.data.type.Lantern) Material.LANTERN.createBlockData();
                lt.setHanging(false);
                w.getBlockAt(x, gy + 3, z).setBlockData(lt, false);
            }
        }
    }

    private int[] area() {
        Build b = plan();
        Block a = b.block(-HX - 3, -5, -5), c = b.block(HX + 3, 24, DEPTH + 3);
        return new int[]{Math.min(a.getX(), c.getX()), Math.min(a.getY(), c.getY()), Math.min(a.getZ(), c.getZ()),
                Math.max(a.getX(), c.getX()), Math.max(a.getY(), c.getY()), Math.max(a.getZ(), c.getZ())};
    }

    private static BoundingBox boxOf(int[] a) {
        return new BoundingBox(a[0], a[1], a[2], a[3] + 1, a[4] + 1, a[5] + 1);
    }

    private void setOrigin(World world, int x, int y, int z, BlockFace f) {
        this.w = world;
        this.cx = x;
        this.cy = y;
        this.cz = z;
        this.front = f;
    }

    public String check(World world, int x, int y, int z, BlockFace f) {
        if (built()) return "A market hall is already built. /mrtown market undo first.";
        setOrigin(world, x, y, z, f);
        int[] a = area();
        int tiles = 0, built = 0, lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, water = 0;
        for (int bx = a[0] >> 4; bx <= a[3] >> 4; bx++)
            for (int bz = a[2] >> 4; bz <= a[5] >> 4; bz++)
                for (var s : w.getChunkAt(bx, bz).getTileEntities()) {
                    Location l = s.getLocation();
                    if (boxOf(a).contains(l.toVector().add(new org.bukkit.util.Vector(0.5, 0.5, 0.5)))) tiles++;
                }
        Build b = plan();
        for (int lx = -HX - 2; lx <= HX + 2; lx++) {
            for (int lz = -1; lz <= DEPTH + 1; lz++) {
                Block top = b.block(lx, 0, lz);
                int gy = w.getHighestBlockYAt(top.getX(), top.getZ(), org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) - cy;
                if (lx % 3 == 0 && lz % 3 == 0) {
                    lo = Math.min(lo, gy);
                    hi = Math.max(hi, gy);
                }
                if (w.getBlockAt(top.getX(), cy + gy, top.getZ()).isLiquid()) water++;
                for (int dy = 0; dy <= 12; dy++) {
                    String n = b.block(lx, dy, lz).getType().name();
                    if (n.contains("PLANKS") || n.contains("BRICK") || n.contains("GLASS") || n.contains("WOOL") || n.contains("CONCRETE") || n.contains("CHEST")
                            || n.contains("FENCE") || n.contains("DOOR") || n.contains("TORCH")) built++;
                }
            }
        }
        int ents = 0;
        for (Entity e : w.getNearbyEntities(boxOf(a))) if (!(e instanceof Player) && !t.isOurs(e)) ents++;
        int pathBuilt = 0, pathTiles = 0, pathCells = 0;
        for (int[] l : pathLegs()) {
            for (int px = l[0]; px <= l[2]; px++) for (int pz = l[1]; pz <= l[3]; pz++) {
                if (box().contains(px + 0.5, cy + 0.5, pz + 0.5)) continue;
                pathCells++;
                for (int dy = 0; dy <= 4; dy++) {
                    Block pb = w.getBlockAt(px, cy + dy, pz);
                    String n = pb.getType().name();
                    if (pb.getState() instanceof org.bukkit.block.TileState) pathTiles++;
                    else if (n.contains("PLANKS") || n.contains("GLASS") || n.contains("WOOL") || n.contains("CONCRETE") || n.contains("FENCE") || n.contains("DOOR")
                            || n.contains("TORCH") || n.contains("BRICK") && dy > 0) pathBuilt++;
                }
            }
        }
        Block f0 = b.block(-HX, 0, 0), f1 = b.block(HX, 0, DEPTH);
        return "Market hall " + Math.min(f0.getX(), f1.getX()) + ".." + Math.max(f0.getX(), f1.getX()) + ", " + Math.min(f0.getZ(), f1.getZ()) + ".."
                + Math.max(f0.getZ(), f1.getZ()) + " (front " + f.name().toLowerCase() + "): " + tiles + " block entities, " + built + " built-looking blocks, "
                + ents + " entities, " + water + " water columns. Ground vs floor: " + lo + " to +" + hi + ". Path from the plaza: "
                + (pathCells == 0 ? "none (no plaza gate found)" : pathCells + " blocks, " + pathTiles + " block entities and " + pathBuilt + " built-looking blocks on it") + ".";
    }

    /** Origin = the floor block at the middle of the front edge; the front faces {@code f} (default west, to the plaza). */
    public String build(World world, int x, int y, int z, BlockFace f) throws IOException {
        if (structureFile().exists()) throw new IOException("A market hall is already built. Run /mrtown market undo first.");
        setOrigin(world, x, y, z, f);
        int[] a = area();
        Structure st = Bukkit.getStructureManager().createStructure();
        st.fill(new Location(w, a[0], a[1], a[2]), new BlockVector(a[3] - a[0] + 1, a[4] - a[1] + 1, a[5] - a[2] + 1), true);
        Bukkit.getStructureManager().saveStructure(structureFile(), st);
        for (Entity e : w.getNearbyEntities(boxOf(a))) if (e instanceof Item) e.remove();
        List<int[]> legs = pathLegs();
        List<List<Integer>> legMeta = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            int[] l = legs.get(i);
            Structure ps = Bukkit.getStructureManager().createStructure();
            ps.fill(new Location(w, l[0] - 1, cy - 10, l[1] - 1), new BlockVector(l[2] - l[0] + 3, 15, l[3] - l[1] + 3), false);
            Bukkit.getStructureManager().saveStructure(pathFile(i), ps);
            legMeta.add(List.of(l[0] - 1, cy - 10, l[1] - 1, l[2] + 1, cy + 4, l[3] + 1));
        }
        Build b = plan();
        groundwork(b);
        frame(b);
        roof(b);
        broker(b);
        for (int i = 0; i < VENDOR_SPOTS.length; i++) vendorStall(b, i);
        for (int n = 1; n <= STALLS.length; n++) playerStall(b, n);
        lights(b);
        b.finish();
        path(legs);
        signs(b);
        YamlConfiguration meta = new YamlConfiguration();
        meta.set("world", w.getName());
        meta.set("centre", List.of(x, y, z));
        meta.set("front", f.name());
        meta.set("area", List.of(a[0], a[1], a[2], a[3], a[4], a[5]));
        meta.set("board", board == null ? null : board.toString());
        meta.set("path", legMeta);
        meta.set("created", System.currentTimeMillis());
        meta.save(metaFile());
        t.stalls().refreshAll();
        return "Market hall built at " + w.getName() + " " + x + " " + y + " " + z + " facing " + f.name().toLowerCase()
                + ": 8 vendors, the broker's exchange and 12 stalls to rent. Undo with /mrtown market undo.";
    }

    public String undo() throws IOException {
        if (!structureFile().exists()) throw new IOException("No generated market hall to undo.");
        YamlConfiguration meta = YamlConfiguration.loadConfiguration(metaFile());
        World world = Bukkit.getWorld(meta.getString("world", ""));
        if (world == null) throw new IOException("World not loaded.");
        List<Integer> l = meta.getIntegerList("area");
        int[] area = {l.get(0), l.get(1), l.get(2), l.get(3), l.get(4), l.get(5)};
        t.stalls().hallGone();
        for (Entity e : world.getNearbyEntities(boxOf(area))) if (e.getPersistentDataContainer().has(key, PersistentDataType.STRING)) e.remove();
        t.plugin().npcs().removeGroup(ID);
        for (Walker x : new ArrayList<>(t.walkers())) if ("market".equals(x.gang)) t.unregister(x, false);
        for (int y = area[4]; y >= area[1]; y--)
            for (int x = area[0]; x <= area[3]; x++) for (int z = area[2]; z <= area[5]; z++) world.getBlockAt(x, y, z).setType(Material.AIR, false);
        Structure st = Bukkit.getStructureManager().loadStructure(structureFile());
        st.place(new Location(world, area[0], area[1], area[2]), true, StructureRotation.NONE, Mirror.NONE, 0, 1.0f, new Random());
        List<?> legs = meta.getList("path", List.of());
        for (int i = 0; i < legs.size(); i++) {
            File pf = pathFile(i);
            if (!pf.exists() || !(legs.get(i) instanceof List<?> leg) || leg.size() < 6) continue;
            int x0 = ((Number) leg.get(0)).intValue(), y0 = ((Number) leg.get(1)).intValue(), z0 = ((Number) leg.get(2)).intValue();
            int x1 = ((Number) leg.get(3)).intValue(), y1 = ((Number) leg.get(4)).intValue(), z1 = ((Number) leg.get(5)).intValue();
            for (int y = y1; y >= y0; y--) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                if (!boxOf(area).contains(x + 0.5, y + 0.5, z + 0.5)) world.getBlockAt(x, y, z).setType(Material.AIR, false);
            }
            Structure ps = Bukkit.getStructureManager().loadStructure(pf);
            ps.place(new Location(world, x0, y0, z0), false, StructureRotation.NONE, Mirror.NONE, 0, 1.0f, new Random());
            pf.renameTo(new File(t.plugin().getDataFolder(), "market-path-" + i + "-undone-" + System.currentTimeMillis() + ".nbt"));
        }
        File done = new File(t.plugin().getDataFolder(), "market-undone-" + System.currentTimeMillis() + ".nbt");
        structureFile().renameTo(done);
        metaFile().delete();
        w = null;
        board = null;
        return "Market hall removed and the ground put back as it was. Snapshot kept as " + done.getName() + ".";
    }

    // ------------------------------------------------------------------ the building

    /** Ground to build on: solid, and not a tree. */
    private static boolean solid(Block b) {
        Material m = b.getType();
        return m.isSolid() && !org.bukkit.Tag.LEAVES.isTagged(m) && !org.bukkit.Tag.LOGS.isTagged(m);
    }

    private void groundwork(Build b) {
        for (int lx = -HX - 3; lx <= HX + 3; lx++) {
            for (int lz = -5; lz <= DEPTH + 3; lz++) {
                boolean path = lz < -1;
                if (path && Math.abs(lx) > 3) continue;
                boolean apron = Math.abs(lx) > HX || lz < 0 || lz > DEPTH;
                for (int y = 1; y <= 24; y++) b.set(lx, y, lz, Material.AIR);
                boolean edge = apron || Math.abs(lx) == HX || lz == 0 || lz == DEPTH;
                for (int y = -1; y >= -14; y--) { // down to solid ground, so no edge floats
                    Block bl = b.block(lx, y, lz);
                    if (solid(bl) && !bl.isLiquid() && y < -1) break;
                    b.set(lx, y, lz, edge ? Material.STONE_BRICKS : Material.DIRT);
                }
                Material floor;
                if (path) floor = (lx + lz) % 3 == 0 ? Material.COBBLESTONE : Material.STONE_BRICKS;
                else if (apron) floor = Math.abs(lx) <= 4 || (lz >= 12 && lz <= 18) ? Material.STONE_BRICKS : Material.GRASS_BLOCK;
                else if (Math.abs(lx) <= 5 || (lz >= 13 && lz <= 17)) floor = (lx + lz) % 4 == 0 ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS;
                else {
                    int h = hash(lx, lz);
                    floor = h % 9 == 0 ? Material.MOSSY_COBBLESTONE : h % 7 == 0 ? Material.GRAVEL : Material.COBBLESTONE;
                }
                b.set(lx, 0, lz, floor);
            }
        }
    }

    private static int hash(int x, int z) {
        int h = x * 73856093 ^ z * 19349663;
        return Math.abs(h);
    }

    private boolean column(int lx, int lz) {
        boolean edgeX = Math.abs(lx) == HX, edgeZ = lz == 0 || lz == DEPTH;
        if (edgeZ && lx % 5 == 0 && lx != 0) return true;
        return edgeX && lz % 5 == 0 && lz != 15;
    }

    private void frame(Build b) {
        for (int lx = -HX; lx <= HX; lx++) {
            for (int lz = 0; lz <= DEPTH; lz++) {
                boolean edge = Math.abs(lx) == HX || lz == 0 || lz == DEPTH;
                if (!edge) continue;
                if (column(lx, lz)) {
                    b.set(lx, 1, lz, Material.STONE_BRICKS);
                    for (int y = 2; y <= 6; y++) b.log(lx, y, lz, Material.SPRUCE_LOG, Axis.Y);
                    continue;
                }
                boolean door = (lz == 0 || lz == DEPTH) && Math.abs(lx) <= 4 || Math.abs(lx) == HX && lz >= 12 && lz <= 18;
                if (!door) b.set(lx, 1, lz, Material.COBBLESTONE_WALL);
            }
        }
        // perimeter beams and two trusses
        for (int lx = -HX; lx <= HX; lx++) {
            b.log(lx, 7, 0, Material.STRIPPED_SPRUCE_LOG, Axis.X);
            b.log(lx, 7, DEPTH, Material.STRIPPED_SPRUCE_LOG, Axis.X);
            b.log(lx, 7, 10, Material.STRIPPED_SPRUCE_LOG, Axis.X);
            b.log(lx, 7, 20, Material.STRIPPED_SPRUCE_LOG, Axis.X);
        }
        for (int lz = 0; lz <= DEPTH; lz++) {
            b.log(-HX, 7, lz, Material.STRIPPED_SPRUCE_LOG, Axis.Z);
            b.log(HX, 7, lz, Material.STRIPPED_SPRUCE_LOG, Axis.Z);
        }
        for (int lz : new int[]{10, 20}) {
            for (int sx : new int[]{-HX, HX}) {
                b.set(sx, 1, lz, Material.STONE_BRICKS);
                for (int y = 2; y <= 6; y++) b.log(sx, y, lz, Material.SPRUCE_LOG, Axis.Y);
            }
            for (int px : new int[]{-10, 0, 10}) for (int y = 8; y < roofLow(px); y++) b.log(px, y, lz, Material.STRIPPED_SPRUCE_LOG, Axis.Y);
        }
        // gable ends: timber frame with daub infill, an open window in the middle
        for (int lz : new int[]{0, DEPTH}) {
            for (int lx = -HX + 1; lx <= HX - 1; lx++) {
                for (int y = 8; y < roofLow(lx); y++) {
                    boolean post = lx % 5 == 0;
                    boolean window = Math.abs(lx) <= 2 && y >= 10 && y <= 13;
                    if (window) continue;
                    if (post) b.log(lx, y, lz, Material.STRIPPED_SPRUCE_LOG, Axis.Y);
                    else b.set(lx, y, lz, y == 8 ? Material.STRIPPED_SPRUCE_WOOD : Material.WHITE_TERRACOTTA);
                }
            }
        }
    }

    /** The first y that the roof covers above local x (a half-block pitch: rises 1 every 2 blocks). */
    private static int roofLow(int lx) {
        double lower = 7.5 + 0.5 * (21 - Math.abs(lx));
        return (int) Math.floor(lower);
    }

    private void roof(Build b) {
        for (int lx = -21; lx <= 21; lx++) {
            double lower = 7.5 + 0.5 * (21 - Math.abs(lx));
            boolean whole = lower == Math.floor(lower);
            int y = (int) Math.floor(lower);
            for (int lz = -1; lz <= DEPTH + 1; lz++) b.slab(lx, y, lz, Material.DARK_OAK_SLAB, !whole);
        }
    }

    private void broker(Build b) {
        for (int lx = -2; lx <= 2; lx++) {
            for (int lz = 13; lz <= 17; lz++) {
                boolean ring = Math.abs(lx) == 2 || lz == 13 || lz == 17;
                if (!ring) continue;
                boolean corner = Math.abs(lx) == 2 && (lz == 13 || lz == 17);
                if (corner) {
                    b.log(lx, 1, lz, Material.STRIPPED_DARK_OAK_LOG, Axis.Y);
                    b.log(lx, 2, lz, Material.STRIPPED_DARK_OAK_LOG, Axis.Y);
                    b.set(lx, 3, lz, Material.DARK_OAK_FENCE);
                    b.lantern(lx, 4, lz, false);
                } else {
                    b.set(lx, 1, lz, Material.DARK_OAK_PLANKS);
                    b.slab(lx, 2, lz, Material.DARK_OAK_SLAB, false);
                }
            }
        }
        b.set(0, 1, 15, Material.AIR);
        b.set(-1, 1, 16, Material.LECTERN);
        b.set(1, 1, 16, Material.BARREL);
        npc(b, "mk_broker", "<gold>Master Aldous", "<gray>Exchange broker · buys & sells everything", 0, 15, BlockFace.NORTH, "banker", "command:market");
    }

    private void vendorStall(Build b, int i) {
        int side = VENDOR_SPOTS[i][0], z0 = VENDOR_SPOTS[i][1];
        Cat c = Cat.values()[i];
        Material wool = Material.valueOf(VENDOR_LOOK[i][3] + "_WOOL");
        for (int dz = 0; dz <= 4; dz++) {
            int lz = z0 + dz;
            b.set(side * 16, 1, lz, dz == 2 ? Material.BARREL : Material.SPRUCE_PLANKS);
            for (int dx = 16; dx <= 19; dx++) b.set(side * dx, 4, lz, wool);
        }
        for (int dz : new int[]{0, 4}) {
            for (int y = 2; y <= 3; y++) b.set(side * 16, y, z0 + dz, Material.SPRUCE_FENCE);
            for (int y = 1; y <= 3; y++) b.set(side * 19, y, z0 + dz, Material.SPRUCE_FENCE);
        }
        Material[] goods = switch (c) {
            case ORES -> new Material[]{Material.ANVIL, Material.BLAST_FURNACE, Material.IRON_BLOCK};
            case FARM -> new Material[]{Material.HAY_BLOCK, Material.PUMPKIN, Material.MELON};
            case MEAT -> new Material[]{Material.SMOKER, Material.BARREL, Material.CAMPFIRE};
            case KITCHEN -> new Material[]{Material.FURNACE, Material.HAY_BLOCK, Material.BARREL};
            case TIMBER -> new Material[]{Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG};
            case STONE -> new Material[]{Material.STONECUTTER, Material.STONE_BRICKS, Material.BRICKS};
            case CLOTH -> new Material[]{Material.LOOM, Material.WHITE_WOOL, Material.RED_WOOL};
            case CURIOS -> new Material[]{Material.BREWING_STAND, Material.BOOKSHELF, Material.CAULDRON};
        };
        for (int dz = 1; dz <= 3; dz++) {
            Material m = goods[dz - 1];
            if (org.bukkit.Tag.LOGS.isTagged(m)) b.log(side * 19, 1, z0 + dz, m, Axis.Z);
            else b.set(side * 19, 1, z0 + dz, m);
        }
        if (c == Cat.KITCHEN) b.set(side * 16, 2, z0 + 1, Material.CAKE);
        if (c == Cat.FARM) b.set(side * 19, 2, z0 + 2, Material.CARVED_PUMPKIN);
        b.lantern(side * 17, 3, z0 + 2, true);
        npc(b, "mk_" + c.vendor, VENDOR_LOOK[i][0], VENDOR_LOOK[i][1], side * 18, z0 + 2, side > 0 ? BlockFace.WEST : BlockFace.EAST, VENDOR_LOOK[i][2],
                "command:market vendor " + c.vendor);
    }

    private void playerStall(Build b, int n) {
        int side = STALLS[n - 1][0], z0 = STALLS[n - 1][1];
        Material wool = n % 2 == 0 ? Material.WHITE_WOOL : Material.LIGHT_BLUE_WOOL;
        for (int dz = 0; dz <= 2; dz++) {
            int lz = z0 + dz;
            b.set(side * 6, 1, lz, dz == 1 ? Material.BARREL : Material.SPRUCE_PLANKS);
            for (int dx = 6; dx <= 8; dx++) b.set(side * dx, 4, lz, wool);
        }
        for (int dz : new int[]{0, 2}) {
            for (int y = 2; y <= 3; y++) b.set(side * 6, y, z0 + dz, Material.SPRUCE_FENCE);
            for (int y = 1; y <= 3; y++) b.set(side * 8, y, z0 + dz, Material.SPRUCE_FENCE);
        }
        b.set(side * 8, 1, z0 + 1, Material.CHEST);
    }

    private void lights(Build b) {
        for (int lz : new int[]{10, 20}) for (int lx : new int[]{-15, -5, 5, 15}) b.lantern(lx, 6, lz, true);
        for (int lx : new int[]{-12, 12}) {
            for (int lz : new int[]{5, 15, 25}) {
                int top = roofLow(lx) - 1;
                for (int y = 7; y <= top; y++) b.set(lx, y, lz, Material.IRON_CHAIN);
                b.lantern(lx, 6, lz, true);
            }
        }
        for (int lz : new int[]{3, 8, 22, 27}) { // on the low wall, between posts
            b.lantern(-HX, 2, lz, false);
            b.lantern(HX, 2, lz, false);
        }
        for (int lx : new int[]{-4, 4}) {
            b.set(lx, 1, -1, Material.COBBLESTONE_WALL);
            b.lantern(lx, 2, -1, false);
            b.set(lx, 1, DEPTH + 1, Material.COBBLESTONE_WALL);
            b.lantern(lx, 2, DEPTH + 1, false);
        }
        for (int lz : new int[]{2, 28}) {
            b.set(-11, 1, lz, Material.BARREL);
            b.set(11, 1, lz, Material.BARREL);
        }
    }

    private TextDisplay text(Location l, String mm, float scale) {
        return w.spawn(l, TextDisplay.class, d -> {
            d.text(SmpPlugin.mm(mm));
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setPersistent(true);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(scale, scale, scale), new org.joml.Quaternionf()));
            d.getPersistentDataContainer().set(key, PersistentDataType.STRING, "sign");
        });
    }

    private void signs(Build b) {
        Block out = b.block(0, 8, -1);
        text(out.getLocation().add(0.5, 0.6, 0.5), "<gold><bold>Market Hall</bold>\n<gray>Buy, sell, rent a stall", 1.6f);
        Block mid = b.block(0, 5, 15);
        board = text(mid.getLocation().add(0.5, 0.3, 0.5), "<gold>Market board", 1.0f).getUniqueId();
        for (int i = 0; i < VENDOR_SPOTS.length; i++) {
            int side = VENDOR_SPOTS[i][0], z0 = VENDOR_SPOTS[i][1];
            Block s = b.block(side * 16, 4, z0 + 2);
            BlockFace into = plan().face(side > 0 ? BlockFace.WEST : BlockFace.EAST);
            text(s.getLocation().add(0.5 + into.getModX() * 0.7, 0.5, 0.5 + into.getModZ() * 0.7),
                    "<gold>" + Cat.values()[i].label, 0.9f);
        }
        updateBoard();
    }

    // ------------------------------------------------------------------ NPCs

    private void npc(Build b, String id, String name, String title, int lx, int lz, BlockFace facing, String skin, String action) {
        Block spot = b.block(lx, 1, lz);
        NpcDef d = new NpcDef();
        d.id = id;
        d.name = name;
        d.title = title;
        d.world = w.getName();
        d.x = spot.getX() + 0.5;
        d.y = spot.getY();
        d.z = spot.getZ() + 0.5;
        d.yaw = worldYaw(facing);
        d.group = ID;
        d.action = action;
        d.texture = skin;
        d.equipment.put(EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE);
        t.plugin().npcs().put(d);
    }

    // ------------------------------------------------------------------ life in the hall

    void updateBoard() {
        if (board == null) return;
        Entity e = Bukkit.getEntity(board);
        if (!(e instanceof TextDisplay td)) return;
        StringBuilder s = new StringBuilder("<gold><bold>Market board</bold>\n<dark_gray>buy / sell · change from normal\n");
        for (String l : t.market().boardLines(12)) s.append(l).append("\n");
        s.append("<yellow>Talk to Master Aldous to trade");
        td.text(SmpPlugin.mm(s.toString()));
    }

    /** Every second: vendors cry their wares to people nearby, shoppers come and go, the board refreshes. */
    void second(int tick) {
        if (!built() || w == null) return;
        if (tick % 60 == 0) updateBoard();
        long now = System.currentTimeMillis();
        if (tick % 5 == 0) {
            for (int i = 0; i < VENDOR_SPOTS.length; i++) {
                Cat c = Cat.values()[i];
                var d = t.plugin().npcs().get("mk_" + c.vendor);
                Location h = d == null ? null : d.home();
                if (h == null || h.getWorld() != w) continue;
                if (now - lastCry.getOrDefault(c, 0L) < 45_000 + r.nextInt(40_000)) continue;
                List<Player> near = new ArrayList<>();
                for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(h) < 14 * 14) near.add(p);
                if (near.isEmpty()) continue;
                lastCry.put(c, now);
                String cry = CRIES[i][r.nextInt(CRIES[i].length)];
                Market.Ware deal = null;
                for (Market.Ware x : t.market().wares()) if (x.cat == c && (deal == null || t.market().mid(x) / x.base < t.market().mid(deal) / deal.base)) deal = x;
                if (deal != null && t.market().mid(deal) / deal.base < 0.8) cry += " " + deal.label() + " going cheap, " + Market.price(t.market().buyPrice(deal)) + "!";
                for (Player p : near) p.sendMessage(SmpPlugin.mm(d.name + " <dark_gray>» <white>" + cry));
            }
        }
        if (tick % 4 == 0) shoppers(now);
    }

    /** Townsfolk browsing the stalls while players are about. */
    private void shoppers(long now) {
        Location in = entrance();
        Player near = t.nearestPlayer(in, 48);
        int have = 0;
        for (Walker x : t.walkers(Walker.Role.CUSTOMER)) if ("market".equals(x.gang)) have++;
        int max = t.plugin().getConfig().getInt("town.market.shoppers", 8);
        if (near != null && have < max && t.canSpawnWalkers(w) && r.nextInt(3) == 0) {
            Location at = r.nextBoolean() ? aisleSpot() : in;
            if (at != null) {
                Walker c = Walker.spawn(t.key, at, Walker.Role.CUSTOMER, "<white>" + pick(), "<gray>Shopping at the market", 0.2, 12, Map.of());
                c.gang = "market";
                c.modeSince = now;
                t.register(c);
            }
        }
        for (Walker x : new ArrayList<>(t.walkers(Walker.Role.CUSTOMER))) {
            if (!"market".equals(x.gang)) continue;
            if (now - x.modeSince > 180_000 || t.nearestPlayer(x.loc(), 64) == null) {
                t.unregister(x, true);
                continue;
            }
            if (!x.moving() && now > x.nextWander) {
                Location g = aisleSpot();
                if (g != null) x.moveTo(g, 0.55);
                x.nextWander = now + 5000 + r.nextInt(9000);
                if (r.nextInt(4) == 0) t.stalls().browse(x);
            }
        }
    }

    private String pick() {
        String[] n = {"Agnes", "Barnaby", "Cecily", "Dunstan", "Edith", "Fulke", "Gwen", "Hamon", "Isolde", "Jocelyn", "Kenric", "Lettice", "Maud", "Neville",
                "Odo", "Petronel", "Quentin", "Rohese", "Simon", "Tiffany", "Walter", "Ysmay"};
        return n[r.nextInt(n.length)];
    }

    String info() {
        if (!built()) return "No market hall built. /mrtown market check|build [x y z] [north|south|east|west]";
        return "Market hall at " + w.getName() + " " + cx + " " + cy + " " + cz + " facing " + front.name().toLowerCase() + ". " + t.market().status() + " "
                + t.stalls().summary();
    }
}
