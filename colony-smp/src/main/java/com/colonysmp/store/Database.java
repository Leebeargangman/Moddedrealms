package com.colonysmp.store;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The colony database (plugins/ColonySMP/colonies.db, SQLite). The live state is written as one complete
 * snapshot in a single transaction on a background thread, so a crash or a failed write never leaves half a
 * colony on disk; transactions and events are appended. The file is backed up at every start, and the schema
 * is versioned so later releases can migrate it.
 */
public final class Database {

    /**
     * Schema history: 1 was ColonySMP 1.x; 2 is the document tables of 2.0-2.2; 3 (2.3) adds {@code civ_docs} for
     * the living world. Every step only adds tables, so 2.x databases open as they are and are backed up first.
     * The transaction journal is a separate file with its own numbered migrations (see {@code txn.Journal}).
     */
    public static final int SCHEMA = 3;

    /** Rows from a version 1 database (the communist edition), for the importer. */
    public record V1(List<String[]> colonies, List<String[]> citizens, List<Object[]> storage) {}

    private static final String[] DOC_TABLES = {"colonies", "citizens", "buildings", "squads", "orders", "blueprints", "relations",
            "proposals", "contracts", "shipments", "wars"};

    private final Plugin plugin;
    private Connection conn;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ColonySMP-DB");
        t.setDaemon(true);
        return t;
    });

    // health, for /colony admin db
    private volatile String lastError;
    private volatile long lastSaveAt, lastSaveMillis, saves, failures, lastRows;
    private volatile boolean v1Pending;

    public Database(Plugin plugin) {
        this.plugin = plugin;
    }

    public void open(int keepBackups) throws SQLException {
        File dir = plugin.getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) throw new SQLException("can't create " + dir);
        File file = new File(dir, "colonies.db");
        if (file.exists()) backup(file, keepBackups);
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite driver missing from the server", e);
        }
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=OFF");
            st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)");
        }
        int version = schemaVersion();
        if (version == 1) migrateFromV1();
        else if (version > SCHEMA) {
            throw new SQLException("colonies.db was written by a newer ColonySMP (schema " + version + "); this version understands " + SCHEMA
                    + ". Update the plugin or restore a backup from plugins/ColonySMP/backups.");
        }
        createTables();
        setMeta("schema", String.valueOf(SCHEMA));
        v1Pending = tableExists("v1_colonies") && !"1".equals(meta("v1_imported"));
    }

    private void backup(File file, int keep) {
        if (keep <= 0) return;
        File dir = new File(plugin.getDataFolder(), "backups");
        if (!dir.exists() && !dir.mkdirs()) return;
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        try {
            Files.copy(file.toPath(), new File(dir, "colonies-" + stamp + ".db").toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not back up colonies.db: " + e.getMessage());
            return;
        }
        File[] all = dir.listFiles((d, n) -> n.startsWith("colonies-") && n.endsWith(".db"));
        if (all == null) return;
        Arrays.sort(all, Comparator.comparing(File::getName));
        for (int i = 0; i < all.length - keep; i++) {
            if (!all[i].delete()) plugin.getLogger().warning("Could not delete old backup " + all[i].getName());
        }
    }

    private int schemaVersion() throws SQLException {
        String v = meta("schema");
        if (v == null) return tableExists("storage") && tableExists("colonies") ? 1 : 0;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean tableExists(String name) throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        try (ResultSet rs = md.getTables(null, null, name, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    /** The old tables are kept under new names for the importer and as a record. */
    private void migrateFromV1() throws SQLException {
        plugin.getLogger().info("Found a ColonySMP 1.x database: keeping it for import (tables renamed to v1_*).");
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            for (String t : new String[]{"colonies", "citizens", "storage", "wars"}) {
                if (tableExists(t) && !tableExists("v1_" + t)) st.execute("ALTER TABLE " + t + " RENAME TO v1_" + t);
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private void createTables() throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (String t : DOC_TABLES) {
                st.execute("CREATE TABLE IF NOT EXISTS " + t + " (colony TEXT NOT NULL DEFAULT '', id TEXT NOT NULL, data TEXT NOT NULL, PRIMARY KEY (colony, id))");
            }
            st.execute("CREATE TABLE IF NOT EXISTS members (colony TEXT NOT NULL, player TEXT NOT NULL, role TEXT NOT NULL, PRIMARY KEY (colony, player))");
            st.execute("CREATE TABLE IF NOT EXISTS claims (world TEXT NOT NULL, cx INTEGER NOT NULL, cz INTEGER NOT NULL, colony TEXT NOT NULL, PRIMARY KEY (world, cx, cz))");
            st.execute("CREATE TABLE IF NOT EXISTS stock (colony TEXT NOT NULL, owner TEXT NOT NULL, good TEXT NOT NULL, qty INTEGER NOT NULL, PRIMARY KEY (colony, owner, good))");
            st.execute("CREATE TABLE IF NOT EXISTS reservations (colony TEXT NOT NULL, ledger TEXT NOT NULL, id TEXT NOT NULL, holder TEXT NOT NULL, created INTEGER NOT NULL, goods TEXT NOT NULL, PRIMARY KEY (colony, ledger, id))");
            st.execute("CREATE TABLE IF NOT EXISTS equipment (colony TEXT NOT NULL, slot INTEGER NOT NULL, item BLOB NOT NULL, PRIMARY KEY (colony, slot))");
            st.execute("CREATE TABLE IF NOT EXISTS transactions (colony TEXT NOT NULL, id INTEGER NOT NULL, time INTEGER NOT NULL, day INTEGER NOT NULL, type TEXT NOT NULL, amount INTEGER NOT NULL, balance INTEGER NOT NULL, memo TEXT, PRIMARY KEY (colony, id))");
            st.execute("CREATE TABLE IF NOT EXISTS events (colony TEXT NOT NULL, id INTEGER NOT NULL, time INTEGER NOT NULL, day INTEGER NOT NULL, type TEXT NOT NULL, message TEXT NOT NULL, PRIMARY KEY (colony, id))");
            st.execute("CREATE TABLE IF NOT EXISTS market (good TEXT PRIMARY KEY, pressure REAL NOT NULL)");
            // schema 3: the living world (civilisations, settlements with their stores, caravans, contracts, wars)
            st.execute("CREATE TABLE IF NOT EXISTS civ_docs (kind TEXT NOT NULL, id TEXT NOT NULL, data TEXT NOT NULL, PRIMARY KEY (kind, id))");
        }
    }

    private String meta(String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void setMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    // ───────────── loading ─────────────

    public Snapshot load(int recentTxns, int recentEvents) throws SQLException {
        Snapshot s = new Snapshot();
        try (Statement st = conn.createStatement()) {
            readDocs(st, "colonies", s.colonies);
            readDocs(st, "citizens", s.citizens);
            readDocs(st, "buildings", s.buildings);
            readDocs(st, "squads", s.squads);
            readDocs(st, "orders", s.orders);
            readDocs(st, "blueprints", s.blueprints);
            readDocs(st, "relations", s.relations);
            readDocs(st, "proposals", s.proposals);
            readDocs(st, "contracts", s.contracts);
            readDocs(st, "shipments", s.shipments);
            readDocs(st, "wars", s.wars);
            try (ResultSet rs = st.executeQuery("SELECT colony, player, role FROM members")) {
                while (rs.next()) s.members.add(new Snapshot.Member(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
            try (ResultSet rs = st.executeQuery("SELECT world, cx, cz, colony FROM claims")) {
                while (rs.next()) s.claims.add(new Snapshot.Claim(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getString(4)));
            }
            try (ResultSet rs = st.executeQuery("SELECT colony, owner, good, qty FROM stock")) {
                while (rs.next()) s.stock.add(new Snapshot.Stock(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
            }
            try (ResultSet rs = st.executeQuery("SELECT colony, ledger, id, holder, created, goods FROM reservations")) {
                while (rs.next()) s.holds.add(new Snapshot.Hold(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6)));
            }
            try (ResultSet rs = st.executeQuery("SELECT colony, slot, item FROM equipment ORDER BY colony, slot")) {
                while (rs.next()) s.equipment.add(new Snapshot.Item(rs.getString(1), rs.getInt(2), rs.getBytes(3)));
            }
            try (ResultSet rs = st.executeQuery("SELECT good, pressure FROM market")) {
                while (rs.next()) s.market.put(rs.getString(1), rs.getDouble(2));
            }
            try (ResultSet rs = st.executeQuery("SELECT kind, id, data FROM civ_docs")) {
                while (rs.next()) s.civDocs.add(new Snapshot.CivDoc(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        }
        // only the recent end of the logs is kept in memory
        try (PreparedStatement ps = conn.prepareStatement("SELECT colony, id, time, day, type, amount, balance, memo FROM transactions t WHERE id > "
                + "(SELECT COALESCE(MAX(id), 0) FROM transactions u WHERE u.colony = t.colony) - ? ORDER BY colony, id DESC")) {
            ps.setInt(1, recentTxns);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    s.txns.add(new Snapshot.TxnRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getString(8)));
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT colony, id, time, day, type, message FROM events e WHERE id > "
                + "(SELECT COALESCE(MAX(id), 0) FROM events f WHERE f.colony = e.colony) - ? ORDER BY colony, id DESC")) {
            ps.setInt(1, recentEvents);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) s.events.add(new Snapshot.EventRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5), rs.getString(6)));
            }
        }
        return s;
    }

    private static void readDocs(Statement st, String table, List<Snapshot.Row> out) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT colony, id, data FROM " + table)) {
            while (rs.next()) out.add(new Snapshot.Row(rs.getString(1), rs.getString(2), rs.getString(3)));
        }
    }

    /** A version 1 database waiting to be imported, or null. */
    public V1 loadV1() throws SQLException {
        if (!v1Pending) return null;
        List<String[]> cols = new ArrayList<>(), cits = new ArrayList<>();
        List<Object[]> store = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT id, data FROM v1_colonies")) {
                while (rs.next()) cols.add(new String[]{rs.getString(1), rs.getString(2)});
            }
            if (tableExists("v1_citizens")) {
                try (ResultSet rs = st.executeQuery("SELECT id, colony, data FROM v1_citizens")) {
                    while (rs.next()) cits.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
                }
            }
            if (tableExists("v1_storage")) {
                try (ResultSet rs = st.executeQuery("SELECT colony, item FROM v1_storage")) {
                    while (rs.next()) store.add(new Object[]{rs.getString(1), rs.getBytes(2)});
                }
            }
        }
        return new V1(cols, cits, store);
    }

    /** Marks the version 1 data as imported (called once the imported colonies were saved). */
    public void v1Imported() {
        writer.submit(() -> {
            try {
                synchronized (this) {
                    if (conn != null) setMeta("v1_imported", "1");
                }
                v1Pending = false;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Could not mark the 1.x data as imported", e);
            }
        });
    }

    // ───────────── saving ─────────────

    /** Queues a save; the callback runs (on the database thread) with whether it worked. */
    public void saveAsync(Snapshot snap, Consumer<Boolean> done) {
        if (writer.isShutdown()) {
            boolean ok = write(snap);
            if (done != null) done.accept(ok);
            return;
        }
        writer.submit(() -> {
            boolean ok = write(snap);
            if (done != null) done.accept(ok);
        });
    }

    /** Waits for queued writes, then writes this snapshot on the calling thread (shutdown). */
    public boolean saveNow(Snapshot snap) {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(30, TimeUnit.SECONDS)) plugin.getLogger().warning("Database writer didn't finish in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return write(snap);
    }

    private synchronized boolean write(Snapshot s) {
        if (conn == null) return false;
        long start = System.nanoTime();
        try {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                for (String t : DOC_TABLES) st.executeUpdate("DELETE FROM " + t);
                for (String t : new String[]{"members", "claims", "stock", "reservations", "equipment", "market", "civ_docs"}) st.executeUpdate("DELETE FROM " + t);
            }
            docs("colonies", s.colonies);
            docs("citizens", s.citizens);
            docs("buildings", s.buildings);
            docs("squads", s.squads);
            docs("orders", s.orders);
            docs("blueprints", s.blueprints);
            docs("relations", s.relations);
            docs("proposals", s.proposals);
            docs("contracts", s.contracts);
            docs("shipments", s.shipments);
            docs("wars", s.wars);
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO members (colony, player, role) VALUES (?, ?, ?)")) {
                for (Snapshot.Member m : s.members) {
                    ps.setString(1, m.colony());
                    ps.setString(2, m.player());
                    ps.setString(3, m.role());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO claims (world, cx, cz, colony) VALUES (?, ?, ?, ?)")) {
                for (Snapshot.Claim c : s.claims) {
                    ps.setString(1, c.world());
                    ps.setInt(2, c.cx());
                    ps.setInt(3, c.cz());
                    ps.setString(4, c.colony());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO stock (colony, owner, good, qty) VALUES (?, ?, ?, ?)")) {
                for (Snapshot.Stock r : s.stock) {
                    ps.setString(1, r.colony());
                    ps.setString(2, r.owner());
                    ps.setString(3, r.good());
                    ps.setLong(4, r.qty());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO reservations (colony, ledger, id, holder, created, goods) VALUES (?, ?, ?, ?, ?, ?)")) {
                for (Snapshot.Hold h : s.holds) {
                    ps.setString(1, h.colony());
                    ps.setString(2, h.ledger());
                    ps.setString(3, h.id());
                    ps.setString(4, h.holder());
                    ps.setLong(5, h.created());
                    ps.setString(6, h.goods());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO equipment (colony, slot, item) VALUES (?, ?, ?)")) {
                for (Snapshot.Item it : s.equipment) {
                    ps.setString(1, it.colony());
                    ps.setInt(2, it.slot());
                    ps.setBytes(3, it.item());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO market (good, pressure) VALUES (?, ?)")) {
                for (var e : s.market.entrySet()) {
                    ps.setString(1, e.getKey());
                    ps.setDouble(2, e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO civ_docs (kind, id, data) VALUES (?, ?, ?)")) {
                for (Snapshot.CivDoc c : s.civDocs) {
                    ps.setString(1, c.kind());
                    ps.setString(2, c.id());
                    ps.setString(3, c.data());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO transactions (colony, id, time, day, type, amount, balance, memo) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (Snapshot.TxnRow t : s.txns) {
                    ps.setString(1, t.colony());
                    ps.setLong(2, t.id());
                    ps.setLong(3, t.time());
                    ps.setLong(4, t.day());
                    ps.setString(5, t.type());
                    ps.setLong(6, t.amount());
                    ps.setLong(7, t.balance());
                    ps.setString(8, t.memo());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO events (colony, id, time, day, type, message) VALUES (?, ?, ?, ?, ?, ?)")) {
                for (Snapshot.EventRow e : s.events) {
                    ps.setString(1, e.colony());
                    ps.setLong(2, e.id());
                    ps.setLong(3, e.time());
                    ps.setLong(4, e.day());
                    ps.setString(5, e.type());
                    ps.setString(6, e.message());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            // logs of dissolved colonies go; long logs are trimmed
            if (!s.colonyIds.isEmpty()) {
                String in = String.join(",", s.colonyIds.stream().map(id -> "'" + id.replace("'", "''") + "'").toList());
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("DELETE FROM transactions WHERE colony NOT IN (" + in + ")");
                    st.executeUpdate("DELETE FROM events WHERE colony NOT IN (" + in + ")");
                    st.executeUpdate("DELETE FROM events WHERE id <= (SELECT MAX(id) FROM events f WHERE f.colony = events.colony) - 2000");
                    st.executeUpdate("DELETE FROM transactions WHERE id <= (SELECT MAX(id) FROM transactions u WHERE u.colony = transactions.colony) - 5000");
                }
            } else {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("DELETE FROM transactions");
                    st.executeUpdate("DELETE FROM events");
                }
            }
            conn.commit();
            conn.setAutoCommit(true);
            saves++;
            lastSaveAt = System.currentTimeMillis();
            lastSaveMillis = (System.nanoTime() - start) / 1_000_000;
            lastRows = s.size();
            lastError = null;
            return true;
        } catch (SQLException e) {
            failures++;
            lastError = e.getMessage();
            plugin.getLogger().log(Level.SEVERE, "Saving colonies failed (nothing was changed on disk)", e);
            try {
                conn.rollback();
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
                // the connection is gone; the next save reports it
            }
            return false;
        }
    }

    private void docs(String table, List<Snapshot.Row> rows) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO " + table + " (colony, id, data) VALUES (?, ?, ?)")) {
            for (Snapshot.Row r : rows) {
                ps.setString(1, r.colony() == null ? "" : r.colony());
                ps.setString(2, r.id());
                ps.setString(3, r.data());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Older events of a colony, for the history menu. */
    public List<Snapshot.EventRow> events(String colony, long beforeId, int limit) {
        List<Snapshot.EventRow> out = new ArrayList<>();
        synchronized (this) {
            if (conn == null) return out;
            try (PreparedStatement ps = conn.prepareStatement("SELECT colony, id, time, day, type, message FROM events WHERE colony = ? AND id < ? ORDER BY id DESC LIMIT ?")) {
                ps.setString(1, colony);
                ps.setLong(2, beforeId);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Snapshot.EventRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5), rs.getString(6)));
                }
            } catch (SQLException e) {
                lastError = e.getMessage();
            }
        }
        return out;
    }

    public void close() {
        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {
            // closing anyway
        }
        conn = null;
    }

    // ───────────── health ─────────────

    public String lastError() {
        return lastError;
    }

    public long lastSaveAt() {
        return lastSaveAt;
    }

    public long lastSaveMillis() {
        return lastSaveMillis;
    }

    public long saves() {
        return saves;
    }

    public long failures() {
        return failures;
    }

    public long lastRows() {
        return lastRows;
    }
}
