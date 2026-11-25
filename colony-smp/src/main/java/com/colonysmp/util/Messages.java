package com.colonysmp.util;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The player-facing text in messages.yml (MiniMessage). Every new or changed line of the plugin lives there, so it
 * can be edited without a recompile; a key missing from the file falls back to the one shipped in the jar.
 *
 * <p>Placeholders are written {name} and filled from key/value pairs. Values are escaped, so a colony or player
 * name can never smuggle in formatting or click actions.
 */
public final class Messages {

    private static YamlConfiguration current = new YamlConfiguration();
    private static YamlConfiguration defaults = new YamlConfiguration();

    private Messages() {}

    /**
     * Reads messages.yml (written from the jar the first time). On a broken file nothing changes: the old text
     * stays in use and the error is returned so /colonyadmin reload can show it. Null means it loaded.
     */
    public static String load(JavaPlugin plugin) {
        File f = new File(plugin.getDataFolder(), "messages.yml");
        if (!f.exists()) plugin.saveResource("messages.yml", false);
        YamlConfiguration def = new YamlConfiguration();
        var in = plugin.getResource("messages.yml");
        if (in != null) {
            try (var r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                def.load(r);
            } catch (IOException | InvalidConfigurationException e) {
                return "the messages.yml inside the jar is broken: " + e.getMessage();
            }
        }
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(f);
        } catch (IOException | InvalidConfigurationException e) {
            return "messages.yml: " + e.getMessage().lines().findFirst().orElse("can't be read");
        }
        defaults = def;
        current = y;
        return null;
    }

    /** The text for a key with its placeholders filled in, still as MiniMessage. */
    public static String get(String key, Object... kv) {
        String s = current.getString(key);
        if (s == null) s = defaults.getString(key);
        if (s == null) return "<red>[missing message: " + key + "]";
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", Text.esc(String.valueOf(kv[i + 1])));
        }
        return s;
    }

    /** A list of lines (menu lore, book pages). */
    public static java.util.List<String> lines(String key, Object... kv) {
        java.util.List<String> raw = current.isList(key) ? current.getStringList(key) : defaults.getStringList(key);
        java.util.List<String> out = new java.util.ArrayList<>(raw.size());
        for (String s : raw) {
            for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", Text.esc(String.valueOf(kv[i + 1])));
            out.add(s);
        }
        return out;
    }

    /** With the [Colony] prefix. */
    public static void send(CommandSender to, String key, Object... kv) {
        Text.send(to, get(key, kv));
    }

    /** Without the prefix. */
    public static void raw(CommandSender to, String key, Object... kv) {
        Text.raw(to, get(key, kv));
    }

    public static void bar(Player p, String key, Object... kv) {
        Text.bar(p, get(key, kv));
    }
}
