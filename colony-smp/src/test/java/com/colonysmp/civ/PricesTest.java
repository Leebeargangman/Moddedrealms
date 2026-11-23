package com.colonysmp.civ;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Barter: what a settlement gives for what, and that no sequence of swaps ever leaves a trader ahead. */
class PricesTest {

    private CivWorld w;
    private Settlement farm, mine;

    @BeforeEach
    void setUp() {
        w = new CivWorld();
        Random r = new Random(7);
        Civ a = CivFactory.civ(w, SettlementType.FARMING_VILLAGE, r), b = CivFactory.civ(w, SettlementType.MINING_SETTLEMENT, r);
        farm = CivFactory.settlement(w, a, SettlementType.FARMING_VILLAGE, "world", 0, 64, 0, r);
        mine = CivFactory.settlement(w, b, SettlementType.MINING_SETTLEMENT, "world", 600, 64, 0, r);
        farm.population = 40;
        mine.population = 40;
    }

    @Test
    void scarceThingsAreDearAndPlentifulThingsCheap() {
        double empty = Prices.worth(w, farm, "iron_ingot", 0), some = Prices.worth(w, farm, "iron_ingot", 20), lots = Prices.worth(w, farm, "iron_ingot", 500);
        assertTrue(empty > some && some > lots);
        assertEquals(TradeGoods.worth("iron_ingot") * 2.5, empty, 1e-6);
        double t = Prices.target(farm, "wheat");
        assertEquals(TradeGoods.worth("wheat"), Prices.worth(w, farm, "wheat", t), 1e-6, "exactly as much as wanted is worth the base");
    }

    @Test
    void aMiningTownValuesBreadMoreThanAFarmDoes() {
        farm.stock.force("bread", 400);
        mine.stock.clear();
        mine.stock.force("coal", 400);
        farm.stock.take("coal", farm.stock.get("coal"));
        assertTrue(Prices.bid(w, mine, "bread", mine.stock.get("bread"), 0) > Prices.bid(w, farm, "bread", farm.stock.get("bread"), 0));
        assertTrue(Prices.bid(w, farm, "coal", farm.stock.get("coal"), 0) > Prices.bid(w, mine, "coal", mine.stock.get("coal"), 0));
    }

    @Test
    void theSettlementAlwaysAsksMoreThanItGives() {
        for (TradeGoods.Item i : TradeGoods.all()) {
            for (double stock : new double[]{0, 5, 50, 500}) {
                assertTrue(Prices.ask(w, farm, i.key(), stock, 0) > Prices.bid(w, farm, i.key(), stock, 0), i.key());
                assertTrue(Prices.ask(w, farm, i.key(), stock, 100) > Prices.bid(w, farm, i.key(), stock, 100), i.key() + " even for a friend");
            }
        }
    }

    @Test
    void aBigSwapCostsMorePerItemThanASmallOne() {
        farm.stock.force("bread", 2000);
        Prices.Quote one = Prices.quote(w, farm, "iron_ingot", "bread", 10, 0), many = Prices.quote(w, farm, "iron_ingot", "bread", 200, 0);
        assertTrue(one.ok() && many.ok(), one.error() + " " + many.error());
        // (whole items only, so the small swap may round up by one)
        assertTrue(many.giveQty() >= 20 * (one.giveQty() - 1), "200 for " + many.giveQty() + ", 10 for " + one.giveQty());
        // and past a point the settlement has all the iron it can use, whatever is offered for it
        Prices.Quote more = Prices.quote(w, farm, "iron_ingot", "bread", 1500, 0);
        assertFalse(more.ok());
        assertTrue(more.error().contains("no use for that much"), more.error());
    }

    @Test
    void buyingAThingAndSellingItBackAlwaysLoses() {
        Random r = new Random(3);
        String[] goods = {"iron_ingot", "bread", "coal", "wheat", "oak_log", "cobblestone", "leather", "emerald", "iron_sword", "book"};
        for (int round = 0; round < 400; round++) {
            Settlement s = r.nextBoolean() ? farm : mine;
            s.stock.clear();
            for (String g : goods) s.stock.force(g, 20 + r.nextInt(600));
            String pay = goods[r.nextInt(goods.length)], thing = goods[r.nextInt(goods.length)];
            if (pay.equals(thing)) continue;
            long qty = 1 + r.nextInt(30);
            Prices.Quote buy = Prices.quote(w, s, pay, thing, qty, r.nextInt(201) - 100);
            if (!buy.ok()) continue;
            // the swap happens
            s.stock.force(pay, buy.giveQty());
            s.stock.take(thing, qty);
            // now sell every one of them straight back for the same goods
            long back = Prices.affordable(w, s, thing, qty, pay, buy.giveQty() + 10, 100);
            assertTrue(back <= buy.giveQty(), "paid " + buy.giveQty() + " " + pay + " for " + qty + " " + thing + " and got " + back + " back");
        }
    }

    @Test
    void aRoundOfSwapsThroughThreeGoodsNeverGains() {
        Random r = new Random(11);
        String[] goods = {"iron_ingot", "bread", "coal", "wheat", "oak_log", "leather"};
        for (int round = 0; round < 300; round++) {
            farm.stock.clear();
            for (String g : goods) farm.stock.force(g, 50 + r.nextInt(800));
            String a = goods[r.nextInt(6)], b = goods[r.nextInt(6)], c = goods[r.nextInt(6)];
            if (a.equals(b) || b.equals(c) || a.equals(c)) continue;
            long start = 64;
            long gotB = swapAll(a, start, b);
            if (gotB == 0) continue;
            long gotC = swapAll(b, gotB, c);
            if (gotC == 0) continue;
            long end = swapAll(c, gotC, a);
            assertTrue(end <= start, a + "→" + b + "→" + c + "→" + a + " turned " + start + " into " + end);
        }
    }

    /** Gives up to {@code have} of one good for as many of another as it fetches; returns how many were got. */
    private long swapAll(String give, long have, String want) {
        long n = Prices.affordable(w, farm, give, have, want, 100_000, 0);
        if (n <= 0) return 0;
        Prices.Quote q = Prices.quote(w, farm, give, want, n, 0);
        farm.stock.force(give, q.giveQty());
        farm.stock.take(want, n);
        return n;
    }

    @Test
    void aSettlementWontTakeMoreThanItCanUseNorGiveWhatItNeeds() {
        farm.stock.clear();
        farm.stock.force("bread", 30);
        farm.stock.force("diamond", 50);
        long spare = Prices.spare(farm, "bread");
        assertTrue(spare < 30, "it keeps bread back for its own people");
        assertFalse(Prices.quote(w, farm, "cobblestone", "bread", spare + 1, 0).ok());
        // cobblestone from a generator can't buy the diamonds: the settlement stops wanting it
        long room = Prices.accepts(farm, "cobblestone");
        assertTrue(room < 2000);
        Prices.Quote q = Prices.quote(w, farm, "cobblestone", "diamond", 40, 0);
        assertFalse(q.ok());
        assertTrue(q.error().contains("no use"));
    }

    @Test
    void goodStandingNarrowsTheMerchantsCutAndBadStandingWidensIt() {
        assertTrue(Prices.spread(w, farm, 80) < Prices.spread(w, farm, 0));
        assertTrue(Prices.spread(w, farm, -80) > Prices.spread(w, farm, 0));
        assertTrue(Prices.spread(w, farm, 100) >= 0.03);
    }

    @Test
    void warAndHungerMakeFoodAndArmsDear() {
        double bread = Prices.worth(w, farm, "bread", 50), sword = Prices.worth(w, farm, "iron_sword", 2);
        Civ other = w.civs.get(mine.civ);
        new CivSim(new CivSim.Config()).declare(w, w.civs.get(farm.civ), other, "a test");
        assertTrue(Prices.worth(w, farm, "bread", 50) > bread);
        assertTrue(Prices.worth(w, farm, "iron_sword", 2) > sword);
        farm.fed = 0.5;
        assertTrue(Prices.worth(w, farm, "bread", 50) > bread * 1.3);
    }

    @Test
    void junkIsRefused() {
        assertFalse(Prices.quote(w, farm, "bedrock", "bread", 1, 0).ok());
        assertFalse(Prices.quote(w, farm, "wheat", "command_block", 1, 0).ok());
        assertFalse(Prices.quote(w, farm, "wheat", "bread", 0, 0).ok());
        assertFalse(Prices.quote(w, farm, "wheat", "bread", -5, 0).ok());
        assertFalse(Prices.quote(w, farm, "bread", "bread", 5, 0).ok());
    }
}
