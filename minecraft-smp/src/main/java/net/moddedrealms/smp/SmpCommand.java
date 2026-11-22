package net.moddedrealms.smp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.moddedrealms.smp.npc.NpcDef;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

final class SmpCommand implements TabExecutor {
    private final SmpPlugin plugin;

    SmpCommand(SmpPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        boolean admin = sender.hasPermission("mrsmp.admin");
        switch (sub) {
            case "choose" -> {
                if (sender instanceof Player p && args.length > 1) {
                    try {
                        plugin.dialogues().choose(p, Integer.parseInt(args[1]));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            case "talk" -> {
                if (!(sender instanceof Player p) || args.length < 2) return true;
                NpcDef d = plugin.npcs().get(args[1].toLowerCase());
                if (d != null && (admin || nearNpc(p, d))) plugin.dialogues().interact(p, d);
            }
            case "reload" -> {
                if (!admin) return no(sender);
                plugin.reloadEverything();
                say(sender, "Reloaded config, quests, dialogues and NPCs.", NamedTextColor.GREEN);
            }
            case "spawn" -> {
                if (!admin) return no(sender);
                String what = args.length > 1 ? args[1].toLowerCase() : "";
                try {
                    if (what.equals("build")) {
                        World w;
                        int x, y, z;
                        if (args.length >= 5) {
                            x = Integer.parseInt(args[2]);
                            y = Integer.parseInt(args[3]);
                            z = Integer.parseInt(args[4]);
                            w = args.length >= 6 ? Bukkit.getWorld(args[5]) : sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
                        } else if (sender instanceof Player p) {
                            w = p.getWorld();
                            x = p.getLocation().getBlockX();
                            y = p.getLocation().getBlockY() - 1;
                            z = p.getLocation().getBlockZ();
                        } else {
                            return say(sender, "/mrsmp spawn build <x> <floorY> <z> [world]", NamedTextColor.YELLOW);
                        }
                        if (w == null) return say(sender, "World not found.", NamedTextColor.RED);
                        say(sender, plugin.plaza().build(w, x, y, z), NamedTextColor.GREEN);
                    } else if (what.equals("undo")) {
                        say(sender, plugin.plaza().undo(), NamedTextColor.GREEN);
                    } else {
                        say(sender, "/mrsmp spawn build [x floorY z [world]]  — builds the plaza centred on you (the block under your feet is the floor)", NamedTextColor.YELLOW);
                        say(sender, "/mrsmp spawn undo  — removes it and restores what was there", NamedTextColor.YELLOW);
                    }
                } catch (IOException | NumberFormatException e) {
                    say(sender, e.getMessage(), NamedTextColor.RED);
                }
            }
            case "key", "keys" -> {
                if (!admin) return no(sender);
                if (args.length < 4) return say(sender, "/mrsmp key <give|take|check> <player> <crate> [amount]", NamedTextColor.YELLOW);
                var target = Bukkit.getOfflinePlayer(args[2]);
                String crate = args[3].toLowerCase();
                if (!plugin.crates().all().containsKey(crate)) return say(sender, "Crates: " + String.join(", ", plugin.crates().all().keySet()), NamedTextColor.RED);
                int n = 1;
                if (args.length > 4) {
                    try {
                        n = Math.max(1, Integer.parseInt(args[4]));
                    } catch (NumberFormatException e) {
                        return say(sender, "Amount must be a number.", NamedTextColor.RED);
                    }
                }
                String what = args[1].toLowerCase();
                if (what.equals("give") && target.getPlayer() != null) {
                    plugin.rewards().give(target.getPlayer(), java.util.List.of("key:" + crate + ":" + n));
                    say(sender, "Gave " + n + " " + plugin.crates().keyName(crate) + "(s) to " + target.getName() + ".", NamedTextColor.GREEN);
                } else if (what.equals("give")) {
                    int now = plugin.quests().addKeys(target.getUniqueId(), crate, n);
                    say(sender, "Gave " + n + " to " + target.getName() + " (offline). Balance: " + now, NamedTextColor.GREEN);
                } else if (what.equals("take")) {
                    int now = plugin.quests().addKeys(target.getUniqueId(), crate, -n);
                    say(sender, target.getName() + " now has " + now + " " + plugin.crates().keyName(crate) + "(s).", NamedTextColor.GREEN);
                } else {
                    say(sender, target.getName() + " has " + plugin.quests().keys(target.getUniqueId(), crate) + " " + plugin.crates().keyName(crate) + "(s).", NamedTextColor.GREEN);
                }
            }
            default -> say(sender, "/mrsmp <reload|spawn build|spawn undo|key>   /mrnpc for NPCs   /quests for quests", NamedTextColor.YELLOW);
        }
        return true;
    }

    private static boolean nearNpc(Player p, NpcDef d) {
        var home = d.home();
        return home != null && home.getWorld() == p.getWorld() && home.distanceSquared(p.getLocation()) < 36;
    }

    private static boolean say(CommandSender s, String m, NamedTextColor c) {
        s.sendMessage(Component.text(m, c));
        return true;
    }

    private static boolean no(CommandSender s) {
        return say(s, "You need mrsmp.admin for that.", NamedTextColor.RED);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("mrsmp.admin")) return out;
        if (args.length == 1) {
            for (String s : List.of("reload", "spawn", "key")) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            out.addAll(List.of("build", "undo"));
        }
        return out;
    }
}
