package net.moddedrealms.guard.replay;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.moddedrealms.guard.GuardPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

public final class ReplayCommand implements TabExecutor {
    private final GuardPlugin plugin;
    private final ReplayManager replays;

    public ReplayCommand(GuardPlugin plugin, ReplayManager replays) {
        this.plugin = plugin;
        this.replays = replays;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase();
        switch (sub) {
            case "list" -> list(sender, args.length > 1 ? args[1] : null);
            case "play", "watch" -> {
                if (!(sender instanceof Player p)) return msg(sender, "Only players can watch replays.");
                if (args.length < 2) return msg(sender, "/replay play <id>");
                String id = args[1].toLowerCase();
                if (replays.isPending(id)) return msg(sender, "Replay " + id + " is still recording: ready in " + replays.secondsUntilReady(id) + "s.");
                replays.play(p, id);
            }
            case "save" -> {
                if (args.length < 2) return msg(sender, "/replay save <player> [seconds-before]");
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) return msg(sender, args[1] + " is not online.");
                int before = plugin.settings().secondsBefore;
                if (args.length > 2) {
                    try {
                        before = Math.max(5, Math.min(plugin.settings().bufferSeconds, Integer.parseInt(args[2])));
                    } catch (NumberFormatException ignored) {
                    }
                }
                String id = replays.saveClip(t.getUniqueId(), t.getName(), "Saved by " + sender.getName(), before, plugin.settings().secondsAfter);
                sender.sendMessage(Component.text("Saving the last " + before + "s of " + t.getName() + " as replay ", NamedTextColor.GREEN)
                        .append(Component.text(id, NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/replay play " + id)))
                        .append(Component.text(" (ready in " + plugin.settings().secondsAfter + "s).", NamedTextColor.GREEN)));
            }
            case "stop" -> {
                if (sender instanceof Player p) replays.stop(p);
            }
            case "pause" -> withSession(sender, s -> s.togglePause());
            case "speed" -> withSession(sender, s -> {
                try {
                    s.setSpeed(Double.parseDouble(args.length > 1 ? args[1].replace("x", "") : "1"));
                } catch (NumberFormatException ignored) {
                    s.setSpeed(1);
                }
            });
            case "skip" -> withSession(sender, s -> {
                try {
                    s.skip(Integer.parseInt(args.length > 1 ? args[1] : "10"));
                } catch (NumberFormatException ignored) {
                }
            });
            case "pov" -> withSession(sender, s -> sender.sendMessage(Component.text(s.pov()
                    ? "Watching through the subject's eyes. Run /replay pov again (or press sneak) to leave." : "Free camera.", NamedTextColor.GRAY)));
            case "controls" -> withSession(sender, ReplaySession::controls);
            default -> msg(sender, "/replay <list [player]|play <id>|save <player> [seconds]|pause|speed <x>|skip <s>|pov|stop>");
        }
        return true;
    }

    private void withSession(CommandSender sender, java.util.function.Consumer<ReplaySession> f) {
        if (!(sender instanceof Player p)) return;
        ReplaySession s = replays.session(p);
        if (s == null) {
            msg(sender, "You are not watching a replay.");
            return;
        }
        f.accept(s);
    }

    private void list(CommandSender sender, String who) {
        List<ReplayManager.Entry> all = replays.list(who);
        if (all.isEmpty()) {
            msg(sender, who == null ? "No saved replays yet." : "No replays of " + who + ".");
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("dd MMM HH:mm");
        sender.sendMessage(Component.text("Replays" + (who == null ? "" : " of " + who) + " (newest first, click to watch):", NamedTextColor.GOLD));
        for (ReplayManager.Entry e : all.subList(0, Math.min(12, all.size()))) {
            sender.sendMessage(Component.text()
                    .append(Component.text(" ▶ " + e.id() + " ", NamedTextColor.AQUA))
                    .append(Component.text(fmt.format(new Date(e.created())) + " ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(e.subject(), NamedTextColor.WHITE))
                    .append(Component.text(" — " + e.reason() + " (" + e.seconds() + "s)", NamedTextColor.GRAY))
                    .hoverEvent(HoverEvent.showText(Component.text("Watch " + e.id())))
                    .clickEvent(ClickEvent.runCommand("/replay play " + e.id())).build());
        }
    }

    private static boolean msg(CommandSender s, String m) {
        s.sendMessage(Component.text(m, NamedTextColor.YELLOW));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("list", "play", "save", "stop", "pause", "speed", "skip", "pov", "controls")) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("save") || args[0].equalsIgnoreCase("list"))) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("play")) {
            for (ReplayManager.Entry e : replays.list(null)) if (e.id().startsWith(args[1].toLowerCase())) out.add(e.id());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("speed")) {
            out.addAll(List.of("0.25", "0.5", "1", "2", "4"));
        }
        return out;
    }
}
