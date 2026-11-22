package net.moddedrealms.guard.replay;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.moddedrealms.guard.GuardPlugin;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;

/** Saved replays on disk, their index, and who is watching what. */
public final class ReplayManager implements Listener {
    public record Entry(String id, String subject, UUID uuid, String reason, long created, int seconds) {}

    private final GuardPlugin plugin;
    private final Recorder recorder;
    private final File folder;
    private final File indexFile;
    private final YamlConfiguration index;
    private final Map<UUID, ReplaySession> sessions = new HashMap<>();
    final NamespacedKey actorKey;
    final NamespacedKey restoreKey;
    private final SecureRandom random = new SecureRandom();

    public ReplayManager(GuardPlugin plugin, Recorder recorder) {
        this.plugin = plugin;
        this.recorder = recorder;
        this.folder = new File(plugin.getDataFolder(), "replays");
        folder.mkdirs();
        this.indexFile = new File(folder, "index.yml");
        this.index = YamlConfiguration.loadConfiguration(indexFile);
        this.actorKey = new NamespacedKey(plugin, "replay_actor");
        this.restoreKey = new NamespacedKey(plugin, "replay_restore");
        cleanup();
    }

    private String newId() {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        for (;;) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) b.append(chars.charAt(random.nextInt(chars.length())));
            String id = b.toString();
            if (!index.contains(id) && !recorder.isPending(id)) return id;
        }
    }

    /** Starts saving a replay of this player; returns its id at once (the file is ready a little later). */
    public String saveClip(Player subject, String reason, boolean auto) {
        return saveClip(subject.getUniqueId(), subject.getName(), reason, plugin.settings().secondsBefore, plugin.settings().secondsAfter);
    }

    public String saveClip(UUID subject, String name, String reason, int before, int after) {
        String id = newId();
        recorder.request(id, subject, name, reason, before, after, clip -> write(clip));
        return id;
    }

    public String pendingClipFor(UUID subject) {
        return recorder.pendingFor(subject);
    }

    private void write(Clip clip) {
        File f = new File(folder, clip.id + ".mrr");
        Runnable job = () -> {
            try {
                ClipIO.write(clip, f);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    index.set(clip.id + ".subject", clip.subjectName);
                    index.set(clip.id + ".uuid", clip.subject.toString());
                    index.set(clip.id + ".reason", clip.reason);
                    index.set(clip.id + ".created", clip.created);
                    index.set(clip.id + ".seconds", clip.length / 20);
                    saveIndex();
                });
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save replay " + clip.id + ": " + e.getMessage());
            }
        };
        if (plugin.isEnabled()) Bukkit.getScheduler().runTaskAsynchronously(plugin, job);
        else {
            try {
                ClipIO.write(clip, f);
                index.set(clip.id + ".subject", clip.subjectName);
                index.set(clip.id + ".uuid", clip.subject.toString());
                index.set(clip.id + ".reason", clip.reason);
                index.set(clip.id + ".created", clip.created);
                index.set(clip.id + ".seconds", clip.length / 20);
                saveIndex();
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save replay " + clip.id + ": " + e.getMessage());
            }
        }
    }

    private void saveIndex() {
        try {
            index.save(indexFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save replay index: " + e.getMessage());
        }
    }

    private void cleanup() {
        long cutoff = System.currentTimeMillis() - plugin.settings().keepDays * 86_400_000L;
        for (String id : new ArrayList<>(index.getKeys(false))) {
            if (index.getLong(id + ".created") < cutoff) {
                new File(folder, id + ".mrr").delete();
                index.set(id, null);
            }
        }
        saveIndex();
    }

    public List<Entry> list(String subjectFilter) {
        List<Entry> out = new ArrayList<>();
        for (String id : index.getKeys(false)) {
            ConfigurationSection s = index.getConfigurationSection(id);
            if (s == null) continue;
            String subject = s.getString("subject", "?");
            if (subjectFilter != null && !subject.equalsIgnoreCase(subjectFilter)) continue;
            UUID uuid = null;
            try {
                uuid = UUID.fromString(s.getString("uuid", ""));
            } catch (IllegalArgumentException ignored) {
            }
            out.add(new Entry(id, subject, uuid, s.getString("reason", ""), s.getLong("created"), s.getInt("seconds")));
        }
        out.sort(Comparator.comparingLong(Entry::created).reversed());
        return out;
    }

    public boolean isPending(String id) {
        return recorder.isPending(id);
    }

    public long secondsUntilReady(String id) {
        return (recorder.ticksUntilReady(id) + 19) / 20;
    }

    public void play(Player viewer, String id) {
        File f = new File(folder, id + ".mrr");
        if (!f.exists()) {
            viewer.sendMessage(Component.text("No replay with id " + id + ".", NamedTextColor.RED));
            return;
        }
        viewer.sendMessage(Component.text("Loading replay " + id + "...", NamedTextColor.GRAY));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Clip clip = ClipIO.read(f);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!viewer.isOnline()) return;
                    stop(viewer);
                    ReplaySession s = new ReplaySession(plugin, this, viewer, clip);
                    if (s.start()) sessions.put(viewer.getUniqueId(), s);
                });
            } catch (IOException e) {
                viewer.sendMessage(Component.text("Could not read replay " + id + ": " + e.getMessage(), NamedTextColor.RED));
            }
        });
    }

    public ReplaySession session(Player viewer) {
        return sessions.get(viewer.getUniqueId());
    }

    public void stop(Player viewer) {
        ReplaySession s = sessions.remove(viewer.getUniqueId());
        if (s != null) s.stop();
    }

    void ended(ReplaySession s) {
        sessions.values().remove(s);
    }

    public void stopAll() {
        for (ReplaySession s : new ArrayList<>(sessions.values())) s.stop();
        sessions.clear();
    }

    boolean isActor(Entity e) {
        return e.getPersistentDataContainer().has(actorKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        stop(e.getPlayer());
    }

    /** If the server stopped mid-replay, put the viewer back where they were. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        ReplaySession.restoreIfNeeded(this, e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onActorDamage(EntityDamageEvent e) {
        if (isActor(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onActorClick(PlayerInteractEntityEvent e) {
        if (isActor(e.getRightClicked())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onActorClickAt(PlayerInteractAtEntityEvent e) {
        if (isActor(e.getRightClicked())) e.setCancelled(true);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) if (isActor(en)) en.remove();
    }
}
