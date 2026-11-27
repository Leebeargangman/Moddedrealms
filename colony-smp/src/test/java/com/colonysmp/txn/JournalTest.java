package com.colonysmp.txn;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The journal against a real SQLite file: the life of a transaction, crashes in the middle of one, and replay. */
class JournalTest {

    @TempDir
    Path dir;
    private File file;
    private Journal j;
    private final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();

    @BeforeEach
    void open() throws SQLException {
        file = dir.resolve("journal.db").toFile();
        j = new Journal();
        j.open(file, false);
    }

    @AfterEach
    void close() {
        j.close();
    }

    private Journal.Record prepare(UUID who, String idem) throws SQLException {
        return j.prepare(who, "Alice", "BARTER", "s1", List.of(new Line("iron_ingot", 12)), List.of(new Line("bread", 30)), "rep:v1:1", idem, 102, 96, 1000);
    }

    /** What a crash does: the process is gone, the file is whatever was written. */
    private void crashAndRestart() throws SQLException {
        j.close();
        j = new Journal();
        j.open(file, false);
    }

    @Test
    void aTransactionIsPreparedThenCommitted() throws SQLException {
        Journal.Record r = prepare(alice, null);
        assertEquals(TxnState.PREPARED, r.state());
        assertTrue(r.seq() > 0);
        assertEquals(1, j.inDoubt().size());
        long no = j.commit(r.id(), "", 2000);
        assertEquals(1, no);
        assertTrue(j.inDoubt().isEmpty());
        Journal.Record back = j.get(r.id());
        assertEquals(TxnState.COMMITTED, back.state());
        assertEquals(List.of(new Line("iron_ingot", 12)), back.gives());
        assertEquals(List.of(new Line("bread", 30)), back.takes());
        assertEquals("rep:v1:1", back.effects());
    }

    @Test
    void sequenceAndCommitNumbersOnlyEverGoUp() throws SQLException {
        long lastSeq = 0, lastCommit = 0;
        for (int i = 0; i < 20; i++) {
            Journal.Record r = prepare(i % 2 == 0 ? alice : bob, null);
            assertTrue(r.seq() > lastSeq);
            lastSeq = r.seq();
            long no = j.commit(r.id(), "", 1);
            assertTrue(no > lastCommit);
            lastCommit = no;
        }
        crashAndRestart();
        Journal.Record r = prepare(alice, null);
        assertTrue(r.seq() > lastSeq, "a sequence number is never used twice, even after a restart");
        assertTrue(j.commit(r.id(), "", 1) > lastCommit);
    }

    @Test
    void aRewardCanOnlyBePreparedOnce() throws SQLException {
        Journal.Record first = prepare(alice, "contract:k7");
        assertThrows(Journal.DuplicateException.class, () -> prepare(alice, "contract:k7"));
        assertThrows(Journal.DuplicateException.class, () -> prepare(bob, "contract:k7"), "not by somebody else either");
        j.commit(first.id(), "", 1);
        assertThrows(Journal.DuplicateException.class, () -> prepare(alice, "contract:k7"), "nor after it was paid");
        crashAndRestart();
        assertThrows(Journal.DuplicateException.class, () -> prepare(alice, "contract:k7"), "nor after a restart");
        assertEquals(1, j.recent(alice, 50).size(), "the refused attempts wrote nothing");
    }

    @Test
    void aRolledBackRewardCanBeTriedAgain() throws SQLException {
        Journal.Record first = prepare(alice, "contract:k9");
        j.rollback(first.id(), "the reward didn't fit", 5);
        assertFalse(j.claimed("contract:k9"));
        Journal.Record again = prepare(alice, "contract:k9");
        j.commit(again.id(), "", 6);
        assertTrue(j.claimed("contract:k9"));
    }

    @Test
    void aFinishedTransactionCannotBeFinishedAgain() throws SQLException {
        Journal.Record r = prepare(alice, null);
        j.commit(r.id(), "", 1);
        assertThrows(SQLException.class, () -> j.commit(r.id(), "", 2), "committed twice");
        assertThrows(SQLException.class, () -> j.rollback(r.id(), "", 2), "undone after it was done");
        Journal.Record s = prepare(alice, null);
        j.rollback(s.id(), "", 1);
        assertThrows(SQLException.class, () -> j.commit(s.id(), "", 2), "done after it was undone");
        assertEquals(1, j.lastCommit());
    }

    @Test
    void aCrashBetweenPrepareAndCommitLeavesTheRowInDoubt() throws SQLException {
        Journal.Record done = prepare(alice, null);
        j.commit(done.id(), "", 1);
        Journal.Record cut = prepare(alice, "contract:k1");
        crashAndRestart();
        List<Journal.Record> doubt = j.inDoubt();
        assertEquals(1, doubt.size());
        assertEquals(cut.id(), doubt.get(0).id());
        assertEquals(cut.seq(), doubt.get(0).seq());
        assertEquals(1, j.inDoubt(alice).size());
        assertTrue(j.inDoubt(bob).isEmpty());
        // the player's stamp is behind the row: their inventory never changed, so it is undone
        long stamp = done.seq();
        assertTrue(stamp < cut.seq());
        j.rollback(cut.id(), "recovered: inventory change was not saved", 9);
        assertTrue(j.inDoubt().isEmpty());
        assertFalse(j.claimed("contract:k1"), "and the contract can be handed in again");
    }

    @Test
    void aCrashAfterTheInventoryWasSavedIsCarriedThrough() throws SQLException {
        Journal.Record cut = prepare(alice, "contract:k2");
        crashAndRestart();
        // the stamp saved with the inventory is at the row's sequence: the items did change hands
        long stamp = cut.seq();
        assertTrue(stamp >= j.inDoubt(alice).get(0).seq());
        long no = j.commit(cut.id(), "recovered: inventory change was saved", 9);
        assertEquals(1, j.committedAfter(0).size());
        assertEquals(no, j.committedAfter(0).get(0).commitNo());
        assertTrue(j.claimed("contract:k2"), "and the reward is not paid a second time");
    }

    @Test
    void commitsAfterTheLastSaveAreReplayedInOrder() throws SQLException {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Journal.Record r = prepare(alice, null);
            j.commit(r.id(), "", i);
            ids.add(r.id());
        }
        // the colony database was last saved after the third commit
        long mark = 3;
        crashAndRestart();
        List<Journal.Record> replay = j.committedAfter(mark);
        assertEquals(3, replay.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(ids.get(3 + i), replay.get(i).id());
            assertEquals(4 + i, replay.get(i).commitNo());
        }
        assertTrue(j.committedAfter(6).isEmpty(), "nothing is applied twice");
    }

    @Test
    void manyThreadsPreparingAtOnceGetDistinctRowsAndOneClaim() throws Exception {
        int threads = 8, each = 25;
        Set<Long> seqs = java.util.Collections.synchronizedSet(new HashSet<>());
        AtomicInteger claims = new AtomicInteger(), refused = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                try {
                    go.await();
                    for (int i = 0; i < each; i++) {
                        seqs.add(prepare(alice, null).seq());
                        try {
                            prepare(alice, "event:festival:alice");
                            claims.incrementAndGet();
                        } catch (Journal.DuplicateException e) {
                            refused.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            ts.add(th);
            th.start();
        }
        go.countDown();
        for (Thread th : ts) th.join();
        assertEquals(threads * each, seqs.size());
        assertEquals(1, claims.get(), "the same reward asked for 200 times at once is prepared once");
        assertEquals(threads * each - 1, refused.get());
    }

    @Test
    void totalsAndFlagsAreKeptPerPlayer() throws SQLException {
        Journal.Record a = prepare(alice, null), b = prepare(bob, null), c = prepare(alice, null);
        j.commit(a.id(), "", 1);
        j.commit(b.id(), "", 1);
        j.rollback(c.id(), "", 1);
        prepare(alice, null);
        Journal.Totals t = j.totals(alice, 0);
        assertEquals(1, t.committed());
        assertEquals(1, t.rolledBack());
        assertEquals(1, t.inDoubt());
        assertEquals(102, t.valueIn(), 1e-9);
        assertEquals(2, j.totals(null, 0).committed());
        j.flag(alice, "Alice", AuditRules.WARN, AuditRules.RAPID_TRADES, "40 trades in a minute", a.id(), 5);
        j.flag(bob, "Bob", AuditRules.INFO, AuditRules.RECOVERED, "carried through", b.id(), 6);
        assertEquals(1, j.flags(alice, 10).size());
        assertEquals(2, j.flags(null, 10).size());
        assertEquals(AuditRules.RAPID_TRADES, j.flags(alice, 10).get(0).code());
        assertEquals(1, j.flagCount(alice, AuditRules.RAPID_TRADES, 0));
    }

    @Test
    void pruningNeverRemovesARowInDoubt() throws SQLException {
        Journal.Record done = prepare(alice, null);
        j.commit(done.id(), "", 10);
        Journal.Record cut = prepare(alice, null);
        j.prune(Long.MAX_VALUE);
        assertNull(j.get(done.id()));
        assertNotNull(j.get(cut.id()));
        assertEquals(1, j.inDoubt().size());
    }

    @Test
    void migrationsRunOnceAndANewerDatabaseIsRefused() throws SQLException {
        assertEquals(1, j.schemaVersion());
        crashAndRestart();
        assertEquals(1, j.schemaVersion(), "opening again applies nothing");
        j.close();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath()); Statement st = c.createStatement()) {
            st.execute("INSERT INTO schema_migrations (version, name, applied_at) VALUES (99, 'from the future', 0)");
        }
        Journal newer = new Journal();
        SQLException e = assertThrows(SQLException.class, () -> newer.open(file, false));
        assertTrue(e.getMessage().contains("99"));
        newer.close();
    }

    @Test
    void aFailedMigrationLeavesTheDatabaseAsItWas() throws SQLException {
        File f = dir.resolve("other.db").toFile();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath())) {
            List<Migrations.Step> steps = List.of(
                    new Migrations.Step(1, "one", List.of("CREATE TABLE a (x INTEGER)")),
                    new Migrations.Step(2, "two", List.of("CREATE TABLE b (x INTEGER)", "THIS IS NOT SQL")));
            assertThrows(SQLException.class, () -> Migrations.apply(c, steps));
            assertEquals(1, Migrations.version(c));
            try (Statement st = c.createStatement()) {
                st.execute("SELECT x FROM a");
                assertThrows(SQLException.class, () -> st.execute("SELECT x FROM b"), "the half of step two that worked was undone");
            }
            assertEquals(1, Migrations.apply(c, List.of(steps.get(0), new Migrations.Step(2, "two", List.of("CREATE TABLE b (x INTEGER)")))));
            assertEquals(2, Migrations.version(c));
        }
    }
}
