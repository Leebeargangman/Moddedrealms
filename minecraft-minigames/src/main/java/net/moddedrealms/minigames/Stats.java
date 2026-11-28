package net.moddedrealms.minigames;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Wins, games and kills per player and game, in stats.yml. Only real players have stats. A win in a match with
 * other real players is worth 10 points, a win against only bots 4, so the leaderboard favours real matches.
 */
final class Stats {

    private final MinigamesPlugin plugin;
    private final File file;
    private final YamlConfiguration y;
    private boolean dirty;

    Stats(MinigamesPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
        this.y = YamlConfiguration.loadConfiguration(file);
    }

    void game(GameType type, UUID id, String name, boolean won, int kills, boolean vsHumans) {
        String base = "players." + id + ".";
        y.set(base + "name", name);
        String g = base + type.id + ".";
        y.set(g + "games", y.getInt(g + "games") + 1);
        if (won) {
            y.set(g + "wins", y.getInt(g + "wins") + 1);
            if (vsHumans) y.set(g + "wins-vs-players", y.getInt(g + "wins-vs-players") + 1);
            y.set(base + "points", y.getInt(base + "points") + (vsHumans ? 10 : 4));
        }
        if (kills > 0) {
            y.set(g + "kills", y.getInt(g + "kills") + kills);
            y.set(base + "points", y.getInt(base + "points") + kills);
        }
        dirty = true;
    }

    int get(UUID id, GameType type, String what) {
        return y.getInt("players." + id + "." + (type == null ? "" : type.id + ".") + what);
    }

    int points(UUID id) {
        return y.getInt("players." + id + ".points");
    }

    /** {name, value} for the top n, by points (type null) or by wins in one game. */
    List<String[]> top(GameType type, int n) {
        ConfigurationSection s = y.getConfigurationSection("players");
        List<String[]> rows = new ArrayList<>();
        if (s == null) return rows;
        for (String k : s.getKeys(false)) {
            int v = type == null ? s.getInt(k + ".points") : s.getInt(k + "." + type.id + ".wins");
            if (v > 0) rows.add(new String[] {s.getString(k + ".name", "?"), String.valueOf(v)});
        }
        rows.sort(Comparator.comparingInt((String[] r) -> Integer.parseInt(r[1])).reversed());
        return rows.size() > n ? rows.subList(0, n) : rows;
    }

    void saveIfDirty() {
        if (dirty) save();
    }

    void save() {
        try {
            y.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save stats.yml: " + e.getMessage());
        }
    }
}
