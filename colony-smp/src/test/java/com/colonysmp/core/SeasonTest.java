package com.colonysmp.core;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.MonthDay;

import static org.junit.jupiter.api.Assertions.*;

class SeasonTest {

    private static final MonthDay FROM = MonthDay.of(12, 1), TO = MonthDay.of(1, 6);

    @Test
    void theSeasonRunsOverTheNewYear() {
        assertTrue(Season.within(MonthDay.of(12, 1), FROM, TO));
        assertTrue(Season.within(MonthDay.of(12, 25), FROM, TO));
        assertTrue(Season.within(MonthDay.of(12, 31), FROM, TO));
        assertTrue(Season.within(MonthDay.of(1, 1), FROM, TO));
        assertTrue(Season.within(MonthDay.of(1, 6), FROM, TO));
        assertFalse(Season.within(MonthDay.of(1, 7), FROM, TO));
        assertFalse(Season.within(MonthDay.of(11, 30), FROM, TO));
        assertFalse(Season.within(MonthDay.of(7, 1), FROM, TO));
    }

    @Test
    void aSeasonWithinOneYearWorksToo() {
        MonthDay a = MonthDay.of(12, 10), b = MonthDay.of(12, 26);
        assertTrue(Season.within(MonthDay.of(12, 10), a, b));
        assertTrue(Season.within(MonthDay.of(12, 26), a, b));
        assertFalse(Season.within(MonthDay.of(12, 27), a, b));
        assertFalse(Season.within(MonthDay.of(1, 2), a, b));
    }

    @Test
    void januaryStillBelongsToLastYearsChristmas() {
        assertEquals(2026, Season.year(LocalDate.of(2026, 12, 20), FROM, TO));
        assertEquals(2026, Season.year(LocalDate.of(2027, 1, 3), FROM, TO), "the tree planted in December is still this season's");
        assertEquals(2027, Season.year(LocalDate.of(2027, 12, 1), FROM, TO));
        assertEquals(2027, Season.year(LocalDate.of(2027, 6, 1), FROM, TO));
    }

    @Test
    void datesParseAndFallBack() {
        assertEquals(MonthDay.of(12, 1), Season.parse("12-01", TO));
        assertEquals(MonthDay.of(1, 6), Season.parse(" 1/6 ", FROM));
        assertEquals(FROM, Season.parse("christmas", FROM));
        assertEquals(FROM, Season.parse(null, FROM));
        assertEquals(FROM, Season.parse("13-45", FROM));
    }
}
