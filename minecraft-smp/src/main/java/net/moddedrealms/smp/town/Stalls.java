package net.moddedrealms.smp.town;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.moddedrealms.smp.SmpPlugin;
import net.moddedrealms.smp.npc.NpcDef;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Rentable stalls in the market hall. A player rents one by the day, fills it from their own inventory, sets a price
 * per item, and anyone can buy from it (the owner is paid even when offline, minus the market's fee). With a stall
 * keeper hired, townsfolk shopping in the hall buy market wares from it too, as long as the price isn't above what the
 * exchange charges. Rent is taken in advance; if it lapses the stall is cleared and its stock kept for /stall claim.
 */
public final class Stalls implements Listener {
    static final class Entry {
        ItemStack item; // amount 1
        int count;
        double price;
    }

    static final class Stall {
        final int n;
        UUID owner;
        String ownerName = "", name = "";
        long paidUntil;
        boolean autorenew = true, keeper, warned;
        final List<Entry> stock = new ArrayList<>();
        double earned;
        int sales;
        UUID sign;

        Stall(int n) {
            this.n = n;
        }

        boolean rented() {
            return owner != null;
        }

        int stacks() {
            int s = 0;
            for (Entry e : stock) s += (e.count + e.item.getMaxStackSize() - 1) / e.item.getMaxStackSize();
            return s;
        }
    }

    private final TownManager t;
    private final File file;
    private final Map<Integer, Stall> stalls = new HashMap<>();
    private final Map<UUID, List<ItemStack>> claims = new HashMap<>();
    private final Random r = new Random();
    private long nextRent, nextSales;

    Stalls(TownManager t) {
        this.t = t;
        this.file = new File(t.plugin().getDataFolder(), "stalls.yml");
        for (int n = 1; n <= MarketHall.STALLS.length; n++) stalls.put(n, new Stall(n));
        load();
    }

    private FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    private double rent() {
        return cfg().getDouble("town.market.stall-rent", 400);
    }

    private double keeperFee() {
        return cfg().getDouble("town.market.keeper-fee", 100);
    }

    private double fee() {
        return cfg().getDouble("town.market.stall-fee", 0.05);
    }

    private MarketHall hall() {
        return t.marketHall();
    }

    private static boolean say(Player p, String m) {
        p.sendMessage(SmpPlugin.mm(m));
        return true;
    }

    private boolean near(Player p, int n) {
        MarketHall h = hall();
        if (h == null || !h.built()) return false;
        Block c = h.stallCounter(n);
        return c.getWorld() == p.getWorld() && c.getLocation().add(0.5, 0, 0.5).distanceSquared(p.getLocation()) <= 64;
    }

    private int owned(UUID u) {
        int k = 0;
        for (Stall s : stalls.values()) if (u.equals(s.owner)) k++;
        return k;
    }

    // ------------------------------------------------------------------ renting

    private String rentFor(Player p, int n, int days) {
        Stall s = stalls.get(n);
        if (s == null) return "<red>No such stall.";
        if (!near(p, n)) return "<gray>Go to stall " + n + " in the market hall to rent it.";
        if (s.rented() && !p.getUniqueId().equals(s.owner)) return "<red>Stall " + n + " is taken by " + s.ownerName + ".";
        days = Math.max(1, Math.min(days, cfg().getInt("town.market.max-rent-days", 14)));
        boolean extend = s.rented();
        if (!extend && owned(p.getUniqueId()) >= cfg().getInt("town.market.max-stalls", 2)) return "<red>You already rent as many stalls as you're allowed.";
        double cost = (rent() + (s.keeper ? keeperFee() : 0)) * days;
        if (!t.money.pay(p, cost)) return "<red>That's " + Money.fmt(cost) + " and you can't afford it.";
        long now = System.currentTimeMillis();
        if (!extend) {
            s.owner = p.getUniqueId();
            s.ownerName = p.getName();
            s.name = p.getName() + "'s stall";
            s.paidUntil = now;
            s.autorenew = true;
            s.keeper = false;
        }
        s.paidUntil = Math.max(s.paidUntil, now) + days * 86_400_000L;
        s.warned = false;
        save();
        refresh(n);
        return "<green>" + (extend ? "Extended" : "Rented") + " stall " + n + " for " + days + " day" + (days == 1 ? "" : "s") + " (" + Money.fmt(cost) + "). "
                + "<gray>Right-click it to stock it and set prices; <white>/stall</white> for the rest.";
    }

    private void vacate(int n, String why) {
        Stall s = stalls.get(n);
        if (s == null || !s.rented()) return;
        List<ItemStack> owed = claims.computeIfAbsent(s.owner, k -> new ArrayList<>());
        for (Entry e : s.stock) {
            int left = e.count;
            while (left > 0) {
                int k = Math.min(left, e.item.getMaxStackSize());
                ItemStack it = e.item.clone();
                it.setAmount(k);
                owed.add(it);
                left -= k;
            }
        }
        Player o = Bukkit.getPlayer(s.owner);
        if (o != null) o.sendMessage(SmpPlugin.mm("<gold>[Stall " + n + "] <gray>" + why + ". The stall is cleared; your stock waits for you: <white>/stall claim"));
        t.plugin().npcs().remove("mk_keeper_" + n);
        Stall fresh = new Stall(n);
        fresh.sign = s.sign;
        stalls.put(n, fresh);
        save();
        refresh(n);
    }

    /** The hall is being taken down: everyone's stock goes to claims. */
    void hallGone() {
        for (int n = 1; n <= MarketHall.STALLS.length; n++) {
            vacate(n, "The market hall was closed");
            stalls.get(n).sign = null;
        }
        save();
    }

    String summary() {
        int rented = 0;
        for (Stall s : stalls.values()) if (s.rented()) rented++;
        return rented + " of " + stalls.size() + " stalls rented.";
    }

    // ------------------------------------------------------------------ signs and keepers

    void refreshAll() {
        for (int n = 1; n <= MarketHall.STALLS.length; n++) refresh(n);
    }

    void refresh(int n) {
        MarketHall h = hall();
        Stall s = stalls.get(n);
        if (h == null || !h.built() || s == null) return;
        Location at = h.stallSign(n);
        if (!at.isChunkLoaded()) return;
        Entity e = s.sign == null ? null : Bukkit.getEntity(s.sign);
        TextDisplay td = e instanceof TextDisplay x ? x : null;
        if (td == null) {
            td = at.getWorld().spawn(at, TextDisplay.class, d -> {
                d.setBillboard(Display.Billboard.CENTER);
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                d.setShadowed(true);
                d.setPersistent(true);
                d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                        new org.joml.Vector3f(0.7f, 0.7f, 0.7f), new org.joml.Quaternionf()));
                d.getPersistentDataContainer().set(h.key, PersistentDataType.STRING, "stall");
            });
            s.sign = td.getUniqueId();
            save();
        }
        String text;
        if (!s.rented()) text = "<gray>Stall " + n + "\n<green>For rent · " + Money.fmt(rent()) + "/day\n<dark_gray>right-click the counter";
        else {
            StringBuilder b = new StringBuilder("<gold>" + Ai.clean(s.name, 28) + "\n<gray>Stall " + n + " · " + s.ownerName);
            int shown = 0;
            for (Entry en : s.stock) {
                if (shown++ >= 3) break;
                b.append("\n<white>").append(label(en.item)).append(" <yellow>").append(Market.price(en.price));
            }
            if (s.stock.isEmpty()) b.append("\n<dark_gray>(empty)");
            if (s.keeper) b.append("\n<green>staffed");
            text = b.toString();
        }
        td.text(SmpPlugin.mm(text));
        // keeper NPC
        NpcDef d = t.plugin().npcs().get("mk_keeper_" + n);
        if (s.rented() && s.keeper && d == null) {
            Location k = h.keeperSpot(n);
            NpcDef nd = new NpcDef();
            nd.id = "mk_keeper_" + n;
            nd.name = "<white>" + pickName();
            nd.title = "<gray>Minds " + Ai.clean(s.name, 24);
            nd.world = k.getWorld().getName();
            nd.x = k.getX();
            nd.y = k.getY();
            nd.z = k.getZ();
            nd.yaw = k.getYaw();
            nd.group = MarketHall.ID;
            nd.action = "command:stall open " + n;
            nd.texture = net.moddedrealms.smp.Pack.pick("merchant");
            nd.equipment.put(EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE);
            t.plugin().npcs().put(nd);
        } else if ((!s.rented() || !s.keeper) && d != null) {
            t.plugin().npcs().remove(d.id);
        } else if (d != null) {
            String title = "<gray>Minds " + Ai.clean(s.name, 24);
            if (!title.equals(d.title)) {
                d.title = title;
                t.plugin().npcs().refresh(d.id);
            }
        }
    }

    private String pickName() {
        String[] n = {"Alys", "Benet", "Clemence", "Dickon", "Emmot", "Floris", "Gib", "Hawise", "Ivetta", "Jack", "Lucia", "Matilda", "Nicol", "Osbert", "Rose", "Tomkin"};
        return n[r.nextInt(n.length)];
    }

    static String label(ItemStack it) {
        if (it.getItemMeta() != null && it.getItemMeta().hasDisplayName()) return SmpPlugin.MM.serialize(it.getItemMeta().displayName()).replaceAll("<[^>]*>", "");
        return Market.nice(it.getType());
    }

    /** A shopper walks over to a stall that's open for business. */
    void browse(Walker x) {
        MarketHall h = hall();
        if (h == null) return;
        List<Integer> open = new ArrayList<>();
        for (Stall s : stalls.values()) if (s.rented() && !s.stock.isEmpty()) open.add(s.n);
        if (open.isEmpty()) return;
        int n = open.get(r.nextInt(open.size()));
        Block c = h.stallCounter(n);
        Location front = h.stallSign(n).clone();
        front.setY(c.getY());
        x.moveTo(front, 0.55);
    }

    // ------------------------------------------------------------------ time passing

    void second() {
        long now = System.currentTimeMillis();
        if (now >= nextRent) {
            nextRent = now + 60_000;
            for (Stall s : new ArrayList<>(stalls.values())) {
                if (!s.rented()) continue;
                if (!s.warned && s.paidUntil - now < 3_600_000L && !s.autorenew) {
                    Player o = Bukkit.getPlayer(s.owner);
                    if (o != null) o.sendMessage(SmpPlugin.mm("<gold>[Stall " + s.n + "] <yellow>Rent runs out within the hour. <white>/stall extend " + s.n));
                    s.warned = true;
                }
                if (now < s.paidUntil) continue;
                double due = rent() + (s.keeper ? keeperFee() : 0);
                OfflinePlayer o = Bukkit.getOfflinePlayer(s.owner);
                if (s.autorenew && t.money.bankPay(o, due)) {
                    s.paidUntil += 86_400_000L;
                    s.warned = false;
                    Player op = o.getPlayer();
                    if (op != null) op.sendMessage(SmpPlugin.mm("<gold>[Stall " + s.n + "] <gray>Another day's rent paid: " + Money.fmt(due) + "."));
                    save();
                } else vacate(s.n, s.autorenew ? "Couldn't pay the rent (" + Money.fmt(due) + ")" : "The rent ran out");
            }
        }
        if (now >= nextSales) {
            nextSales = now + cfg().getLong("town.market.stall-sales-minutes", 5) * 60_000L;
            if (!Bukkit.getOnlinePlayers().isEmpty()) npcSales();
        }
    }

    /** Townsfolk buy market wares from staffed stalls that price at or under the exchange. */
    private void npcSales() {
        Market m = t.market();
        for (Stall s : stalls.values()) {
            if (!s.rented() || s.stock.isEmpty()) continue;
            boolean staffed = s.keeper || Bukkit.getPlayer(s.owner) != null && hall() != null && hall().inside(Bukkit.getPlayer(s.owner).getLocation());
            if (!staffed) continue;
            int budget = cfg().getInt("town.market.npc-units-per-stall", 12);
            for (Entry e : new ArrayList<>(s.stock)) {
                if (budget <= 0) break;
                if (!Market.plain(e.item)) continue;
                Market.Ware w = m.ware(e.item.getType());
                if (w == null) continue;
                double ex = m.buyPrice(w);
                if (e.price > ex) continue;
                double chance = Math.min(1, 0.3 + (ex - e.price) / ex * 3);
                if (r.nextDouble() > chance) continue;
                int n = Math.min(budget, Math.min(e.count, 1 + r.nextInt(6)));
                sold(s, e, n, null);
                budget -= n;
            }
        }
    }

    private void sold(Stall s, Entry e, int n, Player buyer) {
        double gross = e.price * n;
        double net = Math.floor(gross * (1 - fee()));
        e.count -= n;
        if (e.count <= 0) s.stock.remove(e);
        s.earned += net;
        s.sales += n;
        t.money.bankGive(Bukkit.getOfflinePlayer(s.owner), net);
        Player o = Bukkit.getPlayer(s.owner);
        if (o != null) o.sendMessage(SmpPlugin.mm("<gold>[Stall " + s.n + "] <white>" + (buyer == null ? "A townsperson" : buyer.getName()) + " <gray>bought " + n + " "
                + label(e.item).toLowerCase(Locale.ROOT) + ": <green>+" + Money.fmt(net)));
        save();
        refresh(s.n);
    }

    // ------------------------------------------------------------------ the screens

    static final class Menu implements InventoryHolder {
        final int n;
        final boolean owner;
        Inventory inv;

        Menu(int n, boolean owner) {
            this.n = n;
            this.owner = owner;
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

    void open(Player p, int n) {
        Stall s = stalls.get(n);
        if (s == null) return;
        if (!s.rented()) {
            p.sendMessage(SmpPlugin.mm("<gold>Stall " + n + " <gray>is for rent: " + Money.fmt(rent()) + " a day (a keeper who sells for you: +" + Money.fmt(keeperFee()) + ")."));
            var b = Component.text().append(SmpPlugin.mm("  "));
            for (int d : new int[]{1, 3, 7}) {
                b.append(SmpPlugin.mm("<green>[" + d + " day" + (d == 1 ? "" : "s") + ", " + Money.fmt(rent() * d) + "]").clickEvent(ClickEvent.runCommand("/stall rent " + n + " " + d)))
                        .append(SmpPlugin.mm("  "));
            }
            p.sendMessage(b.build());
            return;
        }
        boolean owner = p.getUniqueId().equals(s.owner);
        Menu m = new Menu(n, owner);
        m.inv = Bukkit.createInventory(m, 54, SmpPlugin.mm(Ai.clean(s.name, 30) + (owner ? " (yours)" : "")));
        fill(m, s);
        p.openInventory(m.inv);
    }

    private static double step(double price) {
        if (price < 20) return 1;
        if (price < 200) return 5;
        if (price < 2000) return 25;
        return 250;
    }

    private void fill(Menu m, Stall s) {
        Inventory inv = m.inv;
        inv.clear();
        for (int i = 0; i < s.stock.size() && i < 45; i++) {
            Entry e = s.stock.get(i);
            ItemStack it = e.item.clone();
            it.setAmount(Math.max(1, Math.min(e.count, it.getMaxStackSize())));
            ItemMeta im = it.getItemMeta();
            List<Component> lore = im.hasLore() && im.lore() != null ? new ArrayList<>(im.lore()) : new ArrayList<>();
            lore.add(SmpPlugin.mm("<!italic><gray>Price <yellow>" + Money.fmt(e.price) + "</yellow> each · <white>" + e.count + "</white> in stock"));
            Market.Ware w = Market.plain(e.item) ? t.market().ware(e.item.getType()) : null;
            if (w != null) lore.add(SmpPlugin.mm("<!italic><dark_gray>Exchange: buy " + Market.price(t.market().buyPrice(w)) + " · sell " + Market.price(t.market().sellPrice(w))));
            if (m.owner) {
                double st = step(e.price);
                lore.add(SmpPlugin.mm("<!italic><yellow>Left/right <gray>±" + Money.fmt(st) + " · <yellow>shift <gray>±" + Money.fmt(st * 10)));
                lore.add(SmpPlugin.mm("<!italic><yellow>Q <gray>take it all back"));
            } else lore.add(SmpPlugin.mm("<!italic><yellow>Click <gray>buy 1 · <yellow>shift-click <gray>buy a stack"));
            im.lore(lore);
            it.setItemMeta(im);
            inv.setItem(i, it);
        }
        long left = Math.max(0, s.paidUntil - System.currentTimeMillis());
        if (m.owner) {
            inv.setItem(45, icon(Material.PAPER, "<gold>" + Ai.clean(s.name, 30), List.of("<gray>Rent paid for " + left / 3_600_000 + "h " + left / 60_000 % 60 + "m",
                    "<gray>Sold " + s.sales + " items, earned " + Money.fmt(s.earned), "<gray>Space: " + s.stacks() + "/27 stacks", "",
                    "<gray>Click items in your inventory to", "<gray>put them on the stall. /stall name " + s.n + " <name>")));
            inv.setItem(47, icon(Material.CLOCK, "<green>Pay another day", List.of("<gray>" + Money.fmt(rent() + (s.keeper ? keeperFee() : 0)))));
            inv.setItem(49, icon(s.keeper ? Material.PLAYER_HEAD : Material.SKELETON_SKULL, s.keeper ? "<green>Keeper hired" : "<gray>No keeper",
                    List.of("<gray>A keeper minds the stall while you're away,", "<gray>and townsfolk buy market wares from it", "<gray>if you price at or under the exchange.",
                            "<gray>+" + Money.fmt(keeperFee()) + " a day. Click to " + (s.keeper ? "dismiss." : "hire."))));
            inv.setItem(51, icon(s.autorenew ? Material.LIME_DYE : Material.GRAY_DYE, s.autorenew ? "<green>Rent renews itself" : "<gray>Rent doesn't renew",
                    List.of("<gray>Click to switch.")));
            inv.setItem(53, icon(Material.BARRIER, "<red>Give up the stall", List.of("<gray>Your stock comes back to you.", "<gray>Shift-click to confirm.")));
        } else {
            inv.setItem(49, icon(Material.PAPER, "<gold>" + Ai.clean(s.name, 30), List.of("<gray>Run by " + s.ownerName, "<gray>Pay the stall; the market takes a small fee.")));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Stall s = stalls.get(m.n);
        if (s == null || !s.rented()) {
            p.closeInventory();
            return;
        }
        if (m.owner && !p.getUniqueId().equals(s.owner)) return;
        boolean top = e.getClickedInventory() == e.getView().getTopInventory();
        if (!top) {
            if (!m.owner || e.getClickedInventory() == null) return;
            ItemStack it = e.getCurrentItem();
            if (it == null || it.getType().isAir()) return;
            String why = add(s, it);
            if (why != null) {
                say(p, why);
                return;
            }
            e.getClickedInventory().setItem(e.getSlot(), null);
            p.playSound(p.getLocation(), Sound.ITEM_BUNDLE_INSERT, 0.7f, 1f);
            save();
            refresh(s.n);
            fill(m, s);
            return;
        }
        int slot = e.getRawSlot();
        if (slot < 45) {
            if (slot >= s.stock.size()) return;
            Entry en = s.stock.get(slot);
            if (m.owner) {
                double st = step(en.price);
                ClickType c = e.getClick();
                if (c == ClickType.DROP || c == ClickType.CONTROL_DROP) {
                    takeBack(p, s, en);
                } else if (c == ClickType.LEFT) en.price += st;
                else if (c == ClickType.RIGHT) en.price = Math.max(1, en.price - st);
                else if (c == ClickType.SHIFT_LEFT) en.price += st * 10;
                else if (c == ClickType.SHIFT_RIGHT) en.price = Math.max(1, en.price - st * 10);
                save();
                refresh(s.n);
                fill(m, s);
                return;
            }
            int want = e.isShiftClick() ? en.item.getMaxStackSize() : 1;
            say(p, buy(p, s, en, want));
            fill(m, s);
            return;
        }
        if (!m.owner) return;
        switch (slot) {
            case 47 -> {
                say(p, rentFor(p, s.n, 1));
                fill(m, s);
            }
            case 49 -> {
                s.keeper = !s.keeper;
                say(p, s.keeper ? "<green>You've hired a keeper for stall " + s.n + " (" + Money.fmt(keeperFee()) + " a day, from the next rent)." : "<gray>The keeper's gone home.");
                save();
                refresh(s.n);
                fill(m, s);
            }
            case 51 -> {
                s.autorenew = !s.autorenew;
                save();
                fill(m, s);
            }
            case 53 -> {
                if (!e.isShiftClick()) return;
                p.closeInventory();
                vacate(s.n, "You gave up the stall");
                say(p, claim(p));
            }
            default -> {
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    /** Put a stack on the stall. Returns why not, or null. */
    private String add(Stall s, ItemStack it) {
        if (it.getItemMeta() != null && it.getItemMeta().getPersistentDataContainer().has(t.stolenKey)) {
            return "<red>Stolen goods can't go on a market stall. Find a fence.";
        }
        if (it.getItemMeta() != null && it.getItemMeta().getPersistentDataContainer().has(Dining.DISH, PersistentDataType.STRING)) {
            return "<red>That dish is for a customer.";
        }
        ItemStack one = it.clone();
        one.setAmount(1);
        Entry match = null;
        for (Entry e : s.stock) if (e.item.isSimilar(one)) match = e;
        int stacksAfter = s.stacks() + (match == null ? (it.getAmount() + it.getMaxStackSize() - 1) / it.getMaxStackSize()
                : (match.count + it.getAmount() + it.getMaxStackSize() - 1) / it.getMaxStackSize() - (match.count + it.getMaxStackSize() - 1) / it.getMaxStackSize());
        if (stacksAfter > 27) return "<red>The stall is full (27 stacks).";
        if (match == null) {
            if (s.stock.size() >= 45) return "<red>That's as many kinds of item as a stall can show.";
            match = new Entry();
            match.item = one;
            Market.Ware w = Market.plain(one) ? t.market().ware(one.getType()) : null;
            match.price = w == null ? 10 : Math.max(1, Math.ceil(t.market().buyPrice(w)));
            s.stock.add(match);
        }
        match.count += it.getAmount();
        return null;
    }

    private void takeBack(Player p, Stall s, Entry e) {
        int left = e.count;
        while (left > 0) {
            int k = Math.min(left, e.item.getMaxStackSize());
            ItemStack it = e.item.clone();
            it.setAmount(k);
            for (ItemStack rest : p.getInventory().addItem(it).values()) claims.computeIfAbsent(p.getUniqueId(), x -> new ArrayList<>()).add(rest);
            left -= k;
        }
        s.stock.remove(e);
        if (claims.containsKey(p.getUniqueId())) say(p, "<yellow>Your inventory filled up; the rest waits for you: /stall claim");
    }

    private String buy(Player p, Stall s, Entry e, int want) {
        if (!near(p, s.n) && !(hall() != null && hall().inside(p.getLocation()))) return "<gray>Step up to the stall to buy.";
        int n = Math.min(want, e.count);
        ItemStack like = e.item.clone();
        n = Math.min(n, Market.fits(p.getInventory(), like));
        if (n <= 0) return "<red>Your inventory is full.";
        double cost = e.price * n;
        if (!t.money.pay(p, cost)) {
            n = (int) Math.floor(t.money.balance(p) / e.price);
            if (n <= 0) return "<red>You can't afford it (" + Money.fmt(e.price) + " each).";
            cost = e.price * n;
            if (!t.money.pay(p, cost)) return "<red>Payment failed.";
        }
        ItemStack it = e.item.clone();
        it.setAmount(n);
        for (ItemStack rest : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), rest);
        String what = n + " " + label(e.item).toLowerCase(Locale.ROOT);
        sold(s, e, n, p);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        return "<green>Bought " + what + " for " + Money.fmt(cost) + ".";
    }

    private String claim(Player p) {
        List<ItemStack> owed = claims.get(p.getUniqueId());
        if (owed == null || owed.isEmpty()) return "<gray>Nothing waiting for you.";
        List<ItemStack> keep = new ArrayList<>();
        int given = 0;
        for (ItemStack it : owed) {
            var rest = p.getInventory().addItem(it);
            if (rest.isEmpty()) given += it.getAmount();
            else keep.addAll(rest.values());
        }
        if (keep.isEmpty()) claims.remove(p.getUniqueId());
        else claims.put(p.getUniqueId(), keep);
        save();
        return "<green>Collected " + given + " items." + (keep.isEmpty() ? "" : " <yellow>Make room for the rest and /stall claim again.");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onStallClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getHand() != EquipmentSlot.HAND) return;
        MarketHall h = hall();
        if (h == null || !h.built()) return;
        int n = h.stallAt(e.getClickedBlock());
        if (n == 0) return;
        e.setCancelled(true);
        open(e.getPlayer(), n);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        List<ItemStack> owed = claims.get(e.getPlayer().getUniqueId());
        if (owed != null && !owed.isEmpty()) {
            e.getPlayer().sendMessage(SmpPlugin.mm("<gold>[Market] <gray>Stock from your old stall is waiting for you: <white>/stall claim"));
        }
    }

    // ------------------------------------------------------------------ /stall

    boolean command(Player p, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        int n = a.length > 1 ? parse(a[1]) : mineHere(p);
        switch (sub) {
            case "rent", "extend" -> {
                if (n <= 0) return say(p, "<yellow>/stall " + sub + " <stall number> [days]");
                return say(p, rentFor(p, n, a.length > 2 ? Math.max(1, parse(a[2])) : 1));
            }
            case "open" -> {
                if (n <= 0) return true;
                if (!near(p, n)) return say(p, "<gray>Step up to stall " + n + " first.");
                open(p, n);
            }
            case "leave" -> {
                Stall s = n > 0 ? stalls.get(n) : null;
                if (s == null || !p.getUniqueId().equals(s.owner)) return say(p, "<yellow>/stall leave <your stall number>");
                vacate(n, "You gave up the stall");
                say(p, claim(p));
            }
            case "claim" -> say(p, claim(p));
            case "name" -> {
                Stall s = n > 0 ? stalls.get(n) : null;
                if (s == null || !p.getUniqueId().equals(s.owner) || a.length < 3) return say(p, "<yellow>/stall name <your stall number> <name>");
                s.name = Ai.clean(String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)), 28);
                save();
                refresh(n);
                say(p, "<green>Stall " + n + " is now \"" + s.name + "\".");
            }
            default -> {
                p.sendMessage(SmpPlugin.mm("<gold><bold>Market stalls</bold> <gray>(" + Money.fmt(rent()) + "/day, the market keeps " + Math.round(fee() * 100) + "% of sales)"));
                boolean any = false;
                for (Stall s : stalls.values()) {
                    if (!p.getUniqueId().equals(s.owner)) continue;
                    any = true;
                    long left = Math.max(0, s.paidUntil - System.currentTimeMillis());
                    p.sendMessage(SmpPlugin.mm(" <white>Stall " + s.n + " <gray>\"" + s.name + "\" · " + s.stock.size() + " kinds · paid " + left / 3_600_000 + "h · earned "
                            + Money.fmt(s.earned) + (s.keeper ? " · keeper" : "")));
                }
                if (!any) say(p, " <gray>Right-click a stall's counter in the market hall to rent it.");
                say(p, " <gray>/stall rent|extend <n> [days] · name <n> <name> · leave <n> · claim");
            }
        }
        return true;
    }

    private int mineHere(Player p) {
        MarketHall h = hall();
        if (h == null || !h.built()) return 0;
        for (int n = 1; n <= MarketHall.STALLS.length; n++) if (near(p, n)) return n;
        return 0;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ saving

    private static String enc(ItemStack it) {
        return Base64.getEncoder().encodeToString(it.serializeAsBytes());
    }

    private static ItemStack dec(String s) {
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection ss = y.getConfigurationSection("stalls");
        if (ss != null) {
            for (String k : ss.getKeys(false)) {
                int n = parse(k);
                Stall s = stalls.get(n);
                if (s == null) continue;
                ConfigurationSection c = ss.getConfigurationSection(k);
                String o = c.getString("owner");
                s.owner = o == null ? null : UUID.fromString(o);
                s.ownerName = c.getString("owner-name", "");
                s.name = c.getString("name", "");
                s.paidUntil = c.getLong("paid-until");
                s.autorenew = c.getBoolean("autorenew", true);
                s.keeper = c.getBoolean("keeper");
                s.earned = c.getDouble("earned");
                s.sales = c.getInt("sales");
                String sign = c.getString("sign");
                s.sign = sign == null ? null : UUID.fromString(sign);
                for (Map<?, ?> m : c.getMapList("stock")) {
                    ItemStack it = dec(String.valueOf(m.get("item")));
                    if (it == null) continue;
                    Entry e = new Entry();
                    e.item = it;
                    e.count = ((Number) m.get("count")).intValue();
                    e.price = ((Number) m.get("price")).doubleValue();
                    s.stock.add(e);
                }
            }
        }
        ConfigurationSection cs = y.getConfigurationSection("claims");
        if (cs != null) {
            for (String k : cs.getKeys(false)) {
                List<ItemStack> l = new ArrayList<>();
                for (String s : cs.getStringList(k)) {
                    ItemStack it = dec(s);
                    if (it != null) l.add(it);
                }
                if (!l.isEmpty()) claims.put(UUID.fromString(k), l);
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Stall s : stalls.values()) {
            String k = "stalls." + s.n;
            if (s.sign != null) y.set(k + ".sign", s.sign.toString());
            if (!s.rented()) continue;
            y.set(k + ".owner", s.owner.toString());
            y.set(k + ".owner-name", s.ownerName);
            y.set(k + ".name", s.name);
            y.set(k + ".paid-until", s.paidUntil);
            y.set(k + ".autorenew", s.autorenew);
            y.set(k + ".keeper", s.keeper);
            y.set(k + ".earned", s.earned);
            y.set(k + ".sales", s.sales);
            List<Map<String, Object>> st = new ArrayList<>();
            for (Entry e : s.stock) st.add(Map.of("item", enc(e.item), "count", e.count, "price", e.price));
            y.set(k + ".stock", st);
        }
        for (var e : claims.entrySet()) {
            List<String> l = new ArrayList<>();
            for (ItemStack it : e.getValue()) l.add(enc(it));
            y.set("claims." + e.getKey(), l);
        }
        try {
            net.moddedrealms.smp.SafeYaml.save(y, file);
        } catch (IOException ignored) {
        }
    }
}
