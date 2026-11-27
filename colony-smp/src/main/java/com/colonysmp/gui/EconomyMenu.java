package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Good;
import com.colonysmp.core.Goods;
import com.colonysmp.core.Txn;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Perm;
import com.colonysmp.util.ItemGoods;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The treasury and the market. */
public final class EconomyMenu extends Menu {

    private final ColonySMP plugin;
    private final Colony c;

    public EconomyMenu(ColonySMP plugin, Player viewer, Colony c) {
        super(viewer, 4, "<dark_aqua>Economy · " + Text.esc(c.name));
        this.plugin = plugin;
        this.c = c;
    }

    /** Opens the market page directly. */
    public void trade() {
        new TradeMenu().open();
    }

    @Override
    protected void draw() {
        List<String> tlore = new ArrayList<>(plugin.commands().info().economy(c));
        tlore.add(0, "<gray>Holds " + Text.items(c.treasury.holdings()));
        button(4, Material.GOLD_BLOCK, "<gold>Treasury: " + Text.value(c.treasury.balance()), tlore, null);
        List<String> recent = new ArrayList<>();
        int i = 0;
        for (Txn t : c.treasury.recent()) {
            if (i++ >= 10) break;
            recent.add("<dark_gray>Day " + t.day() + " <gray>" + t.type().display + " " + Text.signed(t.amount()) + " <dark_gray>" + Text.esc(t.memo() == null ? "" : t.memo()));
        }
        if (recent.isEmpty()) recent.add("<gray>No transactions yet");
        button(13, Material.BOOK, "<white>Recent transactions", recent, null);
        button(19, Material.EMERALD, "<green>Deposit", List.of("<gray>Hand iron, gold, copper, emeralds or diamonds", "<gray>to the treasury (type \"all\" for everything)", "<gray>You carry " + plugin.economy().money(viewer)), k -> {
            viewer.closeInventory();
            plugin.prompt().ask(viewer, "How much worth do you deposit (or \"all\")?", 30, s -> viewer.performCommand("colony deposit " + s));
        });
        button(20, Material.REDSTONE, "<green>Withdraw", List.of("<gray>Take valuables out (needs treasury rights)"), k -> {
            if (!plugin.access().check(viewer, c, Perm.TREASURY)) return;
            viewer.closeInventory();
            plugin.prompt().ask(viewer, "How much worth do you withdraw?", 30, s -> viewer.performCommand("colony withdraw " + s));
        });
        boolean post = plugin.economy().tradingPost(c) != null;
        button(22, Material.CARTOGRAPHY_TABLE, "<green>Market", List.of(post ? "<gray>Sell surplus and buy what's lacking" : "<red>Needs a Trading Post with a merchant at work",
                "<dark_gray>Click to open"), k -> new TradeMenu().open());
        button(24, Material.CHEST_MINECART, "<green>Auto-sell: " + (c.policies.autoSell ? "on" : "off"), List.of("<gray>Sell stock above its target every day", "<dark_gray>Click to toggle"), k -> {
            if (!plugin.access().check(viewer, c, Perm.POLICIES)) return;
            c.policies.autoSell = !c.policies.autoSell;
            plugin.requestSave();
            refresh();
        });
        button(25, Material.HOPPER_MINECART, "<green>Auto-buy: " + (c.policies.autoBuy ? "on" : "off"), List.of("<gray>Buy goods below their minimum every day", "<dark_gray>Click to toggle"), k -> {
            if (!plugin.access().check(viewer, c, Perm.POLICIES)) return;
            c.policies.autoBuy = !c.policies.autoBuy;
            plugin.requestSave();
            refresh();
        });
        button(31, Material.ARROW, "<white>Back to the Town Hall", List.of(), k -> new TownHallMenu(plugin, viewer, c).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    /** Market prices for what the colony has and common goods: left-click sells a stack, right-click buys one. */
    private final class TradeMenu extends Menu {
        private int page;

        TradeMenu() {
            super(EconomyMenu.this.viewer, 6, "<dark_aqua>Market · " + Text.esc(c.name));
        }

        @Override
        protected void draw() {
            Set<String> keys = new LinkedHashSet<>(c.storage.contents().keySet());
            for (String k : new String[]{"wheat", "bread", "carrot", "potato", "cooked_beef", "oak_log", "oak_planks", "cobblestone", "stone", "iron_ingot", "coal",
                    "torch", "arrow", "leather", "string", "glass", "white_wool", "sugar_cane", "book"}) {
                if (Goods.known(k)) keys.add(k);
            }
            List<String> list = new ArrayList<>(keys);
            list.removeIf(k -> Goods.get(k).custom() && !k.equals(Goods.FLOUR));
            list.sort(Comparator.comparing((String k) -> Goods.get(k).category().ordinal()).thenComparing(k -> Goods.get(k).name()));
            int from = pager(page, list.size(), 45, p -> {
                page = p;
                refresh();
            });
            int skill = plugin.economy().merchantSkill(c);
            var m = plugin.economy().market();
            for (int i = 0; i < 45 && from + i < list.size(); i++) {
                String k = list.get(from + i);
                Good g = Goods.get(k);
                int stack = Goods.stack(k);
                List<String> lore = new ArrayList<>();
                lore.add("<gray>In storage: <white>" + c.storage.available(k));
                lore.add("<gray>Sell " + stack + ": <green>" + Text.value(m.quoteSale(k, stack, skill)));
                lore.add("<gray>Buy " + stack + ": <gold>" + Text.value(m.quotePurchase(k, stack, skill)));
                double pr = m.pressure(k);
                if (Math.abs(pr) > 0.05) lore.add(pr > 0 ? "<yellow>Scarce: prices are up" : "<aqua>Flooded: prices are down");
                lore.add("<dark_gray>Left: sell a stack · Right: buy a stack");
                set(i, Items.icon(ItemGoods.icon(k), "<white>" + g.name(), lore), x -> {
                    String err = x.right() ? plugin.economy().buy(viewer, c, k, stack, false) : plugin.economy().sell(viewer, c, k, stack, false);
                    if (err != null) Text.bar(viewer, "<red>" + err);
                    refresh();
                });
            }
            button(49, Material.ARROW, "<white>Back", List.of("<gray>Treasury " + Text.value(c.treasury.balance())), x -> EconomyMenu.this.open());
            fill(Material.GRAY_STAINED_GLASS_PANE);
        }
    }
}
