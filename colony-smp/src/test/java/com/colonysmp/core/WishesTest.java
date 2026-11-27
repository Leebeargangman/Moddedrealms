package com.colonysmp.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WishesTest {

    @Test
    void childrenAskForChildishThings() {
        List<Wishes.Wish> pool = Wishes.pool("FARMER", true, false);
        assertFalse(pool.isEmpty());
        for (Wishes.Wish w : pool) assertNotEquals("bone_meal", w.good(), "a child has no trade yet");
    }

    @Test
    void workersAskForTheirTradesWants() {
        List<Wishes.Wish> pool = Wishes.pool("FARMER", false, false);
        assertTrue(pool.stream().anyMatch(w -> w.good().equals("bone_meal")));
        assertTrue(pool.stream().anyMatch(w -> w.good().equals("bread")), "and for the things anyone wants");
        long own = pool.stream().filter(w -> w.good().equals("bone_meal")).count();
        assertEquals(2, own, "their trade is on their mind twice as much");
    }

    @Test
    void anUnknownJobStillWantsSomething() {
        assertFalse(Wishes.pool("UNEMPLOYED", false, false).isEmpty());
        assertFalse(Wishes.pool("NO_SUCH_JOB", false, true).isEmpty());
    }

    @Test
    void theTableIsSound() {
        for (Wishes.Wish w : Wishes.all()) {
            assertTrue(w.min() >= 1 && w.max() >= w.min(), w.good() + " amounts");
            assertFalse(w.reason().isBlank(), w.good() + " has a reason");
            assertEquals(w.good(), w.good().toLowerCase(), "goods are keyed in lower case");
        }
    }

    @Test
    void theRewardIsFairAndNeverMoreThanTheyHave() {
        assertEquals(18, Wishes.reward(1.5, 8, 100), "half again the value");
        assertEquals(5, Wishes.reward(1.5, 8, 5), "capped by the purse");
        assertEquals(0, Wishes.reward(1.5, 8, 0), "thanks only");
        assertEquals(1, Wishes.reward(0.1, 1, 50), "never less than a coin if they can pay");
    }

    @Test
    void christmasHasItsOwnWishes() {
        assertTrue(Wishes.festive(false).stream().anyMatch(w -> w.good().equals("cookie")));
        assertTrue(Wishes.festive(true).stream().anyMatch(w -> w.good().equals("snowball")));
        assertFalse(Wishes.festive(false).stream().anyMatch(w -> w.good().equals("snowball")), "snowballs are the children's idea");
        // a fresh list each time: the caller may pick it apart
        Wishes.festive(false).clear();
        assertFalse(Wishes.festive(false).isEmpty());
    }
}
