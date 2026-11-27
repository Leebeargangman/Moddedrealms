package com.colonysmp.civ;

import java.util.Map;
import java.util.Random;

/** Brings civilisations and settlements into being: a name, a ruler, a way of ruling, and stores to start with. */
public final class CivFactory {

    private CivFactory() {}

    /** A new civilisation whose character suits the kind of settlement it begins as. */
    public static Civ civ(CivWorld w, SettlementType first, Random rng) {
        Civ c = new Civ(w.newId("v"));
        boolean outlaw = first == SettlementType.BANDIT_CAMP;
        String name = outlaw ? "the " + Names.house(rng) + " Band" : Names.people(rng);
        for (int i = 0; i < 20 && taken(w, name); i++) name = outlaw ? "the " + Names.house(rng) + " Band" : Names.people(rng);
        c.name = name;
        c.adjective = name.endsWith("a") ? name + "n" : name + (outlaw ? "" : "ian");
        c.outlaw = outlaw;
        c.founded = w.day;
        Government[] any = Government.values();
        c.government = switch (first) {
            case BANDIT_CAMP, FRONTIER_OUTPOST, ISOLATED_COMMUNITY -> Government.TRIBAL;
            case TRADING_TOWN, COASTAL_SETTLEMENT -> rng.nextBoolean() ? Government.MERCHANT_COUNCIL : Government.REPUBLIC;
            case RELIGIOUS_SETTLEMENT -> Government.THEOCRACY;
            case MILITARY_SETTLEMENT -> rng.nextBoolean() ? Government.MILITARY_DICTATORSHIP : Government.MONARCHY;
            case FORTIFIED_KINGDOM -> Government.MONARCHY;
            case INDUSTRIAL_TOWN -> rng.nextBoolean() ? Government.OLIGARCHY : Government.REPUBLIC;
            default -> any[rng.nextInt(any.length)];
        };
        Leader l = new Leader();
        l.name = Names.person(rng);
        l.house = Names.house(rng);
        Personality[] ps = Personality.values();
        l.personality = switch (first) {
            case BANDIT_CAMP -> rng.nextBoolean() ? Personality.AGGRESSIVE : Personality.OPPORTUNISTIC;
            case MILITARY_SETTLEMENT -> rng.nextBoolean() ? Personality.AGGRESSIVE : Personality.AMBITIOUS;
            case ISOLATED_COMMUNITY -> rng.nextBoolean() ? Personality.DEFENSIVE : Personality.PEACEFUL;
            case TRADING_TOWN -> rng.nextBoolean() ? Personality.GREEDY : Personality.DIPLOMATIC;
            default -> ps[rng.nextInt(ps.length)];
        };
        l.age = 26 + rng.nextInt(28);
        l.since = w.day;
        if (c.government.hereditary() && rng.nextBoolean()) {
            l.heir = Names.person(rng);
            l.heirPersonality = rng.nextBoolean() ? l.personality : ps[rng.nextInt(ps.length)];
        }
        c.leader = l;
        // older peoples know a little more
        for (Tech t : Tech.values()) if (rng.nextDouble() < 0.25) c.tech.put(t, 1);
        w.civs.put(c.id, c);
        c.record(w.day, c.name + " was founded under " + c.ruler() + " of House " + l.house + " (" + c.government.display + ").");
        return c;
    }

    private static boolean taken(CivWorld w, String name) {
        for (Civ c : w.civs.values()) if (c.name.equalsIgnoreCase(name)) return true;
        for (Settlement s : w.settlements.values()) if (s.name.equalsIgnoreCase(name)) return true;
        return false;
    }

    /** A new settlement with people and a few days of everything it makes and eats. */
    public static Settlement settlement(CivWorld w, Civ civ, SettlementType type, String world, int x, int y, int z, Random rng) {
        Settlement s = new Settlement(w.newId("s"));
        String name = type == SettlementType.BANDIT_CAMP ? Names.camp(rng) : Names.place(rng);
        for (int i = 0; i < 20 && taken(w, name); i++) name = type == SettlementType.BANDIT_CAMP ? Names.camp(rng) : Names.place(rng);
        s.name = name;
        s.civ = civ.id;
        s.type = type;
        s.world = world;
        s.x = x;
        s.y = y;
        s.z = z;
        s.marketX = x;
        s.marketY = y;
        s.marketZ = z;
        s.founded = w.day;
        s.lastDay = w.day;
        s.capital = w.of(civ.id).isEmpty();
        s.level = type == SettlementType.FORTIFIED_KINGDOM || type == SettlementType.TRADING_TOWN || type == SettlementType.INDUSTRIAL_TOWN ? 2 : 1;
        s.population = switch (type) {
            case BANDIT_CAMP, FRONTIER_OUTPOST, ISOLATED_COMMUNITY -> 12 + rng.nextInt(8);
            case FORTIFIED_KINGDOM -> 45 + rng.nextInt(15);
            case TRADING_TOWN, INDUSTRIAL_TOWN -> 35 + rng.nextInt(15);
            default -> 20 + rng.nextInt(10);
        };
        s.soldiers = Math.max(1, s.population * type.garrison);
        s.radius = 22 + 4 * s.level;
        stock(s, 5);
        w.settlements.put(s.id, s);
        civ.record(w.day, (s.capital ? "The " + type.noun + " of " + s.name + " was founded as its seat." : "Founded the " + type.noun + " of " + s.name + "."));
        return s;
    }

    /** Fills a new settlement's stores with some days' worth of what it makes, eats and uses. */
    public static void stock(Settlement s, double days) {
        double workers = s.population * CivSim.WORKERS;
        for (SettlementType.Industry i : s.type.industries) {
            s.stock.force(i.out(), Math.round(workers * i.share() * i.perWorker() * days * 0.6));
            for (Map.Entry<String, Double> in : i.inputs().entrySet()) {
                s.stock.force(in.getKey(), Math.round(workers * i.share() * i.perWorker() * in.getValue() * days * 0.5));
            }
        }
        for (Map.Entry<String, Double> e : s.type.wants.entrySet()) s.stock.force(e.getKey(), Math.max(1, Math.round(s.population * e.getValue() * days * 2)));
        // enough to eat until the first caravans come
        s.stock.force("bread", Math.round(s.population * CivSim.FOOD_PER_HEAD * days));
        s.stock.force("oak_planks", 40);
        s.stock.force("cobblestone", 40);
    }
}
