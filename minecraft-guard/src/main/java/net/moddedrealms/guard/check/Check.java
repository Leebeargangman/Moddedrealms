package net.moddedrealms.guard.check;

import org.bukkit.configuration.ConfigurationSection;

/** Every check, with the settings read from config.yml (checks.<key>). */
public enum Check {
    FLY("fly", "Fly"),
    SPEED("speed", "Speed"),
    REACH("reach", "Reach"),
    MULTI_AURA("multi-aura", "Multi-Aura"),
    BAD_PACKETS("bad-packets", "Bad Packets"),
    TIMER("timer", "Timer"),
    KILLAURA("killaura", "KillAura"),
    AUTOCLICKER("autoclicker", "AutoClicker"),
    XRAY("xray", "X-Ray");

    public final String key;
    public final String display;

    public boolean enabled = true;
    public boolean ban;
    public int threshold = 5;
    public long windowMillis = 60_000;
    private ConfigurationSection section;

    Check(String key, String display) {
        this.key = key;
        this.display = display;
    }

    public static void load(ConfigurationSection checks) {
        for (Check c : values()) {
            ConfigurationSection s = checks == null ? null : checks.getConfigurationSection(c.key);
            c.section = s;
            if (s == null) continue;
            c.enabled = s.getBoolean("enabled", true);
            c.ban = s.getBoolean("ban", false);
            c.threshold = Math.max(1, s.getInt("threshold", 5));
            c.windowMillis = Math.max(1, s.getLong("window-seconds", 60)) * 1000L;
        }
    }

    public double num(String path, double def) {
        return section == null ? def : section.getDouble(path, def);
    }
}
