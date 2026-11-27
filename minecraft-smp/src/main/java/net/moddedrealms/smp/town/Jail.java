package net.moddedrealms.smp.town;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.moddedrealms.smp.SmpPlugin;
import net.moddedrealms.smp.npc.NpcDef;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.EntityTransformer;

/**
 * The jail: cells, a release point and the rules for prisoners (no commands, no teleports, no fighting, time only
 * counts down while online, bail can be paid). It can be generated as a police station with {@link #build} (saved
 * first and reversible with {@link #undo}, like the plaza) or set up by hand in an existing building with
 * /mrtown jail addcell and setrelease.
 */
public final class Jail implements Listener {
    private static final int HX = 9, HZ = 7; // half sizes of the building (walls included)
    private static final int DOWN = 4, UP = 12;

    private final TownManager t;
    private final NamespacedKey key;
    private final File file;
    private final List<Location> cells = new ArrayList<>();
    private Location release;
    private BoundingBox box;
    private String boxWorld;
    private UUID board;
    private int tick;

    // builder state
    private World w;
    private int cx, cy, cz;
    private final List<Block> panes = new ArrayList<>();

    final String id;
    /** Only one jail enforces the prisoner rules (commands, teleports, damage), so they don't run twice. */
    private final boolean rules;

    Jail(TownManager t) {
        this(t, "spawn", "jail.yml", true);
    }

    Jail(TownManager t, String id, String fileName, boolean rules) {
        this.id = id;
        this.rules = rules;
        this.t = t;
        this.key = new NamespacedKey(t.plugin(), "jail");
        this.file = new File(t.plugin().getDataFolder(), fileName);
        load();
    }

    private void load() {
        cells.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String s : y.getStringList("cells")) {
            Location l = parse(s);
            if (l != null) cells.add(l);
        }
        release = parse(y.getString("release"));
        if (y.contains("box.world")) {
            boxWorld = y.getString("box.world");
            box = new BoundingBox(y.getDouble("box.min-x"), y.getDouble("box.min-y"), y.getDouble("box.min-z"),
                    y.getDouble("box.max-x"), y.getDouble("box.max-y"), y.getDouble("box.max-z"));
        }
        String b = y.getString("board");
        board = b == null ? null : UUID.fromString(b);
    }

    private void save() {
        YamlConfiguration y = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        List<String> cs = new ArrayList<>();
        for (Location l : cells) cs.add(str(l));
        y.set("cells", cs);
        y.set("release", release == null ? null : str(release));
        if (box != null) {
            y.set("box.world", boxWorld);
            y.set("box.min-x", box.getMinX());
            y.set("box.min-y", box.getMinY());
            y.set("box.min-z", box.getMinZ());
            y.set("box.max-x", box.getMaxX());
            y.set("box.max-y", box.getMaxY());
            y.set("box.max-z", box.getMaxZ());
        } else {
            y.set("box", null);
        }
        y.set("board", board == null ? null : board.toString());
        try {
            y.save(file);
        } catch (IOException e) {
            t.plugin().getLogger().warning("Could not save jail.yml: " + e.getMessage());
        }
    }

    private static String str(Location l) {
        return l.getWorld().getName() + "," + l.getX() + "," + l.getY() + "," + l.getZ() + "," + l.getYaw();
    }

    private static Location parse(String s) {
        if (s == null) return null;
        String[] a = s.split(",");
        World w = Bukkit.getWorld(a[0]);
        if (w == null || a.length < 5) return null;
        return new Location(w, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]), Float.parseFloat(a[4]), 0);
    }

    public boolean ready() {
        return !cells.isEmpty() && release != null;
    }

    public Location releasePoint() {
        return release == null ? null : release.clone();
    }

    public List<Location> cells() {
        return cells;
    }

    public boolean contains(Location l) {
        return box != null && l.getWorld() != null && l.getWorld().getName().equals(boxWorld) && box.contains(l.toVector());
    }

    void shutdown() {
    }

    // ------------------------------------------------------------------ prisoners

    int freeCell() {
        if (cells.isEmpty()) return -1;
        int[] n = new int[cells.size()];
        for (Player p : Bukkit.getOnlinePlayers()) {
            TownData d = t.data(p);
            if (d.jailed() && id.equals(d.jailTown) && d.cell >= 0 && d.cell < n.length) n[d.cell]++;
        }
        int best = 0;
        for (int i = 1; i < n.length; i++) if (n[i] < n[best]) best = i;
        return best;
    }

    void toCell(Player p, TownData d) {
        if (d.cell < 0 || d.cell >= cells.size()) d.cell = freeCell();
        if (d.cell < 0) return;
        t.teleport(p, cells.get(d.cell).clone());
    }

    void bailHint(Player p, TownData d) {
        double cost = bail(d);
        p.sendMessage(Component.text().append(SmpPlugin.mm("<gray>Pay bail to leave now: "))
                .append(SmpPlugin.mm("<gold><bold>[Pay " + Money.fmt(cost) + "]").clickEvent(ClickEvent.runCommand("/bail"))).build());
    }

    public double bail(TownData d) {
        return Math.ceil(d.jailLeft * t.plugin().getConfig().getDouble("town.arrest.bail-per-second", 4));
    }

    void secondInJail(Player p, TownData d) {
        if (!ready()) {
            t.release(p, "The jail was closed.");
            return;
        }
        d.jailLeft--;
        t.markDirty();
        if (d.jailLeft <= 0) {
            t.release(p, "You've served your time.");
            return;
        }
        if (d.cell < 0 || d.cell >= cells.size()) d.cell = freeCell();
        Location c = cells.get(d.cell);
        if (t.prisonBreak().escaping(p, d, c)) {
            if (d.jailed()) p.sendActionBar(SmpPlugin.mm("<green><bold>RUN!</bold> <gray>Out through the hole in the back wall"));
            return;
        }
        if (p.getWorld() != c.getWorld() || p.getLocation().distanceSquared(c) > 25) toCell(p, d);
        p.sendActionBar(SmpPlugin.mm("<gray>Jail: <white>" + TownManager.fmtTime(d.jailLeft) + "</white> left  ·  <gold>/bail " + Money.fmt(bail(d))));
    }

    void second() {
        if (++tick % 5 == 0) updateBoard();
    }

    private void updateBoard() {
        if (board == null) return;
        Entity e = Bukkit.getEntity(board);
        if (!(e instanceof TextDisplay td)) return;
        StringBuilder b = new StringBuilder("<red><bold>WANTED</bold>\n");
        List<Player> wanted = t.wantedOnline();
        if (wanted.isEmpty()) b.append("<gray>Nobody, for now.");
        for (int i = 0; i < Math.min(6, wanted.size()); i++) {
            Player p = wanted.get(i);
            TownData d = t.data(p);
            b.append(TownManager.stars(d.stars)).append(" <white>").append(p.getName()).append(" <gold>").append(Money.fmt(t.bounties().of(d)))
                    .append(" <gray>").append(d.reason).append('\n');
        }
        int inside = 0;
        for (Player p : Bukkit.getOnlinePlayers()) if (t.data(p).jailed()) inside++;
        List<Walker> bandits = t.walkers(Walker.Role.BANDIT);
        if (!bandits.isEmpty()) {
            b.append("\n<red>Bandits at large: <white>").append(bandits.size());
        }
        b.append("\n<blue>In the cells: <white>").append(inside);
        td.text(SmpPlugin.mm(b.toString().trim()));
    }

    // ------------------------------------------------------------------ manual set-up

    public String addCell(Location l) {
        Location c = l.getBlock().getLocation().add(0.5, 0, 0.5);
        c.setYaw(Math.round(l.getYaw() / 90f) * 90f);
        cells.add(c);
        save();
        return "Cell " + cells.size() + " added at " + c.getBlockX() + " " + c.getBlockY() + " " + c.getBlockZ() + ".";
    }

    /** Cells set up by a town builder (another town's sheriff office). */
    public void setup(List<Location> newCells, Location rel, BoundingBox protect, UUID boardId) {
        cells.clear();
        cells.addAll(newCells);
        release = rel;
        box = protect;
        boxWorld = protect == null ? null : rel.getWorld().getName();
        board = boardId;
        save();
    }

    /** Forget everything (the town that owned this jail was removed). */
    public void reset() {
        cells.clear();
        release = null;
        box = null;
        boxWorld = null;
        board = null;
        save();
    }

    public String clearCells() {
        cells.clear();
        save();
        return "All cells removed. Arrests now only fine players until you add cells again.";
    }

    public String setRelease(Location l) {
        release = l.getBlock().getLocation().add(0.5, 0, 0.5);
        release.setYaw(l.getYaw());
        save();
        return "Release point set.";
    }

    // ------------------------------------------------------------------ the generated police station

    private File structureFile() {
        return new File(t.plugin().getDataFolder(), "jail-undo.nbt");
    }

    private File metaFile() {
        return new File(t.plugin().getDataFolder(), "jail-undo.yml");
    }

    public boolean built() {
        return structureFile().exists();
    }

    private int[] plazaGate() {
        File pf = new File(t.plugin().getDataFolder(), "plaza-undo.yml");
        if (!pf.exists()) return null;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(pf);
        if (!y.getString("world", "").equals(w.getName())) return null;
        return new int[]{y.getInt("x"), y.getInt("y"), y.getInt("z") + 26};
    }

    /** Snapshot box: the building plus a margin, and the path to the plaza's south gate. */
    private int[] area(int[] gate) {
        int x0 = cx - HX - 2, x1 = cx + HX + 2, z0 = cz - HZ - 4, z1 = cz + HZ + 2;
        if (gate != null) {
            x0 = Math.min(x0, gate[0] - 3);
            x1 = Math.max(x1, gate[0] + 3);
            z0 = Math.min(z0, gate[2]);
            z1 = Math.max(z1, gate[2]);
        }
        return new int[]{x0, cy - DOWN, z0, x1, cy + UP, z1};
    }

    public String check(World world, int x, int y, int z) {
        this.w = world;
        this.cx = x;
        this.cy = y;
        this.cz = z;
        int[] a = area(plazaGate());
        Map<String, Integer> built = new TreeMap<>();
        int tiles = 0;
        for (int bx = a[0]; bx <= a[3]; bx++) {
            for (int bz = a[2]; bz <= a[5]; bz++) {
                for (int by = a[1]; by <= a[4]; by++) {
                    Block b = w.getBlockAt(bx, by, bz);
                    String n = b.getType().name();
                    if (n.contains("PLANKS") || n.contains("BRICK") || n.contains("GLASS") || n.contains("WOOL") || n.contains("CONCRETE")
                            || n.contains("CHEST") || n.contains("SIGN") || n.contains("DOOR") || n.contains("TORCH") || n.contains("LANTERN")
                            || n.contains("FURNACE") || n.contains("COMMAND") || n.startsWith("POLISHED_") || n.startsWith("SMOOTH_")) {
                        built.merge(n.toLowerCase(), 1, Integer::sum);
                    }
                }
            }
        }
        for (int bx = a[0] >> 4; bx <= a[3] >> 4; bx++) {
            for (int bz = a[2] >> 4; bz <= a[5] >> 4; bz++) {
                for (BlockState s : w.getChunkAt(bx, bz).getTileEntities()) {
                    Location l = s.getLocation();
                    if (l.getBlockX() >= a[0] && l.getBlockX() <= a[3] && l.getBlockZ() >= a[2] && l.getBlockZ() <= a[5] && l.getBlockY() >= a[1] && l.getBlockY() <= a[4]) tiles++;
                }
            }
        }
        Map<String, Integer> ents = new TreeMap<>();
        for (Entity e : w.getNearbyEntities(boxOf(a))) if (!(e instanceof Player) && !t.isOurs(e)) ents.merge(e.getType().name().toLowerCase(), 1, Integer::sum);
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int dx = -HX; dx <= HX; dx += 3) {
            for (int dz = -HZ; dz <= HZ; dz += 3) {
                int top = w.getHighestBlockYAt(cx + dx, cz + dz, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) - cy;
                lo = Math.min(lo, top);
                hi = Math.max(hi, top);
            }
        }
        return "Jail area " + a[0] + ".." + a[3] + ", " + a[2] + ".." + a[5] + ": " + tiles + " block entities, built-looking blocks: "
                + (built.isEmpty() ? "none" : built) + ". Entities: " + (ents.isEmpty() ? "none" : ents)
                + ". Ground under the building relative to the floor: " + lo + " to +" + hi + ".";
    }

    private static BoundingBox boxOf(int[] a) {
        return new BoundingBox(a[0], a[1], a[2], a[3] + 1, a[4] + 1, a[5] + 1);
    }

    /** Centre = the floor block in the middle of the station; the front door faces north (towards -z). */
    public String build(World world, int x, int y, int z) throws IOException {
        if (built()) throw new IOException("A jail is already built. Run /mrtown jail undo first.");
        this.w = world;
        this.cx = x;
        this.cy = y;
        this.cz = z;
        int[] gate = plazaGate();
        int[] a = area(gate);
        long t0 = System.currentTimeMillis();
        Structure st = Bukkit.getStructureManager().createStructure();
        Location corner = new Location(w, a[0], a[1], a[2]);
        BlockVector size = new BlockVector(a[3] - a[0] + 1, a[4] - a[1] + 1, a[5] - a[2] + 1);
        st.fill(corner, size, true);
        Bukkit.getStructureManager().saveStructure(structureFile(), st);
        YamlConfiguration meta = new YamlConfiguration();
        meta.set("world", w.getName());
        meta.set("centre", List.of(x, y, z));
        meta.set("area", List.of(a[0], a[1], a[2], a[3], a[4], a[5]));
        meta.set("saved-entities", st.getEntities().size());
        meta.set("created", System.currentTimeMillis());
        meta.save(metaFile());
        for (Entity e : w.getNearbyEntities(boxOf(a))) if (loose(e)) e.remove();

        panes.clear();
        if (gate != null) path(gate);
        shell();
        office();
        cellBlock();
        outside();
        for (Block b : panes) connect(b);
        int lifted = unstick(a);

        cells.clear();
        for (int[] c : CELLS) cells.add(new Location(w, cx + (c[0] + c[1]) / 2.0 + 0.5, cy + 1, cz + 4.5, 180, 0));
        release = new Location(w, cx + 0.5, cy + 1, cz - HZ - 2 + 0.5, 180, 0);
        box = new BoundingBox(cx - HX - 1, cy - DOWN, cz - HZ - 1, cx + HX + 2, cy + UP, cz + HZ + 2);
        boxWorld = w.getName();
        save();
        return "Guardhouse built at " + w.getName() + " " + x + " " + y + " " + z + " in " + (System.currentTimeMillis() - t0) + "ms with "
                + cells.size() + " cells" + (gate != null ? " and a path to the plaza's south gate" : "") + " (" + lifted
                + " mobs lifted out of new blocks). Undo with /mrtown jail undo.";
    }

    private static final int[][] CELLS = {{-8, -6}, {-4, -2}, {2, 4}, {6, 8}};

    private boolean inCell(int dx) {
        for (int[] c : CELLS) if (dx >= c[0] && dx <= c[1]) return true;
        return false;
    }

    private void shell() {
        for (int dx = -HX - 1; dx <= HX + 1; dx++) {
            for (int dz = -HZ - 1; dz <= HZ + 1; dz++) {
                for (int dy = 1; dy <= UP; dy++) set(dx, dy, dz, Material.AIR);
                for (int dy = -DOWN; dy < 0; dy++) {
                    Block b = at(dx, dy, dz);
                    if (b.isPassable() || b.isLiquid()) b.setType(Material.STONE, false);
                }
                boolean inside = Math.abs(dx) <= HX && Math.abs(dz) <= HZ;
                if (!inside) set(dx, 0, dz, dz < -HZ ? Material.POLISHED_ANDESITE : Material.GRASS_BLOCK);
                else set(dx, 0, dz, Material.POLISHED_ANDESITE);
            }
        }
        for (int dx = -HX; dx <= HX; dx++) {
            for (int dz = -HZ; dz <= HZ; dz++) {
                boolean edge = Math.abs(dx) == HX || Math.abs(dz) == HZ;
                boolean pillar = Math.abs(dx) == HX && Math.abs(dz) == HZ || Math.abs(dz) == HZ && Math.abs(dx) == 4 || Math.abs(dx) == HX && dz == -1;
                if (edge) {
                    for (int dy = 1; dy <= 5; dy++) set(dx, dy, dz, pillar || dy == 1 ? Material.POLISHED_DEEPSLATE : brick(dx, dy, dz));
                    set(dx, 6, dz, Material.STONE_BRICKS);
                    if (Math.abs(dx) == HX && Math.abs(dz) == HZ) {
                        set(dx, 7, dz, Material.CHISELED_STONE_BRICKS);
                        set(dx, 8, dz, Material.LANTERN);
                    } else {
                        slab(dx, 7, dz, Material.STONE_BRICK_SLAB);
                    }
                } else {
                    set(dx, 6, dz, Material.SMOOTH_STONE);
                }
            }
        }
        // Front door (3 wide) with a lintel and a blue light above it.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 1; dy <= 3; dy++) set(dx, dy, -HZ, Material.AIR);
            set(dx, 4, -HZ, Material.CHISELED_STONE_BRICKS);
            set(dx, 5, -HZ, Material.BLUE_STAINED_GLASS);
        }
        tower();
        // Office windows: front and sides.
        for (int dx : new int[]{-7, -6, 6, 7}) for (int dy = 2; dy <= 3; dy++) pane(dx, dy, -HZ, Material.GLASS_PANE);
        for (int side : new int[]{-HX, HX}) for (int dz = -5; dz <= -3; dz++) for (int dy = 2; dy <= 3; dy++) pane(side, dy, dz, Material.GLASS_PANE);
    }

    /** Stone bricks with some moss and cracks, like the plaza. */
    private static Material brick(int x, int y, int z) {
        int h = Math.abs((x * 73856093) ^ (y * 19349663) ^ (z * 83492791)) % 100;
        return h < 12 ? Material.MOSSY_STONE_BRICKS : h < 22 ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS;
    }

    /** Watchtower on the front-west corner with a blue police light on top, so the station is easy to spot from the plaza. */
    private void tower() {
        int x0 = -HX, x1 = -HX + 3, z0 = -HZ, z1 = -HZ + 3;
        for (int dx = x0; dx <= x1; dx++) {
            for (int dz = z0; dz <= z1; dz++) {
                boolean edge = dx == x0 || dx == x1 || dz == z0 || dz == z1;
                boolean corner = (dx == x0 || dx == x1) && (dz == z0 || dz == z1);
                for (int dy = 6; dy <= 10; dy++) {
                    if (!edge) set(dx, dy, dz, dy == 6 ? Material.SMOOTH_STONE : Material.AIR);
                    else if (corner) set(dx, dy, dz, Material.POLISHED_DEEPSLATE);
                    else if ((dy == 8 || dy == 9)) pane(dx, dy, dz, Material.GLASS_PANE);
                    else set(dx, dy, dz, brick(dx, dy, dz));
                }
                set(dx, 11, dz, edge ? Material.POLISHED_DEEPSLATE : Material.SEA_LANTERN);
                if (!edge) set(dx, 12, dz, Material.BLUE_STAINED_GLASS);
                else if (corner) set(dx, 12, dz, Material.LANTERN);
            }
        }
    }

    private void office() {
        for (int dx = -HX + 1; dx <= HX - 1; dx++) for (int dz = -HZ + 1; dz <= -2; dz++) set(dx, 0, dz, Material.SPRUCE_PLANKS);
        for (int dz = -HZ + 1; dz <= -2; dz++) set(0, 1, dz, Material.BLUE_CARPET);
        // Desk with the chief behind it.
        for (int dx = -6; dx <= -4; dx++) slab(dx, 1, -4, Material.DARK_OAK_SLAB, Slab.Type.TOP);
        BlockData lectern = Material.LECTERN.createBlockData();
        ((Directional) lectern).setFacing(BlockFace.NORTH);
        at(-3, 1, -4).setBlockData(lectern, false);
        for (int dx = -8; dx <= -6; dx++) {
            set(dx, 1, -2, dx == -7 ? Material.BARREL : Material.BOOKSHELF);
            set(dx, 2, -2, Material.BOOKSHELF);
        }
        set(-8, 1, -6, Material.POTTED_FERN);
        set(8, 1, -2, Material.POTTED_FERN);
        // Waiting bench on the east wall.
        for (int dz = -6; dz <= -4; dz++) {
            BlockData s = Material.SPRUCE_STAIRS.createBlockData();
            ((Directional) s).setFacing(BlockFace.EAST);
            at(8, 1, dz).setBlockData(s, false);
        }
        hanging(-4, 5, -4);
        hanging(4, 5, -4);
        // Wall between the office and the cells, with bars to look through.
        for (int dx = -HX + 1; dx <= HX - 1; dx++) {
            for (int dy = 1; dy <= 5; dy++) {
                if (Math.abs(dx) <= 1 && dy <= 3) pane(dx, dy, -1, Material.IRON_BARS);
                else set(dx, dy, -1, Material.STONE_BRICKS);
            }
        }
        npc("warden", "<blue>Chief Warden Holt", "<gray>Records · Deputies · Wanted list", cx - 4.5, cz - 2.5, 180, "#1F2A44",
                Map.of(EquipmentSlot.HEAD, Material.IRON_HELMET, EquipmentSlot.HAND, Material.WRITABLE_BOOK, EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE,
                        EquipmentSlot.LEGS, Material.LEATHER_LEGGINGS));
        board = w.spawn(new Location(w, cx - 7.0, cy + 3.3, cz - 4.0), TextDisplay.class, d -> {
            d.text(SmpPlugin.mm("<red><bold>WANTED"));
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setBackgroundColor(Color.fromARGB(160, 30, 20, 10));
            d.setPersistent(true);
            d.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        }).getUniqueId();
    }

    private void cellBlock() {
        for (int dx = -HX + 1; dx <= HX - 1; dx++) {
            for (int dz = 0; dz <= HZ - 1; dz++) set(dx, 0, dz, dz <= 1 ? Material.POLISHED_ANDESITE : Material.SMOOTH_STONE);
            for (int dy = 1; dy <= 5; dy++) {
                if (inCell(dx) && dy <= 3) pane(dx, dy, 2, Material.IRON_BARS);
                else set(dx, dy, 2, Material.STONE_BRICKS);
            }
            if (!inCell(dx)) for (int dz = 3; dz <= HZ - 1; dz++) for (int dy = 1; dy <= 5; dy++) set(dx, dy, dz, Material.STONE_BRICKS);
        }
        hanging(-6, 5, 0);
        hanging(6, 5, 0);
        for (int[] c : CELLS) {
            int mid = (c[0] + c[1]) / 2;
            slab(c[0], 1, 5, Material.SPRUCE_SLAB);
            slab(c[0], 1, 6, Material.SPRUCE_SLAB);
            set(c[1], 1, 6, Material.CAULDRON);
            pane(mid, 3, HZ, Material.IRON_BARS);
            hanging(mid, 5, 4);
        }
    }

    private void outside() {
        for (int side : new int[]{-3, 3}) {
            set(side, 1, -HZ - 1, Material.POLISHED_DEEPSLATE_WALL);
            set(side, 2, -HZ - 1, Material.DARK_OAK_FENCE);
            set(side, 3, -HZ - 1, Material.DARK_OAK_FENCE);
            set(side, 4, -HZ - 1, Material.LANTERN);
        }
        for (int side : new int[]{-2, 2}) {
            BlockData banner = Material.BLUE_WALL_BANNER.createBlockData();
            ((Directional) banner).setFacing(BlockFace.NORTH);
            at(side, 3, -HZ - 1).setBlockData(banner, false);
        }
        hologram(cx + 0.5, cy + 8.2, cz - HZ + 0.5, "<blue><bold>⚖ TOWN WATCH ⚖</bold>\n<gray>Guardhouse & jail");
        npc("jailguard", "<blue>Sergeant Pike", "<gray>Station guard", cx + 2.5, cz - HZ - 1.5 + 0.0, 180, "#1F2A44",
                Map.of(EquipmentSlot.HEAD, Material.IRON_HELMET, EquipmentSlot.HAND, Material.IRON_SWORD, EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE,
                        EquipmentSlot.LEGS, Material.LEATHER_LEGGINGS, EquipmentSlot.FEET, Material.LEATHER_BOOTS));
    }

    /** 3-wide path from the plaza's south gate to the front door, following the ground, with lamp posts. */
    private void path(int[] gate) {
        int gx = gate[0], gz = gate[2], turnZ = cz - HZ - 3;
        List<int[]> pts = new ArrayList<>();
        int stepZ = turnZ >= gz ? 1 : -1;
        for (int z = gz; z != turnZ + stepZ; z += stepZ) pts.add(new int[]{gx, z, 0});
        int stepX = cx >= gx ? 1 : -1;
        for (int x = gx + stepX; x != cx + stepX; x += stepX) pts.add(new int[]{x, turnZ, 1});
        for (int z = turnZ + 1; z <= cz - HZ - 2; z++) pts.add(new int[]{cx, z, 0});
        int n = 0;
        for (int[] p : pts) {
            for (int o = -1; o <= 1; o++) {
                int x = p[2] == 0 ? p[0] + o : p[0], z = p[2] == 0 ? p[1] : p[1] + o;
                pave(x, z, o == 0 ? Material.SMOOTH_STONE : Material.POLISHED_DEEPSLATE);
            }
            if (++n % 9 == 0) {
                int x = p[2] == 0 ? p[0] + 2 : p[0], z = p[2] == 0 ? p[1] : p[1] + 2;
                Integer g = ground(x, z);
                if (g != null && !(Math.abs(x - cx) <= HX + 1 && Math.abs(z - cz) <= HZ + 1)) {
                    Block b = w.getBlockAt(x, g + 1, z);
                    b.setType(Material.POLISHED_DEEPSLATE_WALL, false);
                    b.getRelative(0, 1, 0).setType(Material.DARK_OAK_FENCE, false);
                    b.getRelative(0, 2, 0).setType(Material.DARK_OAK_FENCE, false);
                    b.getRelative(0, 3, 0).setType(Material.LANTERN, false);
                }
            }
        }
    }

    private Integer ground(int x, int z) {
        for (int y = cy + 5; y >= cy - 5; y--) {
            Block b = w.getBlockAt(x, y, z);
            if (b.getType().isSolid() && !b.getType().name().contains("LEAVES") && !b.getType().name().contains("LOG")) return y;
        }
        return null;
    }

    private void pave(int x, int z, Material m) {
        Integer g = ground(x, z);
        if (g == null) return;
        w.getBlockAt(x, g, z).setType(m, false);
        for (int dy = 1; dy <= 3; dy++) {
            Block b = w.getBlockAt(x, g + dy, z);
            if (!b.getType().isAir() && (b.isPassable() || dy <= 2 && !b.getType().name().contains("LEAVES"))) b.setType(Material.AIR, false);
        }
    }

    // ------------------------------------------------------------------ undo

    private static boolean loose(Entity e) {
        if (e instanceof Player) return false;
        return !(e instanceof LivingEntity) || e instanceof ArmorStand;
    }

    public String undo() throws IOException {
        if (!built()) throw new IOException("No generated jail to undo. (Cells set up by hand are removed with /mrtown jail clearcells.)");
        YamlConfiguration meta = YamlConfiguration.loadConfiguration(metaFile());
        w = Bukkit.getWorld(meta.getString("world", ""));
        if (w == null) throw new IOException("World " + meta.getString("world") + " not loaded.");
        List<Integer> a = meta.getIntegerList("area");
        int[] area = {a.get(0), a.get(1), a.get(2), a.get(3), a.get(4), a.get(5)};
        for (Player p : Bukkit.getOnlinePlayers()) if (t.data(p).jailed()) t.release(p, "The jail is closing.");
        Structure st = Bukkit.getStructureManager().loadStructure(structureFile());
        for (Entity e : w.getNearbyEntities(boxOf(area))) {
            if (e.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) e.remove();
        }
        t.plugin().npcs().removeGroup("jail");
        for (int y = area[4]; y >= area[1]; y--) {
            for (int x = area[0]; x <= area[3]; x++) for (int z = area[2]; z <= area[5]; z++) w.getBlockAt(x, y, z).setType(Material.AIR, false);
        }
        EntityTransformer onlyLoose = (region, x, y, z, entity, allowed) -> loose(entity);
        st.place(new Location(w, area[0], area[1], area[2]), true, StructureRotation.NONE, Mirror.NONE, 0, 1.0f, new Random(), List.of(), List.of(onlyLoose));
        File done = new File(t.plugin().getDataFolder(), "jail-undone-" + System.currentTimeMillis() + ".nbt");
        structureFile().renameTo(done);
        metaFile().delete();
        cells.clear();
        release = null;
        box = null;
        boxWorld = null;
        board = null;
        save();
        return "Guardhouse removed and the area restored exactly as before. Snapshot kept as " + done.getName() + ".";
    }

    private int unstick(int[] a) {
        int n = 0;
        for (Entity e : w.getNearbyEntities(boxOf(a))) {
            if (!(e instanceof LivingEntity) || e instanceof Player || t.isOurs(e) || t.plugin().npcs().idOf(e) != null) continue;
            Location l = e.getLocation();
            int x = l.getBlockX(), z = l.getBlockZ();
            if (w.getBlockAt(x, l.getBlockY(), z).isPassable() && w.getBlockAt(x, l.getBlockY() + 1, z).isPassable()) continue;
            for (int y = l.getBlockY() + 1; y <= cy + UP + 2; y++) {
                if (w.getBlockAt(x, y, z).isPassable() && w.getBlockAt(x, y + 1, z).isPassable() && !w.getBlockAt(x, y - 1, z).isPassable()) {
                    e.teleport(new Location(w, l.getX(), y, l.getZ(), l.getYaw(), l.getPitch()));
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ builder helpers

    private Block at(int dx, int dy, int dz) {
        return w.getBlockAt(cx + dx, cy + dy, cz + dz);
    }

    private void set(int dx, int dy, int dz, Material m) {
        at(dx, dy, dz).setType(m, false);
    }

    private void pane(int dx, int dy, int dz, Material m) {
        set(dx, dy, dz, m);
        panes.add(at(dx, dy, dz));
    }

    private void slab(int dx, int dy, int dz, Material m) {
        slab(dx, dy, dz, m, Slab.Type.BOTTOM);
    }

    private void slab(int dx, int dy, int dz, Material m, Slab.Type type) {
        Slab s = (Slab) m.createBlockData();
        s.setType(type);
        at(dx, dy, dz).setBlockData(s, false);
    }

    private void hanging(int dx, int dy, int dz) {
        Lantern l = (Lantern) Material.LANTERN.createBlockData();
        l.setHanging(true);
        at(dx, dy, dz).setBlockData(l, false);
    }

    /** Bars and panes placed without physics don't join up by themselves. */
    private static void connect(Block b) {
        if (!(b.getBlockData() instanceof MultipleFacing mf)) return;
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            Block n = b.getRelative(f);
            Material m = n.getType();
            boolean join = m.isOccluding() || m == Material.IRON_BARS || m.name().endsWith("GLASS_PANE");
            if (mf.getAllowedFaces().contains(f)) mf.setFace(f, join);
        }
        b.setBlockData(mf, false);
    }

    private void npc(String id, String name, String title, double x, double z, float yaw, String leather, Map<EquipmentSlot, Material> gear) {
        NpcDef d = new NpcDef();
        d.id = id;
        d.name = name;
        d.title = title;
        d.world = w.getName();
        d.x = x;
        d.y = cy + 1;
        d.z = z;
        d.yaw = yaw;
        d.group = "jail";
        d.equipment.putAll(gear);
        if (leather != null) d.leatherColor = Color.fromRGB(Integer.parseInt(leather.substring(1), 16));
        t.plugin().npcs().put(d);
    }

    private void hologram(double x, double y, double z, String text) {
        w.spawn(new Location(w, x, y, z), TextDisplay.class, d -> {
            d.text(SmpPlugin.mm(text));
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setBackgroundColor(Color.fromARGB(90, 0, 0, 0));
            d.setPersistent(true);
            d.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
    }

    // ------------------------------------------------------------------ prisoner rules and protection

    private boolean prisoner(Player p) {
        TownData d = t.data(p);
        return d.jailed() || d.inService();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!rules) return;
        Player p = e.getPlayer();
        if (!prisoner(p) || p.hasPermission("mrsmp.admin")) return;
        String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        if (cmd.contains(":")) cmd = cmd.substring(cmd.indexOf(':') + 1);
        if (cmd.equals("bail") || cmd.equals("service") || cmd.equals("rep") || cmd.equals("surrender")) return;
        if (t.plugin().getConfig().getStringList("town.jail-allowed-commands").contains(cmd)) return;
        e.setCancelled(true);
        p.sendMessage(SmpPlugin.mm(t.data(p).inService() ? "<red>Not during community service. <gray>Finish picking up the litter first."
                : "<red>You can't do that from a jail cell. <gray>/bail to pay your way out."));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!rules) return;
        Player p = e.getPlayer();
        if (!prisoner(p) || t.ourTeleport(p) || p.hasPermission("mrsmp.admin")) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        TownData d = t.data(e.getPlayer());
        if (rules && d.inService() && t.jail().releasePoint() != null) {
            e.setRespawnLocation(t.jail().releasePoint());
            return;
        }
        if (d.jailed() && id.equals(d.jailTown) && ready()) {
            if (d.cell < 0 || d.cell >= cells.size()) d.cell = freeCell();
            e.setRespawnLocation(cells.get(d.cell).clone());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!rules) return;
        if (e.getEntity() instanceof Player p && prisoner(p) && e.getCause() != EntityDamageEvent.DamageCause.VOID) {
            e.setCancelled(true);
            return;
        }
        if (e instanceof EntityDamageByEntityEvent by) {
            Entity d = by.getDamager();
            if (d instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player s) d = s;
            if (d instanceof Player p && prisoner(p)) e.setCancelled(true);
        }
    }

    private boolean protectedHere(Player p, Location l) {
        return ((rules && prisoner(p)) || contains(l)) && !p.hasPermission("mrsmp.admin");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (protectedHere(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (protectedHere(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (protectedHere(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }
}
