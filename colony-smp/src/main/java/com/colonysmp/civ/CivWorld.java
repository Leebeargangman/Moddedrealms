package com.colonysmp.civ;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the living world is made of: the civilisations, their settlements, what stands between each pair
 * of them, the caravans on the road, the wars being fought and the contracts on offer. Plain data: the
 * simulation ({@link CivSim}) changes it, the server shows it, the database stores it.
 */
public final class CivWorld {

    public final Map<String, Civ> civs = new LinkedHashMap<>();
    public final Map<String, Settlement> settlements = new LinkedHashMap<>();
    public final Map<String, Shipment> shipments = new LinkedHashMap<>();
    public final Map<String, Contract> contracts = new LinkedHashMap<>();
    public final Map<String, War> wars = new LinkedHashMap<>();
    /** How two civilisations feel about each other, -100 to 100, keyed by {@link #pair}. */
    public final Map<String, Double> relations = new LinkedHashMap<>();
    public final Map<String, EnumSet<Treaty>> treaties = new LinkedHashMap<>();
    /** The day a treaty between a pair lapses ("a|b|PEACE" → day). */
    public final Map<String, Long> treatyUntil = new LinkedHashMap<>();
    /** Who pays tribute to whom under a TRIBUTE treaty (pair → the civilisation that pays). */
    public final Map<String, String> tributePayer = new LinkedHashMap<>();
    /** Regions of the map already considered for a settlement or a ruin ("world:rx:rz" → what was decided). */
    public final Map<String, String> regions = new LinkedHashMap<>();
    /** The world's own calendar: one more for every Minecraft day that passes (and some for time the server was off). */
    public long day;
    public long seed = 1;
    /** When these books were last written (epoch ms), so time the server was off can be made up. */
    public long savedAt;
    /** The Minecraft day the calendar was last moved on at. */
    public long lastGameDay = -1;
    private long nextId = 1;
    /** The last journal commit these books contain (later commits are replayed onto them at start-up). */
    public long journalMark;
    /** Transactions whose effects are in these books but whose commit could not be written. */
    public final Set<String> appliedUncommitted = new LinkedHashSet<>();
    /** News for anyone watching: drained by the server and shown sparingly. */
    public final List<News> news = new ArrayList<>();

    /** Something that happened. {@code weight} 1 is local colour, 3 is the talk of the whole map. */
    public record News(long day, String settlement, String civ, int weight, String text) {}

    public String newId(String prefix) {
        return prefix + Long.toString(nextId++, 36);
    }

    public static String pair(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    public double relation(String a, String b) {
        return a.equals(b) ? 100 : relations.getOrDefault(pair(a, b), 0.0);
    }

    public void adjustRelation(String a, String b, double delta) {
        if (a.equals(b)) return;
        relations.put(pair(a, b), Math.max(-100, Math.min(100, relation(a, b) + delta)));
    }

    public boolean has(String a, String b, Treaty t) {
        EnumSet<Treaty> s = treaties.get(pair(a, b));
        return s != null && s.contains(t);
    }

    public Set<Treaty> treaties(String a, String b) {
        EnumSet<Treaty> s = treaties.get(pair(a, b));
        return s == null ? EnumSet.noneOf(Treaty.class) : EnumSet.copyOf(s);
    }

    public void sign(String a, String b, Treaty t, long untilDay) {
        treaties.computeIfAbsent(pair(a, b), k -> EnumSet.noneOf(Treaty.class)).add(t);
        if (untilDay > 0) treatyUntil.put(pair(a, b) + "|" + t.name(), untilDay);
    }

    public void tear(String a, String b, Treaty t) {
        EnumSet<Treaty> s = treaties.get(pair(a, b));
        if (s != null) {
            s.remove(t);
            if (s.isEmpty()) treaties.remove(pair(a, b));
        }
        treatyUntil.remove(pair(a, b) + "|" + t.name());
    }

    public War war(String a, String b) {
        for (War w : wars.values()) if (w.involves(a) && w.involves(b)) return w;
        return null;
    }

    public boolean atWar(String civ) {
        for (War w : wars.values()) if (w.involves(civ)) return true;
        return false;
    }

    /** One word for where two civilisations stand. */
    public String standing(String a, String b) {
        if (war(a, b) != null) return "at war";
        if (has(a, b, Treaty.ALLIANCE)) return "allied";
        if (has(a, b, Treaty.EMBARGO)) return "embargoed";
        double r = relation(a, b);
        if (r >= 40) return "friendly";
        if (r <= -60) return "hostile";
        if (r <= -25) return "rivals";
        return "neutral";
    }

    public List<Settlement> of(String civ) {
        List<Settlement> out = new ArrayList<>();
        for (Settlement s : settlements.values()) if (civ.equals(s.civ) && s.alive()) out.add(s);
        return out;
    }

    public Settlement capital(String civ) {
        Settlement best = null;
        for (Settlement s : of(civ)) if (best == null || s.capital && !best.capital || s.capital == best.capital && s.population > best.population) best = s;
        return best;
    }

    public double population(String civ) {
        double n = 0;
        for (Settlement s : of(civ)) n += s.population;
        return n;
    }

    public double soldiers(String civ) {
        double n = 0;
        for (Settlement s : of(civ)) n += s.soldiers;
        return n;
    }

    /** A civilisation's strength at arms: its soldiers, better armed and better led. */
    public double strength(String civ) {
        Civ c = civs.get(civ);
        if (c == null) return 0;
        double arms = 0;
        for (Settlement s : of(civ)) arms += s.stock.get("iron_sword") + 0.5 * s.stock.get("bow") + 0.5 * s.stock.get("shield");
        double soldiers = soldiers(civ);
        double armed = soldiers <= 0 ? 0 : Math.min(1, arms / soldiers);
        return soldiers * (0.6 + 0.4 * armed) * (1 + 0.15 * c.tech(Tech.MILITARY));
    }

    /** The whole of a settlement's wealth: everything in its stores at base worth. */
    public static double wealth(Settlement s) {
        double v = 0;
        for (Map.Entry<String, Long> e : s.stock.contents().entrySet()) v += TradeGoods.worth(e.getKey()) * e.getValue();
        return v;
    }

    public Settlement nearest(String world, int x, int z, double within) {
        Settlement best = null;
        double bd = within * within;
        for (Settlement s : settlements.values()) {
            if (!s.world.equals(world)) continue;
            double dx = s.x - x, dz = s.z - z, d = dx * dx + dz * dz;
            if (d <= bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    public void news(String settlement, String civ, int weight, String text) {
        news.add(new News(day, settlement, civ, weight, text));
        while (news.size() > 200) news.remove(0);
    }

    public Collection<Settlement> alive() {
        List<Settlement> out = new ArrayList<>();
        for (Settlement s : settlements.values()) if (s.alive()) out.add(s);
        return out;
    }

    // ───────────── saving ─────────────

    /** The parts that aren't a civilisation, settlement, caravan, contract or war, as one document. */
    public Map<String, Object> metaMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day", day);
        m.put("seed", seed);
        m.put("saved-at", savedAt);
        m.put("last-game-day", lastGameDay);
        m.put("next-id", nextId);
        m.put("journal-mark", journalMark);
        m.put("applied-uncommitted", new ArrayList<>(appliedUncommitted));
        m.put("relations", new LinkedHashMap<>(relations));
        Map<String, Object> t = new LinkedHashMap<>();
        treaties.forEach((k, v) -> {
            List<String> names = new ArrayList<>();
            for (Treaty tr : v) names.add(tr.name());
            t.put(k, names);
        });
        m.put("treaties", t);
        m.put("treaty-until", new LinkedHashMap<>(treatyUntil));
        m.put("regions", new LinkedHashMap<>(regions));
        m.put("tribute", new LinkedHashMap<>(tributePayer));
        return m;
    }

    public void loadMeta(Map<?, ?> m) {
        day = (long) Leader.num(m.get("day"), 0);
        seed = (long) Leader.num(m.get("seed"), 1);
        savedAt = (long) Leader.num(m.get("saved-at"), 0);
        lastGameDay = (long) Leader.num(m.get("last-game-day"), -1);
        nextId = Math.max(1, (long) Leader.num(m.get("next-id"), 1));
        journalMark = (long) Leader.num(m.get("journal-mark"), 0);
        if (m.get("applied-uncommitted") instanceof List<?> l) for (Object o : l) appliedUncommitted.add(String.valueOf(o));
        if (m.get("relations") instanceof Map<?, ?> rm) rm.forEach((k, v) -> relations.put(String.valueOf(k), Leader.num(v, 0)));
        if (m.get("treaties") instanceof Map<?, ?> tm) {
            tm.forEach((k, v) -> {
                EnumSet<Treaty> set = EnumSet.noneOf(Treaty.class);
                if (v instanceof List<?> l) {
                    for (Object o : l) {
                        try {
                            set.add(Treaty.valueOf(String.valueOf(o)));
                        } catch (IllegalArgumentException ignored) {
                            // a treaty from another version
                        }
                    }
                }
                if (!set.isEmpty()) treaties.put(String.valueOf(k), set);
            });
        }
        if (m.get("treaty-until") instanceof Map<?, ?> um) um.forEach((k, v) -> treatyUntil.put(String.valueOf(k), (long) Leader.num(v, 0)));
        if (m.get("regions") instanceof Map<?, ?> gm) gm.forEach((k, v) -> regions.put(String.valueOf(k), String.valueOf(v)));
        if (m.get("tribute") instanceof Map<?, ?> pm) pm.forEach((k, v) -> tributePayer.put(String.valueOf(k), String.valueOf(v)));
    }

    /** Ids handed out so far (kept ahead of every id that was loaded). */
    public void seenId(String id) {
        try {
            nextId = Math.max(nextId, Long.parseLong(id.substring(1), 36) + 1);
        } catch (RuntimeException ignored) {
            // not one of ours
        }
    }
}
