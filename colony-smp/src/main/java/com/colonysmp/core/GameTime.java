package com.colonysmp.core;

/**
 * Minecraft time for the simulation. Elapsed time uses the world's game time (which keeps counting whether or
 * not the colony's chunks are loaded, and stops while the server is down, like the world itself); the daily
 * routine uses the time of day.
 */
public final class GameTime {

    public static final long TICKS_PER_HOUR = 1000;
    public static final long TICKS_PER_DAY = 24000;

    private GameTime() {}

    public static double hours(long ticks) {
        return ticks / (double) TICKS_PER_HOUR;
    }

    public static long day(long gameTime) {
        return Math.floorDiv(gameTime, TICKS_PER_DAY);
    }

    /** Clock hour 0-23 for a time of day (tick 0 is 06:00). */
    public static int clockHour(long dayTime) {
        return (int) ((Math.floorMod(dayTime, TICKS_PER_DAY) / TICKS_PER_HOUR + 6) % 24);
    }

    /** "14:30" for a time of day. */
    public static String clock(long dayTime) {
        long t = Math.floorMod(dayTime, TICKS_PER_DAY);
        int h = clockHour(t);
        int m = (int) ((t % TICKS_PER_HOUR) * 60 / TICKS_PER_HOUR);
        return String.format("%02d:%02d", h, m);
    }

    /** Is a time of day inside a window that may wrap past midnight (from > to)? */
    public static boolean within(long dayTime, long from, long to) {
        long t = Math.floorMod(dayTime, TICKS_PER_DAY);
        from = Math.floorMod(from, TICKS_PER_DAY);
        to = Math.floorMod(to, TICKS_PER_DAY);
        if (from <= to) return t >= from && t < to;
        return t >= from || t < to;
    }

    /**
     * How many ticks of a daily window fall between two game times (whose time of day is offset so that
     * {@code dayTimeAtStart} is the time of day at {@code start}). Used to split a background step into work,
     * leisure and sleep.
     */
    public static long overlap(long dayTimeAtStart, long length, long from, long to) {
        if (length <= 0) return 0;
        long total = 0;
        long t = Math.floorMod(dayTimeAtStart, TICKS_PER_DAY);
        long left = length;
        // walk whole days first
        long fullDays = left / TICKS_PER_DAY;
        long windowLen = Math.floorMod(to - from, TICKS_PER_DAY);
        if (from == to) windowLen = 0;
        total += fullDays * windowLen;
        left -= fullDays * TICKS_PER_DAY;
        // the remainder, at most two segments of the clock
        while (left > 0) {
            long untilMidnight = TICKS_PER_DAY - t;
            long seg = Math.min(left, untilMidnight);
            total += windowOverlap(t, t + seg, from, to);
            left -= seg;
            t = 0;
        }
        return total;
    }

    /** Overlap of [a, b) (inside one day) with the daily window. */
    private static long windowOverlap(long a, long b, long from, long to) {
        from = Math.floorMod(from, TICKS_PER_DAY);
        to = Math.floorMod(to, TICKS_PER_DAY);
        if (from == to) return 0;
        if (from < to) return Math.max(0, Math.min(b, to) - Math.max(a, from));
        return Math.max(0, Math.min(b, TICKS_PER_DAY) - Math.max(a, from)) + Math.max(0, Math.min(b, to) - Math.max(a, 0));
    }
}
