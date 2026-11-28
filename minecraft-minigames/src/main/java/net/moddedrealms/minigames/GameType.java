package net.moddedrealms.minigames;

import java.util.Locale;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/** The games, where their arenas are and how many of them there are. */
enum GameType {
    TNTRUN("tntrun", "TNT Run", NamedTextColor.RED, Material.TNT, "Blocks vanish under your feet. Keep running, last one standing wins."),
    MURDER("murder", "Murder Mystery", NamedTextColor.DARK_RED, Material.IRON_SWORD, "One murderer, one detective. Find out who it is before it's too late.");

    final String id;
    final String title;
    final TextColor color;
    final Material icon;
    final String blurb;

    GameType(String id, String title, TextColor color, Material icon, String blurb) {
        this.id = id;
        this.title = title;
        this.color = color;
        this.icon = icon;
        this.blurb = blurb;
    }

    static GameType parse(String s) {
        String k = s.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
        return switch (k) {
            case "tntrun", "tnt", "run" -> TNTRUN;
            case "murder", "murdermystery", "mm", "mystery" -> MURDER;
            default -> null;
        };
    }

    ConfigurationSection cfg(MinigamesPlugin plugin) {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("games." + id);
        return c != null ? c : plugin.getConfig().createSection("games." + id);
    }

    boolean enabled(MinigamesPlugin plugin) {
        return cfg(plugin).getBoolean("enabled", true);
    }

    int min(MinigamesPlugin plugin) {
        return Math.max(this == MURDER ? 3 : 2, cfg(plugin).getInt("min-players", this == MURDER ? 4 : 2));
    }

    int max(MinigamesPlugin plugin) {
        return Math.max(min(plugin), Math.min(16, cfg(plugin).getInt("max-players", 10)));
    }

    int arenas(MinigamesPlugin plugin) {
        return Math.max(1, Math.min(8, cfg(plugin).getInt("arenas", 3)));
    }

    /** Arena slots sit far apart in the void world: TNT Run east of the hub, Murder Mystery south. */
    Location origin(MinigamesPlugin plugin, int slot) {
        return switch (this) {
            case TNTRUN -> plugin.at(1000 + slot * 200, 64, 0);
            case MURDER -> plugin.at(0, 64, 1000 + slot * 200);
        };
    }

    void buildArena(MinigamesPlugin plugin, int slot) {
        switch (this) {
            case TNTRUN -> TntRun.buildArena(origin(plugin, slot), true);
            case MURDER -> Mansion.build(origin(plugin, slot));
        }
    }

    Match create(MinigamesPlugin plugin, int slot) {
        return switch (this) {
            case TNTRUN -> new TntRun(plugin, slot);
            case MURDER -> new Murder(plugin, slot);
        };
    }
}
