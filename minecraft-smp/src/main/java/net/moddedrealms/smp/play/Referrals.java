package net.moddedrealms.smp.play;

import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Bring a friend (referrals.enabled): /invite shows a player's own short code. A new player types /invite <code> (or
 * picks who invited them in Elder Rowan's menu) within their first referrals.claim-within-minutes of play; once they've
 * played referrals.minutes-needed minutes, both get their rewards. One referrer per new player, a cap a day per
 * referrer, and nothing when both come from the same address. Addresses are kept only as salted hashes.
 */
public final class Referrals implements Listener, CommandExecutor {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final SmpPlugin plugin;
    private final File file;
    private final YamlConfiguration y;
    private final SecureRandom random = new SecureRandom();
    private boolean dirty;

    public Referrals(SmpPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "referrals.yml");
        this.y = YamlConfiguration.loadConfiguration(file);
        if (!y.isString("salt")) {
            byte[] b = new byte[16];
            random.nextBytes(b);
            y.set("salt", HexFormat.of().formatHex(b));
            dirty = true;
        }
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("referrals.enabled", false);
    }

    private int cfg(String key, int def) {
        return plugin.getConfig().getInt("referrals." + key, def);
    }

    private String msg(String key, String def) {
        String s = plugin.getConfig().getString("referrals.messages." + key);
        return s == null ? def : s;
    }

    private List<String> rewards(String key, List<String> def) {
        List<String> l = plugin.getConfig().getStringList("referrals." + key);
        return l.isEmpty() ? def : l;
    }

    public void save() {
        if (!dirty) return;
        try {
            net.moddedrealms.smp.SafeYaml.save(y, file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save referrals.yml: " + e.getMessage());
        }
    }

    private static String p(UUID u) {
        return "players." + u;
    }

    // ───────────── codes ─────────────

    /** The player's own code, made the first time it's asked for. */
    public String code(UUID u) {
        String c = y.getString(p(u) + ".code");
        if (c != null) return c;
        int len = Math.max(4, Math.min(8, cfg("code-length", 5)));
        for (int tries = 0; tries < 50; tries++) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < len; i++) b.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            String code = b.toString();
            if (y.contains("codes." + code)) continue;
            y.set("codes." + code, u.toString());
            y.set(p(u) + ".code", code);
            dirty = true;
            return code;
        }
        return "?";
    }

    private String hash(Player pl) {
        try {
            var a = pl.getAddress();
            if (a == null || a.getAddress() == null) return null;
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(y.getString("salt", "").getBytes(StandardCharsets.UTF_8));
            d.update(a.getAddress().getHostAddress().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d.digest()).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player pl = e.getPlayer();
        String h = hash(pl);
        if (h != null) {
            y.set(p(pl.getUniqueId()) + ".address", h);
            dirty = true;
        }
        // a referrer paid while they were away
        UUID u = pl.getUniqueId();
        List<String> owed = y.getStringList(p(u) + ".owed");
        if (!owed.isEmpty()) {
            y.set(p(u) + ".owed", null);
            dirty = true;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!pl.isOnline()) return;
                plugin.rewards().give(pl, owed);
                pl.sendMessage(SmpPlugin.mm(msg("owed", "<gold>★ <white>A friend you invited played their first half hour while you were away. Your reward is in!")));
            }, 60L);
        }
    }

    // ───────────── claiming ─────────────

    /** Minutes this player has played on the SMP (from Insights). */
    private long minutesPlayed(UUID u) {
        return plugin.insights() == null ? 0 : plugin.insights().playedSeconds(u) / 60;
    }

    /** Can this player still name who invited them? */
    public boolean canClaim(Player pl) {
        return enabled() && !y.contains(p(pl.getUniqueId()) + ".by") && minutesPlayed(pl.getUniqueId()) <= cfg("claim-within-minutes", 60);
    }

    /** Records who invited this new player. Null when done; otherwise why not. */
    public String claim(Player pl, UUID by) {
        UUID u = pl.getUniqueId();
        if (!enabled()) return msg("off", "Inviting friends isn't open yet.");
        if (y.contains(p(u) + ".by")) return msg("already", "You've already said who invited you.");
        if (minutesPlayed(u) > cfg("claim-within-minutes", 60)) return msg("too-late", "Invite codes only work in your first hour here.");
        if (by == null) return msg("unknown", "No player has that code.");
        if (by.equals(u)) return msg("self", "That's your own code! Give it to a friend.");
        if (u.toString().equals(y.getString(p(by) + ".by"))) return msg("each-other", "You can't invite each other: only one of you brought the other.");
        String mine = y.getString(p(u) + ".address"), theirs = y.getString(p(by) + ".address");
        if (plugin.getConfig().getBoolean("referrals.same-address-check", true) && mine != null && mine.equals(theirs)) {
            return msg("same-address", "You and that player are on the same connection, so the invite reward doesn't apply.");
        }
        y.set(p(u) + ".by", by.toString());
        y.set(p(u) + ".at", System.currentTimeMillis());
        dirty = true;
        save();
        OfflinePlayer o = Bukkit.getOfflinePlayer(by);
        int need = cfg("minutes-needed", 30);
        pl.sendMessage(SmpPlugin.mm(msg("claimed", "<green>Thanks! <white>{player}</white> invited you. Play {minutes} minutes and you both get a reward.")
                .replace("{player}", SmpPlugin.MM.escapeTags(String.valueOf(o.getName()))).replace("{minutes}", String.valueOf(need))));
        Player op = o.getPlayer();
        if (op != null) {
            op.sendMessage(SmpPlugin.mm(msg("claimed-referrer", "<green>{player} joined with your invite! <gray>Once they've played {minutes} minutes you both get a reward.")
                    .replace("{player}", SmpPlugin.MM.escapeTags(pl.getName())).replace("{minutes}", String.valueOf(need))));
            op.playSound(op.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.5f);
        }
        plugin.getLogger().info(pl.getName() + " was invited by " + o.getName() + " (referral).");
        return null;
    }

    // ───────────── paying out ─────────────

    /** Every minute: new players who were invited and have now played long enough get paid, and so does their friend. */
    public void tick() {
        if (!enabled()) return;
        int need = cfg("minutes-needed", 30);
        for (Player pl : Bukkit.getOnlinePlayers()) {
            UUID u = pl.getUniqueId();
            String byS = y.getString(p(u) + ".by");
            if (byS == null || y.getBoolean(p(u) + ".paid") || minutesPlayed(u) < need) continue;
            UUID by;
            try {
                by = UUID.fromString(byS);
            } catch (IllegalArgumentException e) {
                continue;
            }
            y.set(p(u) + ".paid", true);
            dirty = true;
            plugin.rewards().give(pl, rewards("newcomer-rewards", List.of("money:100", "key:common:1")));
            pl.sendMessage(SmpPlugin.mm(msg("paid", "<gold>★ <white>You've played your first {minutes} minutes. Here's your welcome reward for coming with a friend!")
                    .replace("{minutes}", String.valueOf(need))));
            pl.playSound(pl.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
            String day = "days." + LocalDate.now(ZoneOffset.UTC) + "." + by;
            int today = y.getInt(day);
            OfflinePlayer o = Bukkit.getOfflinePlayer(by);
            if (today >= cfg("max-per-day", 5)) {
                plugin.getLogger().info("Referral of " + pl.getName() + " by " + o.getName() + ": the referrer is over today's cap, newcomer paid only.");
                continue;
            }
            y.set(day, today + 1);
            y.set(p(by) + ".referred", y.getInt(p(by) + ".referred") + 1);
            List<String> theirs = rewards("referrer-rewards", List.of("money:150", "key:common:1"));
            Player op = o.getPlayer();
            if (op != null) {
                plugin.rewards().give(op, theirs);
                op.sendMessage(SmpPlugin.mm(msg("paid-referrer", "<gold>★ <white>{player} has played {minutes} minutes. Thanks for bringing a friend: here's your reward!")
                        .replace("{player}", SmpPlugin.MM.escapeTags(pl.getName())).replace("{minutes}", String.valueOf(need))));
                op.playSound(op.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
            } else {
                List<String> owed = new java.util.ArrayList<>(y.getStringList(p(by) + ".owed"));
                owed.addAll(theirs);
                y.set(p(by) + ".owed", owed);
            }
            plugin.getLogger().info("Referral paid: " + pl.getName() + " and " + o.getName() + ".");
        }
        save();
    }

    // ───────────── /invite ─────────────

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player pl)) {
            s.sendMessage("Players only.");
            return true;
        }
        if (!enabled()) {
            pl.sendMessage(SmpPlugin.mm("<gray>" + msg("off", "Inviting friends isn't open yet.")));
            return true;
        }
        if (a.length == 0) {
            String code = code(pl.getUniqueId());
            save();
            int need = cfg("minutes-needed", 30);
            for (String line : List.of(
                    msg("code-1", "<gold><bold>Bring a friend"),
                    msg("code-2", "<gray>Your code: <white><bold>{code}"),
                    msg("code-3", "<gray>Your friend types <white>/invite {code}</white> in their first hour here."),
                    msg("code-4", "<gray>When they've played {minutes} minutes, you both get a reward."),
                    msg("code-5", "<gray>Friends invited so far: <white>{n}")))
                pl.sendMessage(SmpPlugin.mm(line.replace("{code}", code).replace("{minutes}", String.valueOf(need))
                        .replace("{n}", String.valueOf(y.getInt(p(pl.getUniqueId()) + ".referred")))));
            return true;
        }
        String code = a[0].toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        String owner = y.getString("codes." + code);
        UUID by = null;
        if (owner != null) {
            try {
                by = UUID.fromString(owner);
            } catch (IllegalArgumentException ignored) {
                // a broken entry: treated as unknown
            }
        }
        String err = claim(pl, by);
        if (err != null) pl.sendMessage(SmpPlugin.mm("<red>" + SmpPlugin.MM.escapeTags(err)));
        return true;
    }

    /** For /mrsmp onboarding. */
    public String stats() {
        int claimed = 0, paid = 0;
        var s = y.getConfigurationSection("players");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                if (s.contains(k + ".by")) claimed++;
                if (s.getBoolean(k + ".paid")) paid++;
            }
        }
        return "<gold>Referrals " + (enabled() ? "<green>on" : "<red>off") + "</gold> <gray>· " + claimed + " invited players, " + paid + " paid out";
    }
}
