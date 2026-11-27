package com.colonysmp.core;

import java.util.EnumMap;
import java.util.Map;

/** A citizen's experience in every skill. Experience only comes from doing the work. */
public final class SkillSet {

    private final EnumMap<Skill, Double> xp = new EnumMap<>(Skill.class);

    public double xp(Skill s) {
        return xp.getOrDefault(s, 0.0);
    }

    public int level(Skill s) {
        return s == null ? 0 : Skill.level(xp(s));
    }

    /** Adds experience; returns the new level if it went up, otherwise -1. */
    public int gain(Skill s, double amount) {
        if (s == null || amount <= 0) return -1;
        int before = level(s);
        xp.merge(s, amount, Double::sum);
        int after = level(s);
        return after > before ? after : -1;
    }

    public void set(Skill s, double value) {
        if (value <= 0) xp.remove(s);
        else xp.put(s, value);
    }

    public Map<Skill, Double> all() {
        return new EnumMap<>(xp);
    }

    /** The skill this citizen is best at, or null if they have none yet. */
    public Skill best() {
        Skill best = null;
        double b = 0;
        for (Map.Entry<Skill, Double> e : xp.entrySet()) {
            if (e.getValue() > b) {
                b = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }
}
