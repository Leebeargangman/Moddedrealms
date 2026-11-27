package com.colonysmp.txn;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Numbered schema migrations. Every database the plugin owns records which steps it has had in a
 * {@code schema_migrations} table; opening it applies the missing ones in order, each in its own transaction, so
 * a failed step leaves the database exactly as the last good step left it. A database from a newer version of
 * the plugin is refused rather than guessed at.
 */
public final class Migrations {

    /** One step: statements that take the schema from {@code version - 1} to {@code version}. */
    public record Step(int version, String name, List<String> sql) {}

    private Migrations() {}

    /** Applies the steps this database hasn't had. Returns how many were applied. */
    public static int apply(Connection conn, List<Step> steps) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at INTEGER NOT NULL)");
        }
        int have = version(conn);
        int newest = steps.isEmpty() ? 0 : steps.get(steps.size() - 1).version();
        if (have > newest) {
            throw new SQLException("this database is at schema " + have + " but this version of ColonySMP only knows up to " + newest
                    + ": update the plugin or restore a backup");
        }
        int applied = 0;
        boolean auto = conn.getAutoCommit();
        for (Step s : steps) {
            if (s.version() <= have) continue;
            if (s.version() != have + 1) throw new SQLException("migration " + s.version() + " is out of order (database is at " + have + ")");
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                for (String sql : s.sql()) st.execute(sql);
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO schema_migrations (version, name, applied_at) VALUES (?, ?, ?)")) {
                    ps.setInt(1, s.version());
                    ps.setString(2, s.name());
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new SQLException("migration " + s.version() + " (" + s.name() + ") failed: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(auto);
            }
            have = s.version();
            applied++;
        }
        return applied;
    }

    public static int version(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
