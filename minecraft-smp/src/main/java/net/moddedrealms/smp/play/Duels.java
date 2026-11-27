package net.moddedrealms.smp.play;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.title.Title;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;

/**
 * 1v1 duels, anywhere: /duel <player> [wager] challenges someone nearby, they click to accept, a ring forms around
 * the two of them and a countdown starts. Nobody dies and nothing drops: the hit that would kill ends the duel, and
 * both are healed. The winner takes both wagers. Leaving the ring, logging out or teleporting forfeits. Duels are
 * exempt from the town's assault laws and from colony land's no-PvP rule, and outsiders can't hit a duellist.
 */
public final class Duels implements Listener, TabExecutor {
    private final SmpPlugin plugin;
    private final File file;
    private final YamlConfiguration stats;
    private final Map<UUID, Challenge> challenges = new HashMap<>(); // by the challenged player
    private final Map<UUID, Duel> byPlayer = new HashMap<>();
    /** After a duel the two can't hurt each other for a few seconds (a swing already on its way, a sore loser). */
    private final Map<String, Long> truce = new HashMap<>();

    private static String pair(UUID x, UUID y) {
        return x.compareTo(y) < 0 ? x + ":" + y : y + ":" + x;
    }

    record Challenge(UUID from, UUID to, double wager, long at) {
    }

    final class Duel {
        final UUID a, b;
        final double wager;
        final Location centre;
        final double radius;
        long started; // 0 while counting down
        final long until;
        int task = -1;

        Duel(UUID a, UUID b, double wager, Location centre, double radius, long until) {
            this.a = a;
            this.b = b;
            this.wager = wager;
            this.centre = centre;
            this.radius = radius;
            this.until = until;
        }

        UUID other(UUID u) {
            return u.equals(a) ? b : a;
        }
    }

    public Duels(SmpPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "duels.yml");
        this.stats = YamlConfiguration.loadConfiguration(file);
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return plugin.getConfig();
    }

    /** True while these two are fighting each other in a duel (the town's laws don't apply). */
    public boolean dueling(Player x, Player y) {
        Duel d = byPlayer.get(x.getUniqueId());
        return d != null && d.other(x.getUniqueId()).equals(y.getUniqueId());
    }

    public boolean inDuel(Player p) {
        return byPlayer.containsKey(p.getUniqueId());
    }

    public int wins(UUID u) {
        return stats.getInt(u + ".wins");
    }

    public Map<UUID, Integer> allWins() {
        Map<UUID, Integer> out = new HashMap<>();
        for (String k : stats.getKeys(false)) {
            try {
                out.put(UUID.fromString(k), stats.getInt(k + ".wins"));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!(sender instanceof Player p)) return true;
        if (!cfg().getBoolean("duels.enabled", true)) {
            p.sendMessage(SmpPlugin.mm("<gray>Duels are off."));
            return true;
        }
        String sub = a.length == 0 ? "" : a[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "", "help" -> help(p);
            case "accept" -> accept(p);
            case "decline", "deny" -> {
                Challenge c = challenges.remove(p.getUniqueId());
                if (c == null) p.sendMessage(SmpPlugin.mm("<gray>Nobody has challenged you."));
                else {
                    p.sendMessage(SmpPlugin.mm("<gray>Declined."));
                    Player f = Bukkit.getPlayer(c.from());
                    if (f != null) f.sendMessage(SmpPlugin.mm("<gray>" + p.getName() + " declined your duel."));
                }
            }
            case "forfeit", "leave", "quit" -> {
                Duel d = byPlayer.get(p.getUniqueId());
                if (d == null) p.sendMessage(SmpPlugin.mm("<gray>You aren't in a duel."));
                else end(d, d.other(p.getUniqueId()), "forfeited");
            }
            case "stats" -> {
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : p;
                UUID u = t != null ? t.getUniqueId() : Bukkit.getOfflinePlayer(a[1]).getUniqueId();
                String n = t != null ? t.getName() : a[1];
                p.sendMessage(SmpPlugin.mm("<gold>" + n + "'s duels: <white>" + stats.getInt(u + ".wins") + "</white> won, <white>" + stats.getInt(u + ".losses")
                        + "</white> lost, best run <white>" + stats.getInt(u + ".best-run") + "</white>."));
            }
            default -> challenge(p, a);
        }
        return true;
    }

    private void help(Player p) {
        p.sendMessage(SmpPlugin.mm("<gold><bold>Duels</bold></gold> <gray>· 1v1, nobody dies, nothing drops.\n"
                + "<white>/duel <player> [wager]</white> <gray>challenge someone within " + (int) cfg().getDouble("duels.challenge-range", 30) + " blocks\n"
                + "<white>/duel accept</white> <gray>· <white>/duel decline</white> <gray>· <white>/duel forfeit</white> <gray>· <white>/duel stats [player]</white>"));
    }

    private String why(Player p) {
        if (byPlayer.containsKey(p.getUniqueId())) return p.getName() + " is already in a duel.";
        if (plugin.town() != null && plugin.town().isJailed(p)) return p.getName() + " is in jail.";
        if (p.isDead() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR || p.getGameMode() == org.bukkit.GameMode.CREATIVE) return p.getName() + " can't duel right now.";
        return null;
    }

    private void challenge(Player p, String[] a) {
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null || t == p || !p.canSee(t)) {
            p.sendMessage(SmpPlugin.mm("<red>Nobody called " + SmpPlugin.MM.escapeTags(a[0]) + " is online."));
            return;
        }
        double range = cfg().getDouble("duels.challenge-range", 30);
        if (t.getWorld() != p.getWorld() || t.getLocation().distanceSquared(p.getLocation()) > range * range) {
            p.sendMessage(SmpPlugin.mm("<red>" + t.getName() + " needs to be within " + (int) range + " blocks."));
            return;
        }
        String no = why(p) != null ? why(p) : why(t);
        if (no != null) {
            p.sendMessage(SmpPlugin.mm("<red>" + no));
            return;
        }
        double wager = 0;
        if (a.length > 1) {
            try {
                wager = Math.floor(Double.parseDouble(a[1].replace("$", "")));
            } catch (NumberFormatException e) {
                p.sendMessage(SmpPlugin.mm("<red>The wager must be a number: /duel " + t.getName() + " 100"));
                return;
            }
            double max = cfg().getDouble("duels.max-wager", 100000);
            if (wager < 0 || wager > max) {
                p.sendMessage(SmpPlugin.mm("<red>Wagers go from $0 to $" + (long) max + "."));
                return;
            }
            if (wager > 0 && plugin.town().money.balance(p) < wager) {
                p.sendMessage(SmpPlugin.mm("<red>You don't have $" + (long) wager + "."));
                return;
            }
        }
        challenges.put(t.getUniqueId(), new Challenge(p.getUniqueId(), t.getUniqueId(), wager, System.currentTimeMillis()));
        String w = wager > 0 ? " for <gold>$" + (long) wager + "</gold> each" : "";
        p.sendMessage(SmpPlugin.mm("<yellow>⚔ You challenged " + t.getName() + " to a duel" + w + ". <gray>They have 60 seconds to accept."));
        t.sendMessage(SmpPlugin.mm("<yellow>⚔ " + p.getName() + " challenges you to a duel" + w + "! <gray>(nobody dies, nothing drops) "
                + "<click:run_command:'/duel accept'><hover:show_text:'<gray>Fight!'><green><u>[Accept]</u></green></hover></click> "
                + "<click:run_command:'/duel decline'><red><u>[Decline]</u></red></click>"));
        t.playSound(t.getLocation(), Sound.ITEM_GOAT_HORN_SOUND_0, 0.6f, 1.0f);
    }

    private void accept(Player t) {
        Challenge c = challenges.remove(t.getUniqueId());
        if (c == null || System.currentTimeMillis() - c.at() > 60_000L) {
            t.sendMessage(SmpPlugin.mm("<gray>There's no duel to accept (challenges last a minute)."));
            return;
        }
        Player p = Bukkit.getPlayer(c.from());
        if (p == null) {
            t.sendMessage(SmpPlugin.mm("<gray>They've gone."));
            return;
        }
        String no = why(p) != null ? why(p) : why(t);
        double range = cfg().getDouble("duels.challenge-range", 30);
        if (no == null && (p.getWorld() != t.getWorld() || p.getLocation().distanceSquared(t.getLocation()) > range * range)) no = "You're too far apart now.";
        if (no != null) {
            t.sendMessage(SmpPlugin.mm("<red>" + no));
            return;
        }
        double wager = c.wager();
        if (wager > 0) {
            if (plugin.town().money.balance(t) < wager) {
                t.sendMessage(SmpPlugin.mm("<red>You don't have the $" + (long) wager + " wager."));
                return;
            }
            if (!plugin.town().money.pay(p, wager)) {
                t.sendMessage(SmpPlugin.mm("<red>" + p.getName() + " can't cover the wager any more."));
                return;
            }
            if (!plugin.town().money.pay(t, wager)) {
                plugin.town().money.give(p, wager);
                t.sendMessage(SmpPlugin.mm("<red>Payment failed."));
                return;
            }
        }
        Location centre = p.getLocation().add(t.getLocation()).multiply(0.5);
        double radius = cfg().getDouble("duels.ring-radius", 14);
        long until = System.currentTimeMillis() + cfg().getLong("duels.max-seconds", 180) * 1000L;
        Duel d = new Duel(p.getUniqueId(), t.getUniqueId(), wager, centre, radius, until);
        byPlayer.put(d.a, d);
        byPlayer.put(d.b, d);
        for (Player x : List.of(p, t)) {
            heal(x);
            x.sendMessage(SmpPlugin.mm("<yellow>⚔ Duel: " + p.getName() + " vs " + t.getName() + (wager > 0 ? " for $" + (long) (wager * 2) : "")
                    + ". <gray>Stay inside the ring. Hit that would kill ends it."));
        }
        int[] count = {3};
        d.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(d, count), 0L, 20L).getTaskId();
    }

    private void tick(Duel d, int[] count) {
        Player a = Bukkit.getPlayer(d.a), b = Bukkit.getPlayer(d.b);
        if (a == null || b == null) return; // quit handler ends it
        ring(d);
        if (d.started == 0) {
            if (count[0] > 0) {
                for (Player x : List.of(a, b)) {
                    x.showTitle(Title.title(SmpPlugin.mm("<gold><bold>" + count[0]), SmpPlugin.mm("<gray>get ready"), Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100))));
                    x.playSound(x.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
                }
                count[0]--;
                return;
            }
            d.started = System.currentTimeMillis();
            for (Player x : List.of(a, b)) {
                x.showTitle(Title.title(SmpPlugin.mm("<red><bold>FIGHT!"), SmpPlugin.mm(""), Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(300))));
                x.playSound(x.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.4f, 1.6f);
            }
            return;
        }
        if (System.currentTimeMillis() > d.until) {
            end(d, null, "ran out of time");
            return;
        }
        for (Player x : List.of(a, b)) {
            if (x.getWorld() != d.centre.getWorld() || flat(x.getLocation(), d.centre) > d.radius * d.radius) {
                end(d, d.other(x.getUniqueId()), "left the ring");
                return;
            }
        }
    }

    private static double flat(Location l, Location c) {
        double dx = l.getX() - c.getX(), dz = l.getZ() - c.getZ();
        return dx * dx + dz * dz;
    }

    private void ring(Duel d) {
        var w = d.centre.getWorld();
        if (w == null) return;
        int n = 48;
        for (int i = 0; i < n; i++) {
            double ang = Math.PI * 2 * i / n;
            Location at = d.centre.clone().add(Math.cos(ang) * d.radius, 0, Math.sin(ang) * d.radius);
            at.setY(w.getHighestBlockYAt(at) + 1.2);
            w.spawnParticle(Particle.FLAME, at, 1, 0, 0.1, 0, 0);
        }
    }

    private void heal(Player x) {
        var max = x.getAttribute(Attribute.MAX_HEALTH);
        x.setHealth(max == null ? 20 : max.getValue());
        x.setFireTicks(0);
        x.setFoodLevel(Math.max(x.getFoodLevel(), 18));
        for (PotionEffect e : new ArrayList<>(x.getActivePotionEffects())) {
            if (e.getType().equals(org.bukkit.potion.PotionEffectType.POISON) || e.getType().equals(org.bukkit.potion.PotionEffectType.WITHER)) x.removePotionEffect(e.getType());
        }
    }

    /** Ends the duel; winner null is a draw (wagers back). */
    private void end(Duel d, UUID winner, String how) {
        if (byPlayer.get(d.a) != d) return;
        byPlayer.remove(d.a);
        byPlayer.remove(d.b);
        if (d.task >= 0) Bukkit.getScheduler().cancelTask(d.task);
        truce.put(pair(d.a, d.b), System.currentTimeMillis() + cfg().getLong("duels.truce-seconds", 8) * 1000L);
        Player a = Bukkit.getPlayer(d.a), b = Bukkit.getPlayer(d.b);
        for (Player x : new Player[]{a, b}) if (x != null) heal(x);
        if (winner == null) {
            if (d.wager > 0) {
                plugin.town().money.give(Bukkit.getOfflinePlayer(d.a), d.wager);
                plugin.town().money.give(Bukkit.getOfflinePlayer(d.b), d.wager);
            }
            for (Player x : new Player[]{a, b}) if (x != null) x.sendMessage(SmpPlugin.mm("<gray>The duel " + how + ": a draw" + (d.wager > 0 ? ", wagers returned." : ".")));
            return;
        }
        UUID loser = d.other(winner);
        if (d.wager > 0) plugin.town().money.give(Bukkit.getOfflinePlayer(winner), d.wager * 2);
        stats.set(winner + ".wins", stats.getInt(winner + ".wins") + 1);
        stats.set(winner + ".run", stats.getInt(winner + ".run") + 1);
        stats.set(winner + ".best-run", Math.max(stats.getInt(winner + ".best-run"), stats.getInt(winner + ".run")));
        stats.set(winner + ".name", name(winner));
        stats.set(loser + ".losses", stats.getInt(loser + ".losses") + 1);
        stats.set(loser + ".run", 0);
        stats.set(loser + ".name", name(loser));
        try {
            net.moddedrealms.smp.SafeYaml.save(stats, file);
        } catch (IOException ignored) {
        }
        String wn = name(winner), ln = name(loser);
        String line = "<gold>⚔ " + wn + " beat " + ln + " in a duel" + (how.equals("won") ? "" : " (" + ln + " " + how + ")")
                + (d.wager > 0 ? " and took <yellow>$" + (long) (d.wager * 2) + "</yellow>" : "") + "!";
        double hear = cfg().getDouble("duels.announce-range", 80);
        for (Player x : Bukkit.getOnlinePlayers()) {
            if (x.getUniqueId().equals(d.a) || x.getUniqueId().equals(d.b) || x.getWorld() == d.centre.getWorld() && x.getLocation().distanceSquared(d.centre) < hear * hear) {
                x.sendMessage(SmpPlugin.mm(line));
            }
        }
        Player w = Bukkit.getPlayer(winner);
        if (w != null) w.playSound(w.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
        int run = stats.getInt(winner + ".run");
        if (run >= 5 && run % 5 == 0) Bukkit.broadcast(SmpPlugin.mm("<gold>⚔ " + wn + " has won " + run + " duels in a row!"));
        if (plugin.town() != null) plugin.town().news(wn + " beat " + ln + " in a duel" + (d.wager > 0 ? " for $" + (long) (d.wager * 2) : "") + ".");
    }

    private static String name(UUID u) {
        Player p = Bukkit.getPlayer(u);
        String n = p != null ? p.getName() : Bukkit.getOfflinePlayer(u).getName();
        return n == null ? "?" : n;
    }

    // ------------------------------------------------------------------ the fight

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    /** Duellists may hit each other wherever they are (colony land and town rules cancel PvP earlier); nobody else may join in. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player v)) return;
        Player a = attacker(e.getDamager());
        Duel dv = byPlayer.get(v.getUniqueId());
        if (a == null || a == v) return;
        Duel da = byPlayer.get(a.getUniqueId());
        if (dv != null && dv == da) {
            if (dv.started == 0) {
                e.setCancelled(true); // still counting down
                return;
            }
            e.setCancelled(false);
            return;
        }
        if (dv != null || da != null) {
            e.setCancelled(true); // outsiders stay out
            return;
        }
        Long t = truce.get(pair(a.getUniqueId(), v.getUniqueId()));
        if (t != null) {
            if (t > System.currentTimeMillis()) e.setCancelled(true);
            else truce.remove(pair(a.getUniqueId(), v.getUniqueId()));
        }
    }

    /** The blow that would kill ends the duel instead. Runs last, after armour and the other plugins. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLethal(EntityDamageEvent e) {
        if (e.isCancelled() || !(e.getEntity() instanceof Player v)) return;
        Duel d = byPlayer.get(v.getUniqueId());
        if (d == null || d.started == 0) return;
        if (v.getHealth() - e.getFinalDamage() > 0.5) return;
        e.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> end(d, d.other(v.getUniqueId()), "won"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        challenges.remove(e.getPlayer().getUniqueId());
        Duel d = byPlayer.get(e.getPlayer().getUniqueId());
        if (d != null) end(d, d.other(e.getPlayer().getUniqueId()), "logged out");
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Duel d = byPlayer.get(e.getPlayer().getUniqueId());
        if (d == null) return;
        var c = e.getCause();
        if (c == PlayerTeleportEvent.TeleportCause.ENDER_PEARL || c == PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT) {
            if (e.getTo().getWorld() == d.centre.getWorld() && flat(e.getTo(), d.centre) <= d.radius * d.radius) return;
        }
        end(d, d.other(e.getPlayer().getUniqueId()), "teleported away");
    }

    /** Server stopping: every duel is a draw. */
    public void shutdown() {
        for (Duel d : new ArrayList<>(byPlayer.values())) end(d, null, "was stopped by a restart");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String x : List.of("accept", "decline", "forfeit", "stats")) if (x.startsWith(a[0].toLowerCase())) out.add(x);
            for (Player p : Bukkit.getOnlinePlayers()) if (p != s && p.getName().toLowerCase().startsWith(a[0].toLowerCase())) out.add(p.getName());
        }
        return out;
    }
}
