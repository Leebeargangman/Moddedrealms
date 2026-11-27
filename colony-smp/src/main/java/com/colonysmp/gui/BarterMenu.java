package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.civ.Civ;
import com.colonysmp.civ.Contract;
import com.colonysmp.civ.Prices;
import com.colonysmp.civ.Settlement;
import com.colonysmp.civ.TradeGoods;
import com.colonysmp.txn.Line;
import com.colonysmp.txn.TransactionManager;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bartering with a settlement's merchant. There is no money: the player picks what they will pay with by
 * clicking it in their own inventory, and every good the settlement can spare then shows what it costs in that.
 * A click swaps at once, through the transaction manager; nothing is ever put down in this menu, so closing it,
 * logging out or dying half way through leaves every item where it was.
 */
public final class BarterMenu extends Menu {

    private final ColonySMP plugin;
    private final Settlement s;
    private String pay;
    private int page;

    public BarterMenu(ColonySMP plugin, Player viewer, Settlement s) {
        super(viewer, 6, "<dark_gray>Barter: " + Text.esc(s.name));
        this.plugin = plugin;
        this.s = s;
    }

    private static String name(String good) {
        TradeGoods.Item i = TradeGoods.get(good);
        return i == null ? good : i.name();
    }

    @Override
    protected void draw() {
        var civs = plugin.civs();
        var world = civs.world();
        Civ civ = world.civs.get(s.civ);
        double rep = civ == null ? 0 : civ.reputation(viewer.getUniqueId());
        Map<String, Double> shortages = new LinkedHashMap<>(), surpluses = new LinkedHashMap<>();
        Prices.sort(s, shortages, surpluses);

        List<String> about = new ArrayList<>();
        about.add("<gray>" + s.type.display + " of <white>" + Text.esc(civ == null ? "nobody" : civ.name));
        about.add("<gray>Known for " + s.type.speciality());
        about.add("<gray>Your standing: " + Text.signed(Math.round(rep)) + " <dark_gray>(better standing, better rates)");
        about.add("");
        about.add("<yellow>No coin changes hands here.");
        about.add("<gray>Click something in <white>your own inventory</white>");
        about.add("<gray>to offer it, then click what you want.");
        button(4, Material.BELL, "<gold>" + Text.esc(s.name), about, c -> new SettlementMenu(plugin, viewer, s).open());

        if (pay == null) {
            button(0, Material.BARRIER, "<red>Paying with: nothing yet", List.of("<gray>Click an item in your inventory below."), null);
        } else {
            long have = TransactionManager.carried(viewer, pay);
            long room = Prices.accepts(s, pay);
            double bid = Prices.bid(world, s, pay, s.stock.get(pay), rep);
            set(0, Items.icon(icon(pay), "<green>Paying with: <white>" + name(pay), List.of("<gray>You carry <white>" + have,
                    "<gray>They will take up to <white>" + room + "</white> more",
                    "<gray>Worth to them: " + rate(bid, TradeGoods.worth(pay)), "<dark_gray>Click another item below to change.")));
        }
        List<String> want = new ArrayList<>();
        int n = 0;
        for (String g : shortages.keySet()) if (n++ < 6) want.add("<gray>- <white>" + name(g));
        if (want.isEmpty()) want.add("<gray>Nothing in particular.");
        want.add("");
        want.add("<dark_gray>They give most for what they lack.");
        button(8, Material.WRITABLE_BOOK, "<yellow>They are short of", want, null);

        List<String> goods = new ArrayList<>();
        for (TradeGoods.Item i : TradeGoods.all()) if (Prices.spare(s, i.key()) > 0 && TransactionManager.material(i.key()) != null) goods.add(i.key());
        goods.sort(Comparator.comparing((String g) -> TradeGoods.get(g).cat()).thenComparing(g -> g));
        int first = pager(page, goods.size(), 36, p -> {
            page = p;
            refresh();
        });
        for (int i = 0; i < 36 && first + i < goods.size(); i++) {
            String g = goods.get(first + i);
            long spare = Prices.spare(s, g);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>They can spare <white>" + spare);
            lore.add("<gray>Asking: " + rate(Prices.ask(world, s, g, s.stock.get(g), rep), TradeGoods.worth(g)));
            if (pay == null) {
                lore.add("<dark_gray>Choose what to pay with first.");
            } else if (pay.equals(g)) {
                lore.add("<dark_gray>You are paying with this.");
            } else {
                long have = TransactionManager.carried(viewer, pay);
                int stack = Math.min(64, TradeGoods.stack(g));
                for (int[] opt : new int[][]{{1, 0}, {Math.min(8, stack), 1}, {stack, 2}}) {
                    if (opt[1] > 0 && opt[0] <= 1) continue;
                    Prices.Quote q = Prices.quote(world, s, pay, g, Math.min(opt[0], spare), rep);
                    String how = opt[1] == 0 ? "Left-click" : opt[1] == 1 ? "Right-click" : "Shift-click";
                    lore.add(q.ok() ? "<gray>" + how + ": <white>" + q.wantQty() + "</white> for " + (q.giveQty() <= have ? "<green>" : "<red>") + q.giveQty() + " " + name(pay)
                            : "<gray>" + how + ": <red>" + q.error());
                }
            }
            ItemStack icon = Items.icon(icon(g), "<white>" + name(g), lore);
            icon.setAmount((int) Math.max(1, Math.min(icon.getMaxStackSize(), spare)));
            set(9 + i, icon, c -> buy(g, c.shift() ? Math.min(64, TradeGoods.stack(g)) : c.right() ? Math.min(8, TradeGoods.stack(g)) : 1));
        }
        int open = 0, mine = 0;
        for (Contract c : world.contracts.values()) {
            if (!c.settlement.equals(s.id)) continue;
            if (c.state == Contract.State.OPEN) open++;
            if (c.state == Contract.State.TAKEN && viewer.getUniqueId().equals(c.takenBy)) mine++;
        }
        button(49, Material.PAPER, "<gold>Contracts", List.of("<gray>" + open + " on offer here, " + mine + " of yours to deliver", "<dark_gray>Bring what they are short of for a reward."),
                c -> new SettlementMenu(plugin, viewer, s).open());
    }

    private static Material icon(String good) {
        Material m = TransactionManager.material(good);
        return m == null || !m.isItem() ? Material.PAPER : m;
    }

    /** "dear (×1.8)" against the base worth, so a player can see at a glance what is scarce here. */
    private static String rate(double now, double base) {
        double f = base <= 0 ? 1 : now / base;
        String word = f >= 1.6 ? "<red>very dear" : f >= 1.15 ? "<gold>dear" : f >= 0.85 ? "<white>fair" : f >= 0.55 ? "<green>cheap" : "<dark_green>very cheap";
        return word + String.format(java.util.Locale.ROOT, " <dark_gray>(×%.2f)", f);
    }

    private void buy(String good, long qty) {
        if (pay == null) {
            Text.bar(viewer, "<yellow>Click what you want to pay with in your own inventory first.");
            return;
        }
        if (pay.equals(good)) return;
        long spare = Prices.spare(s, good);
        qty = Math.min(qty, spare);
        TransactionManager.Result r = plugin.civs().barter(viewer, s, pay, good, qty);
        if (!r.ok()) {
            Text.bar(viewer, "<red>" + Text.esc(r.message()));
            Fx.sound(viewer, "minecraft:entity.villager.no", 0.7f, 1f);
        } else {
            Text.bar(viewer, "<green>Swapped " + Line.describe(r.record().gives()) + " for " + Line.describe(r.record().takes()) + ".");
            Fx.sound(viewer, "minecraft:entity.villager.yes", 0.7f, 1f);
        }
        refresh();
    }

    @Override
    protected void bottom(Click c, int slot) {
        ItemStack it = viewer.getInventory().getItem(slot);
        String good = TransactionManager.goodOf(it);
        if (good == null) {
            Text.bar(viewer, "<red>They only take plain goods they know (nothing named, enchanted or worn).");
            return;
        }
        if (Prices.accepts(s, good) <= 0) {
            Text.bar(viewer, "<red>They have no use for more " + name(good) + ".");
            return;
        }
        pay = good;
        refresh();
    }
}
