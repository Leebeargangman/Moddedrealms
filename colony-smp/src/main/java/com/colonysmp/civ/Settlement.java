package com.colonysmp.civ;

import com.colonysmp.core.Ledger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One settlement of a civilisation: where it is, who lives there, and what is in its stores. The stores are the
 * whole of its wealth. There is no treasury: a settlement is rich when its granaries and warehouses are full.
 */
public final class Settlement {

    public enum State { ACTIVE, ABANDONED, RUINED }

    /** A passing condition that changes what the settlement makes or what things are worth there. */
    public record Condition(String kind, String target, double factor, long untilDay) {}

    public final String id;
    public String civ;
    public String name;
    public SettlementType type = SettlementType.FARMING_VILLAGE;
    public State state = State.ACTIVE;
    public String world = "world";
    public int x, y, z;
    /** 1 hamlet, 2 village, 3 town, 4 city: how many people it can hold and how much it has built. */
    public int level = 1;
    public boolean capital;
    public double population = 20;
    public double soldiers;
    public double happiness = 60, stability = 60;
    public long founded;
    /** The last day it was simulated (so time away is made up). */
    public long lastDay = -1;
    public final Ledger stock;
    public final List<Condition> conditions = new ArrayList<>();
    /** How well fed its people were yesterday, 0-1. */
    public double fed = 1;
    /** How many of its wants were met yesterday, 0-1. */
    public double supplied = 1;
    /** Days in a row its people went hungry. */
    public int hungryDays;
    /** Under siege by this civilisation (nothing gets in or out). */
    public String besiegedBy;
    /** What it made yesterday, for menus and prices. */
    public final Map<String, Double> output = new LinkedHashMap<>();
    /** Parts of an item made but not yet whole (0.04 diamonds a day become a diamond in 25 days). */
    public final Map<String, Double> partial = new LinkedHashMap<>();

    // the place itself (written by the world layer)
    /** Its buildings have been placed in the world. */
    public boolean built;
    /** The level its buildings were last built for. */
    public int builtLevel;
    public int radius = 24;
    /** Boxes "x1,y1,z1,x2,y2,z2" of what was built: protected, and recognisable as the settlement's. */
    public final List<String> structures = new ArrayList<>();
    /** Where the merchant stands. */
    public int marketX, marketY, marketZ;

    public Settlement(String id) {
        this.id = id;
        this.stock = new Ledger("s-" + id + "-");
    }

    public boolean alive() {
        return state == State.ACTIVE;
    }

    /** How many people its buildings can house. */
    public int capacity(int constructionTech, int maxPerSettlement) {
        int base = switch (level) { case 1 -> 30; case 2 -> 70; case 3 -> 130; default -> 220; };
        return Math.min(maxPerSettlement, (int) (base * (1 + 0.08 * constructionTech)));
    }

    public double condition(String kind, String target, long day) {
        double f = 1;
        for (Condition c : conditions) {
            if (c.untilDay() >= day && c.kind().equals(kind) && (c.target().equals("*") || c.target().equals(target))) f *= c.factor();
        }
        return f;
    }

    public boolean has(String kind, long day) {
        for (Condition c : conditions) if (c.untilDay() >= day && c.kind().equals(kind)) return true;
        return false;
    }

    public void add(String kind, String target, double factor, long untilDay) {
        conditions.add(new Condition(kind, target, factor, untilDay));
    }

    public double distance(Settlement o) {
        if (!world.equals(o.world)) return Double.MAX_VALUE;
        double dx = x - o.x, dz = z - o.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public String title() {
        return switch (level) { case 1 -> "hamlet"; case 2 -> "village"; case 3 -> "town"; default -> "city"; };
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("civ", civ);
        m.put("name", name);
        m.put("type", type.name());
        m.put("state", state.name());
        m.put("world", world);
        m.put("pos", x + "," + y + "," + z);
        m.put("level", level);
        m.put("capital", capital);
        m.put("population", population);
        m.put("soldiers", soldiers);
        m.put("happiness", happiness);
        m.put("stability", stability);
        m.put("founded", founded);
        m.put("last-day", lastDay);
        m.put("fed", fed);
        m.put("supplied", supplied);
        m.put("hungry-days", hungryDays);
        if (besiegedBy != null) m.put("besieged-by", besiegedBy);
        List<String> cs = new ArrayList<>();
        for (Condition c : conditions) cs.add(c.kind() + "|" + c.target() + "|" + c.factor() + "|" + c.untilDay());
        m.put("conditions", cs);
        m.put("partial", new LinkedHashMap<>(partial));
        m.put("output", new LinkedHashMap<>(output));
        m.put("built", built);
        m.put("built-level", builtLevel);
        m.put("radius", radius);
        m.put("structures", new ArrayList<>(structures));
        m.put("market", marketX + "," + marketY + "," + marketZ);
        return m;
    }

    public static Settlement fromMap(String id, Map<?, ?> m) {
        Settlement s = new Settlement(id);
        s.civ = Leader.str(m.get("civ"), "");
        s.name = Leader.str(m.get("name"), id);
        s.type = SettlementType.parse(Leader.str(m.get("type"), ""));
        try {
            s.state = State.valueOf(Leader.str(m.get("state"), "ACTIVE"));
        } catch (IllegalArgumentException e) {
            s.state = State.ACTIVE;
        }
        s.world = Leader.str(m.get("world"), "world");
        int[] p = ints(Leader.str(m.get("pos"), "0,64,0"));
        s.x = p[0];
        s.y = p[1];
        s.z = p[2];
        s.level = Math.max(1, Math.min(4, (int) Leader.num(m.get("level"), 1)));
        s.capital = Boolean.parseBoolean(Leader.str(m.get("capital"), "false"));
        s.population = Math.max(0, Leader.num(m.get("population"), 20));
        s.soldiers = Math.max(0, Leader.num(m.get("soldiers"), 0));
        s.happiness = Leader.num(m.get("happiness"), 60);
        s.stability = Leader.num(m.get("stability"), 60);
        s.founded = (long) Leader.num(m.get("founded"), 0);
        s.lastDay = (long) Leader.num(m.get("last-day"), -1);
        s.fed = Leader.num(m.get("fed"), 1);
        s.supplied = Leader.num(m.get("supplied"), 1);
        s.hungryDays = (int) Leader.num(m.get("hungry-days"), 0);
        s.besiegedBy = m.get("besieged-by") == null ? null : String.valueOf(m.get("besieged-by"));
        if (m.get("conditions") instanceof List<?> cl) {
            for (Object o : cl) {
                String[] a = String.valueOf(o).split("\\|");
                if (a.length == 4) s.conditions.add(new Condition(a[0], a[1], Leader.num(a[2], 1), (long) Leader.num(a[3], 0)));
            }
        }
        if (m.get("partial") instanceof Map<?, ?> pm) pm.forEach((k, v) -> s.partial.put(String.valueOf(k), Leader.num(v, 0)));
        if (m.get("output") instanceof Map<?, ?> om) om.forEach((k, v) -> s.output.put(String.valueOf(k), Leader.num(v, 0)));
        s.built = Boolean.parseBoolean(Leader.str(m.get("built"), "false"));
        s.builtLevel = (int) Leader.num(m.get("built-level"), 0);
        s.radius = (int) Leader.num(m.get("radius"), 24);
        if (m.get("structures") instanceof List<?> sl) for (Object o : sl) s.structures.add(String.valueOf(o));
        int[] mk = ints(Leader.str(m.get("market"), s.x + "," + s.y + "," + s.z));
        s.marketX = mk[0];
        s.marketY = mk[1];
        s.marketZ = mk[2];
        return s;
    }

    static int[] ints(String s) {
        String[] a = s.split(",");
        int[] out = new int[3];
        for (int i = 0; i < 3 && i < a.length; i++) {
            try {
                out[i] = Integer.parseInt(a[i].trim());
            } catch (NumberFormatException ignored) {
                // stays 0
            }
        }
        return out;
    }
}
