package com.colonysmp.model;

import java.util.Locale;

/**
 * Illnesses. Each has causes (crowding, hunger, bad food), a chance to spread to people who share a home or
 * workplace, and a course that treatment shortens. Outbreaks are a problem to manage, not a death sentence:
 * only untreated severe cases kill, and quarantine slows the spread.
 */
public enum Disease {
    COLD("Common Cold", 0.15, 1.5, 0.85, 0.0),
    FLU("Flu", 0.25, 3, 0.65, 0.02),
    DYSENTERY("Dysentery", 0.12, 3, 0.5, 0.04),
    FEVER("Swamp Fever", 0.08, 5, 0.45, 0.06);

    public final String display;
    /** Daily chance to infect each person they share a home or workplace with (before quarantine). */
    public final double contagion;
    /** Days it lasts untreated. */
    public final double days;
    /** Work speed while ill. */
    public final double work;
    /** Daily chance an untreated case turns fatal once health is low. */
    public final double lethality;

    Disease(String display, double contagion, double days, double work, double lethality) {
        this.display = display;
        this.contagion = contagion;
        this.days = days;
        this.work = work;
        this.lethality = lethality;
    }

    public static Disease parse(String s) {
        try {
            return s == null || s.isEmpty() ? null : valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
