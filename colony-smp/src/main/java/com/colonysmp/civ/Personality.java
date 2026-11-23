package com.colonysmp.civ;

/** What a leader is like. It bends every decision their civilisation makes. */
public enum Personality {
    //            war    peace  trade  grow   hoard
    AGGRESSIVE(   1.8,   0.6,   0.8,   1.1,   1.0, "quick to anger and quicker to arms"),
    PEACEFUL(     0.3,   1.6,   1.1,   0.9,   1.0, "slow to anger and loath to fight"),
    GREEDY(       1.0,   0.9,   1.3,   1.0,   1.5, "counts every sack and shares none"),
    DIPLOMATIC(   0.6,   1.5,   1.2,   1.0,   0.9, "sends envoys before soldiers"),
    EXPANSIONIST( 1.2,   0.9,   1.0,   1.7,   0.9, "sees every empty valley as theirs"),
    DEFENSIVE(    0.5,   1.1,   0.9,   0.7,   1.2, "builds walls and waits behind them"),
    OPPORTUNISTIC(1.3,   1.0,   1.2,   1.2,   1.1, "strikes when a neighbour stumbles"),
    AMBITIOUS(    1.4,   0.8,   1.1,   1.4,   1.0, "wants a bigger name than their forebears");

    /** How readily they go to war, make peace, trade, found outposts, and hold back stores. */
    public final double war, peace, trade, growth, hoard;
    public final String blurb;

    Personality(double war, double peace, double trade, double growth, double hoard, String blurb) {
        this.war = war;
        this.peace = peace;
        this.trade = trade;
        this.growth = growth;
        this.hoard = hoard;
        this.blurb = blurb;
    }

    public static Personality parse(String s) {
        try {
            return valueOf(s);
        } catch (RuntimeException e) {
            return DIPLOMATIC;
        }
    }
}
