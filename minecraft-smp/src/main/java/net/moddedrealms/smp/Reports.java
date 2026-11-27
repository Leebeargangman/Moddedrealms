package net.moddedrealms.smp;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * /report: a player tells the staff about a bug, a griefer or a cheater. Each report keeps what's needed to check it
 * rather than take it on trust: where the reporter was, who was near, and the last lines of chat. Staff read them
 * with /mrsmp reports and close them with /mrsmp report <id> <note>; the reporter hears back when it's closed.
 */
public final class Reports implements CommandExecutor, Listener {
    private final SmpPlugin plugin;
    private final File file;
    private final YamlConfiguration y;
    private final Deque<String> chat = new ArrayDeque<>();
    private final Map<UUID, Long> last = new HashMap<>();

    Reports(SmpPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "reports.yml");
        this.y = YamlConfiguration.loadConfiguration(file);
    }

    private void save() {
        try {
            net.moddedrealms.smp.SafeYaml.save(y, file);
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save reports.yml: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        String line = e.getPlayer().getName() + ": " + PlainTextComponentSerializer.plainText().serialize(e.message());
        synchronized (chat) {
            chat.addLast(DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC).format(Instant.now()) + " " + (line.length() > 200 ? line.substring(0, 200) : line));
            while (chat.size() > 30) chat.removeFirst();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only. Staff: /mrsmp reports");
            return true;
        }
        String text = String.join(" ", a).trim();
        if (text.length() < 6) {
            p.sendMessage(SmpPlugin.mm("<gray>Tell the staff what's wrong: <white>/report the bakery job won't pay me</white> or <white>/report Steve is flying</white>. "
                    + "Say what happened, where and when."));
            return true;
        }
        long now = System.currentTimeMillis();
        Long prev = last.get(p.getUniqueId());
        if (prev != null && now - prev < 60_000L) {
            p.sendMessage(SmpPlugin.mm("<gray>Got your last report. Give it a minute before the next one."));
            return true;
        }
        last.put(p.getUniqueId(), now);
        if (text.length() > 300) text = text.substring(0, 300);
        int id = y.getInt("next-id", 1);
        y.set("next-id", id + 1);
        String k = "reports." + id + ".";
        Location l = p.getLocation();
        y.set(k + "time", now);
        y.set(k + "reporter", p.getName());
        y.set(k + "uuid", p.getUniqueId().toString());
        y.set(k + "text", text);
        y.set(k + "where", l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
        List<String> near = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o == p || o.getWorld() != p.getWorld()) continue;
            double d = o.getLocation().distance(l);
            if (d < 64) near.add(o.getName() + " (" + Math.round(d) + "m)");
        }
        y.set(k + "near", near);
        y.set(k + "online", Bukkit.getOnlinePlayers().size());
        synchronized (chat) {
            y.set(k + "chat", new ArrayList<>(chat));
        }
        y.set(k + "status", "open");
        save();
        p.sendMessage(SmpPlugin.mm("<green>Thanks, report #" + id + " is with the staff.</green> <gray>We check every report before acting, and you'll hear back here."));
        for (Player s : Bukkit.getOnlinePlayers()) {
            if (s != p && s.hasPermission("mrsmp.admin")) s.sendMessage(SmpPlugin.mm("<red>[Report #" + id + "] <gray>" + p.getName() + ": <white>").append(net.kyori.adventure.text.Component.text(text)));
        }
        plugin.getLogger().info("Report #" + id + " from " + p.getName() + " at " + y.getString(k + "where") + ": " + text);
        return true;
    }

    /** /mrsmp reports [all] */
    List<String> list(boolean all) {
        List<String> out = new ArrayList<>();
        ConfigurationSection s = y.getConfigurationSection("reports");
        if (s == null) {
            out.add("<gray>No reports.");
            return out;
        }
        DateTimeFormatter f = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneOffset.UTC);
        List<String> ids = new ArrayList<>(s.getKeys(false));
        for (int i = ids.size() - 1; i >= 0 && out.size() < 15; i--) {
            ConfigurationSection r = s.getConfigurationSection(ids.get(i));
            if (r == null || !all && !"open".equals(r.getString("status"))) continue;
            out.add("<gold>#" + ids.get(i) + " <dark_gray>" + f.format(Instant.ofEpochMilli(r.getLong("time"))) + " <gray>" + r.getString("reporter") + " @ " + r.getString("where")
                    + ": <white>" + SmpPlugin.MM.escapeTags(r.getString("text", "")) + (all ? " <dark_gray>[" + r.getString("status") + "]" : ""));
        }
        if (out.isEmpty()) out.add("<gray>No open reports.");
        return out;
    }

    /** /mrsmp report <id> <note>: closes it and tells the reporter (now if online, else on their next join). */
    String close(String id, String note) {
        String k = "reports." + id;
        if (!y.contains(k)) return "No report #" + id + ".";
        y.set(k + ".status", "closed");
        y.set(k + ".note", note);
        save();
        Player p = Bukkit.getPlayerExact(y.getString(k + ".reporter", ""));
        String msg = "<gold>[Staff] <gray>About your report #" + id + ": <white>" + SmpPlugin.MM.escapeTags(note);
        if (p != null) p.sendMessage(SmpPlugin.mm(msg));
        else y.set("pending." + y.getString(k + ".uuid", "none"), msg);
        save();
        return "Closed #" + id + ".";
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        String k = "pending." + e.getPlayer().getUniqueId();
        String msg = y.getString(k);
        if (msg == null) return;
        y.set(k, null);
        save();
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) p.sendMessage(SmpPlugin.mm(msg));
        }, 100L);
    }
}
