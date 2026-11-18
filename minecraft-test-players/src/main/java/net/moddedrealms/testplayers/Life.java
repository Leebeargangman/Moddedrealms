package net.moddedrealms.testplayers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * Free players: test players that live like normal survival players instead of working through a town plan.
 * They start with nothing, like anyone joining a server: punch a tree, make tools, mine some stone, hunt when
 * they get hungry. Then they walk out away from spawn, find a spot nobody has built on and set up a base (a
 * campfire, a hut each, a farm...), and from there they mine, explore, go to the nether, trade at spawn.
 *
 * They play solo or in teams (a team is a small Town with camp = true). Real players can team up with them
 * (/teamup): a couple of the team's test players then stick with them, fight off monsters going for them,
 * chop and mine nearby when they stop, follow them through portals, and share what they carry (right-click).
 * The rest of the team carries on with its own jobs. With an AI model each one decides for itself.
 */
final class Life implements Listener, TabExecutor {

    private static final Set<EntityType> GAME = EnumSet.of(EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN, EntityType.RABBIT);

    /** Food it eats, best first, with how much it fills it up. */
    private static final Map<Material, Integer> FOOD = new LinkedHashMap<>();
    /** What it cooks raw food into. */
    private static final Map<Material, Material> COOKED = new LinkedHashMap<>();

    static {
        FOOD.put(Material.COOKED_BEEF, 8);
        FOOD.put(Material.COOKED_PORKCHOP, 8);
        FOOD.put(Material.COOKED_MUTTON, 6);
        FOOD.put(Material.COOKED_CHICKEN, 6);
        FOOD.put(Material.COOKED_RABBIT, 5);
        FOOD.put(Material.BREAD, 5);
        FOOD.put(Material.BAKED_POTATO, 5);
        FOOD.put(Material.APPLE, 4);
        FOOD.put(Material.CARROT, 3);
        FOOD.put(Material.BEEF, 3);
        FOOD.put(Material.PORKCHOP, 3);
        FOOD.put(Material.SWEET_BERRIES, 2);
        FOOD.put(Material.MUTTON, 2);
        COOKED.put(Material.BEEF, Material.COOKED_BEEF);
        COOKED.put(Material.PORKCHOP, Material.COOKED_PORKCHOP);
        COOKED.put(Material.MUTTON, Material.COOKED_MUTTON);
        COOKED.put(Material.CHICKEN, Material.COOKED_CHICKEN);
        COOKED.put(Material.RABBIT, Material.COOKED_RABBIT);
        COOKED.put(Material.POTATO, Material.BAKED_POTATO);
    }

    /** Parts of blocks only players place: a spot near these is someone's, not wilderness. */
    private static final String[] MAN_MADE = {"PLANKS", "BRICK", "GLASS", "WOOL", "CONCRETE", "_BED", "CHEST", "BARREL", "TORCH", "LANTERN",
        "CRAFTING_TABLE", "FURNACE", "DOOR", "FENCE", "SLAB", "STAIRS", "CARPET", "SIGN", "BANNER", "RAIL", "FARMLAND", "DIRT_PATH", "SMOOTH_",
        "POLISHED_", "CUT_", "CHISELED_", "BOOKSHELF", "HAY_BLOCK", "GLAZED", "BELL", "ANVIL", "LADDER", "SCAFFOLDING", "PRESSURE_PLATE",
        "BUTTON", "LEVER", "CAMPFIRE", "SHULKER", "BEACON", "HOPPER", "DISPENSER", "DROPPER", "OBSERVER", "PISTON", "REPEATER", "COMPARATOR",
        "FLOWER_POT", "COBBLESTONE", "_WALL", "BED", "LOOM", "SMOKER", "LECTERN", "COMPOSTER", "CAULDRON", "STONECUTTER", "GRINDSTONE"};

    private static final String[] NAME_A = {"Ash", "Birch", "Cobalt", "Dusk", "Ember", "Flint", "Gale", "Haze", "Jade", "Kite", "Lynx", "Moss",
        "Nova", "Onyx", "Pike", "Quartz", "Rook", "Sage", "Thorn", "Vex", "Wren", "Frost", "Blaze", "Storm", "Pixel", "Echo", "Rusty", "Maple",
        "Copper", "Fox", "Otter", "Raven", "Badger", "Cinder", "Drift", "Lumen", "Pebble", "Bramble", "Sparrow", "Juniper", "Basalt", "Marlo"};
    private static final String[] NAME_B = {"Miner", "Craft", "Digs", "Builds", "Wolf", "Bear", "Hawk", "Byte", "Leaf", "Rock", "Spark", "Pine"};
    private static final String[] TEAM_NAMES = {"Wolfpack", "Nomads", "Oak_Crew", "Night_Owls", "Stonecutters", "Wanderers", "Ravens",
        "Deep_Diggers", "Pathfinders", "Lantern_Club", "Iron_Will", "Moss_Gang", "Driftwood", "Hearthkeepers", "Ridge_Runners"};

    private final TestPlayersPlugin plugin;
    private final Map<String, Location> humanSeen = new HashMap<>();   // real teammate -> roughly where they've been standing...
    private final Map<String, Long> humanStill = new HashMap<>();      // ...since then
    private final Map<String, Location> humanLeftBy = new HashMap<>(); // real teammate -> the portal they last went through
    private final Map<String, Long> farSince = new HashMap<>();        // test player -> since when its teammate's been out of reach
    private final Map<UUID, String> lastHitBy = new HashMap<>();       // entity -> the test player that hit it last
    private final Map<UUID, Long> cantCatch = new HashMap<>();         // animals someone couldn't get to: left alone until then
    private Town forming;           // keep-online: the team newcomers are joining
    private int formingSize;
    private long formingAt;
    private long lastPopulate;
    private long combatTicks;

    Life(TestPlayersPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean cfg(String key, boolean def) {
        return plugin.getConfig().getBoolean("free." + key, def);
    }

    private int cfgInt(String key, int def) {
        return plugin.getConfig().getInt("free." + key, def);
    }

    private static ThreadLocalRandom rnd() {
        return ThreadLocalRandom.current();
    }

    // ------------------------------------------------------------ teams

    /** Make it a free player: in team, or (null) on its own. */
    void makeFree(TestPlayer tp, Town team) {
        if (team == null) {
            Town old = plugin.towns().get(tp.town);
            if (tp.free && old != null && old.camp && old.jobs.size() == 1 && old.jobs.containsKey(tp.id)) return; // on its own already
            team = plugin.towns().foundCamp(freeTeamName(tp.name), where(tp));
        }
        join(tp, team);
    }

    /** Join a team (leaving any town or team it was in). */
    void join(TestPlayer tp, Town team) {
        Towns towns = plugin.towns();
        if (tp.town != null && !tp.town.equalsIgnoreCase(team.name)) towns.leave(tp);
        tp.free = true;
        tp.town = team.name;
        tp.job = roleFor(tp, team);
        team.jobs.put(tp.id, tp.job);
        tp.jobUntil = 0;
        tp.seekBase = null;
        // With real players on the team, the first couple stick with them.
        tp.buddy = !team.humans.isEmpty() && buddies(team) < 2 && !tp.job.equals("scout");
        if (team.settled && team.center() != null) tp.home = team.center();
        plugin.players().idle(tp);
        towns.markDirty();
    }

    /** What it does on its team: the first does a bit of everything; the others pick a speciality. */
    private static String roleFor(TestPlayer tp, Town team) {
        List<String> taken = team.jobs.entrySet().stream().filter(e -> !e.getKey().equals(tp.id)).map(Map.Entry::getValue).toList();
        if (taken.isEmpty()) return "settler";
        String preset = Towns.jobFor(tp);
        if (List.of("scout", "guard", "farmer", "trader").contains(preset) && !taken.contains(preset)) return preset;
        for (String j : List.of("miner", "lumberjack", "scout", "guard", "farmer")) if (!taken.contains(j)) return j;
        return "settler";
    }

    private int buddies(Town team) {
        int n = 0;
        for (String id : team.jobs.keySet()) {
            TestPlayer o = plugin.players().get(id);
            if (o != null && o.buddy) n++;
        }
        return n;
    }

    private String freeTeamName(String base) {
        String n = base.length() > 20 ? base.substring(0, 20) : base;
        if (n.length() < 2) n = n + "_team";
        String name = n;
        for (int i = 2; plugin.towns().get(name) != null; i++) name = n + i;
        return name;
    }

    /** The team a real player is on, if any. */
    Town teamOf(String player) {
        for (Town t : plugin.towns().camps()) for (String h : t.humans) if (h.equalsIgnoreCase(player)) return t;
        return null;
    }

    /** Is this player on the test player's team? */
    boolean teammates(Player p, TestPlayer tp) {
        Town t = plugin.towns().get(tp.town);
        return t != null && t.camp && t.humans.stream().anyMatch(h -> h.equalsIgnoreCase(p.getName()));
    }

    /** A real player joins the test player's team (or the test player joins theirs). Returns what happened. */
    String teamUp(Player p, TestPlayer tp) {
        if (!tp.free) makeFree(tp, null);
        Town mine = plugin.towns().get(tp.town);
        Town theirs = teamOf(p.getName());
        if (mine == null) throw new IllegalStateException(tp.name + " has no team.");
        if (theirs == mine) {
            tp.buddy = true;
            return tp.name + " is already on your team (" + mine.name + ").";
        }
        boolean botSolo = mine.jobs.size() == 1 && mine.humans.isEmpty();
        if (theirs != null && botSolo) {
            join(tp, theirs); // it comes over to your team
            tp.buddy = true;
            say(theirs, tp.name + " joined your team " + theirs.name + ".");
            return tp.name + " joined your team " + theirs.name + ".";
        }
        if (mine.humans.size() >= 4) throw new IllegalStateException(mine.name + " already has 4 real players.");
        if (theirs != null) leaveHuman(p.getName());
        mine.humans.add(p.getName());
        tp.buddy = true;
        if (buddies(mine) < 2) {
            for (String id : mine.jobs.keySet()) {
                TestPlayer o = plugin.players().get(id);
                if (o != null && o != tp && !o.buddy && !"scout".equals(o.job)) {
                    o.buddy = true;
                    break;
                }
            }
        }
        plugin.towns().markDirty();
        return "You're on team " + mine.name + " with " + memberNames(mine) + "." + (mine.settled ? " Base at " + mine.cx + " " + mine.cz + "." : " No base yet: they'll find a spot.");
    }

    void leaveHuman(String player) {
        Town t = teamOf(player);
        if (t == null) return;
        t.humans.removeIf(h -> h.equalsIgnoreCase(player));
        if (t.humans.isEmpty()) {
            for (String id : t.jobs.keySet()) {
                TestPlayer o = plugin.players().get(id);
                if (o != null) o.buddy = false;
            }
        }
        plugin.towns().markDirty();
    }

    private String memberNames(Town t) {
        List<String> names = new ArrayList<>();
        for (String id : t.jobs.keySet()) {
            TestPlayer o = plugin.players().get(id);
            if (o != null) names.add(o.name + " (" + o.job + ")");
        }
        names.addAll(t.humans);
        return String.join(", ", names);
    }

    private void say(Town t, String text) {
        for (String h : t.humans) {
            Player p = Bukkit.getPlayerExact(h);
            if (p != null) p.sendMessage(Component.text("[" + t.name + "] " + text, NamedTextColor.GRAY));
        }
    }

    private static Location where(TestPlayer tp) {
        Mannequin m = tp.entity();
        if (m != null) return m.getLocation();
        if (tp.home != null) return tp.home;
        if (tp.lastKnown != null) return tp.lastKnown;
        return Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    /** Its team's first test player in the world (the one who decides where the base goes). */
    private TestPlayer leader(Town t) {
        TestPlayer best = null;
        for (Map.Entry<String, String> e : t.jobs.entrySet()) {
            TestPlayer o = plugin.players().get(e.getKey());
            if (o == null || o.entity() == null) continue;
            if (best == null || e.getValue().equals("settler") && !"settler".equals(best.job)) best = o;
        }
        return best;
    }

    // ------------------------------------------------------------ every second, for each free player

    void live(TestPlayer tp, Town t, Mannequin m) {
        long now = System.currentTimeMillis();
        Towns towns = plugin.towns();
        TestPlayers players = plugin.players();
        towns.behave(tp, m, now);
        if (tp.prey != null) {
            fight(tp, m, now);
            return;
        }
        if (tp.pendingGive != null) {
            give(tp, m, now);
            return;
        }
        Player mate = tp.buddy ? mateOnline(t) : null;
        if (mate != null && tp.trip == null && companion(tp, t, m, mate, now)) return;
        if ("market".equals(tp.trip)) {
            market(tp, m, now);
            return;
        }
        if (tp.trip != null) {
            towns.trip(tp, t, m, now);
            return;
        }
        World w = m.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL || (t.settled && !w.getName().equals(t.world))) {
            tp.trip = "home"; // in another world (it followed someone, or walked into a portal): find the way back
            tp.tripFrom = null;
            tp.tripUntil = now + 300_000;
            players.idle(tp);
            return;
        }
        // Starving with nothing to eat: that comes first, even mid-build (the build carries on after).
        if (tp.food <= 6 && foodCount(tp) == 0 && (tp.food <= 3 || !busy(tp)) && now - tp.huntTryAt > 60_000) {
            tp.huntTryAt = now;
            if (hunt(tp, m, 48, now)) return;
            if (t.settled && t.builtOf("farm") > 0 && Town.hdist(m.getLocation(), t.center()) < 200) {
                players().idle(tp);
                plugin.towns().farm(tp, t, m, now);
                return;
            }
            // Nothing to hunt around here: go somewhere new to look (fresh land has animals).
            double a = rnd().nextDouble(Math.PI * 2);
            players().goTo(tp, m.getLocation().clone().add(Math.cos(a) * rnd().nextDouble(70, 130), 0, Math.sin(a) * rnd().nextDouble(70, 130)));
            tp.buildStatus = "looking for food";
            tp.jobUntil = now + 60_000;
            return;
        }
        if (busy(tp)) return;
        eat(tp, m);
        if (tp.scoutReport != null && tp.mode == TestPlayer.Mode.IDLE) {
            if (now - tp.lastSaidAt > 180_000) players.say(tp, tp.scoutReport);
            tp.scoutReport = null;
        }
        if (now < tp.jobUntil && tp.mode != TestPlayer.Mode.IDLE) return;
        if (!t.settled) roam(tp, t, m, now);
        else based(tp, t, m, now);
    }

    private static boolean busy(TestPlayer tp) {
        return tp.mode == TestPlayer.Mode.BUILD || tp.mode == TestPlayer.Mode.WORK || tp.pendingBuild != null;
    }

    /** No base yet: get the basics like anyone new, stick together, and the leader goes to find a spot. */
    private void roam(TestPlayer tp, Town t, Mannequin m, long now) {
        Location l = m.getLocation();
        TestPlayer lead = leader(t);
        boolean leading = lead == tp;
        if (leading) {
            t.world = l.getWorld().getName(); // the team's meeting point is wherever its leader is
            t.cx = l.getBlockX();
            t.cz = l.getBlockZ();
        }
        // Wood, then wooden tools, then stone for stone tools.
        if (Items.tool(tp, "PICKAXE") < 0 && !Items.craftTool(tp, "PICKAXE")) {
            chop(tp, m, now, t);
            return;
        }
        Items.craftTool(tp, "AXE");
        if (Items.tool(tp, "PICKAXE") < 1 || Items.stone(tp) < 12) {
            Gather.Work w = surface(m.getLocation(), 14);
            if (w != null) {
                plugin.gather().start(tp, w);
                return;
            }
        }
        Items.craftTool(tp, "PICKAXE");
        Items.craftTool(tp, "AXE");
        if (Items.tool(tp, "SWORD") < 0 && Items.logs(tp) >= 6) Items.craftTool(tp, "SWORD");
        if (hungry(tp) && hunt(tp, m, 40, now)) return;
        // Stay with the group.
        Mannequin lm = lead == null || leading ? null : lead.entity();
        if (lm != null && lm.getWorld() == l.getWorld() && Town.hdist(lm.getLocation(), l) > 24) {
            players().goTo(tp, lm.getLocation());
            tp.buildStatus = "catching up with " + lead.name;
            tp.jobUntil = now + 20_000;
            return;
        }
        if (leading && cfg("build-bases", true) && Items.tool(tp, "PICKAXE") >= 1) {
            seek(tp, t, m, now);
            return;
        }
        if (Items.logs(tp) < 32) {
            chop(tp, m, now, t);
            return;
        }
        double r = rnd().nextDouble();
        if (r < 0.3 && hunt(tp, m, 32, now)) return;
        if (r < 0.6) {
            Gather.Work w = surface(m.getLocation(), 16);
            if (w != null) {
                plugin.gather().start(tp, w);
                return;
            }
        }
        if (leading && r > 0.85) {
            plugin.towns().startTrip(tp, t, m, t.center(), now, null);
            return;
        }
        Location c = lm != null ? lm.getLocation() : l;
        plugin.towns().patrol(tp, t, c, 20, leading ? "looking around" : "hanging out with the team", now);
    }

    /** The leader walks out away from spawn to a spot nobody has built on, and the team settles there. */
    private void seek(TestPlayer tp, Town t, Mannequin m, long now) {
        Location l = m.getLocation();
        World w = l.getWorld();
        Location spawn = w.getSpawnLocation();
        int minD = Math.max(0, cfgInt("base-min-distance-from-spawn", 150));
        if (tp.seekBase == null) {
            double from = Town.hdist(l, spawn);
            double a = from > 8 ? Math.atan2(l.getZ() - spawn.getZ(), l.getX() - spawn.getX()) + rnd().nextDouble(-0.7, 0.7) : rnd().nextDouble(Math.PI * 2);
            Location to = from < minD
                    ? spawn.clone().add(Math.cos(a) * (minD + rnd().nextDouble(20, 140)), 0, Math.sin(a) * (minD + rnd().nextDouble(20, 140)))
                    : l.clone().add(Math.cos(a) * rnd().nextDouble(40, 90), 0, Math.sin(a) * rnd().nextDouble(40, 90));
            tp.seekBase = to;
            tp.seekTries++;
            players().goTo(tp, to);
            tp.buildStatus = "looking for a spot for a base";
            tp.jobUntil = now + 240_000;
            if (!tp.ai && tp.seekTries == 1 && rnd().nextBoolean()) players().say(tp, t.jobs.size() > 1 ? "let's go find a spot for our base" : "gonna go find somewhere to build");
            return;
        }
        String why = siteProblem(t, l, minD);
        boolean ok = why == null || tp.seekTries >= 6 && why.startsWith("too steep");
        if (!ok) {
            tp.lastResult = "no good for a base here: " + why;
            tp.seekBase = null;
            if (tp.seekTries >= 12) {
                tp.seekTries = 0; // give it a rest, look again later
                tp.jobUntil = now + 300_000;
                plugin.towns().patrol(tp, t, l, 24, "exploring", now);
            }
            return;
        }
        Location ground = w.getHighestBlockAt(l.getBlockX(), l.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES).getLocation().add(0.5, 1, 0.5);
        plugin.towns().settle(t, ground);
        tp.seekBase = null;
        tp.seekTries = 0;
        for (String id : t.jobs.keySet()) {
            TestPlayer o = plugin.players().get(id);
            if (o != null) o.home = t.center();
        }
        tp.lastResult = "set up base at " + t.cx + " " + t.cz;
        if (!tp.ai) players().say(tp, "this spot's perfect, setting up " + (t.jobs.size() > 1 ? "our" : "my") + " base here");
        say(t, "Your team set up base at " + t.cx + " " + t.cz + ".");
        plugin.getLogger().info("Team " + t.name + " set up base at " + t.cx + " " + t.cz + " in " + t.world);
    }

    /** Why a base can't go here (null if it can): near spawn, someone's builds, another base, water, a cliff. */
    String siteProblem(Town t, Location at, int minD) {
        World w = at.getWorld();
        if (Town.hdist(at, w.getSpawnLocation()) < minD) return "too close to spawn";
        for (Town o : plugin.towns().all()) {
            if (o == t || !o.settled || !o.world.equals(w.getName())) continue;
            Location c = new Location(w, o.cx, at.getY(), o.cz);
            if (Town.hdist(c, at) < o.radius + 28 + 48) return "too close to " + o.name;
        }
        int n = 0;
        int water = 0;
        double sum = 0;
        double sq = 0;
        for (int dx = -36; dx <= 36; dx += 4) {
            for (int dz = -36; dz <= 36; dz += 4) {
                int x = at.getBlockX() + dx;
                int z = at.getBlockZ() + dz;
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (manMade(top.getType()) || manMade(top.getRelative(BlockFace.UP).getType())) return "someone has built near here";
                n++;
                if (top.isLiquid()) water++;
                if (Math.abs(dx) <= 16 && Math.abs(dz) <= 16) {
                    sum += top.getY();
                    sq += (double) top.getY() * top.getY();
                }
            }
        }
        if (n > 0 && water * 3 > n) return "too much water";
        int k = 81;
        double sd = Math.sqrt(Math.max(0, sq / k - (sum / k) * (sum / k)));
        if (sd > 5) return "too steep";
        return null;
    }

    static boolean manMade(Material m) {
        String n = m.name();
        for (String k : MAN_MADE) if (n.contains(k)) return true;
        return false;
    }

    /** Any player-made block within r of b. */
    private static boolean nearBuilt(Block b, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) if (manMade(b.getRelative(dx, dy, dz).getType())) return true;
            }
        }
        return false;
    }

    /** Settled: life at the base. Build it up, do its job, and otherwise whatever it feels like. */
    private void based(TestPlayer tp, Town t, Mannequin m, long now) {
        Towns towns = plugin.towns();
        Location c = t.center();
        if (c == null) return;
        long time = m.getWorld().getTime();
        boolean night = time > 12_800 && time < 23_200;
        if (time > 11_600 && time <= 12_800 && t.jobs.size() > 1 && !tp.job.equals("guard") && Town.hdist(c, m.getLocation()) < 120) {
            towns.patrol(tp, t, c, 5, "hanging out by the campfire", now);
            return;
        }
        if (night && !tp.job.equals("guard")) {
            // Miners keep going underground (it's no darker down there); everyone else goes home.
            if (List.of("miner", "settler").contains(tp.job) && Items.tool(tp, "PICKAXE") >= 0 && rnd().nextBoolean()) {
                towns.miner(tp, t, m, now);
                return;
            }
            int home = t.homeFor(tp.id);
            Location bed = home >= 0 && t.plots.get(home).built != null ? t.plots.get(home).built : c;
            tp.buildStatus = home >= 0 ? "home for the night" : "sleeping by the campfire";
            if (Town.hdist(bed, m.getLocation()) > 3) players().goTo(tp, bed);
            else players().idle(tp);
            tp.jobUntil = now + 20_000;
            return;
        }
        if (Towns.haul(tp) >= 64 && towns.deposit(tp, t)) return;
        // The base first: settlers build the next thing, the others lend a hand now and then.
        int plot = tp.job.equals("settler") || rnd().nextDouble() < 0.3 ? t.nextPlot("settler") : -1;
        if (towns.buildPlot(tp, t, m, plot, now)) return;
        if (t.roadBy == null && t.roads.size() >= 2 && tp.job.equals("settler")) {
            Location[] r = t.roads.poll();
            if (plugin.builder().startRoad(tp, t, r[0], r[1])) t.roadBy = tp.id;
            return;
        }
        if (hungry(tp) && food(tp, t, m, now)) return;
        switch (tp.job) {
            case "miner" -> towns.miner(tp, t, m, now);
            case "lumberjack" -> towns.lumberjack(tp, t, m, now);
            case "scout" -> towns.startTrip(tp, t, m, c, now, null);
            case "farmer" -> {
                if (t.builtOf("farm") > 0) towns.farm(tp, t, m, now);
                else if (!hunt(tp, m, 48, now)) chores(tp, t, m, now);
            }
            case "guard" -> {
                if (tp.crewOf == null) towns.patrol(tp, t, c, 18, "keeping watch", now);
            }
            case "trader" -> {
                if (rnd().nextDouble() < 0.5 && startMarket(tp, m, now)) return;
                chores(tp, t, m, now);
            }
            default -> chores(tp, t, m, now);
        }
    }

    /** What a player does when nothing needs doing: a bit of everything, what it's short of most likely. */
    private void chores(TestPlayer tp, Town t, Mannequin m, long now) {
        Towns towns = plugin.towns();
        Map<String, Double> w = new LinkedHashMap<>();
        boolean iron = Items.tool(tp, "PICKAXE") >= 2;
        w.put("mine", Items.stone(tp) < 96 || !iron ? 30.0 : 10.0);
        w.put("chop", Items.logs(tp) < 48 ? 20.0 : 5.0);
        w.put("hunt", foodCount(tp) < 6 ? 14.0 : 3.0);
        w.put("explore", 10.0);
        if (!towns.portalsFor(t, m.getLocation()).isEmpty() && Items.tool(tp, "PICKAXE") >= 1) w.put("nether", 8.0);
        if (spare(tp) >= 2 && Town.hdist(m.getLocation(), m.getWorld().getSpawnLocation()) < 900) w.put("trade", 6.0);
        if (t.builtOf("farm") > 0) w.put("farm", 5.0);
        w.put("rest", 4.0);
        double total = w.values().stream().mapToDouble(Double::doubleValue).sum();
        double r = rnd().nextDouble(total);
        String pick = "rest";
        for (Map.Entry<String, Double> e : w.entrySet()) {
            r -= e.getValue();
            if (r <= 0) {
                pick = e.getKey();
                break;
            }
        }
        Location c = t.center();
        switch (pick) {
            case "mine" -> towns.miner(tp, t, m, now);
            case "chop" -> chop(tp, m, now, t);
            case "hunt" -> {
                if (!hunt(tp, m, 56, now)) chop(tp, m, now, t);
            }
            case "explore" -> towns.startTrip(tp, t, m, c, now, false);
            case "nether" -> towns.startTrip(tp, t, m, c, now, true);
            case "trade" -> {
                if (!startMarket(tp, m, now)) towns.miner(tp, t, m, now);
            }
            case "farm" -> towns.farm(tp, t, m, now);
            default -> towns.patrol(tp, t, c, 8, t.jobs.size() > 1 ? "chilling at the base" : "taking a break", now);
        }
    }

    private TestPlayers players() {
        return plugin.players();
    }

    /** Chop the nearest tree (replanting, except inside its own base), or go looking for trees. */
    private void chop(TestPlayer tp, Mannequin m, long now, Town t) {
        Location l = m.getLocation();
        boolean replant = t == null || !t.settled || Town.hdist(l, t.center()) > t.radius * 0.9;
        Gather.Work w = plugin.gather().tree(l, 24, l, replant);
        if (w == null) w = plugin.gather().tree(l, 48, l, replant);
        if (w != null) {
            plugin.gather().start(tp, w);
            return;
        }
        double a = rnd().nextDouble(Math.PI * 2);
        players().goTo(tp, l.clone().add(Math.cos(a) * 60, 0, Math.sin(a) * 60));
        tp.buildStatus = "looking for trees";
        tp.jobUntil = now + 40_000;
    }

    /**
     * Stone and ore it can see without digging a mine: hillsides, cave mouths, cliffs. Only natural stone well
     * away from anything a player built (so it never digs into someone's house).
     */
    Gather.Work surface(Location around, int radius) {
        World w = around.getWorld();
        List<Block> found = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = around.getBlockX() + dx;
                int z = around.getBlockZ() + dz;
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int dy = -4; dy <= 4; dy++) {
                    Block b = w.getBlockAt(x, around.getBlockY() + dy, z);
                    String n = b.getType().name();
                    if (!(n.equals("STONE") || n.equals("DEEPSLATE") || n.equals("ANDESITE") || n.equals("DIORITE") || n.equals("GRANITE") || n.endsWith("_ORE"))) continue;
                    if (!exposed(b)) continue;
                    found.add(b);
                }
            }
        }
        if (found.isEmpty()) return null;
        // Ore first (that's what a player goes for), then the nearest stone.
        found.sort(Comparator.comparingInt((Block b) -> b.getType().name().endsWith("_ORE") ? 0 : 1).thenComparingDouble(b -> b.getLocation().distanceSquared(around)));
        Gather.Work work = new Gather.Work("mining stone");
        for (Block b : found) {
            if (work.todo.size() >= 14) break;
            if (nearBuilt(b, 3)) continue;
            work.todo.add(b);
        }
        return work.todo.isEmpty() ? null : work;
    }

    private static boolean exposed(Block b) {
        for (BlockFace f : new BlockFace[] {BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Material t = b.getRelative(f).getType();
            if (t.isAir() || t == Material.CAVE_AIR) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ food: hunger, hunting, cooking, eating

    /** Every second: hunger goes down slowly while it's out and about, like a player's. */
    private void hunger(TestPlayer tp, long now) {
        if (!cfg("hunger", true)) {
            tp.food = 20;
            return;
        }
        if (now - tp.hungerAt < 75_000) return;
        tp.hungerAt = now;
        if (tp.mode != TestPlayer.Mode.IDLE && tp.food > 0) tp.food--;
    }

    private static boolean hungry(TestPlayer tp) {
        return tp.food <= 10 && foodCount(tp) == 0;
    }

    static boolean isFood(Material m) {
        return FOOD.containsKey(m) || COOKED.containsKey(m);
    }

    private static int foodCount(TestPlayer tp) {
        int n = 0;
        for (Material f : FOOD.keySet()) n += Items.count(tp, f);
        return n;
    }

    /** Hungry and carrying food: cook what's raw (with a furnace and fuel), then eat, with the sounds. */
    private void eat(TestPlayer tp, Mannequin m) {
        if (tp.food > 14) return;
        cook(tp);
        for (Map.Entry<Material, Integer> f : FOOD.entrySet()) {
            if (!Items.take(tp, f.getKey(), 1)) continue;
            tp.food = Math.min(20, tp.food + f.getValue());
            org.bukkit.inventory.EntityEquipment eq = m.getEquipment();
            ItemStack before = eq.getItemInMainHand();
            eq.setItemInMainHand(new ItemStack(f.getKey()));
            for (int i = 0; i < 5; i++) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (m.isValid()) m.getWorld().playSound(m.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.6f, 0.9f + (float) rnd().nextDouble(0.2));
                }, i * 4L);
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!m.isValid()) return;
                m.getWorld().playSound(m.getLocation(), Sound.ENTITY_PLAYER_BURP, 0.5f, 1f);
                if (eq.getItemInMainHand().getType() == f.getKey()) eq.setItemInMainHand(before);
            }, 22);
            return;
        }
    }

    /** Cook raw food: a furnace (8 cobblestone) the first time, one coal or log for every 8. */
    private static void cook(TestPlayer tp) {
        for (Map.Entry<Material, Material> e : COOKED.entrySet()) {
            int raw = Items.count(tp, e.getKey());
            if (raw == 0) continue;
            if (Items.count(tp, Material.FURNACE) == 0) {
                if (Items.stone(tp) < 8) return;
                if (!Items.take(tp, Material.COBBLESTONE, 8) && !Items.take(tp, Material.COBBLED_DEEPSLATE, 8)) return;
                Items.add(tp, Material.FURNACE, 1);
            }
            int n = Math.min(raw, 8);
            boolean fuel = Items.take(tp, Material.COAL, 1) || Items.take(tp, Material.CHARCOAL, 1);
            if (!fuel) {
                for (Material log : new ArrayList<>(tp.items.keySet())) {
                    if (Items.isLog(log) && Items.take(tp, log, 1)) {
                        fuel = true;
                        break;
                    }
                }
            }
            if (!fuel) return;
            Items.take(tp, e.getKey(), n);
            Items.add(tp, e.getValue(), n);
        }
    }

    /** Get food: the team's farm, else hunting. */
    private boolean food(TestPlayer tp, Town t, Mannequin m, long now) {
        if (t.builtOf("farm") > 0 && rnd().nextBoolean()) {
            plugin.towns().farm(tp, t, m, now);
            return true;
        }
        return hunt(tp, m, 56, now);
    }

    /** Go after the nearest animal (not someone's pet or penned-in livestock). */
    boolean hunt(TestPlayer tp, Mannequin m, int radius, long now) {
        if (!cfg("hunting", true)) return false;
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : m.getNearbyEntities(radius, 24, radius)) {
            if (!GAME.contains(e.getType()) || !(e instanceof LivingEntity le) || le.isDead()) continue;
            if (e.customName() != null || e instanceof Tameable tm && tm.isTamed() || le.isLeashed()) continue;
            if (e instanceof Ageable a && !a.isAdult()) continue;
            double d = e.getLocation().distanceSquared(m.getLocation());
            if (d >= bestD || cantCatch.getOrDefault(e.getUniqueId(), 0L) > now || nearBuilt(e.getLocation().getBlock(), 3)) continue; // a pen: someone's farm
            best = le;
            bestD = d;
        }
        if (best == null) return false;
        startFight(tp, best, now, "hunting a " + pretty(best.getType().name()));
        return true;
    }

    private void startFight(TestPlayer tp, LivingEntity target, long now, String what) {
        players().goTo(tp, target.getLocation());
        tp.prey = target.getUniqueId();
        tp.preyUntil = now + 45_000;
        tp.buildStatus = what;
        tp.lastResult = "started " + what; // (a "stuck" from before would end the chase at once)
    }

    /** Chase it down and hit it, about once a second (with its best sword or axe). */
    void fight(TestPlayer tp, Mannequin m, long now) {
        Entity e = Bukkit.getEntity(tp.prey);
        boolean stuck = tp.mode == TestPlayer.Mode.IDLE && tp.lastResult.startsWith("stuck");
        boolean gone = e instanceof Player p && (p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR || !p.isOnline());
        if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid() || e.getWorld() != m.getWorld() || now > tp.preyUntil || stuck || gone) {
            // Couldn't get to it (up a cliff, across water): leave that one alone for a while.
            if (e != null && e.isValid() && !(e instanceof LivingEntity l2 && l2.isDead())) {
                cantCatch.put(e.getUniqueId(), now + 300_000);
                if (cantCatch.size() > 300) cantCatch.values().removeIf(t -> t < now);
                if (tp.buildStatus.startsWith("hunting")) tp.lastResult = "couldn't catch the " + pretty(e.getType().name());
            }
            tp.prey = null;
            if (tp.mode == TestPlayer.Mode.GOTO) players().idle(tp);
            return;
        }
        double d = m.getLocation().distance(le.getLocation());
        if (d > 3.0) {
            if (tp.mode != TestPlayer.Mode.GOTO || tp.target == null || tp.target.distanceSquared(le.getLocation()) > 2) players().goTo(tp, le.getLocation());
            return;
        }
        if (now - tp.hitAt < 600) return;
        tp.hitAt = now;
        if (tp.mode != TestPlayer.Mode.IDLE) players().idle(tp);
        Location at = le.getLocation();
        m.lookAt(at.getX(), at.getY() + le.getHeight() * 0.6, at.getZ(), io.papermc.paper.entity.LookAnchor.EYES);
        Material weapon = Items.toolItem(tp, "SWORD");
        if (weapon == null) weapon = Items.toolItem(tp, "AXE");
        m.getEquipment().setItemInMainHand(weapon == null ? null : new ItemStack(weapon));
        m.swingMainHand();
        lastHitBy.put(le.getUniqueId(), tp.id);
        le.damage(damage(tp), m);
    }

    private static double damage(TestPlayer tp) {
        int s = Items.tool(tp, "SWORD");
        if (s >= 0) return 4 + s;
        int a = Items.tool(tp, "AXE");
        return a >= 0 ? 3 + a : 1;
    }

    /** What it killed drops into its inventory (it's standing right there), like a player picking it up. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onKill(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        if (dead instanceof Mannequin || dead instanceof Player) return;
        String id = lastHitBy.remove(dead.getUniqueId());
        TestPlayer tp = e.getDamageSource().getCausingEntity() instanceof Mannequin m ? plugin.players().of(m) : null;
        if (tp == null && id != null) tp = plugin.players().get(id);
        if (tp == null) return;
        List<String> got = new ArrayList<>();
        for (ItemStack d : e.getDrops()) {
            Items.add(tp, d.getType(), d.getAmount());
            got.add(d.getAmount() + " " + pretty(d.getType().name()));
        }
        e.getDrops().clear();
        e.setDroppedExp(0);
        tp.prey = null;
        tp.lastResult = (dead instanceof Monster ? "fought off a " : "hunted a ") + pretty(dead.getType().name()) + (got.isEmpty() ? "" : ": " + String.join(", ", got));
        if (lastHitBy.size() > 200) lastHitBy.clear();
        plugin.players().markDirty();
    }

    private static String pretty(String name) {
        return name.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    // ------------------------------------------------------------ combat: getting hurt, fighting back, healing

    private boolean combat(String key, boolean def) {
        return plugin.getConfig().getBoolean("combat." + key, def);
    }

    /** Something hurt it (combat is on): hit back, or run if a player is winning. */
    void attacked(TestPlayer tp, Mannequin m, Entity by, double damage) {
        long now = System.currentTimeMillis();
        tp.hurtAt = now;
        LivingEntity attacker = by instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof LivingEntity s ? s : by instanceof LivingEntity le ? le : null;
        if (attacker == null || attacker == m) return;
        boolean player = attacker instanceof Player;
        String name = player ? ((Player) attacker).getName() : pretty(attacker.getType().name());
        if (attacker instanceof Mannequin) return; // test players don't fight each other
        tp.lastResult = "got hit by " + name;
        if (player && tp.ai && tp.model != null && now - tp.toldHurtAt > 20_000) {
            tp.toldHurtAt = now;
            plugin.brain().heard(tp, name, "(" + name + " just attacked you! You have " + Math.round(m.getHealth() - damage) + " of 20 health.)");
        }
        if (!combat("fight-back", true) || player && !combat("fight-back-players", true)) return;
        if (player && m.getHealth() - damage < 7) {
            // Losing: run for it, like a player would.
            Vector away = m.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
            away.normalize().multiply(28);
            tp.prey = null;
            players().goTo(tp, m.getLocation().clone().add(away));
            tp.buildStatus = "running away from " + name;
            tp.jobUntil = now + 15_000;
            return;
        }
        if (tp.prey == null || !tp.prey.equals(attacker.getUniqueId())) startFight(tp, attacker, now, (player ? "fighting " : "fighting off a ") + name);
    }

    /** Every second, with combat on: heal slowly when not hurt for a bit (and fed), and get noticed by monsters. */
    private void combatTick(long now) {
        boolean mobs = combat("mobs-attack", true);
        boolean aggro = mobs && ++combatTicks % 2 == 0;
        for (TestPlayer tp : plugin.players().all()) {
            Mannequin m = tp.entity();
            if (m == null) continue;
            double max = m.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null ? m.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue() : 20;
            if (m.getHealth() < max && now - tp.hurtAt > 4_000 && now - tp.healAt > 3_000 && (!tp.free || tp.food > 6)) {
                tp.healAt = now;
                m.setHealth(Math.min(max, m.getHealth() + 1));
            }
            if (!aggro || m.getWorld().getDifficulty() == org.bukkit.Difficulty.PEACEFUL) continue;
            // Monsters go for it like they go for a player (they don't by themselves: it isn't one).
            for (Entity e : m.getNearbyEntities(16, 8, 16)) {
                if (e instanceof Monster mob && mob.getTarget() == null && !mob.isDead() && mob.hasLineOfSight(m)) mob.setTarget(m);
            }
        }
    }

    /** Died: drop what it carried (unless keepInventory), say how in chat, and start over hungry-free. */
    void died(TestPlayer tp, Mannequin m) {
        Location at = m.getLocation();
        String how = deathMessage(m);
        boolean keep = Boolean.TRUE.equals(at.getWorld().getGameRuleValue(org.bukkit.GameRule.KEEP_INVENTORY));
        if (combat("drop-items", true) && !keep && !tp.items.isEmpty()) {
            int stacks = 0;
            for (Map.Entry<Material, Integer> e : new ArrayList<>(tp.items.entrySet())) {
                Material mat = e.getKey();
                if (!mat.isItem()) continue;
                for (int left = e.getValue(); left > 0 && stacks < 120; stacks++) {
                    int k = Math.min(left, mat.getMaxStackSize());
                    Item it = at.getWorld().dropItemNaturally(at, new ItemStack(mat, k));
                    it.setPickupDelay(20);
                    left -= k;
                }
            }
            tp.items.clear();
        }
        tp.prey = null;
        tp.pendingGive = null;
        tp.food = 20;
        tp.lastResult = "died: " + how;
        if (combat("death-messages", true)) {
            Bukkit.broadcast(Component.text(plugin.settings().chatPrefix(), NamedTextColor.GRAY).append(Component.text(tp.name + " " + how, NamedTextColor.WHITE)));
        }
        plugin.link().death(tp, at, how);
        if (tp.ai && tp.model != null) plugin.brain().heard(tp, "(game)", "(You died: you " + how + ". You lost what you carried" + (keep ? " - no, keepInventory is on, you kept it" : "") + " and are back at your " + (tp.town != null ? "base" : "spawn") + ".)");
    }

    /** "was slain by Zombie", "fell from a high place"... like the game's own death messages. */
    private String deathMessage(Mannequin m) {
        org.bukkit.event.entity.EntityDamageEvent last = m.getLastDamageCause();
        if (last instanceof org.bukkit.event.entity.EntityDamageByEntityEvent byEntity) {
            Entity d = byEntity.getDamager();
            Entity src = d instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Entity s ? s : d;
            String who = src instanceof Player p ? p.getName() : src.customName() != null ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(src.customName()) : pretty(src.getType().name());
            if (d instanceof org.bukkit.entity.Projectile) return "was shot by " + who;
            if (last.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) return "was blown up by " + who;
            return "was slain by " + who;
        }
        if (last == null) return "died";
        return switch (last.getCause()) {
            case FALL -> "fell from a high place";
            case LAVA -> "tried to swim in lava";
            case FIRE, FIRE_TICK -> "burned to death";
            case DROWNING -> "drowned";
            case SUFFOCATION -> "suffocated in a wall";
            case VOID -> "fell out of the world";
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> "blew up";
            case STARVATION -> "starved to death";
            case FREEZE -> "froze to death";
            case CONTACT -> "was pricked to death";
            case MAGIC, POISON -> "was killed by magic";
            default -> "died";
        };
    }

    // ------------------------------------------------------------ playing with real teammates

    /** A real player on its team who's online, if any (the nearest in the same world first). */
    private Player mateOnline(Town t) {
        Player any = null;
        for (String h : t.humans) {
            Player p = Bukkit.getPlayerExact(h);
            if (p == null || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            any = p;
        }
        return any;
    }

    /**
     * Stick with a real teammate: keep up, fight off what's after them, follow them through portals, and when
     * they stop for a bit (building, mining), chop or mine nearby. True if it's busy with them.
     */
    private boolean companion(TestPlayer tp, Town t, Mannequin m, Player h, long now) {
        Location hl = h.getLocation();
        Location l = m.getLocation();
        String key = h.getName().toLowerCase(Locale.ROOT);
        Location seen = humanSeen.get(key);
        if (seen == null || seen.getWorld() != hl.getWorld() || seen.distanceSquared(hl) > 9) {
            humanSeen.put(key, hl.clone());
            humanStill.put(key, now);
        }
        long still = now - humanStill.getOrDefault(key, now);
        boolean teleport = cfg("companions-teleport", true);
        if (hl.getWorld() != l.getWorld()) {
            // They went through a portal: after them. Teleported (/home, /tpa): catch up like with a /tpa.
            Location portal = humanLeftBy.get(key);
            if (portal != null && portal.getWorld() == l.getWorld() && Town.hdist(portal, l) < 160) {
                plugin.towns().intoPortal(tp, m, portal);
                tp.buildStatus = "following " + h.getName() + " through the portal";
                return true;
            }
            return teleport && catchUp(tp, m, h, now);
        }
        double d = Town.hdist(hl, l);
        if (d > 80) return teleport && catchUp(tp, m, h, now) || walkTo(tp, h);
        farSince.remove(tp.id);
        // Something's going for them: get it.
        if (cfg("fight-monsters", true)) {
            for (Entity e : h.getNearbyEntities(10, 5, 10)) {
                if (e instanceof Monster mob && mob instanceof Mob mb && mb.getTarget() == h && !mob.isDead()) {
                    startFight(tp, mob, now, "fighting a " + pretty(mob.getType().name()) + " off " + h.getName());
                    return true;
                }
            }
        }
        if (busy(tp)) {
            if (d <= 24) return true; // carry on, it's close by
            players().idle(tp); // they're leaving: drop it and keep up
        }
        if (d > 7) return walkTo(tp, h);
        // Close by, and they've stopped for a while: make itself useful nearby.
        if (still > 12_000 && now >= tp.jobUntil) {
            tp.jobUntil = now + 25_000;
            Gather.Work w = null;
            if (Items.logs(tp) < 48) w = plugin.gather().tree(hl, 16, l, true);
            if (w == null && Items.tool(tp, "PICKAXE") >= 0 && Items.stone(tp) < 64) w = surface(hl, 10);
            if (w != null) {
                plugin.gather().start(tp, w);
                tp.buildStatus = w.label + " near " + h.getName();
                return true;
            }
            if (foodCount(tp) < 4 && hunt(tp, m, 16, now)) return true;
        }
        if (tp.mode != TestPlayer.Mode.FOLLOW || !h.getName().equals(tp.follow)) players().follow(tp, h.getName());
        tp.buildStatus = "with " + h.getName();
        return true;
    }

    private boolean walkTo(TestPlayer tp, Player h) {
        if (tp.mode != TestPlayer.Mode.FOLLOW || !h.getName().equals(tp.follow)) players().follow(tp, h.getName());
        tp.buildStatus = "catching up with " + h.getName();
        return true;
    }

    /** Out of reach for 20 seconds (they teleported, or they're far off): it teleports to them, like a /tpa. */
    private boolean catchUp(TestPlayer tp, Mannequin m, Player h, long now) {
        long since = farSince.computeIfAbsent(tp.id, k -> now);
        if (now - since < 20_000) return true;
        farSince.remove(tp.id);
        Location to = h.getLocation().clone().add(rnd().nextDouble(-3, 3), 0, rnd().nextDouble(-3, 3));
        to = to.getWorld().getHighestBlockAt(to).getLocation().add(0.5, 1, 0.5);
        if (Math.abs(to.getY() - h.getLocation().getY()) > 6) to = h.getLocation(); // in a cave or the nether: right next to them
        players().idle(tp);
        tp.trip = null;
        m.teleport(to);
        tp.lastResult = "teleported to " + h.getName();
        return true;
    }

    /** Where real teammates went through portals, so test players can follow them. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (teamOf(e.getPlayer().getName()) != null) humanLeftBy.put(e.getPlayer().getName().toLowerCase(Locale.ROOT), e.getFrom().clone());
    }

    /** Hand a teammate something (walking over first). Returns what happened. */
    String startGive(TestPlayer tp, String player, String item, int count) {
        Player p = Bukkit.getPlayerExact(player);
        if (p == null) return "give failed: " + player + " isn't online";
        if (!teammates(p, tp)) return "give failed: you only hand things to your teammates (" + player + " can right-click you to trade)";
        Material mat = carried(tp, item);
        if (mat == null) return "give failed: you don't have any " + item;
        int n = Math.max(1, Math.min(count <= 0 ? 16 : count, Items.count(tp, mat)));
        tp.pendingGive = new String[] {p.getName(), mat.name(), String.valueOf(n)};
        tp.giveUntil = System.currentTimeMillis() + 60_000;
        return "bringing " + n + " " + pretty(mat.name()) + " to " + p.getName();
    }

    /** The thing it carries that best matches what someone asked for ("logs", "food", "iron", "cobblestone"...). */
    static Material carried(TestPlayer tp, String what) {
        String w = what.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        Material exact = Material.matchMaterial(w);
        if (exact != null && Items.count(tp, exact) > 0) return exact;
        if (w.contains("food") || w.contains("eat")) {
            for (Material f : FOOD.keySet()) if (Items.count(tp, f) > 0) return f;
        }
        String[] aliases = {"wood:_LOG", "log:_LOG", "plank:_PLANKS", "stone:COBBLESTONE", "cobble:COBBLESTONE", "iron:IRON_INGOT", "iron:RAW_IRON",
            "coal:COAL", "torch:TORCH", "meat:COOKED_", "bread:BREAD", "diamond:DIAMOND", "gold:GOLD", "seed:WHEAT_SEEDS", "wheat:WHEAT"};
        for (String a : aliases) {
            String[] kv = a.split(":");
            if (!w.contains(kv[0])) continue;
            for (Map.Entry<Material, Integer> e : tp.items.entrySet()) if (e.getValue() > 0 && e.getKey().name().contains(kv[1])) return e.getKey();
        }
        String singular = w.endsWith("s") ? w.substring(0, w.length() - 1) : w;
        for (Map.Entry<Material, Integer> e : tp.items.entrySet()) if (e.getValue() > 0 && e.getKey().name().toLowerCase(Locale.ROOT).contains(singular)) return e.getKey();
        return null;
    }

    private void give(TestPlayer tp, Mannequin m, long now) {
        String[] g = tp.pendingGive;
        Player p = Bukkit.getPlayerExact(g[0]);
        if (p == null || now > tp.giveUntil || p.getWorld() != m.getWorld()) {
            tp.pendingGive = null;
            return;
        }
        if (m.getLocation().distance(p.getLocation()) > 3.5) {
            if (tp.mode != TestPlayer.Mode.FOLLOW || !p.getName().equals(tp.follow)) players().follow(tp, p.getName());
            tp.buildStatus = "bringing " + p.getName() + " something";
            return;
        }
        tp.pendingGive = null;
        Material mat = Material.matchMaterial(g[1]);
        int n = mat == null ? 0 : Math.min(Integer.parseInt(g[2]), Items.count(tp, mat));
        if (n <= 0 || !Items.take(tp, mat, n)) return;
        Location eye = m.getEyeLocation();
        Vector toward = p.getLocation().toVector().subtract(eye.toVector()).normalize().multiply(0.3).setY(0.2);
        m.lookAt(p.getEyeLocation().getX(), p.getEyeLocation().getY(), p.getEyeLocation().getZ(), io.papermc.paper.entity.LookAnchor.EYES);
        m.swingMainHand();
        for (int left = n; left > 0; ) {
            int k = Math.min(left, mat.getMaxStackSize());
            Item it = m.getWorld().dropItem(eye, new ItemStack(mat, k));
            it.setVelocity(toward);
            it.setPickupDelay(10);
            it.setOwner(p.getUniqueId()); // only they can pick it up
            left -= k;
        }
        tp.lastResult = "gave " + n + " " + pretty(mat.name()) + " to " + p.getName();
        players().markDirty();
    }

    /** Teammates can look in its bag (right-click) and take or leave things, like a shared chest. */
    void openBag(Player p, TestPlayer tp) {
        Bag bag = new Bag(tp);
        Inventory inv = Bukkit.createInventory(bag, 54, Component.text(tp.name + "'s stuff"));
        bag.inv = inv;
        List<Map.Entry<Material, Integer>> stacks = new ArrayList<>(tp.items.entrySet());
        stacks.sort((a, b) -> b.getValue() * Math.max(1, Items.worth(b.getKey())) - a.getValue() * Math.max(1, Items.worth(a.getKey())));
        int slot = 0;
        for (Map.Entry<Material, Integer> e : stacks) {
            int left = e.getValue();
            Material mat = e.getKey();
            if (!mat.isItem()) continue;
            while (left > 0 && slot < 54) {
                int k = Math.min(left, mat.getMaxStackSize());
                inv.setItem(slot++, new ItemStack(mat, k));
                left -= k;
                Items.take(tp, mat, k); // it's in the bag now (so it can't be used twice while it's open)
            }
        }
        Mannequin m = tp.entity();
        if (m != null) m.lookAt(p.getEyeLocation().getX(), p.getEyeLocation().getY(), p.getEyeLocation().getZ(), io.papermc.paper.entity.LookAnchor.EYES);
        p.openInventory(inv);
    }

    private static final class Bag implements InventoryHolder {
        final TestPlayer tp;
        Inventory inv;

        Bag(TestPlayer tp) {
            this.tp = tp;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    @EventHandler
    public void onBagClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Bag bag)) return;
        TestPlayer tp = bag.tp;
        Player p = (Player) e.getPlayer();
        for (ItemStack s : e.getInventory().getContents()) {
            if (s == null || s.getType().isAir()) continue;
            if (s.hasItemMeta()) {
                // Named, enchanted or worn things stay the player's: its bag only holds plain items.
                for (ItemStack rest : p.getInventory().addItem(s.clone()).values()) p.getWorld().dropItem(p.getLocation(), rest);
                continue;
            }
            Items.add(tp, s.getType(), s.getAmount());
        }
        e.getInventory().clear();
        tp.lastResult = p.getName() + " went through your bag";
        plugin.players().markDirty();
    }

    /** On shutdown: close open bags so nothing in them is lost. */
    void closeBags() {
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getOpenInventory().getTopInventory().getHolder() instanceof Bag) p.closeInventory();
    }

    // ------------------------------------------------------------ trading at spawn

    private int spare(TestPlayer tp) {
        int worth = 0;
        for (Map.Entry<Material, Integer> e : tp.items.entrySet()) {
            if (e.getKey().name().endsWith("_PICKAXE") || e.getKey().name().endsWith("_AXE") || e.getKey().name().endsWith("_SWORD")) continue;
            worth += e.getValue() * Items.worth(e.getKey());
        }
        return worth / Math.max(1, Items.worth(Material.EMERALD));
    }

    /** Head to spawn, where the players are, to trade for a few minutes. */
    private boolean startMarket(TestPlayer tp, Mannequin m, long now) {
        if (!plugin.getConfig().getBoolean("survival.trading", true) || spare(tp) < 2) return false;
        Location spawn = m.getWorld().getSpawnLocation();
        if (m.getWorld().getEnvironment() != World.Environment.NORMAL || Town.hdist(spawn, m.getLocation()) > 900) return false;
        tp.trip = "market";
        tp.tripUntil = now + 360_000 + rnd().nextInt(240_000);
        double a = rnd().nextDouble(Math.PI * 2);
        players().goTo(tp, spawn.clone().add(Math.cos(a) * rnd().nextDouble(5, 14), 0, Math.sin(a) * rnd().nextDouble(5, 14)));
        tp.buildStatus = "heading to spawn to trade";
        return true;
    }

    private void market(TestPlayer tp, Mannequin m, long now) {
        Location spawn = m.getWorld().getSpawnLocation();
        if (now > tp.tripUntil || m.getWorld().getEnvironment() != World.Environment.NORMAL) {
            tp.trip = null;
            tp.jobUntil = 0;
            players().idle(tp);
            return;
        }
        if (Town.hdist(spawn, m.getLocation()) > 18) {
            if (tp.mode != TestPlayer.Mode.GOTO) players().goTo(tp, spawn);
            return;
        }
        if (tp.mode == TestPlayer.Mode.IDLE && rnd().nextDouble() < 0.1) players().wander(tp, 8);
        tp.buildStatus = "trading at spawn";
        if (!tp.ai && now - tp.lastSaidAt > 240_000 && !m.getLocation().getNearbyPlayers(16).isEmpty()) {
            players().say(tp, "anyone wanna trade? got " + Items.describe(tp, 3) + ", right-click me");
        }
    }

    // ------------------------------------------------------------ what its AI picked

    /** A free player's AI chose an action. Null if it's one Brain handles itself (goto, follow, wander...). */
    String order(TestPlayer tp, String action, String direction, String player, String item, int count) {
        Mannequin m = tp.entity();
        Town t = plugin.towns().get(tp.town);
        if (m == null || t == null) return null;
        long now = System.currentTimeMillis();
        Towns towns = plugin.towns();
        return switch (action) {
            case "chop" -> {
                players().idle(tp);
                chop(tp, m, now, t);
                yield "going to chop a tree";
            }
            case "mine" -> {
                players().idle(tp);
                if (t.settled && t.world.equals(m.getWorld().getName())) {
                    towns.miner(tp, t, m, now);
                    yield "heading into the mine at the base";
                }
                Gather.Work w = surface(m.getLocation(), 16);
                if (w == null) yield "mine failed: no stone you can get at near here (and no base with a mine yet)";
                plugin.gather().start(tp, w);
                yield "mining stone nearby";
            }
            case "hunt" -> {
                players().idle(tp);
                yield hunt(tp, m, 48, now) ? "going hunting" : "hunt failed: no animals nearby";
            }
            case "explore", "nether" -> {
                Player mate = tp.buddy ? mateOnline(t) : null;
                if (mate != null) yield action + " failed: you're sticking with " + mate.getName() + " (ask them, or follow them there)";
                if (t.settled && tp.job.equals("settler") && t.count("built") < 2 && t.count("planned") > 0)
                    yield action + " failed: get the base going first (\"work\" builds the next part)";
                // No base yet: the team's leader exploring is how it finds a spot for one.
                if (action.equals("explore") && !t.settled && leader(t) == tp && cfg("build-bases", true) && Items.tool(tp, "PICKAXE") >= 0) {
                    players().idle(tp);
                    tp.seekBase = null;
                    seek(tp, t, m, now);
                    yield "off to find a spot for the base";
                }
                tp.trip = null;
                yield towns.order(tp, action, direction);
            }
            case "trade" -> {
                players().idle(tp);
                yield startMarket(tp, m, now) ? "heading to spawn to trade" : "trade failed: nothing spare to trade, or spawn is too far";
            }
            case "home" -> {
                if (!t.settled) yield "home failed: your team has no base yet";
                players().goTo(tp, t.center());
                tp.jobUntil = now + 90_000;
                yield "heading back to base";
            }
            case "settle" -> {
                if (t.settled) yield "settle failed: your team already has a base at " + t.cx + " " + t.cz;
                if (!cfg("build-bases", true)) yield "settle failed: bases are turned off on this server";
                String why = siteProblem(t, m.getLocation(), Math.max(0, cfgInt("base-min-distance-from-spawn", 150)));
                if (why != null && !why.equals("too steep")) yield "settle failed: " + why;
                tp.seekBase = m.getLocation();
                tp.seekTries = 6;
                seek(tp, t, m, now);
                yield tp.lastResult;
            }
            case "give" -> startGive(tp, player, item, count);
            case "campfire" -> t.settled ? towns.order(tp, "campfire", "") : "campfire failed: no base yet";
            case "work" -> {
                tp.jobUntil = 0;
                yield "getting on with things";
            }
            default -> null;
        };
    }

    /**
     * Someone said its name in chat, and it has no AI model to answer: it still understands the basics.
     * "Ash team up?", "Ash come with me", "Ash do your own thing", "Ash give me some logs".
     */
    void heardChat(TestPlayer tp, Player p, String lower) {
        Mannequin m = tp.entity();
        if (m == null) return;
        boolean quiet = tp.ai && tp.model != null; // its AI answers in its own words; this only does what was asked
        boolean mate = teammates(p, tp);
        if (!mate && lower.matches(".*\\b(team ?up|join (me|my team|us)|wanna team|be my teammate)\\b.*")) {
            if (!cfg("players-can-team-up", true) || !tp.free || quiet) return; // with an AI, it decides (team_up isn't automatic)
            if (m.getWorld() != p.getWorld() || m.getLocation().distance(p.getLocation()) > 24) {
                if (!quiet) players().say(tp, "come find me first " + p.getName() + ", I'm at " + m.getLocation().getBlockX() + " " + m.getLocation().getBlockZ());
                return;
            }
            try {
                String r = teamUp(p, tp);
                p.sendMessage(Component.text(r, NamedTextColor.GREEN));
                if (!quiet) players().say(tp, "sure " + p.getName() + ", let's team up!");
            } catch (IllegalStateException e) {
                if (!quiet) players().say(tp, "can't right now, sorry " + p.getName());
            }
            return;
        }
        if (!mate) return;
        java.util.regex.Matcher give = java.util.regex.Pattern.compile("\\bgive me (?:(\\d+) )?(?:some |a |an |your )?([a-z_ ]{2,24})").matcher(lower);
        if (give.find()) {
            int n = give.group(1) != null ? Integer.parseInt(give.group(1)) : 16;
            String r = startGive(tp, p.getName(), give.group(2).trim(), n);
            if (!quiet) players().say(tp, r.startsWith("give failed") ? "don't have any " + give.group(2).trim() + ", sorry" : "here you go");
            tp.lastResult = r;
        } else if (lower.matches(".*\\b(come|follow me|with me|over here|let'?s go)\\b.*")) {
            tp.buddy = true;
            players().idle(tp);
            players().follow(tp, p.getName());
            if (!quiet) players().say(tp, "coming!");
        } else if (lower.matches(".*\\b(stay|do your (own )?thing|go (mine|chop|explore)|you can go)\\b.*")) {
            tp.buddy = false;
            tp.jobUntil = 0;
            players().idle(tp);
            if (!quiet) players().say(tp, "ok, I'll get on with stuff");
        }
        plugin.towns().markDirty();
    }

    /** For the AI's view of the world: its team, base and teammates. */
    String describe(TestPlayer tp, Mannequin m) {
        Town t = plugin.towns().get(tp.town);
        if (t == null) return "You're on your own, with no base.";
        StringBuilder sb = new StringBuilder();
        List<String> mates = new ArrayList<>();
        for (Map.Entry<String, String> e : t.jobs.entrySet()) {
            TestPlayer o = plugin.players().get(e.getKey());
            if (o == null || o == tp) continue;
            Mannequin om = o.entity();
            String dist = om == null ? "offline" : om.getWorld() != m.getWorld() ? "in " + om.getWorld().getName() : Math.round(om.getLocation().distance(m.getLocation())) + "m away";
            mates.add(o.name + " (test player, " + e.getValue() + ", " + dist + ", " + o.doing() + ")");
        }
        for (String h : t.humans) {
            Player p = Bukkit.getPlayerExact(h);
            String dist = p == null ? "offline" : p.getWorld() != m.getWorld() ? "in " + p.getWorld().getName() : Math.round(p.getLocation().distance(m.getLocation())) + "m away";
            mates.add(h + " (REAL player, your teammate, " + dist + ")");
        }
        sb.append(mates.isEmpty() ? "You play solo (team " + t.name + "). " : "Your team " + t.name + ": you (" + tp.job + ") and " + String.join(", ", mates) + ". ");
        if (t.settled && t.count("planned") + t.count("building") > 0) sb.append("Your base still needs building (\"work\" carries on with it). ");
        if (t.settled) sb.append("Your base is at x=").append(t.cx).append(" z=").append(t.cz).append(" (").append(t.count("built")).append(" of ").append(t.plots.size())
                .append(" things built").append(t.storage != null ? ", storage chest by the campfire" : "").append("). ");
        else sb.append(cfg("build-bases", true) ? "No base yet: once you have tools and some logs, explore to find a spot away from spawn (\"settle\" when you're somewhere good). " : "No base: you roam. ");
        sb.append("Food: ").append(tp.food).append("/20").append(foodCount(tp) == 0 ? " and nothing to eat" : "").append(". ");
        if (tp.buddy && !t.humans.isEmpty()) sb.append("You stick with your real teammates when they're online. ");
        if (cfg("players-can-team-up", true)) sb.append("Real players can team up with you (/teamup ").append(tp.name).append(").");
        return sb.toString();
    }

    // ------------------------------------------------------------ every second: hunger, and keeping the server populated

    void tick() {
        long now = System.currentTimeMillis();
        for (TestPlayer tp : plugin.players().all()) if (tp.free && tp.entity() != null) hunger(tp, now);
        if (!plugin.settings().invulnerable()) combatTick(now);
        if (now - lastPopulate < 5_000) return;
        lastPopulate = now;
        int keep = Math.max(0, cfgInt("keep-online", 0));
        List<TestPlayer> autos = plugin.players().all().stream().filter(tp -> tp.auto).toList();
        if (keep == 0 && autos.isEmpty()) return;
        long online = autos.stream().filter(tp -> tp.wanted).count();
        // Sessions: after a while they log off (somewhere sensible, not mid-trip), and come back later.
        // More online than wanted (keep-online was lowered): some log off.
        boolean sessions = cfg("sessions", true);
        for (TestPlayer tp : autos) {
            if (!tp.wanted) continue;
            if (tp.sessionUntil == 0) tp.sessionUntil = now + session();
            Town t = plugin.towns().get(tp.town);
            boolean withMate = t != null && tp.buddy && mateOnline(t) != null;
            boolean done = sessions && now > tp.sessionUntil && tp.trip == null && !busy(tp) && !withMate;
            if (done || online > keep) {
                logOff(tp, now);
                online--;
            }
        }
        int room = plugin.settings().maxPlayers() - plugin.players().all().size();
        for (int i = 0; i < 3 && online < keep; i++) { // a few at a time, like players trickling in
            TestPlayer back = autos.stream().filter(tp -> !tp.wanted && tp.backAt <= now).min(Comparator.comparingLong(tp -> tp.backAt)).orElse(null);
            if (back != null) {
                logOn(back, now);
            } else if (autos.size() + i < keep * 2 && room-- > 0) {
                newcomer(Bukkit.getWorlds().getFirst().getSpawnLocation(), now, true);
            } else break;
            online++;
        }
    }

    private long session() {
        int lo = Math.max(5, cfgInt("session-minutes-min", 40));
        int hi = Math.max(lo + 1, cfgInt("session-minutes-max", 150));
        return (lo + rnd().nextInt(hi - lo)) * 60_000L;
    }

    private void logOff(TestPlayer tp, long now) {
        plugin.players().despawn(tp, false);
        tp.sessionUntil = 0;
        int lo = Math.max(1, cfgInt("break-minutes-min", 20));
        int hi = Math.max(lo + 1, cfgInt("break-minutes-max", 120));
        tp.backAt = now + (lo + rnd().nextInt(hi - lo)) * 60_000L;
        plugin.getLogger().info(tp.name + " logged off (back in about " + (tp.backAt - now) / 60_000 + " minutes)");
    }

    private void logOn(TestPlayer tp, long now) {
        Location at = tp.lastKnown != null && tp.lastKnown.isWorldLoaded() ? tp.lastKnown : Bukkit.getWorlds().getFirst().getSpawnLocation();
        tp.backAt = 0;
        tp.sessionUntil = now + session();
        plugin.players().spawn(tp, at);
    }

    /**
     * A new player joins: a made-up player name, a random personality, its AI on if one is linked, and on its own
     * or (sometimes) with a couple of others who join around the same time.
     */
    TestPlayer newcomer(Location near, long now, boolean auto) {
        String name = playerName();
        TestPlayer tp = plugin.players().create(name);
        tp.auto = auto;
        tp.personality = Personalities.resolve("random");
        tp.sessionUntil = now + session();
        aiByDefault(tp);
        Location at = near.clone().add(rnd().nextDouble(-16, 16), 0, rnd().nextDouble(-16, 16));
        at.setYaw((float) rnd().nextDouble(360));
        tp.home = at;
        Town team = null;
        if (forming != null && plugin.towns().get(forming.name) == forming && forming.jobs.size() < formingSize && now - formingAt < 180_000) team = forming;
        else if (rnd().nextDouble() < plugin.getConfig().getDouble("free.team-chance", 0.4)) {
            forming = plugin.towns().foundCamp(teamName(), at);
            formingSize = 2 + rnd().nextInt(3);
            formingAt = now;
            team = forming;
        }
        makeFree(tp, team);
        plugin.players().spawn(tp, at);
        return tp;
    }

    /** AI on for a new free player when one is linked (free.ai-by-default). The API budget still decides who gets it. */
    void aiByDefault(TestPlayer tp) {
        if (!cfg("ai-by-default", true) || !plugin.llm().ready()) return;
        if (tp.model == null) tp.model = "default";
        tp.ai = true;
    }

    /** A made-up name that looks like a player's (Ash427, xMossx, pebble_digs...), not taken here. */
    String randomName() {
        return playerName();
    }

    private String playerName() {
        for (int tries = 0; tries < 200; tries++) {
            String a = NAME_A[rnd().nextInt(NAME_A.length)];
            String n = switch (rnd().nextInt(6)) {
                case 0 -> a + rnd().nextInt(10, 1000);
                case 1 -> a + NAME_B[rnd().nextInt(NAME_B.length)];
                case 2 -> a.toLowerCase(Locale.ROOT) + "_" + NAME_B[rnd().nextInt(NAME_B.length)].toLowerCase(Locale.ROOT);
                case 3 -> "x" + a + "x";
                case 4 -> a + "_" + NAME_A[rnd().nextInt(NAME_A.length)];
                default -> NAME_B[rnd().nextInt(NAME_B.length)] + a + rnd().nextInt(1, 100);
            };
            if (n.length() > 16 || plugin.players().get(n) != null || Bukkit.getOfflinePlayerIfCached(n) != null) continue;
            return n;
        }
        return "Player" + rnd().nextInt(10_000, 100_000);
    }

    private String teamName() {
        for (int i = 0; i < 50; i++) {
            String n = TEAM_NAMES[rnd().nextInt(TEAM_NAMES.length)];
            if (plugin.towns().get(n) == null) return n;
        }
        String n = "Team";
        for (int i = 2; plugin.towns().get(n) != null; i++) n = "Team" + i;
        return n;
    }

    // ------------------------------------------------------------ /teamup: for real players

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can team up. Admins: /testplayer team ...");
            return true;
        }
        if (!cfg("players-can-team-up", true)) {
            p.sendMessage(Component.text("Teaming up with test players is turned off on this server.", NamedTextColor.RED));
            return true;
        }
        String op = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        Town mine = teamOf(p.getName());
        try {
            switch (op) {
                case "leave" -> {
                    if (mine == null) throw new IllegalStateException("You're not on a team.");
                    leaveHuman(p.getName());
                    p.sendMessage(Component.text("You left " + mine.name + ".", NamedTextColor.GREEN));
                }
                case "info" -> {
                    if (mine == null) throw new IllegalStateException("You're not on a team. Stand near a test player and /teamup <name>.");
                    p.sendMessage(Component.text(mine.summary(), NamedTextColor.GOLD));
                    p.sendMessage(Component.text("Members: " + memberNames(mine), NamedTextColor.GRAY));
                }
                case "come", "free" -> {
                    if (mine == null) throw new IllegalStateException("You're not on a team.");
                    boolean come = op.equals("come");
                    int n = 0;
                    for (String id : mine.jobs.keySet()) {
                        TestPlayer o = plugin.players().get(id);
                        if (o == null) continue;
                        if (come && n < 3) {
                            o.buddy = true;
                            n++;
                        } else if (!come) o.buddy = false;
                    }
                    plugin.towns().markDirty();
                    p.sendMessage(Component.text(come ? n + " teammates are coming with you." : "Your teammates are back to doing their own thing.", NamedTextColor.GREEN));
                }
                case "help" -> {
                    p.sendMessage(Component.text("/teamup <test player> - team up with a test player near you", NamedTextColor.GOLD));
                    p.sendMessage(Component.text("/teamup info | come | free | leave", NamedTextColor.GRAY));
                }
                default -> {
                    TestPlayer tp = plugin.players().get(a[0]);
                    Mannequin m = tp == null ? null : tp.entity();
                    if (m == null) throw new IllegalStateException("There's no test player called " + a[0] + " around.");
                    if (!p.hasPermission("moddedrealms.testplayers.admin") && (m.getWorld() != p.getWorld() || m.getLocation().distance(p.getLocation()) > 24))
                        throw new IllegalStateException("Go and find " + tp.name + " first (within 24 blocks).");
                    if (!tp.free && tp.town != null) throw new IllegalStateException(tp.name + " lives in the town " + tp.town + " and won't leave it.");
                    String r = teamUp(p, tp);
                    p.sendMessage(Component.text(r, NamedTextColor.GREEN));
                    if (tp.ai) plugin.brain().heard(tp, p.getName(), "(" + p.getName() + " just teamed up with you: they're a real player and your teammate now)");
                    else plugin.players().say(tp, rnd().nextBoolean() ? "sure, let's team up " + p.getName() + "!" : "welcome to the team " + p.getName());
                }
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            p.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] a) {
        if (a.length != 1) return List.of();
        String pre = a[0].toLowerCase(Locale.ROOT);
        Stream<String> names = plugin.players().all().stream().filter(tp -> tp.free && tp.entity() != null).map(tp -> tp.name);
        return Stream.concat(Stream.of("info", "come", "free", "leave"), names).filter(s -> s.toLowerCase(Locale.ROOT).startsWith(pre)).limit(40).toList();
    }
}
