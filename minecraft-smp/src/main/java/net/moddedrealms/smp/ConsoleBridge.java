package net.moddedrealms.smp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.bukkit.Bukkit;

/**
 * AMP has no command-line way to type into the server console, so admin tools (and Claude, over SSH) append
 * commands to plugins/ModdedrealmsSMP/console.in. Lines are run as the console once a second and the file is
 * emptied. Only someone who can already write the server's files can use it.
 */
final class ConsoleBridge {
    private final SmpPlugin plugin;
    private final File file;

    ConsoleBridge(SmpPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "console.in");
    }

    void start() {
        try {
            if (!file.exists()) file.createNewFile();
        } catch (IOException ignored) {
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::poll, 40L, 20L);
    }

    private void poll() {
        if (!file.exists() || file.length() == 0) return;
        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            Files.write(file.toPath(), new byte[0]);
        } catch (IOException e) {
            return;
        }
        for (String line : lines) {
            String cmd = line.strip();
            if (cmd.isEmpty() || cmd.startsWith("#")) continue;
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            plugin.getLogger().info("console.in: " + cmd);
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("console.in command failed: " + ex.getMessage());
            }
        }
    }
}
