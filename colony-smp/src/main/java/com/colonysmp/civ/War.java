package com.colonysmp.civ;

import java.util.LinkedHashMap;
import java.util.Map;

/** A war between two civilisations: who began it, why, and how it stands. */
public final class War {

    public final String id;
    public String attacker, defender;
    public String cause;
    public long startDay;
    /** Above zero the attacker is winning, below zero the defender. */
    public double score;
    public double attackerLosses, defenderLosses;

    public War(String id) {
        this.id = id;
    }

    public boolean involves(String civ) {
        return attacker.equals(civ) || defender.equals(civ);
    }

    public String other(String civ) {
        return attacker.equals(civ) ? defender : attacker;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attacker", attacker);
        m.put("defender", defender);
        m.put("cause", cause);
        m.put("start", startDay);
        m.put("score", score);
        m.put("attacker-losses", attackerLosses);
        m.put("defender-losses", defenderLosses);
        return m;
    }

    public static War fromMap(String id, Map<?, ?> m) {
        War w = new War(id);
        w.attacker = Leader.str(m.get("attacker"), "");
        w.defender = Leader.str(m.get("defender"), "");
        w.cause = Leader.str(m.get("cause"), "an old grudge");
        w.startDay = (long) Leader.num(m.get("start"), 0);
        w.score = Leader.num(m.get("score"), 0);
        w.attackerLosses = Leader.num(m.get("attacker-losses"), 0);
        w.defenderLosses = Leader.num(m.get("defender-losses"), 0);
        return w;
    }
}
