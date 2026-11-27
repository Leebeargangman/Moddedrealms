package com.colonysmp.civ;

import java.util.LinkedHashMap;
import java.util.Map;

/** Who rules a civilisation: their name and house, their character, how old they are, and who comes next. */
public final class Leader {

    public String name;
    /** The house they belong to (a dynasty lasts as long as heirs of the same house keep the seat). */
    public String house;
    public Personality personality = Personality.DIPLOMATIC;
    /** In years (a year is {@link CivSim#DAYS_PER_YEAR} days). */
    public double age = 35;
    public long since;
    /** How many of this house have ruled in a row. */
    public int generation = 1;
    public String heir;
    public Personality heirPersonality;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("house", house);
        m.put("personality", personality.name());
        m.put("age", age);
        m.put("since", since);
        m.put("generation", generation);
        if (heir != null) {
            m.put("heir", heir);
            m.put("heir-personality", heirPersonality == null ? personality.name() : heirPersonality.name());
        }
        return m;
    }

    public static Leader fromMap(Map<?, ?> m) {
        Leader l = new Leader();
        if (m == null) return l;
        l.name = str(m.get("name"), "Nobody");
        l.house = str(m.get("house"), "");
        l.personality = Personality.parse(str(m.get("personality"), ""));
        l.age = num(m.get("age"), 35);
        l.since = (long) num(m.get("since"), 0);
        l.generation = (int) num(m.get("generation"), 1);
        l.heir = m.get("heir") == null ? null : String.valueOf(m.get("heir"));
        l.heirPersonality = m.get("heir-personality") == null ? null : Personality.parse(String.valueOf(m.get("heir-personality")));
        return l;
    }

    static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    static double num(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return o == null ? def : Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
