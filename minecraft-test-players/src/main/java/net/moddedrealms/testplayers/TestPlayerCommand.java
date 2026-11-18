package net.moddedrealms.testplayers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;

/** /testplayer: spawn, move and manage test players. Targets are a name, a prefix ending in * (Load*), or all. */
final class TestPlayerCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("spawn", "despawn", "start", "remove", "move", "follow", "wander", "walk", "stop",
            "sprint", "bring", "tp", "say", "ai", "goal", "personality", "build", "undo", "zone", "town", "job", "free", "team", "populate", "api", "list", "info", "stats", "reload", "help");
    private static final List<String> TARGETED = List.of("despawn", "start", "remove", "move", "follow", "wander", "walk", "stop", "sprint", "bring", "ai", "goal",
            "personality", "build", "undo", "job", "free");

    private final TestPlayersPlugin plugin;

    TestPlayerCommand(TestPlayersPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) {
            help(sender, label);
            return true;
        }
        try {
            run(sender, a[0].toLowerCase(Locale.ROOT), a);
        } catch (IllegalArgumentException | IllegalStateException e) {
            sender.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    private void run(CommandSender sender, String sub, String[] a) {
        TestPlayers players = plugin.players();
        switch (sub) {
            case "spawn" -> {
                need(a, 2, "spawn <name> [count] [idle|wander|walk|free|team]");
                int count = a.length > 2 ? integer(a[2], 1, plugin.settings().maxPlayers()) : 1;
                String mode = a.length > 3 ? a[3].toLowerCase(Locale.ROOT) : "idle";
                if (!List.of("idle", "wander", "walk", "free", "team").contains(mode)) throw new IllegalArgumentException("Mode is idle, wander, walk, free (each plays on its own) or team (they play together).");
                List<String> names = players.batchNames(a[1], count);
                int room = plugin.settings().maxPlayers() - players.all().size();
                if (names.size() > room) throw new IllegalArgumentException("Only room for " + room + " more (max-players is " + plugin.settings().maxPlayers() + ").");
                Location center = origin(sender);
                Town team = mode.equals("team") ? plugin.towns().foundCamp(uniqueTeam(a[1]), center) : null;
                for (int i = 0; i < names.size(); i++) {
                    TestPlayer tp = players.create(names.get(i));
                    // Spread a batch out on a sunflower spiral, about 3 blocks apart.
                    double r = names.size() == 1 ? 0 : 3 * Math.sqrt(i + 1);
                    double ang = i * 2.39996;
                    Location at = center.clone().add(Math.cos(ang) * r, 0, Math.sin(ang) * r);
                    at.setYaw((float) (Math.random() * 360));
                    tp.home = at;
                    if (mode.equals("wander")) {
                        tp.mode = TestPlayer.Mode.WANDER;
                        tp.wanderRadius = 24;
                    } else if (mode.equals("walk")) {
                        tp.mode = TestPlayer.Mode.WALK;
                        tp.walkYaw = at.getYaw();
                    } else if (mode.equals("free") || mode.equals("team")) {
                        if (tp.personality == null) tp.personality = Personalities.resolve("random");
                        plugin.life().aiByDefault(tp);
                        plugin.life().makeFree(tp, team);
                    }
                    players.spawn(tp, at);
                }
                ok(sender, "Spawning " + (names.size() == 1 ? names.getFirst() : names.size() + " test players (" + names.getFirst() + " to " + names.getLast() + ")") + ", "
                        + (mode.equals("free") ? "each playing survival on their own" : mode.equals("team") ? "playing survival as team " + team.name : mode) + ".");
                if ((mode.equals("free") || mode.equals("team")) && !plugin.llm().ready())
                    sender.sendMessage(Component.text("No AI linked, so they play on their own routines. /testplayer ai link to give them a brain.", NamedTextColor.GRAY));
            }
            case "despawn" -> {
                need(a, 2, "despawn <target>");
                List<TestPlayer> list = players.select(a[1]);
                list.forEach(tp -> players.despawn(tp, false));
                ok(sender, "Took " + count(list) + " out of the world. /testplayer start brings them back, /testplayer remove forgets them.");
            }
            case "start" -> {
                need(a, 2, "start <target>");
                List<TestPlayer> list = players.select(a[1]);
                for (TestPlayer tp : list) {
                    Location at = tp.lastKnown != null && tp.lastKnown.isWorldLoaded() ? tp.lastKnown : origin(sender);
                    players.spawn(tp, at);
                }
                ok(sender, "Starting " + count(list) + ".");
            }
            case "remove" -> {
                need(a, 2, "remove <target>");
                List<TestPlayer> list = players.select(a[1]);
                list.forEach(tp -> players.despawn(tp, true));
                ok(sender, "Removed " + count(list) + ".");
            }
            case "move" -> {
                need(a, 3, "move <target> <x> <y> <z> | here | <player>");
                List<TestPlayer> list = players.select(a[1]);
                Location to;
                if (a[2].equalsIgnoreCase("here")) to = player(sender).getLocation();
                else if (a.length >= 5) to = new Location(origin(sender).getWorld(), number(a[2]), number(a[3]), number(a[4]));
                else {
                    Player p = Bukkit.getPlayerExact(a[2]);
                    if (p == null) throw new IllegalArgumentException("Give x y z, \"here\", or an online player's name.");
                    to = p.getLocation();
                }
                list.forEach(tp -> players.goTo(tp, to));
                ok(sender, "Sending " + count(list) + " to " + to.getBlockX() + " " + to.getBlockY() + " " + to.getBlockZ() + ".");
            }
            case "follow" -> {
                need(a, 3, "follow <target> <player>");
                List<TestPlayer> list = players.select(a[1]);
                for (TestPlayer tp : list) players.follow(tp, a[2]);
                ok(sender, count(list) + " following " + a[2] + ".");
            }
            case "wander" -> {
                need(a, 2, "wander <target> [radius]");
                List<TestPlayer> list = players.select(a[1]);
                double radius = a.length > 2 ? number(a[2]) : 24;
                list.forEach(tp -> players.wander(tp, radius));
                ok(sender, count(list) + " wandering within " + Math.round(Math.max(4, radius)) + " blocks.");
            }
            case "walk" -> {
                need(a, 2, "walk <target> [north|south|east|west|ne|...|yaw]");
                List<TestPlayer> list = players.select(a[1]);
                Float yaw = a.length > 2 ? TestPlayers.parseDirection(a[2]) : null;
                for (TestPlayer tp : list) players.walk(tp, yaw != null ? yaw : (float) (Math.random() * 360));
                ok(sender, count(list) + " walking " + (yaw != null ? TestPlayer.compass(yaw) : "in random directions") + " (loads new chunks as they go).");
            }
            case "stop" -> {
                need(a, 2, "stop <target>");
                List<TestPlayer> list = players.select(a[1]);
                for (TestPlayer tp : list) {
                    tp.ai = false;
                    players.idle(tp);
                }
                ok(sender, count(list) + " standing still (AI paused; /testplayer ai <target> on resumes it).");
            }
            case "sprint" -> {
                need(a, 3, "sprint <target> <on|off>");
                List<TestPlayer> list = players.select(a[1]);
                boolean on = onOff(a[2]);
                list.forEach(tp -> tp.sprint = on);
                players.markDirty();
                ok(sender, count(list) + (on ? " sprinting." : " walking."));
            }
            case "bring" -> {
                need(a, 2, "bring <target>");
                Location here = player(sender).getLocation();
                List<TestPlayer> list = players.select(a[1]);
                int n = 0;
                for (TestPlayer tp : list) {
                    Mannequin m = tp.entity();
                    if (m == null) continue;
                    m.teleport(here);
                    n++;
                }
                ok(sender, "Brought " + n + " test player" + (n == 1 ? "" : "s") + " to you.");
            }
            case "tp" -> {
                need(a, 2, "tp <name>");
                Mannequin m = one(a[1]).entity();
                if (m == null) throw new IllegalArgumentException(a[1] + " isn't in the world.");
                player(sender).teleport(m);
                ok(sender, "Teleported to " + a[1] + ".");
            }
            case "say" -> {
                need(a, 3, "say <name> <text>");
                TestPlayer tp = one(a[1]);
                if (tp.entity() == null) throw new IllegalArgumentException(tp.name + " isn't in the world.");
                players.say(tp, String.join(" ", Arrays.copyOfRange(a, 2, a.length)));
            }
            case "ai" -> {
                if (a.length >= 2 && a[1].equalsIgnoreCase("link")) {
                    plugin.aiLink().link(sender, a);
                    return;
                }
                if (a.length >= 2 && a[1].equalsIgnoreCase("test")) {
                    sender.sendMessage(Component.text("Asking " + plugin.llm().placeFor(new TestPlayer("x")) + "...", NamedTextColor.GRAY));
                    plugin.aiLink().test(sender);
                    return;
                }
                need(a, 3, "ai <target> <on|off|model> [staff member, to use their computer]   or   ai link ...   or   ai test");
                List<TestPlayer> list = players.select(a[1]);
                String v = a[2];
                for (TestPlayer tp : list) {
                    if (v.equalsIgnoreCase("off")) {
                        tp.ai = false;
                        plugin.llm().release(tp);
                    } else if (v.equalsIgnoreCase("on")) {
                        if (tp.model == null) tp.model = "default"; // config.yml ai.model, or the provider's default
                        if (a.length > 3) tp.staff = a[3];
                        tp.ai = true;
                    } else {
                        if (!v.matches("^[\\w.:/-]{1,64}$")) throw new IllegalArgumentException("That isn't a model name (e.g. qwen2.5:3b).");
                        tp.model = v;
                        tp.staff = a.length > 3 ? a[3] : null;
                        tp.ai = true;
                    }
                    tp.nextThinkAt = 0;
                    players.relabel(tp);
                }
                TestPlayer first = list.getFirst();
                ok(sender, v.equalsIgnoreCase("off") ? "AI off for " + count(list) + "."
                        : count(list) + " now thinking with " + plugin.llm().labelFor(first) + " on " + plugin.llm().placeFor(first) + ".");
                if (!v.equalsIgnoreCase("off") && first.staff == null && plugin.llm().paid())
                    sender.sendMessage(Component.text("API budget: " + plugin.llm().usage(), NamedTextColor.GRAY));
            }
            case "goal" -> {
                need(a, 3, "goal <target> <text...|clear>");
                List<TestPlayer> list = players.select(a[1]);
                String text = String.join(" ", Arrays.copyOfRange(a, 2, a.length)).trim();
                for (TestPlayer tp : list) {
                    tp.goal = text.equalsIgnoreCase("clear") ? null : text.length() > 500 ? text.substring(0, 500) : text;
                    tp.nextThinkAt = 0;
                }
                players.markDirty();
                ok(sender, text.equalsIgnoreCase("clear") ? "Goal cleared." : "Goal set for " + count(list) + " (their AI model works toward it).");
            }
            case "personality" -> {
                need(a, 3, "personality <target> <" + String.join("|", Personalities.PRESETS.keySet()) + "|random|your own words...|clear>");
                List<TestPlayer> list = players.select(a[1]);
                String text = String.join(" ", Arrays.copyOfRange(a, 2, a.length)).trim();
                for (TestPlayer tp : list) {
                    tp.personality = text.equalsIgnoreCase("clear") ? null : Personalities.resolve(text); // "random" picks again for each
                    tp.nextThinkAt = 0;
                }
                players.markDirty();
                if (text.equalsIgnoreCase("clear")) ok(sender, "Personality cleared for " + count(list) + ".");
                else if (list.size() == 1) ok(sender, list.getFirst().name + ": " + list.getFirst().personality);
                else ok(sender, "Personalities set for " + count(list) + (text.equalsIgnoreCase("random") ? " (a random preset each)." : "."));
                if (list.stream().anyMatch(tp -> tp.model == null)) sender.sendMessage(Component.text("Personalities only show with an AI model: /testplayer ai <target> <model>", NamedTextColor.GRAY));
            }
            case "build" -> {
                need(a, 3, "build <target> <" + String.join("|", Builder.STRUCTURES) + "> [" + String.join("|", Builder.MATERIALS) + "]");
                List<TestPlayer> list = players.select(a[1]);
                String material = a.length > 3 ? a[3] : "wood";
                List<String> errors = new ArrayList<>();
                int started = 0;
                for (TestPlayer tp : list) {
                    try {
                        String r = plugin.builder().startOrApproach(tp, a[2], material);
                        if (list.size() == 1) ok(sender, r);
                        started++;
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        errors.add(e.getMessage());
                    }
                }
                if (list.size() > 1) ok(sender, started + " of " + list.size() + " started building.");
                errors.stream().distinct().limit(3).forEach(e -> sender.sendMessage(Component.text(e, NamedTextColor.RED)));
            }
            case "undo" -> {
                need(a, 2, "undo <target>");
                List<TestPlayer> list = players.select(a[1]);
                int n = 0;
                for (TestPlayer tp : list) n += plugin.builder().undo(tp);
                ok(sender, "Removed " + n + " blocks built by " + count(list) + ".");
            }
            case "zone" -> zone(sender, a);
            case "api" -> {
                if (a.length > 1 && (a[1].equalsIgnoreCase("on") || a[1].equalsIgnoreCase("off"))) {
                    plugin.llm().setApiEnabled(a[1].equalsIgnoreCase("on"));
                    ok(sender, "AI API " + (a[1].equalsIgnoreCase("on") ? "on" : "paused: test players carry on without it") + ".");
                }
                sender.sendMessage(Component.text("AI: " + plugin.llm().usage(), NamedTextColor.GRAY));
            }
            case "town" -> town(sender, a);
            case "job" -> {
                need(a, 3, "job <target> <" + String.join("|", Town.JOB_BUILDS.keySet()) + "|auto>");
                List<TestPlayer> list = players.select(a[1]);
                for (TestPlayer tp : list) {
                    Town t = plugin.towns().get(tp.town);
                    if (t == null) throw new IllegalArgumentException(tp.name + " doesn't live in a town: /testplayer town join <town> " + tp.name);
                    plugin.towns().join(tp, t, a[2].equalsIgnoreCase("auto") ? null : a[2].toLowerCase(Locale.ROOT));
                }
                ok(sender, count(list) + (list.size() == 1 ? " is now a " + list.getFirst().job : " have new jobs") + ".");
            }
            case "free" -> {
                need(a, 2, "free <target> [team]");
                List<TestPlayer> list = players.select(a[1]);
                Town team = null;
                if (a.length > 2) {
                    team = plugin.towns().get(a[2]);
                    if (team == null) team = plugin.towns().foundCamp(a[2], origin(sender));
                    else if (!team.camp) throw new IllegalArgumentException(a[2] + " is a town; free players go in teams.");
                }
                for (TestPlayer tp : list) plugin.life().makeFree(tp, team);
                ok(sender, count(list) + (team != null ? " now play survival as team " + team.name : " now play survival on their own") + ": tools, a base away from spawn, mining, exploring, the nether.");
            }
            case "team" -> team(sender, a);
            case "populate" -> {
                need(a, 2, "populate <count>   (free players join around the world spawn, solo and in small teams)");
                int n = integer(a[1], 1, 100);
                int room = plugin.settings().maxPlayers() - players.all().size();
                if (n > room) throw new IllegalArgumentException("Only room for " + room + " more (max-players is " + plugin.settings().maxPlayers() + ").");
                Location spawn = sender instanceof Player p ? p.getWorld().getSpawnLocation() : Bukkit.getWorlds().getFirst().getSpawnLocation();
                long now = System.currentTimeMillis();
                List<String> names = new ArrayList<>();
                for (int i = 0; i < n; i++) names.add(plugin.life().newcomer(spawn, now, false).name);
                ok(sender, n + " players joined at spawn: " + String.join(", ", names.subList(0, Math.min(10, names.size()))) + (n > 10 ? "..." : "") + ".");
                sender.sendMessage(Component.text(plugin.llm().ready() ? "They think with " + plugin.llm().placeFor(new TestPlayer("x")) + " (" + plugin.llm().usage() + ")."
                        : "No AI linked, so they play on their own routines. /testplayer ai link to give them a brain.", NamedTextColor.GRAY));
            }
            case "list" -> list(sender);
            case "info" -> {
                need(a, 2, "info <name>");
                info(sender, one(a[1]));
            }
            case "stats" -> stats(sender);
            case "reload" -> {
                plugin.reload();
                ok(sender, "Reloaded config.yml. Website link: " + plugin.link().status() + ".");
            }
            default -> throw new IllegalArgumentException("Unknown subcommand. /testplayer help");
        }
    }

    private String uniqueTeam(String base) {
        String n = base.length() > 20 ? base.substring(0, 20) : base;
        String name = n;
        for (int i = 2; plugin.towns().get(name) != null; i++) name = n + i;
        return name;
    }

    /** /testplayer team create|add|kick|list|info|settle|remove: free players' teams (and real players on them). */
    private void team(CommandSender sender, String[] a) {
        Towns towns = plugin.towns();
        Life life = plugin.life();
        String op = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
        switch (op) {
            case "create" -> {
                need(a, 3, "team create <name> [test players]");
                Town t = towns.foundCamp(a[2], origin(sender));
                List<TestPlayer> list = a.length > 3 ? plugin.players().select(a[3]) : List.of();
                for (TestPlayer tp : list) life.join(tp, t);
                ok(sender, "Team " + t.name + " made" + (list.isEmpty() ? "" : " with " + count(list)) + ". /testplayer team add " + t.name + " <test players or a real player>");
            }
            case "add" -> {
                need(a, 4, "team add <team> <test players | real player>");
                Town t = camp(a[2]);
                Player human = Bukkit.getPlayerExact(a[3]);
                if (human != null && plugin.players().get(a[3]) == null) {
                    life.leaveHuman(human.getName());
                    t.humans.add(human.getName());
                    towns.markDirty();
                    ok(sender, human.getName() + " is on team " + t.name + " now.");
                    human.sendMessage(Component.text("You're on team " + t.name + " (test players). /teamup info", NamedTextColor.GREEN));
                    return;
                }
                List<TestPlayer> list = plugin.players().select(a[3]);
                for (TestPlayer tp : list) life.join(tp, t);
                ok(sender, count(list) + " joined team " + t.name + ".");
            }
            case "kick" -> {
                need(a, 4, "team kick <team> <test players | real player>");
                Town t = camp(a[2]);
                if (t.humans.stream().anyMatch(h -> h.equalsIgnoreCase(a[3]))) {
                    life.leaveHuman(a[3]);
                    ok(sender, a[3] + " left team " + t.name + ".");
                    return;
                }
                List<TestPlayer> list = plugin.players().select(a[3]).stream().filter(tp -> t.name.equalsIgnoreCase(tp.town)).toList();
                for (TestPlayer tp : list) life.makeFree(tp, null);
                ok(sender, count(list) + " left team " + t.name + " and play solo now.");
            }
            case "settle" -> {
                need(a, 3, "team settle <team>   (makes its base where you stand)");
                Town t = camp(a[2]);
                towns.settle(t, player(sender).getLocation());
                for (String id : t.jobs.keySet()) {
                    TestPlayer tp = plugin.players().get(id);
                    if (tp != null) tp.home = t.center();
                }
                ok(sender, t.name + "'s base is here now: " + t.plots.size() + " things planned (a hut each, a farm, a house, a lookout tower).");
            }
            case "info" -> {
                List<Town> show = a.length > 2 ? List.of(camp(a[2])) : towns.camps();
                if (show.isEmpty()) throw new IllegalArgumentException("No teams yet. /testplayer spawn <name> <count> team, or /testplayer populate <count>");
                for (Town t : show) {
                    sender.sendMessage(Component.text(t.name + " (" + t.stage() + ")", NamedTextColor.GOLD));
                    List<String> who = new ArrayList<>();
                    for (Map.Entry<String, String> e : t.jobs.entrySet()) {
                        TestPlayer tp = plugin.players().get(e.getKey());
                        if (tp != null) who.add(tp.name + " (" + e.getValue() + (tp.buddy ? ", with the real players" : "") + ")");
                    }
                    line(sender, "Test players", who.isEmpty() ? "none" : String.join(", ", who));
                    line(sender, "Real players", t.humans.isEmpty() ? "none (/teamup <name> in game)" : String.join(", ", t.humans));
                    line(sender, "Base", t.settled ? t.world + " " + t.cx + " " + t.cz + ", " + t.count("built") + "/" + t.plots.size() + " built" : "none yet (meeting at " + t.cx + " " + t.cz + ")");
                    if (t.storage != null) line(sender, "Storage chest", t.storage.getBlockX() + " " + t.storage.getBlockY() + " " + t.storage.getBlockZ());
                    if (t.mineDx != 0 || t.mineDz != 0) line(sender, "Mine", "entrance " + t.mineX + " " + t.mineY + " " + t.mineZ + ", " + t.mineShafts + " shafts");
                    if (!t.portals.isEmpty()) line(sender, "Nether portals known", String.valueOf(t.portals.size()));
                }
            }
            case "list" -> {
                if (towns.camps().isEmpty()) {
                    sender.sendMessage(Component.text("No teams yet. /testplayer spawn <name> <count> team, or /testplayer populate <count>", NamedTextColor.GRAY));
                    return;
                }
                for (Town t : towns.camps()) sender.sendMessage(Component.text(t.summary(), NamedTextColor.GRAY));
            }
            case "remove" -> {
                need(a, 3, "team remove <team>");
                Town t = camp(a[2]);
                for (String id : new ArrayList<>(t.jobs.keySet())) {
                    TestPlayer tp = plugin.players().get(id);
                    if (tp != null) life.makeFree(tp, null);
                }
                if (towns.get(t.name) != null) towns.remove(t);
                ok(sender, "Team " + t.name + " split up: its test players play solo now. What it built stays.");
            }
            default -> throw new IllegalArgumentException("Usage: /testplayer team create|add|kick|settle|info|list|remove");
        }
    }

    private Town camp(String name) {
        Town t = plugin.towns().get(name);
        if (t == null || !t.camp) throw new IllegalArgumentException("No team called " + name + ". /testplayer team list");
        return t;
    }

    /** /testplayer town found|join|leave|info|list|remove: settlements that grow by themselves. */
    private void town(CommandSender sender, String[] a) {
        Towns towns = plugin.towns();
        String op = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
        switch (op) {
            case "found" -> {
                need(a, 3, "town found <name> [radius]   (around you)   or   town found <name> <radius> <x> <z>");
                int radius = a.length > 3 ? integer(a[3], 30, 200) : 45;
                Location at = a.length >= 6 ? new Location(origin(sender).getWorld(), number(a[4]), 0, number(a[5])) : player(sender).getLocation();
                Town t = towns.found(a[2], at, radius);
                ok(sender, "Founded " + t.name + " at " + t.cx + " " + t.cz + ": " + t.plots.size() + " buildings planned in a " + (radius * 2 + 1)
                        + "-block square (also a build zone). Now: /testplayer town join " + t.name + " <test players>");
            }
            case "join" -> {
                need(a, 4, "town join <town> <target> [job]");
                Town t = towns.get(a[2]);
                if (t == null || t.camp) throw new IllegalArgumentException("No town called " + a[2] + ". /testplayer town list" + (t != null ? " (" + t.name + " is a team: /testplayer team add)" : ""));
                List<TestPlayer> list = plugin.players().select(a[3]);
                list.forEach(tp -> tp.free = false);
                Map<String, Integer> jobs = new java.util.TreeMap<>();
                for (TestPlayer tp : list) jobs.merge(towns.join(tp, t, a.length > 4 ? a[4].toLowerCase(Locale.ROOT) : null), 1, Integer::sum);
                ok(sender, count(list) + " now live in " + t.name + ": " + jobs.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).collect(java.util.stream.Collectors.joining(", ")) + ".");
            }
            case "leave" -> {
                need(a, 3, "town leave <target>");
                List<TestPlayer> list = plugin.players().select(a[2]);
                list.forEach(towns::leave);
                ok(sender, count(list) + " left their town.");
            }
            case "info" -> {
                List<Town> show = a.length > 2 ? List.of(java.util.Optional.ofNullable(towns.get(a[2])).orElseThrow(() -> new IllegalArgumentException("No town called " + a[2] + "."))) : towns.townsOnly();
                if (show.isEmpty()) throw new IllegalArgumentException("No towns yet. /testplayer town found <name> [radius]");
                for (Town t : show) {
                    sender.sendMessage(Component.text(t.name + " (" + t.stage() + ")", NamedTextColor.GOLD));
                    line(sender, "Where", t.world + " " + t.cx + " " + t.cz + ", radius " + t.radius);
                    line(sender, "Buildings", t.count("built") + " built, " + t.count("building") + " going up, " + t.count("planned") + " planned"
                            + (t.count("skipped") > 0 ? ", " + t.count("skipped") + " didn't fit" : ""));
                    line(sender, "Homes / farms", t.builtOf("house", "hut") + " / " + t.builtOf("farm") + (t.builtOf("townhall") > 0 ? ", town hall built" : ""));
                    Map<String, Integer> jobs = new java.util.TreeMap<>();
                    t.jobs.values().forEach(j -> jobs.merge(j, 1, Integer::sum));
                    line(sender, "Citizens", t.jobs.size() + (jobs.isEmpty() ? "" : ": " + jobs.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).collect(java.util.stream.Collectors.joining(", "))));
                    line(sender, "Roads waiting", String.valueOf(t.roads.size()));
                    line(sender, "Storage chest", t.storage == null ? "none yet" : t.storage.getBlockX() + " " + t.storage.getBlockY() + " " + t.storage.getBlockZ());
                    line(sender, "Mine", t.mineDx == 0 && t.mineDz == 0 ? "not started" : "entrance " + t.mineX + " " + t.mineY + " " + t.mineZ + ", " + t.mineShafts + " shafts dug");
                    line(sender, "Nether portals known", t.portals.isEmpty() ? "none (explorers look for them; or /testplayer town portal " + t.name + ")"
                            : t.portals.stream().map(l -> l.getBlockX() + " " + l.getBlockZ()).collect(java.util.stream.Collectors.joining(", ")));
                }
            }
            case "portal" -> {
                need(a, 3, "town portal <town>   (standing next to a lit nether portal)");
                Town t = towns.get(a[2]);
                if (t == null) throw new IllegalArgumentException("No town called " + a[2] + ".");
                towns.addPortal(t, player(sender).getLocation());
                ok(sender, t.name + "'s explorers now know about this portal (" + t.portals.size() + " known). Scouts take trips through it.");
            }
            case "list" -> {
                if (towns.townsOnly().isEmpty()) {
                    sender.sendMessage(Component.text("No towns yet. /testplayer town found <name> [radius]", NamedTextColor.GRAY));
                    return;
                }
                for (Town t : towns.townsOnly()) sender.sendMessage(Component.text(t.summary(), NamedTextColor.GRAY));
            }
            case "remove" -> {
                need(a, 3, "town remove <name>");
                Town t = towns.get(a[2]);
                if (t == null) throw new IllegalArgumentException("No town called " + a[2] + ".");
                towns.remove(t);
                ok(sender, "Removed " + t.name + ". Its buildings stay; /testplayer undo <citizens> removes what they built.");
            }
            default -> throw new IllegalArgumentException("Usage: /testplayer town found|join|leave|info|list|portal|remove");
        }
    }

    /** /testplayer zone add|list|remove: where test players may build. */
    private void zone(CommandSender sender, String[] a) {
        Builder b = plugin.builder();
        String op = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
        switch (op) {
            case "add" -> {
                need(a, 4, "zone add <name> <radius>   (a square around you)   or   zone add <name> <x1> <z1> <x2> <z2>");
                Builder.Zone z;
                if (a.length >= 7) {
                    z = b.addZone(a[2], origin(sender).getWorld(), (int) number(a[3]), (int) number(a[4]), (int) number(a[5]), (int) number(a[6]));
                } else {
                    Location here = player(sender).getLocation();
                    int r = integer(a[3], 4, 500);
                    z = b.addZone(a[2], here.getWorld(), here.getBlockX() - r, here.getBlockZ() - r, here.getBlockX() + r, here.getBlockZ() + r);
                }
                ok(sender, "Build zone " + z.name() + ": " + z.world() + " " + z.x1() + " " + z.z1() + " to " + z.x2() + " " + z.z2()
                        + ". Test players can build there (never over existing blocks).");
            }
            case "remove" -> {
                need(a, 3, "zone remove <name>");
                b.removeZone(a[2]);
                ok(sender, "Removed build zone " + a[2] + ". Builds there stay (/testplayer undo removes a test player's builds).");
            }
            case "list" -> {
                if (b.zones().isEmpty()) {
                    sender.sendMessage(Component.text("No build zones, so test players can't build. /testplayer zone add <name> <radius>", NamedTextColor.GRAY));
                    return;
                }
                sender.sendMessage(Component.text("Build zones (" + b.buildCount() + " builds so far):", NamedTextColor.GOLD));
                for (Builder.Zone z : b.zones()) line(sender, z.name(), z.world() + " " + z.x1() + " " + z.z1() + " to " + z.x2() + " " + z.z2());
            }
            default -> throw new IllegalArgumentException("Usage: /testplayer zone add|list|remove");
        }
    }

    // ------------------------------------------------------------ output

    private void help(CommandSender s, String label) {
        String c = "/" + label + " ";
        s.sendMessage(Component.text("Test players: simulated players for performance testing", NamedTextColor.GOLD));
        s.sendMessage(Component.text("<target> is a name, a prefix ending in * (Load*), or all", NamedTextColor.GRAY));
        for (String[] line : new String[][] {
                {"spawn <name> [count] [idle|wander|walk|free|team]", "spawn one, or a numbered batch around you (free/team: they play survival)"},
                {"populate <count>", "players join at spawn and play survival, solo and in small teams"},
                {"free <target> [team]", "make test players play survival like normal players"},
                {"team create|add|kick|settle|info|list|remove ...", "free players' teams; add a real player to one (or they use /teamup)"},
                {"ai link <ollama|lmstudio|pc|openai|openrouter|groq|anthropic|off> / ai test", "link an AI (local, your PC, or an API key with spending caps)"},
                {"despawn <target>", "take out of the world (start brings back)"},
                {"start <target>", "put stopped test players back"},
                {"remove <target>", "forget them completely"},
                {"move <target> <x y z|here|player>", "walk somewhere"},
                {"follow <target> <player>", "follow a player"},
                {"wander <target> [radius]", "walk around randomly"},
                {"walk <target> [direction]", "walk straight on, loading new chunks"},
                {"stop <target>", "stand still, AI paused"},
                {"sprint <target> <on|off>", ""},
                {"bring <target> / tp <name>", "teleport them to you / you to it"},
                {"say <name> <text>", "chat as the test player"},
                {"ai <target> <on|off|model> [staff]", "let an AI model drive them (staff: on that person's PC)"},
                {"api [on|off]", "the paid AI API's budget and usage; off pauses it"},
                {"goal <target> <text|clear>", "a goal their AI works toward"},
                {"personality <target> <preset|random|text|clear>", "who they are: builder, explorer, leader, joker, nervous, grumpy, friendly"},
                {"build <target> <structure> [material]", "build a hut, house, tower, wall or campfire (inside a build zone)"},
                {"undo <target>", "remove everything they built"},
                {"zone add <name> <radius> / zone list / zone remove <name>", "where test players may build"},
                {"town found <name> [radius] / town join <town> <target> [job]", "a town they build and live in"},
                {"town info|list|leave|remove ... / job <target> <job|auto>", "towns and their citizens' jobs"},
                {"town portal <town>", "tell a town's explorers about the nether portal you're next to"},
                {"list / info <name> / stats", "what they're doing, server performance"},
                {"reload", "re-read config.yml"}}) {
            s.sendMessage(Component.text(c + line[0], NamedTextColor.YELLOW).append(Component.text(line[1].isEmpty() ? "" : "  " + line[1], NamedTextColor.GRAY)));
        }
    }

    private void list(CommandSender s) {
        TestPlayers players = plugin.players();
        if (players.all().isEmpty()) {
            s.sendMessage(Component.text("No test players. /testplayer spawn <name> [count]", NamedTextColor.GRAY));
            return;
        }
        s.sendMessage(Component.text(players.spawnedCount() + " of " + players.all().size() + " test players in the world:", NamedTextColor.GOLD));
        int shown = 0;
        for (TestPlayer tp : players.all()) {
            if (shown++ >= 40) {
                s.sendMessage(Component.text("... and " + (players.all().size() - 40) + " more", NamedTextColor.GRAY));
                break;
            }
            Mannequin m = tp.entity();
            String where = m == null ? (tp.wanted ? "starting" : "stopped")
                    : m.getLocation().getBlockX() + " " + m.getLocation().getBlockY() + " " + m.getLocation().getBlockZ() + " " + m.getWorld().getName();
            s.sendMessage(Component.text(tp.name, m != null ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                    .append(Component.text("  " + where + "  " + (m != null ? tp.doing() : "") + (tp.ai && tp.model != null ? "  AI " + plugin.llm().labelFor(tp) : "")
                            + (tp.personality != null ? "  (" + Personalities.label(tp.personality) + ")" : ""), NamedTextColor.GRAY)));
        }
    }

    private void info(CommandSender s, TestPlayer tp) {
        Mannequin m = tp.entity();
        s.sendMessage(Component.text(tp.name, NamedTextColor.GOLD));
        line(s, "State", m != null ? "in the world" : tp.wanted ? "starting" : "stopped");
        if (m != null) {
            Location l = m.getLocation();
            line(s, "Position", l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ() + " in " + l.getWorld().getName());
            line(s, "Doing", tp.doing() + (tp.sprint ? ", sprinting" : ""));
            line(s, "In the world for", Math.round((System.currentTimeMillis() - tp.spawnedAt) / 60000.0) + " min");
        }
        line(s, "Moved", Math.round(tp.blocksMoved) + " blocks");
        if (tp.free) {
            Town t = plugin.towns().get(tp.town);
            line(s, "Plays", "survival like a normal player: team " + (t == null ? "?" : t.name + (t.jobs.size() == 1 && t.humans.isEmpty() ? " (solo)" : "")) + ", " + tp.job
                    + (tp.buddy ? ", sticks with its real teammates" : "") + (tp.trip != null ? " (on a trip: " + tp.trip + ")" : "") + (tp.auto ? ", comes and goes (keep-online)" : ""));
            if (t != null) line(s, "Base", t.settled ? t.cx + " " + t.cz : "none yet");
            line(s, "Food", tp.food + "/20");
        } else if (tp.town != null) line(s, "Town", tp.town + ", " + tp.job + (tp.trip != null ? " (on a trip: " + tp.trip + ")" : ""));
        line(s, "Carrying", Items.describe(tp, 10));
        if (tp.path != null && tp.pathIndex < tp.path.size()) {
            Path.Step next = tp.path.get(tp.pathIndex);
            Path.Step end = tp.path.getLast();
            line(s, "Route", "step " + (tp.pathIndex + 1) + " of " + tp.path.size() + ", next " + next.x() + " " + next.y() + " " + next.z() + ", ends " + end.x() + " " + end.y() + " " + end.z());
        } else if (tp.pathRetryAt > System.currentTimeMillis() && tp.mode != TestPlayer.Mode.IDLE) line(s, "Route", "none found, heading straight there");
        if (!tp.lastResult.isEmpty()) line(s, "Last result", tp.lastResult);
        line(s, "AI", !tp.ai ? "none" : plugin.llm().labelFor(tp) + " on " + plugin.llm().placeFor(tp) + (tp.aiNote != null ? " (" + tp.aiNote + ")" : ""));
        if (tp.personality != null) line(s, "Personality", tp.personality);
        if (tp.goal != null) line(s, "Goal", tp.goal);
        int built = plugin.builder().buildCount(tp);
        if (built > 0) line(s, "Built", built + " thing" + (built == 1 ? "" : "s"));
        if (!tp.thought.isEmpty()) line(s, "Thinking", tp.thought);
        line(s, "Deaths", String.valueOf(tp.deaths));
    }

    private void stats(CommandSender s) {
        TestPlayers players = plugin.players();
        double[] tps = Bukkit.getTPS();
        s.sendMessage(Component.text("Server performance", NamedTextColor.GOLD));
        line(s, "TPS (1m, 5m, 15m)", String.format(Locale.ROOT, "%.2f, %.2f, %.2f", Math.min(20, tps[0]), Math.min(20, tps[1]), Math.min(20, tps[2])));
        line(s, "MSPT", String.format(Locale.ROOT, "%.2f ms (50 ms is the limit for 20 TPS)", Bukkit.getAverageTickTime()));
        line(s, "Players", Bukkit.getOnlinePlayers().size() + " real, " + players.spawnedCount() + " test (" + players.all().size() + " defined)");
        int ai = 0;
        for (TestPlayer tp : players.all()) if (tp.ai && tp.model != null && tp.entity() != null) ai++;
        line(s, "AI", ai + " controlled by a model, " + plugin.brain().thinking() + " thinking now; " + plugin.llm().usage());
        int loaded = 0;
        int entities = 0;
        List<String> perWorld = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            loaded += w.getLoadedChunks().length;
            entities += w.getEntityCount();
            perWorld.add(w.getName() + " " + w.getLoadedChunks().length);
        }
        line(s, "Chunks", loaded + " loaded (" + String.join(", ", perWorld) + "), " + players.chunksHeld() + " held by test players (radius " + plugin.settings().chunkRadius() + ")");
        line(s, "Entities", String.valueOf(entities));
        Runtime rt = Runtime.getRuntime();
        line(s, "Memory", (rt.totalMemory() - rt.freeMemory()) / 1048576 + " MB used of " + rt.maxMemory() / 1048576 + " MB");
        line(s, "Website", plugin.link().status());
    }

    private static void line(CommandSender s, String k, String v) {
        s.sendMessage(Component.text(k + ": ", NamedTextColor.GRAY).append(Component.text(v, NamedTextColor.WHITE)));
    }

    private static void ok(CommandSender s, String text) {
        s.sendMessage(Component.text(text, NamedTextColor.GREEN));
    }

    // ------------------------------------------------------------ argument helpers

    private TestPlayer one(String name) {
        TestPlayer tp = plugin.players().get(name);
        if (tp == null) throw new IllegalArgumentException("No test player called " + name + ".");
        return tp;
    }

    private static String count(List<TestPlayer> list) {
        return list.size() == 1 ? list.getFirst().name : list.size() + " test players";
    }

    private static void need(String[] a, int n, String usage) {
        if (a.length < n) throw new IllegalArgumentException("Usage: /testplayer " + usage);
    }

    private static Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        throw new IllegalArgumentException("Only a player can do that (it uses where you are).");
    }

    /** Where the sender is, or the first world's spawn from the console. */
    private static Location origin(CommandSender s) {
        if (s instanceof Player p) return p.getLocation();
        if (s instanceof Entity e) return e.getLocation();
        return Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    private static double number(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + s + "\" isn't a number.");
        }
    }

    private static int integer(String s, int lo, int hi) {
        try {
            int n = Integer.parseInt(s);
            if (n < lo || n > hi) throw new IllegalArgumentException("Pick a number from " + lo + " to " + hi + ".");
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + s + "\" isn't a whole number.");
        }
    }

    private static boolean onOff(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes" -> true;
            case "off", "false", "no" -> false;
            default -> throw new IllegalArgumentException("on or off");
        };
    }

    // ------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] a) {
        if (a.length == 1) return starting(SUBS.stream(), a[0]);
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("town")) {
            if (a.length == 2) return starting(Stream.of("found", "join", "leave", "info", "list", "portal", "remove"), a[1]);
            if (a.length == 3 && List.of("join", "info", "remove", "portal").contains(a[1].toLowerCase(Locale.ROOT))) return starting(plugin.towns().all().stream().map(t -> t.name), a[2]);
            if (a.length == 4 && a[1].equalsIgnoreCase("join")) return starting(Stream.concat(Stream.of("all"), plugin.players().all().stream().map(tp -> tp.name)), a[3]);
            if (a.length == 5 && a[1].equalsIgnoreCase("join")) return starting(Town.JOB_BUILDS.keySet().stream(), a[4]);
            if (a.length == 3 && a[1].equalsIgnoreCase("leave")) return starting(Stream.concat(Stream.of("all"), plugin.players().all().stream().map(tp -> tp.name)), a[2]);
            return List.of();
        }
        if (sub.equals("populate")) return a.length == 2 ? starting(Stream.of("5", "10", "20"), a[1]) : List.of();
        if (sub.equals("team")) {
            if (a.length == 2) return starting(Stream.of("create", "add", "kick", "settle", "info", "list", "remove"), a[1]);
            if (a.length == 3 && !a[1].equalsIgnoreCase("create")) return starting(plugin.towns().camps().stream().map(t -> t.name), a[2]);
            if (a.length == 4) return starting(Stream.concat(Stream.concat(Stream.of("all"), plugin.players().all().stream().map(tp -> tp.name)), Bukkit.getOnlinePlayers().stream().map(Player::getName)), a[3]);
            return List.of();
        }
        if (sub.equals("ai") && a.length >= 2 && a[1].equalsIgnoreCase("link")) {
            if (a.length == 3) return starting(AiLink.PROVIDERS.stream(), a[2]);
            return List.of();
        }
        if (sub.equals("zone")) {
            if (a.length == 2) return starting(Stream.of("add", "list", "remove"), a[1]);
            if (a.length == 3 && a[1].equalsIgnoreCase("remove")) return starting(plugin.builder().zones().stream().map(Builder.Zone::name), a[2]);
            return List.of();
        }
        if (a.length == 2 && (TARGETED.contains(sub) || List.of("tp", "say", "info").contains(sub))) {
            Stream<String> names = plugin.players().all().stream().map(tp -> tp.name);
            if (sub.equals("ai")) names = Stream.concat(Stream.of("link", "test"), names);
            return starting(TARGETED.contains(sub) ? Stream.concat(Stream.of("all"), names) : names, a[1]);
        }
        if (a.length == 3) {
            return switch (sub) {
                case "spawn" -> starting(Stream.of("1", "4", "10", "50", "100"), a[2]);
                case "free" -> starting(plugin.towns().camps().stream().map(t -> t.name), a[2]);
                case "move" -> starting(Stream.concat(Stream.of("here"), Bukkit.getOnlinePlayers().stream().map(Player::getName)), a[2]);
                case "follow" -> starting(Bukkit.getOnlinePlayers().stream().map(Player::getName), a[2]);
                case "walk" -> starting(Stream.of("north", "south", "east", "west", "north-east", "north-west", "south-east", "south-west"), a[2]);
                case "sprint" -> starting(Stream.of("on", "off"), a[2]);
                case "ai" -> starting(Stream.of("on", "off", "qwen2.5:3b", "qwen2.5:7b", "claude-haiku-5-5"), a[2]);
                case "goal" -> starting(Stream.of("clear"), a[2]);
                case "personality" -> starting(Stream.concat(Personalities.PRESETS.keySet().stream(), Stream.of("random", "clear")), a[2]);
                case "build" -> starting(Builder.STRUCTURES.stream(), a[2]);
                case "job" -> starting(Stream.concat(Town.JOB_BUILDS.keySet().stream(), Stream.of("auto")), a[2]);
                case "wander" -> starting(Stream.of("16", "32", "64", "128"), a[2]);
                default -> List.of();
            };
        }
        if (a.length == 4 && sub.equals("spawn")) return starting(Stream.of("free", "team", "idle", "wander", "walk"), a[3]);
        if (a.length == 4 && sub.equals("build")) return starting(Builder.MATERIALS.stream(), a[3]);
        return List.of();
    }

    private static List<String> starting(Stream<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).limit(50).toList();
    }
}
