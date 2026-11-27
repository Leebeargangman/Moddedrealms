package com.colonysmp.command;

import com.colonysmp.ColonySMP;
import com.colonysmp.api.ColonyApi;
import com.colonysmp.core.Goods;
import com.colonysmp.core.RaidPlan;
import com.colonysmp.model.Building;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Job;
import com.colonysmp.npc.Npc;
import com.colonysmp.util.Messages;
import com.colonysmp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * /colonyadmin: the server team's tools. Its own subcommands (cleanup, debug, reload, funnel, raid, event, give,
 * tier, season) each need colonysmp.admin.&lt;name&gt;; anything else is handed to the older /colony admin tools.
 */
public final class ColonyAdminCommand implements TabExecutor {

    private static final List<String> OWN = List.of("help", "cleanup", "debug", "reload", "funnel", "raid", "event", "give", "tier", "season");

    private final ColonySMP plugin;
    private final AdminCommand older;

    public ColonyAdminCommand(ColonySMP plugin, ColonyCommand colony) {
        this.plugin = plugin;
        this.older = colony.admin();
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        if (!OWN.contains(sub)) {
            older.run(s, a);
            return true;
        }
        String perm = "colonysmp.admin." + (sub.equals("help") ? "debug" : sub);
        if (!s.hasPermission(perm)) {
            Messages.send(s, "admin.no-permission", "permission", perm);
            return true;
        }
        String[] rest = Arrays.copyOfRange(a, Math.min(1, a.length), a.length);
        switch (sub) {
            case "cleanup" -> cleanup(s, rest.length > 0 && rest[0].equalsIgnoreCase("confirm"));
            case "debug" -> debug(s, rest);
            case "reload" -> {
                String err = plugin.reloadSafely();
                if (err == null) Messages.send(s, "admin.reload.ok");
                else Messages.send(s, "admin.reload.refused", "error", err);
            }
            case "funnel" -> {
                if (!plugin.settings().feature("funnel")) Messages.send(s, "admin.funnel.off");
                for (String l : plugin.funnel().report(rest.length > 0 ? rest[0] : "7d")) Text.raw(s, l);
                Messages.raw(s, "admin.funnel.sites", "status", plugin.sites().status(),
                        "camps", plugin.settings().feature("starter-camp") ? "on" : "off");
            }
            case "raid" -> raid(s, rest);
            case "event" -> event(s, rest);
            case "give" -> give(s, rest);
            case "tier" -> tier(s, rest);
            case "season" -> season(s, rest);
            default -> {
                for (String l : Messages.lines("admin.help")) Text.raw(s, l);
            }
        }
        return true;
    }

    // ───────────── cleanup ─────────────

    private void cleanup(CommandSender s, boolean confirm) {
        List<Entity> strays = plugin.npcs().strays();
        List<String> lines = new ArrayList<>();
        for (Entity e : strays) {
            String name = e.customName() == null ? e.getType().name().toLowerCase(Locale.ROOT)
                    : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.customName());
            lines.add("ColonySMP " + name + "|" + e.getWorld().getName() + " " + e.getLocation().getBlockX() + " " + e.getLocation().getBlockY() + " " + e.getLocation().getBlockZ());
        }
        ColonyApi.ServerHook h = ColonyApi.hook();
        List<String> other = h == null ? List.of() : h.strayNpcs(confirm);
        if (confirm) {
            int n = plugin.npcs().removeStrays(strays) + other.size();
            plugin.debug("admin", s.getName() + " removed " + n + " stray NPCs");
            Messages.send(s, "admin.cleanup.done", "count", n);
            return;
        }
        for (String o : other) lines.add(o);
        if (lines.isEmpty()) {
            Messages.send(s, "admin.cleanup.none");
            return;
        }
        Messages.send(s, "admin.cleanup.found", "count", lines.size());
        for (int i = 0; i < Math.min(15, lines.size()); i++) {
            String[] p = lines.get(i).split("\\|", 2);
            Messages.raw(s, "admin.cleanup.line", "what", p[0], "where", p.length > 1 ? p[1] : "?");
        }
        if (lines.size() > 15) Messages.raw(s, "admin.cleanup.more", "count", lines.size() - 15);
        Messages.raw(s, "admin.cleanup.confirm");
    }

    // ───────────── debug ─────────────

    private Colony colony(CommandSender s, String name) {
        Colony c = plugin.colonies().byName(name);
        if (c == null) c = plugin.colonies().get(name);
        if (c == null) Messages.send(s, "admin.unknown-colony", "name", name);
        return c;
    }

    private void debug(CommandSender s, String[] a) {
        if (a.length == 0) {
            Text.send(s, "Usage: <white>/colonyadmin debug <colony></white>. Colonies: " + String.join(", ", plugin.colonies().list().stream().map(c -> c.name).toList()));
            return;
        }
        Colony c = colony(s, String.join(" ", a));
        if (c == null) return;
        String owner = c.owner == null ? "nobody" : String.valueOf(Bukkit.getOfflinePlayer(c.owner).getName());
        Text.raw(s, "<aqua><bold>" + Text.esc(c.name) + "</bold></aqua> <gray>id " + c.id + " · owner " + Text.esc(owner) + " · level " + c.level()
                + " · " + c.citizens.size() + " citizens · treasury " + Text.value(c.treasury.balance()));
        World w = c.world();
        String chunk;
        if (w == null || c.core == null) chunk = "<red>no Town Hall placed";
        else {
            int cx = c.core.x() >> 4, cz = c.core.z() >> 4;
            if (!w.isChunkLoaded(cx, cz)) chunk = "Town Hall chunk not loaded (background simulation)";
            else {
                Chunk.LoadLevel lvl = w.getChunkAt(cx, cz).getLoadLevel();
                chunk = "Town Hall chunk " + lvl.name().toLowerCase(Locale.ROOT) + " at " + c.core.x() + " " + c.core.y() + " " + c.core.z();
            }
        }
        Text.raw(s, " <gray>Chunk: <white>" + chunk + "</white> · " + (c.active ? "<green>active</green> for " + Text.duration(System.currentTimeMillis() - c.activeSince) : "inactive")
                + " · land " + c.territory.size() + " chunks");
        Text.raw(s, " <gray>NPCs: <white>" + plugin.npcs().count(c) + "</white> bodies of <white>" + plugin.npcs().wantedBodies(c) + "</white> wanted (cap "
                + plugin.settings().maxBodiesPerColony + ", server " + plugin.npcs().all().size() + "/" + plugin.settings().maxBodiesTotal + ")");
        Map<Job, Integer> jobs = new EnumMap<>(Job.class);
        int downed = 0, unhoused = 0, idle = 0;
        for (Citizen ct : c.citizens.values()) {
            jobs.merge(ct.job, 1, Integer::sum);
            if (ct.downed()) downed++;
            if (ct.home == 0) unhoused++;
            if (ct.waiting != null) idle++;
        }
        List<String> js = new ArrayList<>();
        jobs.forEach((j, n) -> js.add(Text.nice(j.name()).toLowerCase(Locale.ROOT) + " " + n));
        Text.raw(s, " <gray>Jobs: <white>" + (js.isEmpty() ? "none" : String.join(", ", js)) + "</white> · downed " + downed + " · no home " + unhoused + " · waiting " + idle);
        Text.raw(s, " <gray>Stores: <white>" + c.storage.usedSlots() + "/" + c.storage.capacity() + "</white> slots · granary " + c.storage.usedGranarySlots()
                + " · armoury " + c.equipment.size() + "/" + c.equipment.capacity());
        Map<String, Long> top = new TreeMap<>();
        c.storage.contents().entrySet().stream().sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).limit(10).forEach(e -> top.put(e.getKey(), e.getValue()));
        if (!top.isEmpty()) Text.raw(s, " <gray>Most held: <white>" + Text.items(top));
        int invalid = 0;
        for (Building b : c.buildings.values()) if (!b.valid) invalid++;
        Text.raw(s, " <gray>Buildings: <white>" + c.buildings.size() + "</white> (" + invalid + " failing inspection) · build orders " + c.buildQueue.size()
                + " · squads " + c.squads.size());
        List<String> tasks = new ArrayList<>();
        tasks.add("quest " + c.questStage);
        if (plugin.raids().raid(c) != null) tasks.add("<red>raid under way</red>");
        if (plugin.wars().fighting(c)) tasks.add("<red>at war</red>");
        else if (plugin.wars().warmup(c)) tasks.add("<yellow>war warm-up</yellow>");
        if (c.inGrace()) tasks.add("founding grace " + Math.round(c.graceHours) + " h");
        if (c.shieldUntil > System.currentTimeMillis()) tasks.add("shield " + Text.duration(c.shieldUntil - System.currentTimeMillis()));
        Text.raw(s, " <gray>Tasks: <white>" + String.join(" · ", tasks));
        Text.raw(s, " <gray>Simulation: last step <white>" + Text.num(c.lastStepMs) + " ms</white> · last tick " + c.lastSim + " · day " + c.lastDay
                + " · overall " + plugin.sim().perf());
        int shown = 0;
        for (Citizen ct : c.citizens.values()) {
            if (shown++ >= 12) {
                Text.raw(s, " <dark_gray>... " + (c.citizens.size() - 12) + " more: /colony admin inspect " + Text.esc(c.name));
                break;
            }
            Npc n = plugin.npcs().npc(ct);
            Text.raw(s, "  <white>" + Text.esc(ct.name) + "</white> <gray>" + ct.title() + (n == null ? " (no body)" : " @" + n.loc().getBlockX() + "," + n.loc().getBlockY() + "," + n.loc().getBlockZ())
                    + ": " + Text.esc(ct.activity) + (ct.waiting != null ? " <yellow>[" + Text.esc(ct.waiting) + "]" : ""));
        }
    }

    // ───────────── raid ─────────────

    private void raid(CommandSender s, String[] a) {
        if (a.length == 0) {
            Messages.send(s, "admin.raid.usage");
            return;
        }
        if (!plugin.settings().raidsEnabled) {
            Messages.send(s, "admin.raid.off");
            return;
        }
        int strength = 0;
        if (a.length > 1) {
            try {
                strength = Math.max(1, Math.min(60, Integer.parseInt(a[a.length - 1])));
            } catch (NumberFormatException ignored) {
                // no strength given: the usual size for the colony
            }
        }
        List<Colony> targets = new ArrayList<>();
        if (a[0].equalsIgnoreCase("all")) {
            for (Colony c : plugin.colonies().list()) if (c.core != null) targets.add(c);
        } else {
            String name = strength > 0 ? String.join(" ", Arrays.copyOf(a, a.length - 1)) : String.join(" ", a);
            Colony c = colony(s, name);
            if (c == null) return;
            targets.add(c);
        }
        for (Colony c : targets) {
            int size = strength > 0 ? strength : RaidPlan.size(c.population(), c.level(), c.raidsRepelled, c.bonus(com.colonysmp.core.Research.Effect.RAID_DEFENCE),
                    plugin.settings().raidMinSize, plugin.settings().raidMaxSize);
            String err = plugin.raids().start(c, com.colonysmp.raid.RaidManager.Kind.BARBARIANS, size);
            if (err == null) Messages.send(s, "admin.raid.started", "size", size, "colony", c.name);
            else Messages.send(s, "admin.raid.failed", "colony", c.name, "error", err);
        }
    }

    // ───────────── season (ModdedrealmsSMP) ─────────────

    private void event(CommandSender s, String[] a) {
        ColonyApi.ServerHook h = ColonyApi.hook();
        if (h == null) {
            Messages.send(s, "admin.season.missing");
            return;
        }
        if (a.length < 2 || !a[1].equalsIgnoreCase("start") && !a[1].equalsIgnoreCase("stop")) {
            Messages.send(s, "admin.event.usage", "events", String.join(", ", h.seasonEvents()));
            return;
        }
        String err = h.seasonEvent(a[0].toLowerCase(Locale.ROOT), a[1].equalsIgnoreCase("start"));
        Text.send(s, err == null ? "<green>" + Text.esc(a[0]) + " " + a[1].toLowerCase(Locale.ROOT) + "ed." : "<red>" + Text.esc(err));
    }

    private void season(CommandSender s, String[] a) {
        if (a.length == 0 || !a[0].equalsIgnoreCase("end")) {
            Messages.send(s, "admin.season.usage");
            return;
        }
        ColonyApi.ServerHook h = ColonyApi.hook();
        if (h == null) {
            Messages.send(s, "admin.season.missing");
            return;
        }
        for (String l : h.seasonEnd(a.length > 1 && a[1].equalsIgnoreCase("confirm"))) Text.raw(s, l);
    }

    // ───────────── give / tier ─────────────

    private void give(CommandSender s, String[] a) {
        if (a.length < 2) {
            Messages.send(s, "admin.give.usage");
            return;
        }
        Player to = Bukkit.getPlayerExact(a[0]);
        if (to == null) {
            Messages.send(s, "admin.unknown-player", "name", a[0]);
            return;
        }
        String res = a[1].toLowerCase(Locale.ROOT);
        long amount = 1;
        if (a.length > 2) {
            try {
                amount = Math.max(1, Math.min(1_000_000, Long.parseLong(a[2])));
            } catch (NumberFormatException e) {
                Messages.send(s, "admin.give.usage");
                return;
            }
        }
        ItemStack item = plugin.items().byId(res);
        if (item != null) {
            ColonyCommand.give(to, item);
            Messages.send(s, "admin.give.item", "player", to.getName(), "resource", res);
            return;
        }
        Colony c = plugin.colonies().of(to);
        if (c == null) {
            Messages.send(s, "admin.give.no-colony", "player", to.getName());
            return;
        }
        String good = Goods.known(res) ? res : Goods.key(res.toUpperCase(Locale.ROOT));
        if (!Goods.known(good)) {
            Messages.send(s, "admin.give.unknown", "resource", res);
            return;
        }
        long added = c.storage.add(good, amount);
        plugin.requestSave();
        plugin.getLogger().info(s.getName() + " gave " + added + " " + good + " to " + c.name + "'s storage (/colonyadmin give).");
        Messages.send(s, added >= amount ? "admin.give.done" : "admin.give.partly", "amount", added, "resource", good, "colony", c.name);
    }

    private void tier(CommandSender s, String[] a) {
        int max = com.colonysmp.model.BuildingType.TOWN_HALL.maxLevel;
        if (a.length < 2) {
            Messages.send(s, "admin.tier.usage", "max", max);
            return;
        }
        int tier;
        try {
            tier = Integer.parseInt(a[a.length - 1]);
        } catch (NumberFormatException e) {
            Messages.send(s, "admin.tier.usage", "max", max);
            return;
        }
        Colony c = colony(s, String.join(" ", Arrays.copyOf(a, a.length - 1)));
        if (c == null) return;
        Building th = c.townHall();
        if (th == null) {
            Messages.send(s, "admin.tier.no-hall", "colony", c.name);
            return;
        }
        th.level = Math.max(1, Math.min(max, tier));
        plugin.buildings().changed(c);
        plugin.requestSave();
        plugin.getLogger().info(s.getName() + " set " + c.name + " to tier " + th.level + " (/colonyadmin tier).");
        Messages.send(s, "admin.tier.done", "colony", c.name, "tier", th.level);
    }

    // ───────────── tab completion ─────────────

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("colonysmp.admin")) return List.of();
        List<String> out = new ArrayList<>();
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        if (a.length <= 1) {
            for (String o : OWN) if (o.startsWith(last)) out.add(o);
            for (String o : older.complete(s, a)) if (!out.contains(o)) out.add(o);
            return out;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "debug", "tier" -> {
                if (a.length == 2) for (Colony c : plugin.colonies().list()) if (c.name.toLowerCase(Locale.ROOT).startsWith(last)) out.add(c.name);
            }
            case "raid" -> {
                if (a.length == 2) {
                    if ("all".startsWith(last)) out.add("all");
                    for (Colony c : plugin.colonies().list()) if (c.name.toLowerCase(Locale.ROOT).startsWith(last)) out.add(c.name);
                }
            }
            case "cleanup" -> {
                if (a.length == 2 && "confirm".startsWith(last)) out.add("confirm");
            }
            case "funnel" -> {
                if (a.length == 2) for (String o : List.of("24h", "7d", "all")) if (o.startsWith(last)) out.add(o);
            }
            case "season" -> {
                if (a.length == 2 && "end".startsWith(last)) out.add("end");
                if (a.length == 3 && "confirm".startsWith(last)) out.add("confirm");
            }
            case "event" -> {
                ColonyApi.ServerHook h = ColonyApi.hook();
                if (a.length == 2 && h != null) for (String o : h.seasonEvents()) if (o.startsWith(last)) out.add(o);
                if (a.length == 3) for (String o : List.of("start", "stop")) if (o.startsWith(last)) out.add(o);
            }
            case "give" -> {
                if (a.length == 2) for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(last)) out.add(p.getName());
            }
            default -> {
                return older.complete(s, a);
            }
        }
        return out;
    }
}
