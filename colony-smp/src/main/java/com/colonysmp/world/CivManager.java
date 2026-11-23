package com.colonysmp.world;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.civ.Civ;
import com.colonysmp.civ.CivFactory;
import com.colonysmp.civ.CivSim;
import com.colonysmp.civ.CivWorld;
import com.colonysmp.civ.Contract;
import com.colonysmp.civ.Prices;
import com.colonysmp.civ.Settlement;
import com.colonysmp.civ.SettlementType;
import com.colonysmp.civ.TradeGoods;
import com.colonysmp.core.Reservation;
import com.colonysmp.store.CivCodec;
import com.colonysmp.store.Snapshot;
import com.colonysmp.txn.AuditRules;
import com.colonysmp.txn.Journal;
import com.colonysmp.txn.Line;
import com.colonysmp.txn.TransactionManager;
import com.colonysmp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The living world on the server: keeps the civilisations' books, moves their calendar on with the Minecraft
 * days (and makes up time the server was off), is the other side of every barter, and tells players the news.
 * The simulation itself is {@link CivSim}; what stands in the world is {@link SettlementBuilder}; who walks
 * about in it is {@link SettlementBodies}.
 */
public final class CivManager implements Listener, TransactionManager.Counterparty {

    private final ColonySMP plugin;
    private final TransactionManager txns;
    private CivWorld world = new CivWorld();
    private CivSim sim;
    private final SettlementBuilder builder;
    private final SettlementGenerator generator;
    private final SettlementBodies bodies;
    private int ticks;
    private int newsCursor;
    private final Map<UUID, Long> lastNews = new HashMap<>();
    /** Simulation cost, for /colony admin civ status. */
    private long lastStepNanos, stepsRun;

    public CivManager(ColonySMP plugin) {
        this.plugin = plugin;
        this.txns = new TransactionManager(plugin);
        this.builder = new SettlementBuilder(plugin, this);
        this.generator = new SettlementGenerator(plugin, this);
        this.bodies = new SettlementBodies(plugin, this);
        configure();
    }

    public void configure() {
        Settings.Civs s = plugin.settings().civs;
        CivSim.Config cfg = sim == null ? new CivSim.Config() : sim.config();
        cfg.production = s.production;
        cfg.growth = s.growth;
        cfg.events = s.events;
        cfg.maxPopulation = s.maxPopulation;
        cfg.maxSoldiers = s.maxSoldiers;
        cfg.maxSettlementsPerCiv = s.maxPerCiv;
        cfg.tradeRange = s.tradeRange;
        cfg.caravanSpeed = s.caravanSpeed;
        cfg.wars = s.wars;
        cfg.contracts = s.contracts;
        if (sim == null) sim = new CivSim(cfg);
        s.worth.forEach(TradeGoods::setWorth);
        txns.configure(s.tradeGapMs, new AuditRules(s.tradesPerMinuteNote, s.rejectsPerMinuteNote, s.hourlyGainNote));
    }

    public CivWorld world() {
        return world;
    }

    public CivSim sim() {
        return sim;
    }

    public TransactionManager txns() {
        return txns;
    }

    public SettlementBuilder builder() {
        return builder;
    }

    public SettlementGenerator generator() {
        return generator;
    }

    public SettlementBodies bodies() {
        return bodies;
    }

    public boolean enabled() {
        return plugin.settings().civs.enabled;
    }

    // ───────────── loading and saving ─────────────

    /** Opens the journal, loads the books, replays what the books missed, and makes up time the server was off. */
    public void load(Snapshot snap) throws SQLException {
        Settings.Civs s = plugin.settings().civs;
        world = CivCodec.load(snap.civDocs);
        if (snap.civDocs.isEmpty()) world.seed = new Random().nextLong() >>> 1;
        txns.open(s.fullSync, s.tradeGapMs, new AuditRules(s.tradesPerMinuteNote, s.rejectsPerMinuteNote, s.hourlyGainNote));
        txns.bind(this);
        txns.recover(world.journalMark);
        txns.journal().prune(System.currentTimeMillis() - s.journalKeepDays * 86_400_000L);
        if (!enabled() || world.settlements.isEmpty() || world.savedAt <= 0) return;
        // a real 20 minutes is a Minecraft day; the world moves on at a share of that while nobody could watch
        double away = (System.currentTimeMillis() - world.savedAt) / 1_200_000.0 * s.offlineRate;
        int days = (int) Math.min(s.offlineDaysMax, Math.floor(away));
        if (days > 0) {
            long t0 = System.nanoTime();
            sim.advance(world, days);
            world.news.clear(); // nobody was here to hear it; it is in the histories
            plugin.getLogger().info("The living world moved on " + days + " day(s) while the server was off (" + (System.nanoTime() - t0) / 1_000_000 + " ms).");
        }
    }

    public void snapshot(Snapshot out) {
        world.savedAt = System.currentTimeMillis();
        CivCodec.snapshot(world, out);
    }

    public void shutdown() {
        bodies.despawnAll();
        txns.close();
    }

    // ───────────── time ─────────────

    /** Every second. */
    public void tick() {
        txns.flushUnwritten();
        if (!enabled()) return;
        Settings.Civs s = plugin.settings().civs;
        ticks++;
        bodies.tick();
        if (ticks % s.stepSeconds == 0) calendar();
        if (ticks % 5 == 0) tellNews();
        builder.tick();
        if (s.generate && ticks % s.generationSeconds == 0) generator.consider();
    }

    private void calendar() {
        World w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (w == null || world.settlements.isEmpty()) return;
        long gameDay = w.getFullTime() / 24000;
        if (world.lastGameDay < 0 || gameDay < world.lastGameDay) world.lastGameDay = gameDay;
        // at most a few days a look, so a /time add doesn't stall the server; the rest follow on the next looks
        int due = (int) Math.min(3, gameDay - world.lastGameDay);
        if (due <= 0) return;
        long t0 = System.nanoTime();
        for (int i = 0; i < due; i++) step();
        lastStepNanos = (System.nanoTime() - t0) / due;
        world.lastGameDay += due;
        plugin.requestSave();
    }

    /** One world day (also from the admin command). */
    public void step() {
        try {
            sim.step(world);
            stepsRun++;
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "A day of the living world failed (day " + world.day + ")", e);
        }
        for (String civId : new ArrayList<>(sim.wantOutpost)) {
            sim.wantOutpost.remove(civId);
            if (world.alive().size() < plugin.settings().civs.maxSettlements) generator.outpost(civId);
        }
        for (Settlement s : world.settlements.values()) {
            if (s.state == Settlement.State.RUINED && s.built && s.builtLevel >= 0) builder.queueRuin(s);
            else if (s.alive() && s.built && s.level > s.builtLevel) builder.queueGrowth(s);
        }
    }

    public long lastStepMicros() {
        return lastStepNanos / 1000;
    }

    public long stepsRun() {
        return stepsRun;
    }

    // ───────────── news ─────────────

    private void tellNews() {
        String mode = plugin.settings().civs.news;
        List<CivWorld.News> all = world.news;
        if (newsCursor > all.size()) newsCursor = 0;
        long now = System.currentTimeMillis();
        for (; newsCursor < all.size(); newsCursor++) {
            CivWorld.News n = all.get(newsCursor);
            if (mode.equals("off")) continue;
            Settlement s = n.settlement() == null ? null : world.settlements.get(n.settlement());
            Civ c = n.civ() == null ? null : world.civs.get(n.civ());
            for (Player p : Bukkit.getOnlinePlayers()) {
                boolean near = s != null && p.getWorld().getName().equals(s.world) && dist2(p.getLocation(), s) < 300 * 300;
                boolean friend = c != null && c.reputation(p.getUniqueId()) >= 20;
                boolean hear = n.weight() >= 3 || mode.equals("all") && n.weight() >= 2 || near || friend && n.weight() >= 2;
                if (!hear) continue;
                // the talk of the whole map always gets through; the rest at most once a minute
                if (n.weight() < 3 && now - lastNews.getOrDefault(p.getUniqueId(), 0L) < 60_000) continue;
                lastNews.put(p.getUniqueId(), now);
                Text.raw(p, "<dark_gray>[<gold>Tidings</gold>]</dark_gray> <gray>" + Text.esc(n.text()));
            }
        }
        if (all.size() >= 150) {
            all.subList(0, 100).clear();
            newsCursor = Math.max(0, newsCursor - 100);
        }
    }

    static double dist2(Location l, Settlement s) {
        double dx = l.getX() - s.x, dz = l.getZ() - s.z;
        return dx * dx + dz * dz;
    }

    // ───────────── finding things ─────────────

    public Settlement settlement(String idOrName) {
        if (idOrName == null) return null;
        Settlement s = world.settlements.get(idOrName);
        if (s != null) return s;
        for (Settlement x : world.settlements.values()) if (x.name.replace(' ', '_').equalsIgnoreCase(idOrName.replace(' ', '_'))) return x;
        return null;
    }

    public Civ civ(String idOrName) {
        if (idOrName == null) return null;
        Civ c = world.civs.get(idOrName);
        if (c != null) return c;
        for (Civ x : world.civs.values()) if (x.name.replace(' ', '_').equalsIgnoreCase(idOrName.replace(' ', '_'))) return x;
        Settlement s = settlement(idOrName);
        return s == null ? null : world.civs.get(s.civ);
    }

    /** The settlement whose ground this is, or null. */
    public Settlement at(Location l) {
        if (l.getWorld() == null) return null;
        for (Settlement s : world.settlements.values()) {
            if (!s.world.equals(l.getWorld().getName())) continue;
            double r = s.radius + 4;
            if (dist2(l, s) <= r * r) return s;
        }
        return null;
    }

    public Settlement nearest(Player p, double within) {
        return world.nearest(p.getWorld().getName(), p.getLocation().getBlockX(), p.getLocation().getBlockZ(), within);
    }

    public Location location(Settlement s) {
        World w = Bukkit.getWorld(s.world);
        return w == null ? null : new Location(w, s.marketX + 0.5, s.marketY, s.marketZ + 0.5);
    }

    // ───────────── creating and removing ─────────────

    /** Founds a settlement here (admin, generator, outposts). The buildings follow when the chunks are loaded. */
    public Settlement found(Civ civ, SettlementType type, Location at, Random rng) {
        if (civ == null) civ = CivFactory.civ(world, type, rng);
        Settlement s = CivFactory.settlement(world, civ, type, at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), rng);
        // new neighbours start as strangers, a little wary of whoever is closest
        for (Civ o : world.civs.values()) if (o != civ && !world.relations.containsKey(CivWorld.pair(civ.id, o.id))) world.relations.put(CivWorld.pair(civ.id, o.id), (double) (rng.nextInt(31) - 10));
        builder.queueBuild(s);
        plugin.requestSave();
        return s;
    }

    public void remove(Settlement s, boolean clearBuildings) {
        bodies.despawn(s);
        if (clearBuildings) builder.clear(s);
        world.settlements.remove(s.id);
        world.shipments.values().removeIf(sh -> sh.from.equals(s.id) || sh.to.equals(s.id));
        world.contracts.values().removeIf(c -> c.settlement.equals(s.id));
        if (world.of(s.civ).isEmpty()) {
            boolean ruins = false;
            for (Settlement x : world.settlements.values()) if (x.civ.equals(s.civ)) ruins = true;
            if (!ruins) {
                world.civs.remove(s.civ);
                world.wars.values().removeIf(w -> w.involves(s.civ));
                world.relations.keySet().removeIf(k -> k.startsWith(s.civ + "|") || k.endsWith("|" + s.civ));
                world.treaties.keySet().removeIf(k -> k.startsWith(s.civ + "|") || k.endsWith("|" + s.civ));
            }
        }
        plugin.requestSave();
    }

    // ───────────── the other side of a trade ─────────────

    @Override
    public String reserve(String counterparty, List<Line> takes, String holdKey) {
        Settlement s = world.settlements.get(counterparty);
        if (s == null || !s.alive()) return "There is nobody here to trade with.";
        Map<String, Long> goods = new LinkedHashMap<>();
        for (Line l : takes) goods.merge(l.good(), l.qty(), Long::sum);
        return s.stock.reserve(holdKey, goods, System.currentTimeMillis()) == null ? "Their stores no longer hold that much." : null;
    }

    @Override
    public void release(String counterparty, String holdKey) {
        Settlement s = world.settlements.get(counterparty);
        if (s != null) s.stock.releaseOwner(holdKey);
    }

    @Override
    public void apply(Journal.Record r, boolean held) {
        Settlement s = world.settlements.get(r.counterparty());
        if (s == null) return;
        // a contract's reward may still be set aside under the contract's own name (replay after a crash, or a row
        // carried through at join): that hold makes way first, so the reward leaves the stores exactly once
        for (String part : r.effects().split(";")) {
            if (!part.startsWith("contract=")) continue;
            Contract c = world.contracts.get(part.substring(9));
            if (c != null && c.hold != null && s.stock.reservation(c.hold) != null) s.stock.release(c.hold);
        }
        boolean consumed = false;
        if (held) {
            String key = TransactionManager.holdOf(r);
            for (Reservation res : new ArrayList<>(s.stock.reservations())) {
                if (res.owner.equals(key)) consumed |= s.stock.consume(res.id);
            }
        }
        if (!consumed) for (Line l : r.takes()) s.stock.take(l.good(), l.qty());
        for (Line l : r.gives()) s.stock.force(l.good(), l.qty());
        Civ civ = world.civs.get(s.civ);
        for (String part : r.effects().split(";")) {
            if (part.startsWith("rep=") && civ != null) {
                try {
                    civ.addReputation(r.player(), Double.parseDouble(part.substring(4)));
                } catch (NumberFormatException ignored) {
                    // a damaged row: the goods are right, the goodwill is skipped
                }
            } else if (part.startsWith("contract=")) {
                Contract c = world.contracts.get(part.substring(9));
                if (c != null) {
                    c.state = Contract.State.DONE;
                    c.hold = null;
                    c.expiresDay = world.day;
                    if (civ != null) civ.record(world.day, r.playerName() + " delivered " + c.wantQty + " " + TradeGoods.get(c.want).name() + " to " + s.name + ".");
                }
            }
        }
        plugin.requestSave();
    }

    @Override
    public void mark(long commitNo) {
        world.journalMark = Math.max(world.journalMark, commitNo);
    }

    @Override
    public boolean alreadyApplied(String txnId) {
        return world.appliedUncommitted.contains(txnId);
    }

    @Override
    public void rememberApplied(String txnId, boolean applied) {
        if (applied) world.appliedUncommitted.add(txnId);
        else world.appliedUncommitted.remove(txnId);
    }

    // ───────────── what players do ─────────────

    /** Why this player can't trade at this settlement right now, or null. */
    public String closed(Player p, Settlement s) {
        if (!s.alive()) return s.name + " lies in ruins.";
        Civ c = world.civs.get(s.civ);
        if (c == null) return "There is nobody here to trade with.";
        if (!p.getWorld().getName().equals(s.world) || dist2(p.getLocation(), s) > Math.pow(s.radius + 12, 2)) return "You have to be in " + s.name + " to trade there.";
        if (s.has("plague", world.day)) return s.name + " is shut up with the plague.";
        if (s.besiegedBy != null) return s.name + " is under siege. Nothing goes in or out.";
        if (c.reputation(p.getUniqueId()) <= -50) return "The people of " + c.name + " will have nothing to do with you.";
        return null;
    }

    /** Swaps the player's {@code give} for {@code wantQty} of {@code want} from the settlement's stores. */
    public TransactionManager.Result barter(Player p, Settlement s, String give, String want, long wantQty) {
        String closed = closed(p, s);
        if (closed != null) return new TransactionManager.Result(false, closed, null);
        Civ c = world.civs.get(s.civ);
        Prices.Quote q = Prices.quote(world, s, give, want, wantQty, c.reputation(p.getUniqueId()));
        if (!q.ok()) return new TransactionManager.Result(false, q.error(), null);
        double goodwill = Math.min(1.0, q.worthOut() / 200);
        return txns.exchange(p, "BARTER", s.id, List.of(new Line(give, q.giveQty())), List.of(new Line(want, wantQty)), null,
                "rep=" + String.format(java.util.Locale.ROOT, "%.3f", goodwill), q.worthIn(), q.worthOut());
    }

    public int contractsOf(UUID player) {
        int n = 0;
        for (Contract c : world.contracts.values()) if (c.state == Contract.State.TAKEN && player.equals(c.takenBy)) n++;
        return n;
    }

    public String take(Player p, Contract c) {
        Settlement s = world.settlements.get(c.settlement);
        if (s == null || c.state != Contract.State.OPEN) return "That contract is no longer on offer.";
        String closed = closed(p, s);
        if (closed != null) return closed;
        Civ civ = world.civs.get(s.civ);
        if (civ.reputation(p.getUniqueId()) < c.minReputation) return "They want someone they know better for this (standing " + Math.round(c.minReputation) + " or more).";
        if (contractsOf(p.getUniqueId()) >= plugin.settings().civs.maxContracts) return "You have " + plugin.settings().civs.maxContracts + " contracts in hand already.";
        // the reward is set aside now: it can't be traded away while the player is fetching the goods
        Reservation r = s.stock.reserve("contract:" + c.id, c.reward, c.rewardQty, System.currentTimeMillis());
        if (r == null) return "They can no longer spare the reward. The offer is withdrawn.";
        c.hold = r.id;
        c.state = Contract.State.TAKEN;
        c.takenBy = p.getUniqueId();
        c.takenByName = p.getName();
        c.takenDay = world.day;
        c.expiresDay = world.day + 3 + c.difficulty;
        plugin.requestSave();
        return null;
    }

    /** Hands the goods over and collects the reward: one exchange, paid once, through the journal. */
    public TransactionManager.Result deliver(Player p, Contract c) {
        Settlement s = world.settlements.get(c.settlement);
        if (s == null || c.state != Contract.State.TAKEN || !p.getUniqueId().equals(c.takenBy)) return new TransactionManager.Result(false, "That isn't a contract you hold.", null);
        String closed = closed(p, s);
        if (closed != null) return new TransactionManager.Result(false, closed, null);
        // the exchange sets the reward aside under its own name; the contract's hold makes way for it this tick
        if (c.hold != null) s.stock.release(c.hold);
        TransactionManager.Result res = txns.exchange(p, "CONTRACT", s.id, List.of(new Line(c.want, c.wantQty)), List.of(new Line(c.reward, c.rewardQty)),
                "contract:" + c.id, "contract=" + c.id + ";rep=" + c.reputation, c.wantQty * TradeGoods.worth(c.want), c.rewardQty * TradeGoods.worth(c.reward));
        if (!res.ok() && c.state == Contract.State.TAKEN) {
            Reservation r = s.stock.reserve("contract:" + c.id, c.reward, c.rewardQty, System.currentTimeMillis());
            c.hold = r == null ? null : r.id;
        }
        return res;
    }

    public String abandon(Player p, Contract c) {
        if (c.state != Contract.State.TAKEN || !p.getUniqueId().equals(c.takenBy)) return "That isn't a contract you hold.";
        Settlement s = world.settlements.get(c.settlement);
        if (s != null && c.hold != null) s.stock.release(c.hold);
        c.hold = null;
        c.state = Contract.State.FAILED;
        c.expiresDay = world.day;
        Civ civ = s == null ? null : world.civs.get(s.civ);
        if (civ != null) civ.addReputation(p.getUniqueId(), -1);
        plugin.requestSave();
        return null;
    }

    // ───────────── players coming and going ─────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // a tick later, so the inventory and its stamp are fully loaded
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            for (String line : txns.settle(p)) Text.send(p, "<yellow>" + Text.esc(line));
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        txns.quit(e.getPlayer().getUniqueId());
        lastNews.remove(e.getPlayer().getUniqueId());
    }
}
