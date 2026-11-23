package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Goods;
import com.colonysmp.model.Colony;
import com.colonysmp.util.ItemGoods;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * A colony's market, open to any player: buy what the colony has to spare, or sell it goods from your own
 * inventory (click them below). Paid for in valuables (iron, gold, copper, emeralds, diamonds) from the inventory.
 */
public final class ShopMenu extends Menu {

    private final ColonySMP plugin;
    private final Colony c;
    private int page;

    public ShopMenu(ColonySMP plugin, Player viewer, Colony c) {
        super(viewer, 6, "<dark_green>Market of " + Text.esc(c.name));
        this.plugin = plugin;
        this.c = c;
    }

    @Override
    protected void draw() {
        var eco = plugin.economy();
        if (eco.shop(c) == null) {
            button(22, Material.BARRIER, "<red>The market is closed", List.of("<gray>" + Text.esc(c.name) + " needs a Market with a shopkeeper at work."), null);
            fill(Material.GRAY_STAINED_GLASS_PANE);
            return;
        }
        List<String> keys = new ArrayList<>();
        for (String k : c.storage.contents().keySet()) if (eco.forSale(c, k) > 0) keys.add(k);
        keys.sort((a, b) -> Goods.get(a).name().compareTo(Goods.get(b).name()));
        int from = pager(page, keys.size(), 45, p -> {
            page = p;
            refresh();
        });
        for (int i = 0; i < 45 && from + i < keys.size(); i++) {
            String k = keys.get(from + i);
            long price = eco.shopPrice(c, k);
            List<String> lore = List.of("<gray>For sale: <white>" + eco.forSale(c, k), "<gray>Price: <gold>" + Text.value(price) + "</gold> each",
                    "<gray>They pay " + Text.value(eco.shopOffer(c, k)) + " for yours", "<dark_gray>Left: buy one · Shift: buy a stack");
            set(i, Items.icon(ItemGoods.icon(k), "<white>" + Goods.get(k).name(), lore), x -> {
                String err = eco.shopBuy(viewer, c, k, x.shift() ? Goods.stack(k) : 1);
                if (err != null) Text.bar(viewer, "<red>" + err);
                refresh();
            });
        }
        if (keys.isEmpty()) button(22, Material.CHEST, "<gray>Nothing to spare right now", List.of(), null);
        button(49, Material.EMERALD, "<green>You carry: " + eco.money(viewer), List.of("<gray>Click goods in your inventory to sell them here."), null);
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    @Override
    protected void bottom(Click k, int slot) {
        ItemStack it = viewer.getInventory().getItem(slot);
        if (it == null || it.getType().isAir()) return;
        String err = plugin.economy().shopSell(viewer, c, it);
        if (err != null) Text.bar(viewer, "<red>" + err);
        else viewer.getInventory().setItem(slot, it.getAmount() <= 0 ? null : it);
        refresh();
    }
}
