package com.colonysmp.txn;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Works out, on a copy, exactly what an inventory looks like after taking some items out and putting others in,
 * before anything real is touched. A trade only goes ahead if the whole change fits: every item to be given is
 * there, and every item to be received has a slot. Nothing is ever dropped on the ground or half done.
 *
 * <p>Plain data only (no server classes), so the arithmetic that guards against duplication can be tested alone.
 */
public final class InventoryMath {

    /** One inventory slot. {@code key == null} with {@code amount == 0} is empty; {@code locked} slots hold something that may not be touched. */
    public static final class Slot {
        public String key;
        public int amount;
        public final boolean locked;

        private Slot(String key, int amount, boolean locked) {
            this.key = key;
            this.amount = amount;
            this.locked = locked;
        }

        public static Slot empty() {
            return new Slot(null, 0, false);
        }

        /** A stack of plain, tradeable items. */
        public static Slot of(String key, int amount) {
            return new Slot(key, amount, false);
        }

        /** Anything that isn't a plain tradeable item (named, enchanted, damaged, a plugin item...): it occupies the slot and is never taken. */
        public static Slot locked() {
            return new Slot(null, 0, true);
        }

        Slot copy() {
            return new Slot(key, amount, locked);
        }
    }

    public enum Problem { NONE, BAD_QUANTITY, MISSING_ITEMS, NO_ROOM }

    /** The outcome: the slots as they would be, or why it can't be done. */
    public record Plan(Problem problem, String good, long shortBy, Slot[] after) {
        public boolean ok() {
            return problem == Problem.NONE;
        }
    }

    /** The most of one good a single transaction may move (a full inventory of 64-stacks is 2304). */
    public static final long MAX_QTY = 36L * 99;

    private InventoryMath() {}

    public static long count(Slot[] slots, String key) {
        long n = 0;
        for (Slot s : slots) if (!s.locked && key.equals(s.key)) n += s.amount;
        return n;
    }

    /** How many more of a good fit (into partial stacks and empty slots). */
    public static long room(Slot[] slots, String key, int maxStack) {
        long n = 0;
        for (Slot s : slots) {
            if (s.locked) continue;
            if (s.key == null) n += maxStack;
            else if (s.key.equals(key)) n += Math.max(0, maxStack - s.amount);
        }
        return n;
    }

    /**
     * Removes {@code give} and then adds {@code take}. All or nothing: on any problem the original slots are
     * untouched and no partial result is returned.
     */
    public static Plan plan(Slot[] slots, List<Line> give, List<Line> take, ToIntFunction<String> maxStack) {
        Slot[] work = new Slot[slots.length];
        for (int i = 0; i < slots.length; i++) work[i] = slots[i].copy();
        for (Line l : give) {
            if (l.good() == null || l.qty() <= 0 || l.qty() > MAX_QTY) return new Plan(Problem.BAD_QUANTITY, l.good(), 0, null);
        }
        for (Line l : take) {
            if (l.good() == null || l.qty() <= 0 || l.qty() > MAX_QTY) return new Plan(Problem.BAD_QUANTITY, l.good(), 0, null);
        }
        for (Line l : Line.merge(give)) {
            long left = l.qty();
            long have = count(work, l.good());
            if (have < left) return new Plan(Problem.MISSING_ITEMS, l.good(), left - have, null);
            // smallest stacks first, so the inventory is left tidy
            while (left > 0) {
                Slot best = null;
                for (Slot s : work) {
                    if (s.locked || !l.good().equals(s.key)) continue;
                    if (best == null || s.amount < best.amount) best = s;
                }
                if (best == null) return new Plan(Problem.MISSING_ITEMS, l.good(), left, null);
                int n = (int) Math.min(left, best.amount);
                best.amount -= n;
                left -= n;
                if (best.amount == 0) best.key = null;
            }
        }
        for (Line l : Line.merge(take)) {
            int max = Math.max(1, maxStack.applyAsInt(l.good()));
            long left = l.qty();
            for (Slot s : work) {
                if (left <= 0) break;
                if (s.locked || !l.good().equals(s.key) || s.amount >= max) continue;
                int n = (int) Math.min(left, max - s.amount);
                s.amount += n;
                left -= n;
            }
            for (Slot s : work) {
                if (left <= 0) break;
                if (s.locked || s.key != null) continue;
                int n = (int) Math.min(left, max);
                s.key = l.good();
                s.amount = n;
                left -= n;
            }
            if (left > 0) return new Plan(Problem.NO_ROOM, l.good(), left, null);
        }
        return new Plan(Problem.NONE, null, 0, work);
    }
}
