package com.colonysmp.txn;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One transaction at a time per player, and not too fast: a double-click, a held button, a macro or two
 * commands in the same tick all meet a closed door. Rejections are counted for the audit.
 */
public final class TradeGuard {

    private static final class State {
        boolean busy;
        long lastAt = Long.MIN_VALUE / 2;
        int rejects;
        long rejectWindow;
    }

    private final Map<UUID, State> states = new HashMap<>();
    private long minGapMs;

    public TradeGuard(long minGapMs) {
        this.minGapMs = Math.max(0, minGapMs);
    }

    public void configure(long minGapMs) {
        this.minGapMs = Math.max(0, minGapMs);
    }

    /** Null if the player may start a transaction now (and they are marked busy); otherwise why not. */
    public String begin(UUID player, long nowMs) {
        State s = states.computeIfAbsent(player, k -> new State());
        if (s.busy) return reject(s, nowMs, "Your last trade is still going through.");
        if (nowMs - s.lastAt < minGapMs) return reject(s, nowMs, "One trade at a time: wait a moment.");
        s.busy = true;
        s.lastAt = nowMs;
        return null;
    }

    private static String reject(State s, long nowMs, String why) {
        if (nowMs - s.rejectWindow > 60_000) {
            s.rejectWindow = nowMs;
            s.rejects = 0;
        }
        s.rejects++;
        return why;
    }

    public void end(UUID player) {
        State s = states.get(player);
        if (s != null) s.busy = false;
    }

    /** Refused attempts in the last minute (a few are normal double-clicks; a flood is a macro). */
    public int recentRejects(UUID player, long nowMs) {
        State s = states.get(player);
        return s == null || nowMs - s.rejectWindow > 60_000 ? 0 : s.rejects;
    }

    public void forget(UUID player) {
        State s = states.get(player);
        if (s != null && !s.busy) states.remove(player);
    }
}
