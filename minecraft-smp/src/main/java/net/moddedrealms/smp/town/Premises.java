package net.moddedrealms.smp.town;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Sheep;
import org.bukkit.util.BoundingBox;
import net.moddedrealms.smp.town.Econ.Kind;

/**
 * What a player's business has actually built on its plot. Each kind needs its fittings, under a roof (crops and
 * sheep excepted): an inn needs beds, a cookhouse stoves and seats, a sawmill stonecutters... The fittings set how
 * much it can make in a cycle; an empty plot makes nothing. Each level lets more fittings count, so a bigger building
 * only pays off after an upgrade.
 */
final class Premises {
    /** A thing to have: how many at least, how much each adds to the cycle's output, how many count per level. */
    record Need(String label, Predicate<Block> match, int min, double perUnit, int perLevel, boolean indoors) {}

    record Line(String label, int have, int min, int counted, boolean ok) {}

    record Report(List<Line> lines, double capacity, boolean ok) {
        String missing() {
            List<String> m = new ArrayList<>();
            for (Line l : lines) if (!l.ok()) m.add(l.label() + " " + l.have() + "/" + l.min());
            return String.join(", ", m);
        }
    }

    private static final Map<Kind, List<Need>> NEEDS = new EnumMap<>(Kind.class);

    private static boolean name(Block b, String... ends) {
        String n = b.getType().name();
        for (String e : ends) if (n.endsWith(e) || n.equals(e)) return true;
        return false;
    }

    private static final Predicate<Block> DOOR = b -> b.getBlockData() instanceof Door d && d.getHalf() == Bisected.Half.BOTTOM;
    private static final Predicate<Block> SEAT = b -> b.getBlockData() instanceof Stairs s && s.getHalf() == Bisected.Half.BOTTOM
            || b.getBlockData() instanceof Slab s2 && s2.getType() == Slab.Type.BOTTOM;
    private static final Predicate<Block> BED = b -> b.getBlockData() instanceof Bed bd && bd.getPart() == Bed.Part.HEAD;
    private static final Predicate<Block> STOVE = b -> b.getType() == Material.SMOKER || b.getType() == Material.FURNACE || b.getType() == Material.BLAST_FURNACE
            || b.getType() == Material.CAMPFIRE;
    private static final Predicate<Block> STORE = b -> b.getType() == Material.CHEST || b.getType() == Material.BARREL || b.getType() == Material.TRAPPED_CHEST;
    private static final Predicate<Block> CROP = b -> b.getType() == Material.WHEAT || b.getType() == Material.CARROTS || b.getType() == Material.POTATOES
            || b.getType() == Material.BEETROOTS;
    private static final Predicate<Block> LIGHT = b -> name(b, "LANTERN", "TORCH", "CANDLE", "GLOWSTONE", "SHROOMLIGHT", "CAMPFIRE");
    static final Predicate<Block> SHEEP = b -> false; // counted from entities, see check()

    static {
        NEEDS.put(Kind.FARM, List.of(
                new Need("crops", CROP, 16, 0.25, 48, false),
                new Need("composter", b -> b.getType() == Material.COMPOSTER, 1, 0, 0, false)));
        NEEDS.put(Kind.WOOL_MILL, List.of(
                new Need("sheep", SHEEP, 2, 1.0, 6, false),
                new Need("looms", b -> b.getType() == Material.LOOM, 1, 3, 2, true),
                new Need("door", DOOR, 1, 0, 0, true)));
        NEEDS.put(Kind.LUMBER_MILL, List.of(
                new Need("stonecutters", b -> b.getType() == Material.STONECUTTER, 1, 4, 2, true),
                new Need("log piles", b -> name(b, "_LOG"), 4, 0, 0, false),
                new Need("door", DOOR, 1, 0, 0, true)));
        NEEDS.put(Kind.BAKERY, List.of(
                new Need("ovens (smoker/furnace)", STOVE, 1, 4, 2, true),
                new Need("barrels or chests", STORE, 1, 0, 0, true),
                new Need("door", DOOR, 1, 0, 0, true)));
        NEEDS.put(Kind.TAVERN, List.of(
                new Need("barrels", b -> b.getType() == Material.BARREL, 2, 3, 3, true),
                new Need("seats (stairs/slabs)", SEAT, 4, 1, 8, true),
                new Need("lights", LIGHT, 2, 0, 0, true),
                new Need("door", DOOR, 1, 0, 0, true)));
        NEEDS.put(Kind.TAILOR, List.of(
                new Need("looms", b -> b.getType() == Material.LOOM, 1, 3, 2, true),
                new Need("door", DOOR, 1, 0, 0, true)));
        NEEDS.put(Kind.FURNITURE, List.of(
                new Need("crafting tables", b -> b.getType() == Material.CRAFTING_TABLE, 1, 2, 2, true),
                new Need("stonecutters or saws", b -> b.getType() == Material.STONECUTTER || b.getType() == Material.SMITHING_TABLE, 1, 0, 0, true),
                new Need("door", DOOR, 1, 0, 0, true)));
        NEEDS.put(Kind.RESTAURANT, List.of(
                new Need("stoves (smoker/furnace/campfire)", STOVE, 1, 3, 2, true),
                new Need("seats (stairs/slabs)", SEAT, 4, 0.75, 8, true),
                new Need("lights", LIGHT, 2, 0, 0, true),
                new Need("door", DOOR, 1, 0, 0, true)));
        NEEDS.put(Kind.STORE, List.of(
                new Need("chests or barrels", STORE, 4, 1, 8, true),
                new Need("a counter (lectern or bell)", b -> b.getType() == Material.LECTERN || b.getType() == Material.BELL, 1, 0, 0, true),
                new Need("door", DOOR, 1, 0, 0, true)));
        NEEDS.put(Kind.HOTEL, List.of(
                new Need("beds (one per room)", BED, 4, 1, 6, true),
                new Need("a bell at reception", b -> b.getType() == Material.BELL, 1, 0, 0, true),
                new Need("lights", LIGHT, 4, 0, 0, true),
                new Need("door", DOOR, 1, 0, 0, true)));
        NEEDS.put(Kind.SPEAKEASY, List.of(
                new Need("brewing stands", b -> b.getType() == Material.BREWING_STAND, 1, 2, 2, true),
                new Need("cauldrons", b -> b.getType() == Material.CAULDRON || b.getType() == Material.WATER_CAULDRON, 1, 0, 0, true),
                new Need("door", DOOR, 1, 0, 0, true)));
    }

    static List<Need> needs(Kind k) {
        return NEEDS.getOrDefault(k, List.of());
    }

    /** Under a roof: something solid within 12 blocks above. */
    private static boolean indoors(Block b) {
        for (int dy = 1; dy <= 12; dy++) {
            Material m = b.getRelative(0, dy, 0).getType();
            if (m.isOccluding() || m.name().endsWith("_SLAB") || m.name().endsWith("_STAIRS")) return true;
        }
        return false;
    }

    static Report check(Plots.Plot p, World w, Kind k, int level) {
        List<Need> needs = needs(k);
        int[] count = new int[needs.size()];
        for (int x = p.x0; x <= p.x1; x++)
            for (int z = p.z0; z <= p.z1; z++)
                for (int y = p.floor - 2; y <= p.floor + 24; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType().isAir()) continue;
                    for (int i = 0; i < needs.size(); i++) {
                        Need n = needs.get(i);
                        if (n.match() == SHEEP || !n.match().test(b)) continue;
                        if (n.indoors() && !indoors(b)) continue;
                        count[i]++;
                    }
                }
        for (int i = 0; i < needs.size(); i++) {
            if (needs.get(i).match() != SHEEP) continue;
            BoundingBox box = new BoundingBox(p.x0, p.floor - 3, p.z0, p.x1 + 1, p.floor + 10, p.z1 + 1);
            count[i] = w.getNearbyEntities(box, e -> e instanceof Sheep s && s.isAdult()).size();
        }
        List<Line> lines = new ArrayList<>();
        double cap = Double.MAX_VALUE;
        boolean ok = true, anyCap = false;
        for (int i = 0; i < needs.size(); i++) {
            Need n = needs.get(i);
            int counted = n.perLevel() > 0 ? Math.min(count[i], n.perLevel() * Math.max(1, level)) : count[i];
            boolean good = count[i] >= n.min();
            ok &= good;
            lines.add(new Line(n.label(), count[i], n.min(), counted, good));
            if (n.perUnit() > 0) {
                cap = Math.min(cap, counted * n.perUnit());
                anyCap = true;
            }
        }
        if (!anyCap) cap = 0;
        return new Report(lines, ok ? Math.floor(cap) : 0, ok);
    }
}
