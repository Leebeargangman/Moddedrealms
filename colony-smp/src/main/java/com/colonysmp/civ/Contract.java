package com.colonysmp.civ;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A settlement's standing request: bring us this much of something we are short of, and we will give you that
 * much of something we have plenty of. The reward is set aside when a player takes the contract, is paid once,
 * through the transaction journal, and can never be claimed again.
 */
public final class Contract {

    public enum State { OPEN, TAKEN, DONE, EXPIRED, FAILED }

    public final String id;
    public String settlement;
    public State state = State.OPEN;
    public String want;
    public long wantQty;
    public String reward;
    public long rewardQty;
    /** The reputation with the settlement's people needed to take it, and gained by finishing it. */
    public double minReputation, reputation;
    /** 1 easy to 5 hard (how much is asked and how soon). */
    public int difficulty = 1;
    public long postedDay, expiresDay;
    public UUID takenBy;
    public String takenByName;
    public long takenDay;
    /** The settlement's reservation holding the reward. */
    public String hold;

    public Contract(String id) {
        this.id = id;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settlement", settlement);
        m.put("state", state.name());
        m.put("want", want);
        m.put("want-qty", wantQty);
        m.put("reward", reward);
        m.put("reward-qty", rewardQty);
        m.put("min-reputation", minReputation);
        m.put("reputation", reputation);
        m.put("difficulty", difficulty);
        m.put("posted", postedDay);
        m.put("expires", expiresDay);
        if (takenBy != null) {
            m.put("taken-by", takenBy.toString());
            m.put("taken-by-name", takenByName);
            m.put("taken-day", takenDay);
        }
        if (hold != null) m.put("hold", hold);
        return m;
    }

    public static Contract fromMap(String id, Map<?, ?> m) {
        Contract c = new Contract(id);
        c.settlement = Leader.str(m.get("settlement"), "");
        try {
            c.state = State.valueOf(Leader.str(m.get("state"), "OPEN"));
        } catch (IllegalArgumentException e) {
            c.state = State.EXPIRED;
        }
        c.want = Leader.str(m.get("want"), "");
        c.wantQty = (long) Leader.num(m.get("want-qty"), 0);
        c.reward = Leader.str(m.get("reward"), "");
        c.rewardQty = (long) Leader.num(m.get("reward-qty"), 0);
        c.minReputation = Leader.num(m.get("min-reputation"), 0);
        c.reputation = Leader.num(m.get("reputation"), 0);
        c.difficulty = (int) Leader.num(m.get("difficulty"), 1);
        c.postedDay = (long) Leader.num(m.get("posted"), 0);
        c.expiresDay = (long) Leader.num(m.get("expires"), 0);
        if (m.get("taken-by") != null) {
            try {
                c.takenBy = UUID.fromString(String.valueOf(m.get("taken-by")));
            } catch (IllegalArgumentException ignored) {
                // nobody
            }
            c.takenByName = Leader.str(m.get("taken-by-name"), "");
            c.takenDay = (long) Leader.num(m.get("taken-day"), 0);
        }
        c.hold = m.get("hold") == null ? null : String.valueOf(m.get("hold"));
        return c;
    }
}
