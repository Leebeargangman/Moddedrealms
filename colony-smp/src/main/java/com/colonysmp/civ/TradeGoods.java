package com.colonysmp.civ;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the civilisations make, use and barter: ordinary Minecraft items, each with a base worth. There is no
 * money anywhere in this economy. A "worth" is only a yardstick for working out how many of one item a
 * settlement will give for another; nobody ever holds a balance of it.
 *
 * <p>Base worths follow what an item costs to make from its ingredients, so that crafting something and trading
 * it back is never a profit at base prices. What a settlement actually offers moves a long way from the base
 * with its own stock and needs (see {@link Prices}).
 */
public final class TradeGoods {

    public enum Cat { FOOD, CROP, ANIMAL, FISH, WOOD, STONE, ORE, INGOT, TOOL, WEAPON, ARMOUR, BUILDING, MANUFACTURED, RARE }

    /** @param nutrition how many people one item feeds for a day (0 if it isn't eaten) */
    public record Item(String key, Cat cat, double worth, int stack, double nutrition) {
        public String name() {
            StringBuilder b = new StringBuilder();
            for (String p : key.split("_")) {
                if (p.isEmpty()) continue;
                if (!b.isEmpty()) b.append(' ');
                b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            }
            return b.toString();
        }
    }

    private static final Map<String, Item> ALL = new LinkedHashMap<>();

    private TradeGoods() {}

    private static void add(Cat cat, int stack, Object... keyWorth) {
        for (int i = 0; i < keyWorth.length; i += 2) {
            String k = (String) keyWorth[i];
            ALL.put(k, new Item(k, cat, ((Number) keyWorth[i + 1]).doubleValue(), stack, 0));
        }
    }

    private static void food(String key, double worth, int stack, double nutrition) {
        ALL.put(key, new Item(key, Cat.FOOD, worth, stack, nutrition));
    }

    static {
        food("bread", 3.2, 64, 1.0);
        food("baked_potato", 1.4, 64, 0.8);
        food("cooked_beef", 4.2, 64, 1.5);
        food("cooked_porkchop", 4.2, 64, 1.5);
        food("cooked_chicken", 3.2, 64, 1.2);
        food("cooked_mutton", 3.2, 64, 1.2);
        food("cooked_cod", 3.0, 64, 1.0);
        food("cooked_salmon", 3.6, 64, 1.2);
        food("pumpkin_pie", 5.0, 64, 1.5);
        food("cookie", 0.8, 64, 0.3);
        food("apple", 2.0, 64, 0.6);
        food("golden_carrot", 16, 64, 1.5);
        food("honey_bottle", 5.0, 16, 0.8);
        // raw crops are eaten when there is nothing better
        ALL.put("carrot", new Item("carrot", Cat.CROP, 0.8, 64, 0.45));
        ALL.put("potato", new Item("potato", Cat.CROP, 0.8, 64, 0.3));
        ALL.put("beetroot", new Item("beetroot", Cat.CROP, 0.8, 64, 0.3));
        add(Cat.CROP, 64, "wheat", 1.0, "wheat_seeds", 0.2, "sugar_cane", 1.0, "pumpkin", 2.0, "melon_slice", 0.4, "hay_block", 9.2, "sugar", 1.1, "cocoa_beans", 1.0);
        add(Cat.CROP, 16, "egg", 0.8);
        add(Cat.ANIMAL, 64, "leather", 3.0, "white_wool", 1.5, "feather", 0.8, "beef", 2.2, "porkchop", 2.2, "chicken", 1.6, "mutton", 1.6, "string", 1.0,
                "bone", 1.0, "honeycomb", 3.0, "rabbit_hide", 0.8);
        // raw fish is eaten at need, like raw crops
        ALL.put("cod", new Item("cod", Cat.FISH, 1.5, 64, 0.4));
        ALL.put("salmon", new Item("salmon", Cat.FISH, 2.0, 64, 0.5));
        add(Cat.FISH, 64, "dried_kelp", 0.5, "ink_sac", 1.5, "prismarine_shard", 3.0);
        add(Cat.WOOD, 64, "oak_log", 2.0, "spruce_log", 2.0, "birch_log", 2.0, "oak_planks", 0.55, "spruce_planks", 0.55, "birch_planks", 0.55, "stick", 0.3,
                "charcoal", 2.4, "oak_sapling", 1.0);
        add(Cat.STONE, 64, "cobblestone", 0.3, "stone", 0.55, "stone_bricks", 0.65, "gravel", 0.4, "sand", 0.4, "sandstone", 1.7, "clay_ball", 0.6,
                "brick", 1.0, "glass", 0.8, "flint", 0.8, "andesite", 0.4, "granite", 0.4, "diorite", 0.4, "cobbled_deepslate", 0.4);
        add(Cat.ORE, 64, "coal", 2.0, "raw_iron", 6.0, "raw_copper", 2.0, "raw_gold", 12, "redstone", 2.0, "lapis_lazuli", 3.0, "quartz", 3.0);
        add(Cat.INGOT, 64, "iron_ingot", 8.5, "copper_ingot", 3.2, "gold_ingot", 15, "iron_nugget", 0.95, "gold_nugget", 1.7, "iron_block", 77,
                "coal_block", 18.2, "gold_block", 136, "copper_block", 29);
        add(Cat.TOOL, 1, "stone_pickaxe", 1.6, "stone_axe", 1.6, "stone_hoe", 1.3, "stone_shovel", 1.0, "iron_pickaxe", 27, "iron_axe", 27, "iron_shovel", 9.6,
                "iron_hoe", 18, "shears", 17.5, "fishing_rod", 2.6, "flint_and_steel", 9.6, "diamond_pickaxe", 185, "diamond_axe", 185);
        add(Cat.TOOL, 16, "bucket", 26);
        add(Cat.TOOL, 64, "torch", 0.65);
        add(Cat.WEAPON, 1, "stone_sword", 1.0, "iron_sword", 17.5, "diamond_sword", 122, "bow", 4.2, "crossbow", 14.5, "shield", 12);
        add(Cat.WEAPON, 64, "arrow", 0.6);
        add(Cat.ARMOUR, 1, "leather_helmet", 15.5, "leather_chestplate", 24.5, "leather_leggings", 21.5, "leather_boots", 12.5, "iron_helmet", 43,
                "iron_chestplate", 69, "iron_leggings", 60, "iron_boots", 35, "chainmail_chestplate", 55, "chainmail_helmet", 35);
        add(Cat.BUILDING, 64, "bricks", 4.2, "glass_pane", 0.35, "iron_bars", 3.3, "rail", 3.3, "ladder", 0.75, "oak_fence", 0.95, "oak_door", 1.15,
                "bookshelf", 21, "lantern", 2.2, "chest", 4.6, "barrel", 4.0, "campfire", 4.0);
        add(Cat.MANUFACTURED, 64, "paper", 1.1, "book", 6.4, "candle", 4.2, "lead", 4.6, "compass", 36, "clock", 62);
        add(Cat.MANUFACTURED, 1, "oak_boat", 2.9, "minecart", 43, "saddle", 40);
        add(Cat.RARE, 64, "emerald", 12, "diamond", 60, "amethyst_shard", 4.0, "obsidian", 6.0, "glowstone_dust", 3.0, "nautilus_shell", 30, "name_tag", 40);
        add(Cat.RARE, 16, "ender_pearl", 15);
    }

    public static Item get(String key) {
        return key == null ? null : ALL.get(key);
    }

    public static boolean known(String key) {
        return key != null && ALL.containsKey(key);
    }

    public static Collection<Item> all() {
        return Collections.unmodifiableCollection(ALL.values());
    }

    public static double worth(String key) {
        Item i = ALL.get(key);
        return i == null ? 0 : i.worth();
    }

    public static int stack(String key) {
        Item i = ALL.get(key);
        return i == null ? 64 : i.stack();
    }

    /** Overrides a base worth (from config). */
    public static void setWorth(String key, double worth) {
        Item i = ALL.get(key);
        if (i != null && worth > 0) ALL.put(key, new Item(i.key(), i.cat(), worth, i.stack(), i.nutrition()));
    }
}
