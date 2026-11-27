package com.colonysmp.model;

import com.colonysmp.core.Skill;
import com.colonysmp.util.Tools;
import org.bukkit.Material;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A profession. Every employed citizen has one, tied to a workplace building (except guards and the unemployed). */
public enum Job {
    // agriculture
    FARMER("Farmer", Material.WHEAT, Skill.FARMING, Background.FARM, 2, 0, Tools.Kind.HOE, false,
            "Plants, harvests and replants a farm; brings the crops to storage."),
    ANIMAL_KEEPER("Animal Keeper", Material.SHEARS, Skill.HUSBANDRY, Background.PEN, 2, 0, Tools.Kind.SHEARS, false,
            "Feeds and breeds the animals of a pen; collects eggs, wool and milk."),
    MILLER("Miller", Material.GRINDSTONE, Skill.COOKING, Background.RECIPE, 2, 0, null, false,
            "Grinds wheat into flour and cane into sugar at a mill."),
    FORESTER("Woodcutter", Material.IRON_AXE, Skill.FORESTRY, Background.NONE, 2, 0, Tools.Kind.AXE, true,
            "Fells mature trees around the colony and replants saplings."),
    FISHER("Fisher", Material.FISHING_ROD, Skill.FISHING, Background.FISHING, 2, 0, Tools.Kind.FISHING_ROD, false,
            "Fishes from a fishery on the shore."),
    // food
    BUTCHER("Butcher", Material.SMOKER, Skill.COOKING, Background.RECIPE, 3, 0, null, false,
            "Slaughters surplus livestock and smokes the meat."),
    COOK("Cook", Material.MUSHROOM_STEW, Skill.COOKING, Background.RECIPE, 3, 0, null, false,
            "Cooks fish and potatoes, soups and hearty meals in a kitchen."),
    BAKER("Baker", Material.BREAD, Skill.COOKING, Background.RECIPE, 3, 0, null, false,
            "Bakes bread, pies, cookies and cakes."),
    RESTAURANT_WORKER("Restaurant Worker", Material.BOWL, Skill.COOKING, Background.SERVICE, 3, 0, null, false,
            "Serves meals to citizens and visitors at a restaurant."),
    FOOD_DISTRIBUTOR("Food Distributor", Material.HAY_BLOCK, Skill.LOGISTICS, Background.HAUL, 2, 0, null, true,
            "Carries food from farms and kitchens to storage and to the places that need it."),
    // industry
    MINER("Miner", Material.IRON_PICKAXE, Skill.MINING, Background.NONE, 3, 0, Tools.Kind.PICKAXE, true,
            "Digs a mine for stone, coal and ore."),
    BLACKSMITH("Blacksmith", Material.ANVIL, Skill.SMITHING, Background.RECIPE, 4, 25, null, true,
            "Smelts ore; forges tools, weapons and armour; repairs worn gear."),
    CARPENTER("Carpenter", Material.CRAFTING_TABLE, Skill.CRAFTING, Background.RECIPE, 3, 0, null, false,
            "Saws logs into planks and makes doors, fences, chests, ladders and torches."),
    MASON("Mason", Material.STONECUTTER, Skill.CONSTRUCTION, Background.RECIPE, 3, 0, null, true,
            "Fires stone and glass, cuts bricks and wall blocks."),
    CRAFTSWORKER("Craftsworker", Material.FLETCHING_TABLE, Skill.CRAFTING, Background.RECIPE, 3, 0, null, false,
            "Makes arrows, javelins, bows, shields, leather goods, paper and books."),
    ENGINEER("Engineer", Material.SMITHING_TABLE, Skill.ENGINEERING, Background.SERVICE, 4, 30, null, false,
            "Maintains buildings (cheaper upkeep, repairs damage) and packs siege charges."),
    BUILDER("Builder", Material.BRICKS, Skill.CONSTRUCTION, Background.NONE, 3, 0, null, true,
            "Builds what you order with the Blueprint Book, carrying materials from storage."),
    // economy and administration
    MERCHANT("Merchant", Material.EMERALD, Skill.TRADING, Background.SERVICE, 4, 25, null, false,
            "Trades with the market and other colonies; sells surplus and buys shortages."),
    SHOPKEEPER("Shopkeeper", Material.BARREL, Skill.TRADING, Background.SERVICE, 3, 0, null, false,
            "Runs a market where citizens and players buy goods."),
    WAREHOUSE_WORKER("Warehouse Worker", Material.CHEST, Skill.LOGISTICS, Background.HAUL, 2, 0, null, true,
            "Carries goods between storage and the workplaces that make or need them."),
    ADMINISTRATOR("Administrator", Material.WRITABLE_BOOK, Skill.ADMINISTRATION, Background.SERVICE, 5, 35, null, false,
            "Runs the Town Hall: assigns idle citizens, keeps records, checks buildings."),
    TREASURER("Treasurer", Material.GOLD_INGOT, Skill.TRADING, Background.SERVICE, 5, 40, null, false,
            "Keeps the books: better tax collection and lower upkeep."),
    // services
    TEACHER("Teacher", Material.BOOK, Skill.TEACHING, Background.TEACH, 4, 30, null, false,
            "Teaches children and students at a school."),
    DOCTOR("Doctor", Material.GLISTERING_MELON_SLICE, Skill.MEDICINE, Background.HEAL, 5, 40, null, false,
            "Treats the injured and the sick at a clinic and makes medicine."),
    INNKEEPER("Innkeeper", Material.CAKE, Skill.TRADING, Background.SERVICE, 3, 0, null, false,
            "Hosts travellers at an inn (income, and more travellers stop by)."),
    WARDEN("Warden", Material.IRON_BARS, Skill.ADMINISTRATION, Background.SERVICE, 3, 0, null, false,
            "Feeds and talks to prisoners; holds criminals."),
    RESEARCHER("Researcher", Material.KNOWLEDGE_BOOK, Skill.SCHOLARSHIP, Background.RESEARCH, 5, 50, null, false,
            "Works on the colony's research at a University."),
    BARD("Bard", Material.NOTE_BLOCK, Skill.PERFORMANCE, Background.ENTERTAIN, 3, 0, null, false,
            "Plays at a tavern: music lifts everyone's spirits and brings people together."),
    DRUID("Druid", Material.MOSS_BLOCK, Skill.FARMING, Background.GROW, 4, 20, null, false,
            "Tends a grove and walks the fields, coaxing the crops to grow faster."),
    ENCHANTER("Enchanter", Material.ENCHANTING_TABLE, Skill.SCHOLARSHIP, Background.ENCHANT, 5, 45, null, false,
            "Enchants the tools, weapons and armour in the armoury, using lapis lazuli."),
    BEEKEEPER("Beekeeper", Material.HONEYCOMB, Skill.HUSBANDRY, Background.BEES, 2, 0, null, false,
            "Keeps the hives of an apiary; collects honeycomb and bottles honey."),
    // defence
    GUARD("Guard", Material.IRON_SWORD, Skill.MELEE, Background.GUARD, 3, 0, null, true,
            "Watches the towers and walls, fights intruders and monsters, keeps order."),
    SOLDIER("Soldier", Material.SHIELD, Skill.MELEE, Background.TRAIN, 4, 0, null, true,
            "A trained soldier of a military class, in a squad that follows orders."),
    // not working
    STUDENT("Student", Material.PAPER, Skill.TEACHING, Background.STUDY, 0, 0, null, false,
            "Studies at a school all day."),
    UNEMPLOYED("Unemployed", Material.BARRIER, null, Background.NONE, 0, 0, null, false,
            "Has no job. Assign one, or let an Administrator do it.");

    /** What the job does when the citizen is simulated in the background (no body). */
    public enum Background {
        /** Needs the world: waits while the colony is unloaded. */
        NONE,
        FARM, PEN, FISHING, RECIPE, SERVICE, HAUL, TEACH, STUDY, HEAL, GUARD, TRAIN,
        RESEARCH, ENTERTAIN, GROW, ENCHANT, BEES
    }

    public final String display;
    public final Material icon;
    public final Skill skill;
    public final Background background;
    /** Daily wage at the normal wage policy. */
    public final int wage;
    /** Education (0-100) needed. */
    public final int education;
    public final Tools.Kind tool;
    /** Heavy work tires and starves faster. */
    public final boolean heavy;
    public final String description;

    Job(String display, Material icon, Skill skill, Background background, int wage, int education, Tools.Kind tool, boolean heavy, String description) {
        this.display = display;
        this.icon = icon;
        this.skill = skill;
        this.background = background;
        this.wage = wage;
        this.education = education;
        this.tool = tool;
        this.heavy = heavy;
        this.description = description;
    }

    /** Buildings this job works in (empty for guards, soldiers and the unemployed). */
    public List<BuildingType> workplaces() {
        List<BuildingType> out = new ArrayList<>();
        for (BuildingType t : BuildingType.values()) if (t.slots(this, t.maxLevel) > 0) out.add(t);
        return out;
    }

    public boolean needsWorkplace() {
        return this != GUARD && this != SOLDIER && this != UNEMPLOYED && this != STUDENT;
    }

    public boolean military() {
        return this == GUARD || this == SOLDIER;
    }

    public boolean employed() {
        return this != UNEMPLOYED && this != STUDENT;
    }

    /** The work can't be done at all without the tool (a keeper only needs shears for shearing). */
    public boolean toolEssential() {
        return this == FARMER || this == FORESTER || this == MINER || this == FISHER;
    }

    public Villager.Profession profession() {
        return switch (this) {
            case FARMER, MILLER, BAKER, BEEKEEPER -> Villager.Profession.FARMER;
            case ANIMAL_KEEPER, BARD -> Villager.Profession.SHEPHERD;
            case FORESTER, CRAFTSWORKER -> Villager.Profession.FLETCHER;
            case FISHER -> Villager.Profession.FISHERMAN;
            case BUTCHER, COOK, INNKEEPER -> Villager.Profession.BUTCHER;
            case RESTAURANT_WORKER, FOOD_DISTRIBUTOR, CARPENTER -> Villager.Profession.LEATHERWORKER;
            case MINER, ENGINEER -> Villager.Profession.TOOLSMITH;
            case BLACKSMITH -> Villager.Profession.ARMORER;
            case MASON, BUILDER -> Villager.Profession.MASON;
            case MERCHANT, SHOPKEEPER, WARDEN -> Villager.Profession.CARTOGRAPHER;
            case ADMINISTRATOR, TREASURER, TEACHER, RESEARCHER -> Villager.Profession.LIBRARIAN;
            case DOCTOR, ENCHANTER -> Villager.Profession.CLERIC;
            case DRUID -> Villager.Profession.NITWIT;
            case GUARD, SOLDIER -> Villager.Profession.WEAPONSMITH;
            case WAREHOUSE_WORKER, STUDENT, UNEMPLOYED -> Villager.Profession.NONE;
        };
    }

    public static Job parse(String s) {
        if (s == null) return null;
        String u = s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        switch (u) {
            case "LUMBERJACK", "WOODCUTTER" -> u = "FORESTER";
            case "HERDER", "KEEPER" -> u = "ANIMAL_KEEPER";
            case "SMITH" -> u = "BLACKSMITH";
            case "NONE", "IDLE" -> u = "UNEMPLOYED";
            case "COURIER", "HAULER" -> u = "WAREHOUSE_WORKER";
            case "HEALER", "MEDIC", "CLERIC" -> u = "DOCTOR";
            case "SCHOLAR" -> u = "RESEARCHER";
            case "MUSICIAN", "MINSTREL" -> u = "BARD";
            case "APIARIST" -> u = "BEEKEEPER";
            default -> {
            }
        }
        try {
            return valueOf(u);
        } catch (IllegalArgumentException e) {
            for (Job j : values()) if (j.display.equalsIgnoreCase(s.trim())) return j;
            return null;
        }
    }
}
