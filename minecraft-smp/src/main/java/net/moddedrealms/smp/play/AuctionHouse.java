package net.moddedrealms.smp.play;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The auction house (/ah): players list what they hold for a fixed price; anyone buys from a menu, anywhere. The
 * seller is paid (less a small tax, which leaves the economy) even when offline; listings run for a couple of days,
 * then go to the seller's collection box with anything they cancel. Every change is saved at once, before the item
 * or the money moves, so a crash can lose a sale but never copy an item. Plugin items (stolen loot, duty kit, colony
 * cores, dishes) can't be listed.
 */
public final class AuctionHouse implements Listener, TabExecutor {
    private final SmpPlugin plugin;
    private final File file;
    private final Map<String, Listing> listings = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> collect = new HashMap<>();
    private final Map<UUID, Long> confirm = new HashMap<>();
    private long nextId = 1;

    static final class Listing {
        final String id;
        final UUID seller;
        final String sellerName;
        final ItemStack item;
        final double price;
        final long listed, expires;

        Listing(String id, UUID seller, String sellerName, ItemStack item, double price, long listed, long expires) {
            this.id = id;
            this.seller = seller;
            this.sellerName = sellerName;
            this.item = item;
            this.price = price;
            this.listed = listed;
            this.expires = expires;
        }
    }

    public AuctionHouse(SmpPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "auctions.yml");
        load();
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return plugin.getConfig();
    }

    // ------------------------------------------------------------------ storage

    private static String enc(ItemStack it) {
        return Base64.getEncoder().encodeToString(it.serializeAsBytes());
    }

    private static ItemStack dec(String s) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
    }

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextId = y.getLong("next-id", 1);
        ConfigurationSection ls = y.getConfigurationSection("listings");
        if (ls != null) {
            for (String id : ls.getKeys(false)) {
                ConfigurationSection s = ls.getConfigurationSection(id);
                try {
                    listings.put(id, new Listing(id, UUID.fromString(s.getString("seller")), s.getString("seller-name", "?"), dec(s.getString("item")),
                            s.getDouble("price"), s.getLong("listed"), s.getLong("expires")));
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Auction " + id + " couldn't be read and was skipped (kept in auctions.yml): " + e.getMessage());
                }
            }
        }
        ConfigurationSection cs = y.getConfigurationSection("collect");
        if (cs != null) {
            for (String u : cs.getKeys(false)) {
                List<ItemStack> items = new ArrayList<>();
                for (String s : cs.getStringList(u)) {
                    try {
                        items.add(dec(s));
                    } catch (RuntimeException ignored) {
                    }
                }
                if (!items.isEmpty()) collect.put(UUID.fromString(u), items);
            }
        }
    }

    private boolean save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        for (Listing l : listings.values()) {
            String p = "listings." + l.id + ".";
            y.set(p + "seller", l.seller.toString());
            y.set(p + "seller-name", l.sellerName);
            y.set(p + "item", enc(l.item));
            y.set(p + "price", l.price);
            y.set(p + "listed", l.listed);
            y.set(p + "expires", l.expires);
        }
        for (var e : collect.entrySet()) {
            List<String> s = new ArrayList<>();
            for (ItemStack it : e.getValue()) s.add(enc(it));
            if (!s.isEmpty()) y.set("collect." + e.getKey(), s);
        }
        try {
            y.save(file);
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Couldn't save auctions.yml: " + e.getMessage());
            return false;
        }
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 1200L, 1200L);
    }

    private void expire() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Iterator<Listing> it = listings.values().iterator(); it.hasNext(); ) {
            Listing l = it.next();
            if (l.expires > now) continue;
            it.remove();
            collect.computeIfAbsent(l.seller, k -> new ArrayList<>()).add(l.item);
            changed = true;
            Player s = Bukkit.getPlayer(l.seller);
            if (s != null) s.sendMessage(SmpPlugin.mm("<gray>Your auction of " + label(l.item) + " ended unsold. Collect it: <white>/ah collect"));
        }
        if (changed) save();
    }

    // ------------------------------------------------------------------ rules

    /** Items carrying this plugin's or ColonySMP's data (loot, kit, cores, dishes, keys) stay out of the auction house. */
    private static boolean tradeable(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return it != null && !it.getType().isAir();
        ItemMeta meta = it.getItemMeta();
        for (NamespacedKey k : meta.getPersistentDataContainer().getKeys()) {
            String ns = k.getNamespace();
            if (ns.equals("moddedrealmssmp") || ns.equals("colonysmp")) return false;
        }
        // nothing smuggled inside a shulker box or a bundle either
        if (meta instanceof org.bukkit.inventory.meta.BlockStateMeta bs && bs.hasBlockState() && bs.getBlockState() instanceof org.bukkit.block.ShulkerBox box) {
            for (ItemStack in : box.getInventory().getContents()) if (in != null && !in.getType().isAir() && !tradeable(in)) return false;
        }
        if (meta instanceof org.bukkit.inventory.meta.BundleMeta b) {
            for (ItemStack in : b.getItems()) if (!tradeable(in)) return false;
        }
        return true;
    }

    private int listedBy(UUID u) {
        int n = 0;
        for (Listing l : listings.values()) if (l.seller.equals(u)) n++;
        return n;
    }

    static String label(ItemStack it) {
        String name = it.hasItemMeta() && it.getItemMeta().hasDisplayName()
                ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it.getItemMeta().displayName())
                : net.moddedrealms.smp.Rewards.pretty(it.getType().name());
        return (it.getAmount() > 1 ? it.getAmount() + "× " : "") + SmpPlugin.MM.escapeTags(name);
    }

    private static String money(double v) {
        return "$" + String.format(Locale.ROOT, "%,.0f", v);
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!(sender instanceof Player p)) return true;
        if (!cfg().getBoolean("auction.enabled", true)) {
            p.sendMessage(SmpPlugin.mm("<gray>The auction house is closed."));
            return true;
        }
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> open(p, 0, false);
            case "sell", "list" -> sell(p, a);
            case "mine", "listings" -> open(p, 0, true);
            case "collect" -> collect(p);
            case "help" -> help(p);
            default -> {
                try {
                    open(p, Math.max(0, Integer.parseInt(sub) - 1), false);
                } catch (NumberFormatException e) {
                    help(p);
                }
            }
        }
        return true;
    }

    private void help(Player p) {
        p.sendMessage(SmpPlugin.mm("<gold><bold>Auction house</bold></gold>\n<white>/ah</white> <gray>browse and buy · <white>/ah sell <price></white> <gray>list what you hold\n"
                + "<white>/ah mine</white> <gray>your listings (click to cancel) · <white>/ah collect</white> <gray>unsold and cancelled items\n"
                + "<gray>Listings last " + cfg().getLong("auction.hours", 48) + " hours. A " + Math.round(cfg().getDouble("auction.tax", 0.05) * 100) + "% tax is taken from each sale."));
    }

    private void sell(Player p, String[] a) {
        if (plugin.town() != null && plugin.town().isJailed(p)) {
            p.sendMessage(SmpPlugin.mm("<red>Not from a jail cell."));
            return;
        }
        if (a.length < 2) {
            p.sendMessage(SmpPlugin.mm("<gray>Hold the item, then <white>/ah sell <price></white> (the whole stack is listed)."));
            return;
        }
        double price;
        try {
            price = Math.floor(Double.parseDouble(a[1].replace("$", "").replace(",", "")));
        } catch (NumberFormatException e) {
            p.sendMessage(SmpPlugin.mm("<red>The price must be a number."));
            return;
        }
        double max = cfg().getDouble("auction.max-price", 10_000_000);
        if (price < 1 || price > max) {
            p.sendMessage(SmpPlugin.mm("<red>Prices go from $1 to " + money(max) + "."));
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            p.sendMessage(SmpPlugin.mm("<red>Hold the item you want to sell."));
            return;
        }
        if (!tradeable(hand)) {
            p.sendMessage(SmpPlugin.mm("<red>That item can't be sold here."));
            return;
        }
        int limit = cfg().getInt("auction.max-listings", 6);
        if (listedBy(p.getUniqueId()) >= limit) {
            p.sendMessage(SmpPlugin.mm("<red>You have " + limit + " listings already. <gray>Cancel one with <white>/ah mine</white>."));
            return;
        }
        double fee = Math.floor(cfg().getDouble("auction.listing-fee", 0));
        if (fee > 0 && !plugin.town().money.pay(p, fee)) {
            p.sendMessage(SmpPlugin.mm("<red>Listing costs " + money(fee) + "."));
            return;
        }
        ItemStack item = hand.clone();
        p.getInventory().setItemInMainHand(null);
        long now = System.currentTimeMillis();
        String id = Long.toString(nextId++, 36);
        listings.put(id, new Listing(id, p.getUniqueId(), p.getName(), item, price, now, now + cfg().getLong("auction.hours", 48) * 3_600_000L));
        if (!save()) {
            listings.remove(id);
            p.getInventory().addItem(item).values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
            p.sendMessage(SmpPlugin.mm("<red>The auction house couldn't take it just now; it's back in your inventory."));
            return;
        }
        p.sendMessage(SmpPlugin.mm("<green>Listed " + label(item) + " for " + money(price) + ". <gray>You're paid when it sells, even offline."));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.3f);
        if (price >= cfg().getDouble("auction.announce-over", 5000)) {
            Bukkit.broadcast(SmpPlugin.mm("<gold>[AH] <white>" + p.getName() + "</white> <gray>listed <white>" + label(item) + "</white> for <yellow>" + money(price)
                    + "</yellow>. <click:run_command:'/ah'><yellow><u>[Browse]</u></yellow></click>"));
        }
    }

    private void collect(Player p) {
        List<ItemStack> items = collect.get(p.getUniqueId());
        if (items == null || items.isEmpty()) {
            p.sendMessage(SmpPlugin.mm("<gray>Nothing to collect."));
            return;
        }
        int got = 0;
        for (Iterator<ItemStack> it = items.iterator(); it.hasNext(); ) {
            ItemStack x = it.next();
            if (!fits(p, x)) break;
            it.remove();
            got++;
            save();
            p.getInventory().addItem(x);
        }
        if (items.isEmpty()) collect.remove(p.getUniqueId());
        save();
        p.sendMessage(SmpPlugin.mm(got == 0 ? "<red>Make room in your inventory first." : "<green>Collected " + got + " item" + (got == 1 ? "" : "s") + "."
                + (items.isEmpty() ? "" : " <gray>" + items.size() + " more when you have room.")));
    }

    private static boolean fits(Player p, ItemStack x) {
        int room = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it == null || it.getType().isAir()) room += x.getMaxStackSize();
            else if (it.isSimilar(x)) room += Math.max(0, it.getMaxStackSize() - it.getAmount());
            if (room >= x.getAmount()) return true;
        }
        return room >= x.getAmount();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        List<ItemStack> items = collect.get(e.getPlayer().getUniqueId());
        if (items != null && !items.isEmpty()) {
            Player p = e.getPlayer();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) p.sendMessage(SmpPlugin.mm("<gold>[AH] <gray>You have " + items.size() + " item" + (items.size() == 1 ? "" : "s")
                        + " to collect. <click:run_command:'/ah collect'><yellow><u>[Collect]</u></yellow></click>"));
            }, 200L);
        }
    }

    // ------------------------------------------------------------------ menus

    static final class Menu implements InventoryHolder {
        final int page;
        final boolean mine;
        final List<String> ids = new ArrayList<>();
        Inventory inv;

        Menu(int page, boolean mine) {
            this.page = page;
            this.mine = mine;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private List<Listing> shown(Player p, boolean mine) {
        long now = System.currentTimeMillis();
        List<Listing> out = new ArrayList<>();
        for (Listing l : listings.values()) if (l.expires > now && (!mine || l.seller.equals(p.getUniqueId()))) out.add(l);
        out.sort(Comparator.comparingLong((Listing l) -> l.listed).reversed());
        return out;
    }

    void open(Player p, int page, boolean mine) {
        List<Listing> all = shown(p, mine);
        int pages = Math.max(1, (all.size() + 44) / 45);
        page = Math.min(page, pages - 1);
        Menu m = new Menu(page, mine);
        m.inv = Bukkit.createInventory(m, 54, SmpPlugin.mm(mine ? "Your listings" : "Auction house · " + (page + 1) + "/" + pages));
        long now = System.currentTimeMillis();
        for (int i = 0; i < 45 && page * 45 + i < all.size(); i++) {
            Listing l = all.get(page * 45 + i);
            ItemStack icon = l.item.clone();
            ItemMeta meta = icon.getItemMeta();
            List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Component.empty());
            lore.add(SmpPlugin.mm("<!italic><gray>Price <gold>" + money(l.price)));
            lore.add(SmpPlugin.mm("<!italic><gray>Seller <white>" + SmpPlugin.MM.escapeTags(l.sellerName)));
            long h = Math.max(0, (l.expires - now) / 3_600_000L);
            lore.add(SmpPlugin.mm("<!italic><dark_gray>Ends in " + (h >= 1 ? h + "h" : Math.max(1, (l.expires - now) / 60_000L) + "m")));
            lore.add(SmpPlugin.mm(mine || l.seller.equals(p.getUniqueId()) ? "<!italic><yellow>Click to cancel" : "<!italic><yellow>Click, then click again, to buy"));
            meta.lore(lore);
            icon.setItemMeta(meta);
            m.inv.setItem(i, icon);
            m.ids.add(l.id);
        }
        ItemStack glass = button(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 45; i < 54; i++) m.inv.setItem(i, glass);
        if (page > 0) m.inv.setItem(45, button(Material.ARROW, "<yellow>Previous page", List.of()));
        if (page < pages - 1) m.inv.setItem(53, button(Material.ARROW, "<yellow>Next page", List.of()));
        int waiting = collect.getOrDefault(p.getUniqueId(), List.of()).size();
        m.inv.setItem(48, button(Material.CHEST, "<gold>Collect" + (waiting > 0 ? " (" + waiting + ")" : ""), List.of("<gray>Unsold and cancelled items")));
        m.inv.setItem(49, button(mine ? Material.COMPASS : Material.WRITABLE_BOOK, mine ? "<yellow>All listings" : "<yellow>Your listings",
                List.of("<gray>" + listedBy(p.getUniqueId()) + "/" + cfg().getInt("auction.max-listings", 6) + " used")));
        m.inv.setItem(50, button(Material.OAK_SIGN, "<gold>Selling", List.of("<gray>Hold an item and type", "<white>/ah sell <price>",
                "<gray>Tax " + Math.round(cfg().getDouble("auction.tax", 0.05) * 100) + "% of the price on sale.")));
        p.openInventory(m.inv);
    }

    private static ItemStack button(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
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
        if (e.getClick() == ClickType.DOUBLE_CLICK || e.getClick() == ClickType.NUMBER_KEY) return;
        int slot = e.getRawSlot();
        switch (slot) {
            case 45 -> open(p, m.page - 1, m.mine);
            case 53 -> open(p, m.page + 1, m.mine);
            case 48 -> {
                p.closeInventory();
                collect(p);
            }
            case 49 -> open(p, 0, !m.mine);
            default -> {
                if (slot < 0 || slot >= m.ids.size()) return;
                Listing l = listings.get(m.ids.get(slot));
                if (l == null || l.expires <= System.currentTimeMillis()) {
                    p.sendMessage(SmpPlugin.mm("<gray>That listing has gone."));
                    open(p, m.page, m.mine);
                    return;
                }
                if (l.seller.equals(p.getUniqueId())) cancel(p, l);
                else buy(p, l);
                open(p, m.page, m.mine);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    private void cancel(Player p, Listing l) {
        listings.remove(l.id);
        collect.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).add(l.item);
        save();
        p.sendMessage(SmpPlugin.mm("<gray>Cancelled. " + label(l.item) + " is in your collection box: <white>/ah collect"));
    }

    private void buy(Player p, Listing l) {
        Long at = confirm.get(p.getUniqueId());
        String key = l.id;
        if (at == null || System.currentTimeMillis() - at > 6000 || !key.equals(lastConfirm.get(p.getUniqueId()))) {
            confirm.put(p.getUniqueId(), System.currentTimeMillis());
            lastConfirm.put(p.getUniqueId(), key);
            p.sendMessage(SmpPlugin.mm("<yellow>Buy " + label(l.item) + " for " + money(l.price) + "? <gray>Click it again to confirm."));
            return;
        }
        confirm.remove(p.getUniqueId());
        lastConfirm.remove(p.getUniqueId());
        if (plugin.town() != null && plugin.town().isJailed(p)) {
            p.sendMessage(SmpPlugin.mm("<red>Not from a jail cell."));
            return;
        }
        if (!fits(p, l.item)) {
            p.sendMessage(SmpPlugin.mm("<red>Make room in your inventory first."));
            return;
        }
        if (plugin.town().money.balance(p) < l.price) {
            p.sendMessage(SmpPlugin.mm("<red>You can't afford " + money(l.price) + "."));
            return;
        }
        if (!plugin.town().money.pay(p, l.price)) {
            p.sendMessage(SmpPlugin.mm("<red>Payment failed."));
            return;
        }
        listings.remove(l.id);
        save(); // the listing is gone before the item moves
        p.getInventory().addItem(l.item.clone()).values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
        double tax = Math.floor(l.price * cfg().getDouble("auction.tax", 0.05));
        OfflinePlayer seller = Bukkit.getOfflinePlayer(l.seller);
        plugin.town().money.give(seller, l.price - tax);
        p.sendMessage(SmpPlugin.mm("<green>Bought " + label(l.item) + " for " + money(l.price) + "."));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        Player s = seller.getPlayer();
        if (s != null) {
            s.sendMessage(SmpPlugin.mm("<gold>[AH] <white>" + p.getName() + "</white> <gray>bought your " + label(l.item) + ": <green>+" + money(l.price - tax)
                    + "</green>" + (tax > 0 ? " <dark_gray>(" + money(tax) + " tax)" : "")));
            s.playSound(s.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.4f);
        }
        plugin.getLogger().info("[AH] " + p.getName() + " bought " + l.item.getType() + " x" + l.item.getAmount() + " from " + l.sellerName + " for " + l.price);
    }

    private final Map<UUID, String> lastConfirm = new HashMap<>();

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) for (String x : List.of("sell", "mine", "collect", "help")) if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(x);
        return out;
    }

    /** For admins. */
    public String status() {
        int waiting = 0;
        for (List<ItemStack> l : collect.values()) waiting += l.size();
        return listings.size() + " listings, " + waiting + " items waiting to be collected.";
    }
}
