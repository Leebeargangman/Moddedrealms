package net.moddedrealms.testplayers;

import org.bukkit.configuration.file.FileConfiguration;

/** config.yml, read once on enable and on /testplayer reload. */
record Settings(
        int maxPlayers,
        int chunkRadius,
        double walkSpeed,
        double sprintSpeed,
        boolean invulnerable,
        boolean skinFromName,
        boolean respawnOnStart,
        String chatPrefix,
        String ollamaUrl,
        int aiIntervalSeconds,
        int aiMaxConcurrent,
        int aiTimeoutSeconds,
        boolean websiteEnabled,
        String websiteUrl,
        String managerKey,
        String hostName,
        String spawnAt) {

    static Settings from(FileConfiguration c) {
        return new Settings(
                clamp(c.getInt("max-players", 200), 1, 2000),
                clamp(c.getInt("chunk-radius", 3), 1, 16),
                Math.max(0.01, c.getDouble("walk-speed", 0.22)),
                Math.max(0.01, c.getDouble("sprint-speed", 0.28)),
                c.getBoolean("invulnerable", true),
                c.getBoolean("skin-from-name", false),
                c.getBoolean("respawn-on-start", true),
                c.getString("chat-prefix", "[Test] "),
                c.getString("ai.ollama-url", "http://127.0.0.1:11434").trim().replaceAll("/+$", ""),
                clamp(c.getInt("ai.interval-seconds", 20), 3, 3600),
                clamp(c.getInt("ai.max-concurrent", 2), 1, 32),
                clamp(c.getInt("ai.timeout-seconds", 90), 5, 600),
                c.getBoolean("website.enabled", false),
                c.getString("website.url", "https://moddedrealms.net").trim().replaceAll("/+$", ""),
                c.getString("website.manager-key", "").trim(),
                c.getString("website.host-name", "testserver").trim(),
                c.getString("website.spawn-at", "").trim());
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
