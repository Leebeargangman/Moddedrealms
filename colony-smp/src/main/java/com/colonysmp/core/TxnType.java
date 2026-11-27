package com.colonysmp.core;

/** Why money moved in or out of a colony treasury. */
public enum TxnType {
    WAGES("Wages", false),
    UPKEEP("Building upkeep", false),
    MILITARY("Military upkeep", false),
    TAX("Taxes", true),
    SALE("Market sales", true),
    PURCHASE("Market purchases", false),
    TRADE_IN("Trade income", true),
    TRADE_OUT("Trade payments", false),
    SERVICES("Restaurants, inns and shops", true),
    RECRUITMENT("Recruitment", false),
    CONSTRUCTION("Construction and upgrades", false),
    TERRITORY("Land claims", false),
    DEPOSIT("Member deposits", true),
    WITHDRAWAL("Member withdrawals", false),
    RANSOM("Ransoms", true),
    REPARATIONS("Reparations", false),
    SPOILS("Spoils of war", true),
    THEFT("Theft", false),
    FINES("Fines", true),
    RESEARCH("Research", false),
    REWARDS("Bounties and rewards", true),
    TRIBUTE("Tribute to raiders", false),
    ADMIN("Admin adjustment", true),
    OTHER("Other", true);

    public final String display;
    /** Normally money coming in (for the summary columns). */
    public final boolean income;

    TxnType(String display, boolean income) {
        this.display = display;
        this.income = income;
    }

    public static TxnType parse(String s) {
        try {
            return s == null ? OTHER : valueOf(s);
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }
}
