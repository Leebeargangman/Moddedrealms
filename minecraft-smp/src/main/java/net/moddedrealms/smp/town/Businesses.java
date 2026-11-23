package net.moddedrealms.smp.town;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.moddedrealms.smp.SmpPlugin;
import net.moddedrealms.smp.town.Econ.Good;
import net.moddedrealms.smp.town.Econ.Kind;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Businesses and the town economy. Every cycle (5 minutes) each open business buys its inputs from the cheapest
 * supplier (or imports them at double price), makes what it can (more with staff and workers' tasks), and sells to
 * the townspeople, who split their spending by price, rating and stock. Town-run businesses set their prices to
 * compete; a player who dominates a market attracts rival companies and sabotage. Illegal businesses get raided,
 * fugitives' businesses are closed, and filthy restaurants fail inspection.
 */
public final class Businesses implements Listener, TabExecutor {
    public static final class Biz {
        public String id, name, plot, town = Meadowbrook.ID;
        public Kind kind;
        public UUID owner; // null = town-run or rival company
        public boolean rival;
        public int level = 1, npcStaff, security;
        public boolean insured, jobsOpen;
        public double cash, rating = 3.0, damage, wage = 30;
        public final Map<Good, Double> stock = new EnumMap<>(Good.class);
        public double price;
        public long closedUntil;
        public String closedWhy = "";
        public final Set<UUID> workers = new HashSet<>();
        public final Set<UUID> applicants = new HashSet<>();
        // last cycle
        public double revenue, costs, made, sold, imported;
        public int tasks;
        public String anchor; // world,x,y,z where there's no plot (town buildings)

        boolean open() {
            return System.currentTimeMillis() >= closedUntil;
        }

        boolean playerOwned() {
            return owner != null;
        }
    }

    private final TownManager t;
    private final File file;
    private final Map<String, Biz> all = new LinkedHashMap<>();
    private final Map<String, Integer> dominance = new HashMap<>();
    private final Random r = new Random();
    private long nextCycle;
    final Work work;

    Businesses(TownManager t) {
        this.t = t;
        this.file = new File(t.plugin().getDataFolder(), "businesses.yml");
        this.work = new Work(t, this);
        load();
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    // ------------------------------------------------------------------ data

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextCycle = y.getLong("next-cycle");
        ConfigurationSection bs = y.getConfigurationSection("businesses");
        if (bs == null) return;
        for (String id : bs.getKeys(false)) {
            ConfigurationSection s = bs.getConfigurationSection(id);
            Biz b = new Biz();
            b.id = id;
            b.name = s.getString("name", id);
            b.plot = s.getString("plot");
            b.town = s.getString("town", Meadowbrook.ID);
            b.anchor = s.getString("anchor");
            try {
                b.kind = Kind.valueOf(s.getString("kind", "STORE"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            String o = s.getString("owner");
            b.owner = o == null ? null : UUID.fromString(o);
            b.rival = s.getBoolean("rival");
            b.level = s.getInt("level", 1);
            b.npcStaff = s.getInt("npc-staff");
            b.security = s.getInt("security");
            b.insured = s.getBoolean("insured");
            b.jobsOpen = s.getBoolean("jobs-open");
            b.cash = s.getDouble("cash");
            b.rating = s.getDouble("rating", 3);
            b.damage = s.getDouble("damage");
            b.wage = s.getDouble("wage", 30);
            b.price = s.getDouble("price", b.kind.output.ref);
            b.closedUntil = s.getLong("closed-until");
            b.closedWhy = s.getString("closed-why", "");
            ConfigurationSection st = s.getConfigurationSection("stock");
            if (st != null) for (String g : st.getKeys(false)) b.stock.put(Good.valueOf(g), st.getDouble(g));
            for (String u : s.getStringList("workers")) b.workers.add(UUID.fromString(u));
            b.revenue = s.getDouble("last.revenue");
            b.costs = s.getDouble("last.costs");
            b.made = s.getDouble("last.made");
            b.sold = s.getDouble("last.sold");
            all.put(id, b);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-cycle", nextCycle);
        for (Biz b : all.values()) {
            ConfigurationSection s = y.createSection("businesses." + b.id);
            s.set("name", b.name);
            s.set("plot", b.plot);
            s.set("town", b.town);
            s.set("anchor", b.anchor);
            s.set("kind", b.kind.name());
            s.set("owner", b.owner == null ? null : b.owner.toString());
            s.set("rival", b.rival ? true : null);
            s.set("level", b.level);
            s.set("npc-staff", b.npcStaff);
            s.set("security", b.security);
            s.set("insured", b.insured);
            s.set("jobs-open", b.jobsOpen);
            s.set("cash", b.cash);
            s.set("rating", b.rating);
            s.set("damage", b.damage);
            s.set("wage", b.wage);
            s.set("price", b.price);
            s.set("closed-until", b.closedUntil);
            s.set("closed-why", b.closedWhy);
            for (var e : b.stock.entrySet()) if (e.getValue() > 0.01) s.set("stock." + e.getKey().name(), Math.round(e.getValue() * 100) / 100.0);
            List<String> ws = new ArrayList<>();
            for (UUID u : b.workers) ws.add(u.toString());
            s.set("workers", ws);
            s.set("last.revenue", b.revenue);
            s.set("last.costs", b.costs);
            s.set("last.made", b.made);
            s.set("last.sold", b.sold);
        }
        try {
            y.save(file);
        } catch (IOException e) {
            t.plugin().getLogger().warning("Could not save businesses.yml: " + e.getMessage());
        }
    }

    public java.util.Collection<Biz> all() {
        return all.values();
    }

    public Biz get(String id) {
        return all.get(id);
    }

    public Biz atPlot(Plots.Plot p) {
        if (p == null) return null;
        for (Biz b : all.values()) if (p.id.equals(b.plot)) return b;
        return null;
    }

    public Biz at(Location l) {
        Biz b = atPlot(t.plots().at(l));
        if (b != null) return b;
        for (Biz x : all.values()) {
            Location a = anchor(x);
            if (x.plot == null && a != null && a.getWorld() == l.getWorld() && a.distance(l) < 12) return x;
        }
        return null;
    }

    public List<Biz> ownedBy(UUID u) {
        List<Biz> out = new ArrayList<>();
        for (Biz b : all.values()) if (u.equals(b.owner)) out.add(b);
        return out;
    }

    public Biz employerOf(UUID u) {
        for (Biz b : all.values()) if (b.workers.contains(u)) return b;
        return null;
    }

    /** Where the business is: the middle of its plot, or its anchor. */
    public Location anchor(Biz b) {
        if (b.plot != null) {
            Plots.Plot p = t.plots().get(b.plot);
            if (p != null) {
                World w = Bukkit.getWorld(p.world);
                return w == null ? null : p.centre(w);
            }
        }
        if (b.anchor == null) return null;
        String[] a = b.anchor.split(",");
        World w = Bukkit.getWorld(a[0]);
        return w == null ? null : new Location(w, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]));
    }

    private String newId(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.isEmpty()) base = "biz";
        String id = base;
        int n = 2;
        while (all.containsKey(id)) id = base + "-" + n++;
        return id;
    }

    /** The town's own businesses in Meadowbrook (created once, after the town is built). */
    void ensureTownBusinesses() {
        if (!t.meadowbrook().built()) return;
        boolean any = false;
        for (Biz b : all.values()) if (!b.playerOwned() && !b.rival) any = true;
        if (any) return;
        townBiz("The Copper Kettle", Kind.RESTAURANT, "b1", null);
        townBiz("Meadowbrook General Store", Kind.STORE, "b2", null);
        townBiz("Bramble Bakery", Kind.BAKERY, "b3", null);
        townBiz("Meadowbrook Wool Mill", Kind.WOOL_MILL, "i5", null);
        townBiz("Brookside Lumber", Kind.LUMBER_MILL, "i6", null);
        var d = t.meadowbrook().data();
        List<Integer> c = d.getIntegerList("centre");
        String w = d.getString("world", "world");
        if (c.size() == 3) {
            townBiz("Meadowbrook Farms", Kind.FARM, null, w + "," + (c.get(0) - 17) + "," + (c.get(1) + 1) + "," + (c.get(2) - 17));
            townBiz("The Grand Meadow Inn", Kind.HOTEL, null, w + "," + (c.get(0) - 17) + "," + (c.get(1) + 1) + "," + (c.get(2) + 45));
            townBiz("The Rusty Tankard", Kind.TAVERN, null, w + "," + (c.get(0) + 9) + "," + (c.get(1) + 1) + "," + (c.get(2) + 9));
            townBiz("Thistle & Thread Tailors", Kind.TAILOR, null, w + "," + (c.get(0) - 9) + "," + (c.get(1) + 1) + "," + (c.get(2) + 9));
            townBiz("Oakheart Furniture", Kind.FURNITURE, null, w + "," + (c.get(0) - 9) + "," + (c.get(1) + 1) + "," + (c.get(2) - 9));
        }
        save();
    }

    private void townBiz(String name, Kind k, String plot, String anchor) {
        Biz b = new Biz();
        b.id = newId(name);
        b.name = name;
        b.kind = k;
        b.plot = plot;
        b.anchor = anchor;
        b.level = 2;
        b.npcStaff = 2;
        b.jobsOpen = true;
        b.price = k.output.ref;
        b.cash = 5000;
        b.stock.put(k.output, (double) k.capacity * 2);
        all.put(b.id, b);
    }

    void plotReleased(Plots.Plot p) {
        Biz b = atPlot(p);
        if (b == null || !b.playerOwned()) return;
        OfflinePlayer o = Bukkit.getOfflinePlayer(b.owner);
        t.money.give(o, Math.max(0, b.cash));
        for (UUID w : b.workers) {
            Player pl = Bukkit.getPlayer(w);
            if (pl != null) pl.sendMessage(SmpPlugin.mm("<gray>" + b.name + " has closed down. You're out of a job."));
        }
        all.remove(b.id);
        t.news(b.name + " closed its doors for good.");
        save();
    }

    // ------------------------------------------------------------------ the cycle

    private double capacity(Biz b) {
        double staff = 1 + 0.25 * b.npcStaff + Math.min(1.0, 0.05 * b.tasks) + t.residents().bonus(b);
        return b.kind.capacity * b.level * staff * (1 - b.damage);
    }

    /** Cheapest seller of a good with stock, other than the buyer. */
    private Biz cheapest(Good g, Biz buyer) {
        Biz best = null;
        for (Biz s : all.values()) {
            if (s == buyer || s.kind.output != g || !s.open() || s.stock.getOrDefault(g, 0.0) < 0.5) continue;
            if (best == null || s.price < best.price) best = s;
        }
        return best;
    }

    private double demandScale() {
        int online = Bukkit.getOnlinePlayers().size();
        return cfg().getDouble("town.business.demand", 1.0) * (1 + 0.15 * online + t.residentsDemand());
    }

    void second() {
        long now = System.currentTimeMillis();
        work.second();
        if (nextCycle == 0) nextCycle = now + cycleMs();
        if (now < nextCycle) return;
        nextCycle = now + cycleMs();
        cycle();
    }

    long cycleMs() {
        return cfg().getLong("town.business.cycle-seconds", 300) * 1000L;
    }

    public long nextCycleIn() {
        return Math.max(0, nextCycle - System.currentTimeMillis());
    }

    void cycle() {
        ensureTownBusinesses();
        long now = System.currentTimeMillis();
        for (Biz b : all.values()) {
            b.revenue = 0;
            b.costs = 0;
            b.made = 0;
            b.sold = 0;
            b.imported = 0;
        }
        // 1. police and fugitives
        for (Biz b : all.values()) policeCheck(b, now);
        // 2. production, buying inputs (suppliers first so they have stock for the rest)
        List<Biz> order = new ArrayList<>(all.values());
        order.sort((a, c) -> Integer.compare(a.kind.inputs.size(), c.kind.inputs.size()));
        for (Biz b : order) {
            if (!b.open()) continue;
            if (b.playerOwned() && b.cash < -1000) continue;
            double want = capacity(b);
            double already = b.stock.getOrDefault(b.kind.output, 0.0);
            double cap = Math.max(0, Math.min(want, b.kind.capacity * b.level * 4 - already));
            if (cap <= 0) continue;
            double canMake = cap;
            for (var in : b.kind.inputs.entrySet()) {
                double need = in.getValue() * cap - b.stock.getOrDefault(in.getKey(), 0.0);
                if (need > 0) buy(b, in.getKey(), need);
                canMake = Math.min(canMake, b.stock.getOrDefault(in.getKey(), 0.0) / in.getValue());
            }
            canMake = Math.floor(canMake);
            if (canMake <= 0) continue;
            for (var in : b.kind.inputs.entrySet()) b.stock.merge(in.getKey(), -in.getValue() * canMake, Double::sum);
            b.stock.merge(b.kind.output, canMake, Double::sum);
            b.made = canMake;
        }
        // 3. townspeople buy consumer goods
        double scale = demandScale();
        for (Good g : Good.values()) {
            if (!g.consumer) continue;
            List<Biz> sellers = new ArrayList<>();
            for (Biz b : all.values()) if (b.kind.output == g && b.open() && b.stock.getOrDefault(g, 0.0) >= 1) sellers.add(b);
            if (sellers.isEmpty()) continue;
            double base = cfg().getDouble("town.business.base-demand." + g.name().toLowerCase(Locale.ROOT), defaultDemand(g)) * scale;
            double avg = 0;
            for (Biz b : sellers) avg += b.price;
            avg /= sellers.size();
            double demand = base * Math.max(0.4, Math.min(1.6, g.ref / avg));
            double totalA = 0;
            Map<Biz, Double> a = new HashMap<>();
            for (Biz b : sellers) {
                double attract = Math.max(0.2, b.rating) * Math.pow(g.ref / Math.max(1, b.price), 2) * Math.min(b.stock.get(g), capacity(b) + 1);
                a.put(b, attract);
                totalA += attract;
            }
            for (Biz b : sellers) {
                double sold = Math.floor(Math.min(b.stock.get(g), demand * a.get(b) / totalA));
                b.stock.merge(g, -sold, Double::sum);
                b.sold += sold;
                b.revenue += sold * b.price;
                b.cash += sold * b.price;
                // rating: served well vs ran short
                b.rating = Math.max(0, Math.min(5, b.rating + (sold >= demand * a.get(b) / totalA * 0.95 ? 0.05 : -0.1)));
            }
        }
        // Market share counts everything sold this cycle, to townspeople and to other businesses.
        for (Good g : Good.values()) {
            List<Biz> makers = new ArrayList<>();
            double total = 0, players = 0;
            for (Biz b : all.values()) if (b.kind.output == g) {
                makers.add(b);
                total += b.sold;
                if (b.playerOwned()) players += b.sold;
            }
            if (total > 0) competition(g, makers, players / total);
        }
        // 4. costs: staff, upkeep, insurance, tax; damage heals
        for (Biz b : all.values()) {
            t.residents().pay(b);
            double staff = b.npcStaff * cfg().getDouble("town.business.npc-wage", 15);
            double upkeep = b.level * cfg().getDouble("town.business.upkeep-per-level", 25) + (b.insured ? b.level * 30 : 0) + b.security * 20;
            double tax = b.revenue * cfg().getDouble("town.business.tax", 0.05);
            double c = staff + upkeep + tax;
            if (!b.playerOwned()) c *= 0.5;
            b.cash -= c;
            b.costs += c;
            t.townTreasury(b.town, tax);
            b.damage = Math.max(0, b.damage - 0.1);
            b.tasks = 0;
            if (!b.playerOwned() && b.cash < 1000) b.cash += 3000; // the town keeps its businesses alive
            sabotageRoll(b);
            if (!b.playerOwned()) priceAI(b);
        }
        // 5. report to owners
        for (Biz b : all.values()) {
            if (!b.playerOwned()) continue;
            Player o = Bukkit.getPlayer(b.owner);
            if (o == null) continue;
            double profit = b.revenue - b.costs;
            o.sendMessage(SmpPlugin.mm("<gold>[" + b.name + "] <gray>made " + (int) b.made + " " + b.kind.output.label.toLowerCase() + ", sold " + (int) b.sold
                    + (b.imported > 0 ? ", imported inputs for " + Money.fmt(b.imported) : "") + ". " + (profit >= 0 ? "<green>Profit +" : "<red>Loss ") + Money.fmt(Math.abs(profit))
                    + " <gray>· cash " + Money.fmt(b.cash) + (b.cash < 0 ? " <red>(deposit money or it stops!)" : "")));
        }
        save();
    }

    private static double defaultDemand(Good g) {
        return switch (g) {
            case BREAD -> 30;
            case ALE -> 20;
            case MEALS -> 14;
            case GOODS -> 14;
            case STAYS -> 6;
            case MOONSHINE -> 8;
            default -> 0;
        };
    }

    private void buy(Biz buyer, Good g, double need) {
        while (need > 0.01) {
            Biz s = cheapest(g, buyer);
            if (s == null) {
                // nobody local has it: import at double the fair price
                double cost = need * g.ref * 2;
                if (buyer.playerOwned() && buyer.cash < cost) need = Math.max(0, Math.floor(buyer.cash / (g.ref * 2)));
                if (need <= 0) return;
                buyer.cash -= need * g.ref * 2;
                buyer.costs += need * g.ref * 2;
                buyer.imported += need * g.ref * 2;
                buyer.stock.merge(g, need, Double::sum);
                return;
            }
            double qty = Math.min(need, s.stock.get(g));
            double cost = qty * s.price;
            if (buyer.playerOwned() && buyer.cash < cost) {
                qty = Math.floor(buyer.cash / s.price);
                if (qty <= 0) return;
                cost = qty * s.price;
            }
            s.stock.merge(g, -qty, Double::sum);
            s.cash += cost;
            s.revenue += cost;
            s.sold += qty;
            buyer.cash -= cost;
            buyer.costs += cost;
            buyer.stock.merge(g, qty, Double::sum);
            need -= qty;
        }
    }

    /** Town-run businesses chase demand; rivals undercut. */
    private void priceAI(Biz b) {
        double ref = b.kind.output.ref;
        double stock = b.stock.getOrDefault(b.kind.output, 0.0);
        if (b.rival) b.price = Math.max(ref * 0.75, b.price * 0.97);
        else if (stock > capacity(b) * 2) b.price = Math.max(ref * 0.6, b.price * 0.95);
        else if (stock < 1) b.price = Math.min(ref * 1.8, b.price * 1.05);
    }

    /** A player who dominates a market draws rivals. */
    private void competition(Good g, List<Biz> sellers, double playerShare) {
        String k = g.name();
        if (playerShare > cfg().getDouble("town.business.dominance-share", 0.4)) dominance.merge(k, 1, Integer::sum);
        else dominance.put(k, 0);
        if (dominance.getOrDefault(k, 0) < cfg().getInt("town.business.rival-after-cycles", 6)) return;
        int rivals = 0;
        for (Biz b : all.values()) if (b.rival && b.kind.output == g) rivals++;
        if (rivals >= 3) return;
        dominance.put(k, 0);
        Kind kind = null;
        for (Kind x : Kind.values()) if (x.output == g) kind = x;
        String[] names = {"Grubb & Sons", "Pennywhistle Co.", "Blackwood Holdings", "Silverline", "Marrow & Finch", "The Hargreave Group"};
        Biz rv = new Biz();
        rv.name = names[r.nextInt(names.length)] + " " + kind.label;
        rv.id = newId(rv.name);
        rv.kind = kind;
        rv.rival = true;
        rv.level = 3;
        rv.npcStaff = 3;
        rv.cash = 20000;
        rv.price = g.ref * 0.85;
        rv.stock.put(g, (double) kind.capacity * 3);
        rv.anchor = null;
        all.put(rv.id, rv);
        for (Biz b : sellers) {
            if (!b.playerOwned()) continue;
            Player o = Bukkit.getPlayer(b.owner);
            if (o != null) o.sendMessage(SmpPlugin.mm("<red>[" + b.name + "] A rival has moved in: <white>" + rv.name + "</white> is undercutting your " + g.label.toLowerCase() + " prices."));
        }
        t.news(rv.name + " opened to take on the town's biggest " + kind.label.toLowerCase() + ".");
    }

    // ------------------------------------------------------------------ sabotage and the law

    private void sabotageRoll(Biz b) {
        if (!b.playerOwned() || !b.open()) return;
        double share = 0;
        double total = 0, mine = 0;
        for (Biz x : all.values()) if (x.kind.output == b.kind.output) {
            total += x.revenue;
            if (x == b) mine = x.revenue;
        }
        if (total > 0) share = mine / total;
        double p = 0.01 + 0.06 * share + 0.01 * b.level - 0.02 * b.security;
        boolean rivalsAround = false;
        for (Biz x : all.values()) if (x.rival && x.kind.output == b.kind.output) rivalsAround = true;
        if (rivalsAround) p += 0.03;
        if (r.nextDouble() >= p) return;
        sabotage(b, null);
    }

    /** Something bad happens to a business. by = the player responsible, or null for rivals/thugs. */
    void sabotage(Biz b, Player by) {
        int kind = r.nextInt(4);
        if (kind == 2 && b.stock.getOrDefault(b.kind.output, 0.0) < 2) kind = r.nextBoolean() ? 0 : 1; // nothing to steal
        String what;
        double loss = 0;
        switch (kind) {
            case 0 -> {
                b.damage = Math.min(0.6, b.damage + 0.3);
                what = "Thugs smashed the windows and broke the equipment (-30% output for a while)";
            }
            case 1 -> {
                b.rating = Math.max(0, b.rating - 1);
                what = "Someone spread nasty rumours about the place (-1 rating)";
            }
            case 2 -> {
                Good g = b.kind.output;
                double lost = Math.floor(b.stock.getOrDefault(g, 0.0) / 2);
                b.stock.merge(g, -lost, Double::sum);
                loss = lost * b.price;
                what = "Half the stock was stolen in the night (" + (int) lost + " " + g.label.toLowerCase() + ")";
            }
            default -> {
                loss = Math.min(Math.max(0, b.cash), b.level * 2000);
                b.damage = Math.min(0.6, b.damage + 0.2);
                what = "A fire broke out in the back (" + Money.fmt(loss) + " in repairs)";
                b.cash -= loss;
            }
        }
        if (b.insured && loss > 0) {
            b.cash += loss * 0.8;
            what += ". <green>Insurance paid out " + Money.fmt(loss * 0.8);
        }
        if (b.owner != null) {
            Player o = Bukkit.getPlayer(b.owner);
            if (o != null) o.sendMessage(SmpPlugin.mm("<red>[" + b.name + "] Sabotage! <white>" + what + (by != null ? "" : "<gray>. Rivals, maybe. Security helps.")));
        }
        t.news(b.name + " was hit by sabotage.");
    }

    private void policeCheck(Biz b, long now) {
        if (!b.playerOwned() || !b.open()) return;
        TownData od = t.data(b.owner);
        if (od.stars >= 3 || od.caseOpen) {
            close(b, now + 3 * cycleMs(), "the owner is a fugitive");
            return;
        }
        if (!b.kind.legal && r.nextDouble() < cfg().getDouble("town.business.raid-chance", 0.06) * (t.gangs().of(b.owner) != null && t.gangs().of(b.owner).perks.contains("bribes") ? 0.6 : 1)) {
            double fine = Math.floor(Math.max(0, b.cash) * 0.2);
            b.cash -= fine;
            b.stock.clear();
            close(b, now + 12 * cycleMs(), "raided by the Watch for selling moonshine (fine " + Money.fmt(fine) + ")");
            t.news("The Watch raided " + b.name + " and found an illegal still!");
            return;
        }
        if ((b.kind == Kind.RESTAURANT || b.kind == Kind.BAKERY || b.kind == Kind.HOTEL) && b.rating < 1.0 && r.nextDouble() < 0.3) {
            close(b, now + 2 * cycleMs(), "failed a health inspection");
            b.rating = 1.5;
        }
    }

    void close(Biz b, long until, String why) {
        b.closedUntil = until;
        b.closedWhy = why;
        if (b.owner != null) {
            Player o = Bukkit.getPlayer(b.owner);
            if (o != null) o.sendMessage(SmpPlugin.mm("<red>[" + b.name + "] Shut down: " + why + ". <gray>Opens again in " + (until - System.currentTimeMillis()) / 60_000 + " minutes."));
        }
    }

    // ------------------------------------------------------------------ menu

    static final class Panel implements InventoryHolder {
        final Biz b;
        Inventory inv;

        Panel(Biz b) {
            this.b = b;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static ItemStack icon(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(SmpPlugin.mm(name).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(SmpPlugin.mm(s).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    private double upgradeCost(Biz b) {
        return b.kind.startCost * b.level;
    }

    void panel(Player p, Biz b) {
        Panel pn = new Panel(b);
        pn.inv = Bukkit.createInventory(pn, 54, SmpPlugin.mm("<dark_green>" + b.name));
        String stars = "★".repeat((int) Math.round(b.rating)) + "☆".repeat(5 - (int) Math.round(b.rating));
        pn.inv.setItem(4, icon(b.kind.station, "<gold><bold>" + b.name, "<gray>" + b.kind.label + " · level " + b.level + " · <yellow>" + stars,
                b.open() ? "<green>Open" : "<red>Closed: " + b.closedWhy, "<gray>Next cycle in " + nextCycleIn() / 1000 + "s"));
        pn.inv.setItem(0, icon(Material.GOLD_INGOT, "<gold>Cash: " + Money.fmt(b.cash), "<gray>/business deposit <amount>", "<gray>/business withdraw <amount>"));
        pn.inv.setItem(8, icon(Material.PAPER, "<white>Last cycle", "<gray>Made " + (int) b.made + ", sold " + (int) b.sold, "<green>Revenue " + Money.fmt(b.revenue),
                "<red>Costs " + Money.fmt(b.costs) + (b.imported > 0 ? " (imports " + Money.fmt(b.imported) + ")" : "")));
        int slot = 10;
        for (Good g : b.kind.inputs.keySet()) pn.inv.setItem(slot++, icon(g.icon, "<white>" + g.label + " (input)", "<gray>In stock: " + (int) Math.floor(b.stock.getOrDefault(g, 0.0)),
                "<gray>Needs " + b.kind.inputs.get(g) + " per " + b.kind.output.label.toLowerCase()));
        pn.inv.setItem(16, icon(b.kind.output.icon, "<white>" + b.kind.output.label + " (output)", "<gray>In stock: " + (int) Math.floor(b.stock.getOrDefault(b.kind.output, 0.0)),
                "<gray>Capacity: " + (int) capacity(b) + " per cycle"));
        pn.inv.setItem(19, icon(Material.RED_CONCRETE, "<red>-10% price"));
        pn.inv.setItem(20, icon(Material.EMERALD, "<gold>Price: " + Money.fmt(b.price) + " each", "<gray>Fair price: " + Money.fmt(b.kind.output.ref), "<gray>Cheaper sells more"));
        pn.inv.setItem(21, icon(Material.LIME_CONCRETE, "<green>+10% price"));
        pn.inv.setItem(23, icon(Material.ANVIL, "<aqua>Upgrade to level " + (b.level + 1), "<gray>More capacity", "<gold>" + Money.fmt(upgradeCost(b)) + " <gray>(from business cash)"));
        pn.inv.setItem(24, icon(Material.PLAYER_HEAD, "<white>NPC staff: " + b.npcStaff, "<gray>+25% output each, " + Money.fmt(cfg().getDouble("town.business.npc-wage", 15)) + " per cycle",
                "<green>Left-click: hire · <red>Right-click: let one go"));
        pn.inv.setItem(25, icon(Material.WRITABLE_BOOK, "<white>Jobs for players: " + (b.jobsOpen ? "<green>open" : "<red>closed"), "<gray>Pay per task: " + Money.fmt(b.wage),
                "<gray>Workers: " + b.workers.size(), "<green>Left-click: open/close · <yellow>Right-click: +$5 wage · Shift: -$5"));
        pn.inv.setItem(28, icon(Material.IRON_DOOR, "<white>Security: " + b.security + "/3", "<gray>Less sabotage. " + Money.fmt(20) + " per cycle per level", "<gold>Upgrade: " + Money.fmt((b.security + 1) * 5000)));
        pn.inv.setItem(29, icon(b.insured ? Material.LIME_DYE : Material.GRAY_DYE, "<white>Insurance: " + (b.insured ? "<green>on" : "<red>off"),
                "<gray>Pays 80% of sabotage losses", "<gray>" + Money.fmt(b.level * 30) + " per cycle"));
        List<String> ws = new ArrayList<>();
        for (UUID u : b.workers) ws.add(Bukkit.getOfflinePlayer(u).getName());
        if (!b.applicants.isEmpty()) {
            List<String> ap = new ArrayList<>();
            for (UUID u : b.applicants) ap.add(Bukkit.getOfflinePlayer(u).getName());
            pn.inv.setItem(31, icon(Material.BELL, "<yellow>Job applications: " + ap.size(), "<white>" + String.join(", ", ap), "<gray>/business accept <player>"));
        }
        pn.inv.setItem(30, icon(Material.NAME_TAG, "<white>Employees", "<gray>" + (ws.isEmpty() ? "none" : String.join(", ", ws)), "<gray>/business fire <player>"));
        p.openInventory(pn.inv);
    }

    @EventHandler
    public void onPanel(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Panel pn)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        Biz b = pn.b;
        if (!p.getUniqueId().equals(b.owner) && !p.hasPermission("mrsmp.admin")) return;
        switch (e.getRawSlot()) {
            case 19 -> b.price = Math.max(1, Math.round(b.price * 0.9));
            case 21 -> b.price = Math.round(b.price * 1.1 + 0.5);
            case 23 -> {
                if (b.level >= 5) {
                    p.sendMessage(SmpPlugin.mm("<gray>Already at the top level."));
                    return;
                }
                double c = upgradeCost(b);
                if (b.cash < c) {
                    p.sendMessage(SmpPlugin.mm("<red>The business needs " + Money.fmt(c) + " in cash. /business deposit"));
                    return;
                }
                b.cash -= c;
                b.level++;
                p.sendMessage(SmpPlugin.mm("<green>" + b.name + " is now level " + b.level + "!"));
                t.news(b.name + " expanded to level " + b.level + ".");
            }
            case 24 -> {
                if (e.isRightClick()) b.npcStaff = Math.max(0, b.npcStaff - 1);
                else if (b.npcStaff < b.level * 2) b.npcStaff++;
                else p.sendMessage(SmpPlugin.mm("<gray>Level " + b.level + " can take " + b.level * 2 + " NPC staff."));
            }
            case 25 -> {
                if (e.isShiftClick()) b.wage = Math.max(5, b.wage - 5);
                else if (e.isRightClick()) b.wage += 5;
                else b.jobsOpen = !b.jobsOpen;
            }
            case 28 -> {
                if (b.security >= 3) return;
                double c = (b.security + 1) * 5000;
                if (b.cash < c) {
                    p.sendMessage(SmpPlugin.mm("<red>The business needs " + Money.fmt(c) + "."));
                    return;
                }
                b.cash -= c;
                b.security++;
            }
            case 29 -> b.insured = !b.insured;
            default -> {
                return;
            }
        }
        save();
        panel(p, b);
    }

    @EventHandler
    public void onPanelDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Panel) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ commands: /business, /jobs, /job

    private static boolean say(CommandSender s, String m) {
        s.sendMessage(SmpPlugin.mm(m));
        return true;
    }

    private Biz mine(Player p) {
        Biz here = at(p.getLocation());
        if (here != null && p.getUniqueId().equals(here.owner)) return here;
        List<Biz> list = ownedBy(p.getUniqueId());
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (!(sender instanceof Player p)) return say(sender, "Players only.");
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("jobs")) return work.list(p);
        if (cmd.equals("job")) return work.command(p, a);
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start" -> {
                Plots.Plot plot = t.plots().at(p.getLocation());
                if (plot == null || !p.getUniqueId().equals(plot.owner) || plot.kind == Plots.Kind.HOME) return say(p, "<red>Stand on a business or industry plot you own. <gray>/plot list");
                if (atPlot(plot) != null) return say(p, "<red>There's already a business here.");
                if (a.length < 3) {
                    StringBuilder kinds = new StringBuilder();
                    for (Kind k : Kind.values()) kinds.append(k.id()).append(k.legal ? "" : " (illegal)").append(" ").append(Money.fmt(k.startCost)).append(", ");
                    return say(p, "<yellow>/business start <type> <name>  <gray>Types: " + kinds.substring(0, kinds.length() - 2));
                }
                Kind k = Kind.parse(a[1]);
                if (k == null) return say(p, "<red>Unknown type.");
                String name = String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)).replace("<", "");
                if (name.length() > 32) return say(p, "<red>Keep the name under 32 characters.");
                if (!t.money.pay(p, k.startCost)) return say(p, "<red>Setting up a " + k.label.toLowerCase() + " costs " + Money.fmt(k.startCost) + ".");
                Biz b = new Biz();
                b.id = newId(name);
                b.name = name;
                b.kind = k;
                b.plot = plot.id;
                b.town = plot.town;
                b.owner = p.getUniqueId();
                b.price = k.output.ref;
                b.cash = k.startCost * 0.2;
                b.jobsOpen = true;
                all.put(b.id, b);
                plot.business = name;
                t.plots().refreshSign(plot);
                t.plots().save();
                save();
                say(p, "<green>" + name + " is open for business! <gray>It starts with " + Money.fmt(b.cash) + " in cash. <white>/business</white> to manage it. "
                        + "Put a " + k.station.name().toLowerCase().replace('_', ' ') + " (or any job block) inside so workers have somewhere to work.");
                if (!k.legal) say(p, "<red>This is illegal. Watch raids will close it and take a fine.");
                t.news(p.getName() + " opened " + name + ", a new " + k.label.toLowerCase() + " in " + Plots.cap(plot.town) + ".");
            }
            case "deposit", "withdraw" -> {
                Biz b = mine(p);
                if (b == null || a.length < 2) return say(p, "<yellow>/business " + sub + " <amount> <gray>(on your business's plot)");
                double n;
                try {
                    n = Math.floor(Double.parseDouble(a[1]));
                } catch (NumberFormatException ex) {
                    return say(p, "<red>Amount must be a number.");
                }
                if (n <= 0) return true;
                if (sub.equals("deposit")) {
                    if (!t.money.pay(p, n)) return say(p, "<red>You don't have that.");
                    b.cash += n;
                } else {
                    if (n > b.cash) return say(p, "<red>" + b.name + " only has " + Money.fmt(b.cash) + ".");
                    b.cash -= n;
                    t.money.give(p, n);
                    t.gangs().crimeIncome(p, b.kind.legal ? 0 : n, b.kind.legal ? 0 : 10);
                }
                save();
                say(p, "<green>Done. " + b.name + " has " + Money.fmt(b.cash) + ".");
            }
            case "accept" -> {
                Biz b = mine(p);
                Player o = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                if (b == null || o == null || !b.applicants.remove(o.getUniqueId())) return say(p, "<yellow>/business accept <applicant>");
                work.hire(b, o);
                say(p, "<green>" + o.getName() + " now works at " + b.name + ".");
            }
            case "hire" -> {
                Biz b = mine(p);
                Player o = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                if (b == null || o == null) return say(p, "<yellow>/business hire <player>");
                work.offer(b, o);
                say(p, "<gray>Job offer sent to " + o.getName() + ".");
            }
            case "fire" -> {
                Biz b = mine(p);
                if (b != null && a.length > 1 && t.residents().fire(b, String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)))) {
                    return say(p, "<gray>" + a[1] + " no longer works at " + b.name + ".");
                }
                OfflinePlayer o = a.length > 1 ? Bukkit.getOfflinePlayer(a[1]) : null;
                if (b == null || o == null || !b.workers.remove(o.getUniqueId())) return say(p, "<yellow>/business fire <employee>");
                work.clockOut(o.getUniqueId());
                save();
                say(p, "<gray>" + o.getName() + " no longer works at " + b.name + ".");
                if (o.getPlayer() != null) o.getPlayer().sendMessage(SmpPlugin.mm("<red>You were let go from " + b.name + "."));
            }
            case "market" -> {
                say(p, "<gold><bold>Meadowbrook market</bold> <gray>(price · stock · rating)");
                for (Good g : Good.values()) {
                    List<String> sellers = new ArrayList<>();
                    for (Biz b : all.values()) if (b.kind.output == g) sellers.add((b.playerOwned() ? "<aqua>" : b.rival ? "<red>" : "<white>") + b.name + " <gray>"
                            + Money.fmt(b.price) + " · " + (int) Math.floor(b.stock.getOrDefault(g, 0.0)) + " · " + String.format("%.1f", b.rating) + (b.open() ? "" : " <red>(closed)"));
                    say(p, " <yellow>" + g.label + "<gray>: " + (sellers.isEmpty() ? "<dark_gray>nobody makes it (imports cost double)" : String.join("<gray>, ", sellers)));
                }
            }
            case "list" -> {
                List<Biz> mineList = ownedBy(p.getUniqueId());
                if (mineList.isEmpty()) return say(p, "<gray>You don't own a business. Rent or buy a business plot, then /business start.");
                for (Biz b : mineList) say(p, " <gold>" + b.name + " <gray>" + b.kind.label + " lvl " + b.level + " · cash " + Money.fmt(b.cash) + (b.open() ? "" : " · <red>closed"));
            }
            default -> {
                Biz here = at(p.getLocation());
                if (here != null && (p.getUniqueId().equals(here.owner) || p.hasPermission("mrsmp.admin"))) {
                    panel(p, here);
                    return true;
                }
                Biz b = mine(p);
                if (b != null) {
                    panel(p, b);
                    return true;
                }
                if (here != null) return say(p, "<gold>" + here.name + " <gray>(" + here.kind.label + (here.playerOwned() ? ", owned by " + Bukkit.getOfflinePlayer(here.owner).getName()
                        : here.rival ? ", a rival company" : ", town-run") + ") sells " + here.kind.output.label.toLowerCase() + " at " + Money.fmt(here.price) + ".");
                say(p, "<gray>/business start <type> <name> on your plot · /business market · /business list · /jobs");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("job")) {
            if (args.length == 1) {
                for (String s : List.of("apply", "accept", "clockin", "clockout", "quit", "info")) if (s.startsWith(args[0].toLowerCase())) out.add(s);
            } else if (args.length == 2 && args[0].equalsIgnoreCase("apply")) {
                out.addAll(all.keySet());
            }
            return out;
        }
        if (cmd.equals("jobs")) return out;
        if (args.length == 1) {
            for (String s : List.of("start", "market", "list", "deposit", "withdraw", "hire", "fire", "accept")) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            for (Kind k : Kind.values()) out.add(k.id());
        } else if (args.length == 2) {
            return null;
        }
        return out;
    }
}
