package com.colonysmp.civ;

/**
 * How a civilisation is ruled, and what that does to it. Each number is a multiplier on the plain case:
 * levy (how much of what its people make goes into the common stores), recruitment, how readily it trades and
 * makes treaties, how eager it is to grow, how steady it is, and how its people feel about it.
 */
public enum Government {
    MONARCHY("Monarchy", "Sovereign", 1.0, 1.0, 1.0, 1.0, 1.0, 1.1, 0),
    REPUBLIC("Republic", "Consul", 0.9, 0.8, 1.15, 1.2, 0.9, 1.0, 6),
    MILITARY_DICTATORSHIP("Military Dictatorship", "Marshal", 1.15, 1.6, 0.8, 0.7, 1.25, 0.85, -8),
    TRIBAL("Tribal Council", "Chieftain", 0.75, 1.2, 0.85, 0.9, 0.9, 0.95, 3),
    MERCHANT_COUNCIL("Merchant Council", "Guildmaster", 1.0, 0.7, 1.4, 1.25, 1.0, 1.0, 2),
    OLIGARCHY("Oligarchy", "Archon", 1.2, 0.9, 1.1, 0.95, 0.9, 0.9, -5),
    THEOCRACY("Theocracy", "High Priest", 1.0, 0.9, 0.85, 0.85, 0.85, 1.2, 2);

    public final String display, title;
    public final double levy, recruitment, trade, diplomacy, expansion, stability;
    /** Added to its people's happiness. */
    public final int happiness;

    Government(String display, String title, double levy, double recruitment, double trade, double diplomacy, double expansion, double stability, int happiness) {
        this.display = display;
        this.title = title;
        this.levy = levy;
        this.recruitment = recruitment;
        this.trade = trade;
        this.diplomacy = diplomacy;
        this.expansion = expansion;
        this.stability = stability;
        this.happiness = happiness;
    }

    /** Is the next ruler the last one's heir (a dynasty), rather than chosen? */
    public boolean hereditary() {
        return this == MONARCHY || this == TRIBAL || this == OLIGARCHY;
    }

    public static Government parse(String s) {
        try {
            return valueOf(s);
        } catch (RuntimeException e) {
            return MONARCHY;
        }
    }
}
