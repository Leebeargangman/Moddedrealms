package com.colonysmp.civ;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A civilisation: a people with a name, a ruler, a way of ruling, what they have learned, and a history. */
public final class Civ {

    public static final int KEEP_HISTORY = 120;

    public final String id;
    public String name;
    /** "Aldermoor" → "Aldermoorian" traders, for messages. */
    public String adjective;
    public Government government = Government.MONARCHY;
    public Leader leader = new Leader();
    /** Outlaws: no treaties, no embassies, and their caravans are raids. */
    public boolean outlaw;
    public long founded;
    public final Map<Tech, Integer> tech = new EnumMap<>(Tech.class);
    public Tech researching;
    public double research;
    /** How each player stands with this people, -100 to 100. */
    public final Map<UUID, Double> reputation = new HashMap<>();
    /** "Day 12: ..." lines, oldest first. */
    public final List<String> history = new ArrayList<>();
    /** Unrest that hasn't boiled over yet (rebellion and coups draw on it). */
    public double unrest;

    public Civ(String id) {
        this.id = id;
    }

    public int tech(Tech t) {
        return tech.getOrDefault(t, 0);
    }

    /** The average level over all ten fields: the "technology level" shown to players. */
    public double techLevel() {
        double sum = 0;
        for (Tech t : Tech.values()) sum += tech(t);
        return sum / Tech.values().length;
    }

    public double reputation(UUID player) {
        return reputation.getOrDefault(player, 0.0);
    }

    public void addReputation(UUID player, double delta) {
        double v = Math.max(-100, Math.min(100, reputation(player) + delta));
        if (Math.abs(v) < 0.01) reputation.remove(player);
        else reputation.put(player, v);
    }

    public void record(long day, String what) {
        history.add("Day " + day + ": " + what);
        while (history.size() > KEEP_HISTORY) history.remove(0);
    }

    public String ruler() {
        return government.title + " " + leader.name;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("adjective", adjective);
        m.put("government", government.name());
        m.put("leader", leader.toMap());
        m.put("outlaw", outlaw);
        m.put("founded", founded);
        Map<String, Object> t = new LinkedHashMap<>();
        tech.forEach((k, v) -> t.put(k.name(), v));
        m.put("tech", t);
        if (researching != null) m.put("researching", researching.name());
        m.put("research", research);
        m.put("unrest", unrest);
        Map<String, Object> r = new LinkedHashMap<>();
        reputation.forEach((k, v) -> r.put(k.toString(), v));
        m.put("reputation", r);
        m.put("history", new ArrayList<>(history));
        return m;
    }

    public static Civ fromMap(String id, Map<?, ?> m) {
        Civ c = new Civ(id);
        c.name = Leader.str(m.get("name"), id);
        c.adjective = Leader.str(m.get("adjective"), c.name);
        c.government = Government.parse(Leader.str(m.get("government"), ""));
        if (m.get("leader") instanceof Map<?, ?> lm) c.leader = Leader.fromMap(lm);
        c.outlaw = Boolean.parseBoolean(Leader.str(m.get("outlaw"), "false"));
        c.founded = (long) Leader.num(m.get("founded"), 0);
        if (m.get("tech") instanceof Map<?, ?> tm) {
            for (Map.Entry<?, ?> e : tm.entrySet()) {
                try {
                    c.tech.put(Tech.valueOf(String.valueOf(e.getKey())), Math.max(0, Math.min(Tech.MAX, (int) Leader.num(e.getValue(), 0))));
                } catch (IllegalArgumentException ignored) {
                    // a field from another version
                }
            }
        }
        if (m.get("researching") != null) {
            try {
                c.researching = Tech.valueOf(String.valueOf(m.get("researching")));
            } catch (IllegalArgumentException ignored) {
                // picks a new one
            }
        }
        c.research = Leader.num(m.get("research"), 0);
        c.unrest = Leader.num(m.get("unrest"), 0);
        if (m.get("reputation") instanceof Map<?, ?> rm) {
            for (Map.Entry<?, ?> e : rm.entrySet()) {
                try {
                    c.reputation.put(UUID.fromString(String.valueOf(e.getKey())), Leader.num(e.getValue(), 0));
                } catch (IllegalArgumentException ignored) {
                    // not a player id
                }
            }
        }
        if (m.get("history") instanceof List<?> hl) for (Object o : hl) c.history.add(String.valueOf(o));
        return c;
    }
}
