package net.moddedrealms.smp.town;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.moddedrealms.smp.SmpPlugin;
import net.moddedrealms.smp.town.Businesses.Biz;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Jobs. Players work for any business (town-run, a rival's, or another player's): apply with /job apply (town
 * businesses take anyone; owners accept or send offers), clock in at the workplace, and do the tasks that come up:
 * work the highlighted station, or run a delivery to another business. Each task pays the wage from the business's
 * cash and makes it more productive that cycle. NPC staff and NPC customers walk around the businesses too.
 */
public final class Work implements Listener {
    private static final Set<Material> STATIONS = EnumSet.of(Material.COMPOSTER, Material.LOOM, Material.STONECUTTER, Material.SMOKER, Material.BARREL,
            Material.CRAFTING_TABLE, Material.FURNACE, Material.BLAST_FURNACE, Material.BREWING_STAND, Material.CARTOGRAPHY_TABLE, Material.LECTERN,
            Material.FLETCHING_TABLE, Material.SMITHING_TABLE, Material.GRINDSTONE, Material.CAULDRON, Material.ANVIL, Material.HAY_BLOCK, Material.CHEST,
            Material.BOOKSHELF, Material.CHISELED_BOOKSHELF, Material.WHITE_BED, Material.RED_BED, Material.LIGHT_BLUE_BED, Material.WHEAT, Material.OAK_LOG);

    private static final class Shift {
        String biz;
        Location target;
        Biz deliverTo;
        Walker customer;
        long deadline, nextTask;
        int done;
    }

    private final TownManager t;
    private final Businesses bz;
    private final Map<UUID, Shift> shifts = new HashMap<>();
    private final Map<UUID, String> offers = new HashMap<>();
    private final Random r = new Random();
    private int tick;
    final Dining dining;

    Work(TownManager t, Businesses bz) {
        this.t = t;
        this.bz = bz;
        this.dining = new Dining(t, bz, this);
    }

    /** Is this block the worker's current station task? */
    boolean isTarget(UUID u, Block b) {
        Shift s = shifts.get(u);
        return s != null && s.target != null && s.target.getBlock().equals(b);
    }

    /** What the worker's current station task is ("composter"), or null. */
    String targetName(UUID u) {
        Shift s = shifts.get(u);
        return s == null || s.target == null ? null : s.target.getBlock().getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    /** The business this player is clocked in at, or null. */
    Biz shiftBiz(UUID u) {
        Shift s = shifts.get(u);
        return s == null ? null : bz.get(s.biz);
    }

    private boolean isShiftCustomer(Walker w) {
        for (Shift s : shifts.values()) if (s.customer == w) return true;
        return false;
    }

    /** Pay a worker for a job done outside the task queue (seating, serving and clearing at an eatery). */
    void credit(Player p, Biz b, int mult) {
        Shift s = shifts.get(p.getUniqueId());
        if (s == null || !b.id.equals(s.biz)) return;
        earn(p, s, b, mult);
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    private double wage(Biz b) {
        return b.playerOwned() ? b.wage : cfg().getDouble("town.business.town-wage", 25);
    }

    // ------------------------------------------------------------------ hiring

    boolean list(Player p) {
        p.sendMessage(SmpPlugin.mm("<gold><bold>Jobs</bold> <gray>(pay per task · click to apply)"));
        int n = 0;
        for (Biz b : bz.all()) {
            if (b.rival || !b.open()) continue;
            if (b.playerOwned() && !b.jobsOpen) continue;
            Location l = bz.anchor(b);
            String where = l == null ? "" : " <dark_gray>(" + l.getBlockX() + ", " + l.getBlockZ() + ")";
            p.sendMessage(Component.text().append(SmpPlugin.mm(" <white>" + b.name + " <gray>" + b.kind.label + " · <green>" + Money.fmt(wage(b)) + "/task" + where + " "))
                    .append(SmpPlugin.mm("<yellow>[Apply]").clickEvent(ClickEvent.runCommand("/job apply " + b.id))).build());
            n++;
        }
        if (n == 0) p.sendMessage(SmpPlugin.mm("<gray>Nobody is hiring right now."));
        return true;
    }

    boolean command(Player p, String[] a) {
        String sub = a.length == 0 ? "info" : a[0].toLowerCase();
        Biz mine = bz.employerOf(p.getUniqueId());
        switch (sub) {
            case "apply" -> {
                Biz b = a.length > 1 ? bz.get(a[1]) : null;
                if (b == null || b.rival) return say(p, "<yellow>/job apply <business> <gray>(see /jobs)");
                if (p.getUniqueId().equals(b.owner)) return say(p, "<gray>That's your own business. Owners work it too: /job clockin works for you there.");
                if (!b.playerOwned()) {
                    hire(b, p);
                    return true;
                }
                if (!b.jobsOpen) return say(p, "<red>" + b.name + " isn't hiring.");
                b.applicants.add(p.getUniqueId());
                say(p, "<gray>Application sent to " + b.name + ".");
                Player o = Bukkit.getPlayer(b.owner);
                if (o != null) o.sendMessage(Component.text().append(SmpPlugin.mm("<gold>[" + b.name + "] <white>" + p.getName() + " wants a job. "))
                        .append(SmpPlugin.mm("<green>[Hire]").clickEvent(ClickEvent.runCommand("/business accept " + p.getName()))).build());
            }
            case "accept" -> {
                String id = offers.remove(p.getUniqueId());
                Biz b = id == null ? null : bz.get(id);
                if (b == null) return say(p, "<gray>You have no job offer.");
                hire(b, p);
            }
            case "quit" -> {
                if (mine == null) return say(p, "<gray>You don't have a job.");
                mine.workers.remove(p.getUniqueId());
                clockOut(p.getUniqueId());
                bz.save();
                say(p, "<gray>You quit your job at " + mine.name + ".");
            }
            case "clockin" -> {
                Biz b = mine;
                Biz here = bz.at(p.getLocation());
                if (here != null && p.getUniqueId().equals(here.owner)) b = here;
                if (b == null) return say(p, "<gray>You need a job first. <white>/jobs");
                if (!b.open()) return say(p, "<red>" + b.name + " is closed (" + b.closedWhy + ").");
                if (here != b) return say(p, "<gray>Go to " + b.name + " to clock in.");
                Shift s = new Shift();
                s.biz = b.id;
                s.nextTask = System.currentTimeMillis() + 3000;
                shifts.put(p.getUniqueId(), s);
                say(p, "<green>Clocked in at " + b.name + ". <gray>Tasks will pop up: work the glowing station, or run a delivery. <white>/job clockout</white> when you're done.");
            }
            case "clockout" -> {
                Shift s = shifts.get(p.getUniqueId());
                if (s == null) return say(p, "<gray>You're not clocked in.");
                clockOut(p.getUniqueId());
                say(p, "<gray>Clocked out after " + s.done + " task" + (s.done == 1 ? "" : "s") + ". Good work.");
            }
            default -> {
                if (mine == null) return say(p, "<gray>You don't have a job. <white>/jobs</white> lists who's hiring.");
                Shift s = shifts.get(p.getUniqueId());
                say(p, "<gold>You work at " + mine.name + " <gray>(" + mine.kind.label + ") for " + Money.fmt(wage(mine)) + " a task. "
                        + (s == null ? "<white>/job clockin</white> there to start." : "<green>Clocked in, " + s.done + " tasks done."));
            }
        }
        return true;
    }

    void hire(Biz b, Player p) {
        Biz old = bz.employerOf(p.getUniqueId());
        if (old != null && old != b) {
            old.workers.remove(p.getUniqueId());
            clockOut(p.getUniqueId());
        }
        b.workers.add(p.getUniqueId());
        b.applicants.remove(p.getUniqueId());
        bz.save();
        p.sendMessage(SmpPlugin.mm("<green>You're hired at " + b.name + "! <gray>Go there and <white>/job clockin</white>. " + Money.fmt(wage(b)) + " per task."));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.8f, 1.2f);
    }

    void offer(Biz b, Player p) {
        offers.put(p.getUniqueId(), b.id);
        p.sendMessage(Component.text().append(SmpPlugin.mm("<gold>[" + b.name + "] <white>offers you a job at " + Money.fmt(b.wage) + " per task. "))
                .append(SmpPlugin.mm("<green>[Accept]").clickEvent(ClickEvent.runCommand("/job accept"))).build());
    }

    void clockOut(UUID u) {
        Shift s = shifts.remove(u);
        if (s != null && s.customer != null) t.unregister(s.customer, false);
        if (s != null) dining.clockOut(u);
    }

    private static boolean say(Player p, String m) {
        p.sendMessage(SmpPlugin.mm(m));
        return true;
    }

    // ------------------------------------------------------------------ shifts

    private static String workerSkin(Biz b) {
        return switch (b.kind) {
            case FARM -> "farmer";
            case BAKERY, RESTAURANT -> "chef";
            case HOTEL -> "bellhop";
            case TAVERN, SPEAKEASY -> "bartender";
            default -> net.moddedrealms.smp.Pack.pick("worker");
        };
    }

    private boolean atWork(Player p, Biz b) {
        Biz here = bz.at(p.getLocation());
        return here == b;
    }

    private List<Block> stations(Biz b) {
        List<Block> out = new ArrayList<>();
        Location c = bz.anchor(b);
        if (c == null) return out;
        Plots.Plot p = b.plot == null ? null : t.plots().get(b.plot);
        int x0, x1, z0, z1, y0, y1;
        if (p != null) {
            x0 = p.x0;
            x1 = p.x1;
            z0 = p.z0;
            z1 = p.z1;
            y0 = p.floor;
            y1 = p.floor + 12;
        } else {
            x0 = c.getBlockX() - 10;
            x1 = c.getBlockX() + 10;
            z0 = c.getBlockZ() - 10;
            z1 = c.getBlockZ() + 10;
            y0 = c.getBlockY() - 2;
            y1 = c.getBlockY() + 10;
        }
        World w = c.getWorld();
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) for (int y = y0; y <= y1; y++) {
            Block bl = w.getBlockAt(x, y, z);
            // only what counts as this business's ground: a worker standing there is "at work", and isn't told it's not theirs
            if (!(STATIONS.contains(bl.getType()) || bl.getType() == b.kind.station) || bz.at(bl.getLocation()) != b) continue;
            // a business without a plot is a circle round its anchor: keep stations well inside, so the worker standing at one is still there
            if (p == null && bl.getLocation().add(0.5, 0.5, 0.5).distance(c) > 9.5) continue;
            out.add(bl);
        }
        return out;
    }

    void second() {
        tick++;
        long now = System.currentTimeMillis();
        for (UUID u : new ArrayList<>(shifts.keySet())) {
            Player p = Bukkit.getPlayer(u);
            Shift s = shifts.get(u);
            Biz b = bz.get(s.biz);
            if (p == null || b == null || !b.open() || !(b.workers.contains(u) || u.equals(b.owner)) || t.data(p).jailed()) {
                clockOut(u);
                continue;
            }
            if (p.getIdleDuration().toSeconds() > cfg().getLong("town.business.afk-clockout-seconds", 180)) {
                clockOut(u);
                p.sendMessage(SmpPlugin.mm("<gray>You dozed off on the job, so you've been clocked out of " + b.name + ". <white>/job clockin</white> when you're back."));
                continue;
            }
            if (s.deliverTo != null) {
                Location dest = bz.anchor(s.deliverTo);
                if (dest == null || now > s.deadline) {
                    p.sendMessage(SmpPlugin.mm("<red>The delivery to " + (s.deliverTo == null ? "?" : s.deliverTo.name) + " ran out of time."));
                    s.deliverTo = null;
                    s.nextTask = now + 5000;
                    continue;
                }
                if (dest.getWorld() == p.getWorld() && dest.distance(p.getLocation()) < 8) {
                    complete(p, s, b, 3);
                    s.deliverTo = null;
                    continue;
                }
                if (tick % 2 == 0) p.sendActionBar(SmpPlugin.mm("<gold>Delivery to " + s.deliverTo.name + ": <white>" + (dest.getWorld() == p.getWorld()
                        ? (int) dest.distance(p.getLocation()) + "m " + PoliceForce.direction(p.getLocation(), dest) : "far away") + " <gray>(" + (s.deadline - now) / 1000 + "s)"));
                continue;
            }
            if (!atWork(p, b)) {
                if (tick % 5 == 0) p.sendActionBar(SmpPlugin.mm("<yellow>You're clocked in at " + b.name + " but you're not there. Head back or /job clockout."));
                if (s.target != null && now > s.deadline) s.target = null;
                continue;
            }
            if (s.customer != null) {
                if (!s.customer.valid() && now <= s.deadline) {
                    // the customer couldn't get here (despawned): no harm done, another task comes along
                    s.customer = null;
                    s.nextTask = now + 2000;
                    continue;
                }
                if (now > s.deadline) {
                    if (s.customer.valid()) {
                        s.customer.say(p, "Forget it, I'll go elsewhere.");
                        t.unregister(s.customer, false);
                    }
                    s.customer = null;
                    b.rating = Math.max(0, b.rating - 0.05);
                    p.sendActionBar(SmpPlugin.mm("<red>The customer gave up waiting. <gray>(-rating)"));
                    s.nextTask = now + 4000;
                    continue;
                }
                if (!s.customer.moving() && s.customer.dist(p.getLocation()) > 2.5) s.customer.moveTo(p, 0.9);
                if (tick % 2 == 0) p.sendActionBar(SmpPlugin.mm("<gold>A customer is waiting: <white>right-click " + s.customer.name + "<white> to serve them <gray>(" + (s.deadline - now) / 1000 + "s)"));
                continue;
            }
            if (s.target != null) {
                if (now > s.deadline) {
                    p.sendActionBar(SmpPlugin.mm("<gray>Too slow, that one's done by someone else."));
                    s.target = null;
                    s.nextTask = now + 4000;
                    continue;
                }
                p.spawnParticle(Particle.HAPPY_VILLAGER, s.target.clone().add(0.5, 1.1, 0.5), 6, 0.3, 0.2, 0.3);
                p.spawnParticle(Particle.DUST, s.target.clone().add(0.5, 0.5, 0.5), 8, 0.4, 0.4, 0.4, new Particle.DustOptions(Color.LIME, 1.3f));
                continue;
            }
            if (now < s.nextTask) continue;
            // New task: diners at eateries, customers to serve at shops, a delivery now and then, otherwise a station.
            if (Dining.handles(b)) {
                if (dining.spawnFor(p, b)) {
                    s.nextTask = now + 15_000 + r.nextInt(10_000);
                    continue;
                }
            } else if (b.kind.output.consumer && r.nextInt(5) < 2 && t.canSpawnWalkers(p.getWorld())) {
                Location at = t.spawnSpot(p, 3, 7);
                if (at != null && bz.at(at) == b) {
                    Walker c = Walker.spawn(t.key, at, Walker.Role.CUSTOMER, "<white>" + pickName(), "<gray>Waiting to be served", 0.22, 12, outfit(null));
                    c.gang = b.id;
                    t.register(c);
                    c.moveTo(p, 0.9);
                    s.customer = c;
                    s.deadline = now + 25_000;
                    p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 0.7f, 1.4f);
                    continue;
                }
            }
            if (r.nextInt(5) == 0 && !Dining.handles(b)) { // waiters stay in the dining room
                Biz dest = deliveryTarget(b);
                if (dest != null) {
                    s.deliverTo = dest;
                    s.deadline = now + cfg().getLong("town.business.delivery-seconds", 300) * 1000L;
                    p.sendMessage(SmpPlugin.mm("<gold>Delivery job: <white>take an order to " + dest.name + "</white>. <gray>Pays " + Money.fmt(wage(b) * 3) + "."));
                    continue;
                }
            }
            List<Block> st = stations(b);
            if (st.isEmpty()) {
                if (tick % 10 == 0) p.sendActionBar(SmpPlugin.mm("<yellow>There's nothing to work with here. The owner needs to put job blocks (a loom, barrel, smoker...) inside."));
                continue;
            }
            Block pick = st.get(r.nextInt(st.size()));
            s.target = pick.getLocation();
            s.deadline = now + 20_000;
            p.sendActionBar(SmpPlugin.mm("<green>Task: work the glowing " + pick.getType().name().toLowerCase().replace('_', ' ') + "! <gray>(right-click or hit it)"));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.6f);
        }
        if (tick % 4 == 0) activity();
        dining.second();
    }

    /** A worker right-clicked a customer: if it's theirs, sell to them now (a sale and a tip). Returns true if handled. */
    boolean serve(Player p, Walker w) {
        if (dining.clicked(p, w)) return true;
        Shift s = shifts.get(p.getUniqueId());
        if (s == null || s.customer != w) return false;
        Biz b = bz.get(s.biz);
        s.customer = null;
        if (b == null) {
            t.unregister(w, false);
            return true;
        }
        Econ.Good g = b.kind.output;
        double have = Math.floor(b.stock.getOrDefault(g, 0.0));
        if (have < 1) {
            w.say(p, "Nothing left? Shame.");
            b.rating = Math.max(0, b.rating - 0.1);
            p.sendActionBar(SmpPlugin.mm("<red>Out of " + g.label.toLowerCase() + "! <gray>The business makes more each cycle (when it's worked)."));
            t.unregister(w, false);
            s.nextTask = System.currentTimeMillis() + 4000;
            return true;
        }
        if (b.price > g.ref * 1.6) {
            w.say(p, "How much?! That's daylight robbery.");
            b.rating = Math.max(0, b.rating - 0.1);
            p.sendActionBar(SmpPlugin.mm("<red>Too dear: customers won't pay more than " + Money.fmt(g.ref * 1.6) + " for " + g.label.toLowerCase() + "."));
            t.unregister(w, false);
            s.nextTask = System.currentTimeMillis() + 4000;
            return true;
        }
        int n = (int) Math.min(have, 1 + r.nextInt(2));
        double sale = n * b.price;
        double tip = Math.round(sale * (0.1 + r.nextDouble() * 0.15));
        b.stock.merge(g, (double) -n, Double::sum);
        b.cash += sale;
        b.revenue += sale;
        b.sold += n;
        b.rating = Math.min(5, b.rating + 0.03);
        t.money.give(p, tip);
        w.say(p, r.nextBoolean() ? "Lovely, thank you!" : "Just what I was after.");
        p.sendMessage(SmpPlugin.mm("<green>Served: " + n + " " + g.label.toLowerCase() + " for " + Money.fmt(sale) + " <gray>(to the business) <yellow>+ " + Money.fmt(tip) + " tip"));
        complete(p, s, b, 1);
        t.unregister(w, false);
        return true;
    }

    private Biz deliveryTarget(Biz b) {
        List<Biz> options = new ArrayList<>();
        for (Biz x : bz.all()) {
            if (x == b || x.rival || bz.anchor(x) == null) continue;
            boolean supplier = b.kind.inputs.containsKey(x.kind.output);
            boolean customer = x.kind.inputs.containsKey(b.kind.output);
            if (supplier || customer) options.add(x);
        }
        if (options.isEmpty()) for (Biz x : bz.all()) if (x != b && !x.rival && bz.anchor(x) != null) options.add(x);
        return options.isEmpty() ? null : options.get(r.nextInt(options.size()));
    }

    private void complete(Player p, Shift s, Biz b, int mult) {
        double pay = earn(p, s, b, mult);
        s.target = null;
        s.nextTask = System.currentTimeMillis() + 6000 + r.nextInt(8000);
        p.sendActionBar(SmpPlugin.mm("<green>Task done!" + (pay > 0 ? " <yellow>+" + Money.fmt(pay) : "") + " <gray>(" + s.done + " this shift)"));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.3f);
    }

    /** The wage for a job and the business's productivity for it. Returns what the worker was paid. */
    private double earn(Player p, Shift s, Biz b, int mult) {
        double pay = wage(b) * mult;
        if (b.playerOwned() && !p.getUniqueId().equals(b.owner)) {
            if (b.cash < pay) {
                p.sendMessage(SmpPlugin.mm("<red>" + b.name + " can't afford to pay you right now. Talk to the owner."));
                pay = 0;
            } else b.cash -= pay;
        } else if (!b.playerOwned()) {
            b.cash -= pay;
        } else {
            pay = 0; // owners don't pay themselves
        }
        if (pay > 0) t.money.give(p, pay);
        b.tasks += mult;
        s.done++;
        if (s.done % 10 == 0) t.addRep(p, 1);
        return pay;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onWork(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getHand() != EquipmentSlot.HAND) return;
        Shift s = shifts.get(e.getPlayer().getUniqueId());
        if (s == null || s.target == null) return;
        if (!e.getClickedBlock().getLocation().equals(s.target)) return;
        e.setCancelled(true);
        Biz b = bz.get(s.biz);
        if (b != null) complete(e.getPlayer(), s, b, 1);
    }

    /**
     * Hitting or breaking the glowing station does the task too: a farmhand harvests by breaking the wheat, a
     * sawyer swings at the log. The block itself stays (it's the business's fitting), and the town's plot rules,
     * which forbid breaking what isn't yours, never get to say no to the task.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onHitStation(PlayerInteractEvent e) {
        if (e.getAction() != Action.LEFT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (doTarget(e.getPlayer(), e.getClickedBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBreakStation(org.bukkit.event.block.BlockBreakEvent e) {
        if (doTarget(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }

    private boolean doTarget(Player p, Block bl) {
        Shift s = shifts.get(p.getUniqueId());
        if (s == null || s.target == null || !bl.getLocation().equals(s.target)) return false;
        Biz b = bz.get(s.biz);
        if (b != null) complete(p, s, b, 1);
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        clockOut(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ NPC staff and customers

    private void activity() {
        World w = t.world();
        if (w == null || !t.canSpawnWalkers(w)) return;
        int staffNow = 0, custNow = 0;
        for (Walker x : t.walkers()) {
            if (x.role == Walker.Role.WORKER) staffNow++;
            if (x.role == Walker.Role.CUSTOMER && !"market".equals(x.gang) && !dining.owns(x)) custNow++;
        }
        long now = System.currentTimeMillis();
        // staff: up to 2 per business with a plot, near players
        for (Biz b : bz.all()) {
            if (b.npcStaff <= 0 || b.plot == null || staffNow >= cfg().getInt("town.business.max-staff-walkers", 16)) continue;
            Plots.Plot p = t.plots().get(b.plot);
            if (p == null) continue;
            World pw = Bukkit.getWorld(p.world);
            Location c = p.centre(pw);
            if (t.nearestPlayer(c, 48) == null) continue;
            int have = 0;
            for (Walker x : t.walkers(Walker.Role.WORKER)) if (b.id.equals(x.gang)) have++;
            if (have >= Math.min(2, b.npcStaff)) continue;
            Location spot = floor(pw, p);
            if (spot == null) continue;
            Walker wk = Walker.spawn(t.key, spot, Walker.Role.WORKER, "<white>" + pickName(), "<gray>Works at " + b.name, 0.2, 12, outfit(b), workerSkin(b));
            wk.gang = b.id;
            t.register(wk);
            staffNow++;
        }
        // customers: walk from the street to an open shop and back
        if (custNow < cfg().getInt("town.business.max-customers", 8)) {
            List<Biz> shops = new ArrayList<>();
            for (Biz b : bz.all()) if (b.open() && b.kind.output.consumer && bz.anchor(b) != null && t.nearestPlayer(bz.anchor(b), 40) != null) shops.add(b);
            if (!shops.isEmpty()) {
                Biz shop = shops.get(r.nextInt(shops.size()));
                Location a = bz.anchor(shop);
                Player near = t.nearestPlayer(a, 40);
                Location from = t.spawnSpot(near, 14, 24);
                if (from != null) {
                    Walker c = Walker.spawn(t.key, from, Walker.Role.CUSTOMER, "<white>" + pickName(), null, 0.22, 12, outfit(null));
                    c.gang = shop.id;
                    c.goal = a;
                    c.modeSince = now;
                    t.register(c);
                }
            }
        }
        // move them
        for (Walker x : new ArrayList<>(t.walkers())) {
            if (x.role == Walker.Role.WORKER) {
                Biz b = bz.get(x.gang);
                Plots.Plot p = b == null || b.plot == null ? null : t.plots().get(b.plot);
                if (p == null || t.nearestPlayer(x.loc(), 64) == null) {
                    t.unregister(x, false);
                    continue;
                }
                if (!x.moving() && now > x.nextWander) {
                    Location g = floor(x.world(), p);
                    if (g != null) x.moveTo(g, 0.5);
                    x.nextWander = now + 4000 + r.nextInt(6000);
                    if (r.nextInt(3) == 0) x.swing();
                }
            } else if (x.role == Walker.Role.CUSTOMER) {
                if ("market".equals(x.gang) || dining.owns(x) || isShiftCustomer(x)) continue; // the market hall and the dining room run their own
                Biz b = bz.get(x.gang);
                if (b == null || now - x.modeSince > 90_000 || t.nearestPlayer(x.loc(), 64) == null) {
                    t.unregister(x, false);
                    continue;
                }
                Location a = bz.anchor(b);
                if (x.mode == 0) {
                    if (a == null) continue;
                    if (x.dist(a) < 3) {
                        x.mode = 1;
                        x.lastAttack = now;
                    } else if (!x.moving()) x.moveTo(a, 0.6);
                } else if (x.mode == 1 && now - x.lastAttack > 8000) {
                    x.mode = 2;
                    Location away = t.awayFrom(x.loc(), a, 20);
                    if (away != null) x.moveTo(away, 0.6);
                } else if (x.mode == 2 && !x.moving()) {
                    t.unregister(x, true);
                }
            }
        }
    }

    /** A free floor spot inside a business: on its plot, or near its door for a town building. */
    Location floorIn(Biz b) {
        if (b.plot != null) {
            Plots.Plot p = t.plots().get(b.plot);
            World w = p == null ? null : Bukkit.getWorld(p.world);
            return w == null ? null : floor(w, p);
        }
        Location a = bz.anchor(b);
        return a == null ? null : t.surface(a.getWorld(), a.getBlockX() + r.nextInt(7) - 3, a.getBlockZ() + r.nextInt(7) - 3, a.getY(), 4);
    }

    private Location floor(World w, Plots.Plot p) {
        for (int i = 0; i < 8; i++) {
            int x = p.x0 + 1 + r.nextInt(Math.max(1, p.x1 - p.x0 - 1)), z = p.z0 + 1 + r.nextInt(Math.max(1, p.z1 - p.z0 - 1));
            for (int y = p.floor + 1; y <= p.floor + 9; y++) {
                Block feet = w.getBlockAt(x, y, z);
                if (feet.isPassable() && feet.getRelative(0, 1, 0).isPassable() && !feet.getRelative(0, -1, 0).isPassable() && !feet.isLiquid()) {
                    return feet.getLocation().add(0.5, 0, 0.5);
                }
            }
        }
        return null;
    }

    private String pickName() {
        String[] n = {"Alma", "Bert", "Cass", "Dora", "Eli", "Fern", "Gil", "Hana", "Ivo", "June", "Kip", "Lou", "Mae", "Nils", "Opal", "Pete", "Rue", "Sam", "Tilly", "Wes"};
        return n[r.nextInt(n.length)];
    }

    private Map<EquipmentSlot, ItemStack> outfit(Biz b) {
        Map<EquipmentSlot, ItemStack> gear = new EnumMap<>(EquipmentSlot.class);
        String[] cols = {"#8B3A3A", "#3A5F8B", "#6B8B3A", "#8B6B3A", "#5A3A8B", "#2F6F6F"};
        gear.put(EquipmentSlot.CHEST, Walker.dyed(Material.LEATHER_CHESTPLATE, b == null ? cols[r.nextInt(cols.length)] : "#FFFFFF"));
        gear.put(EquipmentSlot.LEGS, Walker.dyed(Material.LEATHER_LEGGINGS, cols[r.nextInt(cols.length)]));
        if (b != null) gear.put(EquipmentSlot.HAND, new ItemStack(b.kind.output.icon.isItem() ? b.kind.output.icon : Material.PAPER));
        return gear;
    }
}
