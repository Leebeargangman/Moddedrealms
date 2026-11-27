package net.moddedrealms.smp.quest;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;

/** One player's quest progress, saved in playerdata/<uuid>.yml. */
public final class PlayerQuests {
    final File file;
    String day = "";
    String week = "";
    List<String> daily = new ArrayList<>();
    List<String> weekly = new ArrayList<>();
    final Map<String, Integer> progress = new HashMap<>();
    final Set<String> done = new HashSet<>();
    int storyIndex;
    int storyProgress;
    boolean storyIntroSeen;
    int streak;
    String lastStreakDay = "";
    int playMinutes;
    final Set<String> metToday = new HashSet<>();
    final Map<String, Integer> keys = new HashMap<>();
    boolean dirty;

    PlayerQuests(File file) {
        this.file = file;
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        day = y.getString("day", "");
        week = y.getString("week", "");
        daily = new ArrayList<>(y.getStringList("daily"));
        weekly = new ArrayList<>(y.getStringList("weekly"));
        var ps = y.getConfigurationSection("progress");
        if (ps != null) for (String k : ps.getKeys(false)) progress.put(k, ps.getInt(k));
        done.addAll(y.getStringList("done"));
        storyIndex = y.getInt("story.index");
        storyProgress = y.getInt("story.progress");
        storyIntroSeen = y.getBoolean("story.intro-seen");
        streak = y.getInt("streak.days");
        lastStreakDay = y.getString("streak.last", "");
        playMinutes = y.getInt("play-minutes");
        metToday.addAll(y.getStringList("met-today"));
        var ks = y.getConfigurationSection("keys");
        if (ks != null) for (String k : ks.getKeys(false)) keys.put(k, ks.getInt(k));
    }

    void save() throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.set("day", day);
        y.set("week", week);
        y.set("daily", daily);
        y.set("weekly", weekly);
        for (var e : progress.entrySet()) y.set("progress." + e.getKey(), e.getValue());
        y.set("done", new ArrayList<>(done));
        y.set("story.index", storyIndex);
        y.set("story.progress", storyProgress);
        y.set("story.intro-seen", storyIntroSeen);
        y.set("streak.days", streak);
        y.set("streak.last", lastStreakDay);
        y.set("play-minutes", playMinutes);
        y.set("met-today", new ArrayList<>(metToday));
        for (var e : keys.entrySet()) y.set("keys." + e.getKey(), e.getValue());
        file.getParentFile().mkdirs();
        y.save(file);
        dirty = false;
    }
}
