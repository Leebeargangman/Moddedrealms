package com.colonysmp.txn;

import com.colonysmp.civ.TradeGoods;
import com.colonysmp.txn.InventoryMath.Slot;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The one door every exchange of items between a player and the simulated world goes through. Nothing else in
 * the plugin gives a player barter goods or takes them: menus and commands build a request and hand it here.
 *
 * <p>An exchange runs start to finish inside one server tick, on the server thread, in this order:
 * <ol>
 *   <li><b>Validate.</b> The player is online, alive and in survival or adventure; nothing else of theirs is in
 *       flight ({@link TradeGuard}); quantities are sane; every item to be given is in their inventory as a plain
 *       item; every item to be received fits ({@link InventoryMath}).</li>
 *   <li><b>Reserve.</b> The other side sets its goods aside ({@link Counterparty#reserve}).</li>
 *   <li><b>Prepare.</b> The exchange is written to the {@link Journal}, durably, with its idempotency key.</li>
 *   <li><b>Transfer.</b> The inventory is rewritten to the planned result and the player is stamped with the
 *       transaction's sequence number. The items given are destroyed here and the items received are created
 *       here, from fresh stacks: nothing a player handed over is ever handed back, so no metadata survives.</li>
 *   <li><b>Commit.</b> The other side's books are updated ({@link Counterparty#apply}) and the journal row is
 *       marked committed.</li>
 * </ol>
 * A refusal at any step before the transfer undoes the steps before it and changes nothing. After a crash,
 * {@link #recover} and {@link #settle} finish or undo whatever was cut short (see {@link Journal}).
 *
 * <p>Items never lie on the ground, sit in a menu or wait in a queue between a player and a settlement, so
 * closing a menu, disconnecting, dying or a hopper can't catch them half way.
 */
public final class TransactionManager {

    /** The other side of an exchange (a settlement's stores). */
    public interface Counterparty {
        /** Sets {@code takes} aside under {@code holdKey}. Returns null if done, or why it can't be. */
        String reserve(String counterparty, List<Line> takes, String holdKey);

        void release(String counterparty, String holdKey);

        /**
         * The exchange happened: remove what was set aside (or, with {@code held} false, take it from stock now),
         * add what the player gave, and carry out the record's effects. Called for live trades, for replay after a
         * crash, and when an in-doubt row is carried through.
         */
        void apply(Journal.Record record, boolean held);

        /** These books now contain every commit up to this number. */
        void mark(long commitNo);

        /** An in-doubt row whose effects are already in the books (its commit could not be written before a save). */
        boolean alreadyApplied(String txnId);

        void rememberApplied(String txnId, boolean applied);
    }

    /** What happened to a request. */
    public record Result(boolean ok, String message, Journal.Record record) {
        static Result refuse(String why) {
            return new Result(false, why, null);
        }
    }

    private final Plugin plugin;
    private final Journal journal = new Journal();
    private final TradeGuard guard = new TradeGuard(250);
    private final NamespacedKey stamp;
    private Counterparty other;
    private AuditRules rules = new AuditRules(30, 20, 5000);
    /** Rows whose effects are applied but whose commit couldn't be written; retried every second. */
    private final List<Journal.Record> unwritten = new ArrayList<>();
    private boolean open;

    public TransactionManager(Plugin plugin) {
        this.plugin = plugin;
        this.stamp = new NamespacedKey(plugin, "txn_seq");
    }

    public void open(boolean fullSync, long minGapMs, AuditRules rules) throws SQLException {
        journal.open(new File(plugin.getDataFolder(), "journal.db"), fullSync);
        guard.configure(minGapMs);
        this.rules = rules;
        open = true;
    }

    public void configure(long minGapMs, AuditRules rules) {
        guard.configure(minGapMs);
        this.rules = rules;
    }

    public void bind(Counterparty other) {
        this.other = other;
    }

    public Journal journal() {
        return journal;
    }

    public boolean ready() {
        return open && other != null;
    }

    public void close() {
        flushUnwritten();
        open = false;
        journal.close();
    }

    // ───────────── items ─────────────

    /** The trade good a stack is, or null: only plain items with no name, enchantment, damage or other data count. */
    public static String goodOf(ItemStack it) {
        if (it == null || it.getType().isAir() || it.getAmount() <= 0 || it.hasItemMeta()) return null;
        String key = it.getType().name().toLowerCase(Locale.ROOT);
        return TradeGoods.known(key) ? key : null;
    }

    public static Material material(String good) {
        return Material.matchMaterial(good.toUpperCase(Locale.ROOT));
    }

    private static int maxStack(String good) {
        Material m = material(good);
        return m == null ? 1 : m.getMaxStackSize();
    }

    private static Slot[] slots(ItemStack[] contents) {
        Slot[] out = new Slot[contents.length];
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir()) out[i] = Slot.empty();
            else {
                String g = goodOf(it);
                out[i] = g == null ? Slot.locked() : Slot.of(g, it.getAmount());
            }
        }
        return out;
    }

    /** How many plain items of a good the player carries (hotbar and main inventory; not armour or off hand). */
    public static long carried(Player p, String good) {
        return InventoryMath.count(slots(p.getInventory().getStorageContents()), good);
    }

    // ───────────── an exchange ─────────────

    /**
     * Swaps {@code gives} from the player for {@code takes} from the counterparty.
     *
     * @param idemKey null for an ordinary trade; for a reward, a key that names it ("contract:k12"), so it is paid once
     * @param effects extra bookkeeping the counterparty understands ("contract:k12"), stored with the row
     */
    public Result exchange(Player p, String kind, String counterparty, List<Line> gives, List<Line> takes, String idemKey, String effects,
                           double worthIn, double worthOut) {
        if (!ready()) return Result.refuse("Trading is closed while the ledgers are being checked.");
        long now = System.currentTimeMillis();
        UUID id = p.getUniqueId();
        String busy = guard.begin(id, now);
        if (busy != null) {
            AuditRules.Finding f = rules.afterReject(guard.recentRejects(id, now));
            if (f != null) journal.flag(id, p.getName(), f.severity(), f.code(), f.detail(), null, now);
            return Result.refuse(busy);
        }
        try {
            return run(p, kind, counterparty, Line.merge(gives), Line.merge(takes), idemKey, effects, worthIn, worthOut, now);
        } finally {
            guard.end(id);
        }
    }

    private Result run(Player p, String kind, String counterparty, List<Line> gives, List<Line> takes, String idemKey, String effects,
                       double worthIn, double worthOut, long now) {
        // 1. validate
        if (!p.isOnline() || !p.isValid() || p.isDead()) return Result.refuse("You can't trade right now.");
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) return Result.refuse("Trading is for survival and adventure mode.");
        if (!journal.inDoubt(p.getUniqueId()).isEmpty()) return Result.refuse("An earlier trade of yours is still being settled. Rejoin, or ask an administrator.");
        if (gives.isEmpty() && takes.isEmpty()) return Result.refuse("Nothing to trade.");
        for (Line l : gives) if (!TradeGoods.known(l.good()) || material(l.good()) == null) return Result.refuse("They don't deal in " + l.good() + ".");
        for (Line l : takes) if (!TradeGoods.known(l.good()) || material(l.good()) == null) return Result.refuse("They don't deal in " + l.good() + ".");
        if (idemKey != null && journal.claimed(idemKey)) {
            journal.flag(p.getUniqueId(), p.getName(), AuditRules.INFO, AuditRules.DUPLICATE_CLAIM, "asked again for " + idemKey, null, now);
            return Result.refuse("That has already been paid.");
        }
        ItemStack[] contents = p.getInventory().getStorageContents();
        InventoryMath.Plan plan = InventoryMath.plan(slots(contents), gives, takes, TransactionManager::maxStack);
        if (!plan.ok()) {
            String name = plan.good() == null ? "that" : TradeGoods.get(plan.good()) == null ? plan.good() : TradeGoods.get(plan.good()).name();
            return Result.refuse(switch (plan.problem()) {
                case MISSING_ITEMS -> "You are " + plan.shortBy() + " " + name + " short (named, enchanted or damaged items don't count).";
                case NO_ROOM -> "Make room first: " + plan.shortBy() + " " + name + " wouldn't fit in your inventory.";
                default -> "That isn't a quantity anyone can trade.";
            });
        }
        // 2. reserve
        String holdKey = "txn:" + UUID.randomUUID();
        String why = takes.isEmpty() ? null : other.reserve(counterparty, takes, holdKey);
        if (why != null) return Result.refuse(why);
        // 3. prepare
        Journal.Record rec;
        try {
            rec = journal.prepare(p.getUniqueId(), p.getName(), kind, counterparty, gives, takes, (effects == null ? "" : effects) + ";hold=" + holdKey, idemKey, worthIn, worthOut, now);
        } catch (Journal.DuplicateException e) {
            if (!takes.isEmpty()) other.release(counterparty, holdKey);
            journal.flag(p.getUniqueId(), p.getName(), AuditRules.INFO, AuditRules.DUPLICATE_CLAIM, "asked again for " + idemKey, null, now);
            return Result.refuse("That has already been paid.");
        } catch (SQLException e) {
            if (!takes.isEmpty()) other.release(counterparty, holdKey);
            plugin.getLogger().log(Level.SEVERE, "The transaction journal couldn't be written; the trade was refused and nothing changed hands", e);
            return Result.refuse("The ledger couldn't be written. Nothing changed hands.");
        }
        // 4. transfer: the inventory becomes the planned result, and the player carries the proof in the same file
        try {
            Slot[] before = slots(contents), after = plan.after();
            for (int i = 0; i < after.length; i++) {
                if (after[i].locked || same(before[i], after[i])) continue;
                Material m = after[i].key == null ? null : material(after[i].key);
                contents[i] = m == null ? null : new ItemStack(m, after[i].amount);
            }
            p.getInventory().setStorageContents(contents);
            p.getPersistentDataContainer().set(stamp, PersistentDataType.LONG, rec.seq());
        } catch (RuntimeException e) {
            // the inventory was not replaced (setStorageContents is all-or-nothing): undo the rest
            if (!takes.isEmpty()) other.release(counterparty, holdKey);
            try {
                journal.rollback(rec.id(), "transfer failed: " + e.getMessage(), now);
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "Could not roll back transaction " + rec.id(), ex);
            }
            plugin.getLogger().log(Level.SEVERE, "A trade's item transfer failed; it was rolled back", e);
            return Result.refuse("The trade couldn't be completed. Nothing changed hands.");
        }
        // 5. commit
        other.apply(rec, !takes.isEmpty());
        writeCommit(rec, "", now);
        audit(p.getUniqueId(), p.getName(), rec, now);
        return new Result(true, null, rec);
    }

    private static boolean same(Slot a, Slot b) {
        return a.amount == b.amount && (a.key == null ? b.key == null : a.key.equals(b.key));
    }

    private void writeCommit(Journal.Record rec, String note, long now) {
        try {
            other.mark(journal.commit(rec.id(), note, now));
            other.rememberApplied(rec.id(), false);
        } catch (SQLException e) {
            // the items have moved and the books are right; only the journal row is behind. It is retried, and if the
            // server stops first the books remember that this row is already in them.
            other.rememberApplied(rec.id(), true);
            unwritten.add(rec);
            journal.flag(rec.player(), rec.playerName(), AuditRules.WARN, AuditRules.COMMIT_DELAYED, "commit not written yet: " + e.getMessage(), rec.id(), now);
            plugin.getLogger().log(Level.WARNING, "Transaction " + rec.id() + " is complete but its commit couldn't be written yet; retrying", e);
        }
    }

    /** Every second: writes commits that failed earlier. */
    public void flushUnwritten() {
        if (unwritten.isEmpty() || !open) return;
        List<Journal.Record> again = new ArrayList<>(unwritten);
        unwritten.clear();
        for (Journal.Record r : again) writeCommit(r, "commit written late", System.currentTimeMillis());
    }

    private void audit(UUID player, String name, Journal.Record rec, long now) {
        Journal.Totals minute = journal.totals(player, now - 60_000), hour = journal.totals(player, now - 3_600_000);
        boolean reward = rec.idemKey() != null;
        // a contract's reward is allowed its bonus: only what goes beyond it is worth a note
        double in = reward ? rec.valueIn() * 1.4 : rec.valueIn();
        for (AuditRules.Finding f : rules.afterCommit(in, rec.valueOut(), minute.committed(), hour.valueOut() - hour.valueIn())) {
            if (journal.flagCount(player, f.code(), now - 600_000) > 0 && !f.code().equals(AuditRules.VALUE_GAIN)) continue; // one note per ten minutes
            journal.flag(player, name, f.severity(), f.code(), f.detail(), rec.id(), now);
        }
    }

    // ───────────── after a crash ─────────────

    /**
     * At start-up, once the books are loaded: applies every commit the books don't contain yet, and sets aside
     * again the goods of rows that were cut short, until their players return.
     */
    public int recover(long booksMark) {
        int replayed = 0;
        for (Journal.Record r : journal.committedAfter(booksMark)) {
            other.apply(r, false);
            other.mark(r.commitNo());
            replayed++;
        }
        long now = System.currentTimeMillis();
        for (Journal.Record r : journal.inDoubt()) {
            if (other.alreadyApplied(r.id())) {
                writeCommit(r, "recovered: already in the books", now);
                continue;
            }
            // nobody else may be given these goods until we know whether the player has them
            if (!r.takes().isEmpty()) {
                String why = other.reserve(r.counterparty(), r.takes(), holdOf(r));
                if (why != null) plugin.getLogger().warning("Transaction " + r.id() + " is in doubt and its goods could not be set aside again: " + why);
            }
        }
        int doubt = journal.inDoubt().size();
        if (replayed > 0 || doubt > 0) {
            plugin.getLogger().info("Transaction recovery: " + replayed + " committed trade(s) replayed onto the books, " + doubt
                    + " cut short by the last shutdown (settled when their players next join; see /colony audit).");
        }
        return replayed;
    }

    public static String holdOf(Journal.Record r) {
        for (String part : r.effects().split(";")) if (part.startsWith("hold=")) return part.substring(5);
        return "txn:" + r.id();
    }

    /**
     * A player has joined: settles any of their rows that a crash cut short. Their stamp was saved in the same
     * file as their inventory, so it says for certain whether the items changed hands.
     */
    public List<String> settle(Player p) {
        List<String> told = new ArrayList<>();
        if (!ready()) return told;
        List<Journal.Record> doubt = journal.inDoubt(p.getUniqueId());
        if (doubt.isEmpty()) return told;
        Long seen = p.getPersistentDataContainer().get(stamp, PersistentDataType.LONG);
        long now = System.currentTimeMillis();
        for (Journal.Record r : doubt) {
            boolean happened = seen != null && seen >= r.seq();
            told.add(resolve(r, happened, happened ? "the inventory change was saved (stamp " + seen + " ≥ " + r.seq() + ")"
                    : "the inventory change was not saved (stamp " + seen + " < " + r.seq() + ")", now));
        }
        return told;
    }

    /** Carries an in-doubt row through or undoes it (also used by administrators for a player who never returns). */
    public String resolve(Journal.Record r, boolean happened, String why, long now) {
        try {
            if (happened) {
                other.apply(r, !r.takes().isEmpty());
                writeCommit(r, "recovered: " + why, now);
                journal.flag(r.player(), r.playerName(), AuditRules.INFO, AuditRules.RECOVERED, "carried through: " + why, r.id(), now);
                return "A trade interrupted by a server stop was completed: you gave " + Line.describe(r.gives()) + " for " + Line.describe(r.takes()) + ".";
            }
            if (!r.takes().isEmpty()) other.release(r.counterparty(), holdOf(r));
            journal.rollback(r.id(), "recovered: " + why, now);
            journal.flag(r.player(), r.playerName(), AuditRules.INFO, AuditRules.RECOVERED, "undone: " + why, r.id(), now);
            return "A trade interrupted by a server stop was cancelled: you kept your " + Line.describe(r.gives()) + ".";
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not settle transaction " + r.id(), e);
            return "A trade interrupted by a server stop couldn't be settled yet. An administrator has been told.";
        }
    }

    public void quit(UUID player) {
        guard.forget(player);
    }
}
