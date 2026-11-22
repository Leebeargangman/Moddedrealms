package net.moddedrealms.smp.spawn;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.moddedrealms.smp.SmpPlugin;
import net.moddedrealms.smp.npc.NpcDef;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.Axis;

/**
 * Builds the spawn plaza procedurally around a centre block: a paved circular square with a tiered fountain,
 * four avenues with gateways, a hedge ring with lamp posts, benches, flowering azalea trees, and four stations:
 * Quest Pavilion (NE), Crate Shrine (NW), Arcane Corner (SE) and Adventurers' Camp (SW), each with its NPCs
 * and a floating sign. Everything it replaces is saved first, so it can be undone exactly.
 */
public final class PlazaBuilder {
    public static final int R = 24;
    private static final int UP = 16, DOWN = 6;

    private final SmpPlugin plugin;
    private final NamespacedKey key;
    private World w;
    private int cx, cy, cz;

    public PlazaBuilder(SmpPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "plaza");
    }

    private File undoFile() {
        return new File(plugin.getDataFolder(), "plaza-undo.dat");
    }

    public boolean hasUndo() {
        return undoFile().exists();
    }

    /** Centre = the floor block under the fountain. Returns a short report. */
    public String build(World world, int x, int y, int z) throws IOException {
        if (hasUndo()) throw new IOException("A plaza is already built. Run /mrsmp spawn undo first.");
        this.w = world;
        this.cx = x;
        this.cy = y;
        this.cz = z;
        snapshot();
        long t0 = System.currentTimeMillis();

        clearAndPave();
        fountain();
        avenuesAndGates();
        hedgeAndLamps();
        benches();
        trees();
        questPavilion(11, -11);
        crateShrine(-11, -11);
        arcaneCorner(11, 11);
        adventurersCamp(-11, 11);
        hologram(0.5, 7.2, 0.5, "<gradient:#f6d365:#fda085><bold>Moddedrealms</bold></gradient>\n<gray>Colonies · Bosses · Quests\n<yellow>Talk to Elder Rowan to begin");
        npc("elder", "<gold>Elder Rowan", "<gray>Your story", 0.5, 6.5, 180, "#6b4f2a", Map.of(EquipmentSlot.HAND, Material.STICK,
                EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE, EquipmentSlot.LEGS, Material.LEATHER_LEGGINGS), 0, null);
        npc("townsfolk_1", "<white>Pip", null, 8.5, 0.5, 0, "#c94f4f", Map.of(EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE), 5, null);
        npc("townsfolk_2", "<white>Old Tom", null, -8.5, 0.5, 0, "#4f7fc9", Map.of(EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE,
                EquipmentSlot.HAND, Material.FISHING_ROD), 5, null);
        npc("townsfolk_3", "<white>Lina", null, 0.5, -8.5, 0, "#d9a441", Map.of(EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE,
                EquipmentSlot.HAND, Material.POPPY), 5, null);
        return "Plaza built at " + world.getName() + " " + x + " " + y + " " + z + " in " + (System.currentTimeMillis() - t0)
                + "ms. Suggested spawn: " + x + ".5 " + (y + 1) + " " + (z + 12) + ".5 facing north.";
    }

    // ------------------------------------------------------------------ pieces

    private void clearAndPave() {
        for (int dx = -R - 1; dx <= R + 1; dx++) {
            for (int dz = -R - 1; dz <= R + 1; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > R + 1.5) continue;
                for (int dy = 1; dy <= UP; dy++) set(dx, dy, dz, Material.AIR);
                if (d > R + 0.5) continue;
                for (int dy = -DOWN; dy < 0; dy++) {
                    Block b = at(dx, dy, dz);
                    if (b.isPassable() || b.isLiquid()) b.setType(Material.STONE, false);
                }
                set(dx, 0, dz, paving(dx, dz, d));
            }
        }
    }

    private Material paving(int dx, int dz, double d) {
        int ax = Math.abs(dx), az = Math.abs(dz);
        boolean avenue = ax <= 2 || az <= 2;
        if (d >= 4.5 && d < 5.5) return Material.DEEPSLATE_TILES;
        if (d >= 5.5 && d < 6.5) return Material.CHISELED_STONE_BRICKS;
        if (d >= 11.5 && d < 12.5) return Material.POLISHED_DEEPSLATE;
        if (avenue && d >= 6.5) {
            int o = ax <= 2 ? ax : az;
            if (ax <= 2 && az <= 2) return Material.SMOOTH_STONE;
            return o == 2 ? Material.POLISHED_DEEPSLATE : (o == 0 && (int) d % 4 == 0 ? Material.CHISELED_STONE_BRICKS : Material.SMOOTH_STONE);
        }
        int h = hash(dx, dz, 7) % 100;
        if (h < 50) return Material.STONE_BRICKS;
        if (h < 70) return Material.POLISHED_ANDESITE;
        if (h < 82) return Material.MOSSY_STONE_BRICKS;
        if (h < 92) return Material.CRACKED_STONE_BRICKS;
        return Material.ANDESITE;
    }

    private void fountain() {
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d <= 3.5) {
                    set(dx, -2, dz, Material.STONE);
                    set(dx, -1, dz, (dx + dz) % 3 == 0 ? Material.SEA_LANTERN : Material.PRISMARINE_BRICKS);
                    set(dx, 0, dz, Material.WATER);
                } else if (d <= 4.5) {
                    set(dx, 0, dz, Material.POLISHED_DEEPSLATE);
                    Slab slab = (Slab) Material.DEEPSLATE_BRICK_SLAB.createBlockData();
                    slab.setType(Slab.Type.BOTTOM);
                    at(dx, 1, dz).setBlockData(slab, false);
                }
            }
        }
        for (int dy = 0; dy <= 3; dy++) set(0, dy, 0, Material.QUARTZ_PILLAR);
        set(0, 4, 0, Material.CHISELED_QUARTZ_BLOCK);
        set(0, 5, 0, Material.END_ROD);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                set(dx, 3, dz, Material.SMOOTH_QUARTZ);
                Block water = at(dx, 4, dz);
                water.setType(Material.WATER, true);
            }
        }
        for (int[] p : new int[][]{{2, 2}, {-2, -1}, {1, -3}}) set(p[0], 1, p[1], Material.LILY_PAD);
        for (int[] p : new int[][]{{4, 0}, {-4, 0}, {0, 4}, {0, -4}}) {
            Lantern l = (Lantern) Material.LANTERN.createBlockData();
            at(p[0], 2, p[1]).setBlockData(l, false);
            set(p[0], 1, p[1], Material.POLISHED_DEEPSLATE_WALL);
        }
    }

    private void avenuesAndGates() {
        // Gateways where the avenues meet the hedge ring.
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            int fx = f.getModX(), fz = f.getModZ();
            int px = -fz, pz = fx; // perpendicular
            int dist = 23;
            for (int side : new int[]{-3, 3}) {
                int bx = fx * dist + px * side, bz = fz * dist + pz * side;
                for (int dy = 1; dy <= 4; dy++) set(bx, dy, bz, Material.STONE_BRICKS);
                set(bx, 5, bz, Material.CHISELED_STONE_BRICKS);
            }
            for (int o = -3; o <= 3; o++) {
                int bx = fx * dist + px * o, bz = fz * dist + pz * o;
                if (Math.abs(o) < 3) {
                    Orientable log = (Orientable) Material.DARK_OAK_LOG.createBlockData();
                    log.setAxis(px != 0 ? Axis.X : Axis.Z);
                    at(bx, 5, bz).setBlockData(log, false);
                }
                Slab s = (Slab) Material.DARK_OAK_SLAB.createBlockData();
                s.setType(Slab.Type.BOTTOM);
                at(bx, 6, bz).setBlockData(s, false);
            }
            for (int o : new int[]{-1, 1}) {
                Lantern l = (Lantern) Material.LANTERN.createBlockData();
                l.setHanging(true);
                at(fx * dist + px * o, 4, fz * dist + pz * o).setBlockData(l, false);
            }
        }
    }

    private void hedgeAndLamps() {
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < 23.5 || d >= 24.5) continue;
                if (Math.abs(dx) <= 3 || Math.abs(dz) <= 3) continue;
                for (int dy = 1; dy <= 2; dy++) {
                    Material m = hash(dx, dz + dy, 3) % 3 == 0 ? Material.FLOWERING_AZALEA_LEAVES : Material.AZALEA_LEAVES;
                    Leaves leaves = (Leaves) m.createBlockData();
                    leaves.setPersistent(true);
                    at(dx, dy, dz).setBlockData(leaves, false);
                }
            }
        }
        // Lamp posts just inside the hedge and on the inner ring, between avenues.
        for (int i = 0; i < 16; i++) {
            double a = Math.toRadians(11.25 + i * 22.5);
            if (i % 2 == 1) continue;
            lamp((int) Math.round(Math.cos(a) * 21.5), (int) Math.round(Math.sin(a) * 21.5));
        }
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(22.5 + i * 45);
            lamp((int) Math.round(Math.cos(a) * 12), (int) Math.round(Math.sin(a) * 12));
        }
    }

    private void lamp(int dx, int dz) {
        for (int dy = 1; dy <= 3; dy++) set(dx, dy, dz, dy == 1 ? Material.POLISHED_DEEPSLATE_WALL : Material.DARK_OAK_FENCE);
        set(dx, 4, dz, Material.LANTERN);
    }

    private void benches() {
        for (int side : new int[]{-1, 1}) {
            for (int start : new int[]{-6, 4}) {
                for (int o = 0; o < 3; o++) {
                    stair(8 * side, start + o, side > 0 ? BlockFace.EAST : BlockFace.WEST);
                    stair(start + o, 8 * side, side > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
                }
            }
        }
    }

    private void stair(int dx, int dz, BlockFace back) {
        BlockData d = Material.SPRUCE_STAIRS.createBlockData();
        ((Directional) d).setFacing(back);
        at(dx, 1, dz).setBlockData(d, false);
    }

    private void trees() {
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(22.5 + i * 45);
            int tx = (int) Math.round(Math.cos(a) * 19), tz = (int) Math.round(Math.sin(a) * 19);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    set(tx + dx, 0, tz + dz, Material.GRASS_BLOCK);
                    if (dx != 0 || dz != 0) {
                        Material[] flowers = {Material.POPPY, Material.CORNFLOWER, Material.ALLIUM, Material.OXEYE_DAISY, Material.AZURE_BLUET, Material.LILY_OF_THE_VALLEY};
                        set(tx + dx, 1, tz + dz, flowers[hash(tx + dx, tz + dz, 5) % flowers.length]);
                    }
                }
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) == 2 || Math.abs(dz) == 2) set(tx + dx, 0, tz + dz, Material.POLISHED_ANDESITE);
                }
            }
            Location base = loc(tx, 1, tz);
            at(tx, 1, tz).setType(Material.AIR, false);
            // Azalea: compact enough not to hang over the avenues and stations (cherry canopies did).
            w.generateTree(base, TreeType.AZALEA);
        }
    }

    private void floor(int sx, int sz, int half, Material m) {
        for (int dx = -half; dx <= half; dx++) for (int dz = -half; dz <= half; dz++) set(sx + dx, 0, sz + dz, m);
    }

    private void questPavilion(int sx, int sz) {
        floor(sx, sz, 3, Material.SPRUCE_PLANKS);
        for (int[] c : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) {
            for (int dy = 1; dy <= 4; dy++) set(sx + c[0], dy, sz + c[1], Material.SPRUCE_LOG);
        }
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                set(sx + dx, 5, sz + dz, Math.abs(dx) == 4 || Math.abs(dz) == 4 ? Material.DARK_OAK_PLANKS : Material.SPRUCE_PLANKS);
                if (Math.abs(dx) <= 2 && Math.abs(dz) <= 2) {
                    Slab s = (Slab) Material.DARK_OAK_SLAB.createBlockData();
                    s.setType(Slab.Type.BOTTOM);
                    at(sx + dx, 6, sz + dz).setBlockData(s, false);
                }
            }
        }
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            Lantern l = (Lantern) Material.LANTERN.createBlockData();
            l.setHanging(true);
            at(sx + c[0], 4, sz + c[1]).setBlockData(l, false);
        }
        // Quest board on the outer side: barrels, a lectern and bookshelves.
        set(sx + 2, 1, sz - 2, Material.BARREL);
        set(sx + 2, 2, sz - 2, Material.BARREL);
        set(sx + 1, 1, sz - 2, Material.BOOKSHELF);
        set(sx - 1, 1, sz - 2, Material.CARTOGRAPHY_TABLE);
        BlockData lectern = Material.LECTERN.createBlockData();
        ((Directional) lectern).setFacing(BlockFace.SOUTH);
        at(sx, 1, sz - 2).setBlockData(lectern, false);
        hologram(sx + 0.5, 7.4, sz + 0.5, "<yellow><bold>✦ Quest Pavilion ✦</bold>\n<gray>Daily & weekly quests · /quests");
        npc("questmaster", "<yellow>Quest Master Bram", "<gray>Daily & weekly quests", sx + 0.5, sz + 0.5, 225, "#3a6b35",
                Map.of(EquipmentSlot.HAND, Material.WRITABLE_BOOK, EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE,
                        EquipmentSlot.LEGS, Material.LEATHER_LEGGINGS), 0, null);
    }

    private void crateShrine(int sx, int sz) {
        floor(sx, sz, 3, Material.POLISHED_BLACKSTONE_BRICKS);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            if (Math.abs(dx) == 3 || Math.abs(dz) == 3) set(sx + dx, 0, sz + dz, Material.GILDED_BLACKSTONE);
        }
        Material crate = Material.matchMaterial("VAULT") != null ? Material.VAULT : Material.ENDER_CHEST;
        String[] ids = {"common", "rare", "mythic"};
        for (int k = 0; k < 3; k++) {
            int o = -2 + 2 * k;
            set(sx + o, 1, sz - 1, crate);
            set(sx + o, 0, sz - 1, Material.CRYING_OBSIDIAN);
            plugin.crates().registerBlock(loc(sx + o, 1, sz - 1), ids[k]);
        }
        for (int[] c : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) {
            set(sx + c[0], 1, sz + c[1], Material.POLISHED_BLACKSTONE_WALL);
            set(sx + c[0], 2, sz + c[1], Material.POLISHED_BLACKSTONE_WALL);
            set(sx + c[0], 3, sz + c[1], Material.SOUL_LANTERN);
        }
        for (int[] c : new int[][]{{-1, -2}, {1, -2}, {-3, 0}, {3, 0}}) set(sx + c[0], 1, sz + c[1], Material.AMETHYST_CLUSTER);
        hologram(sx + 0.5, 4.2, sz - 0.5, "<light_purple><bold>✦ Crate Shrine ✦</bold>\n<white>Common · Rare · Mythic\n<gray>Right-click to open · Left-click to preview");
        npc("cratekeeper", "<light_purple>Keeper Isolde", "<gray>Crates & keys", sx + 0.5, sz + 1.5, 135, "#6a3d9a",
                Map.of(EquipmentSlot.HAND, Material.TRIPWIRE_HOOK, EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE,
                        EquipmentSlot.LEGS, Material.LEATHER_LEGGINGS), 0, null);
    }

    private void arcaneCorner(int sx, int sz) {
        floor(sx, sz, 3, Material.DEEPSLATE_TILES);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                boolean edge = Math.abs(dx) == 3 || Math.abs(dz) == 3;
                boolean gap = Math.abs(dx) <= 1 && dz == -3 || dx == -3 && Math.abs(dz) <= 1;
                if (edge && !gap) {
                    set(sx + dx, 1, sz + dz, Material.BOOKSHELF);
                    if ((dx + dz) % 2 == 0) set(sx + dx, 2, sz + dz, Material.BOOKSHELF);
                }
            }
        }
        set(sx, 1, sz, Material.ENCHANTING_TABLE);
        set(sx, 0, sz, Material.AMETHYST_BLOCK);
        for (int[] c : new int[][]{{-2, 2}, {2, 2}, {2, -2}}) {
            set(sx + c[0], 1, sz + c[1], Material.AMETHYST_BLOCK);
            set(sx + c[0], 2, sz + c[1], Material.AMETHYST_CLUSTER);
        }
        hologram(sx + 0.5, 4.0, sz + 0.5, "<aqua><bold>✦ Arcane Corner ✦</bold>\n<gray>60+ new enchantments");
        npc("enchanter", "<aqua>Enchanter Vael", "<gray>Enchantments", sx - 1.5, sz - 1.5, 315, "#2b4c7e",
                Map.of(EquipmentSlot.HAND, Material.ENCHANTED_BOOK, EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE,
                        EquipmentSlot.LEGS, Material.LEATHER_LEGGINGS, EquipmentSlot.FEET, Material.LEATHER_BOOTS), 0, null);
    }

    private void adventurersCamp(int sx, int sz) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                set(sx + dx, 0, sz + dz, hash(sx + dx, sz + dz, 11) % 3 == 0 ? Material.PODZOL : Material.COARSE_DIRT);
            }
        }
        BlockData fire = Material.CAMPFIRE.createBlockData();
        if (fire instanceof Lightable l) l.setLit(true);
        at(sx, 1, sz).setBlockData(fire, false);
        for (int[] c : new int[][]{{-2, 0, 0}, {2, 0, 0}, {0, -2, 1}, {0, 2, 1}}) {
            Orientable log = (Orientable) Material.STRIPPED_SPRUCE_LOG.createBlockData();
            log.setAxis(c[2] == 0 ? Axis.Z : Axis.X);
            at(sx + c[0], 1, sz + c[1]).setBlockData(log, false);
        }
        set(sx - 3, 1, sz - 3, Material.BARREL);
        set(sx + 3, 1, sz + 3, Material.TARGET);
        set(sx - 3, 1, sz + 3, Material.SMITHING_TABLE);
        set(sx + 3, 1, sz - 3, Material.GRINDSTONE);
        hologram(sx + 0.5, 4.2, sz + 0.5, "<red><bold>✦ Adventurers' Camp ✦</bold>\n<gray>Bosses · Colonies · Travel");
        npc("herald", "<red>Herald Kael", "<gray>Bosses of the deep", sx + 2.5, sz + 2.5, 45, null,
                Map.of(EquipmentSlot.HAND, Material.NETHERITE_SWORD, EquipmentSlot.CHEST, Material.NETHERITE_CHESTPLATE,
                        EquipmentSlot.HEAD, Material.IRON_HELMET), 0, null);
        npc("envoy", "<gold>Envoy Mira", "<gray>The colonies", sx - 2.5, sz + 1.5, 135, "#b5651d",
                Map.of(EquipmentSlot.HAND, Material.BELL, EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE,
                        EquipmentSlot.LEGS, Material.LEATHER_LEGGINGS), 0, null);
        npc("captain", "<green>Captain Hale", "<gray>/warp to the wilds", sx + 1.5, sz - 2.5, 315, null,
                Map.of(EquipmentSlot.HAND, Material.COMPASS, EquipmentSlot.CHEST, Material.CHAINMAIL_CHESTPLATE,
                        EquipmentSlot.LEGS, Material.CHAINMAIL_LEGGINGS), 0, null);
    }

    // ------------------------------------------------------------------ NPCs and holograms

    private void npc(String id, String name, String title, double dx, double dz, float yaw, String leather,
                     Map<EquipmentSlot, Material> gear, double wander, String action) {
        NpcDef d = new NpcDef();
        d.id = id;
        d.name = name;
        d.title = title;
        d.world = w.getName();
        d.x = cx + dx;
        d.y = cy + 1;
        d.z = cz + dz;
        d.yaw = yaw;
        d.wander = wander;
        d.action = action;
        d.group = "plaza";
        d.equipment.putAll(gear);
        if (leather != null) d.leatherColor = Color.fromRGB(Integer.parseInt(leather.substring(1), 16));
        plugin.npcs().put(d);
    }

    private void hologram(double dx, double dy, double dz, String text) {
        w.spawn(new Location(w, cx + dx, cy + dy, cz + dz), TextDisplay.class, t -> {
            t.text(SmpPlugin.mm(text));
            t.setBillboard(Display.Billboard.CENTER);
            t.setAlignment(TextDisplay.TextAlignment.CENTER);
            t.setShadowed(true);
            t.setBackgroundColor(Color.fromARGB(90, 0, 0, 0));
            t.setPersistent(true);
            t.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
    }

    // ------------------------------------------------------------------ undo

    private void snapshot() throws IOException {
        Map<String, Integer> palette = new HashMap<>();
        List<String> order = new ArrayList<>();
        int size = (2 * R + 3);
        int height = UP + DOWN + 1;
        int[] data = new int[size * size * height];
        int i = 0;
        for (int dx = -R - 1; dx <= R + 1; dx++) {
            for (int dz = -R - 1; dz <= R + 1; dz++) {
                for (int dy = -DOWN; dy <= UP; dy++) {
                    String s = at(dx, dy, dz).getBlockData().getAsString();
                    Integer idx = palette.get(s);
                    if (idx == null) {
                        idx = order.size();
                        palette.put(s, idx);
                        order.add(s);
                    }
                    data[i++] = idx;
                }
            }
        }
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(undoFile())))) {
            out.writeUTF(w.getName());
            out.writeInt(cx);
            out.writeInt(cy);
            out.writeInt(cz);
            out.writeInt(order.size());
            for (String s : order) out.writeUTF(s);
            for (int v : data) out.writeInt(v);
        }
    }

    public String undo() throws IOException {
        File f = undoFile();
        if (!f.exists()) throw new IOException("Nothing to undo.");
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(f)))) {
            w = Bukkit.getWorld(in.readUTF());
            if (w == null) throw new IOException("World not loaded.");
            cx = in.readInt();
            cy = in.readInt();
            cz = in.readInt();
            int n = in.readInt();
            BlockData[] pal = new BlockData[n];
            for (int i = 0; i < n; i++) pal[i] = Bukkit.createBlockData(in.readUTF());
            for (int dx = -R - 1; dx <= R + 1; dx++) {
                for (int dz = -R - 1; dz <= R + 1; dz++) {
                    for (int dy = -DOWN; dy <= UP; dy++) at(dx, dy, dz).setBlockData(pal[in.readInt()], false);
                }
            }
        }
        for (Entity e : w.getNearbyEntities(loc(0, 5, 0), R + 4, UP + DOWN, R + 4)) {
            if (e.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) e.remove();
        }
        plugin.npcs().removeGroup("plaza");
        plugin.crates().clearBlocks();
        f.delete();
        return "Plaza removed and the area restored at " + w.getName() + " " + cx + " " + cy + " " + cz + ".";
    }

    // ------------------------------------------------------------------ helpers

    private Block at(int dx, int dy, int dz) {
        return w.getBlockAt(cx + dx, cy + dy, cz + dz);
    }

    private Location loc(int dx, int dy, int dz) {
        return new Location(w, cx + dx, cy + dy, cz + dz);
    }

    private void set(int dx, int dy, int dz, Material m) {
        at(dx, dy, dz).setType(m, false);
    }

    private static int hash(int x, int z, int salt) {
        int h = x * 73856093 ^ z * 19349663 ^ salt * 83492791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return Math.abs(h);
    }
}
