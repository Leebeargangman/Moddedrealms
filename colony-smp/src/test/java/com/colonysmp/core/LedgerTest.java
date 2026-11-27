package com.colonysmp.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LedgerTest {

    private Ledger ledger;

    @BeforeEach
    void setUp() {
        TestGoods.setUp();
        ledger = new Ledger("t");
    }

    @Test
    void reservedGoodsAreNotAvailableToOtherTasks() {
        ledger.add("wheat", 10);
        Reservation kitchen = ledger.reserve("kitchen", "wheat", 10, 0);
        assertNotNull(kitchen);
        assertEquals(10, ledger.get("wheat"), "still in stock while reserved");
        assertEquals(0, ledger.available("wheat"));
        assertNull(ledger.reserve("bakery", "wheat", 1, 0), "the same wheat can't be reserved twice");
        assertEquals(0, ledger.take("wheat", 5), "nor taken by anyone else");
    }

    @Test
    void consumingAReservationRemovesExactlyItsGoods() {
        ledger.add("wheat", 15);
        Reservation r = ledger.reserve("kitchen", "wheat", 10, 0);
        assertTrue(ledger.consume(r.id));
        assertEquals(5, ledger.get("wheat"));
        assertEquals(5, ledger.available("wheat"));
        assertFalse(ledger.consume(r.id), "a reservation can only be consumed once");
        assertEquals(5, ledger.get("wheat"));
    }

    @Test
    void releasingGivesGoodsBack() {
        ledger.add("bread", 4);
        Reservation r = ledger.reserve("sale", "bread", 4, 0);
        assertTrue(ledger.release(r.id));
        assertEquals(4, ledger.available("bread"));
        assertEquals(0, ledger.reserved("bread"));
    }

    @Test
    void reservationsAreAllOrNothing() {
        ledger.add("wheat", 10);
        ledger.add("coal", 1);
        assertNull(ledger.reserve("bakery", Map.of("wheat", 6L, "coal", 2L), 0));
        assertEquals(10, ledger.available("wheat"), "a failed reservation holds nothing");
        assertEquals(1, ledger.available("coal"));
    }

    @Test
    void partialConsumptionClosesWhenEmpty() {
        ledger.add("wheat", 64);
        Reservation r = ledger.reserve("deliver", "wheat", 40, 0);
        assertEquals(16, ledger.consume(r.id, "wheat", 16));
        assertEquals(48, ledger.get("wheat"));
        assertEquals(24, ledger.reserved("wheat"));
        assertEquals(24, ledger.consume(r.id, "wheat", 100), "can't take more than was reserved");
        assertNull(ledger.reservation(r.id));
        assertEquals(24, ledger.get("wheat"));
        assertEquals(0, ledger.reserved("wheat"));
    }

    @Test
    void stockNeverGoesNegative() {
        ledger.add("bread", 3);
        assertEquals(3, ledger.take("bread", 10));
        assertEquals(0, ledger.get("bread"));
        assertFalse(ledger.takeExact("bread", 1));
        assertEquals(0, ledger.get("bread"));
    }

    @Test
    void capacityIsCountedInSlotsAndGranariesOnlyHoldFood() {
        ledger.setCapacity(2, 1);
        // one granary slot plus two general slots of wheat
        assertEquals(64 * 3, ledger.room("wheat"));
        // cobblestone can't use the granary
        assertEquals(64 * 2, ledger.room("cobblestone"));
        assertEquals(64, ledger.add("cobblestone", 64));
        assertEquals(64 * 2, ledger.room("wheat"));
        assertEquals(100, ledger.add("wheat", 100));
        assertEquals(28, ledger.room("wheat"), "the rest of the half-used stack");
        assertEquals(0, ledger.room("cobblestone") % 64);
        assertEquals(0, ledger.add("cobblestone", 70), "no free general slot left");
    }

    @Test
    void addReturnsWhatDidntFit() {
        ledger.setCapacity(1, 0);
        assertEquals(64, ledger.add("bread", 100));
        assertEquals(64, ledger.get("bread"));
        assertTrue(ledger.full());
    }

    @Test
    void repairShrinksImpossibleReservations() {
        ledger.add("wheat", 10);
        ledger.restore("t99", "ghost", 0, Map.of("wheat", 25L));
        assertEquals(25, ledger.reserved("wheat"));
        assertFalse(ledger.repair().isEmpty());
        assertEquals(10, ledger.reserved("wheat"));
        Reservation next = ledger.reserve("x", "wheat", 0, 0);
        assertNotEquals("t99", next.id, "restored ids aren't handed out again");
    }

    @Test
    void releaseOwnerFreesEverythingTheyHeld() {
        ledger.add("wheat", 30);
        ledger.reserve("prod:1", "wheat", 10, 0);
        ledger.reserve("prod:1", "wheat", 10, 0);
        ledger.reserve("prod:2", "wheat", 5, 0);
        assertEquals(2, ledger.releaseOwner("prod:1"));
        assertEquals(25, ledger.available("wheat"));
    }
}
