package com.colonysmp.gui;

import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** A chest GUI. Subclasses draw buttons in {@link #draw()}; clicks run the button's action. */
public abstract class Menu implements InventoryHolder {

    public record Click(Player player, ClickType type, int slot) {
        public boolean shift() {
            return type.isShiftClick();
        }

        public boolean right() {
            return type.isRightClick();
        }
    }

    protected final Player viewer;
    private final Inventory inventory;
    private final Map<Integer, Consumer<Click>> actions = new HashMap<>();

    protected Menu(Player viewer, int rows, String title) {
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, rows * 9, Text.mm(title));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    /** Fills the inventory. Called on open and by {@link #refresh()}. */
    protected abstract void draw();

    public void open() {
        refresh();
        viewer.openInventory(inventory);
    }

    public void refresh() {
        inventory.clear();
        actions.clear();
        draw();
    }

    protected void set(int slot, ItemStack item, Consumer<Click> action) {
        if (slot < 0 || slot >= inventory.getSize()) return;
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, action);
        else actions.remove(slot);
    }

    protected void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    protected void button(int slot, Material m, String name, List<String> lore, Consumer<Click> action) {
        set(slot, Items.icon(m, name, lore), action);
    }

    protected void fill(Material m) {
        ItemStack pane = Items.icon(m, "<dark_gray> ");
        for (int i = 0; i < inventory.getSize(); i++) if (inventory.getItem(i) == null) inventory.setItem(i, pane);
    }

    protected void border(Material m) {
        int size = inventory.getSize();
        for (int i = 0; i < size; i++) {
            int row = i / 9, col = i % 9;
            if (row == 0 || row == size / 9 - 1 || col == 0 || col == 8) {
                if (inventory.getItem(i) == null) inventory.setItem(i, Items.icon(m, "<dark_gray> "));
            }
        }
    }

    void click(Click c) {
        Consumer<Click> a = actions.get(c.slot());
        if (a != null) a.accept(c);
    }

    /** A click on the viewer's own inventory below the menu (storage menus take items this way). */
    protected void bottom(Click c, int slot) {
    }

    /** A row of page arrows on the last row; returns the first index to show. */
    protected int pager(int page, int total, int perPage, java.util.function.IntConsumer turn) {
        int pages = Math.max(1, (total + perPage - 1) / perPage);
        int p = Math.max(0, Math.min(page, pages - 1));
        int last = getInventory().getSize() - 9;
        if (p > 0) button(last, Material.ARROW, "<white>Previous page", List.of("<gray>Page " + p + " of " + pages), c -> turn.accept(p - 1));
        if (p < pages - 1) button(last + 8, Material.ARROW, "<white>Next page", List.of("<gray>Page " + (p + 2) + " of " + pages), c -> turn.accept(p + 1));
        return p * perPage;
    }

    /** Called when the viewer closes the menu. */
    protected void closed() {
    }

    void onClose() {
        closed();
    }
}
