package net.moddedrealms.smp.town;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.title.Title;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * The town around spawn: reputation, crimes and wanted stars, arrests and jail time. Officers ({@link Officers})
 * patrol the police zone, townsfolk ({@link Townsfolk}) wander the town, {@link Crimes} spots what players do and
 * {@link Jail} holds the cells.
 */
public final class TownManager implements Listener {
    public enum Tier {
        OUTLAW("<dark_red>Outlaw"), ROGUE("<red>Rogue"), CITIZEN("<white>Citizen"), RESPECTED("<green>Respected"), HERO("<gold>Hero");
        public final String label;

        Tier(String label) {
            this.label = label;
        }
    }

    public record News(long at, String text) {}

    final SmpPlugin plugin;
    public final NamespacedKey key;
    public final NamespacedKey stolenKey;
    public final Money money = new Money();
    private final File dataFile;
    private final Map<UUID, TownData> data = new HashMap<>();
    private final Map<UUID, Walker> byEntity = new HashMap<>();
    private final List<Walker> walkers = new ArrayList<>();
    private final Deque<News> news = new ArrayDeque<>();
    private final Set<UUID> ourTeleports = new HashSet<>();
    private Officers officers;
    private Townsfolk townsfolk;
    private Jail jail;
    private final Map<String, Jail> jails = new java.util.LinkedHashMap<>();
    /** Every town with its own police zone: "spawn" (from config.yml) plus generated towns (towns.yml). */
    public record Zone(String id, String name, String world, double x, double z, double police, double town) {
        public double dist(Location l) {
            double dx = l.getX() - x, dz = l.getZ() - z;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }
    private final List<Zone> zones = new ArrayList<>();
    private Crimes crimes;
    private Protection protection;
    private Residents residents;
    private Ai ai;
    private Herald herald;
    private Court court;
    private int heraldTick;
    private Bounties bounties;
    private PoliceForce police;
    private Bandits bandits;
    private Bureau bureau;
    private Service service;
    private Bank bank;
    private Casino casino;
    private CasinoGames games;
    private Gangs gangs;
    private NpcGangs npcGangs;
    private Heists heists;
    private PrisonBreak prisonBreak;
    private Plots plots;
    private Meadowbrook meadowbrook;
    private Railway railway;
    private Businesses businesses;
    private Loans loans;
    private boolean warnedPeaceful;
    private boolean dirty;
    private int tick;

    // settings
    public boolean enabled;
    public String worldName;
    public double cx, cz, policeRadius, townRadius, sight, witnessRange;
    public int hostileStars;

    public TownManager(SmpPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "town");
        this.stolenKey = new NamespacedKey(plugin, "stolen");
        this.dataFile = new File(plugin.getDataFolder(), "town-data.yml");
        loadSettings();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection tr = y.getConfigurationSection("treasury");
        if (tr != null) for (String k : tr.getKeys(false)) treasury.put(k, tr.getDouble(k));
        ConfigurationSection ps = y.getConfigurationSection("players");
        if (ps != null) {
            for (String k : ps.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(k);
                    data.put(id, TownData.load(id, ps.getConfigurationSection(k)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        officers = new Officers(this);
        townsfolk = new Townsfolk(this);
        jail = new Jail(this);
        jails.put("spawn", jail);
        loadZones();
        crimes = new Crimes(this);
        bounties = new Bounties(this);
        police = new PoliceForce(this);
        bandits = new Bandits(this);
        bureau = new Bureau(this);
        service = new Service(this);
        bank = new Bank(this);
        casino = new Casino(this);
        games = new CasinoGames(this);
        gangs = new Gangs(this);
        npcGangs = new NpcGangs(this);
        heists = new Heists(this);
        prisonBreak = new PrisonBreak(this);
        plots = new Plots(this);
        protection = new Protection(this);
        residents = new Residents(this);
        ai = new Ai(this);
        herald = new Herald(this);
        court = new Court(this);
        meadowbrook = new Meadowbrook(this);
        railway = new Railway(this);
        businesses = new Businesses(this);
        loans = new Loans(this);
    }

    public void loadSettings() {
        FileConfiguration c = plugin.getConfig();
        enabled = c.getBoolean("town.enabled", true);
        worldName = c.getString("town.world", "world");
        cx = c.getDouble("town.center-x");
        cz = c.getDouble("town.center-z");
        policeRadius = c.getDouble("town.police-radius", 500);
        townRadius = c.getDouble("town.townsfolk-radius", 160);
        sight = c.getDouble("town.officer-sight", 24);
        witnessRange = c.getDouble("town.witness-range", 16);
        hostileStars = c.getInt("town.hostile-at-stars", 3);
        for (Crime cr : Crime.values()) {
            cr.stars = c.getInt("town.crimes." + cr.key() + ".stars", cr.defaultStars);
            cr.rep = c.getInt("town.crimes." + cr.key() + ".reputation", cr.defaultRep);
        }
        if (townsfolk != null) townsfolk.reload();
    }

    public void start() {
        var pm = Bukkit.getPluginManager();
        pm.registerEvents(this, plugin);
        pm.registerEvents(crimes, plugin);
        pm.registerEvents(protection, plugin);
        for (Jail j : jails.values()) pm.registerEvents(j, plugin);
        pm.registerEvents(townsfolk, plugin);
        pm.registerEvents(service, plugin);
        pm.registerEvents(bank, plugin);
        pm.registerEvents(casino, plugin);
        pm.registerEvents(games, plugin);
        pm.registerEvents(gangs, plugin);
        pm.registerEvents(npcGangs, plugin);
        pm.registerEvents(heists, plugin);
        pm.registerEvents(prisonBreak, plugin);
        pm.registerEvents(plots, plugin);
        pm.registerEvents(railway, plugin);
        pm.registerEvents(businesses, plugin);
        pm.registerEvents(businesses.work, plugin);
        // Leftovers from a crash (walkers are never meant to be saved).
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (isOurs(e)) e.remove();
        Bukkit.getScheduler().runTaskTimer(plugin, this::onTick, 20L, 1L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::second, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (dirty) save();
        }, 2400L, 2400L);
        for (Player p : Bukkit.getOnlinePlayers()) joined(p);
    }

    public void stop() {
        if (residents != null) residents.stop();
        for (Walker w : new ArrayList<>(walkers)) w.remove(false);
        walkers.clear();
        byEntity.clear();
        for (Jail j : jails.values()) j.shutdown();
        service.shutdown();
        bank.shutdown();
        heists.shutdown();
        prisonBreak.shutdown();
        gangs.save();
        plots.save();
        railway.shutdown();
        businesses.save();
        save();
    }

    // ------------------------------------------------------------------ accessors

    public SmpPlugin plugin() { return plugin; }
    public Officers officers() { return officers; }
    public Townsfolk townsfolk() { return townsfolk; }
    public Jail jail() { return jail; }
    /** The jail this player is (or was last) held in. */
    public Jail jailOf(TownData d) { return jails.getOrDefault(d.jailTown, jail); }
    public Jail jail(String id) { return jails.get(id); }
    public java.util.Collection<Jail> jails() { return jails.values(); }
    public List<Zone> zones() { return zones; }
    public Bounties bounties() { return bounties; }
    public PoliceForce police() { return police; }
    public Bandits bandits() { return bandits; }
    public Bureau bureau() { return bureau; }
    public Service service() { return service; }
    public Bank bank() { return bank; }
    public Casino casino() { return casino; }
    public CasinoGames games() { return games; }
    public Gangs gangs() { return gangs; }
    public NpcGangs npcGangs() { return npcGangs; }
    public Heists heists() { return heists; }
    public PrisonBreak prisonBreak() { return prisonBreak; }
    public Plots plots() { return plots; }
    Protection protection() { return protection; }

    /** Give generated buildings (or "all") the medieval look; players' plots are left alone. Returns a summary. */
    public String medieval(String which) {
        int swapped = 0, merlons = 0, n = 0;
        for (Protection.Area a : protection.built()) {
            if (!which.equals("all") && !a.name().equals(which)) continue;
            var b = a.box();
            boolean battlements = a.name().equals("jail") || a.name().equals("bank");
            Medieval m = Medieval.area(a.world(), (int) b.getMinX(), (int) b.getMinY(), (int) b.getMinZ(), (int) b.getMaxX() - 1, (int) b.getMaxY() - 1, (int) b.getMaxZ() - 1,
                    bl -> { Plots.Plot p = plots.at(bl.getLocation()); return p != null && p.owner != null; }, battlements);
            swapped += m.swapped;
            merlons += m.merlons;
            n++;
        }
        int renamed = 0;
        for (Protection.Area a : protection.built()) {
            if (!which.equals("all") && !a.name().equals(which)) continue;
            renamed += renameSigns(a);
        }
        if (which.equals("all")) renamed += renameData();
        return n == 0 ? "Nothing built under that name." : "Medieval look on " + n + " building" + (n == 1 ? "" : "s") + ": " + swapped + " blocks changed, " + merlons
                + " merlons added, " + renamed + " signs/names renamed.";
    }

    /** Floating signs (text displays) in a building's area get the medieval wording. */
    private int renameSigns(Protection.Area a) {
        int n = 0;
        var b = a.box();
        for (Entity e : a.world().getNearbyEntities(b, x -> x instanceof org.bukkit.entity.TextDisplay)) {
            org.bukkit.entity.TextDisplay d = (org.bukkit.entity.TextDisplay) e;
            String mm = SmpPlugin.MM.serialize(d.text());
            String nw = Medieval.text(mm);
            if (!nw.equals(mm)) {
                d.text(SmpPlugin.mm(nw));
                n++;
            }
        }
        return n;
    }

    /** NPC names and titles and business names saved before the medieval update. */
    private int renameData() {
        int n = 0;
        var npcs = plugin.npcs();
        for (var d : new ArrayList<>(npcs.all())) {
            String nn = Medieval.text(d.name), nt = Medieval.text(d.title);
            if (!java.util.Objects.equals(nn, d.name) || !java.util.Objects.equals(nt, d.title)) {
                d.name = nn;
                d.title = nt;
                npcs.refresh(d.id);
                n++;
            }
        }
        npcs.save();
        for (Businesses.Biz b : businesses.all()) {
            String nn = Medieval.text(b.name);
            if (!nn.equals(b.name)) {
                b.name = nn;
                n++;
            }
        }
        businesses.save();
        return n;
    }

    /** Fix fences, panes, walls, stairs and misplaced beds in one generated building (or "all"). Returns a summary. */
    public String repair(String which) {
        int shapes = 0, beds = 0, n = 0;
        for (Protection.Area a : protection.built()) {
            if (!which.equals("all") && !a.name().equals(which)) continue;
            var b = a.box();
            Repair r = Repair.area(a.world(), (int) b.getMinX(), (int) b.getMinY(), (int) b.getMinZ(), (int) b.getMaxX() - 1, (int) b.getMaxY() - 1, (int) b.getMaxZ() - 1);
            shapes += r.shapes;
            beds += r.beds;
            n++;
        }
        protection.reload();
        return n == 0 ? "Nothing built under that name." : "Repaired " + n + " building" + (n == 1 ? "" : "s") + ": " + shapes + " fences/panes/walls/stairs joined up, " + beds + " beds moved out of walls.";
    }
    public Meadowbrook meadowbrook() { return meadowbrook; }
    public Railway railway() { return railway; }
    public Businesses businesses() { return businesses; }
    public Loans loans() { return loans; }

    /** Extra demand from the town's residents (filled in when NPC families arrive). */
    public double residentsDemand() {
        return residents == null ? 0 : residents.demand();
    }

    public Residents residents() { return residents; }
    Ai ai() { return ai; }
    Herald herald() { return herald; }
    Court court() { return court; }

    public java.util.Set<UUID> knownPlayers() {
        return new java.util.HashSet<>(data.keySet());
    }
    public Collection<Walker> walkers() { return walkers; }

    public TownData data(Player p) {
        TownData d = data.computeIfAbsent(p.getUniqueId(), TownData::new);
        d.name = p.getName();
        return d;
    }

    public TownData dataIfKnown(UUID id) {
        return data.get(id);
    }

    public TownData data(UUID id) {
        return data.computeIfAbsent(id, TownData::new);
    }

    public void markDirty() {
        dirty = true;
    }

    public World world() {
        return Bukkit.getWorld(worldName);
    }

    public boolean inPoliceZone(Location l) {
        return zoneAt(l) != null;
    }

    public boolean inTown(Location l) {
        if (!enabled || l.getWorld() == null) return false;
        for (Zone z : zones) if (l.getWorld().getName().equals(z.world()) && z.dist(l) <= z.town()) return true;
        return false;
    }

    /** The town whose police zone this is in (the closest one if they overlap), or null. */
    public Zone zoneAt(Location l) {
        if (!enabled || l.getWorld() == null) return null;
        Zone best = null;
        double bd = Double.MAX_VALUE;
        for (Zone z : zones) {
            if (!l.getWorld().getName().equals(z.world())) continue;
            double d = z.dist(l);
            if (d <= z.police() && d < bd) {
                bd = d;
                best = z;
            }
        }
        return best;
    }

    /** Within a generated town (not spawn) plus some margin: no bandits or gang turf there. */
    public boolean nearOtherTown(Location l, double extra) {
        if (l.getWorld() == null) return false;
        for (Zone z : zones) if (!z.id().equals("spawn") && l.getWorld().getName().equals(z.world()) && z.dist(l) <= z.town() + extra) return true;
        return false;
    }

    private void loadZones() {
        zones.clear();
        zones.add(new Zone("spawn", "Spawn", worldName, cx, cz, policeRadius, townRadius));
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "towns.yml"));
        ConfigurationSection ts = y.getConfigurationSection("towns");
        if (ts == null) return;
        for (String id : ts.getKeys(false)) {
            ConfigurationSection s = ts.getConfigurationSection(id);
            zones.add(new Zone(id, s.getString("name", id), s.getString("world", worldName), s.getDouble("x"), s.getDouble("z"),
                    s.getDouble("police-radius", 250), s.getDouble("town-radius", 90)));
            if (!jails.containsKey(id)) jails.put(id, new Jail(this, id, "jail-" + id + ".yml", false));
        }
    }

    private final Map<String, Double> treasury = new HashMap<>();

    /** Rent, plot sales and (later) taxes go to the town's treasury. */
    public void townTreasury(String town, double amount) {
        treasury.merge(town, amount, Double::sum);
        dirty = true;
    }

    public double treasury(String town) {
        return treasury.getOrDefault(town, 0.0);
    }

    /** A generated town registers itself (and gets a jail of its own). */
    public Jail addZone(Zone z) {
        File f = new File(plugin.getDataFolder(), "towns.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        y.set("towns." + z.id() + ".name", z.name());
        y.set("towns." + z.id() + ".world", z.world());
        y.set("towns." + z.id() + ".x", z.x());
        y.set("towns." + z.id() + ".z", z.z());
        y.set("towns." + z.id() + ".police-radius", z.police());
        y.set("towns." + z.id() + ".town-radius", z.town());
        try {
            y.save(f);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save towns.yml: " + e.getMessage());
        }
        zones.removeIf(x -> x.id().equals(z.id()));
        zones.add(z);
        Jail j = jails.get(z.id());
        if (j == null) {
            j = new Jail(this, z.id(), "jail-" + z.id() + ".yml", false);
            jails.put(z.id(), j);
            Bukkit.getPluginManager().registerEvents(j, plugin);
        }
        return j;
    }

    public void removeZone(String id) {
        File f = new File(plugin.getDataFolder(), "towns.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        y.set("towns." + id, null);
        try {
            y.save(f);
        } catch (IOException ignored) {
        }
        zones.removeIf(x -> x.id().equals(id));
        Jail j = jails.get(id);
        if (j != null) j.reset();
    }

    private double flat(Location l) {
        double dx = l.getX() - cx, dz = l.getZ() - cz;
        return dx * dx + dz * dz;
    }

    /** Creative/spectator players, staff with mrsmp.lawless and jailed players are left alone. */
    public boolean exempt(Player p) {
        GameMode g = p.getGameMode();
        TownData d = data(p);
        return g == GameMode.CREATIVE || g == GameMode.SPECTATOR || p.hasPermission("mrsmp.lawless") || d.jailed() || d.inService() || p.isDead();
    }

    public boolean canSpawnWalkers(World w) {
        if (w.getDifficulty() == Difficulty.PEACEFUL) {
            if (!warnedPeaceful) plugin.getLogger().warning("Town NPCs need a difficulty above peaceful (they walk on an invisible skeleton).");
            warnedPeaceful = true;
            return false;
        }
        return true;
    }

    public boolean isNight(World w) {
        long t = w.getTime();
        return t >= 13000 && t < 23000;
    }

    // ------------------------------------------------------------------ walkers

    Walker register(Walker w) {
        walkers.add(w);
        byEntity.put(w.legs.getUniqueId(), w);
        byEntity.put(w.body.getUniqueId(), w);
        return w;
    }

    void unregister(Walker w, boolean poof) {
        walkers.remove(w);
        byEntity.remove(w.legs.getUniqueId());
        byEntity.remove(w.body.getUniqueId());
        w.remove(poof);
    }

    public Walker walker(Entity e) {
        return e == null ? null : byEntity.get(e.getUniqueId());
    }

    public boolean isOurs(Entity e) {
        return e.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    public List<Walker> walkers(Walker.Role role) {
        List<Walker> out = new ArrayList<>();
        for (Walker w : walkers) if (w.role == role) out.add(w);
        return out;
    }

    public int countNear(Location l, double r, boolean police) {
        int n = 0;
        for (Walker w : walkers) {
            boolean match = police ? w.role == Walker.Role.OFFICER : Townsfolk.civilian(w);
            if (match && w.dist(l) <= r) n++;
        }
        return n;
    }

    private void onTick() {
        tick++;
        for (Walker w : new ArrayList<>(walkers)) {
            if (!w.valid()) {
                unregister(w, false);
                continue;
            }
            w.sync();
        }
        railway.tick();
        if (!enabled) return;
        if (tick % 10 == 0) officers.think();
        if (tick % 10 == 5) townsfolk.think();
        if (tick % 40 == 0) officers.spawnTick();
        if (tick % 60 == 30) townsfolk.spawnTick();
        if (tick % 10 == 3) bandits.think();
        if (tick % 10 == 7) bureau.think();
        if (tick % 600 == 300) bandits.spawnTick();
        if (tick % 200 == 100) bureau.tick();
        if (tick % 10 == 1) casino.thinkPatrons();
        if (tick % 10 == 9) npcGangs.think();
        if (tick % 600 == 450) npcGangs.spawnTick();
    }

    // ------------------------------------------------------------------ every second

    private void second() {
        long now = System.currentTimeMillis();
        long decay = plugin.getConfig().getLong("town.wanted-decay-seconds", 60) * 1000L;
        for (Player p : Bukkit.getOnlinePlayers()) {
            TownData d = data(p);
            if (d.jailed()) {
                jailOf(d).secondInJail(p, d);
                continue;
            }
            if (d.inService()) {
                service.second(p, d);
                continue;
            }
            police.second(p, d);
            if (d.stars <= 0) continue;
            // An arrest order runs out: still close by = compliant enough, otherwise they ran.
            if (d.orderedAt > 0 && !d.hostile && now - d.orderedAt > 10_000) {
                Walker o = officers.nearest(p.getLocation(), 64);
                if (o != null && o.dist(p.getLocation()) <= 5) {
                    arrest(p, o, false);
                    continue;
                }
                d.orderedAt = 0;
                commit(p, Crime.RESISTING, p.getLocation(), "resist", null, true);
                d.hostile = true;
            }
            long myDecay = gangs.hasPerk(p, "lookouts") ? decay * 3 / 4 : decay;
            if (now - d.lastSeenByPolice > myDecay && now - d.lastDecay > myDecay) {
                d.stars--;
                d.lastDecay = now;
                dirty = true;
                if (d.stars <= 0) {
                    clearWanted(d);
                    p.sendMessage(SmpPlugin.mm("<green>The Watch has lost your trail. You're no longer wanted."));
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.2f);
                    continue;
                }
            }
            String order = d.orderedAt > 0 && !d.hostile ? "  <yellow>Crouch to surrender: " + Math.max(0, 10 - (now - d.orderedAt) / 1000) + "s" : "";
            p.sendActionBar(SmpPlugin.mm("<red><bold>WANTED</bold> " + stars(d.stars) + " <gray>(" + d.reason + ") <gold>" + Money.fmt(bounties.of(d))
                    + (d.caseOpen ? " <dark_red>[Inquisition]" : "") + order));
        }
        for (Jail j : jails.values()) j.second();
        bank.second();
        casino.second();
        games.second();
        gangs.second();
        heists.second();
        prisonBreak.second();
        plots.second();
        businesses.second();
        residents.second();
        if (++heraldTick % 60 == 0) herald.minute();
        loans.second();
        if (tick % 100 == 0) meadowbrook.tickClock();
    }

    public static String stars(int n) {
        return "<red>" + "★".repeat(Math.max(0, n)) + "<dark_gray>" + "☆".repeat(Math.max(0, 5 - n));
    }

    // ------------------------------------------------------------------ crimes

    /**
     * Something illegal happened. Reputation drops either way; the wanted level only rises if an officer saw it
     * (straight away) or a townsperson did (they shout for the police a moment later).
     *
     * @param victimKey de-duplicates repeats (hitting the same player five times is one assault)
     * @param selfWitness a townsperson who was the victim and can report it
     * @param certainWitness the crime was against the police themselves
     */
    public void commit(Player p, Crime c, Location where, String victimKey, Walker selfWitness, boolean certainWitness) {
        commit(p, c, where, victimKey, selfWitness, certainWitness, null);
    }

    /** @param unaware a victim who didn't notice (a successful pickpocket) and so can't be the one who reports it */
    public void commit(Player p, Crime c, Location where, String victimKey, Walker selfWitness, boolean certainWitness, Walker unaware) {
        if (!enabled || exempt(p) && c != Crime.RESISTING || !inPoliceZone(where)) return;
        TownData d = data(p);
        long now = System.currentTimeMillis();
        String k = c.name() + ":" + victimKey;
        Long last = d.recentCrimes.get(k);
        if (last != null && now - last < 30_000) return;
        d.recentCrimes.put(k, now);
        d.recentCrimes.values().removeIf(t -> now - t > 120_000);
        addRep(p, -c.rep);
        police.fire(p, d);
        if (c.stars <= 0) return;
        if (certainWitness) {
            wanted(p, c, officers.nearest(where, 8));
            return;
        }
        Walker cop = officers.witness(p, where);
        if (cop != null) {
            wanted(p, c, cop);
            return;
        }
        Object civ = selfWitness != null && selfWitness.valid() ? selfWitness : townsfolk.witness(p, where, unaware);
        if (civ == null) {
            p.sendMessage(SmpPlugin.mm("<dark_gray><italic>Nobody seems to have noticed..."));
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || data(p).jailed()) return;
            townsfolk.report(civ, p, c);
            wanted(p, c, null);
        }, 30L);
    }

    /** Raise the wanted level and send the police. */
    public void wanted(Player p, Crime c, Walker sawIt) {
        TownData d = data(p);
        long now = System.currentTimeMillis();
        int before = d.stars;
        int next = Math.max(before, c.stars) + (before > 0 && c.stars > 0 ? 1 : 0);
        d.stars = Math.min(5, Math.max(next, before));
        if (before == 0 || c.stars >= Crime.valueOf(reasonCrime(d)).stars) d.reason = c.label;
        d.lastSeenByPolice = now;
        d.lastDecay = now;
        if (d.stars >= hostileStars || c == Crime.RESISTING || c == Crime.ASSAULT_OFFICER || c == Crime.COP_KILLING) d.hostile = true;
        dirty = true;
        if (d.stars != before) {
            p.showTitle(Title.title(SmpPlugin.mm("<red><bold>WANTED"), SmpPlugin.mm(stars(d.stars) + " <gray>" + c.label),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1600), Duration.ofMillis(400))));
            p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 1f, 0.8f);
            p.sendMessage(SmpPlugin.mm("<red>★ " + (sawIt != null ? sawIt.name + " <red>saw that!" : "Someone called the Watch!")
                    + " You're wanted for <white>" + c.label + "</white>. " + (d.hostile
                    ? "<gray>The guards will use force. Crouch next to one to give yourself up."
                    : "<gray>Crouch next to a guard to give yourself up, or get away and lie low.")));
            if (before == 0) news(p.getName() + " is wanted for " + c.label + "!");
            police.alert(p, d, c);
            bounties.posted(p, d, before);
        }
        bureau.maybeOpen(p, d, c);
        officers.radio(p, sawIt);
    }

    private static String reasonCrime(TownData d) {
        for (Crime c : Crime.values()) if (c.label.equals(d.reason)) return c.name();
        return Crime.CONTRABAND.name();
    }

    void clearWanted(TownData d) {
        d.stars = 0;
        d.reason = "";
        d.hostile = false;
        d.orderedAt = 0;
        dirty = true;
    }

    /** An officer catches up with a wanted player who isn't violent yet: they get 10 seconds to surrender. */
    public void orderArrest(Player p, Walker officer) {
        TownData d = data(p);
        if (d.orderedAt > 0 || d.hostile) return;
        d.orderedAt = System.currentTimeMillis();
        officer.say(p, "Stop right there, " + p.getName() + "! You're under arrest for " + d.reason + ".");
        p.showTitle(Title.title(SmpPlugin.mm("<blue><bold>POLICE!"), SmpPlugin.mm("<yellow>Crouch to surrender"),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2000), Duration.ofMillis(300))));
        p.sendMessage(Component.text().append(SmpPlugin.mm("<gray>   Crouch, or click: "))
                .append(SmpPlugin.mm("<green><bold>[Surrender]").clickEvent(ClickEvent.runCommand("/surrender")))
                .append(SmpPlugin.mm("  <gold>[Bribe " + Money.fmt(d.stars * plugin.getConfig().getDouble("town.bribe.per-star", 300)) + "]")
                        .clickEvent(ClickEvent.runCommand("/bribe"))).build());
    }

    public boolean surrender(Player p) {
        TownData d = data(p);
        if (d.stars <= 0 || d.jailed()) return false;
        Walker o = officers.nearest(p.getLocation(), 8);
        if (o == null) o = bureau.nearest(p.getLocation(), 8);
        if (o == null) return false;
        o.say(p, "Smart choice. Hands where I can see them.");
        arrest(p, o, false, null);
        return true;
    }

    /** Wanted player is caught: fine, jail time, wanted cleared. busted = beaten down instead of giving up. */
    public void arrest(Player p, Walker by, boolean busted) {
        arrest(p, by, busted, null);
    }

    /**
     * @param arrester a player who made the arrest (citizen's arrest or player police): they get the bounty
     */
    public void arrest(Player p, Walker by, boolean busted, Player arrester) {
        TownData d = data(p);
        if (d.jailed() || d.inService()) return;
        var c = plugin.getConfig();
        boolean defaulted = loans.seize(p, d);
        boolean bureauArrest = defaulted || by != null && by.role == Walker.Role.AGENT || d.caseOpen && d.stars <= 0;
        int stars = Math.max(1, d.stars);
        int max = c.getInt("town.arrest.max-seconds", 300);
        int secs = bureauArrest ? max
                : Math.min(max, stars * c.getInt("town.arrest.seconds-per-star", 60) + (busted ? c.getInt("town.arrest.busted-extra-seconds", 60) : 0));
        double fine = money.take(p, stars * c.getDouble("town.arrest.fine-per-star", 100));
        String reason = d.reason.isEmpty() ? (d.caseOpen ? d.caseReason : "breaking the law") : d.reason;
        if (reason.isEmpty()) reason = "breaking the law";
        d.offences++;
        d.arrests++;
        bounties.payout(p, d, d.stars, arrester);
        police.credit(p, d, stars, arrester);
        d.damagedBy.clear();
        clearWanted(d);
        officers.forget(p);
        int evidence = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            var it = inv.getItem(i);
            if (it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(stolenKey, PersistentDataType.INTEGER)) {
                evidence += it.getAmount();
                inv.setItem(i, null);
            }
        }
        if (evidence > 0) p.sendMessage(SmpPlugin.mm("<gray>The watch took " + evidence + " stolen item" + (evidence == 1 ? "" : "s") + " as evidence."));
        bureau.close(d, p, d.caseOpen ? "<gray>The Inquisition has closed your case." : null);
        String by2 = arrester != null ? arrester.getName() : bureauArrest ? "Inquisitors" : "The watch";
        news(by2 + " hauled " + p.getName() + " off to jail for " + reason + ".");
        if (Bukkit.getPluginManager().isPluginEnabled("ModdedrealmsGuard") && c.getBoolean("town.bodycam", true)) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "replay save " + p.getName() + " 60 Town Watch bodycam: arrest for " + reason);
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 4, false, false));
        if (busted) {
            var maxHp = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            p.setHealth(Math.min(maxHp == null ? 20 : maxHp.getValue(), Math.max(p.getHealth(), 8)));
            p.setFireTicks(0);
        }
        p.showTitle(Title.title(SmpPlugin.mm(busted ? "<red><bold>BUSTED" : "<blue><bold>ARRESTED"), SmpPlugin.mm("<gray>" + reason),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2500), Duration.ofMillis(500))));
        p.playSound(p.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 0.7f);
        if (by != null) by.swing();
        Zone here = zoneAt(p.getLocation());
        Jail jl = here != null && jails.containsKey(here.id()) && jails.get(here.id()).ready() ? jails.get(here.id()) : jail;
        if (!jl.ready()) {
            p.sendMessage(SmpPlugin.mm("<gray>There's no jail yet, so you're let off with a fine of <white>" + Money.fmt(fine) + "</white>. Behave."));
            dirty = true;
            return;
        }
        d.jailLeft = secs;
        d.jailTown = jl.id;
        d.cell = jl.freeCell();
        dirty = true;
        court.charged(p, new Court.Charge(reason, stars, busted, evidence, bureauArrest, secs));
        p.sendMessage(SmpPlugin.mm("<gray>Sentence: <white>" + fmtTime(secs) + "</white> in jail (served while online)"
                + (fine > 0 ? ", fine <white>" + Money.fmt(fine) + "</white>" : "") + "."));
        final int arrestedStars = stars;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && data(p).jailed()) {
                jailOf(data(p)).toCell(p, data(p));
                jailOf(data(p)).bailHint(p, data(p));
                service.offer(p, data(p), arrestedStars);
            }
        }, 40L);
    }

    public void release(Player p, String why) {
        TownData d = data(p);
        d.jailLeft = 0;
        d.cell = -1;
        dirty = true;
        Location out = jailOf(d).releasePoint();
        if (out == null) out = jail.releasePoint();
        if (out != null) teleport(p, out);
        p.sendMessage(SmpPlugin.mm("<green>" + why + " <gray>You're free to go. Stay out of trouble."));
        p.playSound(p.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 1f, 1f);
    }

    public void teleport(Player p, Location l) {
        ourTeleports.add(p.getUniqueId());
        try {
            p.teleport(l);
        } finally {
            ourTeleports.remove(p.getUniqueId());
        }
    }

    boolean ourTeleport(Player p) {
        return ourTeleports.contains(p.getUniqueId());
    }

    public static String fmtTime(int secs) {
        return secs / 60 + ":" + String.format("%02d", secs % 60);
    }

    // ------------------------------------------------------------------ reputation

    public void addRep(Player p, int delta) {
        if (delta == 0) return;
        TownData d = data(p);
        Tier before = tier(d.rep);
        d.rep = Math.max(-1000, Math.min(1000, d.rep + delta));
        dirty = true;
        Tier after = tier(d.rep);
        if (after != before) {
            p.sendMessage(SmpPlugin.mm(("<gray>Your standing in town is now " + after.label + "<gray>. " + tierPerk(after)).trim()));
            p.playSound(p.getLocation(), after.ordinal() > before.ordinal() ? Sound.ENTITY_PLAYER_LEVELUP : Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
        }
    }

    public Tier tier(int rep) {
        var c = plugin.getConfig();
        if (rep >= c.getInt("town.reputation.hero", 300)) return Tier.HERO;
        if (rep >= c.getInt("town.reputation.respected", 100)) return Tier.RESPECTED;
        if (rep <= c.getInt("town.reputation.outlaw", -300)) return Tier.OUTLAW;
        if (rep <= c.getInt("town.reputation.rogue", -50)) return Tier.ROGUE;
        return Tier.CITIZEN;
    }

    public Tier tier(Player p) {
        return tier(data(p).rep);
    }

    public String tierPerk(Tier t) {
        return switch (t) {
            case HERO -> "Merchants give you their best prices and the watch salutes you.";
            case RESPECTED -> "Merchants give you a discount, and you can join the Town Watch at the guardhouse (/police).";
            case CITIZEN -> "";
            case ROGUE -> "Merchants charge you more... but a shady figure might want to meet you at night.";
            case OUTLAW -> "Townsfolk avoid you and merchants won't trade. The Fence is your friend now.";
        };
    }

    /** Price multiplier for merchants; 0 = refuses to trade. */
    public double priceFactor(Player p) {
        return switch (tier(p)) {
            case HERO -> 0.8;
            case RESPECTED -> 0.9;
            case CITIZEN -> 1.0;
            case ROGUE -> 1.2;
            case OUTLAW -> 0;
        };
    }

    // ------------------------------------------------------------------ news (for gossip)

    public void news(String text) {
        news.addFirst(new News(System.currentTimeMillis(), text));
        while (news.size() > 25) news.removeLast();
    }

    public List<News> recentNews(long maxAgeMs) {
        long now = System.currentTimeMillis();
        List<News> out = new ArrayList<>();
        for (News n : news) if (now - n.at() <= maxAgeMs) out.add(n);
        return out;
    }

    // ------------------------------------------------------------------ persistence

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : treasury.entrySet()) y.set("treasury." + e.getKey(), e.getValue());
        for (TownData d : data.values()) if (d.worthSaving()) d.save(y.createSection("players." + d.id));
        try {
            y.save(dataFile);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save town-data.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ events: walkers

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    /** A monster hurting a player in town: the nearest guards come to help. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobHitsPlayer(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Entity d = e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Entity sh ? sh : e.getDamager();
        if (!(d instanceof org.bukkit.entity.Enemy) || !(d instanceof LivingEntity mob) || isOurs(d)) return;
        if (!inPoliceZone(p.getLocation()) || data(p).jailed()) return;
        officers.defend(mob, p.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWalkerDamage(EntityDamageEvent e) {
        Walker w = walker(e.getEntity());
        if (w == null) return;
        e.setCancelled(true);
        if (!(e instanceof EntityDamageByEntityEvent by)) return;
        Entity mobHit = by.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Entity sh ? sh : by.getDamager();
        if (w.role == Walker.Role.OFFICER && mobHit instanceof org.bukkit.entity.Enemy && mobHit instanceof LivingEntity mob && !isOurs(mobHit)) {
            officers.defend(mob, w.loc());
            return;
        }
        Player p = attacker(by.getDamager());
        if (p == null || p.getGameMode() == GameMode.SPECTATOR) return;
        if (data(p).jailed()) return;
        switch (w.role) {
            case OFFICER -> officers.hit(w, p, by.getDamage());
            case AGENT -> bureau.hit(w, p, by.getDamage());
            case BANDIT -> bandits.hit(w, p, by.getDamage());
            case PATRON -> casino.patronAttacked(w, p, by.getDamage());
            case GANGSTER -> npcGangs.hit(w, p, by.getDamage());
            case COURIER -> heists.courierHit(w, p, by.getDamage());
            case WORKER, CUSTOMER -> townsfolk.hit(w, p, by.getDamage());
            default -> townsfolk.hit(w, p, by.getDamage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWalkerClick(PlayerInteractEntityEvent e) {
        Walker w = walker(e.getRightClicked());
        if (w == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        Long last = w.talked.get(p.getUniqueId());
        if (last != null && now - last < 700) return;
        w.talked.put(p.getUniqueId(), now);
        switch (w.role) {
            case OFFICER -> officers.clicked(w, p);
            case AGENT -> bureau.clicked(w, p);
            case BANDIT -> w.say(p, "Back off, unless you want trouble.");
            case PATRON -> casino.patronClicked(w, p);
            case GANGSTER -> w.say(p, npcGangs.ally(p, w.gang) ? "Good to see you, friend." : "Keep walking.");
            case COURIER -> w.say(p, "Official business. Stand aside.");
            case WORKER -> {
                Businesses.Biz b = businesses.get(w.gang);
                w.say(p, b == null ? "Busy, busy!" : "Can't stop, I'm on shift at " + b.name + ". They're hiring, you know: /jobs");
            }
            case CUSTOMER -> {
                Businesses.Biz b = businesses.get(w.gang);
                w.say(p, b == null ? "Just browsing." : "Off to " + b.name + " for some " + b.kind.output.label.toLowerCase() + ". Best in town, they say.");
            }
            case RESIDENT -> residents.clicked(w, p);
            default -> townsfolk.clicked(w, p);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWalkerClickAt(PlayerInteractAtEntityEvent e) {
        if (walker(e.getRightClicked()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (e.getTarget() != null && isOurs(e.getTarget())) e.setCancelled(true);
        else if (isOurs(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) {
        if (isOurs(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) {
        if (isOurs(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) if (isOurs(en) && walker(en) == null) en.remove();
    }

    // ------------------------------------------------------------------ events: players

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        joined(e.getPlayer());
    }

    private void joined(Player p) {
        TownData d = data(p);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) loans.joined(p);
        }, 40L);
        if (d.jailed()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                Jail jl = jailOf(d);
                if (!jl.ready()) {
                    release(p, "The jail was closed.");
                    return;
                }
                jl.toCell(p, d);
                p.sendMessage(SmpPlugin.mm("<gray>You still have <white>" + fmtTime(d.jailLeft) + "</white> to serve."));
                jl.bailHint(p, d);
            }, 5L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        TownData d = data(e.getPlayer());
        d.orderedAt = 0;
        if (d.stars > 0 && d.hostile) d.lastSeenByPolice = System.currentTimeMillis();
        dirty = true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Player p = e.getPlayer();
        TownData d = data(p);
        if (d.stars <= 0 && !d.caseOpen || d.jailed()) return;
        Walker o = d.stars > 0 ? officers.nearest(p.getLocation(), 5) : null;
        if (o == null && d.caseOpen) o = bureau.nearest(p.getLocation(), 5);
        if (o == null) return;
        o.say(p, "That's it, nice and easy.");
        arrest(p, o, false, null);
    }

    // ------------------------------------------------------------------ hooks from the rest of the plugin

    public void questDone(Player p, boolean story, boolean weekly) {
        var c = plugin.getConfig();
        addRep(p, story ? c.getInt("town.reputation.story-chapter", 10) : weekly ? c.getInt("town.reputation.weekly-quest", 8) : c.getInt("town.reputation.daily-quest", 3));
    }

    public void bossKilled(Player p, String boss) {
        addRep(p, plugin.getConfig().getInt("town.reputation.boss-kill", 5));
    }

    public void bossNews(String text) {
        news(text);
    }

    // used by commands
    public List<Player> wantedOnline() {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (data(p).stars > 0) out.add(p);
        out.sort((a, b) -> data(b).stars - data(a).stars);
        return out;
    }

    public void despawnAllWalkers() {
        for (Walker w : new ArrayList<>(walkers)) unregister(w, true);
    }

    public LivingEntity bodyOf(Walker w) {
        return w.body;
    }

    // ------------------------------------------------------------------ places to stand

    /** Standing spot on the surface at x,z (outdoors, dry, two free blocks), within maxDy of nearY, or null. */
    public Location surface(World w, int x, int z, double nearY, int maxDy) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
        int y = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (Math.abs(y + 1 - nearY) > maxDy) return null;
        var ground = w.getBlockAt(x, y, z);
        var feet = w.getBlockAt(x, y + 1, z);
        var head = w.getBlockAt(x, y + 2, z);
        if (ground.isLiquid() || !ground.isSolid() || ground.getType().name().contains("LEAVES")) return null;
        if (!feet.isPassable() || feet.isLiquid() || !head.isPassable() || head.isLiquid()) return null;
        if (feet.getType().name().contains("FIRE") || ground.getType() == org.bukkit.Material.MAGMA_BLOCK) return null;
        Location l = new Location(w, x + 0.5, y + 1, z + 0.5);
        for (Jail j : jails.values()) if (j.contains(l)) return null;
        return l;
    }

    /** A spot between min and max blocks from the player, preferably behind them (so NPCs don't pop in on screen). */
    public Location spawnSpot(Player p, double min, double max) {
        Location pl = p.getLocation();
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 14; i++) {
            double yaw = Math.toRadians(pl.getYaw() + 180 + (i < 8 ? r.nextDouble(-70, 70) : r.nextDouble(-180, 180)));
            double d = r.nextDouble(min, max);
            int x = (int) Math.floor(pl.getX() - Math.sin(yaw) * d), z = (int) Math.floor(pl.getZ() + Math.cos(yaw) * d);
            Location l = surface(pl.getWorld(), x, z, pl.getY(), 4);
            if (l != null) return l;
        }
        return null;
    }

    /** A point roughly `dist` blocks from `from`, directly away from `threat`. */
    public Location awayFrom(Location from, Location threat, double dist) {
        org.bukkit.util.Vector v = from.toVector().subtract(threat.toVector()).setY(0);
        if (v.lengthSquared() < 1e-4) v = new org.bukkit.util.Vector(1, 0, 0);
        v.normalize();
        for (int i = 0; i < 6; i++) {
            double a = (i - 2.5) * 0.35;
            double x = v.getX() * Math.cos(a) - v.getZ() * Math.sin(a), z = v.getX() * Math.sin(a) + v.getZ() * Math.cos(a);
            Location l = surface(from.getWorld(), (int) Math.floor(from.getX() + x * dist), (int) Math.floor(from.getZ() + z * dist), from.getY(), 6);
            if (l != null) return l;
        }
        return null;
    }

    public Player nearestPlayer(Location l, double r) {
        Player best = null;
        double bd = r * r;
        for (Player p : l.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            double d = p.getLocation().distanceSquared(l);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }
}
