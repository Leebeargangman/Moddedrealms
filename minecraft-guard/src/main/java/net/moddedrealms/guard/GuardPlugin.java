package net.moddedrealms.guard;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.moddedrealms.guard.check.Check;
import net.moddedrealms.guard.check.CombatChecks;
import net.moddedrealms.guard.check.MovementChecks;
import net.moddedrealms.guard.check.PacketChecks;
import net.moddedrealms.guard.check.XrayCheck;
import net.moddedrealms.guard.replay.Recorder;
import net.moddedrealms.guard.replay.ReplayCommand;
import net.moddedrealms.guard.replay.ReplayManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class GuardPlugin extends JavaPlugin implements Listener {

    public enum Mode { SHADOW, ENFORCE }

    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private Mode mode = Mode.SHADOW;
    private Settings settings;
    private Violations violations;
    private Punishments punishments;
    private Recorder recorder;
    private ReplayManager replays;
    private PacketChecks packetChecks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        punishments = new Punishments(this);
        recorder = new Recorder(this);
        replays = new ReplayManager(this, recorder);
        violations = new Violations(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(this, this);
        pm.registerEvents(recorder, this);
        pm.registerEvents(new MovementChecks(this), this);
        pm.registerEvents(new CombatChecks(this), this);
        pm.registerEvents(new XrayCheck(this), this);
        pm.registerEvents(replays, this);

        if (pm.getPlugin("packetevents") != null) {
            packetChecks = new PacketChecks(this);
            packetChecks.register();
        } else {
            getLogger().warning("PacketEvents not found: timer, multi-aura, bad packets and autoclicker checks are off.");
        }

        var guard = new GuardCommand(this);
        getCommand("mrguard").setExecutor(guard);
        getCommand("mrguard").setTabCompleter(guard);
        var replay = new ReplayCommand(this, replays);
        getCommand("replay").setExecutor(replay);
        getCommand("replay").setTabCompleter(replay);
        getCommand("report").setExecutor(new ReportCommand(this));

        recorder.start();
        for (Player p : Bukkit.getOnlinePlayers()) track(p);
        getLogger().info("ModdedrealmsGuard enabled in " + mode + " mode.");
    }

    @Override
    public void onDisable() {
        if (packetChecks != null) packetChecks.unregister();
        if (replays != null) replays.stopAll();
        if (recorder != null) recorder.flushPending();
        if (punishments != null) punishments.save();
    }

    public void loadSettings() {
        reloadConfig();
        mode = "enforce".equalsIgnoreCase(getConfig().getString("mode")) ? Mode.ENFORCE : Mode.SHADOW;
        settings = new Settings(getConfig());
        Check.load(getConfig().getConfigurationSection("checks"));
    }

    public void setMode(Mode m) {
        mode = m;
        getConfig().set("mode", m.name().toLowerCase());
        saveConfig();
    }

    private PlayerState track(Player p) {
        PlayerState s = new PlayerState(p.getUniqueId(), p.getName());
        s.lastSafe = p.getLocation();
        s.lastGroundY = p.getLocation().getY();
        s.graceUntil = tick() + settings.graceTicks;
        s.awaitingGround = true;
        states.put(p.getUniqueId(), s);
        return s;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        track(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        states.remove(e.getPlayer().getUniqueId());
    }

    public PlayerState state(Player p) {
        PlayerState s = states.get(p.getUniqueId());
        return s != null ? s : track(p);
    }

    public PlayerState stateIfPresent(UUID id) {
        return states.get(id);
    }

    /** Server tick counter (counted by the recorder). */
    public long tick() {
        return recorder == null ? 0 : recorder.tick();
    }

    public boolean packetsAvailable() { return packetChecks != null; }
    public Mode mode() { return mode; }
    public Settings settings() { return settings; }
    public Violations violations() { return violations; }
    public Punishments punishments() { return punishments; }
    public Recorder recorder() { return recorder; }
    public ReplayManager replays() { return replays; }
}
