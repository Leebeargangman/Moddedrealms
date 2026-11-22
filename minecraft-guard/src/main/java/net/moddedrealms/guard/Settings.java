package net.moddedrealms.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.file.FileConfiguration;

/** The non-check settings from config.yml. */
public final class Settings {
    public final double minTps;
    public final int maxPing;
    public final int graceTicks;
    public final boolean banBedrock;
    public final boolean banOldClients;
    public final List<Long> ladderMillis = new ArrayList<>();
    public final long maxBanMillis;
    public final String banMessage;
    public final long alertCooldownMillis;
    public final int bufferSeconds;
    public final int secondsBefore;
    public final int secondsAfter;
    public final double replayRadius;
    public final int keepDays;
    public final long autoReplayCooldownMillis;
    public final long reportCooldownMillis;

    Settings(FileConfiguration c) {
        minTps = c.getDouble("safety.min-tps", 18.0);
        maxPing = c.getInt("safety.max-ping", 300);
        graceTicks = c.getInt("safety.grace-seconds", 5) * 20;
        banBedrock = c.getBoolean("safety.ban-bedrock", false);
        banOldClients = c.getBoolean("safety.ban-old-clients", false);
        maxBanMillis = parseDuration(c.getString("bans.max", "3d"));
        for (String s : c.getStringList("bans.ladder")) ladderMillis.add(Math.min(parseDuration(s), maxBanMillis));
        if (ladderMillis.isEmpty()) ladderMillis.add(Math.min(parseDuration("6h"), maxBanMillis));
        banMessage = c.getString("bans.message", "<red>Banned for <check>");
        alertCooldownMillis = c.getLong("alerts.cooldown-seconds", 3) * 1000L;
        bufferSeconds = Math.max(30, c.getInt("replays.buffer-seconds", 120));
        secondsBefore = Math.min(bufferSeconds, c.getInt("replays.seconds-before", 60));
        secondsAfter = Math.max(0, c.getInt("replays.seconds-after", 20));
        replayRadius = c.getDouble("replays.radius", 48);
        keepDays = c.getInt("replays.keep-days", 14);
        autoReplayCooldownMillis = c.getLong("replays.auto-cooldown-seconds", 90) * 1000L;
        reportCooldownMillis = c.getLong("report.cooldown-seconds", 60) * 1000L;
    }

    /** "30m", "6h", "1d", "90s" -> milliseconds. */
    public static long parseDuration(String s) {
        if (s == null || s.isBlank()) return 0;
        s = s.trim().toLowerCase(Locale.ROOT);
        long mult = switch (s.charAt(s.length() - 1)) {
            case 's' -> 1000L;
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            case 'w' -> 604_800_000L;
            default -> -1L;
        };
        String num = mult < 0 ? s : s.substring(0, s.length() - 1);
        if (mult < 0) mult = 1000L;
        try {
            return (long) (Double.parseDouble(num) * mult);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String formatDuration(long millis) {
        long m = millis / 60_000L;
        long d = m / 1440, h = (m % 1440) / 60, min = m % 60;
        StringBuilder b = new StringBuilder();
        if (d > 0) b.append(d).append("d ");
        if (h > 0) b.append(h).append("h ");
        if (min > 0 || b.isEmpty()) b.append(min).append("m");
        return b.toString().trim();
    }
}
