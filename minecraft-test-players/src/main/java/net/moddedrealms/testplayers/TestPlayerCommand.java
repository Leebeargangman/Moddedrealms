package net.moddedrealms.testplayers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
            "sprint", "bring", "tp", "say", "ai", "goal", "list", "info", "stats", "reload", "help");
    private static final List<String> TARGETED = List.of("despawn", "start", "remove", "move", "follow", "wander", "walk", "stop", "sprint", "bring", "ai", "goal");

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
                need(a, 2, "spawn <name> [count] [idle|wander|walk]");
                int count = a.length > 2 ? integer(a[2], 1, plugin.settings().maxPlayers()) : 1;
                String mode = a.length > 3 ? a[3].toLowerCase(Locale.ROOT) : "idle";
                if (!List.of("idle", "wander", "walk").contains(mode)) throw new IllegalArgumentException("Mode is idle, wander or walk.");
                List<String> names = players.batchNames(a[1], count);
                int room = plugin.settings().maxPlayers() - players.all().size();
                if (names.size() > room) throw new IllegalArgumentException("Only room for " + room + " more (max-players is " + plugin.settings().maxPlayers() + ").");
                Location center = origin(sender);
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
                    }
                    players.spawn(tp, at);
                }
                ok(sender, "Spawning " + (names.size() == 1 ? names.getFirst() : names.size() + " test players (" + names.getFirst() + " to " + names.getLast() + ")") + ", " + mode + ".");
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
                need(a, 3, "ai <target> <model|on|off> [staff member, to use their computer]");
                List<TestPlayer> list = players.select(a[1]);
                String v = a[2];
                for (TestPlayer tp : list) {
                    if (v.equalsIgnoreCase("off")) tp.ai = false;
                    else if (v.equalsIgnoreCase("on")) {
                        if (tp.model == null) throw new IllegalArgumentException(tp.name + " has no model yet: /testplayer ai " + tp.name + " <model>, e.g. qwen2.5:3b");
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
                        : count(list) + " now controlled by " + first.model + (first.staff != null ? " on " + first.staff + "'s computer (through the website)" : " on " + plugin.settings().ollamaUrl()) + ".");
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

    // ------------------------------------------------------------ output

    private void help(CommandSender s, String label) {
        String c = "/" + label + " ";
        s.sendMessage(Component.text("Test players: simulated players for performance testing", NamedTextColor.GOLD));
        s.sendMessage(Component.text("<target> is a name, a prefix ending in * (Load*), or all", NamedTextColor.GRAY));
        for (String[] line : new String[][] {
                {"spawn <name> [count] [idle|wander|walk]", "spawn one, or a numbered batch around you"},
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
                {"ai <target> <model|on|off> [staff]", "let an AI model control them"},
                {"goal <target> <text|clear>", "a goal their AI works toward"},
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
                    .append(Component.text("  " + where + "  " + (m != null ? tp.doing() : "") + (tp.ai && tp.model != null ? "  AI " + tp.model : ""), NamedTextColor.GRAY)));
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
        if (!tp.lastResult.isEmpty()) line(s, "Last result", tp.lastResult);
        line(s, "AI", tp.model == null ? "none" : tp.model + (tp.staff != null ? " on " + tp.staff + "'s computer" : "") + (tp.ai ? "" : " (paused)"));
        if (tp.goal != null) line(s, "Goal", tp.goal);
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
        line(s, "AI", ai + " controlled by a model, " + plugin.brain().thinking() + " thinking now");
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
        if (a.length == 2 && (TARGETED.contains(sub) || List.of("tp", "say", "info").contains(sub))) {
            Stream<String> names = plugin.players().all().stream().map(tp -> tp.name);
            return starting(TARGETED.contains(sub) ? Stream.concat(Stream.of("all"), names) : names, a[1]);
        }
        if (a.length == 3) {
            return switch (sub) {
                case "spawn" -> starting(Stream.of("1", "10", "50", "100"), a[2]);
                case "move" -> starting(Stream.concat(Stream.of("here"), Bukkit.getOnlinePlayers().stream().map(Player::getName)), a[2]);
                case "follow" -> starting(Bukkit.getOnlinePlayers().stream().map(Player::getName), a[2]);
                case "walk" -> starting(Stream.of("north", "south", "east", "west", "north-east", "north-west", "south-east", "south-west"), a[2]);
                case "sprint" -> starting(Stream.of("on", "off"), a[2]);
                case "ai" -> starting(Stream.of("off", "on", "qwen2.5:0.5b", "qwen2.5:1.5b", "qwen2.5:3b", "qwen2.5:7b", "llama3.2:3b"), a[2]);
                case "goal" -> starting(Stream.of("clear"), a[2]);
                case "wander" -> starting(Stream.of("16", "32", "64", "128"), a[2]);
                default -> List.of();
            };
        }
        if (a.length == 4 && sub.equals("spawn")) return starting(Stream.of("idle", "wander", "walk"), a[3]);
        return List.of();
    }

    private static List<String> starting(Stream<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).limit(50).toList();
    }
}
