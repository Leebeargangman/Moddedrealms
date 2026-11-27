package com.colonysmp.txn;

import java.util.ArrayList;
import java.util.List;

/**
 * What makes a transaction worth an administrator's look. These rules only ever write a note for a human:
 * nothing here punishes, blocks or bans anybody, because every one of them can have an innocent cause.
 */
public final class AuditRules {

    /** A note for the audit log. */
    public record Finding(String severity, String code, String detail) {}

    public static final String INFO = "INFO", WARN = "WARN", ALERT = "ALERT";

    /** A player got more value than they gave (pricing should make this impossible). */
    public static final String VALUE_GAIN = "VALUE_GAIN";
    /** Far more trades in a minute than hands can make. */
    public static final String RAPID_TRADES = "RAPID_TRADES";
    /** A flood of refused attempts: a macro hammering a button. */
    public static final String REJECT_FLOOD = "REJECT_FLOOD";
    /** Net value flowing to one player over an hour. */
    public static final String HOURLY_GAIN = "HOURLY_GAIN";
    /** A transaction cut short by a crash was carried through or undone when the player returned. */
    public static final String RECOVERED = "RECOVERED";
    /** The same reward was asked for again. */
    public static final String DUPLICATE_CLAIM = "DUPLICATE_CLAIM";
    /** A transaction that could not be written as committed (the items had already moved). */
    public static final String COMMIT_DELAYED = "COMMIT_DELAYED";

    private final int tradesPerMinute;
    private final int rejectsPerMinute;
    private final double hourlyGain;

    public AuditRules(int tradesPerMinute, int rejectsPerMinute, double hourlyGain) {
        this.tradesPerMinute = Math.max(1, tradesPerMinute);
        this.rejectsPerMinute = Math.max(1, rejectsPerMinute);
        this.hourlyGain = Math.max(1, hourlyGain);
    }

    /**
     * Looks at one finished transaction with the player's recent totals.
     *
     * @param valueIn        what the player gave, at base value
     * @param valueOut       what the player received, at base value
     * @param tradesLastMin  their committed transactions in the last minute (including this one)
     * @param netLastHour    value received minus value given over the last hour (including this one)
     */
    public List<Finding> afterCommit(double valueIn, double valueOut, int tradesLastMin, double netLastHour) {
        List<Finding> out = new ArrayList<>();
        // a reward for a contract is paid on top of what was delivered, within a set margin: that is checked by its own rule
        if (valueOut > valueIn * 1.5 + 1 && valueIn > 0) {
            out.add(new Finding(ALERT, VALUE_GAIN, String.format("received %.1f for %.1f (%.0f%% of what was given)", valueOut, valueIn, 100 * valueOut / valueIn)));
        }
        if (tradesLastMin > tradesPerMinute) {
            out.add(new Finding(WARN, RAPID_TRADES, tradesLastMin + " trades in a minute (limit for a note: " + tradesPerMinute + ")"));
        }
        if (netLastHour > hourlyGain) {
            out.add(new Finding(WARN, HOURLY_GAIN, String.format("net %.0f value received in the last hour (limit for a note: %.0f)", netLastHour, hourlyGain)));
        }
        return out;
    }

    public Finding afterReject(int rejectsLastMin) {
        return rejectsLastMin == rejectsPerMinute ? new Finding(WARN, REJECT_FLOOD, rejectsLastMin + " refused attempts in a minute") : null;
    }
}
