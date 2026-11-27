package com.colonysmp.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A colony's treasury. There is no money: the treasury is the valuables kept in the colony's storage (iron, gold
 * and copper from nuggets to blocks, emeralds, diamonds, netherite), and its balance is what they are worth. Worth
 * is measured against the iron nugget (1): an iron ingot is 9, a gold ingot 18, an emerald 10, a diamond 60 by
 * default (the values of goods in the config).
 *
 * <p>Paying takes real items: the largest that fit first, breaking an ingot into nuggets or a block into ingots for
 * change (the vanilla recipes, so nothing is gained or lost). If only gems are left and none fits the amount, the
 * smallest one is handed over and nothing comes back, the way barter works; the books record what was really
 * given. Incoming worth that isn't a particular set of items (a market sale, a fine) arrives as a mix of metal and
 * gems. A payment that can't be made fails; nothing goes negative.
 */
public final class Treasury {

    public static final int KEEP_RECENT = 200;

    /** A valuable, and what one breaks into (nine of them), or null. */
    private record Kind(String good, String breaksInto) {}

    private static final List<Kind> KINDS = List.of(
            new Kind("iron_nugget", null), new Kind("gold_nugget", null), new Kind("copper_ingot", null),
            new Kind("emerald", null), new Kind("diamond", null), new Kind("netherite_ingot", null),
            new Kind("iron_ingot", "iron_nugget"), new Kind("iron_block", "iron_ingot"),
            new Kind("gold_ingot", "gold_nugget"), new Kind("gold_block", "gold_ingot"),
            new Kind("copper_block", "copper_ingot"), new Kind("emerald_block", "emerald"), new Kind("diamond_block", "diamond"));
    private static final Map<String, Kind> BY_GOOD = new LinkedHashMap<>();
    /** What a market sale or other worth "from outside" is paid in: metals (breakable for change) and gems. */
    private static final String[] METAL = {"gold_ingot", "iron_ingot", "gold_nugget", "iron_nugget"};
    private static final String[] GEMS = {"diamond", "emerald", "copper_ingot"};

    static {
        for (Kind k : KINDS) BY_GOOD.put(k.good, k);
    }

    private final Ledger store;
    private long nextId = 1;
    private final Deque<Txn> recent = new ArrayDeque<>();
    private final List<Txn> unsaved = new ArrayList<>();
    /** Today's and yesterday's totals per type, for the economy summary. */
    private long statsDay = Long.MIN_VALUE;
    private final Map<TxnType, Long> today = new EnumMap<>(TxnType.class);
    private final Map<TxnType, Long> yesterday = new EnumMap<>(TxnType.class);

    /** A treasury that is the valuables in this store. */
    public Treasury(Ledger store) {
        this.store = store;
    }

    /** A treasury with a store of its own (tests, and planning a player's payment on a copy). */
    public Treasury() {
        this(new Ledger("t"));
    }

    public Ledger store() {
        return store;
    }

    // ───────────── what counts ─────────────

    /** True for the goods the treasury is made of. */
    public static boolean isValuable(String good) {
        return good != null && BY_GOOD.containsKey(good);
    }

    /** The valuables, smallest first. */
    public static List<String> valuables() {
        List<String> l = new ArrayList<>(BY_GOOD.keySet());
        l.sort(Comparator.comparingLong((String g) -> worth(g)));
        return l;
    }

    /** What one of a valuable is worth (0 for anything else). A breakable one is exactly nine of what it breaks into. */
    public static long worth(String good) {
        Kind k = BY_GOOD.get(good);
        if (k == null) return 0;
        if (k.breaksInto != null) return 9 * worth(k.breaksInto);
        return Math.max(1, Math.round(Goods.get(good).value()));
    }

    /** Worth of a set of goods (valuables only). */
    public static long worth(Map<String, Long> goods) {
        long v = 0;
        for (Map.Entry<String, Long> e : goods.entrySet()) v += worth(e.getKey()) * e.getValue();
        return v;
    }

    /** Valuables held, free to spend, and how many of each. */
    public Map<String, Long> holdings() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String g : valuables()) {
            long n = store.available(g);
            if (n > 0) m.put(g, n);
        }
        return m;
    }

    /** What the valuables held are worth. */
    public long balance() {
        long v = 0;
        for (Kind k : KINDS) v += store.available(k.good) * worth(k.good);
        return v;
    }

    public boolean canAfford(long amount) {
        return amount <= balance();
    }

    // ───────────── moving it ─────────────

    /** Worth in, as a mix of metal and gems. The items go into storage even past its capacity: they've arrived. */
    public Txn credit(long amount, TxnType type, String memo, long time, long day) {
        if (amount <= 0) return null;
        long added = worth(mint(amount));
        return added <= 0 ? null : record(type, added, memo, time, day);
    }

    /** These very items in (a player's payment, a gift handed over). */
    public Txn creditItems(Map<String, Long> items, TxnType type, String memo, long time, long day) {
        long v = 0;
        for (Map.Entry<String, Long> e : items.entrySet()) {
            if (!isValuable(e.getKey()) || e.getValue() <= 0) continue;
            store.force(e.getKey(), e.getValue());
            v += worth(e.getKey()) * e.getValue();
        }
        return v <= 0 ? null : record(type, v, memo, time, day);
    }

    /** Pays at least the amount, only if the treasury holds that much. Returns null if it doesn't. */
    public Txn debit(long amount, TxnType type, String memo, long time, long day) {
        if (amount <= 0) return null;
        Map<String, Long> given = take(amount);
        if (given == null) return null;
        return record(type, -worth(given), memo, time, day);
    }

    /** Pays and returns the items that left (to hand to a player or another colony), or null if it can't. */
    public Map<String, Long> debitItems(long amount, TxnType type, String memo, long time, long day) {
        if (amount <= 0) return new LinkedHashMap<>();
        Map<String, Long> given = take(amount);
        if (given == null) return null;
        record(type, -worth(given), memo, time, day);
        return given;
    }

    /**
     * Hands valuables worth at least the amount to another treasury: the very items leave one store and arrive in
     * the other. Returns the worth moved, or 0 (nothing moved) if this treasury can't pay.
     */
    public long transfer(Treasury to, long amount, TxnType outType, String outMemo, long outDay, TxnType inType, String inMemo, long inDay, long time) {
        Map<String, Long> items = debitItems(amount, outType, outMemo, time, outDay);
        if (items == null || items.isEmpty()) return 0;
        to.creditItems(items, inType, inMemo, time, inDay);
        return worth(items);
    }

    /** Pays as much as possible of an amount; returns the worth actually paid. */
    public long debitUpTo(long amount, TxnType type, String memo, long time, long day) {
        long pay = Math.min(amount, balance());
        if (pay <= 0) return 0;
        Txn t = debit(pay, type, memo, time, day);
        return t == null ? 0 : -t.amount();
    }

    /** Admin: brings the worth held to a value, recording the difference. */
    public void set(long value, String memo, long time, long day) {
        long diff = Math.max(0, value) - balance();
        if (diff > 0) credit(diff, TxnType.ADMIN, memo, time, day);
        else if (diff < 0) debitUpTo(-diff, TxnType.ADMIN, memo, time, day);
    }

    /**
     * Takes items worth at least the amount out of the store and returns them, or null (taking nothing) if the
     * store doesn't hold that much. Ingots and blocks broken for change stay in the store as their smaller parts.
     */
    public Map<String, Long> take(long amount) {
        if (amount > balance()) return null;
        Map<String, Long> given = new LinkedHashMap<>();
        List<String> biggestFirst = new ArrayList<>(valuables());
        java.util.Collections.reverse(biggestFirst);
        long left = amount;
        while (left > 0) {
            for (String g : biggestFirst) {
                long w = worth(g);
                long n = Math.min(store.available(g), left / w);
                if (n <= 0) continue;
                store.takeExact(g, n);
                given.merge(g, n, Long::sum);
                left -= n * w;
            }
            if (left <= 0) break;
            // everything still held is worth more than what's left: break the smallest ingot or block
            String breakable = null;
            for (String g : valuables()) {
                if (BY_GOOD.get(g).breaksInto != null && store.available(g) > 0) {
                    breakable = g;
                    break;
                }
            }
            if (breakable != null) {
                store.takeExact(breakable, 1);
                store.force(BY_GOOD.get(breakable).breaksInto, 9);
                continue;
            }
            // only gems that are each worth more: hand over the smallest, no change
            for (String g : valuables()) {
                if (store.available(g) > 0) {
                    store.takeExact(g, 1);
                    given.merge(g, 1L, Long::sum);
                    left -= worth(g);
                    break;
                }
            }
        }
        return given;
    }

    /** Puts a mix of metal and gems worth the amount into the store and returns it. */
    private Map<String, Long> mint(long amount) {
        Map<String, Long> out = new LinkedHashMap<>();
        long gems = amount / 2;
        long left = amount - gems;
        for (String g : GEMS) {
            long w = worth(g), n = gems / w;
            if (n > 0) out.merge(g, n, Long::sum);
            gems -= n * w;
        }
        left += gems;
        for (String g : METAL) {
            long w = worth(g), n = left / w;
            if (n > 0) out.merge(g, n, Long::sum);
            left -= n * w;
        }
        for (Map.Entry<String, Long> e : out.entrySet()) store.force(e.getKey(), e.getValue());
        return out;
    }

    // ───────────── the books ─────────────

    private Txn record(TxnType type, long amount, String memo, long time, long day) {
        Txn t = new Txn(nextId++, time, day, type, amount, balance(), memo == null ? "" : memo);
        recent.addFirst(t);
        while (recent.size() > KEEP_RECENT) recent.removeLast();
        unsaved.add(t);
        if (day == statsDay - 1) yesterday.merge(type, amount, Long::sum); // booked to the day that just ended
        else if (day >= statsDay) {
            roll(day);
            today.merge(type, amount, Long::sum);
        }
        return t;
    }

    private void roll(long day) {
        if (day == statsDay) return;
        yesterday.clear();
        if (day == statsDay + 1) yesterday.putAll(today);
        today.clear();
        statsDay = day;
    }

    /** Signed totals per type so far today. */
    public Map<TxnType, Long> today(long day) {
        roll(day);
        return new EnumMap<>(today);
    }

    /** Signed totals per type for the whole of yesterday. */
    public Map<TxnType, Long> yesterday(long day) {
        roll(day);
        return new EnumMap<>(yesterday);
    }

    public List<Txn> recent() {
        return new ArrayList<>(recent);
    }

    /** Transactions not yet written to the database; clears the list. */
    public List<Txn> drainUnsaved() {
        List<Txn> out = new ArrayList<>(unsaved);
        unsaved.clear();
        return out;
    }

    /** Puts transactions back if a save failed, so they're written next time. */
    public void requeue(List<Txn> txns) {
        unsaved.addAll(0, txns);
    }

    // ───────────── loading ─────────────

    /**
     * Loads the books. {@code oldCoins} is a coin balance from a save made before treasuries were valuables (up
     * to 2.3); it is put into storage once as valuables of the same worth, so nobody loses what they had.
     */
    public void load(long oldCoins, long nextId, List<Txn> recentNewestFirst) {
        this.nextId = Math.max(1, nextId);
        recent.clear();
        for (Txn t : recentNewestFirst) {
            recent.addLast(t);
            this.nextId = Math.max(this.nextId, t.id() + 1);
        }
        if (oldCoins > 0) mint(oldCoins);
    }

    public long nextId() {
        return nextId;
    }
}
