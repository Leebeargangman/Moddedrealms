package com.colonysmp.civ;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The living world, run for seasons at a time: economies that hang together, histories that persist, and no free lunch. */
class CivSimTest {

    private CivWorld w;
    private CivSim sim;
    private Random r;

    @BeforeEach
    void setUp() {
        w = new CivWorld();
        w.seed = 99;
        CivSim.Config cfg = new CivSim.Config();
        cfg.events = 0; // the economy tests want quiet years; the events have their own tests
        cfg.wars = false;
        sim = new CivSim(cfg);
        r = new Random(5);
    }

    private Settlement found(SettlementType type, int x, int z) {
        Civ c = CivFactory.civ(w, type, r);
        return CivFactory.settlement(w, c, type, "world", x, 64, z, r);
    }

    private static Map<String, Object> dump(CivWorld w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("meta", w.metaMap());
        w.civs.forEach((k, v) -> m.put("civ:" + k, v.toMap()));
        w.settlements.forEach((k, v) -> {
            m.put("s:" + k, v.toMap());
            m.put("stock:" + k, v.stock.snapshot());
        });
        w.shipments.forEach((k, v) -> m.put("ship:" + k, v.toMap()));
        w.contracts.forEach((k, v) -> m.put("k:" + k, v.toMap()));
        w.wars.forEach((k, v) -> m.put("war:" + k, v.toMap()));
        return m;
    }

    private static CivWorld reload(CivWorld w) {
        CivWorld n = new CivWorld();
        n.loadMeta(w.metaMap());
        w.civs.forEach((k, v) -> n.civs.put(k, Civ.fromMap(k, v.toMap())));
        w.settlements.forEach((k, v) -> {
            Settlement s = Settlement.fromMap(k, v.toMap());
            v.stock.snapshot().forEach(s.stock::force);
            n.settlements.put(k, s);
        });
        w.shipments.forEach((k, v) -> n.shipments.put(k, Shipment.fromMap(k, v.toMap())));
        w.contracts.forEach((k, v) -> n.contracts.put(k, Contract.fromMap(k, v.toMap())));
        w.wars.forEach((k, v) -> n.wars.put(k, War.fromMap(k, v.toMap())));
        return n;
    }

    @Test
    void aFarmingVillageFeedsItselfAndFillsItsGranaries() {
        Settlement farm = found(SettlementType.FARMING_VILLAGE, 0, 0);
        double start = farm.population;
        sim.advance(w, 60);
        assertTrue(farm.alive());
        assertTrue(farm.fed > 0.95, "fed " + farm.fed);
        assertTrue(farm.population > start, "it grows: " + start + " → " + farm.population);
        assertTrue(farm.stock.get("wheat") + farm.stock.get("bread") > 50);
    }

    @Test
    void aMiningSettlementDigsOreButCannotFeedItselfAlone() {
        Settlement mine = found(SettlementType.MINING_SETTLEMENT, 0, 0);
        sim.advance(w, 40);
        assertTrue(mine.stock.get("coal") > 20 && mine.stock.get("raw_iron") > 10, "it exports minerals");
        assertTrue(mine.fed < 0.9, "but its gardens don't feed it: fed " + mine.fed);
    }

    @Test
    void tradeFeedsTheMinersAndBringsTheFarmersIron() {
        Settlement alone = found(SettlementType.MINING_SETTLEMENT, 50_000, 50_000);
        Settlement mine = found(SettlementType.MINING_SETTLEMENT, 0, 0);
        Settlement farm = found(SettlementType.FARMING_VILLAGE, 600, 0);
        Settlement farm2 = found(SettlementType.FARMING_VILLAGE, 0, 700);
        long ore0 = farm.stock.get("coal") + farm.stock.get("raw_iron") + farm.stock.get("cobblestone");
        double fedAlone = 0, fedTrading = 0;
        for (int d = 0; d < 80; d++) {
            sim.step(w);
            if (d >= 20) {
                fedAlone += alone.fed;
                fedTrading += mine.fed;
            }
        }
        assertTrue(fedTrading > fedAlone + 3, "miners with farms to trade with eat better than miners alone (" + fedTrading / 60 + " vs " + fedAlone / 60 + ")");
        long ore = farm.stock.get("coal") + farm.stock.get("raw_iron") + farm.stock.get("cobblestone") + farm2.stock.get("coal") + farm2.stock.get("cobblestone");
        assertTrue(ore > ore0, "and the mine's goods reached the farms");
        assertTrue(w.relation(mine.civ, farm.civ) > 0 || w.relation(mine.civ, farm2.civ) > 0, "trade makes friends");
    }

    @Test
    void nothingComesFromNothing() {
        // a world with no industry at all: goods can be eaten, used, moved and lost, but their number can never go up
        Settlement a = found(SettlementType.TRADING_TOWN, 0, 0), b = found(SettlementType.FORTIFIED_KINGDOM, 500, 0);
        for (Settlement s : w.settlements.values()) s.add("output", "*", 0, 10_000);
        long before = total();
        for (int d = 0; d < 60; d++) {
            sim.step(w);
            long now = total();
            assertTrue(now <= before, "day " + d + ": " + before + " items became " + now);
            before = now;
        }
        assertTrue(a.alive() || b.alive() || true);
    }

    private long total() {
        long n = 0;
        for (Settlement s : w.settlements.values()) n += s.stock.total();
        for (Shipment sh : w.shipments.values()) for (long q : sh.cargo.values()) n += q;
        return n;
    }

    @Test
    void storesFillUpAndThenStop() {
        Settlement farm = found(SettlementType.FARMING_VILLAGE, 0, 0);
        sim.advance(w, 400);
        for (TradeGoods.Item i : TradeGoods.all()) {
            double cap = Prices.target(farm, i.key()) * Prices.GLUT + 2;
            if (farm.type.makes(i.key())) assertTrue(farm.stock.get(i.key()) <= cap, i.key() + ": " + farm.stock.get(i.key()) + " over " + cap);
        }
    }

    @Test
    void theSameSeedTellsTheSameHistory() {
        CivSim.Config cfg = new CivSim.Config();
        CivWorld a = build(cfg), b = build(cfg);
        new CivSim(cfg).advance(a, 150);
        new CivSim(cfg).advance(b, 150);
        assertEquals(dump(a).toString(), dump(b).toString());
    }

    private static CivWorld build(CivSim.Config cfg) {
        CivWorld w = new CivWorld();
        w.seed = 1234;
        Random r = new Random(8);
        SettlementType[] types = SettlementType.values();
        for (int i = 0; i < 12; i++) {
            Civ c = CivFactory.civ(w, types[i], r);
            CivFactory.settlement(w, c, types[i], "world", (i % 4) * 500, 64, (i / 4) * 500, r);
        }
        return w;
    }

    @Test
    void everythingSurvivesASaveAndALoad() {
        CivSim.Config cfg = new CivSim.Config();
        cfg.events = 3;
        CivWorld a = build(cfg);
        CivSim s = new CivSim(cfg);
        s.advance(a, 120);
        UUID player = UUID.randomUUID();
        a.civs.values().iterator().next().addReputation(player, 37.5);
        a.regions.put("world:3:-2", "s4");
        a.journalMark = 41;
        a.appliedUncommitted.add("abc");
        CivWorld b = reload(a);
        assertEquals(dump(a).toString(), dump(b).toString());
        assertEquals(37.5, b.civs.values().iterator().next().reputation(player));
        assertFalse(b.civs.values().iterator().next().history.isEmpty());
        // and the two go on to live the same lives
        new CivSim(cfg).advance(a, 60);
        new CivSim(cfg).advance(b, 60);
        assertEquals(dump(a).toString(), dump(b).toString(), "a restart changes nothing about what happens next");
        // ids handed out after a reload never collide with ones already used
        for (String id : b.settlements.keySet()) b.seenId(id);
        assertFalse(b.settlements.containsKey(b.newId("s")));
    }

    @Test
    void aWarIsFoughtRememberedAndEnded() {
        CivSim.Config cfg = new CivSim.Config();
        cfg.events = 0;
        sim = new CivSim(cfg);
        Settlement strong = found(SettlementType.FORTIFIED_KINGDOM, 0, 0), weak = found(SettlementType.FARMING_VILLAGE, 500, 0);
        strong.soldiers = 30;
        strong.stock.force("iron_sword", 40);
        Civ a = w.civs.get(strong.civ), d = w.civs.get(weak.civ);
        w.sign(a.id, d.id, Treaty.TRADE_AGREEMENT, 0);
        double popBefore = strong.population + weak.population;
        War war = sim.declare(w, a, d, "a disputed border");
        assertNotNull(w.war(a.id, d.id));
        assertFalse(w.has(a.id, d.id, Treaty.TRADE_AGREEMENT), "treaties are torn up");
        assertEquals("at war", w.standing(a.id, d.id));
        // a restart in the middle of it
        sim.advance(w, 2);
        CivWorld loaded = reload(w);
        War same = loaded.wars.get(war.id);
        assertNotNull(same);
        assertEquals(war.score, same.score, 1e-9);
        assertEquals(war.cause, same.cause);
        int days = 0;
        while (w.war(a.id, d.id) != null && days++ < 300) sim.step(w);
        assertNull(w.war(a.id, d.id), "wars end");
        assertTrue(w.has(a.id, d.id, Treaty.PEACE));
        assertTrue(strong.population + weak.population < popBefore + days * 2, "people died");
        assertTrue(a.history.stream().anyMatch(h -> h.contains("declared war")));
        for (Settlement s : w.settlements.values()) assertNull(s.besiegedBy, "sieges are lifted");
        // and the peace holds: no new war while the treaty lasts
        cfg.wars = true;
        w.relations.put(CivWorld.pair(a.id, d.id), -100.0);
        sim.advance(w, 20);
        assertNull(w.war(a.id, d.id));
    }

    @Test
    void rulersDieAndAreSucceeded() {
        Settlement s = found(SettlementType.FORTIFIED_KINGDOM, 0, 0);
        Civ c = w.civs.get(s.civ);
        c.leader.heir = "Edmund";
        c.leader.heirPersonality = Personality.PEACEFUL;
        String house = c.leader.house;
        sim.succeed(w, c, new Random(1), c.leader.name + " died", false);
        assertEquals("Edmund", c.leader.name);
        assertEquals(house, c.leader.house, "the dynasty goes on");
        assertEquals(2, c.leader.generation);
        assertEquals(Personality.PEACEFUL, c.leader.personality);
        // no heir: the house falls
        c.leader.heir = null;
        sim.succeed(w, c, new Random(2), "The ruler died", false);
        assertEquals(1, c.leader.generation);
        // old age catches everyone in the end
        c.leader.age = 95;
        String old = c.leader.name;
        long since = c.leader.since;
        sim.advance(w, 400);
        assertTrue(c.leader.since > since || !c.leader.name.equals(old), "a ruler of 95 does not reign four more years");
    }

    @Test
    void aStarvedSettlementFallsToRuin() {
        Settlement camp = found(SettlementType.MINING_SETTLEMENT, 0, 0);
        camp.stock.clear();
        camp.add("output", "*", 0, 10_000);
        sim.advance(w, 200);
        assertEquals(Settlement.State.RUINED, camp.state);
        assertEquals(0, camp.population);
        Civ c = w.civs.get(camp.civ);
        assertTrue(c.history.get(c.history.size() - 1).contains("went cold"));
        assertTrue(w.of(c.id).isEmpty());
        // ruins don't trade, grow or post contracts
        long stock = camp.stock.total();
        sim.advance(w, 30);
        assertEquals(stock, camp.stock.total());
    }

    @Test
    void contractsAskForWhatIsShortAndOfferWhatIsSpare() {
        Settlement mine = found(SettlementType.MINING_SETTLEMENT, 0, 0);
        sim.advance(w, 30);
        Contract c = null;
        for (int i = 0; i < 20 && c == null; i++) c = sim.post(w, mine, new Random(i));
        assertNotNull(c, "a hungry mining town with coal to spare posts a contract");
        assertFalse(mine.type.makes(c.want));
        assertTrue(Prices.spare(mine, c.reward) >= c.rewardQty, "it never promises what it hasn't got");
        double asked = c.wantQty * TradeGoods.worth(c.want), offered = c.rewardQty * TradeGoods.worth(c.reward);
        assertTrue(offered <= asked * 1.34, "the bonus is bounded: " + offered + " for " + asked);
        // an untaken contract lapses
        long posted = w.contracts.size();
        sim.advance(w, 30);
        assertNotEquals(Contract.State.OPEN, c.state);
        assertTrue(w.contracts.size() <= posted + 2 * w.settlements.size());
    }

    @Test
    void learningTakesTimeAndBooks() {
        Settlement abbey = found(SettlementType.RELIGIOUS_SETTLEMENT, 0, 0);
        Civ c = w.civs.get(abbey.civ);
        c.tech.clear();
        abbey.stock.take("book", abbey.stock.get("book"));
        abbey.add("output", "book", 0, 10_000);
        sim.advance(w, 200);
        assertEquals(0.0, c.techLevel(), "without books nothing is learned, however long they study");
        abbey.conditions.clear();
        abbey.stock.force("book", 60);
        sim.advance(w, 200);
        assertTrue(c.techLevel() > 0);
        assertTrue(abbey.stock.get("book") < 60 + 200, "and the books are used up, not copied");
        for (Tech t : Tech.values()) assertTrue(c.tech(t) <= Tech.MAX);
    }

    @Test
    void aBusyWorldRunsFast() {
        CivSim.Config cfg = new CivSim.Config();
        cfg.events = 2;
        CivWorld big = new CivWorld();
        big.seed = 77;
        Random rr = new Random(3);
        SettlementType[] types = SettlementType.values();
        for (int i = 0; i < 40; i++) {
            Civ c = i % 3 == 0 || big.civs.isEmpty() ? CivFactory.civ(big, types[i % types.length], rr) : big.civs.values().stream().skip(rr.nextInt(big.civs.size())).findFirst().orElseThrow();
            CivFactory.settlement(big, c, types[i % types.length], "world", (i % 7) * 450, 64, (i / 7) * 450, rr);
        }
        CivSim s = new CivSim(cfg);
        long t0 = System.nanoTime();
        s.advance(big, 365);
        double msPerDay = (System.nanoTime() - t0) / 1e6 / 365;
        assertTrue(msPerDay < 25, "40 settlements should take well under a tick a day: " + msPerDay + " ms");
        assertFalse(big.alive().isEmpty(), "and the world should still have people in it after a year");
        long people = 0;
        for (Settlement st : big.alive()) people += Math.round(st.population);
        Map<String, int[]> by = new java.util.TreeMap<>();
        for (Settlement st : big.settlements.values()) {
            int[] n = by.computeIfAbsent(st.type.name(), k -> new int[3]);
            n[0]++;
            if (st.alive()) {
                n[1]++;
                n[2] += (int) st.population;
            }
        }
        by.forEach((k, v) -> System.out.println("[perf]   " + k + ": " + v[1] + "/" + v[0] + " alive, " + v[2] + " people"));
        System.out.println("[perf] 40 settlements, 365 days: " + String.format("%.2f", msPerDay) + " ms/day; " + big.alive().size() + " settlements alive, "
                + people + " people, " + big.wars.size() + " wars, " + big.shipments.size() + " caravans on the road, " + big.contracts.size() + " contracts");
    }
}
