package net.moddedrealms.smp.town;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import net.moddedrealms.smp.Pack;
import net.moddedrealms.smp.SmpPlugin;

/**
 * The people of Meadowbrook. Families live in the town's homes (the ones no player owns), adults work at town or
 * player businesses (and are paid by them), children play in the square, elders sit there. Everyone follows the
 * clock: off to work at dawn, errands in the afternoon, the tavern in the evening, home at night. Only the ones near a
 * player are walking about; the rest carry on out of sight. Days bring births, weddings, children growing up and
 * moving out, and old age. Residents also buy from the shops, so a bigger town means more trade.
 */
public final class Residents {
    enum Age { CHILD, ADULT, ELDER }

    enum Place { HOME, WORK, SHOP, TAVERN, SQUARE }

    static final class Res {
        String id, first, family, home, job, spouse, skin;
        boolean female;
        long born; // epoch day
        final List<String> kids = new ArrayList<>();
        // not saved: what they're doing right now
        Place place = Place.HOME;
        Walker walker;
        Location target;
        long insideUntil, nextWander;
        boolean shopped;
        String shop;

        String name() {
            return first + " " + family;
        }
    }

    private static final String[] MALE = {"Tom", "Will", "Hugh", "Walter", "Robin", "Alfred", "Edmund", "Gilbert", "Roger", "Simon", "Piers", "Hal",
            "Ned", "Osric", "Ralph", "Geoffrey", "Bram", "Cedric", "Jory", "Martin"};
    private static final String[] FEMALE = {"Mara", "Agnes", "Alys", "Edith", "Maud", "Joan", "Isolde", "Rose", "Elsie", "Beatrice", "Cecily",
            "Margery", "Wren", "Ida", "Rowena", "Nell", "Hilda", "Avice", "Tilda", "Gwen"};
    private static final String[] FAMILY = {"Thatcher", "Miller", "Cooper", "Fletcher", "Smith", "Baker", "Weaver", "Tanner", "Chandler", "Mason",
            "Carter", "Fisher", "Brewer", "Potter", "Wright", "Sawyer", "Shepherd", "Hayward", "Ashdown", "Bramble", "Fairweather", "Holloway"};

    private final TownManager t;
    private final File file;
    private final Map<String, Res> all = new LinkedHashMap<>();
    private final Random r = new Random();
    private long lastDay;
    private long lastWorldDay = -1;
    private int tick;
    private boolean dirty;

    Residents(TownManager t) {
        this.t = t;
        this.file = new File(t.plugin().getDataFolder(), "residents.yml");
        load();
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    private boolean enabled() {
        return cfg().getBoolean("town.residents.enabled", true) && t.meadowbrook().built();
    }

    // ------------------------------------------------------------------ data

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        lastDay = y.getLong("last-day");
        ConfigurationSection s = y.getConfigurationSection("residents");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(id);
            Res x = new Res();
            x.id = id;
            x.first = c.getString("first");
            x.family = c.getString("family");
            x.female = c.getBoolean("female");
            x.born = c.getLong("born");
            x.home = c.getString("home");
            x.job = c.getString("job");
            x.spouse = c.getString("spouse");
            x.skin = c.getString("skin");
            x.kids.addAll(c.getStringList("kids"));
            all.put(id, x);
        }
    }

    void save() {
        if (!dirty) return;
        YamlConfiguration y = new YamlConfiguration();
        y.set("last-day", lastDay);
        for (Res x : all.values()) {
            String k = "residents." + x.id + ".";
            y.set(k + "first", x.first);
            y.set(k + "family", x.family);
            y.set(k + "female", x.female);
            y.set(k + "born", x.born);
            y.set(k + "home", x.home);
            y.set(k + "job", x.job);
            y.set(k + "spouse", x.spouse);
            y.set(k + "skin", x.skin);
            y.set(k + "kids", x.kids);
        }
        try {
            net.moddedrealms.smp.SafeYaml.save(y, file);
            dirty = false;
        } catch (IOException e) {
            t.plugin().getLogger().warning("Couldn't save residents.yml: " + e.getMessage());
        }
    }

    private static long today() {
        return System.currentTimeMillis() / 86_400_000L;
    }

    /** Age in real days; children grow up after town.residents.childhood-days, adults become elders after elder-days. */
    private Age age(Res x) {
        long d = today() - x.born;
        if (d < cfg().getLong("town.residents.childhood-days", 21)) return Age.CHILD;
        if (d >= cfg().getLong("town.residents.elder-days", 120)) return Age.ELDER;
        return Age.ADULT;
    }

    public int population() {
        return all.size();
    }

    /** Extra demand for the shops: every resident buys a little. */
    double demand() {
        return enabled() ? all.size() * cfg().getDouble("town.residents.demand-each", 0.015) : 0;
    }

    // ------------------------------------------------------------------ founding the town

    private List<Plots.Plot> freeHomes() {
        List<Plots.Plot> out = new ArrayList<>();
        for (Plots.Plot p : t.plots().all()) {
            if (p.kind != Plots.Kind.HOME || !p.built || p.owner != null || !Meadowbrook.ID.equals(p.town)) continue;
            boolean taken = false;
            for (Res x : all.values()) if (p.id.equals(x.home)) taken = true;
            if (!taken) out.add(p);
        }
        return out;
    }

    private Res person(String family, boolean female, long born) {
        Res x = new Res();
        x.first = (female ? FEMALE : MALE)[r.nextInt(female ? FEMALE.length : MALE.length)];
        x.family = family;
        x.female = female;
        x.born = born;
        String base = (x.first + "-" + x.family).toLowerCase();
        String id = base;
        for (int n = 2; all.containsKey(id); n++) id = base + "-" + n;
        x.id = id;
        all.put(id, x);
        dirty = true;
        return x;
    }

    private String skinFor(Res x) {
        return switch (age(x)) {
            case CHILD -> Pack.pick("child");
            case ELDER -> "elder";
            default -> Pack.pick(x.female ? "resident_f" : "resident_m");
        };
    }

    /** A family moves into each free home the first time (and whenever homes free up). */
    private void settle() {
        int max = cfg().getInt("town.residents.max", 80);
        long now = today();
        long child = cfg().getLong("town.residents.childhood-days", 21), elder = cfg().getLong("town.residents.elder-days", 120);
        for (Plots.Plot home : freeHomes()) {
            if (all.size() >= max) break;
            String fam = FAMILY[r.nextInt(FAMILY.length)];
            Res a = person(fam, false, now - child - r.nextInt((int) (elder - child - 5)));
            a.home = home.id;
            if (r.nextInt(5) != 0) {
                Res b = person(fam, true, now - child - r.nextInt((int) (elder - child - 5)));
                b.home = home.id;
                a.spouse = b.id;
                b.spouse = a.id;
                int kids = r.nextInt(4);
                for (int i = 0; i < kids; i++) {
                    Res k = person(fam, r.nextBoolean(), now - r.nextInt((int) child));
                    k.home = home.id;
                    a.kids.add(k.id);
                    b.kids.add(k.id);
                }
            }
            if (r.nextInt(4) == 0) {
                Res g = person(fam, r.nextBoolean(), now - elder - r.nextInt(30));
                g.home = home.id;
            }
        }
        for (Res x : all.values()) if (x.skin == null) x.skin = skinFor(x);
    }

    // ------------------------------------------------------------------ jobs

    /** Unemployed adults take the best-paid open job; a business whose cash runs dry loses its residents. */
    private void jobs() {
        Businesses bz = t.businesses();
        for (Res x : all.values()) {
            if (age(x) != Age.ADULT) {
                if (x.job != null) {
                    x.job = null;
                    dirty = true;
                }
                continue;
            }
            Businesses.Biz b = x.job == null ? null : bz.get(x.job);
            if (b != null && b.open() && b.kind.legal && (b.cash > 0 || !b.playerOwned())) continue;
            if (x.job != null) {
                x.job = null;
                dirty = true;
            }
            // honest work only, wherever there's room; the emptiest workplace first
            Businesses.Biz best = null;
            int fewest = Integer.MAX_VALUE;
            double bestWage = wage(null);
            for (Businesses.Biz c : bz.all()) {
                if (c.rival || !c.open() || !c.jobsOpen || !c.kind.legal || !Meadowbrook.ID.equals(c.town == null ? Meadowbrook.ID : c.town)) continue;
                int n = employed(c);
                if (n >= c.level * 2 + 1) continue;
                if (c.playerOwned() && c.cash < bestWage * 3) continue;
                if (n < fewest || n == fewest && r.nextBoolean()) {
                    best = c;
                    fewest = n;
                }
            }
            if (best != null) {
                x.job = best.id;
                dirty = true;
                if (best.owner != null) {
                    Player o = Bukkit.getPlayer(best.owner);
                    if (o != null) o.sendMessage(SmpPlugin.mm("<gold>[" + best.name + "] <white>" + x.name() + "</white> <gray>(a Meadowbrook resident) now works for you at "
                            + Money.fmt(bestWage) + " a cycle. <dark_gray>/business fire " + x.first + " to let them go."));
                }
            }
        }
    }

    /** What a resident is paid per economy cycle (the same everywhere). */
    double wage(Businesses.Biz b) {
        return cfg().getDouble("town.residents.wage", 20);
    }

    int employed(Businesses.Biz b) {
        int n = 0;
        for (Res x : all.values()) if (b.id.equals(x.job)) n++;
        return n;
    }

    /** Extra output from residents working at a business (each adds 25%, like NPC staff). */
    double bonus(Businesses.Biz b) {
        return enabled() ? 0.25 * employed(b) : 0;
    }

    /** Called by the economy cycle: residents at work get their wage. */
    void pay(Businesses.Biz b) {
        if (!enabled()) return;
        int n = employed(b);
        if (n == 0) return;
        double pay = wage(b) * n;
        b.cash -= pay;
        b.costs += pay;
    }

    /** /business fire <first name or full name> for a resident. */
    boolean fire(Businesses.Biz b, String name) {
        for (Res x : all.values()) {
            if (!b.id.equals(x.job)) continue;
            if (x.first.equalsIgnoreCase(name) || x.name().equalsIgnoreCase(name) || x.id.equalsIgnoreCase(name)) {
                x.job = null;
                dirty = true;
                return true;
            }
        }
        return false;
    }

    List<String> staffNames(Businesses.Biz b) {
        List<String> out = new ArrayList<>();
        for (Res x : all.values()) if (b.id.equals(x.job)) out.add(x.name());
        return out;
    }

    // ------------------------------------------------------------------ life

    /** Once a real day: births, weddings, growing up and moving out, old age. */
    private void day() {
        long now = today();
        if (now == lastDay) return;
        lastDay = now;
        dirty = true;
        List<Res> list = new ArrayList<>(all.values());
        for (Res x : list) {
            if (!all.containsKey(x.id)) continue;
            Age a = age(x);
            // births
            if (a == Age.ADULT && x.female && x.spouse != null && all.containsKey(x.spouse) && x.kids.size() < 3 && all.size() < cfg().getInt("town.residents.max", 80)
                    && r.nextDouble() < cfg().getDouble("town.residents.birth-chance", 0.04)) {
                Res k = person(x.family, r.nextBoolean(), now);
                k.home = x.home;
                k.skin = Pack.pick("child");
                x.kids.add(k.id);
                Res d = all.get(x.spouse);
                if (d != null) d.kids.add(k.id);
                t.news("The " + x.family + " family welcomed a baby " + (k.female ? "girl" : "boy") + ", " + k.first + ".");
            }
            // growing up: an adult child moves into a free home if there is one
            if (a == Age.ADULT && x.skin != null && x.skin.startsWith("child")) {
                x.skin = skinFor(x);
                List<Plots.Plot> free = freeHomes();
                if (!free.isEmpty()) {
                    x.home = free.get(r.nextInt(free.size())).id;
                    t.news(x.name() + " is all grown up and has moved into a home of their own.");
                }
            }
            if (a == Age.ELDER && !"elder".equals(x.skin)) x.skin = "elder";
            // old age
            if (a == Age.ELDER && today() - x.born > cfg().getLong("town.residents.elder-days", 120) + 20 && r.nextDouble() < 0.02) {
                t.news(x.name() + " of Meadowbrook has passed away, aged and much loved. The town mourns.");
                remove(x);
            }
        }
        // a wedding now and then
        if (r.nextDouble() < cfg().getDouble("town.residents.wedding-chance", 0.25)) {
            List<Res> single = new ArrayList<>();
            for (Res x : all.values()) if (age(x) == Age.ADULT && x.spouse == null) single.add(x);
            for (Res a : single) {
                Res b = null;
                for (Res c : single) if (c != a && c.female != a.female && !c.family.equals(a.family)) b = c;
                if (b == null) continue;
                Res wife = a.female ? a : b, husband = a.female ? b : a;
                wife.family = husband.family;
                wife.home = husband.home;
                wife.spouse = husband.id;
                husband.spouse = wife.id;
                t.news("Wedding bells! " + husband.first + " and " + wife.first + " " + husband.family + " were married in the square.");
                break;
            }
        }
    }

    private void remove(Res x) {
        all.remove(x.id);
        if (x.walker != null) t.unregister(x.walker, false);
        for (Res o : all.values()) {
            if (x.id.equals(o.spouse)) o.spouse = null;
            o.kids.remove(x.id);
        }
        dirty = true;
    }

    // ------------------------------------------------------------------ the day's routine

    /** Where someone wants to be at this time of day (world time: 0 = 6am, 6000 = noon, 12000 = 6pm). */
    private Place want(Res x, long time) {
        Age a = age(x);
        if (time >= 13000 || time < 0) return Place.HOME;
        return switch (a) {
            case CHILD -> time >= 1000 && time < 10000 ? Place.SQUARE : Place.HOME;
            case ELDER -> time >= 2000 && time < 9500 ? Place.SQUARE : Place.HOME;
            default -> {
                if (x.job != null && time < 9000) yield Place.WORK;
                if (x.job == null && time >= 1000 && time < 9000) yield Place.SQUARE;
                if (time < 11000) yield x.shopped ? Place.HOME : Place.SHOP;
                yield Math.floorMod(x.id.hashCode(), 3) == 0 ? Place.TAVERN : Place.HOME;
            }
        };
    }

    private World world() {
        return Bukkit.getWorld(t.meadowbrook().data().getString("world", "world"));
    }

    private Location square() {
        var c = t.meadowbrook().data().getIntegerList("centre");
        World w = world();
        return c.size() < 3 || w == null ? null : new Location(w, c.get(0) + 0.5, c.get(1) + 1, c.get(2) + 0.5);
    }

    /** A standing spot on the edge of a home plot (the garden, outside the walls). */
    private Location homeSpot(Res x) {
        Plots.Plot p = x.home == null ? null : t.plots().get(x.home);
        World w = world();
        if (p == null || w == null) return square();
        int[][] edges = {{(p.x0 + p.x1) / 2, p.z0}, {(p.x0 + p.x1) / 2, p.z1}, {p.x0, (p.z0 + p.z1) / 2}, {p.x1, (p.z0 + p.z1) / 2}};
        Location sq = square();
        int[] best = edges[0];
        double bd = Double.MAX_VALUE;
        for (int[] e : edges) {
            double d = sq == null ? 0 : Math.hypot(e[0] - sq.getX(), e[1] - sq.getZ());
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return stand(w, best[0], p.floor, best[1]);
    }

    private Location stand(World w, int x, int y, int z) {
        for (int dy = 4; dy >= -3; dy--) {
            Block b = w.getBlockAt(x, y + dy, z);
            if (b.getType().isSolid() && b.getRelative(0, 1, 0).isPassable() && b.getRelative(0, 2, 0).isPassable()) return b.getLocation().add(0.5, 1, 0.5);
        }
        return new Location(w, x + 0.5, y + 1, z + 0.5);
    }

    private Location placeLoc(Res x, Place p) {
        return switch (p) {
            case HOME -> homeSpot(x);
            case WORK -> {
                Businesses.Biz b = x.job == null ? null : t.businesses().get(x.job);
                Location l = b == null ? null : t.businesses().anchor(b);
                yield l == null ? square() : l;
            }
            case SHOP -> {
                List<Businesses.Biz> shops = new ArrayList<>();
                for (Businesses.Biz b : t.businesses().all()) if (b.open() && b.kind.output.consumer && t.businesses().anchor(b) != null && !b.rival) shops.add(b);
                if (shops.isEmpty()) yield square();
                Businesses.Biz s = shops.get(r.nextInt(shops.size()));
                x.shop = s.id;
                yield t.businesses().anchor(s);
            }
            case TAVERN -> {
                for (Businesses.Biz b : t.businesses().all()) if (b.kind == Econ.Kind.TAVERN && b.open() && t.businesses().anchor(b) != null) yield t.businesses().anchor(b);
                yield square();
            }
            case SQUARE -> {
                Location s = square();
                yield s == null ? null : stand(s.getWorld(), s.getBlockX() + r.nextInt(21) - 10, s.getBlockY() - 1, s.getBlockZ() + r.nextInt(21) - 10);
            }
        };
    }

    /** Every second. */
    void second() {
        if (++tick % 2 != 0) return;
        if (!enabled()) return;
        World w = world();
        if (w == null) return;
        if (tick % 60 == 0 || all.isEmpty()) {
            settle();
            day();
            jobs();
            save();
        }
        long time = w.getTime();
        long worldDay = w.getFullTime() / 24000;
        if (worldDay != lastWorldDay) {
            lastWorldDay = worldDay;
            for (Res x : all.values()) x.shopped = false;
        }
        long now = System.currentTimeMillis();
        int walking = 0;
        for (Res x : all.values()) if (x.walker != null) walking++;
        int maxWalk = cfg().getInt("town.residents.max-walking", 24);
        for (Res x : new ArrayList<>(all.values())) {
            if (x.walker != null && !x.walker.valid()) x.walker = null;
            Place p = want(x, time);
            if (x.insideUntil > now) continue;
            if (p != x.place) {
                // set off: from where they are now to where they want to be
                Location from = x.walker != null ? x.walker.loc() : placeLoc(x, x.place);
                x.place = p;
                x.target = placeLoc(x, p);
                if (x.target == null) continue;
                if (x.walker == null && from != null && walking < maxWalk && (t.nearestPlayer(from, 64) != null || t.nearestPlayer(x.target, 64) != null)) {
                    x.walker = spawn(x, from);
                    if (x.walker != null) walking++;
                }
                if (x.walker != null) x.walker.moveTo(x.target, 0.8);
                continue;
            }
            if (x.walker == null) {
                // they're in the square while someone's watching: show them
                if (p == Place.SQUARE && walking < maxWalk) {
                    Location sq = square();
                    if (sq != null && t.nearestPlayer(sq, 48) != null && r.nextInt(6) == 0) {
                        Location at = placeLoc(x, Place.SQUARE);
                        if (at != null) {
                            x.walker = spawn(x, at);
                            if (x.walker != null) walking++;
                        }
                    }
                }
                continue;
            }
            Walker wk = x.walker;
            if (t.nearestPlayer(wk.loc(), 80) == null) {
                t.unregister(wk, false);
                x.walker = null;
                continue;
            }
            if (p == Place.SQUARE) {
                if (!wk.moving() && now > x.nextWander) {
                    Location g = placeLoc(x, Place.SQUARE);
                    if (g != null) wk.moveTo(g, 0.6);
                    x.nextWander = now + 6000 + r.nextInt(12000);
                }
                continue;
            }
            if (x.target != null && (wk.dist(x.target) < 3 || !wk.moving() && wk.dist(x.target) < 8)) {
                // arrived: go in
                if (p == Place.SHOP) {
                    x.shopped = true;
                    buy(x);
                    x.insideUntil = now + 40_000;
                }
                t.unregister(wk, false);
                x.walker = null;
            } else if (!wk.moving()) {
                wk.moveTo(x.target, 0.8);
            }
        }
    }

    /** A resident who reached a shop buys something there. */
    private void buy(Res x) {
        Businesses.Biz b = x.shop == null ? null : t.businesses().get(x.shop);
        if (b == null) return;
        Econ.Good g = b.kind.output;
        if (b.stock.getOrDefault(g, 0.0) < 1) return;
        b.stock.merge(g, -1.0, Double::sum);
        b.cash += b.price;
        b.revenue += b.price;
    }

    private Walker spawn(Res x, Location at) {
        if (at == null || !t.canSpawnWalkers(at.getWorld())) return null;
        Age a = age(x);
        String title = switch (a) {
            case CHILD -> "<gray>of the " + x.family + " family";
            case ELDER -> "<gray>Elder of Meadowbrook";
            default -> {
                Businesses.Biz b = x.job == null ? null : t.businesses().get(x.job);
                yield b == null ? "<gray>Meadowbrook resident" : "<gray>Works at " + b.name;
            }
        };
        Map<EquipmentSlot, ItemStack> gear = new java.util.EnumMap<>(EquipmentSlot.class);
        if (a == Age.CHILD && r.nextInt(3) == 0) gear.put(EquipmentSlot.HAND, new ItemStack(r.nextBoolean() ? Material.STICK : Material.POPPY));
        if (a == Age.ELDER) gear.put(EquipmentSlot.HAND, new ItemStack(Material.STICK));
        Walker w = Walker.spawn(t.key, at, Walker.Role.RESIDENT, "<white>" + x.name(), title, a == Age.CHILD ? 0.25 : a == Age.ELDER ? 0.15 : 0.21, 12, gear, x.skin);
        w.gang = x.id;
        if (a == Age.CHILD) {
            for (var e : List.of(w.legs, w.body)) {
                var sc = e.getAttribute(Attribute.SCALE);
                if (sc != null) sc.setBaseValue(0.6);
            }
        }
        return t.register(w);
    }

    // ------------------------------------------------------------------ talking

    void clicked(Walker w, Player p) {
        Res x = all.get(w.gang);
        if (x == null) {
            w.say(p, "Good day to you.");
            return;
        }
        Age a = age(x);
        Res sp = x.spouse == null ? null : all.get(x.spouse);
        int kids = 0;
        for (String k : x.kids) if (all.containsKey(k)) kids++;
        Businesses.Biz job = x.job == null ? null : t.businesses().get(x.job);
        Plots.Plot home = x.home == null ? null : t.plots().get(x.home);
        String line = switch (a) {
            case CHILD -> {
                Res parent = null;
                for (Res o : all.values()) if (o.kids.contains(x.id)) parent = o;
                yield r.nextBoolean() ? "I'm " + x.first + "! " + (parent != null ? "My " + (parent.female ? "mum" : "dad") + " is " + parent.first + "." : "") + " Want to play tag?"
                        : "When I grow up I'm going to " + (r.nextBoolean() ? "join the Watch" : "run my own bakery") + "!";
            }
            case ELDER -> "Ah, " + (t.tier(p) == TownManager.Tier.HERO ? "our hero" : "young one") + ". I'm " + x.name() + ". I've seen this town grow from a few huts, you know.";
            default -> {
                String s = "I'm " + x.name() + ". ";
                s += job != null ? "I work at " + job.name + ". " : "I'm looking for work, if you're hiring. ";
                if (sp != null) s += (sp.female ? "My wife " : "My husband ") + sp.first + (kids > 0 ? " and our " + kids + (kids == 1 ? " little one live" : " little ones live") : " lives") + " with me";
                else if (kids > 0) s += "My " + kids + (kids == 1 ? " child lives" : " children live") + " with me";
                else s += "I live alone";
                yield s + (home != null ? " at " + home.id + "." : ".");
            }
        };
        w.say(p, line);
    }

    // ------------------------------------------------------------------ commands

    /** /census: the town's families. */
    public void census(org.bukkit.command.CommandSender s) {
        Map<String, List<Res>> homes = new LinkedHashMap<>();
        for (Res x : all.values()) homes.computeIfAbsent(x.home == null ? "?" : x.home, k -> new ArrayList<>()).add(x);
        int working = 0;
        for (Res x : all.values()) if (x.job != null) working++;
        s.sendMessage(SmpPlugin.mm("<gold>Meadowbrook census: <white>" + all.size() + "</white> people in " + homes.size() + " homes, " + working + " in work."));
        int shown = 0;
        for (var e : homes.entrySet()) {
            if (shown++ >= 16) break;
            StringBuilder b = new StringBuilder(" <gray>" + e.getKey() + ": <white>");
            String fam = e.getValue().get(0).family;
            b.append("the ").append(fam).append("s</white> <dark_gray>(");
            List<String> names = new ArrayList<>();
            for (Res x : e.getValue()) names.add(x.first + (age(x) == Age.CHILD ? " (child)" : age(x) == Age.ELDER ? " (elder)" : ""));
            b.append(String.join(", ", names)).append(")");
            s.sendMessage(SmpPlugin.mm(b.toString()));
        }
    }

    /** Admin: make it a new day now (for testing births and weddings). */
    void forceDay() {
        lastDay = 0;
        day();
        jobs();
        dirty = true;
        save();
    }

    void stop() {
        for (Res x : all.values()) {
            if (x.walker != null) t.unregister(x.walker, false);
            x.walker = null;
        }
        dirty = true;
        save();
    }
}
