package net.moddedrealms.smp.quest;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /quests [story] ; admin: /quests reset|complete <player> [quest] */
public final class QuestCommand implements CommandExecutor {
    private final SmpPlugin plugin;

    public QuestCommand(SmpPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 2 && sender.hasPermission("mrsmp.admin")) {
            Player t = Bukkit.getPlayerExact(args[1]);
            if (t == null) {
                sender.sendMessage(Component.text(args[1] + " is not online.", NamedTextColor.RED));
                return true;
            }
            if (args[0].equalsIgnoreCase("reset")) {
                plugin.quests().reset(t.getUniqueId());
                sender.sendMessage(Component.text("Reset all quest progress of " + t.getName() + ".", NamedTextColor.GREEN));
                return true;
            }
            if (args[0].equalsIgnoreCase("complete") && args.length >= 3) {
                boolean ok = plugin.quests().forceComplete(t, args[2]);
                sender.sendMessage(Component.text(ok ? "Completed " + args[2] + " for " + t.getName() + "." : t.getName() + " has no open quest " + args[2] + ".",
                        ok ? NamedTextColor.GREEN : NamedTextColor.RED));
                return true;
            }
        }
        if (!(sender instanceof Player p)) return true;
        if (args.length >= 1 && args[0].equalsIgnoreCase("story")) plugin.quests().showStory(p);
        else plugin.quests().openGui(p);
        return true;
    }
}
