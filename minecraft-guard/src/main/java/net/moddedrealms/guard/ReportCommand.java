package net.moddedrealms.guard;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /report: any player can flag someone; the last minute is saved as a replay for staff. */
final class ReportCommand implements CommandExecutor {
    private final GuardPlugin plugin;
    private final Map<UUID, Long> last = new HashMap<>();

    ReportCommand(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        if (args.length < 2) {
            p.sendMessage(Component.text("/report <player> <reason>", NamedTextColor.YELLOW));
            return true;
        }
        Player t = Bukkit.getPlayerExact(args[0]);
        if (t == null || t == p) {
            p.sendMessage(Component.text("That player is not online.", NamedTextColor.RED));
            return true;
        }
        long now = System.currentTimeMillis();
        long wait = plugin.settings().reportCooldownMillis - (now - last.getOrDefault(p.getUniqueId(), 0L));
        if (wait > 0) {
            p.sendMessage(Component.text("You can report again in " + (wait / 1000 + 1) + "s.", NamedTextColor.RED));
            return true;
        }
        last.put(p.getUniqueId(), now);
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        if (reason.length() > 80) reason = reason.substring(0, 80);
        String id = plugin.replays().saveClip(t.getUniqueId(), t.getName(), "Report by " + p.getName() + ": " + reason,
                plugin.settings().secondsBefore, plugin.settings().secondsAfter);
        p.sendMessage(Component.text("Thanks! Staff will review a replay of " + t.getName() + ".", NamedTextColor.GREEN));
        plugin.violations().log("REPORT " + p.getName() + " -> " + t.getName() + ": " + reason + " replay=" + id);
        plugin.violations().staff(Component.text()
                .append(Component.text("[Report] ", NamedTextColor.GOLD))
                .append(Component.text(p.getName() + " reported " + t.getName() + ": ", NamedTextColor.WHITE))
                .append(Component.text(reason, NamedTextColor.GRAY))
                .append(Component.text(" [Replay]", NamedTextColor.AQUA, TextDecoration.BOLD).clickEvent(ClickEvent.runCommand("/replay play " + id)))
                .append(Component.text(" [TP]", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/tp " + t.getName())))
                .build());
        return true;
    }
}
