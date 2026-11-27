package com.colonysmp;

import com.colonysmp.building.BlueprintManager;
import com.colonysmp.building.BuildingMarker;
import com.colonysmp.building.BuildingManager;
import com.colonysmp.building.SelectionManager;
import com.colonysmp.colony.Access;
import com.colonysmp.colony.Christmas;
import com.colonysmp.colony.ClaimListener;
import com.colonysmp.colony.ColonyManager;
import com.colonysmp.colony.Happenings;
import com.colonysmp.colony.Milestones;
import com.colonysmp.colony.QuestManager;
import com.colonysmp.colony.RequestManager;
import com.colonysmp.colony.TerritoryManager;
import com.colonysmp.colony.TownHallListener;
import com.colonysmp.command.ColonyCommand;
import com.colonysmp.diplomacy.DiplomacyManager;
import com.colonysmp.economy.EconomyManager;
import com.colonysmp.gui.ChatPrompt;
import com.colonysmp.gui.MenuListener;
import com.colonysmp.military.CombatListener;
import com.colonysmp.military.MilitaryManager;
import com.colonysmp.model.Colony;
import com.colonysmp.npc.NpcManager;
import com.colonysmp.prison.PrisonManager;
import com.colonysmp.prison.TravelerManager;
import com.colonysmp.raid.RaidManager;
import com.colonysmp.sim.SimulationEngine;
import com.colonysmp.store.Database;
import com.colonysmp.store.Snapshot;
import com.colonysmp.store.V1Importer;
import com.colonysmp.util.ItemGoods;
import com.colonysmp.util.Items;
import com.colonysmp.util.Keys;
import com.colonysmp.war.WarManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * ColonySMP: colonies of simulated citizens who live, work, trade and fight, for Paper survival servers.
 *
 * <p>Everything a colony is lives in plain records (citizens, buildings, goods, money) kept in a SQLite
 * database; the villagers walking around are only the bodies of those records while the colony is loaded,
 * and the simulation keeps every colony going whether anyone is near it or not.
 */
public final class ColonySMP extends JavaPlugin {

    private Settings settings;
    private Items items;
    private Database db;
    private ColonyManager colonies;
    private Access access;
    private TerritoryManager territory;
    private BuildingManager buildings;
    private SelectionManager selection;
    private BlueprintManager blueprints;
    private BuildingMarker markers;
    private QuestManager quests;
    private NpcManager npcs;
    private SimulationEngine sim;
    private MilitaryManager military;
    private PrisonManager prison;
    private TravelerManager travelers;
    private WarManager wars;
    private DiplomacyManager diplomacy;
    private EconomyManager economy;
    private TownHallListener townHall;
    private ColonyCommand commands;
    private ChatPrompt prompt;
    private RaidManager raids;
    private RequestManager requests;
    private Happenings happenings;
    private Milestones milestones;
    private Christmas christmas;
    private com.colonysmp.world.CivManager civs;
    private com.colonysmp.command.CivCommand civCommand;

    private long tick;
    private boolean dirty, saving, loaded;
    private final Set<UUID> bypass = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(getConfig(), getLogger());
        Keys.init(this);
        int goods = ItemGoods.registerAll(settings.prices);
        items = new Items(this);
        items.registerRecipes();

        colonies = new ColonyManager(this);
        access = new Access(this);
        territory = new TerritoryManager(this);
        buildings = new BuildingManager(this);
        wars = new WarManager(this);
        diplomacy = new DiplomacyManager(this);
        economy = new EconomyManager(this);
        sim = new SimulationEngine(this);
        npcs = new NpcManager(this);
        military = new MilitaryManager(this);
        prison = new PrisonManager(this);
        travelers = new TravelerManager(this);
        quests = new QuestManager(this);
        raids = new RaidManager(this);
        requests = new RequestManager(this);
        happenings = new Happenings(this);
        milestones = new Milestones(this);
        christmas = new Christmas(this);
        blueprints = new BlueprintManager(this);
        markers = new BuildingMarker(this);
        selection = new SelectionManager(this);
        townHall = new TownHallListener(this);
        prompt = new ChatPrompt(this);
        civs = new com.colonysmp.world.CivManager(this);
        civCommand = new com.colonysmp.command.CivCommand(this);
        commands = new ColonyCommand(this);
        economy.configure();

        db = new Database(this);
        try {
            db.open(settings.backups);
            Snapshot snap = db.load(200, Colony.KEEP_EVENTS);
            colonies.load(snap);
            wars.load(snap);
            diplomacy.load(snap);
            economy.load(snap);
            civs.load(snap);
            Database.V1 v1 = db.loadV1();
            if (v1 != null) {
                int n = new V1Importer(this).run(v1);
                getLogger().info("Imported " + n + " colonies from ColonySMP 1.x.");
                if (db.saveNow(snapshot())) db.v1Imported();
                db = reopen();
            }
            loaded = true;
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Could not open the colony database. ColonySMP will not run, so nothing is overwritten.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        com.colonysmp.api.ColonyApi.attach(this);
        for (Colony c : colonies.list()) {
            buildings.applyCapacity(c);
            sim.logistics().recover(c);
            sim.housing().assign(c);
            sim.jobs().refreshWorkplaces(c);
        }

        for (Listener l : new Listener[]{items, selection, blueprints, markers, quests, npcs, prison, travelers, wars, townHall, military, raids, requests, happenings, christmas,
                new CombatListener(this), new ClaimListener(this), new MenuListener(), prompt, civs, new com.colonysmp.world.SettlementProtection(this, civs)}) {
            Bukkit.getPluginManager().registerEvents(l, this);
        }
        PluginCommand cmd = getCommand("colony");
        if (cmd != null) {
            cmd.setExecutor(commands);
            cmd.setTabCompleter(commands);
        }
        PluginCommand civ = getCommand("civ");
        if (civ != null) {
            civ.setExecutor(civCommand);
            civ.setTabCompleter(civCommand);
        }

        // every tick: bodies walk and think
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            tick++;
            npcs.tick(tick);
            travelers.move(tick);
            happenings.move(tick);
            civs.bodies().move(tick);
        }, 1L, 1L);
        // every second: bodies come and go, the simulation steps, wars and offers move on
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            guarded("bodies", npcs::activityLoop);
            guarded("simulation", sim::tick);
            guarded("prisoners", prison::tick);
            guarded("travellers", travelers::tick);
            guarded("wars", wars::tick);
            guarded("diplomacy", diplomacy::tick);
            guarded("market", economy::tick);
            guarded("quests", quests::tick);
            guarded("raids", raids::tick);
            guarded("happenings", happenings::tick);
            guarded("christmas", christmas::tick);
            guarded("chatter", npcs.chatter()::tick);
            guarded("living world", civs::tick);
        }, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            selection.render();
            blueprints.render();
        }, 5L, 5L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (dirty) save();
        }, 600L, 600L);
        long auto = settings.autosaveMinutes * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::save, auto, auto);

        for (Player p : Bukkit.getOnlinePlayers()) items.discover(p);
        getLogger().info("ColonySMP enabled: " + colonies.all().size() + " colonies, " + colonies.totalCitizens() + " citizens, " + goods + " goods.");
    }

    private Database reopen() throws SQLException {
        // saveNow shut the writer down: open a fresh connection for the rest of the session
        db.close();
        Database d = new Database(this);
        d.open(0);
        return d;
    }

    private void guarded(String what, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ex) {
            getLogger().log(Level.SEVERE, "The " + what + " update failed", ex);
        }
    }

    @Override
    public void onDisable() {
        com.colonysmp.api.ColonyApi.detach();
        if (!loaded) return;
        npcs.rememberAll();
        Snapshot snap = snapshot();
        npcs.despawnAll();
        travelers.shutdown();
        raids.shutdown();
        happenings.shutdown();
        quests.shutdown();
        wars.shutdown();
        civs.bodies().despawnAll();
        if (!db.saveNow(snap)) getLogger().severe("The final save failed: " + db.lastError());
        db.close();
        civs.shutdown();
        items.unregisterRecipes();
    }

    // ───────────── saving ─────────────

    /** Everything, as rows for the database (built on the server thread). */
    public Snapshot snapshot() {
        Snapshot s = new Snapshot();
        colonies.snapshot(s);
        wars.snapshot(s);
        diplomacy.snapshot(s);
        economy.snapshot(s);
        civs.snapshot(s);
        return s;
    }

    /** Something changed: it will be written within 30 seconds. */
    public void requestSave() {
        dirty = true;
    }

    public void save() {
        if (!loaded || saving) return;
        dirty = false;
        saving = true;
        Snapshot snap = snapshot();
        db.saveAsync(snap, ok -> Bukkit.getScheduler().runTask(this, () -> {
            saving = false;
            if (!ok) {
                // the logs that weren't written go back in the queue; the next save tries again
                colonies.requeue(snap);
                dirty = true;
                getLogger().warning("Saving failed (" + db.lastError() + "); retrying shortly.");
            }
        }));
    }

    public void reload() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
        ItemGoods.registerAll(settings.prices);
        items.registerRecipes();
        economy.configure();
        civs.configure();
        christmas.reload();
        sim.citizens().reload();
        for (Colony c : colonies.list()) buildings.changed(c);
        requestSave();
    }

    // ───────────── access ─────────────

    public long tick() {
        return tick;
    }

    public boolean isBypassing(Player p) {
        return bypass.contains(p.getUniqueId()) && p.hasPermission("colonysmp.admin");
    }

    public boolean toggleBypass(Player p) {
        if (!bypass.remove(p.getUniqueId())) {
            bypass.add(p.getUniqueId());
            return true;
        }
        return false;
    }

    /** The name of the place that keeps colonies off this chunk (config or another plugin), or null. */
    public String noFoundZone(String world, int cx, int cz) {
        Settings.Zone z = settings.noFoundZone(world, cx, cz);
        return z != null ? z.label() : com.colonysmp.api.ColonyApi.zoneAt(world, cx, cz);
    }

    public Settings settings() {
        return settings;
    }

    public Items items() {
        return items;
    }

    public Database database() {
        return db;
    }

    public ColonyManager colonies() {
        return colonies;
    }

    public Access access() {
        return access;
    }

    public TerritoryManager territory() {
        return territory;
    }

    public BuildingManager buildings() {
        return buildings;
    }

    public BuildingMarker markers() {
        return markers;
    }

    public SelectionManager selection() {
        return selection;
    }

    public BlueprintManager blueprints() {
        return blueprints;
    }

    public QuestManager quests() {
        return quests;
    }

    public NpcManager npcs() {
        return npcs;
    }

    public SimulationEngine sim() {
        return sim;
    }

    public MilitaryManager military() {
        return military;
    }

    public PrisonManager prison() {
        return prison;
    }

    public TravelerManager travelers() {
        return travelers;
    }

    public WarManager wars() {
        return wars;
    }

    public DiplomacyManager diplomacy() {
        return diplomacy;
    }

    public EconomyManager economy() {
        return economy;
    }

    public TownHallListener townHall() {
        return townHall;
    }

    public ColonyCommand commands() {
        return commands;
    }

    public ChatPrompt prompt() {
        return prompt;
    }

    public RaidManager raids() {
        return raids;
    }

    public RequestManager requests() {
        return requests;
    }

    public Happenings happenings() {
        return happenings;
    }

    public Milestones milestones() {
        return milestones;
    }

    public Christmas christmas() {
        return christmas;
    }

    /** The living world: NPC civilisations, their settlements, and the transaction journal. */
    public com.colonysmp.world.CivManager civs() {
        return civs;
    }

    public com.colonysmp.command.CivCommand civCommand() {
        return civCommand;
    }
}
