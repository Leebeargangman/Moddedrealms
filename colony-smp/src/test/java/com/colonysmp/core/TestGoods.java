package com.colonysmp.core;

/** Registers the goods the tests use, the way the plugin does at start-up (without a server). */
final class TestGoods {

    private TestGoods() {}

    static void setUp() {
        Goods.clear();
        String[][] items = {
                {"WHEAT", "64"}, {"WHEAT_SEEDS", "64"}, {"BREAD", "64"}, {"CARROT", "64"}, {"POTATO", "64"}, {"BAKED_POTATO", "64"},
                {"BEEF", "64"}, {"COOKED_BEEF", "64"}, {"COOKED_PORKCHOP", "64"}, {"COD", "64"}, {"COOKED_COD", "64"}, {"BEETROOT", "64"},
                {"COAL", "64"}, {"CHARCOAL", "64"}, {"OAK_LOG", "64"}, {"OAK_PLANKS", "64"}, {"STICK", "64"}, {"COBBLESTONE", "64"},
                {"RAW_IRON", "64"}, {"IRON_INGOT", "64"}, {"EGG", "16"}, {"SUGAR", "64"}, {"SUGAR_CANE", "64"}, {"MILK_BUCKET", "1"},
                {"BUCKET", "16"}, {"BOWL", "64"}, {"SWEET_BERRIES", "64"}, {"FLINT", "64"}, {"FEATHER", "64"}, {"ARROW", "64"},
                {"DIAMOND", "64"}, {"STONE", "64"}, {"IRON_NUGGET", "64"}, {"IRON_BLOCK", "64"}, {"GOLD_NUGGET", "64"},
                {"GOLD_INGOT", "64"}, {"GOLD_BLOCK", "64"}, {"COPPER_INGOT", "64"}, {"COPPER_BLOCK", "64"}, {"EMERALD", "64"},
                {"EMERALD_BLOCK", "64"}, {"DIAMOND_BLOCK", "64"}, {"NETHERITE_INGOT", "64"}
        };
        for (String[] it : items) Goods.register(Goods.classify(it[0], Integer.parseInt(it[1]), 0));
        Goods.register(Goods.classify("IRON_PICKAXE", 1, 250));
        Goods.register(Goods.classify("IRON_SWORD", 1, 250));
        Goods.registerColonyGoods();
    }
}
