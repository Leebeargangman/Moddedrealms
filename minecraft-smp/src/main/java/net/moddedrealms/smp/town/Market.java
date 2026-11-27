package net.moddedrealms.smp.town;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
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
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The central market: a commodity exchange whose prices follow supply. Every ware has a normal stock; the price is
 * the base price times (normal / stock)^k, so each unit sold makes the next one cheaper and each unit bought makes the
 * next one dearer. Dump 600 iron and iron gets very cheap; buy the market out of salmon and salmon gets dear. Stock
 * drifts back towards normal over a few hours (the town eating, building and trading), news-worthy shortages and
 * gluts happen now and then, and there's a spread between buying and selling so flipping never pays.
 *
 * <p>The market also feeds the town's businesses: a bakery with no local wheat supplier buys it here (at the market
 * price, moving it) before it imports at double the fair price.
 */
public final class Market implements Listener {
    public enum Cat {
        ORES("Ores & gems", Material.IRON_INGOT, "smith"),
        FARM("Farm produce", Material.WHEAT, "farmer"),
        MEAT("Meat & fish", Material.BEEF, "butcher"),
        KITCHEN("Bread & cooked food", Material.BREAD, "baker"),
        TIMBER("Timber", Material.OAK_LOG, "woodcutter"),
        STONE("Stone & building", Material.STONE_BRICKS, "mason"),
        CLOTH("Wool, leather & paper", Material.WHITE_WOOL, "weaver"),
        CURIOS("Curios & mob drops", Material.ENDER_PEARL, "curios");

        final String label;
        final Material icon;
        final String vendor;

        Cat(String label, Material icon, String vendor) {
            this.label = label;
            this.icon = icon;
            this.vendor = vendor;
        }

        static Cat parse(String s) {
            for (Cat c : values()) if (c.vendor.equalsIgnoreCase(s) || c.name().equalsIgnoreCase(s)) return c;
            return null;
        }
    }

    /** One traded ware. Stock is what the market holds; normal is where it drifts back to. */
    static final class Ware {
        final Material mat;
        final Cat cat;
        final double base;
        final double normal;
        double stock;
        final List<Double> history = new ArrayList<>(); // hourly mid prices, oldest first
        long traded; // units through the market today

        Ware(Material mat, Cat cat, double base) {
            this.mat = mat;
            this.cat = cat;
            this.base = base;
            this.normal = Math.max(24, Math.min(2000, Math.round(2000 / base)));
            this.stock = normal;
        }

        String label() {
            return nice(mat);
        }
    }

    private static final Object[][] WARES = {
            // ores & gems
            {Material.COAL, Cat.ORES, 3.0}, {Material.CHARCOAL, Cat.ORES, 3.0}, {Material.RAW_IRON, Cat.ORES, 8.0}, {Material.IRON_INGOT, Cat.ORES, 12.0},
            {Material.RAW_COPPER, Cat.ORES, 1.5}, {Material.COPPER_INGOT, Cat.ORES, 2.0}, {Material.RAW_GOLD, Cat.ORES, 22.0}, {Material.GOLD_INGOT, Cat.ORES, 30.0},
            {Material.GOLD_NUGGET, Cat.ORES, 3.0}, {Material.REDSTONE, Cat.ORES, 3.0}, {Material.LAPIS_LAZULI, Cat.ORES, 5.0}, {Material.QUARTZ, Cat.ORES, 5.0},
            {Material.AMETHYST_SHARD, Cat.ORES, 3.0}, {Material.EMERALD, Cat.ORES, 60.0}, {Material.DIAMOND, Cat.ORES, 250.0}, {Material.NETHERITE_SCRAP, Cat.ORES, 900.0},
            // farm
            {Material.WHEAT, Cat.FARM, 5.0}, {Material.WHEAT_SEEDS, Cat.FARM, 0.5}, {Material.CARROT, Cat.FARM, 3.0}, {Material.POTATO, Cat.FARM, 3.0},
            {Material.BEETROOT, Cat.FARM, 3.0}, {Material.MELON_SLICE, Cat.FARM, 1.5}, {Material.PUMPKIN, Cat.FARM, 6.0}, {Material.SUGAR_CANE, Cat.FARM, 3.0},
            {Material.COCOA_BEANS, Cat.FARM, 4.0}, {Material.APPLE, Cat.FARM, 4.0}, {Material.SWEET_BERRIES, Cat.FARM, 2.0}, {Material.EGG, Cat.FARM, 2.0},
            {Material.HONEYCOMB, Cat.FARM, 6.0}, {Material.CACTUS, Cat.FARM, 2.0}, {Material.BAMBOO, Cat.FARM, 1.0}, {Material.BROWN_MUSHROOM, Cat.FARM, 2.0},
            {Material.RED_MUSHROOM, Cat.FARM, 2.0},
            // meat & fish
            {Material.BEEF, Cat.MEAT, 8.0}, {Material.PORKCHOP, Cat.MEAT, 8.0}, {Material.CHICKEN, Cat.MEAT, 6.0}, {Material.MUTTON, Cat.MEAT, 7.0},
            {Material.RABBIT, Cat.MEAT, 7.0}, {Material.COD, Cat.MEAT, 5.0}, {Material.SALMON, Cat.MEAT, 7.0},
            // kitchen
            {Material.BREAD, Cat.KITCHEN, 15.0}, {Material.COOKED_BEEF, Cat.KITCHEN, 14.0}, {Material.COOKED_PORKCHOP, Cat.KITCHEN, 14.0},
            {Material.COOKED_CHICKEN, Cat.KITCHEN, 11.0}, {Material.COOKED_MUTTON, Cat.KITCHEN, 12.0}, {Material.COOKED_COD, Cat.KITCHEN, 8.0},
            {Material.COOKED_SALMON, Cat.KITCHEN, 11.0}, {Material.BAKED_POTATO, Cat.KITCHEN, 6.0}, {Material.PUMPKIN_PIE, Cat.KITCHEN, 18.0},
            {Material.CAKE, Cat.KITCHEN, 45.0}, {Material.COOKIE, Cat.KITCHEN, 3.0}, {Material.GOLDEN_CARROT, Cat.KITCHEN, 35.0},
            {Material.MUSHROOM_STEW, Cat.KITCHEN, 12.0}, {Material.SUGAR, Cat.KITCHEN, 2.0}, {Material.HONEY_BOTTLE, Cat.KITCHEN, 10.0},
            // timber
            {Material.OAK_LOG, Cat.TIMBER, 4.0}, {Material.SPRUCE_LOG, Cat.TIMBER, 4.0}, {Material.BIRCH_LOG, Cat.TIMBER, 4.0}, {Material.JUNGLE_LOG, Cat.TIMBER, 4.0},
            {Material.ACACIA_LOG, Cat.TIMBER, 4.0}, {Material.DARK_OAK_LOG, Cat.TIMBER, 4.0}, {Material.CHERRY_LOG, Cat.TIMBER, 5.0}, {Material.MANGROVE_LOG, Cat.TIMBER, 4.0},
            {Material.OAK_PLANKS, Cat.TIMBER, 1.0}, {Material.SPRUCE_PLANKS, Cat.TIMBER, 1.0}, {Material.STICK, Cat.TIMBER, 0.5},
            // stone & building
            {Material.COBBLESTONE, Cat.STONE, 0.4}, {Material.COBBLED_DEEPSLATE, Cat.STONE, 0.4}, {Material.STONE, Cat.STONE, 1.0}, {Material.STONE_BRICKS, Cat.STONE, 1.5},
            {Material.GRANITE, Cat.STONE, 0.5}, {Material.DIORITE, Cat.STONE, 0.5}, {Material.ANDESITE, Cat.STONE, 0.5}, {Material.SAND, Cat.STONE, 0.8},
            {Material.GRAVEL, Cat.STONE, 0.6}, {Material.GLASS, Cat.STONE, 2.0}, {Material.CLAY_BALL, Cat.STONE, 1.0}, {Material.BRICKS, Cat.STONE, 4.0},
            {Material.TERRACOTTA, Cat.STONE, 2.0}, {Material.SANDSTONE, Cat.STONE, 1.5}, {Material.OBSIDIAN, Cat.STONE, 15.0}, {Material.FLINT, Cat.STONE, 1.0},
            // wool, leather & paper
            {Material.WHITE_WOOL, Cat.CLOTH, 8.0}, {Material.LEATHER, Cat.CLOTH, 6.0}, {Material.STRING, Cat.CLOTH, 2.0}, {Material.FEATHER, Cat.CLOTH, 1.0},
            {Material.RABBIT_HIDE, Cat.CLOTH, 3.0}, {Material.INK_SAC, Cat.CLOTH, 3.0}, {Material.GLOW_INK_SAC, Cat.CLOTH, 8.0}, {Material.PAPER, Cat.CLOTH, 2.0},
            {Material.BOOK, Cat.CLOTH, 8.0},
            // curios
            {Material.BONE, Cat.CURIOS, 2.0}, {Material.GUNPOWDER, Cat.CURIOS, 5.0}, {Material.SPIDER_EYE, Cat.CURIOS, 3.0}, {Material.ROTTEN_FLESH, Cat.CURIOS, 0.3},
            {Material.ENDER_PEARL, Cat.CURIOS, 25.0}, {Material.BLAZE_ROD, Cat.CURIOS, 30.0}, {Material.SLIME_BALL, Cat.CURIOS, 8.0}, {Material.GHAST_TEAR, Cat.CURIOS, 60.0},
            {Material.NETHER_WART, Cat.CURIOS, 4.0}, {Material.GLOWSTONE_DUST, Cat.CURIOS, 4.0}, {Material.MAGMA_CREAM, Cat.CURIOS, 12.0},
            {Material.PHANTOM_MEMBRANE, Cat.CURIOS, 20.0}, {Material.PRISMARINE_SHARD, Cat.CURIOS, 3.0}, {Material.NAUTILUS_SHELL, Cat.CURIOS, 40.0},
            {Material.SHULKER_SHELL, Cat.CURIOS, 150.0}, {Material.ARROW, Cat.CURIOS, 1.0},
    };

    private final TownManager t;
    private final File file;
    private final Map<Material, Ware> wares = new LinkedHashMap<>();
    private final Map<Cat, List<Ware>> byCat = new EnumMap<>(Cat.class);
    private final Random r = new Random();
    private long nextDrift, nextHour, nextEvent;
    private String lastEvent = "";
    private int dayOfYear = -1;
    private final Map<UUID, Long> sellAllConfirm = new java.util.HashMap<>();

    Market(TownManager t) {
        this.t = t;
        this.file = new File(t.plugin().getDataFolder(), "market.yml");
        for (Cat c : Cat.values()) byCat.put(c, new ArrayList<>());
        boolean coin = t.money.physical();
        for (Object[] w : WARES) {
            if (coin && Mint.isCurrency((Material) w[0])) continue; // metal coins are money, not wares
            Ware x = new Ware((Material) w[0], (Cat) w[1], (double) w[2]);
            wares.put(x.mat, x);
            byCat.get(x.cat).add(x);
        }
        load();
    }

    private FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    // ------------------------------------------------------------------ prices

    private double k() {
        return cfg().getDouble("town.market.elasticity", 0.7);
    }

    private double spread() {
        return cfg().getDouble("town.market.spread", 0.07);
    }

    /** The middle price at a given stock level. */
    double mid(Ware w, double stock) {
        double p = w.base * Math.pow(w.normal / Math.max(1, stock), k());
        return Math.max(w.base * cfg().getDouble("town.market.floor", 0.15), Math.min(w.base * cfg().getDouble("town.market.ceiling", 4.0), p));
    }

    double mid(Ware w) {
        return mid(w, w.stock);
    }

    /** What one more unit costs a buyer right now. */
    double buyPrice(Ware w) {
        return mid(w, w.stock - 0.5) * (1 + spread());
    }

    /** What one unit fetches a seller right now. */
    double sellPrice(Ware w) {
        return mid(w, w.stock + 0.5) * (1 - spread());
    }

    /** The most the market takes in before it's flooded. */
    private double maxStock(Ware w) {
        return w.normal * cfg().getDouble("town.market.flood-factor", 12);
    }

    /** Total cost of buying n units, walking the price up one unit at a time. */
    double quoteBuy(Ware w, int n) {
        double s = 0, st = w.stock;
        for (int i = 0; i < n; i++) {
            s += mid(w, st - 0.5) * (1 + spread());
            st -= 1;
        }
        return s;
    }

    /** Total paid for selling n units, walking the price down one unit at a time. */
    double quoteSell(Ware w, int n) {
        double s = 0, st = w.stock;
        for (int i = 0; i < n; i++) {
            s += mid(w, st + 0.5) * (1 - spread());
            st += 1;
        }
        return s;
    }

    int available(Ware w) {
        return (int) Math.max(0, Math.floor(w.stock) - 1);
    }

    int room(Ware w) {
        return (int) Math.max(0, Math.floor(maxStock(w) - w.stock));
    }

    Ware ware(Material m) {
        Ware w = wares.get(m);
        if (w == null && Tag.WOOL.isTagged(m)) w = wares.get(Material.WHITE_WOOL); // any colour sells as wool
        return w;
    }

    java.util.Collection<Ware> wares() {
        return wares.values();
    }

    List<Ware> wares(Cat c) {
        return byCat.get(c);
    }

    void markDirty() {
        dirty = true;
    }

    /** Percentage change over the last day (from the hourly history). */
    double change(Ware w) {
        if (w.history.isEmpty()) return 0;
        double then = w.history.get(Math.max(0, w.history.size() - 24));
        return then <= 0 ? 0 : (mid(w) - then) / then * 100;
    }

    static String nice(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String price(double v) {
        if (v >= 100) return "$" + String.format("%,d", Math.round(v));
        if (v >= 10) return "$" + String.format("%.1f", v);
        return "$" + String.format("%.2f", v);
    }

    private static String arrow(double pct) {
        if (pct > 0.5) return "<green>▲ " + String.format("%.0f", pct) + "%";
        if (pct < -0.5) return "<red>▼ " + String.format("%.0f", -pct) + "%";
        return "<gray>■ 0%";
    }

    private String spark(Ware w) {
        List<Double> h = new ArrayList<>(w.history.subList(Math.max(0, w.history.size() - 23), w.history.size()));
        h.add(mid(w));
        if (h.size() < 2) return "";
        double lo = Double.MAX_VALUE, hi = 0;
        for (double v : h) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        String bars = "▁▂▃▄▅▆▇█";
        StringBuilder b = new StringBuilder();
        for (double v : h) b.append(bars.charAt(hi - lo < 1e-9 ? 3 : (int) Math.round((v - lo) / (hi - lo) * 7)));
        return b.toString();
    }

    // ------------------------------------------------------------------ trading

    /** Plain items only: no names, enchantments or plugin data, so relics and loot can't be dumped as ingots. */
    static boolean plain(ItemStack it) {
        return it != null && !it.getType().isAir() && it.isSimilar(new ItemStack(it.getType()));
    }

    private static int count(PlayerInventory inv, Ware w, Market m) {
        int n = 0;
        for (ItemStack it : inv.getStorageContents()) if (plain(it) && m.ware(it.getType()) == w) n += it.getAmount();
        return n;
    }

    private static void remove(PlayerInventory inv, Ware w, Market m, int n) {
        ItemStack[] c = inv.getStorageContents();
        for (int i = 0; i < c.length && n > 0; i++) {
            ItemStack it = c[i];
            if (!plain(it) || m.ware(it.getType()) != w) continue;
            int take = Math.min(n, it.getAmount());
            it.setAmount(it.getAmount() - take);
            c[i] = it.getAmount() <= 0 ? null : it;
            n -= take;
        }
        inv.setStorageContents(c);
    }

    /** How many of this item fit in the main inventory. */
    static int fits(PlayerInventory inv, ItemStack like) {
        int n = 0, max = like.getMaxStackSize();
        for (ItemStack it : inv.getStorageContents()) {
            if (it == null || it.getType().isAir()) n += max;
            else if (it.isSimilar(like)) n += Math.max(0, max - it.getAmount());
        }
        return n;
    }

    /** True when trading is allowed here: in the market hall (or anywhere while there is no hall). */
    boolean canTrade(Player p) {
        if (t.data(p).jailed()) return false;
        MarketHall h = t.marketHall();
        return h == null || !h.built() || h.inside(p.getLocation());
    }

    String buy(Player p, Ware w, int want) {
        if (!canTrade(p)) return "<red>Trade at the market hall (east of the spawn plaza). <gray>/market shows the prices anywhere.";
        int n = Math.min(want, available(w));
        if (n <= 0) return "<red>The market is out of " + w.label().toLowerCase() + ". <gray>Its price is high: worth selling some!";
        n = Math.min(n, fits(p.getInventory(), new ItemStack(w.mat)));
        if (n <= 0) return "<red>Your inventory is full.";
        double cost = Math.ceil(quoteBuy(w, n));
        double bal = t.money.balance(p);
        while (n > 0 && cost > bal) {
            n--;
            cost = Math.ceil(quoteBuy(w, n));
        }
        if (n <= 0) return "<red>You can't afford that. <gray>(" + price(buyPrice(w)) + " each)";
        if (!t.money.pay(p, cost)) return "<red>Payment failed.";
        w.stock -= n;
        w.traded += n;
        int left = n;
        while (left > 0) {
            int s = Math.min(left, w.mat.getMaxStackSize());
            for (ItemStack rest : p.getInventory().addItem(new ItemStack(w.mat, s)).values()) p.getWorld().dropItem(p.getLocation(), rest);
            left -= s;
        }
        dirty = true;
        return "<green>Bought " + n + " " + w.label().toLowerCase() + " for " + Money.fmt(cost) + ". <gray>Now " + price(buyPrice(w)) + " each.";
    }

    String sell(Player p, Ware w, int want) {
        if (!canTrade(p)) return "<red>Trade at the market hall (east of the spawn plaza). <gray>/market shows the prices anywhere.";
        int have = count(p.getInventory(), w, this);
        int n = Math.min(want, have);
        if (n <= 0) return "<red>You have no " + w.label().toLowerCase() + " to sell. <gray>(Named or enchanted items aren't taken.)";
        int room = room(w);
        if (room <= 0) return "<red>The market is flooded with " + w.label().toLowerCase() + ": nobody's buying until it clears. <gray>Try a stall, or a business.";
        n = Math.min(n, room);
        double got = Math.floor(quoteSell(w, n));
        if (got < 1) return "<red>That's worth less than $1. <gray>Sell more at once.";
        double before = mid(w);
        remove(p.getInventory(), w, this, n);
        w.stock += n;
        w.traded += n;
        t.money.give(p, got);
        dirty = true;
        double drop = (before - mid(w)) / before * 100;
        if (n >= cfg().getInt("town.market.news-units", 256) || got >= cfg().getDouble("town.market.news-value", 5000)) {
            t.news(p.getName() + " sold " + n + " " + w.label().toLowerCase() + " at the market" + (drop >= 5 ? ", and the price fell " + Math.round(drop) + "%." : "."));
        }
        return "<green>Sold " + n + " " + w.label().toLowerCase() + " for " + Money.fmt(got) + "." + (n < want && n == room ? " <yellow>The market won't take any more." : "")
                + " <gray>Now " + price(sellPrice(w)) + " each.";
    }

    /** Everything sellable in the inventory, priced as one sale per ware. */
    private Map<Ware, Integer> sellable(Player p) {
        Map<Ware, Integer> out = new LinkedHashMap<>();
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (!plain(it)) continue;
            Ware w = ware(it.getType());
            if (w != null) out.merge(w, it.getAmount(), Integer::sum);
        }
        return out;
    }

    private String sellAll(Player p) {
        Map<Ware, Integer> all = sellable(p);
        if (all.isEmpty()) return "<gray>Nothing in your inventory sells here.";
        Long at = sellAllConfirm.get(p.getUniqueId());
        if (at == null || System.currentTimeMillis() - at > 8000) {
            double total = 0;
            int units = 0;
            for (var e : all.entrySet()) {
                int n = Math.min(e.getValue(), room(e.getKey()));
                total += quoteSell(e.getKey(), n);
                units += n;
            }
            sellAllConfirm.put(p.getUniqueId(), System.currentTimeMillis());
            return "<yellow>Sell " + units + " items (" + all.size() + " kinds) for about " + Money.fmt(total) + "? <white>Click again to confirm.";
        }
        sellAllConfirm.remove(p.getUniqueId());
        double total = 0;
        int units = 0;
        for (var e : all.entrySet()) {
            Ware w = e.getKey();
            int n = Math.min(e.getValue(), room(w));
            if (n <= 0) continue;
            double got = Math.floor(quoteSell(w, n));
            if (got < 1) continue;
            remove(p.getInventory(), w, this, n);
            w.stock += n;
            w.traded += n;
            t.money.give(p, got);
            total += got;
            units += n;
        }
        dirty = true;
        return units == 0 ? "<red>The market wouldn't take any of it." : "<green>Sold " + units + " items for " + Money.fmt(total) + ".";
    }

    /**
     * A business with no local supplier buys here before importing. Takes up to {@code units} of the ware and returns
     * {units bought, cost}. The business pays the market's buying price, so this moves the market like a player would.
     */
    double[] supply(Material m, double units, double maxEach) {
        Ware w = wares.get(m);
        if (w == null || units <= 0) return new double[]{0, 0};
        int n = (int) Math.min(Math.floor(units), available(w));
        double cost = quoteBuy(w, n);
        while (n > 0 && cost / n > maxEach) {
            n--;
            cost = quoteBuy(w, n);
        }
        if (n <= 0) return new double[]{0, 0};
        w.stock -= n;
        w.traded += n;
        dirty = true;
        return new double[]{n, cost};
    }

    // ------------------------------------------------------------------ time passing

    private boolean dirty;

    void second() {
        long now = System.currentTimeMillis();
        if (now >= nextDrift) {
            nextDrift = now + cfg().getLong("town.market.drift-minutes", 5) * 60_000L;
            double rate = cfg().getDouble("town.market.drift-rate", 0.05), cap = cfg().getDouble("town.market.drift-cap", 0.04);
            for (Ware w : wares.values()) {
                double gap = w.normal - w.stock;
                double step = Math.max(-cap * w.normal, Math.min(cap * w.normal, gap * rate));
                double noise = r.nextGaussian() * 0.01 * w.normal;
                w.stock = Math.max(1, w.stock + step + noise);
            }
            dirty = true;
        }
        if (now >= nextHour) {
            nextHour = now + 3_600_000L;
            int doy = java.time.LocalDate.now().getDayOfYear();
            for (Ware w : wares.values()) {
                w.history.add(mid(w));
                while (w.history.size() > 48) w.history.remove(0);
                if (doy != dayOfYear) w.traded = 0;
            }
            if (doy != dayOfYear) {
                dayOfYear = doy;
                movers();
            }
            dirty = true;
        }
        if (now >= nextEvent) {
            if (nextEvent > 0 && !org.bukkit.Bukkit.getOnlinePlayers().isEmpty()) event();
            nextEvent = now + cfg().getLong("town.market.event-hours", 6) * 3_600_000L;
            dirty = true;
        }
        if (dirty && now % 30_000 < 1000) save();
    }

    /** A shortage or a glut of one ware, for the Herald. */
    private void event() {
        List<Ware> all = new ArrayList<>(wares.values());
        Ware w = all.get(r.nextInt(all.size()));
        boolean shortage = r.nextBoolean();
        String why;
        if (shortage) {
            w.stock = Math.max(1, w.stock * 0.4);
            String[] c = {"a cart overturned on the north road", "the guild bought up the lot", "a poor harvest", "a fire at the warehouse"};
            why = c[r.nextInt(c.length)];
            lastEvent = "Shortage of " + w.label().toLowerCase() + " (" + why + ")";
            t.news("Shortage at the market: " + w.label().toLowerCase() + " is scarce after " + why + ". Prices are up; sellers, now's your chance.");
        } else {
            w.stock = Math.min(maxStock(w) * 0.6, w.stock * 2.5);
            String[] c = {"a ship came in heavy", "traders from the south arrived", "a bumper season", "a merchant went bust and sold up"};
            why = c[r.nextInt(c.length)];
            lastEvent = "Glut of " + w.label().toLowerCase() + " (" + why + ")";
            t.news("The market is awash with " + w.label().toLowerCase() + " after " + why + ". Prices have tumbled; buyers, stock up.");
        }
    }

    /** Daily: the biggest movers make the news. */
    private void movers() {
        Ware up = null, down = null;
        for (Ware w : wares.values()) {
            if (w.history.size() < 12) continue;
            if (up == null || change(w) > change(up)) up = w;
            if (down == null || change(w) < change(down)) down = w;
        }
        if (up != null && change(up) > 25) t.news(up.label() + " is up " + Math.round(change(up)) + "% at the market in a day.");
        if (down != null && change(down) < -25) t.news(down.label() + " is down " + Math.round(-change(down)) + "% at the market in a day.");
    }

    /** For the admin: knock a ware's stock. */
    String shock(String what, double factor) {
        Material m = Material.matchMaterial(what);
        Ware w = m == null ? null : wares.get(m);
        if (w == null) return "<red>Not a market ware: " + what;
        w.stock = Math.max(1, Math.min(maxStock(w), w.stock * factor));
        dirty = true;
        return "<green>" + w.label() + " stock is now " + (int) w.stock + " (normal " + (int) w.normal + "), price " + price(mid(w)) + ".";
    }

    String restock() {
        for (Ware w : wares.values()) w.stock = w.normal;
        dirty = true;
        return "<green>Every ware is back to its normal stock and price.";
    }

    String status() {
        int up = 0, down = 0;
        for (Ware w : wares.values()) {
            double c = mid(w) / w.base;
            if (c > 1.15) up++;
            if (c < 0.85) down++;
        }
        return wares.size() + " wares, " + up + " dear, " + down + " cheap" + (lastEvent.isEmpty() ? "" : ". Last event: " + lastEvent) + ".";
    }

    /** Lines for the price board in the hall: the wares furthest from their normal price. */
    List<String> boardLines(int n) {
        List<Ware> all = new ArrayList<>(wares.values());
        all.sort((a, b) -> Double.compare(Math.abs(Math.log(mid(b) / b.base)), Math.abs(Math.log(mid(a) / a.base))));
        List<String> out = new ArrayList<>();
        for (Ware w : all.subList(0, Math.min(n, all.size()))) {
            double c = (mid(w) / w.base - 1) * 100;
            out.add("<white>" + w.label() + " <gray>" + price(buyPrice(w)) + " / " + price(sellPrice(w)) + " " + arrow(c));
        }
        return out;
    }

    // ------------------------------------------------------------------ the screens

    static final class Menu implements InventoryHolder {
        final Cat cat; // null = the main page
        final boolean trade, vendor;
        Inventory inv;

        Menu(Cat cat, boolean trade, boolean vendor) {
            this.cat = cat;
            this.trade = trade;
            this.vendor = vendor;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static ItemStack icon(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.displayName(SmpPlugin.mm("<!italic>" + name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(SmpPlugin.mm("<!italic>" + s));
        im.lore(l);
        it.setItemMeta(im);
        return it;
    }

    void open(Player p, Cat cat, boolean vendor) {
        boolean trade = canTrade(p);
        Menu m = new Menu(cat, trade, vendor);
        m.inv = org.bukkit.Bukkit.createInventory(m, 54, SmpPlugin.mm(cat == null ? "Market exchange" : cat.label + (trade ? "" : " (prices)")));
        fill(m, p);
        p.openInventory(m.inv);
    }

    private void fill(Menu m, Player p) {
        Inventory inv = m.inv;
        inv.clear();
        ItemStack glass = icon(Material.BROWN_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 9; i++) {
            inv.setItem(i, glass);
            inv.setItem(45 + i, glass);
        }
        List<String> help = new ArrayList<>(List.of("<gray>Prices follow supply: every unit sold", "<gray>makes the next cheaper, every unit bought", "<gray>makes it dearer. Stock drifts back to normal",
                "<gray>over a few hours.", ""));
        if (!m.trade) help.add("<yellow>You're viewing prices. Trade at the market hall,");
        if (!m.trade) help.add("<yellow>east of the spawn plaza.");
        inv.setItem(4, icon(Material.BOOK, "<gold>How the market works", help));
        if (m.cat == null) {
            int[] slots = {19, 20, 21, 22, 23, 24, 25, 31};
            Cat[] cats = Cat.values();
            for (int i = 0; i < cats.length; i++) {
                Cat c = cats[i];
                List<String> lore = new ArrayList<>();
                for (Ware w : byCat.get(c).subList(0, Math.min(5, byCat.get(c).size()))) lore.add("<gray>" + w.label() + " " + price(buyPrice(w)) + " " + arrow(change(w)));
                lore.add("");
                lore.add("<yellow>Click to see all " + byCat.get(c).size());
                inv.setItem(slots[i], icon(c.icon, "<gold>" + c.label, lore));
            }
            List<String> movers = boardLines(8);
            movers.add(0, "<gray>Furthest from normal (buy / sell):");
            inv.setItem(40, icon(Material.CLOCK, "<gold>Market board", movers));
            if (m.trade) inv.setItem(49, icon(Material.HOPPER, "<green>Sell everything sellable", List.of("<gray>Sells every plain item in your inventory", "<gray>that the market trades. Click twice.")));
            if (t.colonyLink() != null) inv.setItem(53, icon(Material.CHEST_MINECART, "<gold>Colony freight", List.of("<gray>Sell your colony's spare goods here,",
                    "<gray>or buy wares for its storage.", "<dark_gray>/market colony")));
        } else {
            List<Ware> list = byCat.get(m.cat);
            int slot = 9;
            for (Ware w : list) {
                if (slot >= 45) break;
                PlayerInventory pi = p.getInventory();
                List<String> lore = new ArrayList<>();
                lore.add("<gray>Buy <white>" + price(buyPrice(w)) + "</white> · sell <white>" + price(sellPrice(w)) + "</white> each");
                lore.add("<gray>Stock <white>" + (int) w.stock + "</white> <dark_gray>(normal " + (int) w.normal + ")");
                lore.add("<gray>24h " + arrow(change(w)) + " <dark_gray>" + spark(w));
                int have = count(pi, w, this);
                if (have > 0) lore.add("<gray>You carry <white>" + have + "</white>: worth " + Money.fmt(quoteSell(w, Math.min(have, room(w)))));
                lore.add("");
                if (m.trade) {
                    lore.add("<yellow>Left-click <gray>buy 1 · <yellow>shift-left <gray>buy 16");
                    lore.add("<yellow>Right-click <gray>sell 1 · <yellow>shift-right <gray>sell all you carry");
                } else lore.add("<dark_gray>Trade at the market hall.");
                ItemStack it = icon(w.mat, "<white>" + w.label(), lore);
                inv.setItem(slot++, it);
            }
            if (!m.vendor) inv.setItem(49, icon(Material.ARROW, "<yellow>Back", List.of()));
        }
    }

    private Ware wareAt(Menu m, int slot) {
        if (m.cat == null || slot < 9 || slot >= 45) return null;
        List<Ware> list = byCat.get(m.cat);
        int i = slot - 9;
        return i < list.size() ? list.get(i) : null;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        if (m.cat == null) {
            int[] slots = {19, 20, 21, 22, 23, 24, 25, 31};
            for (int i = 0; i < slots.length; i++) {
                if (slot == slots[i]) {
                    Menu next = new Menu(Cat.values()[i], m.trade, false);
                    next.inv = org.bukkit.Bukkit.createInventory(next, 54, SmpPlugin.mm(next.cat.label + (m.trade ? "" : " (prices)")));
                    fill(next, p);
                    p.openInventory(next.inv);
                    return;
                }
            }
            if (slot == 53 && t.colonyLink() != null) {
                t.colonyLink().open(p);
                return;
            }
            if (slot == 49 && m.trade) {
                p.sendMessage(SmpPlugin.mm(sellAll(p)));
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
                fill(m, p);
            }
            return;
        }
        if (slot == 49 && !m.vendor) {
            open(p, null, false);
            return;
        }
        Ware w = wareAt(m, slot);
        if (w == null || !m.trade) return;
        String msg;
        ClickType c = e.getClick();
        if (c == ClickType.LEFT) msg = buy(p, w, 1);
        else if (c == ClickType.SHIFT_LEFT) msg = buy(p, w, 16);
        else if (c == ClickType.RIGHT) msg = sell(p, w, 1);
        else if (c == ClickType.SHIFT_RIGHT) msg = sell(p, w, Integer.MAX_VALUE);
        else return;
        p.sendMessage(SmpPlugin.mm(msg));
        p.playSound(p.getLocation(), msg.startsWith("<green>") ? Sound.ENTITY_EXPERIENCE_ORB_PICKUP : Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 1.2f);
        fill(m, p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ /market

    boolean command(Player p, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "colony", "colonies", "freight" -> {
                if (t.colonyLink() == null) p.sendMessage(SmpPlugin.mm("<gray>There are no colonies on this server."));
                else t.colonyLink().open(p);
            }
            case "vendor" -> {
                Cat c = a.length > 1 ? Cat.parse(a[1]) : null;
                if (c == null) return true;
                MarketHall h = t.marketHall();
                if (h != null && h.built() && !h.nearVendor(p, c)) {
                    p.sendMessage(SmpPlugin.mm("<gray>Talk to the " + c.vendor + " at the market hall."));
                    return true;
                }
                open(p, c, true);
            }
            case "price" -> {
                Material m = a.length > 1 ? Material.matchMaterial(a[1]) : p.getInventory().getItemInMainHand().getType();
                Ware w = m == null ? null : ware(m);
                if (w == null) {
                    p.sendMessage(SmpPlugin.mm("<gray>The market doesn't trade that. <white>/market</white> lists what it does."));
                    return true;
                }
                p.sendMessage(SmpPlugin.mm("<gold>" + w.label() + "<gray>: buy <white>" + price(buyPrice(w)) + "</white>, sell <white>" + price(sellPrice(w)) + "</white>, stock "
                        + (int) w.stock + " (normal " + (int) w.normal + "), 24h " + arrow(change(w))));
            }
            default -> open(p, null, false);
        }
        return true;
    }

    // ------------------------------------------------------------------ saving

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("wares");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                Ware w = m == null ? null : wares.get(m);
                if (w == null) continue;
                w.stock = Math.max(1, s.getDouble(k + ".stock", w.normal));
                w.traded = s.getLong(k + ".traded");
                w.history.clear();
                w.history.addAll(s.getDoubleList(k + ".history"));
            }
        }
        lastEvent = y.getString("last-event", "");
        nextEvent = y.getLong("next-event", System.currentTimeMillis() + 3_600_000L);
        dayOfYear = y.getInt("day", -1);
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Ware w : wares.values()) {
            String k = "wares." + w.mat.name();
            y.set(k + ".stock", Math.round(w.stock * 100) / 100.0);
            y.set(k + ".traded", w.traded);
            List<Double> h = new ArrayList<>();
            for (double v : w.history) h.add(Math.round(v * 1000) / 1000.0);
            y.set(k + ".history", h);
        }
        y.set("last-event", lastEvent);
        y.set("next-event", nextEvent);
        y.set("day", dayOfYear);
        try {
            y.save(file);
            dirty = false;
        } catch (IOException ignored) {
        }
    }

    /** Is this location's block inside the hall? Used by vendors' "shout their wares" and by stalls. */
    boolean near(Location a, Location b, double r) {
        return a.getWorld() == b.getWorld() && a.distanceSquared(b) <= r * r;
    }
}
