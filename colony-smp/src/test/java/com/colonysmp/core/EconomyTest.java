package com.colonysmp.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EconomyTest {

    @BeforeEach
    void setUp() {
        TestGoods.setUp();
    }

    @Test
    void treasuryRefusesPaymentsItCantMake() {
        Treasury t = new Treasury();
        t.credit(100, TxnType.SALE, "wheat", 0, 0);
        assertNull(t.debit(150, TxnType.WAGES, "", 0, 0));
        assertEquals(100, t.balance());
        assertNotNull(t.debit(40, TxnType.WAGES, "", 0, 0));
        assertEquals(60, t.balance());
        assertEquals(60, t.debitUpTo(500, TxnType.UPKEEP, "", 0, 0));
        assertEquals(0, t.balance());
    }

    @Test
    void theTreasuryIsTheValuablesInStorage() {
        Ledger store = new Ledger("s");
        Treasury t = new Treasury(store);
        store.force("diamond", 2);
        store.force("iron_ingot", 3);
        store.force("wheat", 64);
        assertEquals(2 * 60 + 3 * 9, t.balance());
        // reserved valuables can't be spent
        store.reserve("smith", "iron_ingot", 1, 0);
        assertEquals(2 * 60 + 2 * 9, t.balance());
        assertEquals(64, store.get("wheat"));
    }

    @Test
    void paymentsBreakIngotsForChangeAndConserveWorth() {
        Ledger store = new Ledger("s");
        Treasury t = new Treasury(store);
        store.force("iron_ingot", 2);
        Map<String, Long> paid = t.debitItems(4, TxnType.WAGES, "", 0, 0);
        assertEquals(Map.of("iron_nugget", 4L), paid);
        assertEquals(1, store.get("iron_ingot"));
        assertEquals(5, store.get("iron_nugget"));
        assertEquals(14, t.balance());
        assertEquals(-4, t.recent().get(0).amount());
    }

    @Test
    void largestValuablesGoFirst() {
        Ledger store = new Ledger("s");
        Treasury t = new Treasury(store);
        store.force("diamond", 3);
        store.force("emerald", 5);
        store.force("iron_nugget", 20);
        Map<String, Long> paid = t.debitItems(75, TxnType.CONSTRUCTION, "", 0, 0);
        assertEquals(1L, paid.get("diamond"));
        assertEquals(1L, paid.get("emerald"));
        assertEquals(5L, paid.get("iron_nugget"));
        assertEquals(75, Treasury.worth(paid));
    }

    @Test
    void withOnlyGemsTheSmallestIsHandedOverWithoutChange() {
        Ledger store = new Ledger("s");
        Treasury t = new Treasury(store);
        store.force("diamond", 1);
        store.force("emerald", 1);
        Txn tx = t.debit(4, TxnType.WAGES, "", 0, 0);
        assertNotNull(tx);
        assertEquals(-10, tx.amount());
        assertEquals(0, store.get("emerald"));
        assertEquals(60, t.balance());
    }

    @Test
    void incomeArrivesAsMetalAndGems() {
        Ledger store = new Ledger("s");
        Treasury t = new Treasury(store);
        t.credit(137, TxnType.SALE, "", 0, 0);
        assertEquals(137, t.balance());
        assertTrue(store.get("diamond") > 0);
        assertTrue(store.get("gold_ingot") + store.get("iron_ingot") > 0);
    }

    @Test
    void transfersMoveTheVeryItems() {
        Ledger a = new Ledger("a"), b = new Ledger("b");
        Treasury ta = new Treasury(a), tb = new Treasury(b);
        a.force("gold_block", 1);
        long moved = ta.transfer(tb, 162, TxnType.TRADE_OUT, "", 0, TxnType.TRADE_IN, "", 0, 0);
        assertEquals(162, moved);
        assertEquals(1, b.get("gold_block"));
        assertEquals(0, ta.balance());
        assertEquals(0, ta.transfer(tb, 5, TxnType.TRADE_OUT, "", 0, TxnType.TRADE_IN, "", 0, 0));
    }

    @Test
    void oldCoinBalancesBecomeValuablesOfTheSameWorth() {
        Ledger store = new Ledger("s");
        Treasury t = new Treasury(store);
        t.load(1234, 1, java.util.List.of());
        assertEquals(1234, t.balance());
        assertTrue(t.recent().isEmpty());
    }

    @Test
    void breakableValuablesAreWorthExactlyNineOfTheirParts() {
        assertEquals(81, Treasury.worth("iron_block"));
        assertEquals(9 * Treasury.worth("gold_nugget"), Treasury.worth("gold_ingot"));
        assertEquals(9 * Treasury.worth("diamond"), Treasury.worth("diamond_block"));
        assertEquals(0, Treasury.worth("wheat"));
    }

    @Test
    void everyMovementIsRecordedWithTheBalanceAfter() {
        Treasury t = new Treasury();
        t.credit(50, TxnType.TAX, "taxes", 0, 1);
        t.debit(20, TxnType.WAGES, "wages", 0, 1);
        var log = t.recent();
        assertEquals(2, log.size());
        assertEquals(-20, log.get(0).amount());
        assertEquals(30, log.get(0).balance());
        assertEquals(2, t.drainUnsaved().size());
        assertTrue(t.drainUnsaved().isEmpty());
        assertEquals(50, t.today(1).get(TxnType.TAX));
        t.credit(5, TxnType.TAX, "", 0, 2);
        assertEquals(50, t.yesterday(2).get(TxnType.TAX));
    }

    @Test
    void paymentsBookedToTheDayThatJustEndedCountForIt() {
        Treasury t = new Treasury();
        t.credit(100, TxnType.SALE, "", 0, 1);
        t.credit(7, TxnType.SERVICES, "meals", 0, 2);
        // wages for day 1, paid as day 2 begins
        t.debit(10, TxnType.WAGES, "", 0, 1);
        assertEquals(-10, t.yesterday(2).get(TxnType.WAGES));
        assertEquals(100, t.yesterday(2).get(TxnType.SALE));
        assertEquals(7, t.today(2).get(TxnType.SERVICES));
        assertNull(t.today(2).get(TxnType.WAGES));
    }

    @Test
    void sellingFloodsTheMarketAndPricesRecover() {
        Market m = new Market();
        double before = m.sellPrice("wheat", 0);
        long first = m.quoteSale("wheat", 64, 0);
        m.recordSale("wheat", 2000);
        assertTrue(m.sellPrice("wheat", 0) < before);
        assertTrue(m.quoteSale("wheat", 64, 0) < first);
        m.recover(500);
        assertEquals(before, m.sellPrice("wheat", 0), before * 0.05);
    }

    @Test
    void buyingCostsMoreThanSellingAndMerchantsNarrowTheGap() {
        Market m = new Market();
        assertTrue(m.buyPrice("bread", 0) > m.sellPrice("bread", 0));
        double gapNovice = m.buyPrice("bread", 0) - m.sellPrice("bread", 0);
        double gapMaster = m.buyPrice("bread", 10) - m.sellPrice("bread", 10);
        assertTrue(gapMaster < gapNovice);
    }

    @Test
    void skillsComeFromExperience() {
        SkillSet s = new SkillSet();
        assertEquals(0, s.level(Skill.FARMING));
        assertEquals(1, s.gain(Skill.FARMING, 60));
        assertEquals(-1, s.gain(Skill.FARMING, 1));
        assertEquals(10, Skill.level(1_000_000));
        assertTrue(Skill.speed(10) > Skill.speed(0));
    }

    @Test
    void needsDecayAndEating() {
        Needs.Params p = Needs.Params.defaults();
        Needs n = new Needs();
        n.satiety = 60;
        n.decay(10, false, false, false, p);
        assertEquals(25, n.satiety, 1e-6);
        assertTrue(n.hungry(p));
        n.eat(5, 2, p);
        assertEquals(55, n.satiety, 1e-6);
        n.energy = 50;
        n.decay(8, true, true, false, p);
        assertEquals(100, n.energy, 1e-6);
    }

    @Test
    void starvingHurts() {
        Needs.Params p = Needs.Params.defaults();
        Needs n = new Needs();
        n.satiety = 0;
        n.health = 10;
        assertTrue(n.heal(2, false, 0, p) < 0);
    }

    @Test
    void moodExplainsItself() {
        Mood.Inputs in = new Mood.Inputs();
        in.housing = 0;
        in.adult = true;
        in.employed = true;
        in.unpaid = true;
        Mood.Result r = Mood.target(in);
        assertTrue(r.factors().containsKey("Homeless"));
        assertEquals("Homeless", r.worst());
        Mood.Inputs good = new Mood.Inputs();
        good.housing = 3;
        good.adult = true;
        good.employed = true;
        good.suitsTrait = true;
        assertTrue(Mood.target(good).target() > r.target());
    }

    @Test
    void scheduleWindowsWrapPastMidnight() {
        assertTrue(GameTime.within(23000, 13500, 0));
        assertTrue(GameTime.within(500, 22000, 1000));
        assertFalse(GameTime.within(5000, 13500, 0));
        // a whole day overlaps a window by exactly its length
        assertEquals(10500, GameTime.overlap(4000, 24000, 13500, 0));
        assertEquals(500, GameTime.overlap(23500, 1000, 23000, 24000));
        assertEquals(1000, GameTime.overlap(23500, 1000, 22000, 1000));
        assertEquals("06:00", GameTime.clock(0));
        assertEquals("18:30", GameTime.clock(12500));
    }
}
