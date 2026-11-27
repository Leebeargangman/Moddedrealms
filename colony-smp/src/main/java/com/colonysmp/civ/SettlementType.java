package com.colonysmp.civ;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The kinds of settlement, and what each is for. A type is a list of industries (what its workers make, from
 * what) and of wants (what its people use up that they don't make). Nothing else decides its economy: a mining
 * settlement exports ore because its workers dig it, and must barter for bread because they don't bake enough.
 */
public enum SettlementType {
    FARMING_VILLAGE("Farming Village", "village", 0.15, 1.0, 0.04),
    MINING_SETTLEMENT("Mining Settlement", "diggings", 0.15, 1.0, 0.05),
    TRADING_TOWN("Trading Town", "market town", 0.08, 2.0, 0.05),
    MILITARY_SETTLEMENT("Military Settlement", "garrison", 0.18, 0.8, 0.22),
    FISHING_VILLAGE("Fishing Village", "harbour", 0.15, 1.0, 0.04),
    INDUSTRIAL_TOWN("Industrial Town", "foundry town", 0.14, 1.2, 0.05),
    COASTAL_SETTLEMENT("Coastal Settlement", "port", 0.12, 1.5, 0.05),
    FORTIFIED_KINGDOM("Fortified Kingdom", "citadel", 0.15, 1.2, 0.15),
    BANDIT_CAMP("Bandit Camp", "camp", 0.40, 0.5, 0.5),
    ISOLATED_COMMUNITY("Isolated Community", "hamlet", 0.22, 0.4, 0.04),
    RELIGIOUS_SETTLEMENT("Religious Settlement", "abbey", 0.15, 0.8, 0.03),
    FRONTIER_OUTPOST("Frontier Outpost", "outpost", 0.18, 0.8, 0.12);

    /** One line of work: {@code perWorker} of {@code out} a day for each worker on it, using up {@code inputs} per item made. */
    public record Industry(String out, double perWorker, double share, Map<String, Double> inputs) {}

    public final String display, noun;
    /** The merchant's cut: the gap between what the settlement gives for a thing and what it asks for it. */
    public final double spread;
    /** How far and how much its caravans carry, against the ordinary. */
    public final double trade;
    /** The share of its people under arms in peacetime. */
    public final double garrison;
    public final List<Industry> industries = new ArrayList<>();
    /** What its people use up each day, per head, beyond food and what their industries take. */
    public final Map<String, Double> wants = new LinkedHashMap<>();

    SettlementType(String display, String noun, double spread, double trade, double garrison) {
        this.display = display;
        this.noun = noun;
        this.spread = spread;
        this.trade = trade;
        this.garrison = garrison;
    }

    private SettlementType make(String out, double perWorker, double share, Object... inputs) {
        Map<String, Double> in = new LinkedHashMap<>();
        for (int i = 0; i < inputs.length; i += 2) in.put((String) inputs[i], ((Number) inputs[i + 1]).doubleValue());
        industries.add(new Industry(out, perWorker, share, in));
        return this;
    }

    private SettlementType want(String good, double perHead) {
        wants.put(good, perHead);
        return this;
    }

    public boolean makes(String good) {
        for (Industry i : industries) if (i.out().equals(good)) return true;
        return false;
    }

    public boolean uses(String good) {
        if (wants.containsKey(good)) return true;
        for (Industry i : industries) if (i.inputs().containsKey(good)) return true;
        return false;
    }

    public boolean coastal() {
        return this == FISHING_VILLAGE || this == COASTAL_SETTLEMENT;
    }

    /** The one thing it is known for (for descriptions and the map). */
    public String speciality() {
        return switch (this) {
            case FARMING_VILLAGE -> "grain, bread and livestock";
            case MINING_SETTLEMENT -> "coal, iron and stone";
            case TRADING_TOWN -> "a little of everything, and the best prices";
            case MILITARY_SETTLEMENT -> "swords, bows and soldiers";
            case FISHING_VILLAGE -> "fish, fresh and smoked";
            case INDUSTRIAL_TOWN -> "ingots, tools and glass";
            case COASTAL_SETTLEMENT -> "boats, sand and glass";
            case FORTIFIED_KINGDOM -> "stonework, arms and grain";
            case BANDIT_CAMP -> "whatever fell off a caravan";
            case ISOLATED_COMMUNITY -> "timber, wool and honey";
            case RELIGIOUS_SETTLEMENT -> "books, candles and honey";
            case FRONTIER_OUTPOST -> "timber, charcoal and hides";
        };
    }

    public static SettlementType parse(String s) {
        try {
            return valueOf(s);
        } catch (RuntimeException e) {
            return FARMING_VILLAGE;
        }
    }

    static {
        FARMING_VILLAGE.make("wheat", 12, .35).make("bread", 8, .15, "wheat", 3).make("carrot", 8, .10).make("potato", 8, .08)
                .make("cooked_beef", 1.2, .07).make("leather", 0.5, .03).make("white_wool", 1.2, .04).make("egg", 2, .03).make("hay_block", 1, .04, "wheat", 9)
                .want("iron_hoe", .003).want("oak_planks", .05).want("cobblestone", .04);
        MINING_SETTLEMENT.make("coal", 3, .22).make("raw_iron", 1.5, .25).make("cobblestone", 10, .14).make("raw_copper", 1.5, .06).make("redstone", 1, .03)
                .make("raw_gold", .25, .03).make("diamond", .04, .02).make("torch", 6, .04, "coal", .25, "stick", .25).make("carrot", 6, .12)
                .want("iron_pickaxe", .004).want("oak_log", .05).want("bread", .1);
        TRADING_TOWN.make("paper", 4, .08, "sugar_cane", 1).make("book", 1, .05, "paper", 3, "leather", 1).make("bread", 6, .15, "wheat", 3).make("carrot", 6, .12)
                .make("compass", .1, .03, "iron_ingot", 4, "redstone", 1).make("lead", .5, .03, "string", 2)
                .want("emerald", .002).want("oak_planks", .05).want("lantern", .01);
        MILITARY_SETTLEMENT.make("iron_sword", .6, .12, "iron_ingot", 2, "stick", 1).make("arrow", 8, .08, "flint", .25, "stick", .25, "feather", .25)
                .make("bow", .8, .05, "stick", 3, "string", 3).make("shield", .4, .05, "oak_planks", 6, "iron_ingot", 1).make("iron_helmet", .3, .04, "iron_ingot", 5)
                .make("carrot", 6, .14).make("bread", 6, .10, "wheat", 3)
                .want("leather", .02).want("cooked_beef", .05);
        FISHING_VILLAGE.make("cod", 5, .30).make("salmon", 3, .14).make("cooked_cod", 8, .12, "cod", 1, "charcoal", .125)
                .make("cooked_salmon", 8, .07, "salmon", 1, "charcoal", .125).make("dried_kelp", 4, .05).make("ink_sac", .8, .03).make("string", 1, .03)
                .make("fishing_rod", .5, .03, "stick", 3, "string", 2).make("oak_boat", .3, .04, "oak_planks", 5).make("potato", 6, .08)
                .want("bread", .1).want("oak_planks", .06);
        INDUSTRIAL_TOWN.make("iron_ingot", 3, .25, "raw_iron", 1, "coal", .125).make("iron_pickaxe", .5, .05, "iron_ingot", 3, "stick", 2)
                .make("iron_axe", .5, .04, "iron_ingot", 3, "stick", 2).make("iron_hoe", .5, .03, "iron_ingot", 2, "stick", 2).make("shears", 1, .03, "iron_ingot", 2)
                .make("bucket", .6, .03, "iron_ingot", 3).make("glass", 6, .07, "sand", 1, "coal", .125).make("brick", 6, .05, "clay_ball", 1, "coal", .125)
                .make("stone_bricks", 8, .05, "cobblestone", 1).make("copper_ingot", 3, .05, "raw_copper", 1, "coal", .125)
                .make("rail", 3, .03, "iron_ingot", .375, "stick", .0625).make("carrot", 6, .10).make("bread", 6, .08, "wheat", 3)
                .want("oak_log", .04);
        COASTAL_SETTLEMENT.make("cod", 4, .20).make("cooked_cod", 8, .10, "cod", 1, "charcoal", .125).make("sand", 10, .12).make("glass", 6, .08, "sand", 1, "charcoal", .125)
                .make("oak_boat", .5, .08, "oak_planks", 5).make("clay_ball", 5, .08).make("charcoal", 3, .05, "oak_log", 1).make("potato", 6, .10)
                .make("prismarine_shard", .5, .03).make("nautilus_shell", .03, .02)
                .want("bread", .1).want("iron_ingot", .01);
        FORTIFIED_KINGDOM.make("wheat", 10, .20).make("bread", 8, .12, "wheat", 3).make("cooked_porkchop", 1.2, .06).make("stone", 8, .08)
                .make("stone_bricks", 8, .06, "stone", 1).make("iron_ingot", 3, .06, "raw_iron", 1, "coal", .125).make("iron_sword", .6, .04, "iron_ingot", 2, "stick", 1)
                .make("oak_log", 4, .08).make("oak_planks", 16, .04, "oak_log", .25).make("stick", 16, .02, "oak_planks", .5).make("sugar_cane", 6, .03)
                .make("paper", 4, .03, "sugar_cane", 1).make("book", 1, .03, "paper", 3, "leather", 1)
                .want("emerald", .002).want("gold_ingot", .002).want("leather", .02);
        BANDIT_CAMP.make("cooked_beef", 1.2, .25).make("leather", .6, .10).make("bone", 1, .05).make("string", 1, .05)
                .want("iron_sword", .01).want("arrow", .1).want("bread", .2);
        ISOLATED_COMMUNITY.make("wheat", 8, .20).make("bread", 6, .12, "wheat", 3).make("carrot", 8, .10).make("oak_log", 4, .12).make("white_wool", 1.2, .08)
                .make("honeycomb", 1, .05).make("charcoal", 3, .05, "oak_log", 1).make("oak_planks", 16, .04, "oak_log", .25).make("stick", 16, .03, "oak_planks", .5)
                .make("cobblestone", 6, .05).make("stone_axe", .5, .03, "cobblestone", 3, "stick", 2)
                .want("iron_axe", .002).want("iron_hoe", .002);
        RELIGIOUS_SETTLEMENT.make("honeycomb", 1.5, .10).make("honey_bottle", 1, .06).make("candle", 1, .08, "honeycomb", 1, "string", 1).make("sugar_cane", 6, .08)
                .make("paper", 4, .08, "sugar_cane", 1).make("book", 1, .10, "paper", 3, "leather", 1).make("bookshelf", .3, .04, "oak_planks", 6, "book", 3)
                .make("wheat", 8, .15).make("bread", 6, .10, "wheat", 3).make("pumpkin", 4, .05)
                .want("gold_ingot", .002).want("lapis_lazuli", .003).want("white_wool", .02);
        FRONTIER_OUTPOST.make("oak_log", 5, .22).make("spruce_log", 5, .12).make("oak_planks", 16, .05, "oak_log", .25).make("charcoal", 3, .08, "oak_log", 1)
                .make("stick", 16, .03, "oak_planks", .5).make("leather", .8, .06).make("cooked_beef", 1.2, .12).make("rabbit_hide", .5, .02).make("feather", 1, .03)
                .make("flint", 2, .04).make("potato", 6, .10)
                .want("iron_axe", .004).want("bread", .15).want("iron_sword", .003);
    }
}
