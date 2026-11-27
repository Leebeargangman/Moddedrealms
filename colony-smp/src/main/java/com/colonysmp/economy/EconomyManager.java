package com.colonysmp.economy;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.core.Goods;
import com.colonysmp.core.Market;
import com.colonysmp.core.Skill;
import com.colonysmp.core.Treasury;
import com.colonysmp.core.TxnType;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Perm;
import com.colonysmp.store.Snapshot;
import com.colonysmp.util.ItemGoods;
import com.colonysmp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Valuables between colonies, the market and players. There is no money: a colony's treasury is the iron, gold,
 * copper, emeralds and diamonds in its storage (see {@link Treasury}), and players pay with the same items from
 * their inventory. Colonies trade surplus with the server-wide market through a staffed Trading Post (better
 * merchants get better rates), automatically if their policies say so, and players can shop at a colony's
 * staffed Market.
 */
public final class EconomyManager {

    private final ColonySMP plugin;
    private final Market market = new Market();

    public EconomyManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    public Market market() {
        return market;
    }

    public void configure() {
        Settings s = plugin.settings();
        market.configure(s.marketRecovery, s.marketSpread);
    }

    // ───────────── a player's valuables ─────────────

    /** The plain valuables a player carries, by good. */
    public static Map<String, Long> carried(Player p) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (ItemStack it : p.getInventory().getStorageContents()) {
            String g = ItemGoods.goodOf(it);
            if (Treasury.isValuable(g)) m.merge(g, (long) it.getAmount(), Long::sum);
        }
        return m;
    }

    /**
     * Takes valuables worth at least the amount from a player, breaking an ingot or block into change, and returns
     * what was handed over (null, taking nothing, if they don't carry enough or the change wouldn't fit).
     * The whole payment is planned on a copy of the inventory first.
     */
    public Map<String, Long> chargeItems(Player p, long amount) {
        if (amount <= 0) return new LinkedHashMap<>();
        Map<String, Long> before = carried(p);
        Treasury purse = new Treasury();
        before.forEach((g, n) -> purse.store().force(g, n));
        Map<String, Long> given = purse.take(amount);
        if (given == null) return null;
        if (p.getGameMode() == GameMode.CREATIVE) return given;
        // what leaves the inventory and what comes back as change
        Map<String, Long> remove = new LinkedHashMap<>(), add = new LinkedHashMap<>();
        for (String g : Treasury.valuables()) {
            long d = purse.store().get(g) - before.getOrDefault(g, 0L);
            if (d < 0) remove.put(g, -d);
            else if (d > 0) add.put(g, d);
        }
        if (!fitsAfter(p, remove, add)) return null;
        takeFrom(p, remove);
        for (Map.Entry<String, Long> e : add.entrySet()) {
            for (ItemStack it : ItemGoods.stacks(e.getKey(), e.getValue())) p.getInventory().addItem(it);
        }
        return given;
    }

    /** Takes valuables worth at least the amount from a player (they go to whoever is paid off the books). */
    public boolean charge(Player p, long amount) {
        return chargeItems(p, amount) != null;
    }

    /** Gives a player these items, all or nothing: false if they wouldn't fit. Nothing is ever dropped. */
    public boolean give(Player p, Map<String, Long> items) {
        if (!fitsAfter(p, Map.of(), items)) return false;
        for (Map.Entry<String, Long> e : items.entrySet()) {
            for (ItemStack it : ItemGoods.stacks(e.getKey(), e.getValue())) p.getInventory().addItem(it);
        }
        return true;
    }

    /**
     * Pays a player valuables worth the amount from outside any treasury (a citizen's own savings, a refund) as a
     * mix of metal and gems. Returns the worth handed over: 0 if it wouldn't fit.
     */
    public long pay(Player p, long amount) {
        if (amount <= 0) return 0;
        Treasury purse = new Treasury();
        purse.credit(amount, com.colonysmp.core.TxnType.OTHER, "", 0, 0);
        Map<String, Long> items = purse.holdings();
        return give(p, items) ? Treasury.worth(items) : 0;
    }

    /** Would the inventory take these additions once the removals are made? Planned on a copy. */
    private static boolean fitsAfter(Player p, Map<String, Long> remove, Map<String, Long> add) {
        if (add.isEmpty()) return true;
        ItemStack[] src = p.getInventory().getStorageContents();
        Inventory copy = Bukkit.createInventory(null, 36);
        for (int i = 0; i < Math.min(36, src.length); i++) if (src[i] != null) copy.setItem(i, src[i].clone());
        takeFrom(copy, remove);
        for (Map.Entry<String, Long> e : add.entrySet()) {
            for (ItemStack it : ItemGoods.stacks(e.getKey(), e.getValue())) if (!copy.addItem(it).isEmpty()) return false;
        }
        return true;
    }

    private static void takeFrom(Player p, Map<String, Long> remove) {
        takeFrom(p.getInventory(), remove);
    }

    private static void takeFrom(Inventory inv, Map<String, Long> remove) {
        Map<String, Long> left = new LinkedHashMap<>(remove);
        ItemStack[] items = inv.getStorageContents();
        for (ItemStack it : items) {
            String g = ItemGoods.goodOf(it);
            long want = g == null ? 0 : left.getOrDefault(g, 0L);
            if (want <= 0) continue;
            int take = (int) Math.min(want, it.getAmount());
            it.setAmount(it.getAmount() - take);
            left.put(g, want - take);
        }
        inv.setStorageContents(items);
    }

    /** The items a treasury would pay out for an amount, worked out on a copy (null if it can't pay). */
    private static Map<String, Long> planPayout(Colony c, long amount) {
        Treasury plan = new Treasury();
        c.treasury.holdings().forEach((g, n) -> plan.store().force(g, n));
        return plan.take(amount);
    }

    /** What a player carries, for menus: "120 worth of valuables (2 diamonds)". */
    public String money(Player p) {
        Map<String, Long> m = carried(p);
        return Text.value(Treasury.worth(m)) + (m.isEmpty() ? "" : " (" + Text.items(m) + ")");
    }

    /** A member hands valuables to the treasury: worth at least the amount, or all they carry with amount 0. */
    public String deposit(Player p, Colony c, long amount) {
        if (!c.isMember(p.getUniqueId()) && !plugin.isBypassing(p)) return "You aren't a member of " + Text.esc(c.name) + ".";
        Map<String, Long> have = carried(p);
        if (have.isEmpty()) return "You carry no valuables (iron, gold, copper, emeralds, diamonds or netherite).";
        Map<String, Long> given;
        if (amount <= 0) {
            given = have;
            if (p.getGameMode() != GameMode.CREATIVE) takeFrom(p, have);
        } else {
            given = chargeItems(p, amount);
            if (given == null) return "You carry " + money(p) + "; that isn't " + Text.value(amount) + " (or the change wouldn't fit).";
        }
        c.treasury.creditItems(given, TxnType.DEPOSIT, "Deposit by " + p.getName() + ": " + Text.items(given), System.currentTimeMillis(), plugin.sim().day(c));
        plugin.requestSave();
        return null;
    }

    public String withdraw(Player p, Colony c, long amount) {
        if (amount <= 0) return "How much?";
        if (!plugin.access().can(p, c, Perm.TREASURY)) return "You may not withdraw from " + Text.esc(c.name) + "'s treasury.";
        if (!c.treasury.canAfford(amount)) return "The treasury only has " + Text.value(c.treasury.balance()) + ".";
        long day = plugin.sim().day(c), now = System.currentTimeMillis();
        Map<String, Long> planned = planPayout(c, amount);
        if (planned == null || !fitsAfter(p, Map.of(), planned)) return "You haven't room for " + (planned == null ? "it" : Text.items(planned)) + ".";
        Map<String, Long> items = c.treasury.debitItems(amount, TxnType.WITHDRAWAL, "Withdrawn by " + p.getName(), now, day);
        if (items == null) return "The treasury only has " + Text.value(c.treasury.balance()) + ".";
        give(p, items);
        Text.send(p, "<gray>You take " + Text.items(items) + " from the treasury.");
        plugin.requestSave();
        return null;
    }

    // ───────────── the market ─────────────

    /** The colony's staffed Trading Post (or null): needed to trade on the market. */
    public Building tradingPost(Colony c) {
        return plugin.sim().citizens().staffed(c, BuildingType.TRADING_POST);
    }

    /** The best merchant's trading level (it narrows the spread). */
    public int merchantSkill(Colony c) {
        Building tp = tradingPost(c);
        if (tp == null) return 0;
        int best = 0;
        for (Citizen ct : c.workers(tp)) best = Math.max(best, ct.level(Skill.TRADING) + (ct.trait == com.colonysmp.model.Trait.CHARISMATIC ? 2 : 0));
        return best;
    }

    /** How much the colony would get for selling (null if it can't). */
    public String sell(Player by, Colony c, String good, long qty, boolean quiet) {
        if (by != null && !plugin.access().can(by, c, Perm.TRADE)) return "You may not trade for " + Text.esc(c.name) + ".";
        if (tradingPost(c) == null) return "You need a Trading Post with a merchant at work to trade on the market.";
        if (!Goods.known(good)) return "Unknown good.";
        if (Treasury.isValuable(good)) return Goods.get(good).name() + " is what the market pays with; it isn't for sale.";
        if (c.storage.available(good) <= 0) return "Storage has no " + Goods.get(good).name() + " to sell.";
        qty = Math.min(qty, plugin.sim().finances().spare(c, good));
        if (qty <= 0) return "That food is the colony's reserve (" + c.policies.foodReserveDays + " days, see the policies).";
        int skill = merchantSkill(c);
        long price = market.quoteSale(good, qty, skill);
        if (price <= 0) return "Nobody will pay for that.";
        long got = c.storage.take(good, qty);
        if (got < qty) price = market.quoteSale(good, got, skill);
        market.recordSale(good, got);
        long day = plugin.sim().day(c);
        c.treasury.credit(price, TxnType.SALE, "Sold " + got + " " + Goods.get(good).name(), System.currentTimeMillis(), day);
        for (Citizen m : c.citizens.values()) if (m.job == com.colonysmp.model.Job.MERCHANT && m.status.free()) m.skills.gain(Skill.TRADING, got * 0.2);
        if (!quiet) c.log(ColonyEvent.Type.ECONOMY, day, (by == null ? "Sold " : by.getName() + " sold ") + got + " " + Goods.get(good).name() + " for " + Text.value(price) + ".");
        plugin.requestSave();
        return null;
    }

    public String buy(Player by, Colony c, String good, long qty, boolean quiet) {
        if (by != null && !plugin.access().can(by, c, Perm.TRADE)) return "You may not trade for " + Text.esc(c.name) + ".";
        if (tradingPost(c) == null) return "You need a Trading Post with a merchant at work to trade on the market.";
        if (!Goods.known(good) || Goods.get(good).custom() && !good.equals(Goods.FLOUR) || Treasury.isValuable(good)) return "The market doesn't sell that.";
        qty = Math.min(qty, c.storage.room(good));
        if (qty <= 0) return "There's no room in storage for " + Goods.get(good).name() + ".";
        int skill = merchantSkill(c);
        long cost = market.quotePurchase(good, qty, skill);
        long day = plugin.sim().day(c);
        if (c.treasury.debit(cost, TxnType.PURCHASE, "Bought " + qty + " " + Goods.get(good).name(), System.currentTimeMillis(), day) == null) {
            return "That costs " + Text.value(cost) + "; the treasury has " + Text.value(c.treasury.balance()) + ".";
        }
        long added = c.storage.add(good, qty);
        market.recordPurchase(good, added);
        if (!quiet) c.log(ColonyEvent.Type.ECONOMY, day, (by == null ? "Bought " : by.getName() + " bought ") + added + " " + Goods.get(good).name() + " for " + Text.value(cost) + ".");
        plugin.requestSave();
        return null;
    }

    /** Every second: market prices drift back toward normal. */
    public void tick() {
        market.recover(1.0 / 50);
    }

    /** Once a day: the auto-sell and auto-buy policies. */
    public void daily(Colony c, long day) {
        if (tradingPost(c) == null || !c.policies.autoSell && !c.policies.autoBuy) return;
        var prod = plugin.sim().production();
        long budget = 64L * Math.max(1, c.workers(tradingPost(c)).size()) * 4;
        long earned = 0, spent = 0;
        List<String> sold = new ArrayList<>(), bought = new ArrayList<>();
        if (c.policies.autoSell) {
            for (Map.Entry<String, Long> e : new ArrayList<>(c.storage.contents().entrySet())) {
                if (budget <= 0) break;
                String k = e.getKey();
                if (Goods.get(k).custom() && !k.equals(Goods.FLOUR) || Treasury.isValuable(k)) continue;
                long surplus = Math.min(plugin.sim().finances().spare(c, k), c.storage.available(k) - prod.max(c, k));
                if (surplus <= 0) continue;
                long n = Math.min(surplus, budget);
                long before = c.treasury.balance();
                if (sell(null, c, k, n, true) == null) {
                    earned += c.treasury.balance() - before;
                    budget -= n;
                    sold.add(n + " " + Goods.get(k).name());
                }
            }
        }
        if (c.policies.autoBuy) {
            long reserve = plugin.sim().finances().dailyCosts(c) * 2;
            for (Map.Entry<String, int[]> e : c.targets.entrySet()) {
                if (budget <= 0) break;
                long lack = e.getValue()[0] - prod.stock(c, e.getKey());
                if (lack <= 0) continue;
                long n = Math.min(lack, budget);
                long cost = market.quotePurchase(e.getKey(), n, merchantSkill(c));
                if (c.treasury.balance() - cost < reserve) continue;
                if (buy(null, c, e.getKey(), n, true) == null) {
                    spent += cost;
                    budget -= n;
                    bought.add(n + " " + Goods.get(e.getKey()).name());
                }
            }
        }
        if (!sold.isEmpty()) c.log(ColonyEvent.Type.ECONOMY, day, "The merchants sold " + String.join(", ", sold) + " for " + Text.value(earned) + ".");
        if (!bought.isEmpty()) c.log(ColonyEvent.Type.ECONOMY, day, "The merchants bought " + String.join(", ", bought) + " for " + Text.value(spent) + ".");
    }

    // ───────────── shopping at a colony's market ─────────────

    /** The colony's staffed Market (players can shop there), or null. */
    public Building shop(Colony c) {
        return plugin.sim().citizens().staffed(c, BuildingType.MARKET);
    }

    /** What a player pays a colony's market for one of a good. */
    public long shopPrice(Colony c, String good) {
        return Math.max(1, Math.round(market.mid(good) * plugin.settings().shopMarkup));
    }

    /** What a colony's market pays a player for one of a good. */
    public long shopOffer(Colony c, String good) {
        return Math.max(0, (long) Math.floor(market.mid(good) / plugin.settings().shopMarkup));
    }

    /** Goods a colony's market will sell to players: what it has beyond its own needs. */
    public long forSale(Colony c, String good) {
        return Math.max(0, Math.min(plugin.sim().finances().spare(c, good), c.storage.available(good) - plugin.sim().production().max(c, good) / 2));
    }

    public String shopBuy(Player p, Colony c, String good, int qty) {
        if (shop(c) == null) return Text.esc(c.name) + " has no Market open (it needs a shopkeeper at work).";
        if (!plugin.diplomacy().canTradeWith(p, c)) return Text.esc(c.name) + " won't trade with you.";
        qty = (int) Math.min(qty, forSale(c, good));
        if (qty <= 0) return Text.esc(c.name) + " has none of that to sell.";
        if (Treasury.isValuable(good)) return "The market doesn't sell valuables: they are what it is paid in.";
        long cost = shopPrice(c, good) * qty;
        if (!fitsAfter(p, Map.of(), Map.of(good, (long) qty))) return "You haven't room for " + qty + " " + Goods.get(good).name() + ".";
        Map<String, Long> paid = chargeItems(p, cost);
        if (paid == null) return "That costs " + Text.value(cost) + ". You have " + money(p) + ".";
        long got = c.storage.take(good, qty);
        for (ItemStack it : ItemGoods.stacks(good, got)) p.getInventory().addItem(it);
        c.treasury.creditItems(paid, TxnType.SERVICES, p.getName() + " bought " + got + " " + Goods.get(good).name() + " at the market for " + Text.items(paid), System.currentTimeMillis(), plugin.sim().day(c));
        plugin.requestSave();
        return null;
    }

    /** A player sells what's in their hand to a colony's market. */
    public String shopSell(Player p, Colony c, ItemStack hand) {
        if (shop(c) == null) return Text.esc(c.name) + " has no Market open (it needs a shopkeeper at work).";
        if (!plugin.diplomacy().canTradeWith(p, c)) return Text.esc(c.name) + " won't trade with you.";
        String good = ItemGoods.goodOf(hand);
        if (good == null) return "The market only buys plain goods.";
        if (Treasury.isValuable(good)) return "Valuables go into the treasury: use /colony deposit.";
        long each = shopOffer(c, good);
        if (each <= 0) return "The market won't pay for that.";
        int qty = (int) Math.min(hand.getAmount(), c.storage.room(good));
        if (qty <= 0) return Text.esc(c.name) + " has no room for that.";
        long pay = each * qty;
        long day = plugin.sim().day(c), now = System.currentTimeMillis();
        if (!c.treasury.canAfford(pay)) return Text.esc(c.name) + "'s treasury can't afford it.";
        // planned on a copy of the treasury, so nothing moves unless the player has room for the payment
        Map<String, Long> planned = planPayout(c, pay);
        if (planned == null || !fitsAfter(p, Map.of(good, (long) qty), planned)) return "You haven't room for the payment.";
        Map<String, Long> items = c.treasury.debitItems(pay, TxnType.PURCHASE, "Bought " + qty + " " + Goods.get(good).name() + " from " + p.getName(), now, day);
        if (items == null) return Text.esc(c.name) + "'s treasury can't afford it.";
        hand.setAmount(hand.getAmount() - qty);
        c.storage.add(good, qty);
        give(p, items);
        Text.send(p, "<gray>Paid " + Text.items(items) + ".");
        plugin.requestSave();
        return null;
    }

    // ───────────── persistence ─────────────

    public void snapshot(Snapshot s) {
        s.market.putAll(market.all());
    }

    public void load(Snapshot s) {
        market.load(s.market);
    }
}
