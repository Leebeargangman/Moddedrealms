package com.colonysmp.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A stock of goods with a slot capacity and reservations: the colony's central storage, or the local stock of
 * one workplace. Every change goes through here, so stock never goes negative, reserved goods can't be spent
 * twice, and nothing is added past capacity.
 *
 * <p>Capacity is counted in slots, like chests: each slot holds one stack of one good. Granary slots only hold
 * food and crops; general slots hold anything (food spills into them once the granaries are full).
 *
 * <p>Not thread-safe: the plugin only touches ledgers on the server thread (saves work on copies).
 */
public final class Ledger {

    public static final int UNLIMITED = Integer.MAX_VALUE / 2;

    private final Map<String, Long> stock = new HashMap<>();
    private final Map<String, Long> reserved = new HashMap<>();
    private final Map<String, Reservation> reservations = new LinkedHashMap<>();
    private int capacity = UNLIMITED;
    private int granaryCapacity;
    private long nextId = 1;
    private final String prefix;
    /** Slots in use by granary goods and by everything else, kept up to date on every change. */
    private long foodSlots, otherSlots;

    public Ledger(String prefix) {
        this.prefix = prefix;
    }

    // ───────────── capacity ─────────────

    public void setCapacity(int generalSlots, int granarySlots) {
        this.capacity = Math.max(0, generalSlots);
        this.granaryCapacity = Math.max(0, granarySlots);
    }

    public int capacity() {
        return capacity;
    }

    public int granaryCapacity() {
        return granaryCapacity;
    }

    private static long slots(long qty, int stack) {
        return qty <= 0 ? 0 : (qty + stack - 1) / stack;
    }

    /** Slots used by granary goods and by everything else. */
    private long[] usage() {
        return new long[]{foodSlots, otherSlots};
    }

    /** Sets a good's quantity, keeping the slot counts right. */
    private void put(String key, long qty) {
        Good g = Goods.get(key);
        int st = Math.max(1, g.stack());
        long before = slots(stock.getOrDefault(key, 0L), st);
        if (qty <= 0) stock.remove(key);
        else stock.put(key, qty);
        long diff = slots(Math.max(0, qty), st) - before;
        if (g.granary()) foodSlots += diff;
        else otherSlots += diff;
    }

    /** Recounts the slots from scratch (after the goods registry changed). */
    public void recount() {
        foodSlots = 0;
        otherSlots = 0;
        for (Map.Entry<String, Long> e : stock.entrySet()) {
            Good g = Goods.get(e.getKey());
            long s = slots(e.getValue(), Math.max(1, g.stack()));
            if (g.granary()) foodSlots += s;
            else otherSlots += s;
        }
    }

    /** General slots in use (including food that overflowed the granaries). */
    public long usedSlots() {
        long[] u = usage();
        return u[1] + Math.max(0, u[0] - granaryCapacity);
    }

    public long usedGranarySlots() {
        return Math.min(usage()[0], granaryCapacity);
    }

    public long freeSlots() {
        return Math.max(0, capacity - usedSlots());
    }

    public boolean full() {
        return freeSlots() == 0;
    }

    /** How many more of this good fit. */
    public long room(String key) {
        if (capacity >= UNLIMITED) return Long.MAX_VALUE / 4;
        Good g = Goods.get(key);
        int st = Math.max(1, g.stack());
        long have = get(key);
        long inLast = have % st == 0 ? 0 : st - have % st;
        long[] u = usage();
        long freeGeneral = Math.max(0, capacity - (u[1] + Math.max(0, u[0] - granaryCapacity)));
        long free = freeGeneral;
        if (g.granary()) free += Math.max(0, granaryCapacity - u[0]);
        return inLast + free * st;
    }

    // ───────────── reading ─────────────

    public long get(String key) {
        return stock.getOrDefault(key, 0L);
    }

    public long reserved(String key) {
        return reserved.getOrDefault(key, 0L);
    }

    /** In stock and not reserved for any task. */
    public long available(String key) {
        return Math.max(0, get(key) - reserved(key));
    }

    public Map<String, Long> contents() {
        return Collections.unmodifiableMap(stock);
    }

    /** Sorted copy (for saving and menus). */
    public TreeMap<String, Long> snapshot() {
        return new TreeMap<>(stock);
    }

    public boolean isEmpty() {
        return stock.isEmpty();
    }

    public long total() {
        long n = 0;
        for (long v : stock.values()) n += v;
        return n;
    }

    // ───────────── changing stock ─────────────

    /** Adds up to qty; returns how many were accepted (the rest didn't fit). */
    public long add(String key, long qty) {
        if (qty <= 0 || key == null) return 0;
        long fit = Math.min(qty, room(key));
        if (fit <= 0) return 0;
        put(key, get(key) + fit);
        return fit;
    }

    /** Adds everything regardless of capacity (loading a save, returning goods that were already counted). */
    public void force(String key, long qty) {
        if (qty <= 0 || key == null) return;
        put(key, get(key) + qty);
    }

    /** Takes up to qty of the unreserved stock; returns how many were taken. */
    public long take(String key, long qty) {
        if (qty <= 0) return 0;
        long n = Math.min(qty, available(key));
        if (n <= 0) return 0;
        decrease(key, n);
        return n;
    }

    /** Takes exactly qty of the unreserved stock, or nothing. */
    public boolean takeExact(String key, long qty) {
        if (qty <= 0) return true;
        if (available(key) < qty) return false;
        decrease(key, qty);
        return true;
    }

    private void decrease(String key, long n) {
        put(key, get(key) - n);
    }

    // ───────────── reservations ─────────────

    /**
     * Reserves all of the goods or none of them. Returns the reservation, or null if any of them isn't
     * available in full.
     */
    public Reservation reserve(String owner, Map<String, Long> goods, long now) {
        for (Map.Entry<String, Long> e : goods.entrySet()) {
            if (e.getValue() < 0 || available(e.getKey()) < e.getValue()) return null;
        }
        Reservation r = new Reservation(prefix + (nextId++), owner, now);
        for (Map.Entry<String, Long> e : goods.entrySet()) {
            if (e.getValue() <= 0) continue;
            r.remaining.merge(e.getKey(), e.getValue(), Long::sum);
            reserved.merge(e.getKey(), e.getValue(), Long::sum);
        }
        reservations.put(r.id, r);
        return r;
    }

    public Reservation reserve(String owner, String key, long qty, long now) {
        return reserve(owner, Map.of(key, qty), now);
    }

    public Reservation reservation(String id) {
        return id == null ? null : reservations.get(id);
    }

    public Collection<Reservation> reservations() {
        return Collections.unmodifiableCollection(reservations.values());
    }

    /** Removes every reserved good from stock (the task used them) and closes the reservation. */
    public boolean consume(String id) {
        Reservation r = reservations.remove(id);
        if (r == null) return false;
        for (Map.Entry<String, Long> e : r.remaining.entrySet()) {
            unreserve(e.getKey(), e.getValue());
            decrease(e.getKey(), Math.min(e.getValue(), get(e.getKey())));
        }
        r.remaining.clear();
        return true;
    }

    /**
     * Removes part of a reservation from stock (a carrier picks up one load of a bigger delivery). Returns how
     * many were taken. The reservation closes when nothing is left in it.
     */
    public long consume(String id, String key, long qty) {
        Reservation r = reservations.get(id);
        if (r == null || qty <= 0) return 0;
        long n = Math.min(qty, r.get(key));
        if (n <= 0) return 0;
        long left = r.get(key) - n;
        if (left <= 0) r.remaining.remove(key);
        else r.remaining.put(key, left);
        unreserve(key, n);
        decrease(key, Math.min(n, get(key)));
        if (r.empty()) reservations.remove(id);
        return n;
    }

    /** Gives reserved goods back to the free stock. */
    public boolean release(String id) {
        Reservation r = reservations.remove(id);
        if (r == null) return false;
        for (Map.Entry<String, Long> e : r.remaining.entrySet()) unreserve(e.getKey(), e.getValue());
        r.remaining.clear();
        return true;
    }

    /** Releases every reservation held by an owner (a task was cancelled, a building removed). */
    public int releaseOwner(String owner) {
        List<String> ids = new ArrayList<>();
        for (Reservation r : reservations.values()) if (r.owner.equals(owner)) ids.add(r.id);
        for (String id : ids) release(id);
        return ids.size();
    }

    private void unreserve(String key, long n) {
        long left = reserved(key) - n;
        if (left <= 0) reserved.remove(key);
        else reserved.put(key, left);
    }

    /**
     * Repairs impossible states (more reserved than in stock after an admin edit or a lost save): shrinks the
     * newest reservations first. Returns a description of each fix, for the admin log.
     */
    public List<String> repair() {
        List<String> fixes = new ArrayList<>();
        stock.entrySet().removeIf(e -> e.getValue() <= 0);
        recount();
        List<Reservation> newestFirst = new ArrayList<>(reservations.values());
        Collections.reverse(newestFirst);
        for (Map.Entry<String, Long> e : new HashMap<>(reserved).entrySet()) {
            long over = e.getValue() - get(e.getKey());
            for (Reservation r : newestFirst) {
                if (over <= 0) break;
                long has = r.get(e.getKey());
                if (has <= 0) continue;
                long cut = Math.min(has, over);
                if (has - cut <= 0) r.remaining.remove(e.getKey());
                else r.remaining.put(e.getKey(), has - cut);
                unreserve(e.getKey(), cut);
                over -= cut;
                fixes.add("reservation " + r.id + " (" + r.owner + ") shrunk by " + cut + " " + e.getKey());
            }
        }
        reservations.values().removeIf(Reservation::empty);
        return fixes;
    }

    /** Restores a saved reservation (load). */
    public void restore(String id, String owner, long created, Map<String, Long> goods) {
        Reservation r = new Reservation(id, owner, created);
        for (Map.Entry<String, Long> e : goods.entrySet()) {
            if (e.getValue() <= 0) continue;
            r.remaining.put(e.getKey(), e.getValue());
            reserved.merge(e.getKey(), e.getValue(), Long::sum);
        }
        if (!r.empty()) reservations.put(id, r);
        try {
            long n = Long.parseLong(id.substring(prefix.length()));
            nextId = Math.max(nextId, n + 1);
        } catch (RuntimeException ignored) {
            // an id from another prefix: keep counting from where we are
        }
    }

    public void clear() {
        foodSlots = 0;
        otherSlots = 0;
        stock.clear();
        reserved.clear();
        reservations.clear();
    }
}
