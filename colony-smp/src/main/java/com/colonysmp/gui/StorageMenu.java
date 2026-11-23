package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Good;
import com.colonysmp.core.Goods;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Perm;
import com.colonysmp.util.ItemGoods;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Central storage, opened at the Town Hall chest: goods are counted, not kept as item stacks. Click your own
 * items to put them in; click a good to take a stack out (right-click: one, shift: as much as fits). The
 * armoury tab holds tools, arms and armour as real items.
 */
public final class StorageMenu extends Menu {

    private final ColonySMP plugin;
    private final Colony c;
    private boolean armoury;
    private int page;

    public StorageMenu(ColonySMP plugin, Player viewer, Colony c) {
        super(viewer, 6, "<dark_aqua>Storage · " + Text.esc(c.name));
        this.plugin = plugin;
        this.c = c;
    }

    @Override
    protected void draw() {
        List<String> keys = new ArrayList<>(c.storage.contents().keySet());
        keys.sort(Comparator.comparing((String k) -> Goods.get(k).category().ordinal()).thenComparing(k -> Goods.get(k).name()));
        if (!armoury) {
            int from = pager(page, keys.size(), 45, p -> {
                page = p;
                refresh();
            });
            for (int i = 0; i < 45 && from + i < keys.size(); i++) {
                String k = keys.get(from + i);
                Good g = Goods.get(k);
                long have = c.storage.get(k), reserved = c.storage.reserved(k);
                List<String> lore = new ArrayList<>();
                lore.add("<gray>" + g.category().display + " · <white>" + have + "</white> in storage" + (reserved > 0 ? " <dark_gray>(" + reserved + " set aside)" : ""));
                int[] t = c.target(k);
                if (t != null) lore.add("<gray>Target: keep " + t[0] + (t[1] > 0 ? ", up to " + t[1] : ""));
                double worth = plugin.economy().market().mid(k);
                lore.add("<gray>Worth about " + (worth < 1 ? Text.value(Math.round(worth * 64)) + " a stack"
                        : Text.value(Math.round(worth)) + " each"));
                lore.add("<dark_gray>Left: a stack · Right: one · Shift: as much as fits");
                ItemStack icon = Items.icon(ItemGoods.icon(k), "<white>" + g.name(), lore);
                icon.setAmount((int) Math.max(1, Math.min(64, have)));
                int slot = i;
                set(slot, icon, k2 -> take(k, k2));
            }
        } else {
            List<ItemStack> eq = c.equipment.contents();
            int from = pager(page, eq.size(), 45, p -> {
                page = p;
                refresh();
            });
            for (int i = 0; i < 45 && from + i < eq.size(); i++) {
                int idx = from + i;
                set(i, eq.get(idx).clone(), k -> {
                    if (!plugin.access().check(viewer, c, Perm.STORAGE_WITHDRAW)) return;
                    if (viewer.getInventory().firstEmpty() < 0) {
                        Text.bar(viewer, "<red>Your inventory is full.");
                        return;
                    }
                    ItemStack it = c.equipment.remove(idx);
                    if (it != null) viewer.getInventory().addItem(it);
                    plugin.requestSave();
                    refresh();
                });
            }
        }
        int last = 45;
        button(last + 3, armoury ? Material.CHEST : Material.IRON_CHESTPLATE, armoury ? "<white>Show goods" : "<white>Show the armoury",
                List.of("<gray>Armoury: " + c.equipment.size() + "/" + c.equipment.capacity() + " items"), k -> {
                    armoury = !armoury;
                    page = 0;
                    refresh();
                });
        button(last + 4, Material.BOOK, "<aqua>Storage", List.of("<gray>Goods: " + c.storage.usedSlots() + "/" + c.storage.capacity() + " slots",
                "<gray>Granary: " + c.storage.usedGranarySlots() + "/" + c.storage.granaryCapacity() + " slots", "<gray>Armoury: " + c.equipment.size() + "/" + c.equipment.capacity(),
                "", "<gray>Click items in your inventory to store them.", "<gray>Warehouses add space; granaries add food space."), null);
        button(last + 5, Material.ARROW, "<white>Back to the Town Hall", List.of(), k -> new TownHallMenu(plugin, viewer, c).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    private void take(String key, Click k) {
        if (!plugin.access().check(viewer, c, Perm.STORAGE_WITHDRAW)) return;
        // valuables are the treasury: taking them needs treasury rights, and is booked as a withdrawal
        if (com.colonysmp.core.Treasury.isValuable(key)) {
            if (!plugin.access().check(viewer, c, Perm.TREASURY)) return;
            viewer.closeInventory();
            Text.send(viewer, "<gray>Valuables are the treasury: use <white>/colony withdraw <worth></white> so the books show it.");
            return;
        }
        long want = k.right() ? 1 : k.shift() ? Goods.stack(key) * 4L : Goods.stack(key);
        long got = c.storage.take(key, Math.min(want, c.storage.available(key)));
        if (got <= 0) {
            Text.bar(viewer, "<red>All of that is set aside for work in progress.");
            return;
        }
        long given = 0;
        for (ItemStack it : ItemGoods.stacks(key, got)) {
            Map<Integer, ItemStack> left = viewer.getInventory().addItem(it);
            int back = 0;
            for (ItemStack l : left.values()) back += l.getAmount();
            given += it.getAmount() - back;
            if (back > 0) break;
        }
        if (given < got) c.storage.force(key, got - given);
        plugin.requestSave();
        refresh();
    }

    @Override
    protected void bottom(Click k, int slot) {
        ItemStack it = viewer.getInventory().getItem(slot);
        if (it == null || it.getType().isAir()) return;
        if (!plugin.access().check(viewer, c, Perm.STORAGE_DEPOSIT)) return;
        if (Items.id(it) != null) {
            Text.bar(viewer, "<red>Colony tools can't go into storage.");
            return;
        }
        String good = ItemGoods.goodOf(it);
        if (good == null) {
            if (c.equipment.full()) {
                Text.bar(viewer, "<red>The armoury is full. Build or upgrade an armoury.");
                return;
            }
            c.equipment.add(it.clone());
            viewer.getInventory().setItem(slot, null);
        } else {
            long added = c.storage.add(good, it.getAmount());
            if (added <= 0) {
                Text.bar(viewer, "<red>Storage is full" + (Goods.get(good).category().granary ? " (food goes in granaries)" : "") + ".");
                return;
            }
            it.setAmount((int) (it.getAmount() - added));
            viewer.getInventory().setItem(slot, it.getAmount() <= 0 ? null : it);
        }
        plugin.quests().check(c);
        plugin.requestSave();
        refresh();
    }
}
