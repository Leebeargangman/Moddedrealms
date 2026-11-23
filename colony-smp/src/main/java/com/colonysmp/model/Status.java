package com.colonysmp.model;

/** A citizen's place in the colony. */
public enum Status {
    /** A free resident. */
    CITIZEN("Citizen", "<green>"),
    /** Bound with rope after going down, waiting to be taken to a cell. */
    CAPTIVE("Captive", "<gold>"),
    /** Held in a prison cell (an enemy taken in war, or a criminal of this colony). */
    PRISONER("Prisoner", "<yellow>"),
    /** Rioting against the colony until arrested or calmed. */
    RIOTER("Rioter", "<red>");

    public final String display, color;

    Status(String display, String color) {
        this.display = display;
        this.color = color;
    }

    public boolean free() {
        return this == CITIZEN;
    }

    public boolean confined() {
        return this == CAPTIVE || this == PRISONER;
    }
}
