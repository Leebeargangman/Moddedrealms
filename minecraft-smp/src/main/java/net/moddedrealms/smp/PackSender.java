package net.moddedrealms.smp;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Sends the server resource pack while a player joins, in place of server.properties.
 *
 * <p>Players come in through Minehut's proxy, which never gives Bedrock players (Geyser/Floodgate) a pack, so with
 * require-resource-pack=true every Bedrock player was kicked. Here Bedrock players are skipped; Java players get the
 * pack before the world loads, as before. A Java player who declines it is let in and then kicked with an explanation:
 * a kick while still joining reaches the player through Minehut as "Network Protocol Error". A pack that fails to
 * download or load kicks nobody, the same as vanilla.
 */
final class PackSender implements Listener {
    private final JavaPlugin plugin;
    /** Java players who declined while joining, kicked once they're in. */
    private final Set<UUID> declined = ConcurrentHashMap.newKeySet();
    private volatile ResourcePackInfo info; // null: no pack is sent
    private volatile Component prompt;
    private volatile boolean required, skipBedrock;
    private volatile int waitSeconds;
    private volatile String declineMessage = "";

    PackSender(JavaPlugin plugin) {
        this.plugin = plugin;
        load(plugin.getConfig());
    }

    /** A string setting, falling back to the jar's config.yml (getString(path, def) would skip that fallback). */
    private static String str(FileConfiguration c, String path) {
        String s = c.getString(path);
        return s == null ? "" : s.trim();
    }

    void load(FileConfiguration c) {
        String url = str(c, "resource-pack.url");
        String sha1 = str(c, "resource-pack.sha1").toLowerCase(Locale.ROOT);
        required = c.getBoolean("resource-pack.required");
        skipBedrock = c.getBoolean("resource-pack.skip-bedrock");
        int wait = c.getInt("resource-pack.wait-seconds");
        waitSeconds = wait <= 0 ? 90 : Math.max(5, wait);
        declineMessage = str(c, "resource-pack.decline-message");
        String p = str(c, "resource-pack.prompt");
        prompt = p.isBlank() ? null : MiniMessage.miniMessage().deserialize(p);
        info = null;
        if (url.isEmpty()) return;
        try {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) throw new IllegalArgumentException();
            // Same id vanilla gives a server.properties pack, so clients keep their downloaded copy.
            UUID id = UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8));
            info = ResourcePackInfo.resourcePackInfo(id, uri, sha1.matches("[0-9a-f]{40}") ? sha1 : "");
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("resource-pack.url isn't a web address, so no resource pack is sent.");
            return;
        }
        String props = plugin.getServer().getResourcePack();
        if (props != null && !props.isBlank()) {
            plugin.getLogger().warning("server.properties also sends a resource pack (resource-pack=); clear it in AMP so players don't get two.");
        }
    }

    /** Floodgate players: a "." name prefix, or an id made from the Xbox id (top half all zeros). */
    static boolean bedrock(UUID id, String name) {
        return (id != null && id.getMostSignificantBits() == 0) || (name != null && name.startsWith("."));
    }

    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent e) {
        ResourcePackInfo pack = info;
        if (pack == null) return;
        PlayerConfigurationConnection c = e.getConnection();
        PlayerProfile profile = c.getProfile();
        if (skipBedrock && bedrock(profile.getId(), profile.getName())) return;
        CompletableFuture<ResourcePackStatus> done = new CompletableFuture<>();
        c.getAudience().sendResourcePacks(ResourcePackRequest.resourcePackRequest().packs(pack).required(required).replace(false)
                .prompt(prompt).callback((id, status, who) -> {
                    if (!status.intermediate()) done.complete(status);
                }));
        ResourcePackStatus status;
        try {
            // Joining waits here until the pack is loaded (or refused), so the world opens with it.
            status = done.get(waitSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException ex) {
            return;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
        }
        if (status == ResourcePackStatus.DECLINED && required && profile.getId() != null) declined.add(profile.getId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!declined.remove(p.getUniqueId())) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) p.kick(MiniMessage.miniMessage().deserialize(declineMessage));
        }, 20L);
    }
}
