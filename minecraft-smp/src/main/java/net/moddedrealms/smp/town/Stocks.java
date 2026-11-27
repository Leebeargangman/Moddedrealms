package net.moddedrealms.smp.town;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.moddedrealms.smp.SmpPlugin;
import net.moddedrealms.smp.town.Businesses.Biz;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The stock exchange: a share in every business in town. A share's price follows the business (its takings last
 * cycle, its rating, its level and its cash, less when it's closed or damaged) times the market's mood, which wanders,
 * reacts to buying and selling, and takes a knock when disaster hits the town. Shares are bought and sold from the
 * bank account (a small broker's fee each way) and pay a dividend once a day. Data in stocks.yml.
 */
final class Stocks implements TabExecutor, Listener {
    private final TownManager t;
    private final File file;
    private final Random r = new Random();
    private final Map<String, Share> shares = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Integer>> holdings = new HashMap<>();
    private final Map<UUID, Map<String, Double>> paid = new HashMap<>();
    private String lastDividend = "";
    private int tick;
    private boolean dirty;

    static final class Share {
        String biz, ticker;
        double price, mood = 1;
        final List<Double> history = new ArrayList<>(); // every update, the newest last (about two days)
    }

    Stocks(TownManager t) {
        this.t = t;
        this.file = new File(t.plugin().getDataFolder(), "stocks.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        lastDividend = y.getString("last-dividend", "");
        ConfigurationSection s = y.getConfigurationSection("shares");
        if (s != null) {
            for (String id : s.getKeys(false)) {
                Share sh = new Share();
                sh.biz = id;
                sh.ticker = s.getString(id + ".ticker", id.substring(0, Math.min(4, id.length())).toUpperCase(Locale.ROOT));
                sh.price = s.getDouble(id + ".price");
                sh.mood = s.getDouble(id + ".mood", 1);
                sh.history.addAll(s.getDoubleList(id + ".history"));
                shares.put(id, sh);
            }
        }
        ConfigurationSection h = y.getConfigurationSection("holdings");
        if (h != null) {
            for (String u : h.getKeys(false)) {
                Map<String, Integer> m = new HashMap<>();
                Map<String, Double> c = new HashMap<>();
                ConfigurationSection hs = h.getConfigurationSection(u);
                for (String id : hs.getKeys(false)) {
                    m.put(id, hs.getInt(id + ".shares"));
                    c.put(id, hs.getDouble(id + ".paid"));
                }
                holdings.put(UUID.fromString(u), m);
                paid.put(UUID.fromString(u), c);
            }
        }
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    boolean enabled() {
        return cfg().getBoolean("town.stocks.enabled", true);
    }

    void save() {
        if (!dirty) return;
        dirty = false;
        YamlConfiguration y = new YamlConfiguration();
        y.set("last-dividend", lastDividend);
        for (Share s : shares.values()) {
            String p = "shares." + s.biz + ".";
            y.set(p + "ticker", s.ticker);
            y.set(p + "price", Math.round(s.price * 100) / 100.0);
            y.set(p + "mood", Math.round(s.mood * 10000) / 10000.0);
            y.set(p + "history", s.history);
        }
        for (var e : holdings.entrySet()) {
            for (var h : e.getValue().entrySet()) {
                if (h.getValue() <= 0) continue;
                String p = "holdings." + e.getKey() + "." + h.getKey() + ".";
                y.set(p + "shares", h.getValue());
                y.set(p + "paid", Math.round(paid.getOrDefault(e.getKey(), Map.of()).getOrDefault(h.getKey(), 0.0) * 100) / 100.0);
            }
        }
        try {
            y.save(file);
        } catch (IOException e) {
            t.plugin().getLogger().warning("Couldn't save stocks.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ prices

    private double fundamental(Biz b) {
        double v = 10 + Math.sqrt(Math.max(0, b.revenue)) * 1.5 + b.rating * 3 + b.level * 3 + Math.max(0, Math.min(20, b.cash / 1000)) - Math.max(0, b.damage) * 0.1;
        if (!b.open()) v *= 0.7;
        return Math.max(1, v);
    }

    private Share share(Biz b) {
        Share s = shares.get(b.id);
        if (s == null) {
            s = new Share();
            s.biz = b.id;
            s.ticker = ticker(b.name);
            s.price = Math.round(fundamental(b) * 100) / 100.0;
            shares.put(b.id, s);
            dirty = true;
        }
        return s;
    }

    private String ticker(String name) {
        StringBuilder b = new StringBuilder();
        for (String w : name.replaceAll("[^A-Za-z ]", "").split(" ")) if (!w.isEmpty() && b.length() < 4 && !w.equalsIgnoreCase("the")) b.append(Character.toUpperCase(w.charAt(0)));
        String base = b.length() < 2 ? name.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT) : b.toString();
        base = base.length() > 4 ? base.substring(0, 4) : base;
        if (base.isEmpty()) base = "BIZ";
        String tk = base;
        int n = 2;
        while (byTicker(tk) != null) tk = base + n++;
        return tk;
    }

    private Share byTicker(String tk) {
        for (Share s : shares.values()) if (s.ticker.equalsIgnoreCase(tk)) return s;
        return null;
    }

    private List<Biz> listed() {
        List<Biz> out = new ArrayList<>();
        for (Biz b : t.businesses().all()) if (!b.rival) out.add(b);
        return out;
    }

    /** Every second; prices move every few minutes. */
    void second() {
        if (!enabled() || ++tick % (60 * Math.max(1, cfg().getInt("town.stocks.update-minutes", 10))) != 0) return;
        for (Biz b : listed()) {
            Share s = share(b);
            s.mood *= Math.exp(r.nextGaussian() * cfg().getDouble("town.stocks.volatility", 0.02));
            s.mood += (1 - s.mood) * 0.03;
            s.mood = Math.max(0.4, Math.min(2.5, s.mood));
            double target = fundamental(b) * s.mood;
            double before = s.price;
            s.price = Math.max(0.5, Math.round((s.price + (target - s.price) * 0.25) * 100) / 100.0);
            s.history.add(s.price);
            while (s.history.size() > 300) s.history.remove(0);
            double moved = before <= 0 ? 0 : (s.price - before) / before;
            if (Math.abs(moved) >= 0.12) t.news(b.name + " shares " + (moved > 0 ? "jumped " : "slumped ") + Math.round(Math.abs(moved) * 100) + "% on the exchange.");
        }
        dirty = true;
        dividends();
        save();
    }

    /** Once a day: town-run businesses pay 1% of the share price, player businesses out of their own cash. */
    private void dividends() {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        if (today.equals(lastDividend)) return;
        lastDividend = today;
        double rate = cfg().getDouble("town.stocks.dividend", 0.01);
        Map<String, Integer> totalHeld = new HashMap<>();
        for (Map<String, Integer> h : holdings.values()) for (var e : h.entrySet()) totalHeld.merge(e.getKey(), e.getValue(), Integer::sum);
        for (var e : holdings.entrySet()) {
            double sum = 0;
            for (var h : e.getValue().entrySet()) {
                Share s = shares.get(h.getKey());
                Biz b = t.businesses().get(h.getKey());
                if (s == null || b == null || h.getValue() <= 0 || !b.open()) continue;
                double per = s.price * rate;
                if (b.playerOwned()) {
                    int held = Math.max(1, totalHeld.getOrDefault(b.id, 1));
                    per = Math.min(per, Math.max(0, b.cash) * 0.2 / held);
                    b.cash -= per * h.getValue();
                }
                sum += per * h.getValue();
            }
            if (sum >= 0.01) {
                OfflinePlayer o = Bukkit.getOfflinePlayer(e.getKey());
                t.money.bankGive(o, sum);
                if (o.getPlayer() != null) o.getPlayer().sendMessage(SmpPlugin.mm("<gold>[Exchange] <gray>Today's dividends: <green>+" + Money.fmt2(sum) + "</green> to your bank account."));
            }
        }
    }

    /** Disaster or scandal in a town: its businesses' shares take a knock (factor 0.85 = -15% mood). */
    void shock(String town, double factor) {
        for (Biz b : listed()) {
            if (town != null && !town.equalsIgnoreCase(b.town)) continue;
            Share s = share(b);
            s.mood = Math.max(0.4, s.mood * factor);
            s.price = Math.max(0.5, Math.round(s.price * factor * 100) / 100.0);
        }
        dirty = true;
    }

    private double change24(Share s) {
        int per = 60 / Math.max(1, cfg().getInt("town.stocks.update-minutes", 10)) * 24;
        if (s.history.size() < 2) return 0;
        double then = s.history.get(Math.max(0, s.history.size() - 1 - per));
        return then <= 0 ? 0 : (s.price - then) / then;
    }

    // ------------------------------------------------------------------ trading

    private double fee() {
        return cfg().getDouble("town.stocks.fee", 0.01);
    }

    String buy(Player p, Share s, int n) {
        if (n <= 0) return "<red>Buy at least one share.";
        Biz b = t.businesses().get(s.biz);
        if (b == null) return "<red>That business is gone.";
        int max = cfg().getInt("town.stocks.max-shares-per-business", 500);
        int have = holdings.getOrDefault(p.getUniqueId(), Map.of()).getOrDefault(s.biz, 0);
        n = Math.min(n, max - have);
        if (n <= 0) return "<red>You hold the most shares anyone may (" + max + ").";
        double cost = Math.round(s.price * n * (1 + fee()) * 100) / 100.0;
        if (!t.money.bankPay(p, cost)) return "<red>That's " + Money.fmt2(cost) + " and your bank account holds " + Money.fmt2(t.money.bankBalance(p)) + ". <gray>(/bank deposit at the bank)";
        holdings.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).merge(s.biz, n, Integer::sum);
        paid.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).merge(s.biz, cost, Double::sum);
        s.mood = Math.min(2.5, s.mood * (1 + Math.min(0.08, 0.002 * n)));
        dirty = true;
        save();
        return "<green>Bought " + n + " " + s.ticker + " (" + b.name + ") for " + Money.fmt2(cost) + ".";
    }

    String sell(Player p, Share s, int n) {
        Map<String, Integer> h = holdings.get(p.getUniqueId());
        int have = h == null ? 0 : h.getOrDefault(s.biz, 0);
        n = Math.min(n, have);
        if (n <= 0) return "<red>You don't own any " + s.ticker + ".";
        double got = Math.round(s.price * n * (1 - fee()) * 100) / 100.0;
        double cost = paid.getOrDefault(p.getUniqueId(), Map.of()).getOrDefault(s.biz, 0.0);
        double costPart = have == 0 ? 0 : cost * n / have;
        h.put(s.biz, have - n);
        paid.get(p.getUniqueId()).put(s.biz, cost - costPart);
        s.mood = Math.max(0.4, s.mood * (1 - Math.min(0.08, 0.002 * n)));
        dirty = true;
        save(); // the shares are gone before the money arrives
        t.money.bankGive(p, got);
        double pl = got - costPart;
        return "<green>Sold " + n + " " + s.ticker + " for " + Money.fmt2(got) + " <gray>(" + (pl >= 0 ? "<green>+" : "<red>-") + Money.fmt2(Math.abs(pl)) + "<gray>) to your bank account.";
    }

    // ------------------------------------------------------------------ menu

    static final class Menu implements InventoryHolder {
        final List<String> ids = new ArrayList<>();
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    void open(Player p) {
        Menu m = new Menu();
        m.inv = Bukkit.createInventory(m, 54, SmpPlugin.mm("Stock exchange"));
        Map<String, Integer> mine = holdings.getOrDefault(p.getUniqueId(), Map.of());
        int slot = 0;
        for (Biz b : listed()) {
            if (slot >= 45) break;
            Share s = share(b);
            int have = mine.getOrDefault(b.id, 0);
            double ch = change24(s);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + b.kind.label + (b.playerOwned() ? " · " + nameOf(b.owner) + "'s" : " · town-run") + (b.open() ? "" : " · <red>closed"));
            lore.add("<gray>Price <gold>" + Money.fmt2(s.price) + "</gold> " + (ch >= 0.005 ? "<green>▲ " : ch <= -0.005 ? "<red>▼ " : "<gray>■ ") + Math.round(Math.abs(ch) * 100) + "%<gray> today");
            lore.add("<gray>Rating " + String.format(Locale.ROOT, "%.1f", b.rating) + "★ · level " + b.level + " · takings " + Money.fmt(b.revenue) + " last cycle");
            if (have > 0) {
                double val = have * s.price;
                double cost = paid.getOrDefault(p.getUniqueId(), Map.of()).getOrDefault(b.id, 0.0);
                lore.add("<white>You own " + have + " · worth " + Money.fmt2(val) + " " + (val >= cost ? "<green>+" : "<red>-") + Money.fmt2(Math.abs(val - cost)));
            }
            lore.add("");
            lore.add("<yellow>Left-click <gray>buy 1 · <yellow>shift <gray>buy 10");
            lore.add("<yellow>Right-click <gray>sell 1 · <yellow>shift <gray>sell all");
            m.inv.setItem(slot, icon(b.kind.station == null ? Material.EMERALD : b.kind.station, "<white>" + s.ticker + " <gray>· " + b.name, lore));
            m.ids.add(b.id);
            slot++;
        }
        ItemStack glass = icon(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 45; i < 54; i++) m.inv.setItem(i, glass);
        double port = 0;
        for (var e : mine.entrySet()) {
            Share s = shares.get(e.getKey());
            if (s != null) port += s.price * e.getValue();
        }
        m.inv.setItem(49, icon(Material.BOOK, "<gold>Your portfolio: " + Money.fmt2(port), List.of("<gray>Bank account: <white>" + Money.fmt2(t.money.bankBalance(p)),
                "<gray>Trades use your bank account, with a " + Math.round(fee() * 1000) / 10.0 + "% fee.", "<gray>Dividends are paid daily.")));
        p.openInventory(m.inv);
    }

    private static String nameOf(UUID u) {
        String n = u == null ? null : Bukkit.getOfflinePlayer(u).getName();
        return n == null ? "?" : n;
    }

    private static ItemStack icon(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m.isItem() ? m : Material.PAPER);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(SmpPlugin.mm("<!italic>" + name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(SmpPlugin.mm("<!italic>" + s));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= m.ids.size()) return;
        Share s = shares.get(m.ids.get(slot));
        if (s == null) return;
        ClickType c = e.getClick();
        String msg;
        if (c == ClickType.LEFT) msg = buy(p, s, 1);
        else if (c == ClickType.SHIFT_LEFT) msg = buy(p, s, 10);
        else if (c == ClickType.RIGHT) msg = sell(p, s, 1);
        else if (c == ClickType.SHIFT_RIGHT) msg = sell(p, s, Integer.MAX_VALUE);
        else return;
        p.sendMessage(SmpPlugin.mm(msg));
        p.playSound(p.getLocation(), msg.startsWith("<green>") ? Sound.ENTITY_EXPERIENCE_ORB_PICKUP : Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 1.2f);
        open(p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!(sender instanceof Player p)) return true;
        if (!enabled()) {
            p.sendMessage(SmpPlugin.mm("<gray>The exchange is closed."));
            return true;
        }
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> open(p);
            case "buy", "sell" -> {
                Share s = a.length > 1 ? byTicker(a[1]) : null;
                if (s == null) {
                    p.sendMessage(SmpPlugin.mm("<red>/stocks " + sub + " <ticker> <shares> <gray>(tickers are in /stocks)"));
                    return true;
                }
                int n = 1;
                if (a.length > 2) {
                    if (a[2].equalsIgnoreCase("all")) n = Integer.MAX_VALUE;
                    else try {
                        n = Integer.parseInt(a[2]);
                    } catch (NumberFormatException e) {
                        n = 0;
                    }
                }
                p.sendMessage(SmpPlugin.mm(sub.equals("buy") ? buy(p, s, n) : sell(p, s, n)));
            }
            case "portfolio", "mine" -> {
                Map<String, Integer> mine = holdings.getOrDefault(p.getUniqueId(), Map.of());
                double total = 0;
                p.sendMessage(SmpPlugin.mm("<gold>Your shares:"));
                for (var e : mine.entrySet()) {
                    Share s = shares.get(e.getKey());
                    if (s == null || e.getValue() <= 0) continue;
                    total += s.price * e.getValue();
                    p.sendMessage(SmpPlugin.mm(" <white>" + s.ticker + " <gray>× " + e.getValue() + " · " + Money.fmt2(s.price * e.getValue())));
                }
                p.sendMessage(SmpPlugin.mm("<gray>Total " + Money.fmt2(total)));
            }
            default -> p.sendMessage(SmpPlugin.mm("<gray>/stocks <white>[buy|sell <ticker> <n>|portfolio]"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) for (String x : List.of("buy", "sell", "portfolio")) if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(x);
        if (a.length == 2) for (Share sh : shares.values()) if (sh.ticker.toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(sh.ticker);
        return out;
    }
}
