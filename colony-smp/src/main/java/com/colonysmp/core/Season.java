package com.colonysmp.core;

import java.time.LocalDate;
import java.time.MonthDay;

/** The Christmas season by the calendar: a span of days that may run over the turn of the year. */
public final class Season {

    private Season() {}

    /** Is a day within the span from one day of the year to another (both included)? */
    public static boolean within(MonthDay day, MonthDay from, MonthDay to) {
        if (from.compareTo(to) <= 0) return day.compareTo(from) >= 0 && day.compareTo(to) <= 0;
        // over the new year: December into January
        return day.compareTo(from) >= 0 || day.compareTo(to) <= 0;
    }

    /**
     * The year a season belongs to: the year it began in. The first days of January still count as last
     * year's Christmas, so a tree planted on the 20th of December is still this season's on the 3rd of January.
     */
    public static int year(LocalDate date, MonthDay from, MonthDay to) {
        if (from.compareTo(to) > 0 && MonthDay.from(date).compareTo(to) <= 0) return date.getYear() - 1;
        return date.getYear();
    }

    /** Reads "12-01" (month-day); the fallback if it isn't one. */
    public static MonthDay parse(String s, MonthDay fallback) {
        if (s == null) return fallback;
        try {
            String[] p = s.trim().split("[-/.]");
            return MonthDay.of(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()));
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
