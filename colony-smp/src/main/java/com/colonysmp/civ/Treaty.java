package com.colonysmp.civ;

/** An agreement two civilisations hold between them. */
public enum Treaty {
    TRADE_AGREEMENT("trade agreement", "their caravans are welcome and carry more"),
    NON_AGGRESSION("non-aggression pact", "neither will attack the other"),
    MILITARY_PACT("military pact", "each sends soldiers when the other is attacked"),
    ALLIANCE("alliance", "they fight each other's wars"),
    EMBARGO("embargo", "no caravans pass between them"),
    TRIBUTE("tribute", "the weaker sends goods to the stronger every week"),
    PEACE("peace treaty", "a war between them ended; neither may attack until it lapses");

    public final String display, meaning;

    Treaty(String display, String meaning) {
        this.display = display;
        this.meaning = meaning;
    }
}
