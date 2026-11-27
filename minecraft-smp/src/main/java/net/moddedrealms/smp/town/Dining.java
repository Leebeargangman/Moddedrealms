package net.moddedrealms.smp.town;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.moddedrealms.smp.SmpPlugin;
import net.moddedrealms.smp.town.Businesses.Biz;
import net.moddedrealms.smp.town.Econ.Kind;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Table service at cookhouses and taverns. A worker on shift gets diners: one comes in asking for a table, the worker
 * clicks them, they follow, and the worker clicks a free chair (a stair or slab inside) to seat them. Seated, they
 * order something off the menu; the worker cooks it at a stove (pick the dish, wait a few seconds), carries it over
 * and hands it to the diner who ordered it. The right dish earns the business a sale and the worker a tip (smaller
 * the longer they waited); the wrong one gets sent back. When they've eaten they leave, and the table needs clearing
 * before the next diner can sit there. Town-run eateries have NPC diners eating at their tables too.
 */
public final class Dining implements Listener {
    static NamespacedKey DISH;

    record Dish(String id, String label, Material mat) {}

    enum Stage { WAITING, FOLLOWING, WALKING, SEATED, ORDERED, EATING, LEAVING }

    static final class Diner {
        Walker w;
        Stage st = Stage.WAITING;
        Block seat;
        Dish dish;
        long since, deadline, orderedAt;
        int wrong;
        UUID worker;
        String biz;
        boolean ambient;
    }

    private record Cooking(Dish dish, Block stove, long until, String biz) {}

    private static final Map<Kind, List<Dish>> MENUS = new EnumMap<>(Kind.class);

    static {
        MENUS.put(Kind.RESTAURANT, List.of(new Dish("rabbit_stew", "Rabbit stew", Material.RABBIT_STEW), new Dish("mushroom_stew", "Mushroom stew", Material.MUSHROOM_STEW),
                new Dish("beetroot_soup", "Beetroot soup", Material.BEETROOT_SOUP), new Dish("steak", "Steak", Material.COOKED_BEEF),
                new Dish("baked_potato", "Baked potato", Material.BAKED_POTATO), new Dish("pumpkin_pie", "Pumpkin pie", Material.PUMPKIN_PIE),
                new Dish("salmon", "Grilled salmon", Material.COOKED_SALMON)));
        MENUS.put(Kind.TAVERN, List.of(new Dish("ale", "Ale", Material.HONEY_BOTTLE), new Dish("pork_pie", "Pork pie", Material.COOKED_PORKCHOP),
                new Dish("bread", "Bread and dripping", Material.BREAD), new Dish("mutton", "Roast mutton", Material.COOKED_MUTTON),
                new Dish("chicken", "Roast chicken", Material.COOKED_CHICKEN)));
    }

    private final TownManager t;
    private final Businesses bz;
    private final Work work;
    private final List<Diner> diners = new ArrayList<>();
    private final Map<Block, Long> dirty = new HashMap<>();
    private final Map<UUID, Cooking> cooking = new HashMap<>();
    private final Map<String, List<Block>> seatCache = new HashMap<>();
    private final Map<String, Long> seatCacheAt = new HashMap<>();
    private final Random r = new Random();
    private int tick;

    Dining(TownManager t, Businesses bz, Work work) {
        this.t = t;
        this.bz = bz;
        this.work = work;
        DISH = new NamespacedKey(t.plugin(), "dish");
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    static boolean handles(Biz b) {
        return b != null && MENUS.containsKey(b.kind);
    }

    int countFor(UUID worker) {
        int n = 0;
        for (Diner d : diners) if (!d.ambient && worker.equals(d.worker) && d.st != Stage.LEAVING) n++;
        return n;
    }

    int waiting(String biz) {
        int n = 0;
        for (Diner d : diners) if (!d.ambient && biz.equals(d.biz) && d.st != Stage.LEAVING) n++;
        return n;
    }

    // ------------------------------------------------------------------ seats

    /** The business's area in world coordinates: x0, y0, z0, x1, y1, z1. */
    private int[] bounds(Biz b) {
        Location c = bz.anchor(b);
        if (c == null) return null;
        Plots.Plot p = b.plot == null ? null : t.plots().get(b.plot);
        if (p != null) return new int[]{p.x0, p.floor, p.z0, p.x1, p.floor + 12, p.z1};
        return new int[]{c.getBlockX() - 10, c.getBlockY() - 2, c.getBlockZ() - 10, c.getBlockX() + 10, c.getBlockY() + 10, c.getBlockZ() + 10};
    }

    static boolean seat(Block b) {
        return b.getBlockData() instanceof Stairs s && s.getHalf() == Bisected.Half.BOTTOM && s.getShape() == Stairs.Shape.STRAIGHT
                || b.getBlockData() instanceof Slab sl && sl.getType() == Slab.Type.BOTTOM;
    }

    private List<Block> seats(Biz b) {
        long now = System.currentTimeMillis();
        Long at = seatCacheAt.get(b.id);
        if (at != null && now - at < 60_000) return seatCache.get(b.id);
        List<Block> out = new ArrayList<>();
        int[] a = bounds(b);
        Location c = bz.anchor(b);
        if (a != null && c != null) {
            World w = c.getWorld();
            for (int x = a[0]; x <= a[3]; x++) for (int z = a[2]; z <= a[5]; z++) for (int y = a[1]; y <= a[4]; y++) {
                Block bl = w.getBlockAt(x, y, z);
                if (!seat(bl)) continue;
                if (!bl.getRelative(0, 1, 0).isPassable() || !bl.getRelative(0, 2, 0).isPassable()) continue;
                if (bz.at(bl.getLocation()) != b) continue;
                out.add(bl);
            }
        }
        seatCache.put(b.id, out);
        seatCacheAt.put(b.id, now);
        return out;
    }

    private boolean taken(Block seat) {
        if (dirty.containsKey(seat)) return true;
        for (Diner d : diners) if (seat.equals(d.seat)) return true;
        return false;
    }

    private Block freeSeat(Biz b) {
        List<Block> free = new ArrayList<>();
        for (Block s : seats(b)) if (!taken(s)) free.add(s);
        return free.isEmpty() ? null : free.get(r.nextInt(free.size()));
    }

    /** Which way to sit: towards a table next to the seat, else away from the stair's back. */
    private static float sitYaw(Block seat) {
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block n = seat.getRelative(f);
            Material m = n.getType();
            if (!seat(n) && (m.isSolid() || m.name().endsWith("_FENCE") || m.name().endsWith("_TRAPDOOR") || m.name().endsWith("_PRESSURE_PLATE"))) return MarketHall.yaw(f);
        }
        if (seat.getBlockData() instanceof Stairs s) return MarketHall.yaw(s.getFacing().getOppositeFace());
        return 0;
    }

    // ------------------------------------------------------------------ diners for workers

    /** A new diner for a worker on shift. Returns true if one came in. */
    boolean spawnFor(Player p, Biz b) {
        if (!t.canSpawnWalkers(p.getWorld())) return false;
        if (countFor(p.getUniqueId()) >= cfg().getInt("town.business.dining.max-diners", 2)) return false;
        if (freeSeat(b) == null) {
            p.sendActionBar(SmpPlugin.mm("<yellow>No free seats for diners: put chairs (stairs or slabs) inside, and clear the tables."));
            return false;
        }
        Location at = t.spawnSpot(p, 3, 7);
        if (at == null || bz.at(at) != b) at = work.floorIn(b);
        if (at == null || bz.at(at) != b) return false;
        Walker w = Walker.spawn(t.key, at, Walker.Role.CUSTOMER, "<white>" + name(), "<yellow>Table for one, please", 0.22, 12, Map.of());
        w.gang = b.id;
        t.register(w);
        Diner d = new Diner();
        d.w = w;
        d.biz = b.id;
        d.worker = p.getUniqueId();
        d.since = System.currentTimeMillis();
        d.deadline = d.since + cfg().getLong("town.business.dining.seat-seconds", 45) * 1000;
        diners.add(d);
        w.moveTo(p, 0.8);
        p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 0.7f, 1.4f);
        p.sendMessage(SmpPlugin.mm("<gold>A diner has come in. <white>Right-click them</white><gray>, then right-click a free seat to show them to a table."));
        return true;
    }

    boolean owns(Walker w) {
        return of(w) != null;
    }

    private Diner of(Walker w) {
        for (Diner d : diners) if (d.w == w) return d;
        return null;
    }

    private boolean onShiftAt(Player p, String biz) {
        Biz b = work.shiftBiz(p.getUniqueId());
        return b != null && b.id.equals(biz);
    }

    /** A click on a walker. Returns true if it was one of ours. */
    boolean clicked(Player p, Walker w) {
        Diner d = of(w);
        if (d == null) return false;
        if (d.ambient) {
            w.say(p, r.nextBoolean() ? "Mmm. Best in town, this." : "Leave a body to eat in peace, would you?");
            return true;
        }
        if (!onShiftAt(p, d.biz)) {
            w.say(p, "Are you the waiter? I'm waiting for the staff.");
            return true;
        }
        switch (d.st) {
            case WAITING -> {
                d.st = Stage.FOLLOWING;
                d.worker = p.getUniqueId();
                d.deadline = System.currentTimeMillis() + 60_000;
                w.rename(w.name, "<gray>Following you to a table");
                w.say(p, "Lead the way.");
                p.sendActionBar(SmpPlugin.mm("<green>Now right-click a free seat (a stair or slab chair) to seat them."));
            }
            case FOLLOWING -> p.sendActionBar(SmpPlugin.mm("<yellow>Right-click a free seat to seat them."));
            case WALKING, SEATED -> w.say(p, "Give me a moment to look at the menu.");
            case ORDERED -> serve(p, d);
            case EATING -> w.say(p, "Lovely, thank you.");
            case LEAVING -> w.say(p, "Goodbye now.");
        }
        return true;
    }

    private void seatAt(Diner d, Block seat) {
        d.seat = seat;
        d.st = Stage.WALKING;
        d.since = System.currentTimeMillis();
        d.w.rename(d.w.name, "<gray>Taking a seat");
        d.w.moveTo(seat.getLocation().add(0.5, 1, 0.5), 0.7);
    }

    private void sitDown(Diner d) {
        d.w.sit(d.seat.getLocation().add(0.5, 0.4, 0.5), sitYaw(d.seat));
        d.st = Stage.SEATED;
        d.since = System.currentTimeMillis();
        d.w.rename(d.w.name, d.ambient ? "<gray>Dining" : "<gray>Reading the menu");
    }

    private void order(Diner d, Biz b) {
        List<Dish> menu = MENUS.get(b.kind);
        d.dish = menu.get(r.nextInt(menu.size()));
        d.st = Stage.ORDERED;
        d.orderedAt = System.currentTimeMillis();
        d.deadline = d.orderedAt + cfg().getLong("town.business.dining.order-seconds", 90) * 1000;
        d.w.rename(d.w.name, "<gold>Wants: <white>" + d.dish.label());
        Player p = d.worker == null ? null : Bukkit.getPlayer(d.worker);
        if (p != null) {
            p.sendMessage(SmpPlugin.mm("<gold>" + d.w.name + " <gray>orders <white>" + d.dish.label() + "</white>. Cook it at a stove (smoker, furnace or campfire) and bring it over."));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.5f);
        }
    }

    private void serve(Player p, Diner d) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        String id = dishOf(hand);
        if (id == null) {
            d.w.say(p, "I'm still waiting on my " + d.dish.label().toLowerCase() + ".");
            return;
        }
        Biz b = bz.get(d.biz);
        if (b == null) return;
        if (!id.equals(d.dish.id())) {
            d.wrong++;
            b.rating = Math.max(0, b.rating - 0.03);
            if (d.wrong >= 2) {
                d.w.say(p, "Wrong again! I asked for " + d.dish.label().toLowerCase() + ". I'm off.");
                leave(d, false);
                return;
            }
            d.w.say(p, "That's not what I ordered! I asked for " + d.dish.label().toLowerCase() + ".");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
            return;
        }
        Econ.Good g = b.kind.output;
        if (b.stock.getOrDefault(g, 0.0) < 1) {
            d.w.say(p, "Out of " + g.label.toLowerCase() + "? What kind of place is this?");
            p.sendActionBar(SmpPlugin.mm("<red>The business has no " + g.label.toLowerCase() + " in stock. It makes more each cycle when it's worked and supplied."));
            b.rating = Math.max(0, b.rating - 0.08);
            leave(d, false);
            return;
        }
        hand.setAmount(hand.getAmount() - 1);
        p.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
        double price = b.price;
        b.stock.merge(g, -1.0, Double::sum);
        b.cash += price;
        b.revenue += price;
        b.sold += 1;
        long waited = System.currentTimeMillis() - d.orderedAt;
        double limit = cfg().getLong("town.business.dining.order-seconds", 90) * 1000.0;
        double speed = Math.max(0, 1 - waited / limit);
        double tip = Math.round(price * (0.08 + 0.3 * speed));
        b.rating = Math.min(5, b.rating + 0.02 + 0.04 * speed);
        t.money.give(p, tip);
        d.w.hold(new ItemStack(d.dish.mat()));
        d.st = Stage.EATING;
        d.since = System.currentTimeMillis();
        d.w.rename(d.w.name, "<gray>Eating");
        d.w.say(p, speed > 0.6 ? "That was quick! Thank you." : speed > 0.25 ? "Lovely, thank you." : "Finally! I was about to leave.");
        p.sendMessage(SmpPlugin.mm("<green>Served " + d.dish.label().toLowerCase() + ": " + Money.fmt(price) + " to the business <yellow>+ " + Money.fmt(tip) + " tip"));
        work.credit(p, b, 2);
    }

    private void leave(Diner d, boolean happy) {
        d.w.stand();
        d.w.hold(null);
        if (d.seat != null && happy) dirty.put(d.seat, System.currentTimeMillis());
        if (!happy && !d.ambient) {
            Biz b = bz.get(d.biz);
            if (b != null) b.rating = Math.max(0, b.rating - 0.05);
        }
        d.seat = null;
        d.st = Stage.LEAVING;
        d.since = System.currentTimeMillis();
        d.w.rename(d.w.name, happy ? "<gray>Off home, full" : "<red>Leaving in a huff");
        Biz b = bz.get(d.biz);
        Location a = b == null ? null : bz.anchor(b);
        Location away = a == null ? null : t.awayFrom(d.w.loc(), a, 18);
        if (away != null) d.w.moveTo(away, 0.65);
    }

    // ------------------------------------------------------------------ cooking

    static String dishOf(ItemStack it) {
        if (it == null || !it.hasItemMeta() || DISH == null) return null;
        return it.getItemMeta().getPersistentDataContainer().get(DISH, PersistentDataType.STRING);
    }

    private ItemStack plate(Dish d, Biz b) {
        ItemStack it = new ItemStack(d.mat());
        ItemMeta im = it.getItemMeta();
        im.displayName(SmpPlugin.mm("<!italic><gold>" + d.label()));
        im.lore(List.of(SmpPlugin.mm("<!italic><gray>For a customer at " + b.name), SmpPlugin.mm("<!italic><dark_gray>Hand it to the diner who ordered it.")));
        im.getPersistentDataContainer().set(DISH, PersistentDataType.STRING, d.id());
        it.setItemMeta(im);
        return it;
    }

    private static boolean stove(Material m) {
        return m == Material.SMOKER || m == Material.FURNACE || m == Material.BLAST_FURNACE || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE;
    }

    static final class CookMenu implements InventoryHolder {
        final String biz;
        final Block stove;
        Inventory inv;

        CookMenu(String biz, Block stove) {
            this.biz = biz;
            this.stove = stove;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    /**
     * Runs even when plot protection has already said no: the kitchen and the chairs belong to the business, and a
     * worker on shift there needs them. Only stoves and seats inside their own workplace are handled here.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlock(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Biz b = work.shiftBiz(p.getUniqueId());
        if (!handles(b)) return;
        Block bl = e.getClickedBlock();
        if (!seat(bl) && !stove(bl.getType())) return;
        if (bz.at(bl.getLocation()) != b) return;
        if (work.isTarget(p.getUniqueId(), bl)) return; // the shift's "work the glowing station" task takes it
        // showing a diner to this seat
        if (seat(bl)) {
            Diner following = null;
            for (Diner d : diners) if (!d.ambient && d.st == Stage.FOLLOWING && p.getUniqueId().equals(d.worker)) following = d;
            if (dirty.containsKey(bl)) {
                e.setCancelled(true);
                dirty.remove(bl);
                bl.getWorld().playSound(bl.getLocation(), Sound.ITEM_BUNDLE_REMOVE_ONE, 0.8f, 1f);
                p.sendActionBar(SmpPlugin.mm("<green>Table cleared."));
                work.credit(p, b, 1);
                return;
            }
            if (following == null) return;
            e.setCancelled(true);
            if (taken(bl)) {
                p.sendActionBar(SmpPlugin.mm("<red>Someone's sitting there."));
                return;
            }
            if (!bl.getRelative(0, 1, 0).isPassable() || !bl.getRelative(0, 2, 0).isPassable()) {
                p.sendActionBar(SmpPlugin.mm("<red>There's no room to sit there."));
                return;
            }
            seatAt(following, bl);
            p.sendActionBar(SmpPlugin.mm("<green>Seated. They'll order in a moment."));
            return;
        }
        if (stove(bl.getType())) {
            e.setCancelled(true);
            if (cooking.containsKey(p.getUniqueId())) {
                p.sendActionBar(SmpPlugin.mm("<yellow>You're already cooking something."));
                return;
            }
            CookMenu m = new CookMenu(b.id, bl);
            List<Dish> menu = MENUS.get(b.kind);
            m.inv = Bukkit.createInventory(m, 9, SmpPlugin.mm("What are you cooking?"));
            for (int i = 0; i < menu.size() && i < 9; i++) {
                Dish d = menu.get(i);
                ItemStack it = new ItemStack(d.mat());
                ItemMeta im = it.getItemMeta();
                im.displayName(SmpPlugin.mm("<!italic><gold>" + d.label()));
                int wanted = 0;
                for (Diner x : diners) if (x.st == Stage.ORDERED && b.id.equals(x.biz) && x.dish == d) wanted++;
                im.lore(List.of(SmpPlugin.mm("<!italic>" + (wanted > 0 ? "<green>" + wanted + " waiting for this" : "<dark_gray>Nobody's ordered this"))));
                it.setItemMeta(im);
                m.inv.setItem(i, it);
            }
            p.openInventory(m.inv);
        }
    }

    @EventHandler
    public void onCookClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof CookMenu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        Biz b = bz.get(m.biz);
        if (b == null || !handles(b)) return;
        List<Dish> menu = MENUS.get(b.kind);
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= menu.size()) return;
        int carried = 0;
        for (ItemStack it : p.getInventory().getContents()) if (dishOf(it) != null) carried += it.getAmount();
        if (carried >= 4) {
            p.sendActionBar(SmpPlugin.mm("<red>Your hands are full: serve what you're carrying first."));
            p.closeInventory();
            return;
        }
        Dish d = menu.get(slot);
        long secs = cfg().getLong("town.business.dining.cook-seconds", 4);
        cooking.put(p.getUniqueId(), new Cooking(d, m.stove, System.currentTimeMillis() + secs * 1000, b.id));
        p.closeInventory();
        p.sendActionBar(SmpPlugin.mm("<gold>Cooking " + d.label().toLowerCase() + "... <gray>stay by the stove"));
        p.playSound(m.stove.getLocation(), Sound.BLOCK_FURNACE_FIRE_CRACKLE, 1f, 1f);
    }

    @EventHandler
    public void onCookDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof CookMenu) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        if (dishOf(e.getItem()) != null) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(SmpPlugin.mm("<red>That's for a customer!"));
        }
    }

    // ------------------------------------------------------------------ every second

    void second() {
        tick++;
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Cooking>> it = cooking.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            Cooking c = en.getValue();
            Biz b = bz.get(c.biz());
            if (p == null || b == null || work.shiftBiz(p.getUniqueId()) != b) {
                it.remove();
                continue;
            }
            Location s = c.stove().getLocation().add(0.5, 0.5, 0.5);
            if (s.getWorld() != p.getWorld() || s.distanceSquared(p.getLocation()) > 25) {
                p.sendActionBar(SmpPlugin.mm("<red>You walked away from the stove and the " + c.dish().label().toLowerCase() + " burnt."));
                it.remove();
                continue;
            }
            s.getWorld().spawnParticle(Particle.SMOKE, s.clone().add(0, 0.6, 0), 4, 0.15, 0.1, 0.15, 0.01);
            if (now < c.until()) {
                long left = (c.until() - now + 999) / 1000;
                p.sendActionBar(SmpPlugin.mm("<gold>Cooking " + c.dish().label().toLowerCase() + "... <white>" + left + "s"));
                continue;
            }
            it.remove();
            for (ItemStack rest : p.getInventory().addItem(plate(c.dish(), b)).values()) p.getWorld().dropItem(p.getLocation(), rest);
            p.sendActionBar(SmpPlugin.mm("<green>" + c.dish().label() + " is ready. Take it to the table!"));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.6f);
        }
        dirty.entrySet().removeIf(en -> now - en.getValue() > 300_000);
        for (Diner d : new ArrayList<>(diners)) {
            if (!d.w.valid()) {
                d.w.stand();
                diners.remove(d);
                continue;
            }
            Biz b = bz.get(d.biz);
            if (b == null || !b.open()) {
                remove(d);
                continue;
            }
            Player p = d.worker == null ? null : Bukkit.getPlayer(d.worker);
            switch (d.st) {
                case WAITING -> {
                    if (now > d.deadline) {
                        if (p != null) d.w.say(p, "Is nobody seating anyone here? I'm going.");
                        leave(d, false);
                    } else if (p != null && !d.w.moving() && d.w.dist(p.getLocation()) > 3) d.w.moveTo(p, 0.8);
                    if (p != null && tick % 2 == 0) p.sendActionBar(SmpPlugin.mm("<gold>A diner wants a table: <white>right-click " + d.w.name + " <gray>(" + (d.deadline - now) / 1000 + "s)"));
                }
                case FOLLOWING -> {
                    if (p == null || now > d.deadline || work.shiftBiz(d.worker) != b) {
                        leave(d, false);
                        continue;
                    }
                    if (d.w.dist(p.getLocation()) > 2.5 && (!d.w.moving() || tick % 2 == 0)) d.w.moveTo(p, 0.85);
                }
                case WALKING -> {
                    Location target = d.seat.getLocation().add(0.5, 1, 0.5);
                    if (d.w.dist(target) < 1.6 || now - d.since > 12_000) sitDown(d);
                    else if (!d.w.moving()) d.w.moveTo(target, 0.7);
                }
                case SEATED -> {
                    if (now - d.since > (d.ambient ? 1500 : 3500)) {
                        if (d.ambient) {
                            d.st = Stage.EATING;
                            d.since = now;
                            List<Dish> menu = MENUS.get(b.kind);
                            d.w.hold(new ItemStack(menu.get(r.nextInt(menu.size())).mat()));
                            d.deadline = now + 30_000 + r.nextInt(40_000);
                        } else order(d, b);
                    }
                }
                case ORDERED -> {
                    if (now > d.deadline) {
                        if (p != null) d.w.say(p, "I've waited long enough. Never coming back here!");
                        leave(d, false);
                    } else if (p != null && tick % 3 == 0 && work.shiftBiz(d.worker) == b) {
                        p.sendActionBar(SmpPlugin.mm("<gold>" + d.w.name + " wants <white>" + d.dish.label() + " <gray>(" + (d.deadline - now) / 1000 + "s)"));
                    }
                }
                case EATING -> {
                    Location l = d.w.loc().add(0, 1.4, 0);
                    if (tick % 2 == 0) {
                        ItemStack food = d.w.held();
                        if (food != null && !food.getType().isAir()) l.getWorld().spawnParticle(Particle.ITEM, l, 5, 0.15, 0.1, 0.15, 0.03, food);
                        l.getWorld().playSound(l, Sound.ENTITY_GENERIC_EAT, 0.4f, 1f);
                    }
                    long dur = d.ambient ? d.deadline - d.since : 8000;
                    if (now - d.since > dur) leave(d, true);
                }
                case LEAVING -> {
                    if (now - d.since > 15_000 || (!d.w.moving() && now - d.since > 3000)) remove(d);
                }
            }
        }
        if (tick % 6 == 0) ambient(now);
    }

    private void remove(Diner d) {
        d.w.stand();
        t.unregister(d.w, true);
        diners.remove(d);
    }

    /** NPC diners at town-run eateries (and player ones nobody's working), so the tables aren't always empty. */
    private void ambient(long now) {
        World w = t.world();
        if (w == null || !t.canSpawnWalkers(w)) return;
        int max = cfg().getInt("town.business.dining.ambient-per-place", 3);
        for (Biz b : bz.all()) {
            if (!handles(b) || !b.open() || b.rival) continue;
            Location a = bz.anchor(b);
            if (a == null || t.nearestPlayer(a, 32) == null) continue;
            int have = 0;
            for (Diner d : diners) if (d.ambient && b.id.equals(d.biz)) have++;
            if (have >= max || r.nextInt(3) != 0) continue;
            Block seat = freeSeat(b);
            if (seat == null) continue;
            Player near = t.nearestPlayer(a, 32);
            Location from = t.spawnSpot(near, 6, 14);
            if (from == null) from = a;
            Walker x = Walker.spawn(t.key, from, Walker.Role.CUSTOMER, "<white>" + name(), "<gray>Off to eat at " + b.name, 0.22, 12, Map.of());
            x.gang = b.id;
            t.register(x);
            Diner d = new Diner();
            d.w = x;
            d.biz = b.id;
            d.ambient = true;
            d.since = now;
            diners.add(d);
            seatAt(d, seat);
        }
    }

    // ------------------------------------------------------------------ shifts ending

    void clockOut(UUID worker) {
        cooking.remove(worker);
        for (Diner d : new ArrayList<>(diners)) {
            if (d.ambient || !worker.equals(d.worker)) continue;
            if (d.st == Stage.WAITING || d.st == Stage.FOLLOWING || d.st == Stage.ORDERED || d.st == Stage.SEATED || d.st == Stage.WALKING) leave(d, false);
        }
        Player p = Bukkit.getPlayer(worker);
        if (p != null) {
            ItemStack[] c = p.getInventory().getContents();
            int n = 0;
            for (int i = 0; i < c.length; i++) {
                if (dishOf(c[i]) != null) {
                    n += c[i].getAmount();
                    c[i] = null;
                }
            }
            if (n > 0) {
                p.getInventory().setContents(c);
                p.sendMessage(SmpPlugin.mm("<gray>You left the dishes you were carrying in the kitchen."));
            }
        }
    }

    void stop() {
        for (Diner d : new ArrayList<>(diners)) {
            d.w.stand();
            t.unregister(d.w, false);
        }
        diners.clear();
    }

    private String name() {
        String[] n = {"Aldith", "Bartholomew", "Constance", "Drogo", "Elfreda", "Gerard", "Hilda", "Ingram", "Joan", "Lambert", "Margery", "Nigel", "Osanna",
                "Piers", "Rosamund", "Stephen", "Thurstan", "Ursula", "Warin"};
        return n[r.nextInt(n.length)];
    }

    /** For the worker's /job info. */
    Component status(UUID worker) {
        int n = countFor(worker);
        return SmpPlugin.mm(n == 0 ? "" : "<gray>" + n + " diner" + (n == 1 ? "" : "s") + " in your care.");
    }
}
