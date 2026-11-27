package com.colonysmp.txn;

import com.colonysmp.txn.InventoryMath.Plan;
import com.colonysmp.txn.InventoryMath.Problem;
import com.colonysmp.txn.InventoryMath.Slot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class InventoryMathTest {

    private static Slot[] inv(Slot... filled) {
        Slot[] s = new Slot[36];
        for (int i = 0; i < 36; i++) s[i] = i < filled.length ? filled[i] : Slot.empty();
        return s;
    }

    private static int stack(String key) {
        return key.endsWith("_sword") ? 1 : 64;
    }

    @Test
    void aSwapTakesAndGivesExactly() {
        Slot[] before = inv(Slot.of("iron_ingot", 40), Slot.of("iron_ingot", 10), Slot.of("coal", 5));
        Plan p = InventoryMath.plan(before, List.of(new Line("iron_ingot", 12)), List.of(new Line("bread", 100)), InventoryMathTest::stack);
        assertTrue(p.ok());
        assertEquals(38, InventoryMath.count(p.after(), "iron_ingot"));
        assertEquals(100, InventoryMath.count(p.after(), "bread"));
        assertEquals(5, InventoryMath.count(p.after(), "coal"));
        // the original is never touched
        assertEquals(50, InventoryMath.count(before, "iron_ingot"));
        assertEquals(0, InventoryMath.count(before, "bread"));
    }

    @Test
    void missingItemsStopTheWholeTrade() {
        Slot[] before = inv(Slot.of("iron_ingot", 5));
        Plan p = InventoryMath.plan(before, List.of(new Line("iron_ingot", 6)), List.of(new Line("bread", 1)), InventoryMathTest::stack);
        assertEquals(Problem.MISSING_ITEMS, p.problem());
        assertEquals(1, p.shortBy());
        assertNull(p.after());
    }

    @Test
    void aFullInventoryStopsTheWholeTradeRatherThanDroppingAnything() {
        Slot[] full = new Slot[36];
        for (int i = 0; i < 36; i++) full[i] = Slot.of("cobblestone", 64);
        Plan p = InventoryMath.plan(full, List.of(new Line("cobblestone", 10)), List.of(new Line("iron_sword", 2)), InventoryMathTest::stack);
        assertEquals(Problem.NO_ROOM, p.problem(), "ten cobblestone out of one stack frees no slot");
        // giving a whole stack frees exactly one slot: one sword fits, two don't
        assertTrue(InventoryMath.plan(full, List.of(new Line("cobblestone", 64)), List.of(new Line("iron_sword", 1)), InventoryMathTest::stack).ok());
        assertEquals(Problem.NO_ROOM, InventoryMath.plan(full, List.of(new Line("cobblestone", 64)), List.of(new Line("iron_sword", 2)), InventoryMathTest::stack).problem());
    }

    @Test
    void lockedSlotsAreNeverTakenFromOrFilled() {
        Slot[] s = new Slot[2];
        s[0] = Slot.locked(); // say, an enchanted sword
        s[1] = Slot.of("wheat", 64);
        assertEquals(0, InventoryMath.room(s, "bread", 64));
        assertEquals(Problem.NO_ROOM, InventoryMath.plan(s, List.of(), List.of(new Line("bread", 1)), InventoryMathTest::stack).problem());
        Plan p = InventoryMath.plan(s, List.of(new Line("wheat", 64)), List.of(new Line("bread", 20)), InventoryMathTest::stack);
        assertTrue(p.ok());
        assertTrue(p.after()[0].locked);
    }

    @Test
    void badQuantitiesAreRefused() {
        Slot[] s = inv(Slot.of("wheat", 64));
        assertEquals(Problem.BAD_QUANTITY, InventoryMath.plan(s, List.of(new Line("wheat", 0)), List.of(), InventoryMathTest::stack).problem());
        assertEquals(Problem.BAD_QUANTITY, InventoryMath.plan(s, List.of(new Line("wheat", -5)), List.of(), InventoryMathTest::stack).problem());
        assertEquals(Problem.BAD_QUANTITY, InventoryMath.plan(s, List.of(), List.of(new Line("bread", Long.MAX_VALUE)), InventoryMathTest::stack).problem());
        assertEquals(Problem.BAD_QUANTITY, InventoryMath.plan(s, List.of(), List.of(new Line("bread", InventoryMath.MAX_QTY + 1)), InventoryMathTest::stack).problem());
    }

    @Test
    void theSameGoodListedTwiceIsCountedOnce() {
        Slot[] s = inv(Slot.of("wheat", 10));
        // two lines of 6 need 12, not "6 is there" twice
        assertEquals(Problem.MISSING_ITEMS, InventoryMath.plan(s, List.of(new Line("wheat", 6), new Line("wheat", 6)), List.of(), InventoryMathTest::stack).problem());
    }

    @Test
    void itemsAreConservedOverThousandsOfRandomSwaps() {
        Random r = new Random(42);
        String[] goods = {"wheat", "bread", "coal", "iron_ingot", "iron_sword"};
        for (int round = 0; round < 3000; round++) {
            Slot[] s = new Slot[36];
            for (int i = 0; i < 36; i++) {
                int k = r.nextInt(8);
                s[i] = k >= goods.length ? (k == 7 ? Slot.locked() : Slot.empty()) : Slot.of(goods[k], 1 + r.nextInt(stack(goods[k])));
            }
            String give = goods[r.nextInt(goods.length)], take = goods[r.nextInt(goods.length)];
            long g = 1 + r.nextInt(200), t = 1 + r.nextInt(200);
            long haveGive = InventoryMath.count(s, give), haveTake = InventoryMath.count(s, take);
            Plan p = InventoryMath.plan(s, List.of(new Line(give, g)), List.of(new Line(take, t)), InventoryMathTest::stack);
            if (!p.ok()) continue;
            if (give.equals(take)) {
                assertEquals(haveGive - g + t, InventoryMath.count(p.after(), give));
            } else {
                assertEquals(haveGive - g, InventoryMath.count(p.after(), give));
                assertEquals(haveTake + t, InventoryMath.count(p.after(), take));
            }
            for (Slot slot : p.after()) {
                assertTrue(slot.amount >= 0);
                if (slot.key != null) assertTrue(slot.amount >= 1 && slot.amount <= stack(slot.key), "no stack over its limit");
            }
            for (String other : goods) {
                if (!other.equals(give) && !other.equals(take)) assertEquals(InventoryMath.count(s, other), InventoryMath.count(p.after(), other));
            }
        }
    }
}
