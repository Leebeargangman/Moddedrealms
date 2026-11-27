package net.moddedrealms.smp.town;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.moddedrealms.smp.SmpPlugin;
import net.moddedrealms.smp.town.Businesses.Biz;
import net.moddedrealms.smp.town.Econ.Good;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * The counter of any business, town-run or a player's: buy what it makes as real items (a hot meal at the cookhouse,
 * ale at the tavern, a room at the inn, planks at the sawmill) and sell it what it needs (wheat to the bakery, wool
 * to the tailor, logs to the carpenter). It opens from the business's bell or lectern, its shopkeeper, or /business
 * shop inside. Sales go into the business's books like any other; what players sell to it fills its input stock,
 * so a player's farm can supply the town. Owners stock their own business from the same screen, for nothing.
 */
public final class Counter implements Listener {
    private final TownManager t;
    private final Businesses bz;

    Counter(TownManager t, Businesses bz) {
        this.t = t;
        this.bz = bz;
    }

    /** One thing on sale: the item handed over and how many units of the business's stock it uses. */
    private record Product(ItemStack item, double units) {}

    /** One thing it buys: which items count, how many units each is worth. */
    private record Want(Good good, String label, Material icon, java.util.function.Predicate<Material> accepts, double unitsEach) {}

    private static ItemStack named(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.displayName(SmpPlugin.mm("<!italic>" + name));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(SmpPlugin.mm("<!italic>" + s));
            im.lore(l);
        }
        it.setItemMeta(im);
        return it;
    }

    private static ItemStack dyed(Material m, String name, int rgb) {
        ItemStack it = named(m, name);
        if (it.getItemMeta() instanceof LeatherArmorMeta lm) {
            lm.setColor(Color.fromRGB(rgb));
            it.setItemMeta(lm);
        }
        return it;
    }

    private static List<Product> products(Biz b) {
        List<Product> out = new ArrayList<>();
        switch (b.kind.output) {
            case WHEAT -> out.add(new Product(new ItemStack(Material.WHEAT), 1));
            case WOOL -> out.add(new Product(new ItemStack(Material.WHITE_WOOL), 1));
            case PLANKS -> {
                out.add(new Product(new ItemStack(Material.OAK_PLANKS, 4), 1));
                out.add(new Product(new ItemStack(Material.SPRUCE_PLANKS, 4), 1));
                out.add(new Product(new ItemStack(Material.BIRCH_PLANKS, 4), 1));
            }
            case BREAD -> out.add(new Product(new ItemStack(Material.BREAD), 1));
            case ALE -> {
                out.add(new Product(named(Material.HONEY_BOTTLE, "<gold>Ale", "<gray>From " + b.name), 1));
                out.add(new Product(named(Material.HONEY_BOTTLE, "<gold>Mead", "<gray>From " + b.name), 1));
            }
            case CLOTH -> {
                out.add(new Product(dyed(Material.LEATHER_CHESTPLATE, "<white>Woollen tunic", 0x7A5230), 1));
                out.add(new Product(dyed(Material.LEATHER_LEGGINGS, "<white>Hose", 0x4A5A3A), 1));
                out.add(new Product(dyed(Material.LEATHER_BOOTS, "<white>Boots", 0x3A2A1A), 1));
                out.add(new Product(dyed(Material.LEATHER_HELMET, "<white>Hood", 0x5A3A6A), 1));
            }
            case BEDS -> {
                out.add(new Product(new ItemStack(Material.RED_BED), 1));
                out.add(new Product(new ItemStack(Material.WHITE_BED), 1));
                out.add(new Product(new ItemStack(Material.BLUE_BED), 1));
            }
            case MEALS -> {
                for (Material m : new Material[]{Material.RABBIT_STEW, Material.MUSHROOM_STEW, Material.BEETROOT_SOUP, Material.COOKED_BEEF, Material.BAKED_POTATO,
                        Material.PUMPKIN_PIE, Material.COOKED_SALMON}) out.add(new Product(new ItemStack(m), 1));
            }
            case GOODS -> {
                for (Material m : new Material[]{Material.CHEST, Material.BARREL, Material.LANTERN, Material.BUCKET, Material.SHEARS, Material.FLOWER_POT,
                        Material.COMPASS, Material.CLOCK}) out.add(new Product(new ItemStack(m), 1));
            }
            case STAYS -> out.add(new Product(named(Material.WHITE_BED, "<gold>A room for the night", "<gray>Wake here when you next die,", "<gray>and leave well rested."), 1));
            case MOONSHINE -> {
                ItemStack it = named(Material.POTION, "<gold>Moonshine", "<gray>Don't let the Watch see you.");
                if (it.getItemMeta() instanceof PotionMeta pm) {
                    pm.setColor(Color.fromRGB(0xD8D0A0));
                    pm.addCustomEffect(new PotionEffect(PotionEffectType.STRENGTH, 20 * 180, 0), true);
                    pm.addCustomEffect(new PotionEffect(PotionEffectType.NAUSEA, 20 * 12, 0), true);
                    it.setItemMeta(pm);
                }
                out.add(new Product(it, 1));
            }
        }
        return out;
    }

    private static List<Want> wants(Biz b) {
        List<Want> out = new ArrayList<>();
        for (Good g : b.kind.inputs.keySet()) {
            switch (g) {
                case WHEAT -> out.add(new Want(g, "Wheat", Material.WHEAT, m -> m == Material.WHEAT, 1));
                case WOOL -> out.add(new Want(g, "Wool (any colour)", Material.WHITE_WOOL, Tag.WOOL::isTagged, 1));
                case PLANKS -> {
                    out.add(new Want(g, "Logs", Material.OAK_LOG, m -> Tag.LOGS.isTagged(m) && !m.name().startsWith("STRIPPED"), 1));
                    out.add(new Want(g, "Planks (4 a unit)", Material.OAK_PLANKS, Tag.PLANKS::isTagged, 0.25));
                }
                case BREAD -> out.add(new Want(g, "Bread", Material.BREAD, m -> m == Material.BREAD, 1));
                case BEDS -> out.add(new Want(g, "Beds", Material.RED_BED, Tag.BEDS::isTagged, 1));
                default -> {
                }
            }
        }
        return out;
    }

    /** Price to a player for a product: the business's price, but never under the exchange's (or it'd be a free arbitrage). */
    private double priceOf(Biz b, Product pr) {
        double p = b.price * pr.units();
        ItemStack it = pr.item();
        if (Market.plain(it)) {
            Market.Ware w = t.market().ware(it.getType());
            if (w != null) p = Math.max(p, t.market().buyPrice(w) * it.getAmount() * 1.02);
        }
        return Math.max(1, Math.ceil(p));
    }

    /** What the business pays a player per unit of an input: a little over the exchange, never over 1.5x the fair price. */
    private double offer(Biz b, Want w) {
        Material m = Businesses.marketItem(w.good());
        double fair = w.good().ref;
        if (m != null) {
            Market.Ware ware = t.market().ware(m);
            if (ware != null) return Math.min(fair * 1.5, t.market().sellPrice(ware) * 1.1);
        }
        return fair * 0.8;
    }

    // ------------------------------------------------------------------ the screen

    static final class Menu implements InventoryHolder {
        final String biz;
        Inventory inv;

        Menu(String biz) {
            this.biz = biz;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    void open(Player p, Biz b) {
        Menu m = new Menu(b.id);
        m.inv = Bukkit.createInventory(m, 54, SmpPlugin.mm(Ai.clean(b.name, 28) + " · " + b.kind.label));
        fill(m, b, p);
        p.openInventory(m.inv);
    }

    private void fill(Menu m, Biz b, Player p) {
        Inventory inv = m.inv;
        inv.clear();
        ItemStack glass = named(Material.BROWN_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 9; i++) inv.setItem(i, glass);
        for (int i = 27; i < 36; i++) inv.setItem(i, glass);
        double stock = Math.floor(b.stock.getOrDefault(b.kind.output, 0.0));
        boolean owner = p.getUniqueId().equals(b.owner);
        inv.setItem(4, named(b.kind.output.icon, "<gold>" + Ai.clean(b.name, 28), "<gray>" + b.kind.label + (b.playerOwned() ? " · run by a player" : " · town-run"),
                "<gray>Rating " + String.format("%.1f", b.rating) + "★ · " + (int) stock + " " + b.kind.output.label.toLowerCase(Locale.ROOT) + " in stock",
                b.open() ? "<green>Open" : "<red>Closed: " + b.closedWhy));
        List<Product> pr = products(b);
        int[] buySlots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < pr.size() && i < buySlots.length; i++) {
            Product x = pr.get(i);
            ItemStack it = x.item().clone();
            ItemMeta im = it.getItemMeta();
            List<Component> lore = im.hasLore() && im.lore() != null ? new ArrayList<>(im.lore()) : new ArrayList<>();
            lore.add(SmpPlugin.mm("<!italic><gray>Price <yellow>" + Money.fmt(priceOf(b, x))));
            lore.add(SmpPlugin.mm("<!italic>" + (stock >= x.units() ? "<yellow>Click <gray>to buy" : "<red>Sold out (made each cycle when it's worked)")));
            im.lore(lore);
            it.setItemMeta(im);
            inv.setItem(buySlots[i], it);
        }
        List<Want> ws = wants(b);
        int[] sellSlots = {38, 39, 40, 41, 42};
        if (ws.isEmpty()) inv.setItem(40, named(Material.BARRIER, "<gray>Buys nothing from the public", "<dark_gray>It makes its goods from scratch."));
        for (int i = 0; i < ws.size() && i < sellSlots.length; i++) {
            Want w = ws.get(i);
            int have = carried(p.getInventory(), w);
            double room = bz.inputRoom(b, w.good());
            inv.setItem(sellSlots[i], named(w.icon(), "<white>Sell us: " + w.label(),
                    owner ? "<green>Stock your own business for free" : "<gray>Pays <yellow>" + Market.price(offer(b, w) * w.unitsEach()) + "</yellow> each",
                    "<gray>Wants up to <white>" + (int) Math.floor(room / w.unitsEach()) + "</white> more",
                    "<gray>You carry <white>" + have, "", "<yellow>Click <gray>sell 16 · <yellow>shift-click <gray>sell all"));
        }
        inv.setItem(49, named(Material.OAK_SIGN, "<gold>How it works", "<gray>Buy what this place makes; sell it", "<gray>what it needs to make more. Prices", "<gray>follow the market hall's exchange."));
    }

    private static boolean plain(ItemStack it) {
        return Market.plain(it);
    }

    private static int carried(PlayerInventory inv, Want w) {
        int n = 0;
        for (ItemStack it : inv.getStorageContents()) if (plain(it) && w.accepts().test(it.getType())) n += it.getAmount();
        return n;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        Biz b = bz.get(m.biz);
        if (b == null) {
            p.closeInventory();
            return;
        }
        if (!b.open()) {
            p.sendMessage(SmpPlugin.mm("<red>" + b.name + " is closed (" + b.closedWhy + ")."));
            return;
        }
        if (bz.at(p.getLocation()) != b) {
            p.sendMessage(SmpPlugin.mm("<gray>Step inside " + b.name + " to trade."));
            p.closeInventory();
            return;
        }
        int slot = e.getRawSlot();
        int[] buySlots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int[] sellSlots = {38, 39, 40, 41, 42};
        List<Product> pr = products(b);
        for (int i = 0; i < buySlots.length; i++) if (buySlots[i] == slot && i < pr.size()) {
            p.sendMessage(SmpPlugin.mm(buy(p, b, pr.get(i))));
            fill(m, b, p);
            return;
        }
        List<Want> ws = wants(b);
        for (int i = 0; i < sellSlots.length; i++) if (sellSlots[i] == slot && i < ws.size()) {
            p.sendMessage(SmpPlugin.mm(sell(p, b, ws.get(i), e.isShiftClick() ? Integer.MAX_VALUE : 16)));
            fill(m, b, p);
            return;
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    private String buy(Player p, Biz b, Product pr) {
        Good g = b.kind.output;
        if (b.stock.getOrDefault(g, 0.0) < pr.units()) return "<red>Sold out. <gray>It makes more each cycle when it's worked and supplied.";
        double price = priceOf(b, pr);
        if (g == Good.STAYS) {
            if (!t.money.pay(p, price)) return "<red>A room is " + Money.fmt(price) + ".";
            take(b, g, pr.units(), price);
            Location a = bz.anchor(b);
            if (a != null) p.setRespawnLocation(a.clone().add(0.5, 1, 0.5), true);
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 60, 1));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, 20 * 5, 0));
            p.addPotionEffect(new PotionEffect(PotionEffectType.HEALTH_BOOST, 20 * 60 * 20, 0));
            p.playSound(p.getLocation(), Sound.BLOCK_WOOL_PLACE, 1f, 0.8f);
            return "<green>You take a room at " + b.name + " (" + Money.fmt(price) + "). <gray>You'll wake here if you die, and you feel well rested.";
        }
        ItemStack it = pr.item().clone();
        if (Market.fits(p.getInventory(), it) < it.getAmount()) return "<red>Your inventory is full.";
        if (!t.money.pay(p, price)) return "<red>That's " + Money.fmt(price) + ".";
        take(b, g, pr.units(), price);
        p.getInventory().addItem(it);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        b.rating = Math.min(5, b.rating + 0.01);
        return "<green>Bought " + Stalls.label(it).toLowerCase(Locale.ROOT) + (it.getAmount() > 1 ? " ×" + it.getAmount() : "") + " for " + Money.fmt(price) + ".";
    }

    private void take(Biz b, Good g, double units, double price) {
        b.stock.merge(g, -units, Double::sum);
        b.cash += price;
        b.revenue += price;
        b.sold += units;
        Player o = b.owner == null ? null : Bukkit.getPlayer(b.owner);
        if (o != null && b.playerOwned()) o.sendActionBar(SmpPlugin.mm("<gold>[" + b.name + "] <gray>a customer paid " + Money.fmt(price)));
        bz.save();
    }

    private String sell(Player p, Biz b, Want w, int want) {
        boolean owner = p.getUniqueId().equals(b.owner);
        double room = Math.floor(bz.inputRoom(b, w.good()) / w.unitsEach());
        if (room <= 0) return "<gray>" + b.name + " has all the " + w.good().label.toLowerCase(Locale.ROOT) + " it can use for now.";
        int have = carried(p.getInventory(), w);
        int n = (int) Math.min(Math.min(want, have), room);
        if (n <= 0) return "<red>You have none. <gray>(Named or enchanted items aren't taken.)";
        double each = offer(b, w) * w.unitsEach();
        double pay = owner ? 0 : Math.floor(each * n);
        if (!owner && b.playerOwned() && b.cash < pay) {
            n = (int) Math.floor(b.cash / each);
            pay = Math.floor(each * n);
            if (n <= 0) return "<red>" + b.name + " can't afford to buy anything right now.";
        }
        if (!owner && pay < 1) return "<red>That's worth less than $1. <gray>Sell more at once.";
        // take the items
        ItemStack[] c = p.getInventory().getStorageContents();
        int left = n;
        for (int i = 0; i < c.length && left > 0; i++) {
            ItemStack it = c[i];
            if (!plain(it) || !w.accepts().test(it.getType())) continue;
            int k = Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - k);
            c[i] = it.getAmount() <= 0 ? null : it;
            left -= k;
        }
        p.getInventory().setStorageContents(c);
        b.stock.merge(w.good(), n * w.unitsEach(), Double::sum);
        if (!owner) {
            b.cash -= pay;
            b.costs += pay;
            t.money.give(p, pay);
        }
        bz.save();
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        return owner ? "<green>Stocked " + b.name + " with " + n + " " + w.label().toLowerCase(Locale.ROOT) + "."
                : "<green>Sold " + n + " " + w.label().toLowerCase(Locale.ROOT) + " to " + b.name + " for " + Money.fmt(pay) + ".";
    }

    // ------------------------------------------------------------------ opening it

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlock(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getHand() != EquipmentSlot.HAND) return;
        Block bl = e.getClickedBlock();
        if (bl.getType() != Material.BELL && bl.getType() != Material.LECTERN) return;
        if (bz.work().isTarget(e.getPlayer().getUniqueId(), bl)) return; // a worker's station task
        Biz b = bz.at(bl.getLocation());
        if (b == null || b.rival) return;
        if (e.getPlayer().isSneaking()) return; // sneak to use the block itself
        e.setCancelled(true);
        open(e.getPlayer(), b);
    }

    /** /business shop: the counter of the business you're standing in. */
    boolean command(Player p) {
        Biz b = bz.at(p.getLocation());
        if (b == null || b.rival) {
            p.sendMessage(SmpPlugin.mm("<gray>Stand inside a business to use its counter (or click its bell or lectern)."));
            return true;
        }
        open(p, b);
        return true;
    }

    /** Shopkeepers standing in a business open its counter when clicked (unless they've been given something else to do). */
    void wireShopkeepers() {
        int n = 0;
        for (var d : new ArrayList<>(t.plugin().npcs().all())) {
            if (d.action != null || d.id.startsWith("mk_")) continue;
            Location h = d.home();
            if (h == null) continue;
            Biz b = bz.at(h);
            if (b == null || b.rival) continue;
            d.action = "command:business shop";
            n++;
        }
        if (n > 0) {
            t.plugin().npcs().save();
            t.plugin().getLogger().info("Business counters: " + n + " shopkeepers now open their shop.");
        }
    }
}
