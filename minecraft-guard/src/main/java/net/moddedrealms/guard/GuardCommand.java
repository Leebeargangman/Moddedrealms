package net.moddedrealms.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.moddedrealms.guard.check.Check;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

final class GuardCommand implements TabExecutor {
    private final GuardPlugin plugin;

    GuardCommand(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        boolean admin = sender.hasPermission("mrguard.admin");
        switch (sub) {
            case "status" -> {
                say(sender, "ModdedrealmsGuard " + plugin.getPluginMeta().getVersion() + " — mode: " + plugin.mode()
                        + (plugin.packetsAvailable() ? "" : " (PacketEvents missing: packet checks off)"), NamedTextColor.GOLD);
                StringBuilder b = new StringBuilder();
                for (Check c : Check.values()) {
                    b.append(c.enabled ? (c.ban ? "§c" : "§e") : "§8").append(c.display).append(c.ban ? " (ban " + c.threshold + ")" : " (alert)").append("§7, ");
                }
                sender.sendMessage(b.substring(0, b.length() - 4));
            }
            case "mode" -> {
                if (!admin) return no(sender);
                if (args.length < 2) return say(sender, "/mrguard mode <shadow|enforce>", NamedTextColor.YELLOW);
                GuardPlugin.Mode m = args[1].equalsIgnoreCase("enforce") ? GuardPlugin.Mode.ENFORCE : GuardPlugin.Mode.SHADOW;
                plugin.setMode(m);
                plugin.violations().staff(Component.text("[Guard] " + sender.getName() + " switched the anticheat to " + m + " mode.", NamedTextColor.GOLD));
                if (!(sender instanceof Player)) say(sender, "Mode is now " + m, NamedTextColor.GREEN);
            }
            case "alerts" -> {
                if (!(sender instanceof Player p)) return true;
                boolean on = plugin.violations().toggleAlerts(p.getUniqueId());
                say(sender, "Anticheat alerts " + (on ? "on" : "off") + ".", NamedTextColor.GREEN);
            }
            case "history", "info" -> {
                if (args.length < 2) return say(sender, "/mrguard history <player>", NamedTextColor.YELLOW);
                OfflinePlayer t = Bukkit.getOfflinePlayer(args[1]);
                say(sender, t.getName() + ": " + plugin.punishments().offences(t.getUniqueId()) + " offence(s)", NamedTextColor.GOLD);
                for (String h : plugin.punishments().history(t.getUniqueId())) say(sender, " - " + h, NamedTextColor.GRAY);
                Player online = t.getPlayer();
                if (online != null) {
                    PlayerState s = plugin.state(online);
                    StringBuilder b = new StringBuilder("Flags this session: ");
                    for (Map.Entry<Check, Integer> e : s.totalFlags.entrySet()) b.append(e.getKey().display).append(' ').append(e.getValue()).append(", ");
                    say(sender, s.totalFlags.isEmpty() ? "No flags this session." : b.substring(0, b.length() - 2), NamedTextColor.GRAY);
                    say(sender, (s.bedrock ? "Bedrock player. " : "") + (s.modernClient ? "Client 1.21.2+." : "Older client (or not known yet)."), NamedTextColor.GRAY);
                }
            }
            case "unban" -> {
                if (!admin) return no(sender);
                if (args.length < 2) return say(sender, "/mrguard unban <player> [forgive]", NamedTextColor.YELLOW);
                OfflinePlayer t = Bukkit.getOfflinePlayer(args[1]);
                boolean was = plugin.punishments().unban(t);
                if (args.length > 2 && args[2].equalsIgnoreCase("forgive")) plugin.punishments().forgive(t.getUniqueId());
                say(sender, was ? "Unbanned " + t.getName() + "." : t.getName() + " was not banned.", NamedTextColor.GREEN);
                plugin.violations().log("UNBAN " + t.getName() + " by " + sender.getName());
            }
            case "exempt" -> {
                if (!admin) return no(sender);
                if (args.length < 3) return say(sender, "/mrguard exempt <player> <minutes>", NamedTextColor.YELLOW);
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) return say(sender, args[1] + " is not online.", NamedTextColor.RED);
                long min;
                try {
                    min = Long.parseLong(args[2]);
                } catch (NumberFormatException e) {
                    return say(sender, "Minutes must be a number.", NamedTextColor.RED);
                }
                plugin.state(t).exemptUntilMillis = System.currentTimeMillis() + min * 60_000L;
                say(sender, t.getName() + " is exempt for " + min + " minute(s).", NamedTextColor.GREEN);
            }
            case "debug" -> {
                if (!admin) return no(sender);
                Player t = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                if (t == null) return say(sender, "/mrguard debug <online player>", NamedTextColor.YELLOW);
                PlayerState s = plugin.state(t);
                s.debug = !s.debug;
                say(sender, "Movement debug for " + t.getName() + " " + (s.debug ? "on (see console)" : "off") + ".", NamedTextColor.GREEN);
            }
            case "reload" -> {
                if (!admin) return no(sender);
                plugin.loadSettings();
                say(sender, "Reloaded. Mode: " + plugin.mode(), NamedTextColor.GREEN);
            }
            default -> say(sender, "/mrguard <status|mode|alerts|history|unban|exempt|reload>", NamedTextColor.YELLOW);
        }
        return true;
    }

    private static boolean say(CommandSender s, String m, NamedTextColor c) {
        s.sendMessage(Component.text(m, c));
        return true;
    }

    private static boolean no(CommandSender s) {
        return say(s, "You need mrguard.admin for that.", NamedTextColor.RED);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("status", "mode", "alerts", "history", "unban", "exempt", "debug", "reload")) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("mode")) {
            out.addAll(List.of("shadow", "enforce"));
        } else if (args.length == 2) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
        }
        return out;
    }
}
