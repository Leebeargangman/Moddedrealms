package net.moddedrealms.testplayers;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;

/**
 * A test player's inventory and how it gets things the way a player does: blocks come from what it broke or
 * was traded, and what it places is crafted from what it carries (planks from logs, slabs from planks, stone
 * bricks from cobblestone, torches from coal and sticks...). Nothing appears from nowhere. When something can't
 * be made it uses what it has (wood when there's no stone, an open window when there's no glass), the way an
 * early-game player builds.
 */
final class Items {

    static final String[] WOODS = {"OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "MANGROVE", "CHERRY", "PALE_OAK"};

    /** What to place instead of this cell: leave it empty (no glass for a window, no bell...). */
    static final Material SKIP = Material.STRUCTURE_VOID;

    private Items() { }

    static int count(TestPlayer tp, Material m) {
        return tp.items.getOrDefault(m, 0);
    }

    static void add(TestPlayer tp, Material m, int n) {
        if (n > 0 && m != null && !m.isAir()) tp.items.merge(m, n, Integer::sum);
    }

    static boolean take(TestPlayer tp, Material m, int n) {
        int have = count(tp, m);
        if (have < n) return false;
        if (have == n) tp.items.remove(m);
        else tp.items.put(m, have - n);
        return true;
    }

    static int total(TestPlayer tp) {
        return tp.items.values().stream().mapToInt(Integer::intValue).sum();
    }

    static int logs(TestPlayer tp) {
        int n = 0;
        for (var e : tp.items.entrySet()) if (isLog(e.getKey())) n += e.getValue();
        return n;
    }

    static int stone(TestPlayer tp) {
        return count(tp, Material.COBBLESTONE) + count(tp, Material.COBBLED_DEEPSLATE);
    }

    static boolean isLog(Material m) {
        String n = m.name();
        return (n.endsWith("_LOG") || n.endsWith("_STEM")) && !n.startsWith("STRIPPED_");
    }

    /** "OAK" for OAK_PLANKS, OAK_LOG, OAK_SLAB, OAK_FENCE...; null if it isn't wood. */
    static String wood(Material m) {
        String n = m.name();
        for (String w : WOODS) if (n.startsWith(w + "_") && (n.endsWith("_LOG") || n.endsWith("_PLANKS") || n.endsWith("_SLAB") || n.endsWith("_FENCE"))) return w;
        return null;
    }

    private static Material of(String name) {
        Material m = Material.matchMaterial(name);
        return m != null ? m : Material.OAK_PLANKS;
    }

    // ------------------------------------------------------------ crafting what's needed

    /**
     * The block to actually place for a wanted one, crafting it from the inventory if need be (and using it
     * up), SKIP to leave the cell empty, or null when it's missing something: then it has to gather or fetch
     * (see needs()).
     */
    static Material provide(TestPlayer tp, Material want) {
        if (take(tp, want, 1)) return want;
        String n = want.name();
        String w = wood(want);
        if (w != null) return provideWood(tp, w, n.substring(w.length() + 1));
        switch (want) {
            case COBBLESTONE, STONE_BRICKS, BRICKS -> {
                if (takeStone(tp, 1)) return want == Material.COBBLESTONE ? Material.COBBLESTONE : Material.STONE_BRICKS;
                return provideWood(tp, null, "PLANKS"); // no stone yet: build it in wood
            }
            case STONE_BRICK_SLAB, BRICK_SLAB -> {
                if (take(tp, Material.STONE_BRICK_SLAB, 1)) return Material.STONE_BRICK_SLAB;
                if (takeStone(tp, 1)) {
                    add(tp, Material.STONE_BRICK_SLAB, 1); // one block makes two slabs
                    return Material.STONE_BRICK_SLAB;
                }
                return provideWood(tp, null, "SLAB");
            }
            case COBBLESTONE_WALL, BRICK_WALL -> {
                if (takeStone(tp, 1)) return Material.COBBLESTONE_WALL;
                return provideWood(tp, null, "FENCE");
            }
            case GLASS_PANE -> {
                if (take(tp, Material.GLASS_PANE, 1)) return Material.GLASS_PANE;
                if (take(tp, Material.SAND, 1) || take(tp, Material.GLASS, 1)) {
                    add(tp, Material.GLASS_PANE, 1); // 6 glass make 16 panes: about two per block of sand
                    return Material.GLASS_PANE;
                }
                return SKIP; // an open window
            }
            case TORCH, LANTERN -> {
                if (take(tp, Material.TORCH, 1)) return Material.TORCH;
                if (makeTorches(tp)) return take(tp, Material.TORCH, 1) ? Material.TORCH : SKIP;
                return SKIP;
            }
            case CAMPFIRE -> {
                // 3 logs, 3 sticks, and coal or charcoal (a log burned in a furnace).
                if (logs(tp) >= 4 && takeAnyLog(tp, 3)) {
                    if (!take(tp, Material.COAL, 1) && !take(tp, Material.CHARCOAL, 1)) takeAnyLog(tp, 1);
                    return Material.CAMPFIRE;
                }
                return null;
            }
            case BARREL, CHEST -> {
                Material p = provideWood(tp, null, "PLANKS");
                if (p == null) return null;
                // A chest is 8 planks, a barrel 7: one is already taken.
                for (int i = 0; i < 6; i++) if (provideWood(tp, null, "PLANKS") == null) break;
                return want;
            }
            case HAY_BLOCK -> {
                return take(tp, Material.WHEAT, 9) ? Material.HAY_BLOCK : SKIP;
            }
            case BELL -> {
                return take(tp, Material.GOLD_INGOT, 7) ? Material.BELL : SKIP;
            }
            case WHEAT -> {
                return take(tp, Material.WHEAT_SEEDS, 1) ? Material.WHEAT : SKIP;
            }
            case FARMLAND -> {
                return tool(tp, "HOE") >= 0 || craftTool(tp, "HOE") ? Material.FARMLAND : SKIP;
            }
            case DIRT_PATH -> {
                return tool(tp, "SHOVEL") >= 0 || craftTool(tp, "SHOVEL") ? Material.DIRT_PATH : SKIP;
            }
            case WATER -> {
                // A bucket of water. The bucket (3 iron) is kept; the water comes from the nearest water.
                if (count(tp, Material.BUCKET) > 0 || count(tp, Material.WATER_BUCKET) > 0) return Material.WATER;
                if (smeltIron(tp, 3)) {
                    take(tp, Material.IRON_INGOT, 3);
                    add(tp, Material.BUCKET, 1);
                    return Material.WATER;
                }
                return SKIP; // dry farm / dry well until someone has iron
            }
            default -> {
                return SKIP;
            }
        }
    }

    private static Material provideWood(TestPlayer tp, String type, String shape) {
        String w = type != null && hasWood(tp, type) ? type : anyWood(tp);
        if (w == null) return null;
        switch (shape) {
            case "LOG" -> {
                Material log = of(w + "_LOG");
                return take(tp, log, 1) ? log : null;
            }
            case "SLAB" -> {
                Material slab = of(w + "_SLAB");
                if (take(tp, slab, 1)) return slab;
                if (!planks(tp, w, 1)) return null;
                add(tp, slab, 1); // a plank makes two slabs
                return slab;
            }
            case "FENCE" -> {
                Material fence = of(w + "_FENCE");
                if (take(tp, fence, 1)) return fence;
                return planks(tp, w, 2) ? fence : null; // 4 planks and 2 sticks make 3 fences
            }
            default -> {
                Material pl = of(w + "_PLANKS");
                return planks(tp, w, 1) ? pl : null;
            }
        }
    }

    /** Use up n planks of a wood (sawing a log if need be). */
    private static boolean planks(TestPlayer tp, String w, int n) {
        Material pl = of(w + "_PLANKS");
        while (count(tp, pl) < n) {
            if (!take(tp, of(w + "_LOG"), 1)) return false;
            add(tp, pl, 4);
        }
        return take(tp, pl, n);
    }

    private static boolean hasWood(TestPlayer tp, String w) {
        return count(tp, of(w + "_PLANKS")) > 0 || count(tp, of(w + "_LOG")) > 0;
    }

    /** The wood it has most of. */
    private static String anyWood(TestPlayer tp) {
        String best = null;
        int most = 0;
        for (String w : WOODS) {
            int n = count(tp, of(w + "_LOG")) * 4 + count(tp, of(w + "_PLANKS"));
            if (n > most) {
                most = n;
                best = w;
            }
        }
        return best;
    }

    private static boolean takeAnyLog(TestPlayer tp, int n) {
        if (logs(tp) < n) return false;
        int left = n;
        for (Material m : new ArrayList<>(tp.items.keySet())) {
            if (!isLog(m)) continue;
            int k = Math.min(left, count(tp, m));
            take(tp, m, k);
            left -= k;
            if (left == 0) break;
        }
        return true;
    }

    private static boolean takeStone(TestPlayer tp, int n) {
        return take(tp, Material.COBBLESTONE, n) || take(tp, Material.COBBLED_DEEPSLATE, n);
    }

    private static boolean sticks(TestPlayer tp, int n) {
        while (count(tp, Material.STICK) < n) {
            String w = anyWood(tp);
            if (w == null || !planks(tp, w, 2)) return false;
            add(tp, Material.STICK, 4);
        }
        return take(tp, Material.STICK, n);
    }

    /** Coal (or charcoal from a log) and a stick: 4 torches. */
    static boolean makeTorches(TestPlayer tp) {
        boolean fuel = take(tp, Material.COAL, 1) || take(tp, Material.CHARCOAL, 1) || takeAnyLog(tp, 1);
        if (!fuel || !sticks(tp, 1)) return false;
        add(tp, Material.TORCH, 4);
        return true;
    }

    /** Smelt raw iron (a furnace from 8 cobblestone the first time, coal or wood as fuel) until it has n ingots. */
    static boolean smeltIron(TestPlayer tp, int n) {
        while (count(tp, Material.IRON_INGOT) < n) {
            if (count(tp, Material.RAW_IRON) == 0) return false;
            if (count(tp, Material.FURNACE) == 0) {
                if (!takeStone(tp, 8)) return false;
                add(tp, Material.FURNACE, 1);
            }
            if (!(take(tp, Material.COAL, 1) || take(tp, Material.CHARCOAL, 1) || takeAnyLog(tp, 1))) return false;
            int k = Math.min(8, count(tp, Material.RAW_IRON)); // one coal smelts 8
            take(tp, Material.RAW_IRON, k);
            add(tp, Material.IRON_INGOT, k);
        }
        return true;
    }

    // ------------------------------------------------------------ tools

    private static final String[] TIERS = {"WOODEN", "STONE", "IRON", "DIAMOND"};

    /** Best tier of this tool it has (0 wood .. 3 diamond), -1 for none. kind: PICKAXE, AXE, SHOVEL, HOE, SWORD. */
    static int tool(TestPlayer tp, String kind) {
        for (int t = TIERS.length - 1; t >= 0; t--) if (count(tp, of(TIERS[t] + "_" + kind)) > 0) return t;
        return -1;
    }

    static Material toolItem(TestPlayer tp, String kind) {
        int t = tool(tp, kind);
        return t < 0 ? null : of(TIERS[t] + "_" + kind);
    }

    /**
     * Make the best tool of a kind it can (iron if it has iron, stone if it has cobblestone, else wood), if
     * that beats what it has. True if it now has one.
     */
    static boolean craftTool(TestPlayer tp, String kind) {
        int heads = switch (kind) { case "PICKAXE", "AXE" -> 3; case "HOE", "SWORD" -> 2; default -> 1; };
        int have = tool(tp, kind);
        if (have < 2 && (count(tp, Material.IRON_INGOT) >= heads || count(tp, Material.RAW_IRON) >= heads) && smeltIron(tp, heads) && sticks(tp, 2)) {
            take(tp, Material.IRON_INGOT, heads);
            add(tp, of("IRON_" + kind), 1);
            if (have >= 0) take(tp, of(TIERS[have] + "_" + kind), 1);
            return true;
        }
        if (have < 1 && stone(tp) >= heads + 8 && sticks(tp, 2)) { // keep some cobblestone for building
            takeStone(tp, heads);
            add(tp, of("STONE_" + kind), 1);
            if (have >= 0) take(tp, of(TIERS[have] + "_" + kind), 1);
            return true;
        }
        if (have < 0) {
            String w = anyWood(tp);
            if (w != null && planks(tp, w, heads) && sticks(tp, 2)) {
                add(tp, of("WOODEN_" + kind), 1);
                return true;
            }
        }
        return have >= 0;
    }

    /** Ticks to break a block by hand or with its best tool (roughly the game's own times). */
    static int breakTicks(TestPlayer tp, Material block) {
        float hardness = block.getHardness();
        if (hardness <= 0) return 2;
        String kind = block.name().contains("LOG") || block.name().contains("PLANKS") || block.name().contains("STEM") ? "AXE"
                : block.name().contains("DIRT") || block.name().contains("GRASS_BLOCK") || block.name().contains("SAND") || block.name().contains("GRAVEL") ? "SHOVEL" : "PICKAXE";
        int t = tool(tp, kind);
        float speed = t < 0 ? 1 : new float[] {2, 4, 6, 8}[t];
        boolean needsPick = kind.equals("PICKAXE") && t < 0;
        return Math.max(2, Math.round(hardness * (needsPick ? 5f : 1.5f) * 20 / speed));
    }

    /** Can it get anything out of this block with what it has? (Stone needs a pickaxe, iron a stone one...) */
    static boolean canHarvest(TestPlayer tp, Material block) {
        String n = block.name();
        int pick = tool(tp, "PICKAXE");
        if (n.contains("DIAMOND_ORE") || n.contains("GOLD_ORE") && !n.startsWith("NETHER") || n.contains("REDSTONE_ORE") || n.contains("EMERALD_ORE")) return pick >= 2;
        if (n.contains("IRON_ORE") || n.contains("LAPIS_ORE") || n.contains("COPPER_ORE")) return pick >= 1;
        if (n.endsWith("STONE") || n.equals("DEEPSLATE") || n.endsWith("_ORE") || n.equals("NETHERRACK") || n.equals("ANDESITE") || n.equals("DIORITE") || n.equals("GRANITE") || n.equals("TUFF")) return pick >= 0;
        return true;
    }

    // ------------------------------------------------------------ worth, for trading

    private static final Map<Material, Integer> WORTH = new EnumMap<>(Material.class);

    static {
        for (String w : WOODS) {
            WORTH.put(of(w + "_LOG"), 4);
            WORTH.put(of(w + "_PLANKS"), 1);
        }
        WORTH.put(Material.COBBLESTONE, 2);
        WORTH.put(Material.COBBLED_DEEPSLATE, 2);
        WORTH.put(Material.COAL, 8);
        WORTH.put(Material.RAW_IRON, 16);
        WORTH.put(Material.IRON_INGOT, 20);
        WORTH.put(Material.RAW_COPPER, 6);
        WORTH.put(Material.RAW_GOLD, 24);
        WORTH.put(Material.GOLD_INGOT, 28);
        WORTH.put(Material.GOLD_NUGGET, 3);
        WORTH.put(Material.REDSTONE, 4);
        WORTH.put(Material.LAPIS_LAZULI, 6);
        WORTH.put(Material.DIAMOND, 160);
        WORTH.put(Material.EMERALD, 40);
        WORTH.put(Material.QUARTZ, 8);
        WORTH.put(Material.GLOWSTONE_DUST, 6);
        WORTH.put(Material.WHEAT, 3);
        WORTH.put(Material.WHEAT_SEEDS, 1);
        WORTH.put(Material.BREAD, 10);
        WORTH.put(Material.SAND, 2);
        WORTH.put(Material.GLASS, 4);
        WORTH.put(Material.TORCH, 3);
        WORTH.put(Material.APPLE, 6);
        WORTH.put(Material.COOKED_BEEF, 12);
        WORTH.put(Material.FLINT, 4);
    }

    static int worth(Material m) {
        return WORTH.getOrDefault(m, 0);
    }

    /** Things it would like to get, by how much it wants them (for trades and its AI). */
    static List<Material> wants(TestPlayer tp) {
        List<Material> out = new ArrayList<>();
        if (tool(tp, "PICKAXE") < 2 && count(tp, Material.IRON_INGOT) < 3) out.add(Material.IRON_INGOT);
        if (count(tp, Material.COAL) < 8) out.add(Material.COAL);
        if (count(tp, Material.BREAD) < 4) out.add(Material.BREAD);
        if (count(tp, Material.GLASS) + count(tp, Material.SAND) < 8) out.add(Material.SAND);
        if (logs(tp) < 16) out.add(Material.OAK_LOG);
        return out;
    }

    /** "12 oak log, 30 cobblestone, a stone pickaxe..." for the AI and status lines. */
    static String describe(TestPlayer tp, int max) {
        List<String> parts = new ArrayList<>();
        tp.items.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(max).forEach(e -> {
            String name = e.getKey().name().toLowerCase(Locale.ROOT).replace('_', ' ');
            parts.add(e.getValue() == 1 ? (name.endsWith("e") || name.endsWith("l") ? "a " : "") + name : e.getValue() + " " + name);
        });
        return parts.isEmpty() ? "nothing" : String.join(", ", parts) + (tp.items.size() > max ? ", ..." : "");
    }

    /** Raw materials a list of wanted blocks needs, roughly: logs and cobblestone (for fetching from storage). */
    static int[] rawNeeds(List<Material> wants) {
        int logs = 0;
        int stone = 0;
        for (Material m : wants) {
            String n = m.name();
            if (wood(m) != null) logs += n.endsWith("_LOG") ? 4 : n.endsWith("_SLAB") ? 1 : n.endsWith("_FENCE") ? 2 : 1; // in quarters of a log
            else if (n.contains("COBBLE") || n.contains("STONE_BRICK") || n.contains("BRICK")) stone++;
            else if (m == Material.CHEST || m == Material.BARREL) logs += 8;
            else if (m == Material.CAMPFIRE) logs += 16;
        }
        return new int[] {(logs + 3) / 4, stone};
    }
}
