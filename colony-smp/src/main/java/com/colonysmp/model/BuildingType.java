package com.colonysmp.model;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every kind of building, what it must contain at each level, who works there and what it costs. Players build
 * them by hand (or order a blueprint) and register them with the Blueprint Book; they only work once they pass
 * validation.
 */
public enum BuildingType {
    TOWN_HALL("Town Hall", Category.ADMINISTRATIVE, Shape.CORE, Material.LODESTONE, 5,
            "The heart of the colony: statistics, policies, the treasury and storage. Its level is the settlement level."),
    HOUSE("House", Category.RESIDENTIAL, Shape.ROOM, Material.RED_BED, 3,
            "Beds for citizens. Bigger, better-furnished houses are more comfortable."),
    INN("Inn", Category.COMMERCIAL, Shape.ROOM, Material.CAKE, 3,
            "Lodges travellers, who pay for their stay and are more likely to settle."),
    WAREHOUSE("Warehouse", Category.INFRASTRUCTURE, Shape.ROOM, Material.CHEST, 3,
            "Storage: every chest or barrel inside adds storage slots. Warehouse workers carry goods."),
    GRANARY("Granary", Category.AGRICULTURAL, Shape.ROOM, Material.HAY_BLOCK, 3,
            "Food storage: every chest or barrel inside holds food and crops. Food distributors work here."),
    FARM("Farm", Category.AGRICULTURAL, Shape.FIELD, Material.WHEAT_SEEDS, 3,
            "A field of farmland. Small, medium and large farms employ 1, 2 and 4 farmers."),
    ANIMAL_PEN("Animal Pen", Category.AGRICULTURAL, Shape.PEN, Material.OAK_FENCE, 3,
            "A fenced pasture with a gate. Animal keepers raise cows, sheep, pigs and chickens."),
    STABLES("Stables", Category.MILITARY, Shape.PEN, Material.SADDLE, 3,
            "A fenced paddock with hay. Horses inside can be taken in as war horses; cavalry trains here."),
    FISHERY("Fishery", Category.AGRICULTURAL, Shape.ROOM, Material.FISHING_ROD, 3,
            "A hut on the shore of open water (at least 20 water blocks nearby)."),
    FORESTRY("Forestry Hut", Category.INDUSTRIAL, Shape.ROOM, Material.OAK_LOG, 3,
            "Home of the foresters, who fell and replant the trees around the colony."),
    MINE("Mine", Category.INDUSTRIAL, Shape.MINE, Material.IRON_PICKAXE, 3,
            "A mine entrance in natural ground; miners dig down and branch out for ore."),
    MILL("Mill", Category.FOOD, Shape.ROOM, Material.GRINDSTONE, 2,
            "Grinds wheat into flour and cane into sugar."),
    BAKERY("Bakery", Category.FOOD, Shape.ROOM, Material.BREAD, 3,
            "Bakes bread from flour (or grain), pies, cookies and cakes."),
    BUTCHER("Butcher Shop", Category.FOOD, Shape.ROOM, Material.SMOKER, 3,
            "Slaughters surplus livestock from the pens and smokes meat."),
    KITCHEN("Kitchen", Category.FOOD, Shape.ROOM, Material.CAMPFIRE, 3,
            "Cooks fish and potatoes, soups and (level 2) hearty meals."),
    RESTAURANT("Restaurant", Category.COMMERCIAL, Shape.ROOM, Material.SUSPICIOUS_STEW, 3,
            "Citizens and visitors eat here: better meals, happier people, income."),
    BLACKSMITH("Blacksmith", Category.INDUSTRIAL, Shape.ROOM, Material.ANVIL, 3,
            "Smelts ore, forges tools, weapons and (level 2) armour, repairs gear."),
    CARPENTRY("Carpentry", Category.INDUSTRIAL, Shape.ROOM, Material.CRAFTING_TABLE, 2,
            "Saws planks and makes wooden goods, torches and charcoal."),
    MASONRY("Masonry", Category.INDUSTRIAL, Shape.ROOM, Material.STONECUTTER, 2,
            "Fires stone, glass and bricks."),
    WORKSHOP("Workshop", Category.INDUSTRIAL, Shape.ROOM, Material.FLETCHING_TABLE, 2,
            "Makes arrows, javelins, bows, shields, leather goods, paper and books."),
    MARKET("Market", Category.COMMERCIAL, Shape.ROOM, Material.BARREL, 3,
            "Citizens spend their wages here; players can buy from the colony."),
    TRADING_POST("Trading Post", Category.COMMERCIAL, Shape.ROOM, Material.CARTOGRAPHY_TABLE, 2,
            "Merchants trade with the server market and with other colonies."),
    SCHOOL("School", Category.SERVICE, Shape.ROOM, Material.LECTERN, 3,
            "Teachers educate children and students; bookshelves make lessons better."),
    CLINIC("Clinic", Category.SERVICE, Shape.ROOM, Material.BREWING_STAND, 3,
            "Doctors treat the injured and the sick here and make medicine."),
    BUILDERS_HUT("Builder's Hut", Category.INFRASTRUCTURE, Shape.ROOM, Material.BRICKS, 3,
            "Builders work from here on the construction orders."),
    BARRACKS("Barracks", Category.MILITARY, Shape.ROOM, Material.IRON_SWORD, 3,
            "Beds for soldiers. The army can't be bigger than the barracks (plus a small militia)."),
    TRAINING_GROUND("Training Ground", Category.MILITARY, Shape.PEN, Material.HAY_BLOCK, 2,
            "A fenced yard with hay-bale dummies where infantry trains."),
    ARCHERY_RANGE("Archery Range", Category.MILITARY, Shape.PEN, Material.TARGET, 2,
            "A fenced range with targets where archers, crossbowmen and slingers train."),
    ARMOURY("Armoury", Category.MILITARY, Shape.ROOM, Material.IRON_CHESTPLATE, 2,
            "Room for weapons and armour: adds equipment storage."),
    WATCHTOWER("Watchtower", Category.MILITARY, Shape.TOWER, Material.STONE_BRICKS, 1,
            "A tower at least 5 blocks tall. Guards keep watch from the top."),
    PRISON("Prison", Category.MILITARY, Shape.ROOM, Material.IRON_BARS, 2,
            "Cells with iron doors and a bed per prisoner. Wardens look after them."),
    ENGINEERING_WORKSHOP("Engineering Workshop", Category.MILITARY, Shape.ROOM, Material.SMITHING_TABLE, 2,
            "Engineers maintain the colony's buildings and pack siege charges."),
    UNIVERSITY("University", Category.SERVICE, Shape.ROOM, Material.KNOWLEDGE_BOOK, 3,
            "Researchers work through the colony's research here. Bigger universities unlock later projects."),
    LIBRARY("Library", Category.SERVICE, Shape.ROOM, Material.BOOKSHELF, 2,
            "A quiet room of books: adults read here in the evening and slowly educate themselves."),
    TAVERN("Tavern", Category.COMMERCIAL, Shape.ROOM, Material.JUKEBOX, 3,
            "Where the colony spends its evenings. A bard's music keeps everyone in good spirits."),
    DRUID_GROVE("Druid's Grove", Category.AGRICULTURAL, Shape.PEN, Material.MOSSY_COBBLESTONE, 2,
            "A fenced garden with mossy standing stones. Druids make the colony's crops grow faster."),
    ENCHANTERS_TOWER("Enchanter's Tower", Category.SERVICE, Shape.ROOM, Material.ENCHANTING_TABLE, 2,
            "An enchanter lays enchantments on the gear in the armoury, at a cost in lapis lazuli."),
    APIARY("Apiary", Category.AGRICULTURAL, Shape.PEN, Material.BEEHIVE, 3,
            "A fenced yard of beehives. Beekeepers collect honeycomb, and honey if they have bottles.");

    public enum Category {
        ADMINISTRATIVE("Administrative"), RESIDENTIAL("Residential"), AGRICULTURAL("Agricultural"), FOOD("Food"),
        INDUSTRIAL("Industrial"), COMMERCIAL("Commercial"), SERVICE("Services"), MILITARY("Military"), INFRASTRUCTURE("Infrastructure");

        public final String display;

        Category(String display) {
            this.display = display;
        }
    }

    /** How a building is recognised in the world. */
    public enum Shape {
        /** The Town Hall Core block. */
        CORE,
        /** An enclosed room (flood-filled from inside). */
        ROOM,
        /** Connected farmland. */
        FIELD,
        /** An area enclosed by fences or walls with a gate. */
        PEN,
        /** The top of a tower. */
        TOWER,
        /** A mine entrance in natural ground. */
        MINE
    }

    /** What one level needs: features (counted inside the building) and floor area. */
    public record Level(Map<Feature, Integer> features, int floor) {}

    public final String display;
    public final Category category;
    public final Shape shape;
    public final Material icon;
    public final int maxLevel;
    public final String description;
    private final List<Level> levels = new ArrayList<>();
    private final Map<Job, int[]> slots = new EnumMap<>(Job.class);
    /** Money for upgrading to level n is costBase * n; goods are scaled the same way. */
    private int costBase = 40;
    private final Map<String, Integer> costGoods = new LinkedHashMap<>();
    private int upkeep;
    /** Local stock slots per level (where workers keep inputs and outputs). */
    private int stockSlots;

    BuildingType(String display, Category category, Shape shape, Material icon, int maxLevel, String description) {
        this.display = display;
        this.category = category;
        this.shape = shape;
        this.icon = icon;
        this.maxLevel = maxLevel;
        this.description = description;
    }

    // ───────────── queries ─────────────

    /** Requirements of a level (1-based). */
    public Level level(int level) {
        if (levels.isEmpty()) return new Level(Map.of(), 0);
        return levels.get(Math.max(0, Math.min(levels.size() - 1, level - 1)));
    }

    /** Workers of a job a building of this level employs. */
    public int slots(Job job, int level) {
        int[] s = slots.get(job);
        if (s == null || level < 1) return 0;
        return s[Math.min(s.length - 1, level - 1)];
    }

    public Map<Job, Integer> jobs(int level) {
        Map<Job, Integer> out = new EnumMap<>(Job.class);
        for (Job j : slots.keySet()) {
            int n = slots(j, level);
            if (n > 0) out.put(j, n);
        }
        return out;
    }

    public boolean workplace() {
        return !slots.isEmpty();
    }

    public long upgradeMoney(int toLevel) {
        return (long) costBase * toLevel;
    }

    public Map<String, Integer> upgradeGoods(int toLevel) {
        Map<String, Integer> out = new LinkedHashMap<>();
        costGoods.forEach((k, v) -> out.put(k, v * Math.max(1, toLevel - 1)));
        return out;
    }

    public int upkeep(int level) {
        return upkeep * Math.max(1, level);
    }

    public int stockSlots(int level) {
        return stockSlots * Math.max(1, level);
    }

    /** The recipe station name for production buildings (same as the enum name). */
    public String station() {
        return name();
    }

    public static BuildingType parse(String s) {
        if (s == null) return null;
        String u = s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (u.equals("TOWER") || u.equals("GUARD_TOWER")) return WATCHTOWER;
        if (u.equals("PEN") || u.equals("PASTURE")) return ANIMAL_PEN;
        if (u.equals("SIEGE_WORKSHOP")) return ENGINEERING_WORKSHOP;
        if (u.equals("GROVE") || u.equals("DRUIDS_GROVE")) return DRUID_GROVE;
        if (u.equals("ENCHANTER") || u.equals("ENCHANTERS") || u.equals("ENCHANTER_TOWER")) return ENCHANTERS_TOWER;
        if (u.equals("PUB") || u.equals("INN_TAVERN")) return TAVERN;
        if (u.equals("BEES") || u.equals("BEE_YARD")) return APIARY;
        try {
            return valueOf(u);
        } catch (IllegalArgumentException e) {
            for (BuildingType t : values()) if (t.display.equalsIgnoreCase(s.trim())) return t;
            return null;
        }
    }

    // ───────────── the table ─────────────

    private BuildingType lv(int floor, Object... featureCounts) {
        Map<Feature, Integer> f = new LinkedHashMap<>();
        for (int i = 0; i + 1 < featureCounts.length; i += 2) f.put((Feature) featureCounts[i], (Integer) featureCounts[i + 1]);
        levels.add(new Level(f, floor));
        return this;
    }

    private BuildingType job(Job j, int... perLevel) {
        slots.put(j, perLevel);
        return this;
    }

    private BuildingType cost(int money, Object... goods) {
        costBase = money;
        for (int i = 0; i + 1 < goods.length; i += 2) costGoods.put((String) goods[i], (Integer) goods[i + 1]);
        return this;
    }

    private BuildingType upkeepPerLevel(int perLevel) {
        upkeep = perLevel;
        return this;
    }

    private BuildingType stock(int perLevel) {
        stockSlots = perLevel;
        return this;
    }

    static {
        // the colony's first builder works from the Town Hall until there's a builder's hut
        TOWN_HALL.job(Job.ADMINISTRATOR, 1, 1, 2, 2, 3).job(Job.TREASURER, 0, 1, 1, 1, 2).job(Job.BUILDER, 1, 1, 1, 1, 1)
                .cost(250, "stone_bricks", 64, "#planks", 64, "iron_ingot", 8, "glass", 16).stock(0);
        HOUSE.lv(9, Feature.BED, 1, Feature.DOOR, 1).lv(20, Feature.BED, 1, Feature.DOOR, 1, Feature.CONTAINER, 1)
                .lv(30, Feature.BED, 1, Feature.DOOR, 1, Feature.CONTAINER, 1, Feature.BOOKSHELF, 1).cost(30, "#planks", 24);
        INN.lv(16, Feature.BED, 2, Feature.DOOR, 1, Feature.BARREL, 1).lv(30, Feature.BED, 4, Feature.DOOR, 1, Feature.BARREL, 1)
                .lv(45, Feature.BED, 6, Feature.DOOR, 1, Feature.BARREL, 2).job(Job.INNKEEPER, 1, 1, 2).cost(60, "#planks", 32).upkeepPerLevel(1).stock(4);
        WAREHOUSE.lv(9, Feature.DOOR, 1, Feature.CONTAINER, 2).lv(16, Feature.DOOR, 1, Feature.CONTAINER, 4).lv(25, Feature.DOOR, 1, Feature.CONTAINER, 6)
                .job(Job.WAREHOUSE_WORKER, 1, 2, 3).cost(60, "#planks", 32, "cobblestone", 32).upkeepPerLevel(1);
        GRANARY.lv(9, Feature.DOOR, 1, Feature.CONTAINER, 2).lv(16, Feature.DOOR, 1, Feature.CONTAINER, 4).lv(25, Feature.DOOR, 1, Feature.CONTAINER, 6)
                .job(Job.FOOD_DISTRIBUTOR, 1, 1, 2).cost(50, "#planks", 32).upkeepPerLevel(1);
        FARM.lv(9).lv(9, Feature.COMPOSTER, 1).lv(9, Feature.COMPOSTER, 2).job(Job.FARMER, 1, 2, 4).cost(30, "bone_meal", 16).stock(6);
        ANIMAL_PEN.lv(9).lv(25, Feature.HAY, 1).lv(40, Feature.HAY, 2).job(Job.ANIMAL_KEEPER, 1, 1, 2).cost(40, "wheat", 32).stock(6);
        STABLES.lv(16, Feature.HAY, 1).lv(30, Feature.HAY, 2).lv(45, Feature.HAY, 3).job(Job.ANIMAL_KEEPER, 0, 1, 1).cost(80, "wheat", 64).upkeepPerLevel(1).stock(4);
        FISHERY.lv(4, Feature.DOOR, 1, Feature.BARREL, 1).lv(9, Feature.DOOR, 1, Feature.BARREL, 1).lv(16, Feature.DOOR, 1, Feature.BARREL, 2)
                .job(Job.FISHER, 1, 2, 3).cost(30, "string", 8).stock(6);
        FORESTRY.lv(4, Feature.DOOR, 1, Feature.CONTAINER, 1).lv(9, Feature.DOOR, 1, Feature.CONTAINER, 1).lv(16, Feature.DOOR, 1, Feature.CONTAINER, 2)
                .job(Job.FORESTER, 1, 2, 3).cost(30, "#planks", 16).stock(9);
        MINE.lv(0).lv(0).lv(0).job(Job.MINER, 1, 2, 3).cost(50, "torch", 32, "#planks", 16).upkeepPerLevel(1).stock(9);
        MILL.lv(9, Feature.DOOR, 1, Feature.GRINDSTONE, 1).lv(16, Feature.DOOR, 1, Feature.GRINDSTONE, 2).job(Job.MILLER, 1, 2).cost(40, "#planks", 32).upkeepPerLevel(1).stock(6);
        BAKERY.lv(9, Feature.DOOR, 1, Feature.COOKER, 1).lv(16, Feature.DOOR, 1, Feature.COOKER, 2, Feature.CONTAINER, 1).lv(25, Feature.DOOR, 1, Feature.COOKER, 3, Feature.CONTAINER, 1)
                .job(Job.BAKER, 1, 2, 2).cost(40, "#planks", 32, "cobblestone", 16).upkeepPerLevel(1).stock(6);
        BUTCHER.lv(9, Feature.DOOR, 1, Feature.SMOKER, 1).lv(16, Feature.DOOR, 1, Feature.SMOKER, 2).lv(25, Feature.DOOR, 1, Feature.SMOKER, 3)
                .job(Job.BUTCHER, 1, 2, 2).cost(40, "#planks", 32).upkeepPerLevel(1).stock(6);
        KITCHEN.lv(9, Feature.DOOR, 1, Feature.COOKER, 1).lv(16, Feature.DOOR, 1, Feature.COOKER, 2, Feature.CRAFTING_TABLE, 1)
                .lv(25, Feature.DOOR, 1, Feature.COOKER, 3, Feature.CRAFTING_TABLE, 1).job(Job.COOK, 1, 2, 3).cost(40, "#planks", 32).upkeepPerLevel(1).stock(6);
        RESTAURANT.lv(12, Feature.DOOR, 1, Feature.SEAT, 2, Feature.BARREL, 1).lv(20, Feature.DOOR, 1, Feature.SEAT, 4, Feature.BARREL, 1)
                .lv(35, Feature.DOOR, 1, Feature.SEAT, 8, Feature.BARREL, 2).job(Job.RESTAURANT_WORKER, 1, 2, 3).cost(60, "#planks", 32, "glass", 8).upkeepPerLevel(2).stock(6);
        BLACKSMITH.lv(9, Feature.DOOR, 1, Feature.ANVIL, 1, Feature.FURNACE, 1).lv(16, Feature.DOOR, 1, Feature.ANVIL, 1, Feature.FURNACE, 1, Feature.SMITHING_TABLE, 1)
                .lv(25, Feature.DOOR, 1, Feature.ANVIL, 1, Feature.FURNACE, 2, Feature.SMITHING_TABLE, 1).job(Job.BLACKSMITH, 1, 1, 2).cost(60, "cobblestone", 32, "iron_ingot", 4).upkeepPerLevel(2).stock(6);
        CARPENTRY.lv(9, Feature.DOOR, 1, Feature.CRAFTING_TABLE, 1).lv(16, Feature.DOOR, 1, Feature.CRAFTING_TABLE, 2).job(Job.CARPENTER, 1, 2).cost(30, "#planks", 32).upkeepPerLevel(1).stock(6);
        MASONRY.lv(9, Feature.DOOR, 1, Feature.STONECUTTER, 1, Feature.FURNACE, 1).lv(16, Feature.DOOR, 1, Feature.STONECUTTER, 1, Feature.FURNACE, 2).job(Job.MASON, 1, 2)
                .cost(40, "cobblestone", 48).upkeepPerLevel(1).stock(6);
        WORKSHOP.lv(9, Feature.DOOR, 1, Feature.FLETCHING_TABLE, 1).lv(16, Feature.DOOR, 1, Feature.FLETCHING_TABLE, 1, Feature.LOOM, 1).job(Job.CRAFTSWORKER, 1, 2)
                .cost(40, "#planks", 32).upkeepPerLevel(1).stock(6);
        MARKET.lv(12, Feature.DOOR, 1, Feature.BARREL, 2).lv(20, Feature.DOOR, 1, Feature.BARREL, 4).lv(30, Feature.DOOR, 1, Feature.BARREL, 6)
                .job(Job.SHOPKEEPER, 1, 2, 3).cost(60, "#planks", 32).upkeepPerLevel(2).stock(9);
        TRADING_POST.lv(9, Feature.DOOR, 1, Feature.CARTOGRAPHY_TABLE, 1, Feature.CONTAINER, 1).lv(16, Feature.DOOR, 1, Feature.CARTOGRAPHY_TABLE, 1, Feature.CONTAINER, 2)
                .job(Job.MERCHANT, 1, 2).cost(80, "#planks", 32, "emerald", 2).upkeepPerLevel(2);
        SCHOOL.lv(9, Feature.DOOR, 1, Feature.LECTERN, 1).lv(16, Feature.DOOR, 1, Feature.LECTERN, 1, Feature.BOOKSHELF, 4).lv(30, Feature.DOOR, 1, Feature.LECTERN, 2, Feature.BOOKSHELF, 10)
                .job(Job.TEACHER, 1, 2, 2).cost(60, "book", 4).upkeepPerLevel(2).stock(2);
        CLINIC.lv(9, Feature.DOOR, 1, Feature.BED, 1, Feature.BREWING_STAND, 1).lv(20, Feature.DOOR, 1, Feature.BED, 3, Feature.BREWING_STAND, 1)
                .lv(35, Feature.DOOR, 1, Feature.BED, 6, Feature.BREWING_STAND, 2).job(Job.DOCTOR, 1, 2, 3).cost(70, "glass_bottle", 8).upkeepPerLevel(2).stock(4);
        BUILDERS_HUT.lv(9, Feature.DOOR, 1, Feature.CRAFTING_TABLE, 1).lv(16, Feature.DOOR, 1, Feature.CRAFTING_TABLE, 1, Feature.CONTAINER, 1)
                .lv(25, Feature.DOOR, 1, Feature.CRAFTING_TABLE, 1, Feature.CONTAINER, 2).job(Job.BUILDER, 1, 2, 3).cost(30, "#planks", 32).upkeepPerLevel(1);
        BARRACKS.lv(12, Feature.DOOR, 1, Feature.BED, 2).lv(25, Feature.DOOR, 1, Feature.BED, 6).lv(45, Feature.DOOR, 1, Feature.BED, 12)
                .cost(80, "cobblestone", 64, "#planks", 32).upkeepPerLevel(2);
        TRAINING_GROUND.lv(25, Feature.HAY, 2).lv(49, Feature.HAY, 4).cost(50, "#planks", 32).upkeepPerLevel(1);
        ARCHERY_RANGE.lv(25, Feature.TARGET, 3).lv(49, Feature.TARGET, 6).cost(50, "#planks", 32).upkeepPerLevel(1);
        ARMOURY.lv(9, Feature.DOOR, 1, Feature.ARMOR_HOLDER, 2).lv(16, Feature.DOOR, 1, Feature.ARMOR_HOLDER, 4).cost(60, "iron_ingot", 4).upkeepPerLevel(1);
        WATCHTOWER.lv(0).job(Job.GUARD, 2);
        PRISON.lv(4, Feature.IRON_DOOR, 1, Feature.BED, 1).lv(9, Feature.IRON_DOOR, 1, Feature.BED, 2).job(Job.WARDEN, 1, 1).cost(50, "iron_bars", 8).upkeepPerLevel(1);
        ENGINEERING_WORKSHOP.lv(9, Feature.DOOR, 1, Feature.SMITHING_TABLE, 1, Feature.CRAFTING_TABLE, 1).lv(16, Feature.DOOR, 1, Feature.SMITHING_TABLE, 1, Feature.CRAFTING_TABLE, 2)
                .job(Job.ENGINEER, 1, 2).cost(80, "iron_ingot", 8).upkeepPerLevel(2).stock(6);
        UNIVERSITY.lv(16, Feature.DOOR, 1, Feature.LECTERN, 1, Feature.BOOKSHELF, 8).lv(30, Feature.DOOR, 1, Feature.LECTERN, 2, Feature.BOOKSHELF, 16)
                .lv(45, Feature.DOOR, 1, Feature.LECTERN, 3, Feature.BOOKSHELF, 30).job(Job.RESEARCHER, 1, 2, 3).cost(120, "book", 8, "paper", 16).upkeepPerLevel(3);
        LIBRARY.lv(12, Feature.DOOR, 1, Feature.BOOKSHELF, 6, Feature.SEAT, 2).lv(25, Feature.DOOR, 1, Feature.BOOKSHELF, 14, Feature.SEAT, 4)
                .cost(60, "book", 6).upkeepPerLevel(1);
        TAVERN.lv(16, Feature.DOOR, 1, Feature.SEAT, 4, Feature.BARREL, 1, Feature.MUSIC, 1).lv(30, Feature.DOOR, 1, Feature.SEAT, 8, Feature.BARREL, 2, Feature.MUSIC, 1)
                .lv(45, Feature.DOOR, 1, Feature.SEAT, 12, Feature.BARREL, 3, Feature.MUSIC, 2).job(Job.BARD, 1, 1, 2).cost(70, "#planks", 32, "glass", 8).upkeepPerLevel(2);
        DRUID_GROVE.lv(9, Feature.STANDING_STONE, 2).lv(25, Feature.STANDING_STONE, 4).job(Job.DRUID, 1, 2).cost(60, "bone_meal", 16).upkeepPerLevel(1);
        ENCHANTERS_TOWER.lv(9, Feature.DOOR, 1, Feature.ENCHANTING_TABLE, 1, Feature.BOOKSHELF, 4).lv(16, Feature.DOOR, 1, Feature.ENCHANTING_TABLE, 1, Feature.BOOKSHELF, 15)
                .job(Job.ENCHANTER, 1, 1).cost(150, "lapis_lazuli", 16, "book", 8).upkeepPerLevel(3).stock(2);
        APIARY.lv(9, Feature.BEEHIVE, 2).lv(16, Feature.BEEHIVE, 4).lv(25, Feature.BEEHIVE, 6).job(Job.BEEKEEPER, 1, 1, 2).cost(40, "#planks", 16, "glass_bottle", 8).stock(6);
        for (BuildingType t : values()) {
            if (t.levels.isEmpty()) t.lv(0);
            if (t.stockSlots == 0 && t.workplace() && t != TOWN_HALL && t != WATCHTOWER && t != BUILDERS_HUT && t != TRADING_POST && t != WAREHOUSE && t != GRANARY) t.stock(4);
        }
    }
}
