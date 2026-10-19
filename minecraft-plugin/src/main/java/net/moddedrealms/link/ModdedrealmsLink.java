package net.moddedrealms.link;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Moddedrealms Link: players run /link CODE with a code from the website to prove they own their
 * Minecraft account. Ranks are sent to the website when a player links, when they join, and every
 * few minutes, so staff ranks unlock the website's staff dashboard.
 */
public final class ModdedrealmsLink extends JavaPlugin implements Listener {

    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9]{6}$");
    private static final long LINK_COOLDOWN_MS = 5_000;

    private final Gson gson = new Gson();
    private final Map<UUID, Long> lastLinkAttempt = new ConcurrentHashMap<>();
    private HttpClient http;
    private BukkitTask syncTask;

    private String websiteUrl;
    private String apiKey;
    private String rankSource;
    private List<String> ranks;
    private String defaultRank;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        if (syncTask != null) syncTask.cancel();
    }

    private void loadSettings() {
        reloadConfig();
        websiteUrl = getConfig().getString("website-url", "").trim().replaceAll("/+$", "");
        apiKey = getConfig().getString("api-key", "").trim();
        rankSource = getConfig().getString("rank-source", "auto").trim().toLowerCase(Locale.ROOT);
        ranks = getConfig().getStringList("ranks");
        defaultRank = getConfig().getString("default-rank", "default");

        if (!isConfigured()) {
            getLogger().warning("Set website-url and api-key in plugins/ModdedrealmsLink/config.yml, then run /mrlink reload.");
        }

        if (syncTask != null) syncTask.cancel();
        syncTask = null;
        long minutes = getConfig().getLong("sync-interval-minutes", 5);
        if (minutes > 0) {
            long ticks = minutes * 60 * 20;
            syncTask = Bukkit.getScheduler().runTaskTimer(this, this::syncOnlinePlayers, ticks, ticks);
        }
    }

    private boolean isConfigured() {
        return websiteUrl.startsWith("http") && !apiKey.isEmpty() && !apiKey.startsWith("paste-");
    }

    /* ---------------- Commands ---------------- */

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("mrlink")) {
            return adminCommand(sender, args);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /link.");
            return true;
        }
        if (args.length != 1 || !CODE.matcher(args[0]).matches()) {
            player.sendMessage(Component.text("Usage: /link <code>", NamedTextColor.YELLOW));
            player.sendMessage(Component.text("Get your 6-character code by signing in at " + websiteUrl + "/account", NamedTextColor.GRAY));
            return true;
        }
        if (!isConfigured()) {
            player.sendMessage(Component.text("Account linking isn't set up yet. Please tell a staff member.", NamedTextColor.RED));
            return true;
        }
        long now = System.currentTimeMillis();
        Long last = lastLinkAttempt.get(player.getUniqueId());
        if (last != null && now - last < LINK_COOLDOWN_MS) {
            player.sendMessage(Component.text("Please wait a few seconds before trying again.", NamedTextColor.RED));
            return true;
        }
        lastLinkAttempt.put(player.getUniqueId(), now);

        JsonObject body = playerJson(player);
        body.addProperty("code", args[0].toUpperCase(Locale.ROOT));
        player.sendMessage(Component.text("Linking your account...", NamedTextColor.GRAY));

        UUID uuid = player.getUniqueId();
        post("/api/plugin/link", body).whenComplete((response, error) -> Bukkit.getScheduler().runTask(this, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null) return;
            if (error != null) {
                getLogger().log(Level.WARNING, "Could not reach the website: " + error.getMessage());
                online.sendMessage(Component.text("Couldn't reach the website. Please try again in a minute.", NamedTextColor.RED));
                return;
            }
            boolean ok = response.has("ok") && response.get("ok").getAsBoolean();
            String message = response.has("message") ? response.get("message").getAsString()
                    : (ok ? "Your account is linked!" : "Linking failed.");
            online.sendMessage(Component.text(message, ok ? NamedTextColor.GREEN : NamedTextColor.RED));
            if (ok) {
                online.sendMessage(Component.text("Refresh your account page on the website to see it.", NamedTextColor.GRAY));
            }
        }));
        return true;
    }

    private boolean adminCommand(CommandSender sender, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("reload")) {
            loadSettings();
            sender.sendMessage(Component.text("Moddedrealms Link config reloaded.", NamedTextColor.GREEN));
            return true;
        }
        if (sub.equals("test")) {
            if (!isConfigured()) {
                sender.sendMessage(Component.text("Set website-url and api-key in config.yml first, then /mrlink reload.", NamedTextColor.RED));
                return true;
            }
            sender.sendMessage(Component.text("Contacting " + websiteUrl + "...", NamedTextColor.GRAY));
            post("/api/plugin/ping", new JsonObject()).whenComplete((response, error) -> Bukkit.getScheduler().runTask(this, () -> {
                if (error != null) {
                    sender.sendMessage(Component.text("Failed: " + error.getMessage(), NamedTextColor.RED));
                } else {
                    boolean ok = response.has("ok") && response.get("ok").getAsBoolean();
                    String message = response.has("message") ? response.get("message").getAsString() : String.valueOf(response);
                    sender.sendMessage(Component.text((ok ? "OK: " : "Failed: ") + message, ok ? NamedTextColor.GREEN : NamedTextColor.RED));
                }
                if (sender instanceof Player player) {
                    sender.sendMessage(Component.text("Your rank is sent as: " + rankOf(player), NamedTextColor.GRAY));
                }
            }));
            return true;
        }
        sender.sendMessage(Component.text("Usage: /mrlink <test|reload>", NamedTextColor.YELLOW));
        return true;
    }

    /* ---------------- Rank sync ---------------- */

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!isConfigured()) return;
        JsonArray players = new JsonArray();
        players.add(playerJson(event.getPlayer()));
        sendSync(players);
    }

    private void syncOnlinePlayers() {
        if (!isConfigured() || Bukkit.getOnlinePlayers().isEmpty()) return;
        JsonArray players = new JsonArray();
        for (Player player : Bukkit.getOnlinePlayers()) players.add(playerJson(player));
        sendSync(players);
    }

    private void sendSync(JsonArray players) {
        JsonObject body = new JsonObject();
        body.add("players", players);
        post("/api/plugin/sync", body).exceptionally(error -> {
            getLogger().fine("Rank sync failed: " + error.getMessage());
            return null;
        });
    }

    private JsonObject playerJson(Player player) {
        JsonObject json = new JsonObject();
        json.addProperty("uuid", player.getUniqueId().toString());
        json.addProperty("name", player.getName());
        json.addProperty("rank", rankOf(player));
        return json;
    }

    /** Works out a player's rank. Must be called on the main server thread. */
    private String rankOf(Player player) {
        String rank = null;
        if (rankSource.equals("auto") || rankSource.equals("luckperms")) rank = luckPermsGroup(player);
        if (rank == null && (rankSource.equals("auto") || rankSource.equals("vault"))) rank = vaultGroup(player);
        if (rank == null && (rankSource.equals("auto") || rankSource.equals("permissions"))) rank = permissionGroup(player);
        return rank == null || rank.isBlank() ? defaultRank : rank;
    }

    /** LuckPerms primary group, looked up by reflection so LuckPerms stays optional. */
    private String luckPermsGroup(Player player) {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) return null;
        try {
            Object api = Class.forName("net.luckperms.api.LuckPermsProvider").getMethod("get").invoke(null);
            Object userManager = Class.forName("net.luckperms.api.LuckPerms").getMethod("getUserManager").invoke(api);
            Object user = Class.forName("net.luckperms.api.model.user.UserManager")
                    .getMethod("getUser", UUID.class)
                    .invoke(userManager, player.getUniqueId());
            if (user == null) return null;
            return (String) Class.forName("net.luckperms.api.model.user.User").getMethod("getPrimaryGroup").invoke(user);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            getLogger().fine("LuckPerms lookup failed: " + e);
            return null;
        }
    }

    /** Vault primary group, looked up by reflection so Vault stays optional. */
    private String vaultGroup(Player player) {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return null;
        try {
            Class<?> permissionClass = Class.forName("net.milkbowl.vault.permission.Permission");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(permissionClass);
            if (rsp == null) return null;
            Method primary = permissionClass.getMethod("getPrimaryGroup", Player.class);
            return (String) primary.invoke(rsp.getProvider(), player);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            getLogger().fine("Vault lookup failed: " + e);
            return null;
        }
    }

    /** First configured rank the player explicitly has via the group.<name> permission (set by LuckPerms and others). */
    private String permissionGroup(Player player) {
        for (String rank : ranks) {
            String node = "group." + rank.toLowerCase(Locale.ROOT);
            if (player.isPermissionSet(node) && player.hasPermission(node)) return rank;
        }
        return null;
    }

    /* ---------------- HTTP ---------------- */

    private CompletableFuture<JsonObject> post(String path, JsonObject body) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(websiteUrl + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("User-Agent", "ModdedrealmsLink/" + getPluginMeta().getVersion())
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new IllegalStateException("website-url isn't a valid URL"));
        }
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            JsonElement parsed;
            try {
                parsed = gson.fromJson(response.body(), JsonElement.class);
            } catch (RuntimeException e) {
                parsed = null;
            }
            if (parsed != null && parsed.isJsonObject()) return parsed.getAsJsonObject();
            JsonObject fallback = new JsonObject();
            fallback.addProperty("ok", false);
            fallback.addProperty("message", "The website answered with HTTP " + response.statusCode() + ".");
            return fallback;
        });
    }
}
