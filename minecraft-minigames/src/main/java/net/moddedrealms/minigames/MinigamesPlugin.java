package net.moddedrealms.minigames;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import java.io.File;
import java.io.IOException;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Minigames server: a hub with TNT Run and Murder Mystery. Real players queue from the hub; when there aren't
 * enough of them, bots (mannequins that move, fight and chat like players, tagged [BOT]) fill the empty spots.
 */
public final class MinigamesPlugin extends JavaPlugin {

    /** Bumped when the hub or arena layouts change: the world is rebuilt on the next start. */
    static final int BUILD_VERSION = 1;

    private World world;
    private Manager manager;
    private Stats stats;
    private Hub hub;
    private Board board;
    NamespacedKey botKey;
    NamespacedKey npcKey;
    NamespacedKey itemKey;
    NamespacedKey corpseKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        botKey = new NamespacedKey(this, "bot");
        npcKey = new NamespacedKey(this, "npc");
        itemKey = new NamespacedKey(this, "item");
        corpseKey = new NamespacedKey(this, "corpse");
        Items.key = itemKey;
        Bot.setTag(getConfig().getString("bots.tag", "[BOT]"));
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        world = loadWorld();
        board = new Board();
        stats = new Stats(this);
        manager = new Manager(this);
        hub = new Hub(this);
        buildIfNeeded();
        removeStrays();
        hub.spawnNpcs();

        getServer().getPluginManager().registerEvents(new Listeners(this), this);
        MgCommand cmd = new MgCommand(this);
        getCommand("mg").setExecutor(cmd);
        getCommand("mg").setTabCompleter(cmd);
        getCommand("hub").setExecutor(cmd);

        Bukkit.getScheduler().runTaskTimer(this, () -> manager.tick(), 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, () -> hub.tick(), 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> stats.saveIfDirty(), 600L, 600L);
        for (Player p : Bukkit.getOnlinePlayers()) hub.send(p);
        getLogger().info("Minigames ready: TNT Run and Murder Mystery in world '" + world.getName() + "'.");
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.shutdown();
        if (hub != null) hub.removeNpcs();
        if (stats != null) stats.save();
    }

    private World loadWorld() {
        String name = getConfig().getString("world", "minigames");
        World w = Bukkit.getWorld(name);
        if (w == null) w = new WorldCreator(name).generator(new VoidGen()).generateStructures(false).createWorld();
        w.setDifficulty(Difficulty.PEACEFUL);
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(GameRule.DO_FIRE_TICK, false);
        w.setGameRule(GameRule.MOB_GRIEFING, false);
        w.setGameRule(GameRule.KEEP_INVENTORY, true);
        w.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        w.setGameRule(GameRule.SHOW_DEATH_MESSAGES, false);
        w.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        w.setGameRule(GameRule.TNT_EXPLODES, false);
        w.setGameRule(GameRule.FALL_DAMAGE, false);
        w.setGameRule(GameRule.DO_TILE_DROPS, false);
        w.setGameRule(GameRule.DO_ENTITY_DROPS, false);
        w.setGameRule(GameRule.LOCATOR_BAR, false);
        w.setGameRule(GameRule.SPAWN_RADIUS, 0);
        w.setTime(6000);
        w.setStorm(false);
        w.setSpawnLocation(0, Hub.Y + 1, 0);
        return w;
    }

    /** Build the hub and the arenas the first time (or after a layout change, or when config says rebuild). */
    private void buildIfNeeded() {
        File f = new File(getDataFolder(), "built.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        boolean force = getConfig().getBoolean("rebuild", false);
        if (!force && y.getInt("version") == BUILD_VERSION && y.getString("world", "").equals(world.getName())) return;
        long t = System.currentTimeMillis();
        hub.build();
        for (GameType type : GameType.values()) {
            for (int slot = 0; slot < type.arenas(this); slot++) type.buildArena(this, slot);
        }
        y.set("version", BUILD_VERSION);
        y.set("world", world.getName());
        try {
            y.save(f);
        } catch (IOException e) {
            getLogger().warning("Couldn't save built.yml: " + e.getMessage());
        }
        if (force) {
            getConfig().set("rebuild", false);
            saveConfig();
        }
        getLogger().info("Built the hub and arenas in " + (System.currentTimeMillis() - t) + " ms.");
    }

    void rebuild() {
        hub.removeNpcs();
        hub.build();
        for (GameType type : GameType.values()) {
            for (int slot = 0; slot < type.arenas(this); slot++) {
                if (!manager.slotBusy(type, slot)) type.buildArena(this, slot);
            }
        }
        hub.spawnNpcs();
    }

    /** Bots, NPCs and corpses left over from a crash. */
    private void removeStrays() {
        for (var e : world.getEntities()) {
            var pdc = e.getPersistentDataContainer();
            if (pdc.has(botKey) || pdc.has(npcKey) || pdc.has(corpseKey) || pdc.has(itemKey)) e.remove();
        }
    }

    /** Send a player to another server on the network (Velocity's BungeeCord channel). */
    void sendToServer(Player p, String server) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(server);
        p.sendPluginMessage(this, "BungeeCord", out.toByteArray());
    }

    World world() {
        return world;
    }

    Location at(double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    Manager manager() {
        return manager;
    }

    Stats stats() {
        return stats;
    }

    Hub hub() {
        return hub;
    }

    Board board() {
        return board;
    }
}
