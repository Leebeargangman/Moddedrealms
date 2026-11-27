package com.colonysmp.txn;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TradeGuardTest {

    @Test
    void aDoubleClickIsRefused() {
        TradeGuard g = new TradeGuard(250);
        UUID p = UUID.randomUUID();
        assertNull(g.begin(p, 1000));
        assertNotNull(g.begin(p, 1000), "a second trade while the first is going through");
        g.end(p);
        assertNotNull(g.begin(p, 1100), "and too soon after it");
        assertNull(g.begin(p, 1300));
        g.end(p);
        assertEquals(2, g.recentRejects(p, 1300));
        assertEquals(0, g.recentRejects(p, 70_000));
    }

    @Test
    void onePlayersTradeDoesNotHoldUpAnother() {
        TradeGuard g = new TradeGuard(250);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertNull(g.begin(a, 0));
        assertNull(g.begin(b, 0));
    }

    @Test
    void auditRulesNoteButNeverJudge() {
        AuditRules r = new AuditRules(30, 20, 5000);
        assertTrue(r.afterCommit(100, 95, 3, 10).isEmpty(), "an ordinary trade");
        List<AuditRules.Finding> gain = r.afterCommit(10, 200, 1, 0);
        assertEquals(AuditRules.VALUE_GAIN, gain.get(0).code());
        assertEquals(AuditRules.RAPID_TRADES, r.afterCommit(10, 9, 31, 0).get(0).code());
        assertEquals(AuditRules.HOURLY_GAIN, r.afterCommit(10, 9, 1, 6000).get(0).code());
        assertNull(r.afterReject(5));
        assertNotNull(r.afterReject(20));
        assertNull(r.afterReject(21), "one note per flood, not one per click");
    }
}
