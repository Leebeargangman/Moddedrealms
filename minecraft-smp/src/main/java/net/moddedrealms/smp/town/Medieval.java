package net.moddedrealms.smp.town;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * Gives generated buildings a medieval look: modern-looking blocks (quartz, polished deepslate and blackstone, smooth
 * stone, sea lanterns, redstone lamps, the station's blue light) become stone brick, cobble, timber and fire, keeping
 * every block's shape and facing; and flat roofs get battlements. Works on finished buildings, so nothing has to be
 * rebuilt.
 */
final class Medieval {
    private static final Map<Material, Material> SWAP = new HashMap<>();

    static {
        swap("SMOOTH_QUARTZ", "STONE_BRICKS");
        swap("QUARTZ_BLOCK", "STONE_BRICKS");
        swap("QUARTZ_BRICKS", "STONE_BRICKS");
        swap("CHISELED_QUARTZ_BLOCK", "CHISELED_STONE_BRICKS");
        swap("QUARTZ_PILLAR", "STRIPPED_SPRUCE_LOG");
        swap("SMOOTH_QUARTZ_SLAB", "STONE_BRICK_SLAB");
        swap("QUARTZ_SLAB", "STONE_BRICK_SLAB");
        swap("SMOOTH_QUARTZ_STAIRS", "STONE_BRICK_STAIRS");
        swap("QUARTZ_STAIRS", "STONE_BRICK_STAIRS");
        swap("POLISHED_DEEPSLATE", "COBBLESTONE");
        swap("POLISHED_DEEPSLATE_WALL", "COBBLESTONE_WALL");
        swap("POLISHED_DEEPSLATE_STAIRS", "COBBLESTONE_STAIRS");
        swap("POLISHED_DEEPSLATE_SLAB", "COBBLESTONE_SLAB");
        swap("POLISHED_ANDESITE", "COBBLESTONE");
        swap("POLISHED_ANDESITE_STAIRS", "COBBLESTONE_STAIRS");
        swap("POLISHED_ANDESITE_SLAB", "COBBLESTONE_SLAB");
        swap("POLISHED_BLACKSTONE_BRICKS", "STONE_BRICKS");
        swap("CRACKED_POLISHED_BLACKSTONE_BRICKS", "CRACKED_STONE_BRICKS");
        swap("POLISHED_BLACKSTONE", "COBBLESTONE");
        swap("GILDED_BLACKSTONE", "CHISELED_STONE_BRICKS");
        swap("POLISHED_BLACKSTONE_WALL", "COBBLESTONE_WALL");
        swap("POLISHED_BLACKSTONE_BRICK_WALL", "STONE_BRICK_WALL");
        swap("POLISHED_BLACKSTONE_STAIRS", "COBBLESTONE_STAIRS");
        swap("POLISHED_BLACKSTONE_SLAB", "COBBLESTONE_SLAB");
        swap("DEEPSLATE_BRICKS", "STONE_BRICKS");
        swap("DEEPSLATE_BRICK_SLAB", "STONE_BRICK_SLAB");
        swap("SMOOTH_STONE", "STONE_BRICKS");
        swap("SMOOTH_STONE_SLAB", "STONE_BRICK_SLAB");
        swap("SEA_LANTERN", "GLOWSTONE");
        swap("REDSTONE_LAMP", "GLOWSTONE");
        swap("BLUE_STAINED_GLASS", "CAMPFIRE");
        swap("LIGHTNING_ROD", "IRON_BARS");
    }

    private static void swap(String from, String to) {
        Material a = Material.matchMaterial(from), b = Material.matchMaterial(to);
        if (a != null && b != null) SWAP.put(a, b);
    }

    /** Modern words in player-visible text and their medieval replacements, longest phrases first. */
    private static final String[] WORDS = {
            "Bureau agents", "Inquisitors",
            "bureau agents", "inquisitors",
            "a Bureau", "an Inquisition",
            "the police are", "the Watch is",
            "The police are", "The Watch is",
            "Police are", "The Watch is",
            "Police raided", "The Watch raided",
            "The police have", "The Watch has",
            "the police have", "the Watch has",
            "police take", "Watch takes",
            "police come", "Watch comes",
            "police ignore", "Watch ignores",
            "with police", "with a Watch",
            "an officer", "a guard",
            "An officer", "A guard",
            "an Officer", "a Guard",
            "a hotel", "an inn",
            "A hotel", "An inn",
            "police stations", "guardhouses",
            "police station", "guardhouse",
            "Police Station", "Guardhouse",
            "Police station", "Guardhouse",
            "the police", "the Watch",
            "The police", "The Watch",
            "the Police", "the Watch",
            "police", "Watch",
            "Police", "Watch",
            "officers", "guards",
            "Officers", "Guards",
            "officer", "guard",
            "Officer", "Guard",
            "the Bureau", "the Inquisition",
            "The Bureau", "The Inquisition",
            "Bureau", "Inquisition",
            "agents", "inquisitors",
            "Agents", "Inquisitors",
            "agent", "inquisitor",
            "Agent", "Inquisitor",
            "Sheriff", "Reeve",
            "sheriff", "reeve",
            "casinos", "gambling dens",
            "casino", "gambling den",
            "Casino", "Gambling Den",
            "CASINO", "GAMBLING DEN",
            "slot machines", "fortune boxes",
            "slot machine", "fortune box",
            "Slot machine", "Fortune box",
            "Savings & Loans", "Counting House",
            "banks", "counting houses",
            "bank", "counting house",
            "Bank", "Counting House",
            "BANK", "COUNTING HOUSE",
            "Bankers", "Moneylenders",
            "Banker", "Moneylender",
            "banker", "moneylender",
            "hotels", "inns",
            "hotel", "inn",
            "Hotel", "Inn",
            "HOTEL", "INN",
            "restaurants", "cookhouses",
            "restaurant", "cookhouse",
            "Restaurant", "Cookhouse",
            "speakeasy", "hidden still",
            "Speakeasy", "Hidden still",
            "cops", "guards",
            "cop", "guard"
    };
    private static final java.util.List<java.util.Map.Entry<java.util.regex.Pattern, String>> RX = new java.util.ArrayList<>();
    private static final java.util.regex.Pattern PROTECT = java.util.regex.Pattern.compile("(?:command|console):[^\"}]*|<[a-z_]+(?:\\|[a-z_]+)+>");

    static {
        for (int i = 0; i < WORDS.length; i += 2) {
            RX.add(java.util.Map.entry(java.util.regex.Pattern.compile("(?<![/\\w.\\-])(?<!mrtown )" + java.util.regex.Pattern.quote(WORDS[i]) + "(?![\\w\\-])"),
                    java.util.regex.Matcher.quoteReplacement(WORDS[i + 1])));
        }
    }

    /** The same text with the modern words swapped (commands and "Land Agent" are left alone). */
    static String text(String s) {
        if (s == null) return null;
        java.util.List<String> held = new java.util.ArrayList<>();
        java.util.regex.Matcher m = PROTECT.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            held.add(m.group());
            m.appendReplacement(sb, "\u0002" + (held.size() - 1) + "\u0002");
        }
        m.appendTail(sb);
        String out = sb.toString().replace("Land Agent", "\u0003");
        for (var e : RX) out = e.getKey().matcher(out).replaceAll(e.getValue());
        out = out.replace("\u0003", "Land Agent");
        for (int i = 0; i < held.size(); i++) out = out.replace("\u0002" + i + "\u0002", held.get(i));
        return out;
    }

    int swapped, merlons;

    private Medieval() {
    }

    /** Swap the materials in a box (inclusive), except where skip says so (players' own plots). */
    static Medieval area(World w, int x0, int y0, int z0, int x1, int y1, int z1, Predicate<Block> skip, boolean battlements) {
        Medieval m = new Medieval();
        int ay = Math.max(w.getMinHeight(), Math.min(y0, y1)), by = Math.min(w.getMaxHeight() - 1, Math.max(y0, y1));
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
                for (int y = ay; y <= by; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    Material to = SWAP.get(b.getType());
                    if (to == null || skip.test(b)) continue;
                    b.setBlockData(convert(b.getBlockData(), to), false);
                    m.swapped++;
                }
        if (battlements) m.battlements(w, Math.min(x0, x1), Math.min(z0, z1), Math.max(x0, x1), Math.max(z0, z1), ay, by, skip);
        return m;
    }

    /** The same shape in another material: stairs stay stairs facing the same way, slabs keep their half. */
    private static BlockData convert(BlockData d, Material to) {
        String s = d.getAsString();
        int i = s.indexOf('[');
        if (i >= 0) {
            try {
                return Bukkit.createBlockData(to, s.substring(i));
            } catch (IllegalArgumentException ignored) {
                // properties that don't exist on the new block (a pillar's axis on glowstone): drop them
            }
        }
        return to.createBlockData();
    }

    /**
     * Merlons along the edges of flat roofs: a column whose top is a full block, with a drop of at least three blocks
     * next to it, gets a stone brick merlon on every other block. Gable roofs (stairs and slabs on top) are left alone.
     */
    private void battlements(World w, int x0, int z0, int x1, int z1, int ay, int by, Predicate<Block> skip) {
        int nx = x1 - x0 + 1, nz = z1 - z0 + 1;
        int[][] top = new int[nx][nz];
        for (int x = 0; x < nx; x++)
            for (int z = 0; z < nz; z++) {
                top[x][z] = ay - 1;
                for (int y = by; y >= ay; y--) {
                    Material m = w.getBlockAt(x0 + x, y, z0 + z).getType();
                    if (!m.isAir() && !m.name().contains("LEAVES") && m != Material.SHORT_GRASS && m != Material.TALL_GRASS) {
                        top[x][z] = y;
                        break;
                    }
                }
            }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int x = 1; x < nx - 1; x++)
            for (int z = 1; z < nz - 1; z++) {
                int t = top[x][z];
                if (t < ay || t + 1 > by) continue;
                Block b = w.getBlockAt(x0 + x, t, z0 + z);
                boolean slab = b.getBlockData() instanceof org.bukkit.block.data.type.Slab;
                if (!(b.getType().isOccluding() || slab) || b.getType().name().endsWith("_LOG") || b.getType().name().endsWith("_WOOD") || skip.test(b)) continue;
                boolean edge = false;
                int flat = 0;
                for (int[] d : dirs) {
                    int nt = top[x + d[0]][z + d[1]];
                    if (nt <= t - 3) edge = true;
                    if (nt == t) flat++;
                }
                if (!edge || flat < 2 || Math.floorMod(x0 + x + z0 + z, 2) != 0) continue;
                Block above = w.getBlockAt(x0 + x, t + 1, z0 + z);
                if (!above.getType().isAir()) continue;
                if (slab) b.setType(Material.STONE_BRICKS, false); // a slab rim: make the merlon a full block standing on it
                above.setType(Material.STONE_BRICKS, false);
                merlons++;
            }
    }
}
