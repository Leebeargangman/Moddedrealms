package net.moddedrealms.network;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

/**
 * Network-wide shortcut commands on the Velocity proxy: /minigames (and any others in shortcuts.properties)
 * send the player to that backend server from wherever they are.
 */
@Plugin(id = "moddedrealms-network", name = "ModdedrealmsNetwork", version = "1.0.0",
        description = "Network shortcut commands such as /minigames", authors = {"Moddedrealms"})
public final class NetworkPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dir;

    @Inject
    public NetworkPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dir = dir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        Map<String, String> shortcuts = load();
        for (Map.Entry<String, String> en : shortcuts.entrySet()) {
            String[] parts = en.getValue().split(",");
            String server = parts[0].trim();
            String label = parts.length > 1 ? parts[1].trim() : server;
            List<String> aliases = new ArrayList<>();
            for (int i = 2; i < parts.length; i++) if (!parts[i].isBlank()) aliases.add(parts[i].trim());
            CommandMeta meta = proxy.getCommandManager().metaBuilder(en.getKey()).aliases(aliases.toArray(new String[0])).plugin(this).build();
            proxy.getCommandManager().register(meta, new Shortcut(server, label));
            logger.info("/{} sends players to server '{}'", en.getKey(), server);
        }
    }

    /** shortcuts.properties: command = server,label[,alias...] */
    private Map<String, String> load() {
        Path f = dir.resolve("shortcuts.properties");
        Properties p = new Properties();
        try {
            if (!Files.exists(f)) {
                Files.createDirectories(dir);
                try (OutputStream out = Files.newOutputStream(f)) {
                    Properties d = new Properties();
                    d.setProperty("minigames", "minigames,the minigames,minigame");
                    d.store(out, "command = server,label[,alias,alias...]  (server = a name from velocity.toml [servers])");
                }
            }
            try (InputStream in = Files.newInputStream(f)) {
                p.load(in);
            }
        } catch (IOException ex) {
            logger.warn("Couldn't read {}: {}", f, ex.getMessage());
            p.setProperty("minigames", "minigames,the minigames,minigame");
        }
        Map<String, String> out = new TreeMap<>();
        for (String k : p.stringPropertyNames()) out.put(k.trim(), p.getProperty(k));
        return out;
    }

    private final class Shortcut implements SimpleCommand {
        private final String server;
        private final String label;

        Shortcut(String server, String label) {
            this.server = server;
            this.label = label;
        }

        @Override
        public void execute(Invocation inv) {
            if (!(inv.source() instanceof Player player)) {
                inv.source().sendMessage(Component.text("Only players can use this."));
                return;
            }
            Optional<RegisteredServer> target = proxy.getServer(server);
            if (target.isEmpty()) {
                player.sendMessage(Component.text("That server isn't set up on this network.", NamedTextColor.RED));
                return;
            }
            boolean already = player.getCurrentServer().map(s -> s.getServerInfo().getName().equalsIgnoreCase(server)).orElse(false);
            if (already) {
                player.sendMessage(Component.text("You're already in " + label + "!", NamedTextColor.YELLOW));
                return;
            }
            player.sendMessage(Component.text("Sending you to " + label + "...", NamedTextColor.GREEN));
            player.createConnectionRequest(target.get()).connect().thenAccept(r -> {
                if (!r.isSuccessful()) {
                    Component why = r.getReasonComponent().orElse(Component.text("it may be restarting"));
                    player.sendMessage(Component.text("Couldn't get you to " + label + ": ", NamedTextColor.RED).append(why));
                }
            });
        }

        @Override
        public boolean hasPermission(Invocation inv) {
            return true;
        }
    }
}
