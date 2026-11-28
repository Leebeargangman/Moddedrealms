package net.moddedrealms.minigames;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** /mg for players (join, leave, stats, top) and staff (list, start, fill, end, rebuild, reload), and /hub. */
final class MgCommand implements TabExecutor {

    private final MinigamesPlugin plugin;

    MgCommand(MinigamesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        Player p = s instanceof Player pl ? pl : null;
        if (cmd.getName().equalsIgnoreCase("hub")) {
            if (p != null) plugin.manager().leave(p, false, true);
            return true;
        }
        String op = a.length == 0 ? "menu" : a[0].toLowerCase(Locale.ROOT);
        GameType direct = GameType.parse(op);
        if (direct != null && p != null) {
            plugin.manager().join(p, direct);
            return true;
        }
        switch (op) {
            case "menu" -> {
                if (p == null) return help(s);
                if (plugin.manager().matchOf(p) != null) plugin.manager().leave(p, false, true);
                plugin.hub().openMenu(p);
            }
            case "join", "play" -> {
                if (p == null || a.length < 2) return help(s);
                GameType t = GameType.parse(a[1]);
                if (t == null) s.sendMessage(Component.text("Games: tntrun, murder", NamedTextColor.RED));
                else plugin.manager().join(p, t);
            }
            case "leave", "hub" -> {
                if (p != null) plugin.manager().leave(p, false, true);
            }
            case "again" -> {
                if (p == null) return true;
                GameType t = plugin.manager().lastGame(p);
                if (t == null) plugin.hub().openMenu(p);
                else plugin.manager().join(p, t);
            }
            case "stats" -> {
                if (a.length >= 2) {
                    OfflinePlayer op2 = Bukkit.getOfflinePlayerIfCached(a[1]);
                    if (op2 == null) s.sendMessage(Component.text("No stats for " + a[1] + ".", NamedTextColor.RED));
                    else showStats(plugin, s, op2.getUniqueId(), op2.getName());
                } else if (p != null) {
                    showStats(plugin, s, p.getUniqueId(), p.getName());
                }
            }
            case "top" -> {
                GameType t = a.length >= 2 ? GameType.parse(a[1]) : null;
                List<String[]> top = plugin.stats().top(t, 10);
                s.sendMessage(Component.text(t == null ? "Top players (points)" : "Top " + t.title + " (wins)", NamedTextColor.GOLD, TextDecoration.BOLD));
                if (top.isEmpty()) s.sendMessage(Component.text("Nobody yet.", NamedTextColor.GRAY));
                for (int i = 0; i < top.size(); i++) {
                    s.sendMessage(Component.text((i + 1) + ". ", NamedTextColor.YELLOW).append(Component.text(top.get(i)[0], NamedTextColor.WHITE))
                            .append(Component.text("  " + top.get(i)[1], NamedTextColor.GRAY)));
                }
            }
            case "list", "start", "fill", "end", "rebuild", "reload", "bots", "debug" -> {
                if (!s.hasPermission("minigames.admin")) {
                    s.sendMessage(Component.text("Only staff can do that.", NamedTextColor.RED));
                    return true;
                }
                admin(s, p, op, a);
            }
            default -> help(s);
        }
        return true;
    }

    private void admin(CommandSender s, Player p, String op, String[] a) {
        switch (op) {
            case "list" -> {
                if (plugin.manager().matches().isEmpty()) s.sendMessage(Component.text("No matches running.", NamedTextColor.GRAY));
                for (Match m : plugin.manager().matches()) {
                    s.sendMessage(Component.text(m.type.title + " #" + m.slot + " " + m.state + ": " + m.humans() + " players, " + m.bots() + " bots, "
                            + m.alive().size() + " alive, " + (m.t / 20) + "s", NamedTextColor.YELLOW));
                }
            }
            case "start", "fill", "end" -> {
                Match m = p != null ? plugin.manager().matchOf(p) : null;
                if (m == null && a.length >= 2) {
                    GameType t = GameType.parse(a[1]);
                    for (Match x : plugin.manager().matches()) if (x.type == t) m = x;
                }
                if (m == null) {
                    s.sendMessage(Component.text("You're not in a match (from the console: /mg " + op + " <game>).", NamedTextColor.RED));
                    return;
                }
                if (op.equals("fill")) {
                    while (m.present() < m.botTarget && m.state == Match.State.WAITING) m.addBot();
                } else if (op.equals("start")) {
                    m.firstHumanAt = Math.min(m.firstHumanAt, m.t - 20L * 60);
                    if (m.state == Match.State.COUNTDOWN) m.countdown = Math.min(m.countdown, 3);
                } else {
                    m.finish(List.of(), Component.text("Stopped by staff", NamedTextColor.RED));
                }
                s.sendMessage(Component.text("Done.", NamedTextColor.GREEN));
            }
            case "debug" -> {
                for (Match m : plugin.manager().matches()) {
                    if (!(m instanceof Murder mm)) continue;
                    s.sendMessage(Component.text("Murder #" + m.slot + " " + m.state + " " + (m.t - m.startedAt) / 20 + "s:", NamedTextColor.GOLD));
                    for (MurderBrain b : mm.brains.values()) s.sendMessage(Component.text("  " + b.status(), NamedTextColor.GRAY));
                }
            }
            case "rebuild" -> {
                plugin.rebuild();
                s.sendMessage(Component.text("Rebuilt the hub and every arena not in use.", NamedTextColor.GREEN));
            }
            case "reload" -> {
                plugin.reloadConfig();
                Bot.setTag(plugin.getConfig().getString("bots.tag", "[BOT]"));
                s.sendMessage(Component.text("Reloaded config.yml.", NamedTextColor.GREEN));
            }
            case "bots" -> {
                if (a.length >= 2) {
                    plugin.getConfig().set("bots.enabled", a[1].equalsIgnoreCase("on") || a[1].equalsIgnoreCase("true"));
                    plugin.saveConfig();
                }
                s.sendMessage(Component.text("Bots fill empty spots: " + (plugin.getConfig().getBoolean("bots.enabled", true) ? "on" : "off"), NamedTextColor.YELLOW));
            }
            default -> {
            }
        }
    }

    static void showStats(MinigamesPlugin plugin, CommandSender to, UUID id, String name) {
        Stats st = plugin.stats();
        to.sendMessage(Component.text(name + "'s stats", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text("  " + st.points(id) + " points", NamedTextColor.YELLOW)));
        for (GameType t : GameType.values()) {
            int games = st.get(id, t, "games"), wins = st.get(id, t, "wins"), kills = st.get(id, t, "kills");
            Component line = Component.text(t.title + ": ", t.color).append(Component.text(wins + " wins / " + games + " games", NamedTextColor.WHITE));
            if (t == GameType.MURDER) line = line.append(Component.text(", " + kills + " kills", NamedTextColor.GRAY));
            to.sendMessage(line);
        }
    }

    private boolean help(CommandSender s) {
        s.sendMessage(Component.text("Minigames: ", NamedTextColor.GOLD).append(Component.text("/mg tntrun, /mg murder, /mg leave, /mg again, /mg stats, /mg top", NamedTextColor.GRAY)));
        if (s.hasPermission("minigames.admin")) s.sendMessage(Component.text("Staff: /mg list, start, fill, end [game], rebuild, reload, bots on|off", NamedTextColor.GRAY));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("hub")) return out;
        if (a.length == 1) {
            for (String o : new String[] {"tntrun", "murder", "join", "leave", "again", "stats", "top"}) if (o.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(o);
            if (s.hasPermission("minigames.admin")) for (String o : new String[] {"list", "start", "fill", "end", "rebuild", "reload", "bots"}) if (o.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(o);
        } else if (a.length == 2 && (a[0].equalsIgnoreCase("join") || a[0].equalsIgnoreCase("top") || a[0].equalsIgnoreCase("start") || a[0].equalsIgnoreCase("fill") || a[0].equalsIgnoreCase("end"))) {
            out.add("tntrun");
            out.add("murder");
        }
        return out;
    }
}
