package com.colonysmp.core;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Every good the plugin knows. Vanilla items are registered at start-up from the server's item list
 * ({@link #classify}); colony goods (flour, medicine...) are registered by {@link #registerColonyGoods()}.
 * Values are base market prices; config can override them.
 */
public final class Goods {

    public static final String FLOUR = "flour", MEAL = "hearty_meal", MEDICINE = "medicine", JAVELIN = "javelin",
            WAR_HORSE = "war_horse", SIEGE_CHARGE = "siege_charge";

    private static final Map<String, Good> ALL = new LinkedHashMap<>();
    private static final Good UNKNOWN = new Good("unknown", "Unknown", Category.MISC, 64, 1, 0, 0, "STONE", false);

    private Goods() {}

    public static void clear() {
        ALL.clear();
    }

    public static void register(Good g) {
        ALL.put(g.key(), g);
    }

    public static Good get(String key) {
        Good g = key == null ? null : ALL.get(key);
        return g != null ? g : UNKNOWN;
    }

    public static boolean known(String key) {
        return key != null && ALL.containsKey(key);
    }

    public static Collection<Good> all() {
        return Collections.unmodifiableCollection(ALL.values());
    }

    public static int stack(String key) {
        return Math.max(1, get(key).stack());
    }

    public static String key(String materialName) {
        return materialName.toLowerCase(Locale.ROOT);
    }

    /** Overrides the base value of a good (from config). */
    public static void setValue(String key, double value) {
        Good g = ALL.get(key);
        if (g != null) {
            ALL.put(key, new Good(g.key(), g.name(), g.category(), g.stack(), Math.max(0.01, value), g.nutrition(), g.quality(), g.material(), g.custom()));
        }
    }

    /** The colony's own goods: intermediate products that only exist as tagged items. */
    public static void registerColonyGoods() {
        register(new Good(FLOUR, "Flour", Category.FOOD, 64, 1.2, 0, 0, "WHITE_DYE", true));
        register(new Good(MEAL, "Hearty Meal", Category.FOOD, 16, 9, 10, 3, "MUSHROOM_STEW", true));
        register(new Good(MEDICINE, "Medicine", Category.MEDICAL, 16, 8, 0, 0, "HONEY_BOTTLE", true));
        register(new Good(JAVELIN, "Javelin", Category.AMMO, 16, 1.5, 0, 0, "STICK", true));
        register(new Good(WAR_HORSE, "War Horse", Category.ANIMAL, 1, 60, 0, 0, "SADDLE", true));
        register(new Good(SIEGE_CHARGE, "Siege Charge", Category.AMMO, 8, 25, 0, 0, "TNT", true));
    }

    // ───────────── vanilla items ─────────────

    /** Builds the good for a vanilla item from its material name, stack size and durability. */
    public static Good classify(String material, int maxStack, int maxDurability) {
        String n = material.toUpperCase(Locale.ROOT);
        String key = key(n);
        Category cat = category(n, maxDurability);
        double nutrition = NUTRITION.getOrDefault(n, 0.0);
        int quality = QUALITY.getOrDefault(n, nutrition > 0 ? 1 : 0);
        double value = VALUES.getOrDefault(n, defaultValue(n, cat, maxDurability));
        return new Good(key, nice(n), cat, Math.max(1, maxStack), value, nutrition, quality, n, false);
    }

    static Category category(String n, int durability) {
        Category c = CATEGORY.get(n);
        if (c != null) return c;
        if (durability > 0) {
            if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS") || n.equals("SHIELD")) return Category.ARMOR;
            if (n.endsWith("_SWORD") || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT") || n.equals("MACE")) return Category.WEAPON;
            return Category.TOOL;
        }
        if (n.startsWith("COOKED_")) return Category.FOOD;
        if (n.endsWith("_SEEDS")) return Category.SEED;
        if (n.endsWith("_ORE") || n.startsWith("RAW_")) return Category.ORE;
        if (n.endsWith("_INGOT") || n.endsWith("_NUGGET")) return Category.METAL;
        if (n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_PLANKS") || n.endsWith("_STEM") || n.endsWith("_HYPHAE") || n.endsWith("_SAPLING")) return Category.WOOD;
        if (n.contains("STONE") || n.contains("DEEPSLATE") || n.contains("BRICK") || n.contains("GRANITE") || n.contains("DIORITE")
                || n.contains("ANDESITE") || n.contains("TUFF") || n.contains("SANDSTONE") || n.equals("GRAVEL") || n.equals("SAND")) return Category.STONE;
        if (n.endsWith("_WOOL") || n.endsWith("_CARPET")) return Category.MATERIAL;
        return Category.MISC;
    }

    private static double defaultValue(String n, Category c, int durability) {
        if (durability > 0) {
            double tier = n.startsWith("NETHERITE_") ? 200 : n.startsWith("DIAMOND_") ? 60 : n.startsWith("IRON_") || n.startsWith("CHAINMAIL_") ? 20
                    : n.startsWith("GOLDEN_") ? 25 : n.startsWith("STONE_") ? 3 : n.startsWith("LEATHER_") ? 6 : 2;
            if (n.endsWith("_CHESTPLATE")) tier *= 1.6;
            else if (n.endsWith("_LEGGINGS")) tier *= 1.4;
            return tier;
        }
        return switch (c) {
            case FOOD -> 2;
            case CROP, SEED -> 0.5;
            case MEAT, FISH -> 1.5;
            case ANIMAL -> 1.5;
            case WOOD -> n.endsWith("_PLANKS") ? 0.4 : 1.2;
            case STONE -> 0.2;
            case ORE -> 6;
            case METAL -> 8;
            case MATERIAL -> 1;
            case AMMO -> 0.5;
            case LUXURY -> 20;
            default -> 1;
        };
    }

    public static String nice(String enumName) {
        String[] parts = enumName.toLowerCase(Locale.ROOT).split("_");
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return b.toString();
    }

    // ───────────── data tables ─────────────

    private static final Map<String, Double> NUTRITION = new HashMap<>();
    private static final Map<String, Integer> QUALITY = new HashMap<>();
    private static final Map<String, Double> VALUES = new HashMap<>();
    private static final Map<String, Category> CATEGORY = new HashMap<>();

    private static void food(String n, double points, int quality, double value, Category c) {
        NUTRITION.put(n, points);
        QUALITY.put(n, quality);
        VALUES.put(n, value);
        CATEGORY.put(n, c);
    }

    private static void val(String n, double value, Category c) {
        VALUES.put(n, value);
        CATEGORY.put(n, c);
    }

    static {
        // raw crops (edible but plain)
        // raw grain is a last resort (porridge): poor nutrition, and the bakers would rather have it
        food("WHEAT", 1.5, 0, 0.6, Category.CROP);
        food("CARROT", 3, 0, 0.7, Category.CROP);
        food("POTATO", 1, 0, 0.5, Category.CROP);
        food("BEETROOT", 1, 0, 0.5, Category.CROP);
        food("MELON_SLICE", 2, 0, 0.3, Category.CROP);
        food("PUMPKIN", 0, 0, 1.2, Category.CROP);
        food("MELON", 0, 0, 2.5, Category.CROP);
        food("APPLE", 4, 1, 1.5, Category.CROP);
        food("SWEET_BERRIES", 2, 0, 0.4, Category.CROP);
        food("GLOW_BERRIES", 2, 0, 0.6, Category.CROP);
        food("SUGAR_CANE", 0, 0, 0.5, Category.CROP);
        food("COCOA_BEANS", 0, 0, 1, Category.CROP);
        food("NETHER_WART", 0, 0, 1, Category.CROP);
        food("HAY_BLOCK", 0, 0, 5, Category.CROP);
        // raw animal food
        food("BEEF", 3, 0, 2, Category.MEAT);
        food("PORKCHOP", 3, 0, 2, Category.MEAT);
        food("MUTTON", 2, 0, 1.6, Category.MEAT);
        food("CHICKEN", 2, 0, 1.4, Category.MEAT);
        food("RABBIT", 3, 0, 1.6, Category.MEAT);
        food("COD", 2, 0, 1.2, Category.FISH);
        food("SALMON", 2, 0, 1.6, Category.FISH);
        food("TROPICAL_FISH", 1, 0, 1, Category.FISH);
        food("PUFFERFISH", 0, 0, 1, Category.FISH);
        // cooked and prepared food
        food("COOKED_BEEF", 8, 2, 4.5, Category.FOOD);
        food("COOKED_PORKCHOP", 8, 2, 4.5, Category.FOOD);
        food("COOKED_MUTTON", 6, 2, 3.5, Category.FOOD);
        food("COOKED_CHICKEN", 6, 2, 3.2, Category.FOOD);
        food("COOKED_RABBIT", 5, 2, 3.2, Category.FOOD);
        food("COOKED_COD", 5, 2, 2.6, Category.FOOD);
        food("COOKED_SALMON", 6, 2, 3.2, Category.FOOD);
        food("BAKED_POTATO", 5, 1, 1.6, Category.FOOD);
        food("BREAD", 5, 2, 2.4, Category.FOOD);
        food("COOKIE", 2, 2, 0.8, Category.FOOD);
        food("PUMPKIN_PIE", 8, 3, 6, Category.FOOD);
        food("CAKE", 14, 3, 14, Category.FOOD);
        food("MUSHROOM_STEW", 6, 3, 4, Category.FOOD);
        food("BEETROOT_SOUP", 6, 3, 3.5, Category.FOOD);
        food("RABBIT_STEW", 10, 3, 7, Category.FOOD);
        food("DRIED_KELP", 1, 1, 0.3, Category.FOOD);
        food("HONEY_BOTTLE", 6, 2, 3, Category.FOOD);
        food("GOLDEN_CARROT", 6, 3, 12, Category.LUXURY);
        food("GOLDEN_APPLE", 4, 3, 60, Category.LUXURY);
        // animal products
        val("EGG", 0.6, Category.ANIMAL);
        val("MILK_BUCKET", 3, Category.ANIMAL);
        val("LEATHER", 2, Category.ANIMAL);
        val("FEATHER", 0.4, Category.ANIMAL);
        val("RABBIT_HIDE", 0.8, Category.ANIMAL);
        val("WHITE_WOOL", 1.2, Category.MATERIAL);
        val("STRING", 0.8, Category.MATERIAL);
        val("BONE", 0.5, Category.MATERIAL);
        val("BONE_MEAL", 0.3, Category.MATERIAL);
        val("HONEYCOMB", 2, Category.MATERIAL);
        // seeds
        val("WHEAT_SEEDS", 0.2, Category.SEED);
        val("BEETROOT_SEEDS", 0.2, Category.SEED);
        val("PUMPKIN_SEEDS", 0.4, Category.SEED);
        val("MELON_SEEDS", 0.4, Category.SEED);
        // materials
        val("SUGAR", 0.8, Category.MATERIAL);
        val("STICK", 0.15, Category.WOOD);
        val("CHARCOAL", 1.5, Category.MATERIAL);
        val("COAL", 2, Category.ORE);
        val("FLINT", 0.5, Category.MATERIAL);
        val("PAPER", 0.6, Category.MATERIAL);
        val("BOOK", 4, Category.MATERIAL);
        val("GLASS", 0.8, Category.MATERIAL);
        val("GLASS_BOTTLE", 0.6, Category.MATERIAL);
        val("BUCKET", 9, Category.TOOL);
        val("WATER_BUCKET", 9, Category.TOOL);
        val("BOWL", 0.3, Category.MATERIAL);
        val("CLAY_BALL", 0.3, Category.MATERIAL);
        val("BRICK", 0.6, Category.MATERIAL);
        val("GUNPOWDER", 3, Category.MATERIAL);
        val("TORCH", 0.3, Category.MATERIAL);
        val("SADDLE", 30, Category.LUXURY);
        // metals and gems
        val("IRON_INGOT", 9, Category.METAL);
        val("GOLD_INGOT", 18, Category.METAL);
        val("COPPER_INGOT", 3, Category.METAL);
        val("NETHERITE_INGOT", 400, Category.METAL);
        val("NETHERITE_SCRAP", 90, Category.METAL);
        // valuables are what treasuries hold; every worth is measured against the iron nugget (1)
        val("IRON_NUGGET", 1, Category.METAL);
        val("IRON_BLOCK", 81, Category.METAL);
        val("GOLD_BLOCK", 162, Category.METAL);
        val("COPPER_BLOCK", 27, Category.METAL);
        val("EMERALD_BLOCK", 90, Category.LUXURY);
        val("DIAMOND_BLOCK", 540, Category.LUXURY);
        val("GOLD_NUGGET", 2, Category.METAL);
        val("RAW_IRON", 6, Category.ORE);
        val("RAW_GOLD", 10, Category.ORE);
        val("RAW_COPPER", 2, Category.ORE);
        val("DIAMOND", 60, Category.LUXURY);
        val("EMERALD", 10, Category.LUXURY);
        val("LAPIS_LAZULI", 2, Category.ORE);
        val("REDSTONE", 1.5, Category.ORE);
        val("QUARTZ", 2, Category.ORE);
        val("AMETHYST_SHARD", 2, Category.MATERIAL);
        // ammunition
        val("ARROW", 0.5, Category.AMMO);
        val("SPECTRAL_ARROW", 1.5, Category.AMMO);
        // stone the masons use
        val("COBBLESTONE", 0.1, Category.STONE);
        val("STONE", 0.25, Category.STONE);
        val("STONE_BRICKS", 0.35, Category.STONE);
        val("SMOOTH_STONE", 0.35, Category.STONE);
        val("COBBLED_DEEPSLATE", 0.12, Category.STONE);
        val("DIRT", 0.05, Category.MISC);
        val("SAND", 0.1, Category.STONE);
        val("GRAVEL", 0.1, Category.STONE);
    }
}
