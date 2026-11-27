package com.colonysmp.model;

/**
 * How one citizen relates to another. Family bonds are permanent; friendship and rivalry follow affinity,
 * which grows by living, working and spending evenings together and sours through disputes.
 */
public final class Bond {

    public enum Kind {
        PARTNER("Partner"), PARENT("Parent"), CHILD("Child"), SIBLING("Sibling"), FRIEND("Friend"), RIVAL("Rival"), ACQUAINTANCE("Acquaintance");

        public final String display;

        Kind(String display) {
            this.display = display;
        }

        public boolean family() {
            return this == PARTNER || this == PARENT || this == CHILD || this == SIBLING;
        }

        public static Kind parse(String s) {
            try {
                return valueOf(s);
            } catch (RuntimeException e) {
                return ACQUAINTANCE;
            }
        }
    }

    public Kind kind;
    /** -100 (hatred) to 100 (devotion). */
    public double affinity;

    public Bond(Kind kind, double affinity) {
        this.kind = kind;
        this.affinity = affinity;
    }

    /** Re-labels friends and rivals as affinity changes (family stays family). */
    public void settle() {
        if (kind.family()) return;
        if (affinity >= 40) kind = Kind.FRIEND;
        else if (affinity <= -40) kind = Kind.RIVAL;
        else kind = Kind.ACQUAINTANCE;
    }
}
