package com.colonysmp.civ;

/**
 * The ten fields a civilisation's learning advances in, each from level 0 to {@link #MAX}. A level is earned
 * with scholars' time and with paper and books from the common stores, and makes its people better at one
 * thing; it never hands anybody an item.
 */
public enum Tech {
    AGRICULTURE("Agriculture", "fields and herds yield more"),
    MINING("Mining", "mines and quarries yield more"),
    CONSTRUCTION("Construction", "settlements hold more people"),
    METALWORKING("Metalworking", "smelters and forges turn out more"),
    MILITARY("Military Equipment", "soldiers fight better"),
    TRADE("Trade", "caravans carry more and merchants take a smaller cut"),
    MEDICINE("Medicine", "plagues kill fewer and families grow"),
    TRANSPORT("Transport", "caravans travel faster"),
    INFRASTRUCTURE("Infrastructure", "stores keep more and less goes to waste"),
    DEFENCE("Defence", "walls and watch make raids and sieges harder");

    public static final int MAX = 5;
    public final String display, effect;

    Tech(String display, String effect) {
        this.display = display;
        this.effect = effect;
    }

    /** Scholar-days a level costs (each level dearer than the last). */
    public static double cost(int nextLevel) {
        return 60 * Math.pow(nextLevel, 1.6);
    }

    /** Books the scholars use up reaching a level. */
    public static int books(int nextLevel) {
        return 2 + 2 * nextLevel;
    }
}
