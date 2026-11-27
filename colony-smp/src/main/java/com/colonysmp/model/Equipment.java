package com.colonysmp.model;

import com.colonysmp.util.Tools;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * The colony's armoury and tool store: tools, weapons and armour (things with durability or enchantments),
 * kept as real items so wear and enchantments survive. Plain goods live in the {@link com.colonysmp.core.Ledger}.
 */
public final class Equipment {

    private final List<ItemStack> items = new ArrayList<>();
    private int capacity = 54;

    public void setCapacity(int slots) {
        capacity = Math.max(0, slots);
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        return items.size();
    }

    public boolean full() {
        return items.size() >= capacity;
    }

    /** Adds an item (one slot each); returns false if there's no room. */
    public boolean add(ItemStack it) {
        if (it == null || it.getType().isAir()) return true;
        if (items.size() >= capacity) return false;
        items.add(it.clone());
        return true;
    }

    /** Adds regardless of capacity (loading, gear handed back by citizens who already counted). */
    public void force(ItemStack it) {
        if (it != null && !it.getType().isAir()) items.add(it.clone());
    }

    public List<ItemStack> addAll(Collection<ItemStack> in) {
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack it : in) if (!add(it)) left.add(it);
        return left;
    }

    public int count(Predicate<ItemStack> what) {
        int n = 0;
        for (ItemStack it : items) if (what.test(it)) n += it.getAmount();
        return n;
    }

    public boolean has(Predicate<ItemStack> what) {
        for (ItemStack it : items) if (what.test(it)) return true;
        return false;
    }

    public int count(Tools.Kind kind) {
        return count(it -> Tools.kind(it) == kind);
    }

    /** Removes and returns the best matching item, or null. */
    public ItemStack takeBest(Predicate<ItemStack> what, ToDoubleFunction<ItemStack> score) {
        int best = -1;
        double bs = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < items.size(); i++) {
            ItemStack it = items.get(i);
            if (!what.test(it)) continue;
            double s = score.applyAsDouble(it);
            if (s > bs) {
                bs = s;
                best = i;
            }
        }
        if (best < 0) return null;
        ItemStack it = items.get(best);
        if (it.getAmount() <= 1) return items.remove(best);
        ItemStack one = it.clone();
        one.setAmount(1);
        it.setAmount(it.getAmount() - 1);
        return one;
    }

    public double bestScore(Predicate<ItemStack> what, ToDoubleFunction<ItemStack> score) {
        double best = Double.NEGATIVE_INFINITY;
        for (ItemStack it : items) if (what.test(it)) best = Math.max(best, score.applyAsDouble(it));
        return best;
    }

    /** Removes the item at an index (menus). */
    public ItemStack remove(int index) {
        return index < 0 || index >= items.size() ? null : items.remove(index);
    }

    public List<ItemStack> contents() {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack it : items) out.add(it.clone());
        return out;
    }

    /** Live view for the smiths (repairs change items in place). */
    public List<ItemStack> live() {
        return items;
    }

    public void clear() {
        items.clear();
    }
}
