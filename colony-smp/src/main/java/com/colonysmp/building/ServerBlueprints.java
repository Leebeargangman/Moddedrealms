package com.colonysmp.building;

import com.colonysmp.ColonySMP;
import com.colonysmp.model.Colony;
import com.colonysmp.model.CustomBlueprint;
import org.bukkit.Bukkit;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Plans every colony can order, from plugins/ColonySMP/blueprints/: copies saved by staff (.yml, the same format as a
 * colony's own copies) and vanilla structure-block files (.nbt). The first time, two starter plans are written there
 * (a hearth cottage and a stone wall with a gate) so there's something to start from. A colony's own copy with the
 * same name comes first.
 */
public final class ServerBlueprints {

    private static final Map<String, CustomBlueprint> ALL = Collections.synchronizedMap(new LinkedHashMap<>());

    private final ColonySMP plugin;

    public ServerBlueprints(ColonySMP plugin) {
        this.plugin = plugin;
    }

    public File folder() {
        return new File(plugin.getDataFolder(), "blueprints");
    }

    /** Reads the folder (writing the starter plans the first time). */
    public void load() {
        File dir = folder();
        if (!dir.exists() && dir.mkdirs()) writeStarters(dir);
        Map<String, CustomBlueprint> found = new LinkedHashMap<>();
        File[] files = dir.listFiles();
        if (files != null) {
            java.util.Arrays.sort(files);
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                CustomBlueprint cb = null;
                try {
                    if (n.endsWith(".yml")) cb = CustomBlueprint.load(YamlConfiguration.loadConfiguration(f));
                    else if (n.endsWith(".nbt")) cb = fromStructure(Bukkit.getStructureManager().loadStructure(f), display(f.getName()));
                    else continue;
                } catch (IOException | RuntimeException e) {
                    plugin.getLogger().warning("blueprints/" + f.getName() + " can't be read: " + e.getMessage());
                    continue;
                }
                if (cb == null) {
                    plugin.getLogger().warning("blueprints/" + f.getName() + " isn't a blueprint the builders can use.");
                    continue;
                }
                String bad = badBlock(cb);
                if (bad != null) {
                    plugin.getLogger().warning("blueprints/" + f.getName() + " has a block this server doesn't know: " + bad);
                    continue;
                }
                if (cb.sx * cb.sy * cb.sz > plugin.settings().blueprintMaxBlocks * 4L) {
                    plugin.getLogger().warning("blueprints/" + f.getName() + " is too big (" + cb.size() + ").");
                    continue;
                }
                found.put(CustomBlueprint.key(cb.name), cb);
            }
        }
        synchronized (ALL) {
            ALL.clear();
            ALL.putAll(found);
        }
        if (!found.isEmpty()) plugin.getLogger().info("Server blueprints: " + String.join(", ", found.keySet()));
    }

    /** The first palette entry the server can't read, or null. */
    private static String badBlock(CustomBlueprint cb) {
        for (String s : cb.palette) {
            try {
                Bukkit.createBlockData(s);
            } catch (IllegalArgumentException e) {
                return s;
            }
        }
        return null;
    }

    /**
     * Staff preview: puts the whole plan down at once, two blocks in front of the player and facing the way they
     * look, the way the builders would (beds and doors whole). For checking a plan, not for colonies.
     */
    public int preview(org.bukkit.entity.Player p, CustomBlueprint cb) {
        Blueprint bp = Blueprint.custom(cb);
        BlockFace f = BlueprintManager.cardinal(p.getLocation().getYaw());
        org.bukkit.block.Block at = p.getLocation().getBlock().getRelative(f, 2);
        com.colonysmp.util.BlockPos origin = new com.colonysmp.util.BlockPos(at.getX(), at.getY(), at.getZ());
        org.bukkit.World w = p.getWorld();
        int n = 0;
        for (Blueprint.Step s : bp.steps) {
            com.colonysmp.util.BlockPos pos = bp.pos(origin, f, s);
            org.bukkit.block.Block b = w.getBlockAt(pos.x(), pos.y(), pos.z());
            if (s.data() == null) {
                if (s.spec() == Blueprint.Spec.AIR && !b.getType().isAir()) b.setType(org.bukkit.Material.AIR, false);
                continue;
            }
            org.bukkit.block.data.BlockData d = Blueprint.turned(s.data(), cb.facing, f);
            if (s.role() == Blueprint.Role.BED_FOOT && d instanceof org.bukkit.block.data.type.Bed bed) {
                org.bukkit.block.data.type.Bed head = (org.bukkit.block.data.type.Bed) bed.clone();
                head.setPart(org.bukkit.block.data.type.Bed.Part.HEAD);
                b.getRelative(bed.getFacing()).setBlockData(head, false);
            } else if (s.role() == Blueprint.Role.DOOR_LOWER && d instanceof org.bukkit.block.data.Bisected bi) {
                org.bukkit.block.data.Bisected top = (org.bukkit.block.data.Bisected) bi.clone();
                top.setHalf(org.bukkit.block.data.Bisected.Half.TOP);
                b.getRelative(BlockFace.UP).setBlockData(top, false);
            }
            b.setBlockData(d, false);
            n++;
        }
        return n;
    }

    private static String display(String file) {
        String base = file.replaceFirst("\\.[^.]+$", "").replace('_', ' ').replace('-', ' ').trim();
        return base.isEmpty() ? "Plan" : Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }

    /** A structure file as a plan: the structure's x runs right, z away, as if copied looking south. */
    static CustomBlueprint fromStructure(Structure s, String name) {
        if (s == null || s.getPalettes().isEmpty()) return null;
        BlockVector size = s.getSize();
        int sx = size.getBlockX(), sy = size.getBlockY(), sz = size.getBlockZ();
        if (sx <= 0 || sy <= 0 || sz <= 0) return null;
        List<String> palette = new ArrayList<>();
        palette.add("minecraft:air");
        Map<String, Integer> index = new LinkedHashMap<>();
        index.put("minecraft:air", 0);
        int[] blocks = new int[sx * sy * sz];
        for (BlockState b : s.getPalettes().get(0).getBlocks()) {
            String data = b.getBlockData().getAsString();
            if (b.getType().isAir() || data.startsWith("minecraft:structure_void")) continue;
            Integer i = index.get(data);
            if (i == null) {
                i = palette.size();
                palette.add(data);
                index.put(data, i);
            }
            var loc = b.getLocation();
            // looking south, "right" is west: mirror x so the plan comes out the way it was saved
            int x = sx - 1 - loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) continue;
            blocks[(y * sz + z) * sx + x] = i;
        }
        CustomBlueprint cb = new CustomBlueprint(name, sx, sy, sz, BlockFace.SOUTH, 0, palette, blocks);
        cb.author = "server";
        return cb.solid() == 0 ? null : cb;
    }

    /** Saves a colony's copy as a plan for every colony (staff only). */
    public String save(CustomBlueprint cb) {
        File f = new File(folder(), CustomBlueprint.key(cb.name).replaceAll("[^a-z0-9_-]+", "_") + ".yml");
        YamlConfiguration y = new YamlConfiguration();
        cb.save(y);
        try {
            folder().mkdirs();
            y.save(f);
        } catch (IOException e) {
            return e.getMessage();
        }
        load();
        return null;
    }

    // ───────────── lookups ─────────────

    public static CustomBlueprint get(String key) {
        return ALL.get(CustomBlueprint.key(key));
    }

    public static List<String> keys() {
        synchronized (ALL) {
            return new ArrayList<>(ALL.keySet());
        }
    }

    /** A colony's own copy, or else the server's plan of that name. */
    public static CustomBlueprint find(Colony c, String key) {
        String k = CustomBlueprint.key(key);
        CustomBlueprint own = c == null ? null : c.blueprints.get(k);
        return own != null ? own : ALL.get(k);
    }

    // ───────────── starter plans ─────────────

    private void writeStarters(File dir) {
        for (CustomBlueprint cb : List.of(cottage(), wall())) {
            YamlConfiguration y = new YamlConfiguration();
            cb.save(y);
            try {
                y.save(new File(dir, CustomBlueprint.key(cb.name).replace(' ', '_') + ".yml"));
            } catch (IOException e) {
                plugin.getLogger().warning("Couldn't write the starter blueprint " + cb.name + ": " + e.getMessage());
            }
        }
    }

    /** Builds a plan in the copier's frame (x right, y up, z away from the front), as if copied looking north. */
    private static final class Plan {
        final int sx, sy, sz;
        final int[] blocks;
        final List<String> palette = new ArrayList<>(List.of("minecraft:air"));

        Plan(int sx, int sy, int sz) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            blocks = new int[sx * sy * sz];
        }

        int id(String data) {
            int i = palette.indexOf(data);
            if (i >= 0) return i;
            palette.add(data);
            return palette.size() - 1;
        }

        void set(int x, int y, int z, String data) {
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return;
            blocks[(y * sz + z) * sx + x] = id(data);
        }

        void box(int x0, int y0, int z0, int x1, int y1, int z1, String data) {
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) set(x, y, z, data);
        }

        CustomBlueprint done(String name, int sink) {
            CustomBlueprint cb = new CustomBlueprint(name, sx, sy, sz, BlockFace.NORTH, sink, palette, blocks);
            cb.author = "server";
            return cb;
        }
    }

    /**
     * Hearth Cottage: a 7x7 spruce and stone cottage for two, with a stone hearth (a furnace and a campfire under a
     * chimney) that keeps it warm in winter, a chest, beds and a door at the front.
     */
    static CustomBlueprint cottage() {
        Plan p = new Plan(7, 7, 7);
        String floor = "minecraft:spruce_planks", wall = "minecraft:spruce_planks", post = "minecraft:stripped_spruce_log[axis=y]",
                stone = "minecraft:cobblestone", roof = "minecraft:dark_oak_slab[type=bottom,waterlogged=false]";
        p.box(0, 0, 0, 6, 0, 6, stone);
        p.box(1, 0, 1, 5, 0, 5, floor);
        // walls with log corners
        p.box(0, 1, 0, 6, 3, 6, wall);
        p.box(1, 1, 1, 5, 3, 5, "minecraft:air");
        for (int[] c : new int[][]{{0, 0}, {6, 0}, {0, 6}, {6, 6}}) p.box(c[0], 1, c[1], c[0], 3, c[1], post);
        // windows on the sides
        p.set(0, 2, 3, "minecraft:glass_pane[east=false,north=true,south=true,waterlogged=false,west=false]");
        p.set(6, 2, 3, "minecraft:glass_pane[east=false,north=true,south=true,waterlogged=false,west=false]");
        // front door (z = 0 is the front)
        p.set(3, 1, 0, "minecraft:spruce_door[facing=north,half=lower,hinge=left,open=false,powered=false]");
        p.set(3, 2, 0, "minecraft:spruce_door[facing=north,half=upper,hinge=left,open=false,powered=false]");
        p.set(3, 3, 0, "minecraft:lantern[hanging=false,waterlogged=false]");
        // ceiling and a flat roof with a rim
        p.box(0, 4, 0, 6, 4, 6, floor);
        p.box(0, 5, 0, 6, 5, 0, roof);
        p.box(0, 5, 6, 6, 5, 6, roof);
        p.box(0, 5, 0, 0, 5, 6, roof);
        p.box(6, 5, 0, 6, 5, 6, roof);
        // the hearth at the back wall: stone surround, furnace, campfire and a chimney
        p.box(2, 1, 5, 4, 3, 5, stone);
        p.set(3, 1, 5, "minecraft:furnace[facing=south,lit=false]");
        p.set(3, 1, 4, "minecraft:campfire[facing=south,lit=true,signal_fire=false,waterlogged=false]");
        p.box(3, 4, 5, 3, 6, 5, stone);
        p.box(3, 4, 4, 3, 4, 4, stone);
        // two beds along the left wall, a chest on the right
        // (looking north, z runs north: a north-facing bed has its head at the higher z)
        p.set(1, 1, 1, "minecraft:red_bed[facing=north,occupied=false,part=foot]");
        p.set(1, 1, 2, "minecraft:red_bed[facing=north,occupied=false,part=head]");
        p.set(1, 1, 5, "minecraft:white_bed[facing=south,occupied=false,part=foot]");
        p.set(1, 1, 4, "minecraft:white_bed[facing=south,occupied=false,part=head]");
        p.set(5, 1, 1, "minecraft:chest[facing=west,type=single,waterlogged=false]");
        p.set(5, 1, 3, "minecraft:crafting_table");
        p.set(5, 3, 3, "minecraft:lantern[hanging=true,waterlogged=false]");
        return p.done("Hearth Cottage", 1);
    }

    /** Stone Wall and Gate: a 9-block stretch of stone-brick wall with a three-wide gate, a walkway and lanterns. */
    static CustomBlueprint wall() {
        Plan p = new Plan(9, 5, 3);
        String brick = "minecraft:stone_bricks";
        p.box(0, 0, 0, 8, 3, 2, brick);
        p.box(0, 4, 0, 8, 4, 0, "minecraft:stone_brick_wall[east=low,north=none,south=none,up=true,waterlogged=false,west=low]");
        p.box(0, 4, 2, 8, 4, 2, "minecraft:stone_brick_wall[east=low,north=none,south=none,up=true,waterlogged=false,west=low]");
        // the gate: three wide, two high, with oak gates on the front face
        p.box(3, 1, 0, 5, 2, 2, "minecraft:air");
        for (int x = 3; x <= 5; x++) {
            p.set(x, 1, 0, "minecraft:oak_fence_gate[facing=north,in_wall=false,open=false,powered=false]");
            p.set(x, 2, 0, "minecraft:oak_fence_gate[facing=north,in_wall=false,open=false,powered=false]");
        }
        p.set(2, 4, 1, "minecraft:lantern[hanging=false,waterlogged=false]");
        p.set(6, 4, 1, "minecraft:lantern[hanging=false,waterlogged=false]");
        return p.done("Stone Wall and Gate", 1);
    }
}
