package com.colonysmp.model;

/** Children learn and play, adults work, elders take lighter work and eventually retire. */
public enum LifeStage {
    CHILD("Child"), ADULT("Adult"), ELDER("Elder");

    public final String display;

    LifeStage(String display) {
        this.display = display;
    }

    public static LifeStage of(double ageDays, double adultAt, double elderAt) {
        if (ageDays < adultAt) return CHILD;
        if (ageDays >= elderAt) return ELDER;
        return ADULT;
    }
}
