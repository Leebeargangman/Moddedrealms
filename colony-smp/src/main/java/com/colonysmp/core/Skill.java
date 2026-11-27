package com.colonysmp.core;

/** What citizens get better at by doing it. */
public enum Skill {
    FARMING("Farming"),
    HUSBANDRY("Animal husbandry"),
    FORESTRY("Forestry"),
    FISHING("Fishing"),
    COOKING("Cooking"),
    CRAFTING("Crafting"),
    SMITHING("Smithing"),
    MINING("Mining"),
    CONSTRUCTION("Construction"),
    TRADING("Trading"),
    LOGISTICS("Logistics"),
    MEDICINE("Medicine"),
    TEACHING("Teaching"),
    ADMINISTRATION("Administration"),
    MELEE("Melee combat"),
    ARCHERY("Archery"),
    HORSEMANSHIP("Horsemanship"),
    LEADERSHIP("Leadership"),
    ENGINEERING("Engineering"),
    SCHOLARSHIP("Scholarship"),
    PERFORMANCE("Performance");

    public final String display;

    Skill(String display) {
        this.display = display;
    }

    public static final int MAX_LEVEL = 10;

    /** XP needed to reach a level (50 · level²: level 1 at 50, level 10 at 5000). */
    public static double xpFor(int level) {
        return 50.0 * level * level;
    }

    public static int level(double xp) {
        int l = (int) Math.floor(Math.sqrt(Math.max(0, xp) / 50.0));
        return Math.min(MAX_LEVEL, l);
    }

    /** Work speed from skill: +6% per level (x1.6 at level 10). */
    public static double speed(int level) {
        return 1 + 0.06 * Math.max(0, Math.min(MAX_LEVEL, level));
    }

    public static Skill parse(String s) {
        try {
            return s == null ? null : valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
