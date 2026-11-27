package com.colonysmp.core;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RemarksTest {

    private static String say(Remarks.Mind m, long seed) {
        return Remarks.say(m, new Random(seed));
    }

    @Test
    void whatStopsTheirWorkComesBeforeSmallTalk() {
        Remarks.Mind m = new Remarks.Mind();
        m.blocked = "Waiting for 6 flour (baking bread)";
        m.mood = 90;
        for (long seed = 0; seed < 20; seed++) assertTrue(say(m, seed).contains("waiting for 6 flour (baking bread)"), "they say what they're waiting for");
    }

    @Test
    void dangerOutranksEverything() {
        Remarks.Mind m = new Remarks.Mind();
        m.underAttack = true;
        m.blocked = "Waiting for seeds";
        m.starving = true;
        for (long seed = 0; seed < 20; seed++) assertFalse(say(m, seed).toLowerCase().contains("seeds"));
    }

    @Test
    void theStarvingSaySo() {
        Remarks.Mind m = new Remarks.Mind();
        m.starving = true;
        m.christmas = true;
        for (long seed = 0; seed < 20; seed++) {
            String s = say(m, seed).toLowerCase();
            assertTrue(s.contains("starv") || s.contains("eaten") || s.contains("food"), s);
        }
    }

    @Test
    void theUnpaidCountTheDays() {
        Remarks.Mind m = new Remarks.Mind();
        m.unpaidDays = 3;
        assertTrue(say(m, 1).contains("3 days"));
    }

    @Test
    void childrenDontTalkShop() {
        Remarks.Mind m = new Remarks.Mind();
        m.child = true;
        m.blocked = "No school with a teacher";
        m.unpaidDays = 2;
        m.unemployed = true;
        for (long seed = 0; seed < 30; seed++) {
            String s = say(m, seed);
            assertFalse(s.contains("paid") || s.contains("work"), s);
        }
    }

    @Test
    void theUnhappySayWhy() {
        Remarks.Mind m = new Remarks.Mind();
        m.mood = 20;
        m.worst = "Taxes";
        assertTrue(say(m, 3).contains("taxes"));
    }

    @Test
    void christmasIsMentionedInSeasonOnly() {
        Remarks.Mind in = new Remarks.Mind(), out = new Remarks.Mind();
        in.christmas = true;
        in.christmasTree = true;
        boolean merry = false;
        for (long seed = 0; seed < 60; seed++) {
            String a = say(in, seed).toLowerCase(), b = say(out, seed).toLowerCase();
            if (a.contains("christmas") || a.contains("tree") || a.contains("pine")) merry = true;
            assertFalse(b.contains("christmas"), b);
        }
        assertTrue(merry, "somebody wishes a merry Christmas");
    }

    @Test
    void thereIsAlwaysSomethingToSayAndItAddressesTheListener() {
        Remarks.Mind m = new Remarks.Mind();
        m.listener = "Lee";
        m.mood = 55;
        for (int t = 0; t < 4; t++) {
            m.timeOfDay = t;
            String s = say(m, t);
            assertNotNull(s);
            assertTrue(s.contains("Lee"), s);
        }
        assertEquals("bakery", Remarks.stripNumber("Bakery #3"));
        assertEquals("town hall", Remarks.stripNumber("Town Hall"));
    }
}
