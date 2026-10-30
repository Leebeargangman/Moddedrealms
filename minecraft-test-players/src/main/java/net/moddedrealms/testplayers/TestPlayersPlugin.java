package net.moddedrealms.testplayers;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Mannequin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Moddedrealms Test Players: simulated players for performance testing. Each one is a server-side mannequin
 * (a player-shaped entity) that walks around, keeps the chunks around it loaded like a real player, and can be
 * controlled by an AI model and from the website's Bots page. Nothing logs in: test players exist only on this
 * server, as entities the server spawns.
 */
public final class TestPlayersPlugin extends JavaPlugin implements Listener {

    private Settings settings;
    private ChunkTickets chunks;
    private TestPlayers players;
    private Brain brain;
    private Builder builder;
    private WebsiteLink link;
    private BukkitTask tickTask;
    private BukkitTask thinkTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.from(getConfig());
        chunks = new ChunkTickets(this);
        players = new TestPlayers(this, chunks);
        brain = new Brain(this);
        builder = new Builder(this);
        builder.loadZones();
        builder.load();
        link = new WebsiteLink(this);

        PluginCommand cmd = getCommand("testplayer");
        TestPlayerCommand handler = new TestPlayerCommand(this);
        cmd.setExecutor(handler);
        cmd.setTabCompleter(handler);
        getServer().getPluginManager().registerEvents(this, this);

        players.removeStrays();
        players.load();
        tickTask = Bukkit.getScheduler().runTaskTimer(this, players::tick, 1, 1);
        thinkTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (TestPlayer tp : players.all()) if (tp.entity() != null) brain.maybeThink(tp);
        }, 40, 20);
        link.start();
        getLogger().info(players.all().size() + " test players known. Website link: " + link.status() + ".");
    }

    @Override
    public void onDisable() {
        if (link != null) link.stop();
        if (tickTask != null) tickTask.cancel();
        if (thinkTask != null) thinkTask.cancel();
        if (players != null) {
            players.save(); // before removing the mannequins, so it remembers which were in the world and where
            players.despawnAllForShutdown();
        }
        if (builder != null) builder.save();
        if (chunks != null) chunks.clear();
    }

    void reload() {
        reloadConfig();
        settings = Settings.from(getConfig());
        builder.loadZones();
        link.start();
    }

    Settings settings() {
        return settings;
    }

    TestPlayers players() {
        return players;
    }

    Brain brain() {
        return brain;
    }

    Builder builder() {
        return builder;
    }

    WebsiteLink link() {
        return link;
    }

    // ------------------------------------------------------------ events

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        // Covers damage that ignores the invulnerable flag (the void, /kill aside).
        if (settings.invulnerable() && e.getEntity() instanceof Mannequin m && players.of(m) != null
                && e.getCause() != EntityDamageEvent.DamageCause.VOID && e.getCause() != EntityDamageEvent.DamageCause.KILL) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Mannequin m)) return;
        TestPlayer tp = players.of(m);
        if (tp == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        players.died(tp);
    }

    @EventHandler
    public void onRemove(EntityRemoveEvent e) {
        // Removed for any other reason (a plugin, /kill, the chunk unloading): the tick loop brings it back.
        if (e.getEntity() instanceof Mannequin m && e.getCause() != EntityRemoveEvent.Cause.DEATH) {
            TestPlayer tp = players.of(m);
            if (tp != null && tp.wanted) tp.lastKnown = m.getLocation();
        }
    }

    /** Players talking to a test player by name: its AI model hears it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        String from = e.getPlayer().getName();
        String lower = text.toLowerCase(Locale.ROOT);
        Bukkit.getScheduler().runTask(this, () -> {
            List<TestPlayer> addressed = new ArrayList<>();
            for (TestPlayer tp : players.all()) {
                if (tp.entity() == null || !lower.contains(tp.id)) continue;
                if (Pattern.compile("\\b" + Pattern.quote(tp.id) + "\\b").matcher(lower).find()) addressed.add(tp);
            }
            for (TestPlayer tp : addressed) {
                link.heard(tp, from, text);
                if (tp.ai && tp.model != null) brain.heard(tp, from, text);
            }
        });
    }
}
