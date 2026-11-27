package com.colonysmp.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Every production chain the colony knows, per workplace, and the code that turns a recipe into concrete goods
 * to reserve (choosing which meat, which fuel...).
 */
public final class Recipes {

    /** Fuel units per item. */
    public static final Map<String, Double> FUEL_UNITS = new LinkedHashMap<>();
    private static final Map<String, List<Recipe>> BY_STATION = new HashMap<>();
    private static final Map<String, Recipe> BY_ID = new LinkedHashMap<>();
    private static final Map<String, List<String>> GROUPS = new HashMap<>();
    private static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak"};

    static {
        FUEL_UNITS.put("coal", 8.0);
        FUEL_UNITS.put("charcoal", 8.0);
        FUEL_UNITS.put("coal_block", 80.0);
        FUEL_UNITS.put("blaze_rod", 12.0);
        FUEL_UNITS.put("dried_kelp_block", 20.0);
        for (String w : WOODS) FUEL_UNITS.put(w + "_planks", 1.5);
        for (String w : WOODS) FUEL_UNITS.put(w + "_log", 1.5);
        FUEL_UNITS.put("stick", 0.5);
    }

    private Recipes() {}

    // ───────────── groups ─────────────

    static {
        GROUPS.put("#cooked_meat", List.of("cooked_beef", "cooked_porkchop", "cooked_mutton", "cooked_chicken", "cooked_rabbit", "cooked_cod", "cooked_salmon"));
        GROUPS.put("#vegetable", List.of("carrot", "baked_potato", "beetroot"));
        GROUPS.put("#herb", List.of("sweet_berries", "glow_berries", "beetroot", "dried_kelp"));
        List<String> logs = new ArrayList<>(), planks = new ArrayList<>();
        for (String w : WOODS) {
            logs.add(w + "_log");
            planks.add(w + "_planks");
        }
        logs.add("crimson_stem");
        logs.add("warped_stem");
        planks.add("crimson_planks");
        planks.add("warped_planks");
        GROUPS.put("#log", logs);
        GROUPS.put("#planks", planks);
        List<String> wool = new ArrayList<>();
        for (String c : new String[]{"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"}) {
            wool.add(c + "_wool");
        }
        GROUPS.put("#wool", wool);
    }

    public static boolean isGroup(String key) {
        return key.startsWith("#");
    }

    public static List<String> members(String group) {
        return GROUPS.getOrDefault(group, List.of());
    }

    public static String groupName(String group) {
        return switch (group) {
            case "#cooked_meat" -> "cooked meat or fish";
            case "#vegetable" -> "vegetables";
            case "#herb" -> "herbs (berries, beetroot or kelp)";
            case "#log" -> "logs";
            case "#planks" -> "planks";
            case "#wool" -> "wool";
            default -> group.substring(1);
        };
    }

    // ───────────── the recipes ─────────────

    private static Builder r(String station, String id, String name) {
        return new Builder(station, id, name);
    }

    static final class Builder {
        final String station, id, name;
        final Map<String, Integer> in = new LinkedHashMap<>(), out = new LinkedHashMap<>();
        double hours = 1;
        Skill skill = Skill.CRAFTING;
        int level = 1;
        boolean demand;

        Builder(String station, String id, String name) {
            this.station = station;
            this.id = id;
            this.name = name;
        }

        Builder in(String k, int n) {
            in.put(k, n);
            return this;
        }

        Builder out(String k, int n) {
            out.put(k, n);
            return this;
        }

        Builder hours(double h) {
            hours = h;
            return this;
        }

        Builder skill(Skill s) {
            skill = s;
            return this;
        }

        Builder level(int l) {
            level = l;
            return this;
        }

        Builder demand() {
            demand = true;
            return this;
        }

        void add() {
            Recipe rec = new Recipe(id, name, station, in, out, hours, skill, level, demand);
            BY_STATION.computeIfAbsent(station, k -> new ArrayList<>()).add(rec);
            BY_ID.put(id, rec);
        }
    }

    static {
        // Mill: grain into flour, cane into sugar
        r("MILL", "flour", "Milling flour").in("wheat", 8).out(Goods.FLOUR, 8).hours(0.6).skill(Skill.COOKING).add();
        r("MILL", "sugar", "Grinding sugar").in("sugar_cane", 8).out("sugar", 8).hours(0.5).skill(Skill.COOKING).add();
        r("MILL", "bone_meal", "Grinding bone meal").in("bone", 4).out("bone_meal", 12).hours(0.3).skill(Skill.COOKING).add();

        // Bakery
        r("BAKERY", "bread", "Baking bread").in(Goods.FLOUR, 6).in(Recipe.FUEL, 2).out("bread", 4).hours(1).skill(Skill.COOKING).add();
        r("BAKERY", "bread_wheat", "Baking bread from grain").in("wheat", 6).in(Recipe.FUEL, 2).out("bread", 2).hours(1).skill(Skill.COOKING).add();
        r("BAKERY", "cookie", "Baking cookies").in(Goods.FLOUR, 2).in("cocoa_beans", 1).in(Recipe.FUEL, 1).out("cookie", 8).hours(0.8).skill(Skill.COOKING).add();
        r("BAKERY", "pumpkin_pie", "Baking pumpkin pie").in("pumpkin", 1).in("sugar", 1).in("egg", 1).in(Recipe.FUEL, 1).out("pumpkin_pie", 1).hours(0.5).skill(Skill.COOKING).add();
        r("BAKERY", "cake", "Baking a cake").in(Goods.FLOUR, 3).in("sugar", 2).in("egg", 1).in("milk_bucket", 3).in(Recipe.FUEL, 2)
                .out("cake", 1).out("bucket", 3).hours(1.5).skill(Skill.COOKING).level(2).add();

        // Butcher: meat at the smoker
        for (String meat : new String[]{"beef", "porkchop", "mutton", "chicken", "rabbit"}) {
            r("BUTCHER", "cook_" + meat, "Smoking " + meat).in(meat, 8).in(Recipe.FUEL, 4).out("cooked_" + meat, 8).hours(1).skill(Skill.COOKING).add();
        }

        // Kitchen: fish, potatoes, soups and proper meals
        r("KITCHEN", "baked_potato", "Baking potatoes").in("potato", 8).in(Recipe.FUEL, 4).out("baked_potato", 8).hours(0.8).skill(Skill.COOKING).add();
        r("KITCHEN", "cook_cod", "Cooking cod").in("cod", 6).in(Recipe.FUEL, 3).out("cooked_cod", 6).hours(0.8).skill(Skill.COOKING).add();
        r("KITCHEN", "cook_salmon", "Cooking salmon").in("salmon", 6).in(Recipe.FUEL, 3).out("cooked_salmon", 6).hours(0.8).skill(Skill.COOKING).add();
        r("KITCHEN", "beetroot_soup", "Making beetroot soup").in("beetroot", 6).in("bowl", 1).out("beetroot_soup", 1).hours(0.4).skill(Skill.COOKING).add();
        r("KITCHEN", "mushroom_stew", "Making mushroom stew").in("brown_mushroom", 1).in("red_mushroom", 1).in("bowl", 1).out("mushroom_stew", 1).hours(0.4).skill(Skill.COOKING).add();
        r("KITCHEN", "rabbit_stew", "Making rabbit stew").in("cooked_rabbit", 1).in("carrot", 1).in("baked_potato", 1).in("brown_mushroom", 1).in("bowl", 1)
                .out("rabbit_stew", 1).hours(0.5).skill(Skill.COOKING).add();
        r("KITCHEN", "hearty_meal", "Preparing hearty meals").in("#cooked_meat", 2).in("#vegetable", 2).in("bread", 1).out(Goods.MEAL, 3).hours(1).skill(Skill.COOKING).level(2).add();

        // Blacksmith: smelting, then tools, arms and armour on demand
        r("BLACKSMITH", "smelt_iron", "Smelting iron").in("raw_iron", 8).in(Recipe.FUEL, 8).out("iron_ingot", 8).hours(1).skill(Skill.SMITHING).add();
        r("BLACKSMITH", "smelt_gold", "Smelting gold").in("raw_gold", 8).in(Recipe.FUEL, 8).out("gold_ingot", 8).hours(1).skill(Skill.SMITHING).add();
        r("BLACKSMITH", "smelt_copper", "Smelting copper").in("raw_copper", 8).in(Recipe.FUEL, 8).out("copper_ingot", 8).hours(1).skill(Skill.SMITHING).add();
        for (String metal : new String[]{"iron", "stone"}) {
            String mat = metal.equals("iron") ? "iron_ingot" : "cobblestone";
            r("BLACKSMITH", metal + "_pickaxe", "Forging a " + metal + " pickaxe").in(mat, 3).in("stick", 2).out(metal + "_pickaxe", 1).hours(0.6).skill(Skill.SMITHING).demand().add();
            r("BLACKSMITH", metal + "_axe", "Forging a " + metal + " axe").in(mat, 3).in("stick", 2).out(metal + "_axe", 1).hours(0.6).skill(Skill.SMITHING).demand().add();
            r("BLACKSMITH", metal + "_hoe", "Forging a " + metal + " hoe").in(mat, 2).in("stick", 2).out(metal + "_hoe", 1).hours(0.5).skill(Skill.SMITHING).demand().add();
            r("BLACKSMITH", metal + "_shovel", "Forging a " + metal + " shovel").in(mat, 1).in("stick", 2).out(metal + "_shovel", 1).hours(0.4).skill(Skill.SMITHING).demand().add();
            r("BLACKSMITH", metal + "_sword", "Forging a " + metal + " sword").in(mat, 2).in("stick", 1).out(metal + "_sword", 1).hours(0.6).skill(Skill.SMITHING).demand().add();
        }
        r("BLACKSMITH", "iron_shears", "Forging shears").in("iron_ingot", 2).out("shears", 1).hours(0.4).skill(Skill.SMITHING).demand().add();
        r("BLACKSMITH", "iron_helmet", "Forging an iron helmet").in("iron_ingot", 5).out("iron_helmet", 1).hours(0.8).skill(Skill.SMITHING).level(2).demand().add();
        r("BLACKSMITH", "iron_chestplate", "Forging an iron chestplate").in("iron_ingot", 8).out("iron_chestplate", 1).hours(1.2).skill(Skill.SMITHING).level(2).demand().add();
        r("BLACKSMITH", "iron_leggings", "Forging iron leggings").in("iron_ingot", 7).out("iron_leggings", 1).hours(1.1).skill(Skill.SMITHING).level(2).demand().add();
        r("BLACKSMITH", "iron_boots", "Forging iron boots").in("iron_ingot", 4).out("iron_boots", 1).hours(0.7).skill(Skill.SMITHING).level(2).demand().add();
        r("BLACKSMITH", "bucket", "Making a bucket").in("iron_ingot", 3).out("bucket", 1).hours(0.4).skill(Skill.SMITHING).add();
        r("BLACKSMITH", "iron_bars", "Making iron bars").in("iron_ingot", 6).out("iron_bars", 16).hours(0.5).skill(Skill.SMITHING).add();
        r("BLACKSMITH", "horse_armor", "Making iron horse armour").in("iron_ingot", 7).in("leather", 1).out("iron_horse_armor", 1).hours(1).skill(Skill.SMITHING).level(3).demand().add();

        // Carpentry: logs into planks and the colony's wooden goods
        for (String w : WOODS) {
            r("CARPENTRY", w + "_planks", "Sawing " + w.replace('_', ' ') + " planks").in(w + "_log", 4).out(w + "_planks", 16).hours(0.4).add();
        }
        r("CARPENTRY", "sticks", "Cutting sticks").in("#planks", 4).out("stick", 8).hours(0.2).add();
        r("CARPENTRY", "ladder", "Making ladders").in("stick", 7).out("ladder", 3).hours(0.3).add();
        r("CARPENTRY", "chest", "Making a chest").in("#planks", 8).out("chest", 1).hours(0.4).add();
        r("CARPENTRY", "barrel", "Making a barrel").in("#planks", 7).out("barrel", 1).hours(0.4).add();
        r("CARPENTRY", "oak_door", "Making doors").in("oak_planks", 6).out("oak_door", 3).hours(0.4).add();
        r("CARPENTRY", "oak_fence", "Making fences").in("oak_planks", 4).in("stick", 2).out("oak_fence", 3).hours(0.3).add();
        r("CARPENTRY", "oak_fence_gate", "Making a gate").in("oak_planks", 2).in("stick", 4).out("oak_fence_gate", 1).hours(0.3).add();
        r("CARPENTRY", "bowl", "Turning bowls").in("#planks", 3).out("bowl", 4).hours(0.3).add();
        r("CARPENTRY", "charcoal", "Burning charcoal").in("#log", 8).in(Recipe.FUEL, 2).out("charcoal", 8).hours(1).add();
        r("CARPENTRY", "torch", "Making torches").in("stick", 4).in("coal", 4).out("torch", 16).hours(0.3).add();
        r("CARPENTRY", "torch_charcoal", "Making torches (charcoal)").in("stick", 4).in("charcoal", 4).out("torch", 16).hours(0.3).add();
        r("CARPENTRY", "wooden_beds", "Making beds").in("#wool", 3).in("#planks", 3).out("white_bed", 1).hours(0.4).add();

        // Masonry: stone into building blocks
        r("MASONRY", "stone", "Firing stone").in("cobblestone", 16).in(Recipe.FUEL, 16).out("stone", 16).hours(1).skill(Skill.CONSTRUCTION).add();
        r("MASONRY", "stone_bricks", "Cutting stone bricks").in("stone", 8).out("stone_bricks", 8).hours(0.6).skill(Skill.CONSTRUCTION).add();
        r("MASONRY", "smooth_stone", "Smoothing stone").in("stone", 8).in(Recipe.FUEL, 8).out("smooth_stone", 8).hours(0.8).skill(Skill.CONSTRUCTION).add();
        r("MASONRY", "glass", "Firing glass").in("sand", 16).in(Recipe.FUEL, 16).out("glass", 16).hours(1).skill(Skill.CONSTRUCTION).add();
        r("MASONRY", "brick", "Firing bricks").in("clay_ball", 16).in(Recipe.FUEL, 16).out("brick", 16).hours(1).skill(Skill.CONSTRUCTION).add();
        r("MASONRY", "bricks", "Laying brick blocks").in("brick", 8).out("bricks", 2).hours(0.3).skill(Skill.CONSTRUCTION).add();
        r("MASONRY", "cobblestone_wall", "Cutting wall blocks").in("cobblestone", 6).out("cobblestone_wall", 6).hours(0.4).skill(Skill.CONSTRUCTION).add();
        // crushing and sifting: stone down to gravel, sand and flint (kept to a small stock unless a target says otherwise)
        r("MASONRY", "gravel", "Crushing stone to gravel").in("cobblestone", 8).out("gravel", 8).hours(0.5).skill(Skill.CONSTRUCTION).add();
        r("MASONRY", "sand", "Crushing gravel to sand").in("gravel", 8).out("sand", 8).hours(0.5).skill(Skill.CONSTRUCTION).add();
        r("MASONRY", "flint", "Sifting gravel for flint").in("gravel", 8).out("flint", 4).hours(0.4).skill(Skill.CONSTRUCTION).add();

        // Workshop: ammunition, bows, leather and paper
        r("WORKSHOP", "arrows", "Fletching arrows").in("flint", 2).in("stick", 2).in("feather", 2).out("arrow", 8).hours(0.5).add();
        r("WORKSHOP", "javelins", "Making javelins").in("stick", 4).in("flint", 2).out(Goods.JAVELIN, 4).hours(0.5).add();
        r("WORKSHOP", "bow", "Making a bow").in("stick", 3).in("string", 3).out("bow", 1).hours(0.6).demand().add();
        r("WORKSHOP", "crossbow", "Making a crossbow").in("stick", 3).in("string", 2).in("iron_ingot", 2).out("crossbow", 1).hours(1).level(2).demand().add();
        r("WORKSHOP", "shield", "Making a shield").in("#planks", 6).in("iron_ingot", 1).out("shield", 1).hours(0.6).demand().add();
        r("WORKSHOP", "fishing_rod", "Making a fishing rod").in("stick", 3).in("string", 2).out("fishing_rod", 1).hours(0.3).demand().add();
        r("WORKSHOP", "leather_helmet", "Sewing a leather cap").in("leather", 5).out("leather_helmet", 1).hours(0.5).demand().add();
        r("WORKSHOP", "leather_chestplate", "Sewing a leather tunic").in("leather", 8).out("leather_chestplate", 1).hours(0.8).demand().add();
        r("WORKSHOP", "leather_leggings", "Sewing leather trousers").in("leather", 7).out("leather_leggings", 1).hours(0.7).demand().add();
        r("WORKSHOP", "leather_boots", "Sewing leather boots").in("leather", 4).out("leather_boots", 1).hours(0.4).demand().add();
        r("WORKSHOP", "paper", "Making paper").in("sugar_cane", 6).out("paper", 6).hours(0.4).add();
        r("WORKSHOP", "book", "Binding books").in("paper", 3).in("leather", 1).out("book", 1).hours(0.5).add();
        r("WORKSHOP", "glass_bottle", "Blowing bottles").in("glass", 3).out("glass_bottle", 3).hours(0.3).add();
        r("WORKSHOP", "saddle", "Making a saddle").in("leather", 5).in("iron_ingot", 1).out("saddle", 1).hours(1.2).level(2).demand().add();

        // Clinic: medicine
        r("CLINIC", "medicine", "Preparing medicine").in("sugar", 1).in("#herb", 3).out(Goods.MEDICINE, 2).hours(0.8).skill(Skill.MEDICINE).add();

        // Siege workshop
        r("ENGINEERING_WORKSHOP", "siege_charge", "Packing siege charges").in("gunpowder", 4).in("sand", 4).out(Goods.SIEGE_CHARGE, 1).hours(1.5).skill(Skill.ENGINEERING).add();
        r("ENGINEERING_WORKSHOP", "siege_charge_tnt", "Packing siege charges (TNT)").in("tnt", 1).out(Goods.SIEGE_CHARGE, 1).hours(0.5).skill(Skill.ENGINEERING).add();
    }

    public static List<Recipe> of(String station) {
        return Collections.unmodifiableList(BY_STATION.getOrDefault(station, List.of()));
    }

    public static Recipe get(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    public static java.util.Collection<Recipe> all() {
        return Collections.unmodifiableCollection(BY_ID.values());
    }

    // ───────────── resolving ─────────────

    /**
     * The concrete goods one batch of a recipe needs from a stock, or null if the stock can't supply it.
     * Groups take whatever member is most plentiful; fuel takes coal first, wood last.
     *
     * @param usable goods the colony is willing to spend (stock targets can hold some back)
     */
    public static Map<String, Long> resolve(Recipe r, Ledger stock, Predicate<String> usable) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : r.inputs.entrySet()) {
            String k = e.getKey();
            long need = e.getValue();
            if (k.equals(Recipe.FUEL)) continue;
            if (isGroup(k)) {
                // as many different members as it takes, biggest pile first
                List<String> members = new ArrayList<>(members(k));
                members.sort((a, b) -> Long.compare(left(stock, b, out), left(stock, a, out)));
                for (String m : members) {
                    if (need <= 0) break;
                    if (!usable.test(m)) continue;
                    long take = Math.min(need, left(stock, m, out));
                    if (take <= 0) continue;
                    out.merge(m, take, Long::sum);
                    need -= take;
                }
                if (need > 0) return null;
            } else {
                if (!usable.test(k) || left(stock, k, out) < need) return null;
                out.merge(k, need, Long::sum);
            }
        }
        int fuel = r.fuel();
        if (fuel > 0) {
            Map<String, Long> f = fuel(fuel, stock, out, usable);
            if (f == null) return null;
            for (Map.Entry<String, Long> e : f.entrySet()) out.merge(e.getKey(), e.getValue(), Long::sum);
        }
        return out;
    }

    private static long left(Ledger stock, String key, Map<String, Long> already) {
        return stock.available(key) - already.getOrDefault(key, 0L);
    }

    /** Fuel goods covering the units: one kind, the best there's enough of. */
    static Map<String, Long> fuel(int units, Ledger stock, Map<String, Long> already, Predicate<String> usable) {
        for (Map.Entry<String, Double> e : FUEL_UNITS.entrySet()) {
            String k = e.getKey();
            if (!usable.test(k)) continue;
            long need = (long) Math.ceil(units / e.getValue() - 1e-9);
            if (left(stock, k, already) >= need) return Map.of(k, need);
        }
        return null;
    }

    /** Which input is missing, for "waiting for..." messages (null if none). */
    public static String missing(Recipe r, Ledger stock, Predicate<String> usable) {
        for (Map.Entry<String, Integer> e : r.inputs.entrySet()) {
            String k = e.getKey();
            if (k.equals(Recipe.FUEL)) continue;
            long have = 0;
            if (isGroup(k)) {
                for (String m : members(k)) if (usable.test(m)) have += stock.available(m);
            } else if (usable.test(k)) {
                have = stock.available(k);
            }
            if (have < e.getValue()) return e.getValue() + " " + (isGroup(k) ? groupName(k) : Goods.get(k).name());
        }
        if (r.fuel() > 0 && fuel(r.fuel(), stock, Map.of(), usable) == null) return "fuel (coal, charcoal or wood)";
        return null;
    }
}
