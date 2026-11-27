package net.moddedrealms.smp.season;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.logging.Level;
import net.moddedrealms.smp.SafeYaml;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Season 1 "The Frozen Crown": the advent calendar, the launch countdown and cosmetics, driven by season.yml.
 * Per-player state (doors opened) is in season-data.yml; cosmetics in cosmetics.yml, which a season reset keeps.
 */
public final class SeasonManager {
    private final SmpPlugin plugin;
    private final File configFile, dataFile;
    private YamlConfiguration cfg = new YamlConfiguration();
    private YamlConfiguration data = new YamlConfiguration();
    private ZoneId zone = ZoneId.of("Europe/London");
    /** /advent date: a pretend "today" for testing (not saved). */
    private LocalDate testDate;

    private final Cosmetics cosmetics;
    private final Advent advent;
    private final Countdown countdown;

    public SeasonManager(SmpPlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "season.yml");
        this.dataFile = new File(plugin.getDataFolder(), "season-data.yml");
        if (!configFile.exists()) plugin.saveResource("season.yml", false);
        String err = reload();
        if (err != null) plugin.getLogger().severe("season.yml: " + err + " (the season is off until it's fixed)");
        if (dataFile.exists()) data = YamlConfiguration.loadConfiguration(dataFile);
        cosmetics = new Cosmetics(this);
        advent = new Advent(this);
        countdown = new Countdown(this);
        var pm = Bukkit.getPluginManager();
        pm.registerEvents(advent, plugin);
        pm.registerEvents(countdown, plugin);
        pm.registerEvents(cosmetics, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::second, 40L, 20L);
        if (Bukkit.getPluginManager().isPluginEnabled("ColonySMP")) {
            try {
                ColonyHook.register(this);
            } catch (LinkageError e) {
                plugin.getLogger().info("ColonySMP is older than 2.6: /colonyadmin event and season won't reach the season here.");
            }
        }
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                Placeholders.register(this);
            } catch (LinkageError e) {
                plugin.getLogger().warning("PlaceholderAPI is too old for the %mrsmp_...% placeholders: " + e);
            }
        }
    }

    /** Reads season.yml; a broken file is refused and the old settings stay. Null when it worked. */
    public String reload() {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            return String.valueOf(e.getMessage()).lines().findFirst().orElse("can't be read");
        }
        try {
            zone = ZoneId.of(y.getString("season.timezone", "Europe/London"));
        } catch (RuntimeException e) {
            return "season.timezone isn't a timezone";
        }
        cfg = y;
        String td = y.getString("advent.test-date", "");
        if (td != null && !td.isBlank()) {
            try {
                testDate = LocalDate.parse(td.trim());
            } catch (RuntimeException e) {
                return "advent.test-date must look like 2026-12-05";
            }
        }
        if (countdown != null) countdown.reset();
        return null;
    }

    private void second() {
        try {
            advent.second();
            countdown.second();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "The season update failed", e);
        }
    }

    public void shutdown() {
        if (Bukkit.getPluginManager().isPluginEnabled("ColonySMP")) {
            try {
                ColonyHook.unregister();
            } catch (LinkageError ignored) {
                // an older ColonySMP never had the hook
            }
        }
        countdown.remove();
        saveData();
    }

    /** The season's master switch (season.enabled): off, the advent calendar, the countdown and /titles are all closed. */
    public boolean on() {
        return cfg.getBoolean("season.enabled", false);
    }

    // ------------------------------------------------------------------ time

    public ZoneId zone() {
        return zone;
    }

    /** Today in UK time (or the test date). */
    public LocalDate today() {
        return testDate != null ? testDate : LocalDate.now(zone);
    }

    /** Now in UK time; with a test date, that day at the current time of day. */
    public ZonedDateTime now() {
        ZonedDateTime n = ZonedDateTime.now(zone);
        return testDate == null ? n : n.with(testDate);
    }

    public void testDate(LocalDate d) {
        testDate = d;
    }

    public LocalDate testDate() {
        return testDate;
    }

    // ------------------------------------------------------------------ files

    public YamlConfiguration cfg() {
        return cfg;
    }

    public YamlConfiguration data() {
        return data;
    }

    public void saveData() {
        try {
            SafeYaml.save(data, dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Couldn't save season-data.yml", e);
        }
    }

    public SmpPlugin plugin() {
        return plugin;
    }

    public Advent advent() {
        return advent;
    }

    public Countdown countdown() {
        return countdown;
    }

    public Cosmetics cosmetics() {
        return cosmetics;
    }
}
