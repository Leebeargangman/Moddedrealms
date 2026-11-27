package net.moddedrealms.smp.town;

import com.colonysmp.api.ColonyApi;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * The bridge to ColonySMP. Colonies stay barter economies (their treasury is metal and gems), but they meet the
 * town's money economy at the Old Town market hall: a colony's owner can sell what the colony can spare to the
 * exchange for dollars, and anyone in a colony can buy wares there and have them carted to colony storage. Both move
 * the exchange's prices like any other trade, and the town's businesses buy from the same exchange, so colony
 * produce ends up in the town's bakeries and smithies. Colony news goes to the Herald, and the towns are kept free of
 * colony land.
 *
 * <p>This is the only class that touches ColonySMP's classes; it exists only when ColonySMP is enabled.
 */
final class ColonyLink implements Listener {
    private final TownManager t;
    private final Map<String, Long> lastNews = new HashMap<>();
    private long hourStart;
    private int newsThisHour, ticks;

    ColonyLink(TownManager t) {
        this.t = t;
    }

    /** True when ColonySMP is installed and new enough to have the API. */
    static boolean available() {
        if (!Bukkit.getPluginManager().isPluginEnabled("ColonySMP")) return false;
        try {
            Class.forName("com.colonysmp.api.ColonyApi");
            return ColonyApi.ready();
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return false;
        }
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    void start() {
        Bukkit.getPluginManager().registerEvents(this, t.plugin());
        zones();
        Bukkit.getScheduler().runTaskTimer(t.plugin(), this::news, 200L, 200L);
    }

    /** Keeps colonies out of the Old Town and Meadowbrook (ColonySMP refuses land there). */
    void zones() {
        if (!cfg().getBoolean("town.colonies.keep-out-of-towns", true)) {
            ColonyApi.unprotect("mrsmp:oldtown");
            ColonyApi.unprotect("mrsmp:meadowbrook");
            return;
        }
        String world = cfg().getString("town.world", "world");
        ColonyApi.protect("mrsmp:oldtown", world, (int) cfg().getDouble("town.center-x"), (int) cfg().getDouble("town.center-z"),
                cfg().getInt("town.colonies.old-town-radius", 320), "the Old Town");
        Meadowbrook m = t.meadowbrook();
        List<Integer> c = m == null || !m.built() ? List.of() : m.data().getIntegerList("centre");
        if (c.size() >= 3) {
            ColonyApi.protect("mrsmp:meadowbrook", m.data().getString("world", world), c.get(0), c.get(2),
                    cfg().getInt("town.colonies.meadowbrook-radius", 200), "Meadowbrook");
        }
    }

    // ------------------------------------------------------------------ the Herald

    /** Colony happenings worth printing: foundings, raids, wars, milestones, colonies emptying and filling. */
    private void news() {
        if (++ticks % 30 == 0) zones(); // Meadowbrook may have been built since
        List<ColonyApi.News> all = ColonyApi.drainNews();
        if (!cfg().getBoolean("town.colonies.herald", true)) return;
        long now = System.currentTimeMillis();
        if (now - hourStart > 3_600_000L) {
            hourStart = now;
            newsThisHour = 0;
        }
        int perHour = cfg().getInt("town.colonies.herald-per-hour", 6);
        for (ColonyApi.News n : all) {
            if (!worthPrinting(n) || newsThisHour >= perHour) continue;
            Long last = lastNews.get(n.colonyId());
            if (last != null && now - last < 10 * 60_000L) continue;
            lastNews.put(n.colonyId(), now);
            newsThisHour++;
            String msg = n.message().replaceAll("<[^>]*>", "");
            t.news(msg.toLowerCase(Locale.ROOT).contains(n.colony().toLowerCase(Locale.ROOT)) ? "Out in the wilds: " + msg
                    : "In the colony of " + n.colony() + ": " + msg);
        }
    }

    static boolean worthPrinting(ColonyApi.News n) {
        String m = n.message().toLowerCase(Locale.ROOT);
        if (m.startsWith("day ")) return false; // the daily report
        return switch (n.type()) {
            case "MILITARY", "DIPLOMACY" -> true;
            case "COLONY" -> m.contains("founded") || m.contains("resettle") || m.contains("milestone") || m.contains("level") || m.contains("festival");
            case "POPULATION" -> m.contains("settlers arrived") || m.contains("married") || m.contains("refugee");
            default -> false;
        };
    }

    // ------------------------------------------------------------------ for the welcome and the guide

    /** Colonies taking new members, biggest first. */
    List<ColonyApi.Summary> recruiting() {
        List<ColonyApi.Summary> out = new ArrayList<>();
        for (ColonyApi.Summary s : ColonyApi.colonies()) if (s.recruiting()) out.add(s);
        out.sort((a, b) -> Integer.compare(b.population(), a.population()));
        return out;
    }

    boolean landAt(org.bukkit.Location l) {
        return ColonyApi.at(l) != null;
    }

    List<ColonyApi.Summary> bySize() {
        List<ColonyApi.Summary> out = new ArrayList<>(ColonyApi.colonies());
        out.sort((a, b) -> Integer.compare(b.population(), a.population()));
        return out;
    }

    ColonyApi.Summary colonyOf(Player p) {
        return ColonyApi.of(p.getUniqueId());
    }

    // ------------------------------------------------------------------ colony freight at the market

    static final class Menu implements InventoryHolder {
        final String colony;
        final boolean sell;
        final Market.Cat cat;
        Inventory inv;
        final Map<Integer, Market.Ware> slots = new HashMap<>();

        Menu(String colony, boolean sell, Market.Cat cat) {
            this.colony = colony;
            this.sell = sell;
            this.cat = cat;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    /** /market colony: the freight desk. */
    void open(Player p) {
        open(p, true, null);
    }

    private void open(Player p, boolean sell, Market.Cat cat) {
        ColonyApi.Summary s = colonyOf(p);
        if (s == null) {
            p.sendMessage(SmpPlugin.mm("<gray>You don't belong to a colony. Found one out in the wilds with <white>/colony create <name></white>, "
                    + "or join one: <white>/colonies</white>."));
            return;
        }
        Menu m = new Menu(s.id(), sell, cat);
        String title = sell ? "Freight: sell from " + s.name() : cat == null ? "Freight: buy for " + s.name() : cat.label + " for " + s.name();
        m.inv = Bukkit.createInventory(m, 54, SmpPlugin.mm(title.length() > 40 ? title.substring(0, 40) : title));
        fill(m, p);
        p.openInventory(m.inv);
    }

    private static ItemStack icon(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        var meta = it.getItemMeta();
        meta.displayName(SmpPlugin.mm("<!italic>" + name));
        List<net.kyori.adventure.text.Component> l = new ArrayList<>();
        for (String s : lore) l.add(SmpPlugin.mm("<!italic>" + s));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    private void fill(Menu m, Player p) {
        Market market = t.market();
        Inventory inv = m.inv;
        inv.clear();
        m.slots.clear();
        ItemStack glass = icon(Material.GREEN_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 9; i++) {
            inv.setItem(i, glass);
            inv.setItem(45 + i, glass);
        }
        boolean here = market.canTrade(p);
        List<String> help = new ArrayList<>(List.of("<gray>The town's carters run goods between", "<gray>your colony's storage and the exchange.",
                "<gray>Prices are the exchange's own: selling", "<gray>pushes them down, buying pushes them up.", ""));
        if (!here) help.add("<yellow>Trade from inside the market hall.");
        inv.setItem(4, icon(Material.CHEST_MINECART, "<gold>Colony freight", help));
        inv.setItem(48, icon(m.sell ? Material.LIME_DYE : Material.GRAY_DYE, (m.sell ? "<green>▶ " : "<gray>") + "Sell colony goods",
                List.of("<gray>What the colony can spare, for dollars", "<gray>(owner and co-owners).")));
        inv.setItem(50, icon(!m.sell ? Material.LIME_DYE : Material.GRAY_DYE, (!m.sell ? "<green>▶ " : "<gray>") + "Buy for the colony",
                List.of("<gray>Pay with your dollars; the goods", "<gray>are carted to colony storage.")));
        if (m.sell) {
            int slot = 9;
            Map<Material, Long> spare = ColonyApi.spare(m.colony);
            boolean may = ColonyApi.maySell(p, m.colony);
            for (Map.Entry<Material, Long> e : spare.entrySet()) {
                Market.Ware w = market.ware(e.getKey());
                if (w == null || w.mat != e.getKey() || slot >= 45) continue;
                int n = (int) Math.min(Integer.MAX_VALUE, e.getValue());
                List<String> lore = new ArrayList<>();
                lore.add("<gray>The colony can spare <white>" + n);
                lore.add("<gray>Exchange pays <white>" + Market.price(market.sellPrice(w)) + "</white> each");
                lore.add("<gray>All of it: about <white>" + Money.fmt(market.quoteSell(w, Math.min(n, market.room(w)))));
                lore.add("");
                if (may && here) lore.add("<yellow>Left-click <gray>sell 16 · <yellow>shift-click <gray>sell all spare");
                else if (!may) lore.add("<dark_gray>Only those who may spend the treasury sell.");
                else lore.add("<dark_gray>Trade from inside the market hall.");
                inv.setItem(slot, icon(w.mat, "<white>" + w.label(), lore));
                m.slots.put(slot, w);
                slot++;
            }
            if (slot == 9) {
                inv.setItem(22, icon(Material.BARRIER, "<gray>Nothing to sell", List.of("<gray>Your colony has nothing the exchange", "<gray>trades beyond what it needs itself",
                        "<gray>(food above its reserve counts).")));
            }
        } else if (m.cat == null) {
            int[] slots = {19, 20, 21, 22, 23, 24, 25, 31};
            Market.Cat[] cats = Market.Cat.values();
            for (int i = 0; i < cats.length; i++) {
                inv.setItem(slots[i], icon(cats[i].icon, "<gold>" + cats[i].label, List.of("<yellow>Click to choose wares")));
            }
        } else {
            int slot = 9;
            boolean may = ColonyApi.mayDeliver(p, m.colony);
            for (Market.Ware w : market.wares(m.cat)) {
                if (slot >= 45) break;
                long room = ColonyApi.room(m.colony, w.mat);
                List<String> lore = new ArrayList<>();
                lore.add("<gray>Costs <white>" + Market.price(market.buyPrice(w)) + "</white> each · exchange has <white>" + market.available(w));
                lore.add("<gray>Colony storage has room for <white>" + (room > 100_000 ? "plenty" : String.valueOf(room)));
                lore.add("");
                if (may && here) lore.add("<yellow>Left-click <gray>buy 16 · <yellow>shift-click <gray>buy 64");
                else if (!may) lore.add("<dark_gray>You may not put goods in this colony's storage.");
                else lore.add("<dark_gray>Trade from inside the market hall.");
                inv.setItem(slot, icon(w.mat, "<white>" + w.label(), lore));
                m.slots.put(slot, w);
                slot++;
            }
            inv.setItem(49, icon(Material.ARROW, "<yellow>Back", List.of()));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        if (slot == 48 && !m.sell) {
            open(p, true, null);
            return;
        }
        if (slot == 50 && m.sell) {
            open(p, false, null);
            return;
        }
        if (slot == 49 && !m.sell && m.cat != null) {
            open(p, false, null);
            return;
        }
        if (!m.sell && m.cat == null) {
            int[] slots = {19, 20, 21, 22, 23, 24, 25, 31};
            for (int i = 0; i < slots.length; i++) if (slot == slots[i]) open(p, false, Market.Cat.values()[i]);
            return;
        }
        Market.Ware w = m.slots.get(slot);
        if (w == null) return;
        boolean shift = e.getClick() == ClickType.SHIFT_LEFT || e.getClick() == ClickType.SHIFT_RIGHT;
        String msg = m.sell ? sell(p, m.colony, w, shift ? Integer.MAX_VALUE : 16) : buy(p, m.colony, w, shift ? 64 : 16);
        p.sendMessage(SmpPlugin.mm(msg));
        p.playSound(p.getLocation(), msg.startsWith("<green>") ? Sound.ENTITY_EXPERIENCE_ORB_PICKUP : Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 1.2f);
        fill(m, p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    /** Sells up to {@code want} of what the colony can spare; the dollars go to the player who sold. */
    String sell(Player p, String colony, Market.Ware w, int want) {
        Market market = t.market();
        if (!market.canTrade(p)) return "<red>Colony freight is handled inside the market hall.";
        if (!ColonyApi.maySell(p, colony)) return "<red>Only those who may spend the colony's treasury sell its goods.";
        long spare = ColonyApi.spare(colony).getOrDefault(w.mat, 0L);
        int n = (int) Math.min(Math.min(want, spare), market.room(w));
        if (spare <= 0) return "<red>The colony has no " + w.label().toLowerCase(Locale.ROOT) + " to spare.";
        if (n <= 0) return "<red>The exchange is flooded with " + w.label().toLowerCase(Locale.ROOT) + ".";
        double got = Math.floor(market.quoteSell(w, n));
        if (got < 1) return "<red>That's worth less than $1. <gray>Sell more at once.";
        long taken = ColonyApi.take(colony, w.mat, n, p.getName() + " sold " + n + " " + w.label().toLowerCase(Locale.ROOT) + " at the Old Town market for " + Money.fmt(got) + ".");
        if (taken <= 0) return "<red>The carters found nothing to take.";
        if (taken < n) got = Math.floor(market.quoteSell(w, (int) taken));
        w.stock += taken;
        w.traded += taken;
        market.markDirty();
        t.money.give(p, got);
        if (taken >= cfg().getInt("town.market.news-units", 256)) {
            ColonyApi.Summary s = ColonyApi.of(p.getUniqueId());
            t.news("A wagon train from " + (s == null ? "a colony" : s.name()) + " brought " + taken + " " + w.label().toLowerCase(Locale.ROOT) + " to the Old Town market.");
        }
        return "<green>The carters brought " + taken + " " + w.label().toLowerCase(Locale.ROOT) + " from your colony: sold for " + Money.fmt(got) + ".";
    }

    /** Buys up to {@code want} with the player's money and carts it to colony storage. */
    String buy(Player p, String colony, Market.Ware w, int want) {
        Market market = t.market();
        if (!market.canTrade(p)) return "<red>Colony freight is handled inside the market hall.";
        if (!ColonyApi.mayDeliver(p, colony)) return "<red>You may not put goods in this colony's storage.";
        int n = (int) Math.min(Math.min(want, market.available(w)), ColonyApi.room(colony, w.mat));
        if (market.available(w) <= 0) return "<red>The exchange is out of " + w.label().toLowerCase(Locale.ROOT) + ".";
        if (n <= 0) return "<red>Colony storage is full. <gray>Build a Warehouse or Granary.";
        double cost = Math.ceil(market.quoteBuy(w, n));
        double bal = t.money.balance(p);
        while (n > 0 && cost > bal) {
            n--;
            cost = Math.ceil(market.quoteBuy(w, n));
        }
        if (n <= 0) return "<red>You can't afford that. <gray>(" + Market.price(market.buyPrice(w)) + " each)";
        if (!t.money.pay(p, cost)) return "<red>Payment failed.";
        long stored = ColonyApi.deliver(colony, w.mat, n, p.getName() + " bought " + n + " " + w.label().toLowerCase(Locale.ROOT) + " at the Old Town market for the colony.");
        if (stored < n) {
            double refund = cost - Math.ceil(market.quoteBuy(w, (int) stored));
            if (refund > 0) t.money.give(p, refund);
            cost -= Math.max(0, refund);
        }
        if (stored <= 0) return "<red>Colony storage is full. <gray>You weren't charged.";
        w.stock -= stored;
        w.traded += stored;
        market.markDirty();
        return "<green>Bought " + stored + " " + w.label().toLowerCase(Locale.ROOT) + " for " + Money.fmt(cost) + ": the carters are taking it to your colony's storage.";
    }

    /** One line per colony, for /mrtown status and admins. */
    List<String> status() {
        List<String> out = new ArrayList<>();
        for (ColonyApi.Summary s : ColonyApi.colonies()) {
            OfflineName owner = new OfflineName(s.owner());
            out.add("<gray>" + s.name() + " <dark_gray>(" + owner + ", " + s.world() + " " + s.x() + " " + s.z() + ")<gray>: " + s.population() + " people, level " + s.level()
                    + (s.graceDays() > 0 ? ", grace " + s.graceDays() + "d" : ""));
        }
        return out;
    }

    private record OfflineName(UUID id) {
        @Override
        public String toString() {
            String n = id == null ? null : Bukkit.getOfflinePlayer(id).getName();
            return n == null ? "?" : n;
        }
    }
}
