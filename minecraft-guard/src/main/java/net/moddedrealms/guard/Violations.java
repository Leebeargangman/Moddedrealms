package net.moddedrealms.guard;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.moddedrealms.guard.check.Check;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Takes flags from the checks and decides what happens: alert staff, save a replay, set back, ban.
 * Bans need repeated certain flags and pass every safety gate first.
 */
public final class Violations {
    private final GuardPlugin plugin;
    private final Set<UUID> alertsOff = new HashSet<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MRGuard-log");
        t.setDaemon(true);
        return t;
    });
    private final File logFile;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    Violations(GuardPlugin plugin) {
        this.plugin = plugin;
        this.logFile = new File(plugin.getDataFolder(), "flags.log");
    }

    public boolean toggleAlerts(UUID id) {
        if (!alertsOff.remove(id)) {
            alertsOff.add(id);
            return false;
        }
        return true;
    }

    /** Safe to call from any thread. */
    public void flag(Player p, Check check, boolean certain, String detail) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> flag(p, check, certain, detail));
            return;
        }
        if (!p.isOnline() || !check.enabled || p.hasPermission("mrguard.bypass")) return;
        PlayerState s = plugin.state(p);
        if (System.currentTimeMillis() < s.exemptUntilMillis) return;
        Settings cfg = plugin.settings();
        long now = System.currentTimeMillis();
        double tps = Bukkit.getTPS()[0];
        int ping = p.getPing();
        s.totalFlags.merge(check, 1, Integer::sum);

        // Can this flag count towards a ban?
        String noBan = null;
        if (!certain) noBan = "suspect";
        else if (!check.ban) noBan = "alert-only check";
        else if (tps < cfg.minTps) noBan = "server lag";
        else if (ping > cfg.maxPing) noBan = "high ping";
        else if (s.bedrock && !cfg.banBedrock) noBan = "bedrock";
        else if (!s.modernClient && plugin.packetsAvailable() && !cfg.banOldClients) noBan = "old client";

        String replayId = autoReplay(p, s, check);
        int vl = 0;
        boolean banNow = false;
        if (noBan == null) {
            ArrayDeque<Long> q = s.certain.computeIfAbsent(check, k -> new ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst() > check.windowMillis) q.pollFirst();
            q.addLast(now);
            vl = q.size();
            if (vl >= check.threshold) {
                banNow = true;
                q.clear();
            }
        }

        String line = String.format("%s %s %s %s [%s] ping=%d tps=%.1f vl=%d/%d%s replay=%s",
                LocalDateTime.now().format(TS), p.getName(), check.display, detail,
                certain ? "CERTAIN" : "suspect", ping, tps, vl, check.threshold,
                noBan != null && certain ? " (no ban: " + noBan + ")" : "", replayId == null ? "-" : replayId);
        log(line);

        if (now - s.lastAlert.getOrDefault(check, 0L) >= cfg.alertCooldownMillis || banNow) {
            s.lastAlert.put(check, now);
            alert(p, check, certain, detail, ping, tps, vl, replayId);
        }

        if (banNow) {
            if (plugin.mode() == GuardPlugin.Mode.ENFORCE) {
                String id = replayId != null ? replayId : plugin.replays().saveClip(p, "Ban: " + check.display, false);
                plugin.punishments().ban(p, check, id);
            } else {
                log(LocalDateTime.now().format(TS) + " WOULD BAN " + p.getName() + " for " + check.display + " (shadow mode)");
                staff(Component.text("[SHADOW] ", NamedTextColor.DARK_PURPLE)
                        .append(Component.text(p.getName() + " would have been banned for " + check.display, NamedTextColor.LIGHT_PURPLE)));
            }
        }
    }

    /** Fly/speed set-back, only in enforce mode and only for certain flags. */
    public void setback(Player p, PlayerState s) {
        if (plugin.mode() != GuardPlugin.Mode.ENFORCE || s.lastSafe == null) return;
        if (s.lastSafe.getWorld() != p.getWorld()) return;
        p.teleport(s.lastSafe);
        s.awaitingGround = true;
        s.awaitingSince = plugin.tick();
    }

    private String autoReplay(Player p, PlayerState s, Check check) {
        long now = System.currentTimeMillis();
        String pending = plugin.replays().pendingClipFor(p.getUniqueId());
        if (pending != null) return pending;
        if (now - s.lastAutoReplay < plugin.settings().autoReplayCooldownMillis) return null;
        s.lastAutoReplay = now;
        return plugin.replays().saveClip(p, "Flag: " + check.display, true);
    }

    private void alert(Player p, Check check, boolean certain, String detail, int ping, double tps, int vl, String replayId) {
        boolean shadow = plugin.mode() == GuardPlugin.Mode.SHADOW;
        Component hover = Component.text()
                .append(Component.text(check.display + "\n", NamedTextColor.GOLD))
                .append(Component.text(detail + "\n", NamedTextColor.WHITE))
                .append(Component.text("Ping " + ping + "ms, TPS " + String.format("%.1f", tps) + "\n", NamedTextColor.GRAY))
                .append(Component.text(certain ? "Certain flag " + vl + "/" + check.threshold : "Suspect: alert only", NamedTextColor.GRAY))
                .build();
        var msg = Component.text()
                .append(Component.text(shadow ? "[SHADOW] " : "[Guard] ", shadow ? NamedTextColor.DARK_PURPLE : NamedTextColor.RED))
                .append(Component.text(p.getName(), NamedTextColor.WHITE))
                .append(Component.text(" failed ", NamedTextColor.GRAY))
                .append(Component.text(check.display, certain ? NamedTextColor.RED : NamedTextColor.YELLOW).hoverEvent(HoverEvent.showText(hover)))
                .append(Component.text(certain ? " ✔" : " ?", certain ? NamedTextColor.RED : NamedTextColor.YELLOW));
        if (replayId != null) {
            msg.append(Component.text(" [Replay]", NamedTextColor.AQUA, TextDecoration.BOLD)
                    .hoverEvent(HoverEvent.showText(Component.text("Watch replay " + replayId + " (ready ~" + plugin.settings().secondsAfter + "s after the flag)")))
                    .clickEvent(ClickEvent.runCommand("/replay play " + replayId)));
        }
        msg.append(Component.text(" [TP]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/tp " + p.getName())));
        staff(msg.build());
    }

    public void staff(Component c) {
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.hasPermission("mrguard.staff") && !alertsOff.contains(o.getUniqueId())) o.sendMessage(c);
        }
    }

    public void log(String line) {
        plugin.getLogger().info(line);
        io.execute(() -> {
            try (FileWriter w = new FileWriter(logFile, true)) {
                w.write(line + System.lineSeparator());
            } catch (IOException ignored) {
            }
        });
    }
}
