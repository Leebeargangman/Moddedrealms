package com.colonysmp.txn;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The transaction journal (plugins/ColonySMP/journal.db): a write-ahead record of every exchange of items
 * between a player and the simulated world.
 *
 * <p><b>Why it exists.</b> A player's inventory lives in the server's player file; a settlement's stock lives in
 * the colony database. They are saved at different times, so a crash between the two could leave the items on
 * both sides (a duplicate) or on neither. The journal closes that gap:
 *
 * <ol>
 *   <li>Before anything is touched, the whole exchange is written here as {@link TxnState#PREPARED}, durably,
 *       together with its idempotency key (so the same reward can never be prepared twice).</li>
 *   <li>The inventory is changed, and in the same tick the transaction's sequence number is stamped on the
 *       player (in the same file as the inventory, so the two are saved together or not at all).</li>
 *   <li>The other side's books are updated and the row becomes {@link TxnState#COMMITTED} with a commit number.
 *       The colony database remembers the last commit number it contains; anything committed after that is
 *       replayed onto it at start-up.</li>
 *   <li>A row still PREPARED after a crash is settled when the player returns: if their stamp is at or past the
 *       row's sequence number the inventory change was saved, so the row is carried through; if not, the
 *       inventory change was lost with the crash, so the row is undone. Either way the items exist exactly
 *       once.</li>
 * </ol>
 *
 * <p>Writes are small, synchronous and made on the server thread: that is deliberate, because the order of the
 * steps above is the whole point. This file is separate from colonies.db so a journal write never waits behind
 * a large colony save.
 */
public final class Journal implements AutoCloseable {

    /** A journal row. */
    public record Record(long seq, String id, UUID player, String playerName, String kind, String counterparty, TxnState state,
                         List<Line> gives, List<Line> takes, String effects, String idemKey, double valueIn, double valueOut,
                         long created, long updated, long commitNo, String note) {}

    /** An entry for administrators to look at. Evidence, never a verdict. */
    public record Flag(long id, long time, UUID player, String playerName, String severity, String code, String detail, String txn) {}

    /** Per-player totals over a window, for the audit. */
    public record Totals(int committed, int rolledBack, int inDoubt, double valueIn, double valueOut) {}

    /** The idempotency key was already used: this reward or trade has been made before. */
    public static final class DuplicateException extends SQLException {
        public DuplicateException(String key) {
            super("already processed: " + key);
        }
    }

    private static final List<Migrations.Step> STEPS = List.of(
            new Migrations.Step(1, "journal, idempotency keys and audit flags", List.of(
                    "CREATE TABLE txn_journal (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, player TEXT NOT NULL, player_name TEXT NOT NULL,"
                            + " kind TEXT NOT NULL, counterparty TEXT NOT NULL, state TEXT NOT NULL, gives TEXT NOT NULL, takes TEXT NOT NULL, effects TEXT NOT NULL,"
                            + " idem TEXT, value_in REAL NOT NULL, value_out REAL NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL,"
                            + " commit_no INTEGER NOT NULL DEFAULT 0, note TEXT NOT NULL DEFAULT '')",
                    "CREATE INDEX txn_journal_player ON txn_journal (player, seq)",
                    "CREATE INDEX txn_journal_state ON txn_journal (state)",
                    "CREATE INDEX txn_journal_commit ON txn_journal (commit_no)",
                    "CREATE TABLE txn_claims (key TEXT PRIMARY KEY, txn TEXT NOT NULL, created INTEGER NOT NULL)",
                    "CREATE TABLE audit_flags (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, player TEXT NOT NULL, player_name TEXT NOT NULL,"
                            + " severity TEXT NOT NULL, code TEXT NOT NULL, detail TEXT NOT NULL, txn TEXT NOT NULL DEFAULT '')",
                    "CREATE INDEX audit_flags_player ON audit_flags (player, id)")));

    private Connection conn;
    private long nextCommit;
    private volatile String lastError;

    /** Opens (and migrates) the journal. {@code fullSync} makes every commit survive a power cut, not just a crash. */
    public synchronized void open(File file, boolean fullSync) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite driver missing from the server", e);
        }
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) throw new SQLException("can't create " + dir);
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=" + (fullSync ? "FULL" : "NORMAL"));
            st.execute("PRAGMA busy_timeout=5000");
        }
        Migrations.apply(conn, STEPS);
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(commit_no), 0) FROM txn_journal")) {
            nextCommit = (rs.next() ? rs.getLong(1) : 0) + 1;
        }
    }

    public synchronized int schemaVersion() throws SQLException {
        return Migrations.version(conn);
    }

    @Override
    public synchronized void close() {
        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {
            // closing anyway
        }
        conn = null;
    }

    public String lastError() {
        return lastError;
    }

    // ───────────── the life of a transaction ─────────────

    /**
     * Step 1: writes the transaction as PREPARED and claims its idempotency key, in one database transaction.
     * Nothing may be handed over until this has returned.
     *
     * @throws DuplicateException the key was used before (nothing was written)
     */
    public synchronized Record prepare(UUID player, String playerName, String kind, String counterparty, List<Line> gives, List<Line> takes,
                                       String effects, String idemKey, double valueIn, double valueOut, long now) throws SQLException {
        if (conn == null) throw new SQLException("the journal is closed");
        String id = UUID.randomUUID().toString();
        conn.setAutoCommit(false);
        try {
            if (idemKey != null) {
                try (PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO txn_claims (key, txn, created) VALUES (?, ?, ?)")) {
                    ps.setString(1, idemKey);
                    ps.setString(2, id);
                    ps.setLong(3, now);
                    if (ps.executeUpdate() == 0) {
                        conn.rollback();
                        throw new DuplicateException(idemKey);
                    }
                }
            }
            long seq;
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO txn_journal (id, player, player_name, kind, counterparty, state, gives, takes, effects, idem,"
                    + " value_in, value_out, created, updated) VALUES (?, ?, ?, ?, ?, 'PREPARED', ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, id);
                ps.setString(2, player.toString());
                ps.setString(3, playerName);
                ps.setString(4, kind);
                ps.setString(5, counterparty);
                ps.setString(6, Line.format(gives));
                ps.setString(7, Line.format(takes));
                ps.setString(8, effects == null ? "" : effects);
                ps.setString(9, idemKey);
                ps.setDouble(10, valueIn);
                ps.setDouble(11, valueOut);
                ps.setLong(12, now);
                ps.setLong(13, now);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (!rs.next()) throw new SQLException("no sequence number for the transaction");
                    seq = rs.getLong(1);
                }
            }
            conn.commit();
            return new Record(seq, id, player, playerName, kind, counterparty, TxnState.PREPARED, List.copyOf(gives), List.copyOf(takes),
                    effects == null ? "" : effects, idemKey, valueIn, valueOut, now, now, 0, "");
        } catch (SQLException e) {
            lastError = e.getMessage();
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // the original error is the one that matters
            }
            throw e;
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
                // reported by the next call
            }
        }
    }

    /** Step 3: the exchange is complete. Returns its commit number (the order the books were updated in). */
    public synchronized long commit(String id, String note, long now) throws SQLException {
        if (conn == null) throw new SQLException("the journal is closed");
        long no = nextCommit;
        try (PreparedStatement ps = conn.prepareStatement("UPDATE txn_journal SET state = 'COMMITTED', commit_no = ?, updated = ?, note = ? WHERE id = ? AND state = 'PREPARED'")) {
            ps.setLong(1, no);
            ps.setLong(2, now);
            ps.setString(3, note == null ? "" : note);
            ps.setString(4, id);
            if (ps.executeUpdate() != 1) throw new SQLException("transaction " + id + " isn't waiting to be committed");
        } catch (SQLException e) {
            lastError = e.getMessage();
            throw e;
        }
        nextCommit = no + 1;
        return no;
    }

    /** The exchange didn't happen. Its idempotency key is given back, so an honest retry isn't refused. */
    public synchronized void rollback(String id, String note, long now) throws SQLException {
        if (conn == null) throw new SQLException("the journal is closed");
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE txn_journal SET state = 'ROLLED_BACK', updated = ?, note = ? WHERE id = ? AND state = 'PREPARED'")) {
                ps.setLong(1, now);
                ps.setString(2, note == null ? "" : note);
                ps.setString(3, id);
                if (ps.executeUpdate() != 1) throw new SQLException("transaction " + id + " isn't waiting to be rolled back");
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM txn_claims WHERE txn = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            lastError = e.getMessage();
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // the original error is the one that matters
            }
            throw e;
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
                // reported by the next call
            }
        }
    }

    public synchronized boolean claimed(String idemKey) {
        if (conn == null || idemKey == null) return false;
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM txn_claims WHERE key = ?")) {
            ps.setString(1, idemKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            lastError = e.getMessage();
            return true; // when in doubt, don't pay twice
        }
    }

    // ───────────── reading ─────────────

    private static final String COLS = "seq, id, player, player_name, kind, counterparty, state, gives, takes, effects, idem, value_in, value_out, created, updated, commit_no, note";

    private static Record read(ResultSet rs) throws SQLException {
        return new Record(rs.getLong(1), rs.getString(2), UUID.fromString(rs.getString(3)), rs.getString(4), rs.getString(5), rs.getString(6),
                TxnState.parse(rs.getString(7)), Line.parse(rs.getString(8)), Line.parse(rs.getString(9)), rs.getString(10), rs.getString(11),
                rs.getDouble(12), rs.getDouble(13), rs.getLong(14), rs.getLong(15), rs.getLong(16), rs.getString(17));
    }

    private synchronized List<Record> query(String where, Object... args) {
        List<Record> out = new ArrayList<>();
        if (conn == null) return out;
        try (PreparedStatement ps = conn.prepareStatement("SELECT " + COLS + " FROM txn_journal " + where)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(read(rs));
            }
        } catch (SQLException e) {
            lastError = e.getMessage();
        }
        return out;
    }

    /** Rows the server stopped in the middle of, oldest first. */
    public List<Record> inDoubt() {
        return query("WHERE state = 'PREPARED' ORDER BY seq");
    }

    public List<Record> inDoubt(UUID player) {
        return query("WHERE state = 'PREPARED' AND player = ? ORDER BY seq", player.toString());
    }

    /** Committed rows the colony database doesn't contain yet, in the order they were committed. */
    public List<Record> committedAfter(long commitNo) {
        return query("WHERE state = 'COMMITTED' AND commit_no > ? ORDER BY commit_no", commitNo);
    }

    public Record get(String idOrPrefix) {
        List<Record> r = query("WHERE id = ?", idOrPrefix);
        if (r.isEmpty() && idOrPrefix.length() >= 6) r = query("WHERE id LIKE ? ORDER BY seq DESC LIMIT 2", idOrPrefix.replace("%", "") + "%");
        return r.size() == 1 ? r.get(0) : null;
    }

    public List<Record> recent(UUID player, int limit) {
        return player == null ? query("ORDER BY seq DESC LIMIT ?", limit) : query("WHERE player = ? ORDER BY seq DESC LIMIT ?", player.toString(), limit);
    }

    public synchronized long lastCommit() {
        return nextCommit - 1;
    }

    public synchronized Totals totals(UUID player, long since) {
        if (conn == null) return new Totals(0, 0, 0, 0, 0);
        String sql = "SELECT state, COUNT(*), COALESCE(SUM(value_in), 0), COALESCE(SUM(value_out), 0) FROM txn_journal WHERE created >= ?"
                + (player == null ? "" : " AND player = ?") + " GROUP BY state";
        int c = 0, r = 0, d = 0;
        double in = 0, out = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, since);
            if (player != null) ps.setString(2, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    switch (TxnState.parse(rs.getString(1))) {
                        case COMMITTED -> {
                            c = rs.getInt(2);
                            in = rs.getDouble(3);
                            out = rs.getDouble(4);
                        }
                        case PREPARED -> d = rs.getInt(2);
                        default -> r = rs.getInt(2);
                    }
                }
            }
        } catch (SQLException e) {
            lastError = e.getMessage();
        }
        return new Totals(c, r, d, in, out);
    }

    // ───────────── audit flags ─────────────

    public synchronized void flag(UUID player, String playerName, String severity, String code, String detail, String txn, long now) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO audit_flags (time, player, player_name, severity, code, detail, txn) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, now);
            ps.setString(2, player == null ? "" : player.toString());
            ps.setString(3, playerName == null ? "" : playerName);
            ps.setString(4, severity);
            ps.setString(5, code);
            ps.setString(6, detail);
            ps.setString(7, txn == null ? "" : txn);
            ps.executeUpdate();
        } catch (SQLException e) {
            lastError = e.getMessage();
        }
    }

    public synchronized List<Flag> flags(UUID player, int limit) {
        List<Flag> out = new ArrayList<>();
        if (conn == null) return out;
        String sql = "SELECT id, time, player, player_name, severity, code, detail, txn FROM audit_flags" + (player == null ? "" : " WHERE player = ?") + " ORDER BY id DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            if (player != null) ps.setString(i++, player.toString());
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String pl = rs.getString(3);
                    out.add(new Flag(rs.getLong(1), rs.getLong(2), pl.isEmpty() ? null : UUID.fromString(pl), rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getString(7), rs.getString(8)));
                }
            }
        } catch (SQLException e) {
            lastError = e.getMessage();
        }
        return out;
    }

    public synchronized int flagCount(UUID player, String code, long since) {
        if (conn == null) return 0;
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM audit_flags WHERE player = ? AND code = ? AND time >= ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, code);
            ps.setLong(3, since);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            lastError = e.getMessage();
            return 0;
        }
    }

    /** Old finished rows and flags are trimmed so the file doesn't grow for ever (in-doubt rows are never trimmed). */
    public synchronized int prune(long before) {
        if (conn == null) return 0;
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM txn_journal WHERE state != 'PREPARED' AND updated < ?");
             PreparedStatement pf = conn.prepareStatement("DELETE FROM audit_flags WHERE time < ?")) {
            ps.setLong(1, before);
            pf.setLong(1, before);
            return ps.executeUpdate() + pf.executeUpdate();
        } catch (SQLException e) {
            lastError = e.getMessage();
            return 0;
        }
    }
}
