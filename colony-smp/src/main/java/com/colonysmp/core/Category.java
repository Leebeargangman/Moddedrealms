package com.colonysmp.core;

/** What kind of good something is: decides storage (granaries hold food), prices and menus. */
public enum Category {
    FOOD("Food", true),
    CROP("Crops", true),
    MEAT("Meat", true),
    FISH("Fish", true),
    SEED("Seeds", false),
    ANIMAL("Animal products", false),
    WOOD("Wood", false),
    STONE("Stone", false),
    ORE("Ores", false),
    METAL("Metals", false),
    MATERIAL("Materials", false),
    TOOL("Tools", false),
    WEAPON("Weapons", false),
    ARMOR("Armour", false),
    AMMO("Ammunition", false),
    MEDICAL("Medicine", false),
    LUXURY("Luxuries", false),
    MISC("Other", false);

    public final String display;
    /** Granaries store these. */
    public final boolean granary;

    Category(String display, boolean granary) {
        this.display = display;
        this.granary = granary;
    }
}
