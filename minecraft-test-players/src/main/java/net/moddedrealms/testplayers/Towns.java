package net.moddedrealms.testplayers;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Towns and their citizens. Every citizen has a job (from its personality, unless set): builders work through
 * the town plan, farmers build farms and harvest them, traders keep the market stalls, guards build towers and
 * patrol, road workers pave roads and put up lamps, scouts explore and report back, the mayor builds the civic
 * buildings and announces how the town is doing, and the entertainer keeps people company at the campfire.
 * At night everyone but the guards goes home. None of it needs an AI model; with one, they also talk about it.
 */
final class Towns {

    private static final Map<String, String> JOB_FOR_PRESET = Map.of(
            "builder", "builder", "leader", "mayor", "nervous", "farmer", "friendly", "trader",
            "explorer", "scout", "joker", "entertainer", "grumpy", "guard");

    private static final String[] JOKES = {
        "why did the creeper cross the road? to get to the other ssssside",
        "I'd tell you a joke about gravel, but it'd fall flat",
        "what do you call a sheep that knows karate? lamb chop!",
        "our town has great walls. well, it will. eventually.",
        "I asked the farmer for a joke. he said it's still growing on him",
        "the guard said he'd keep an eye on me. I said that's a bit much, one is plenty"
    };

    private final TestPlayersPlugin plugin;
    private final File file;
    private final Map<String, Town> towns = new LinkedHashMap<>();
    private boolean dirty;

    Towns(TestPlayersPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "towns.yml");
    }

    Town get(String name) {
        return name == null ? null : towns.get(name.toLowerCase(Locale.ROOT));
    }

    /** Towns and teams' camps. */
    List<Town> all() {
        return new ArrayList<>(towns.values());
    }

    /** Planned towns only (not the free players' team camps). */
    List<Town> townsOnly() {
        return towns.values().stream().filter(t -> !t.camp).toList();
    }

    List<Town> camps() {
        return towns.values().stream().filter(t -> t.camp).toList();
    }

    /** A team for free players (see Life): it has no base until it settles somewhere. */
    Town foundCamp(String name, Location at) {
        if (!name.matches("^[A-Za-z0-9_]{2,24}$")) throw new IllegalArgumentException("Team names are 2-24 letters, numbers or _.");
        if (get(name) != null) throw new IllegalArgumentException("There's already a town or team called " + name + ".");
        Town t = new Town(name, at.getWorld().getName(), at.getBlockX(), at.getBlockZ(), 28);
        t.camp = true;
        t.settled = false;
        towns.put(name.toLowerCase(Locale.ROOT), t);
        dirty = true;
        return t;
    }

    /** The team makes its base here: a small plan around (x, z), and a build zone for it. */
    void settle(Town t, Location at) {
        t.world = at.getWorld().getName();
        t.cx = at.getBlockX();
        t.cz = at.getBlockZ();
        t.radius = 28;
        t.settled = true;
        t.storage = null; // its chest goes by the new campfire
        t.planCamp(Math.max(1, t.jobs.size()));
        try {
            plugin.builder().removeZone("camp-" + t.name);
        } catch (IllegalArgumentException ignored) {
            // it had none
        }
        plugin.builder().addZone("camp-" + t.name, at.getWorld(), t.cx - t.radius, t.cz - t.radius, t.cx + t.radius, t.cz + t.radius);
        dirty = true;
        save();
    }

    void markDirty() {
        dirty = true;
    }

    // ------------------------------------------------------------ founding and joining

    Town found(String name, Location at, int radius) {
        if (!name.matches("^[A-Za-z][A-Za-z0-9_]{1,23}$")) throw new IllegalArgumentException("Town names are 2-24 letters, numbers or _, starting with a letter.");
        if (get(name) != null) throw new IllegalArgumentException("There's already a town called " + name + ".");
        if (radius < 30 || radius > 200) throw new IllegalArgumentException("A town's radius is 30 to 200 blocks (45 suits about 30 buildings).");
        Town t = new Town(name, at.getWorld().getName(), at.getBlockX(), at.getBlockZ(), radius);
        t.plan();
        plugin.builder().addZone("town-" + name, at.getWorld(), t.cx - radius, t.cz - radius, t.cx + radius, t.cz + radius);
        towns.put(name.toLowerCase(Locale.ROOT), t);
        dirty = true;
        save();
        return t;
    }

    void remove(Town t) {
        for (String id : new ArrayList<>(t.jobs.keySet())) {
            TestPlayer tp = plugin.players().get(id);
            if (tp != null) leave(tp);
        }
        towns.remove(t.name.toLowerCase(Locale.ROOT));
        try {
            plugin.builder().removeZone((t.camp ? "camp-" : "town-") + t.name);
        } catch (IllegalArgumentException ignored) {
            // the zone was removed by hand already
        }
        dirty = true;
        save();
    }

    /** Make a test player a citizen: its job comes from its personality unless one is given. */
    String join(TestPlayer tp, Town t, String job) {
        if (tp.town != null && !tp.town.equalsIgnoreCase(t.name)) leave(tp);
        String j = job != null ? job : jobFor(tp);
        if (job == null && j.equals("builder")) {
            // A town needs wood and stone more than a third builder: the first spare builders become a lumberjack and a miner.
            long builders = t.jobs.values().stream().filter("builder"::equals).count();
            if (builders >= 2 && !t.jobs.containsValue("lumberjack")) j = "lumberjack";
            else if (builders >= 2 && !t.jobs.containsValue("miner")) j = "miner";
        }
        if (!Town.JOB_BUILDS.containsKey(j)) throw new IllegalArgumentException("Jobs: " + String.join(", ", Town.JOB_BUILDS.keySet()) + ".");
        tp.town = t.name;
        tp.job = j;
        t.jobs.put(tp.id, j);
        tp.jobUntil = 0;
        plugin.players().idle(tp);
        dirty = true;
        return j;
    }

    void leave(TestPlayer tp) {
        Town t = get(tp.town);
        if (t != null) {
            t.jobs.remove(tp.id);
            t.homes.remove(tp.id);
        }
        plugin.builder().cancel(tp);
        tp.town = null;
        tp.job = null;
        dirty = true;
        // A team nobody's left in is gone (what it built stays in the world).
        if (t != null && t.camp && t.jobs.isEmpty() && t.humans.isEmpty()) remove(t);
    }

    /** builder for a builder personality, mayor for a leader... builder when it has none. */
    static String jobFor(TestPlayer tp) {
        String preset = Personalities.label(tp.personality);
        return preset != null && JOB_FOR_PRESET.containsKey(preset) ? JOB_FOR_PRESET.get(preset) : "builder";
    }

    // ------------------------------------------------------------ every second

    void tick() {
        for (TestPlayer tp : plugin.players().all()) {
            if (tp.town == null) continue;
            Town t = get(tp.town);
            Mannequin m = tp.entity();
            if (t == null || m == null) continue;
            try {
                if (t.camp) plugin.life().live(tp, t, m);
                else live(tp, t, m);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, tp.name + "'s town routine failed", e);
                tp.jobUntil = System.currentTimeMillis() + 10_000;
            }
        }
        for (Town t : new ArrayList<>(towns.values())) {
            if (!t.camp) announce(t);
            // Everything planned is built or skipped: every 10 minutes the skipped plots get another go
            // (trees chopped since, leaves decayed, a player flattened the ground...).
            long now = System.currentTimeMillis();
            if (t.count("planned") == 0 && t.count("skipped") > 0 && now - t.revivedAt > 600_000) {
                t.revivedAt = now;
                for (Town.Plot p : t.plots) {
                    if (!p.state.equals("skipped")) continue;
                    p.state = "planned";
                    p.clears = 0;
                }
                dirty = true;
            }
        }
        if (dirty) save();
    }

    private void live(TestPlayer tp, Town t, Mannequin m) {
        long now = System.currentTimeMillis();
        TestPlayers players = plugin.players();
        behave(tp, m, now);
        if (tp.trip != null) {
            trip(tp, t, m, now);
            return;
        }
        if (m.getWorld().getEnvironment() != World.Environment.NORMAL || !m.getWorld().getName().equals(t.world)) {
            tp.trip = "home"; // in another world (walked into a portal...): find the way home
            tp.tripFrom = null;
            tp.tripUntil = now + 300_000;
            plugin.players().idle(tp); // drops whatever it was doing over there
            return;
        }
        if (tp.mode == TestPlayer.Mode.BUILD || tp.mode == TestPlayer.Mode.WORK || tp.pendingBuild != null) return; // busy
        if (tp.job.equals("trader")) greet(tp, t, m, now);
        if (tp.job.equals("entertainer")) entertain(tp, t, m, now);
        if (tp.scoutReport != null && tp.mode == TestPlayer.Mode.IDLE) {
            // At most one report every 3 minutes; the others just go in its log.
            if (now - tp.lastSaidAt > 180_000) players.say(tp, tp.scoutReport);
            tp.scoutReport = null;
        }
        // Busy with the current errand until it's done (arrived) or its time is up.
        if (now < tp.jobUntil && tp.mode != TestPlayer.Mode.IDLE) return;

        World w = m.getWorld();
        Location c = t.center();
        if (c == null) return;
        long time = w.getTime();
        boolean night = time > 12_800 && time < 23_200;

        // Dusk: everyone gathers round the campfire for a bit before bed.
        if (time > 11_600 && time <= 12_800 && !tp.job.equals("guard")) {
            patrol(tp, t, c, 5, "hanging out by the campfire", now);
            return;
        }
        // Night: home to bed (the guards keep watch).
        if (night && !tp.job.equals("guard")) {
            int home = t.homeFor(tp.id);
            Location bed = home >= 0 && t.plots.get(home).built != null ? t.plots.get(home).built : c;
            tp.buildStatus = home >= 0 ? "home for the night" : "sleeping by the campfire";
            if (Town.hdist(bed, m.getLocation()) > 3) players.goTo(tp, bed);
            else players.idle(tp);
            tp.jobUntil = now + 20_000;
            return;
        }

        // Roads piling up: builders and road workers pave one before the next building.
        if (t.roads.size() >= 3 && t.roadBy == null && (tp.job.equals("builder") || tp.job.equals("roadworker"))) {
            Location[] r = t.roads.poll();
            if (plugin.builder().startRoad(tp, t, r[0], r[1])) t.roadBy = tp.id;
            return;
        }
        // Build: the next plot for this job, in the plan's order. Specialists with nothing of their own left to
        // build help with the houses half the time.
        // Carrying a lot: put it in the town's storage chest first, where the builders can get it.
        if (!tp.job.equals("builder") && haul(tp) >= 48 && deposit(tp, t)) return;
        int plot = t.nextPlot(tp.job);
        if (plot < 0 && !List.of("scout", "entertainer", "lumberjack", "miner").contains(tp.job) && ThreadLocalRandom.current().nextBoolean()) plot = t.nextPlot("builder");
        if (buildPlot(tp, t, m, plot, now)) return;
        switch (tp.job) {
            case "roadworker" -> {
                if (t.roadBy == null && !t.roads.isEmpty()) {
                    Location[] r = t.roads.poll();
                    if (plugin.builder().startRoad(tp, t, r[0], r[1])) t.roadBy = tp.id;
                    return;
                }
                patrol(tp, t, c, t.radius * 0.5, "checking the roads", now);
            }
            case "farmer" -> farm(tp, t, m, now);
            case "guard" -> {
                if (tp.crewOf == null) patrol(tp, t, c, t.radius * 0.8, "on patrol", now);
            }
            case "scout" -> startTrip(tp, t, m, c, now, null);
            case "lumberjack" -> lumberjack(tp, t, m, now);
            case "miner" -> miner(tp, t, m, now);
            case "mayor" -> patrol(tp, t, c, 12, "looking over the town", now);
            case "entertainer" -> patrol(tp, t, c, 6, "entertaining at the campfire", now);
            case "trader" -> {
                Location stall = builtSpot(t, "stall");
                if (stall != null) {
                    if (Town.hdist(stall, m.getLocation()) > 2) players.goTo(tp, stall);
                    tp.buildStatus = "minding the market stall";
                    tp.jobUntil = now + 30_000;
                } else patrol(tp, t, c, 10, "waiting for a market stall", now);
            }
            default -> {
                // A builder with nothing left to build: road work, then a stroll.
                if (t.roadBy == null && !t.roads.isEmpty()) {
                    Location[] r = t.roads.poll();
                    if (plugin.builder().startRoad(tp, t, r[0], r[1])) t.roadBy = tp.id;
                    return;
                }
                patrol(tp, t, c, t.radius * 0.6, "admiring the town", now);
            }
        }
    }

    /** Start building plot (from Town.nextPlot): false if there's none to build. Trees in the way get cleared first. */
    boolean buildPlot(TestPlayer tp, Town t, Mannequin m, int plot, long now) {
        if (plot < 0) return false;
        Town.Plot p = t.plots.get(plot);
        p.state = "building";
        p.by = tp.id;
        if (!plugin.builder().startPlot(tp, t, plot)) {
            // Trees in the way? Clear them first (and the wood comes in handy), then try again.
            int[] box = survival() && p.clears < 2 ? plugin.builder().footprint(p) : null;
            Gather.Work clear = box == null ? null : plugin.gather().clearArea(m.getWorld(), box);
            if (clear != null) {
                p.clears++;
                p.busyUntil = now + 120_000;
                p.state = "planned";
                p.by = null;
                plugin.gather().start(tp, clear);
                tp.buildStatus = "clearing trees for the " + p.structure;
                return true;
            }
            p.state = "skipped";
            p.by = null;
            plugin.getLogger().info(t.name + ": the " + p.structure + " at " + p.x + " " + p.z + " doesn't fit there, skipping it");
        } else if (survival() && storageHas(t, true, true)) {
            // Stock up from the storage chest before starting, like a player grabbing materials.
            int[] need = plugin.builder().needs(tp);
            if (Items.logs(tp) * 4 < need[0] * 4 || Items.stone(tp) < need[1]) plugin.builder().setMissing(tp);
        }
        dirty = true;
        return true;
    }

    void patrol(TestPlayer tp, Town t, Location c, double r, String what, long now) {
        double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        double d = r * (0.5 + ThreadLocalRandom.current().nextDouble() * 0.5);
        plugin.players().goTo(tp, new Location(c.getWorld(), c.getX() + Math.cos(a) * d, c.getY(), c.getZ() + Math.sin(a) * d));
        tp.buildStatus = what;
        tp.jobUntil = now + 15_000 + ThreadLocalRandom.current().nextInt(15_000);
    }

    /** Walk the farms, harvesting ripe wheat (it's replanted at once). Builds a farm first if there's none. */
    void farm(TestPlayer tp, Town t, Mannequin m, long now) {
        List<Town.Plot> farms = t.plots.stream().filter(p -> p.structure.equals("farm") && p.state.equals("built") && p.built != null).toList();
        if (farms.isEmpty()) {
            patrol(tp, t, t.center(), 10, "waiting for a farm", now);
            return;
        }
        Town.Plot f = farms.get(ThreadLocalRandom.current().nextInt(farms.size()));
        // The farm's middle is ~5 blocks in from its gate (where it was built from).
        Location gate = f.built;
        Location mid = gate.clone().add((f.x - gate.getX()) * 0.5, 0, (f.z - gate.getZ()) * 0.5);
        if (Town.hdist(mid, m.getLocation()) > 6) {
            plugin.players().goTo(tp, gate);
            tp.buildStatus = "walking to a farm";
            tp.jobUntil = now + 25_000;
            return;
        }
        int harvested = 0;
        World w = m.getWorld();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    Block b = w.getBlockAt(f.x + dx, m.getLocation().getBlockY() + dy, f.z + dz);
                    if (b.getType() == Material.WHEAT && b.getBlockData() instanceof Ageable crop && crop.getAge() == crop.getMaximumAge()) {
                        crop.setAge(0); // replanted with one of the seeds it drops
                        b.setBlockData(crop);
                        harvested++;
                        Items.add(tp, Material.WHEAT, 1);
                        Items.add(tp, Material.WHEAT_SEEDS, ThreadLocalRandom.current().nextInt(3));
                    }
                }
            }
        }
        if (harvested > 0) {
            m.swingMainHand();
            w.playSound(m.getLocation(), Sound.BLOCK_CROP_BREAK, 1f, 1f);
            tp.lastResult = "harvested " + harvested + " wheat and replanted";
            tp.harvested += harvested;
            while (Items.count(tp, Material.WHEAT) >= 6 && Items.take(tp, Material.WHEAT, 3)) Items.add(tp, Material.BREAD, 1); // bakes some
        }
        tp.buildStatus = harvested > 0 ? "harvesting wheat" : "tending the farm";
        plugin.players().wander(tp, 3);
        tp.jobUntil = now + 20_000;
    }

    Location builtSpot(Town t, String structure) {
        return t.plots.stream().filter(p -> p.structure.equals(structure) && p.state.equals("built") && p.built != null).map(p -> p.built).findFirst().orElse(null);
    }

    /** Traders say hello to real players who come to the market (without an AI model; with one, the AI does). */
    private void greet(TestPlayer tp, Town t, Mannequin m, long now) {
        if (tp.ai && tp.model != null || now - tp.lastSaidAt < 45_000) return;
        for (Player p : m.getLocation().getNearbyPlayers(8)) {
            String[] lines = {"welcome to " + t.name + ", " + p.getName() + "! best prices in the " + t.stage(),
                "hi " + p.getName() + "! fresh wheat today, straight from our farms", "come back anytime, " + p.getName() + "!"};
            plugin.players().say(tp, lines[ThreadLocalRandom.current().nextInt(lines.length)]);
            return;
        }
    }

    /** The entertainer tells a joke when there's an audience. */
    private void entertain(TestPlayer tp, Town t, Mannequin m, long now) {
        if (tp.ai && tp.model != null || now - tp.lastSaidAt < 90_000) return;
        if (m.getLocation().getNearbyEntities(10, 5, 10).stream().anyMatch(e -> e instanceof Player || (e instanceof Mannequin o && plugin.players().of(o) != null && o != m))) {
            plugin.players().say(tp, JOKES[ThreadLocalRandom.current().nextInt(JOKES.length)]);
        }
    }

    private boolean survival() {
        return plugin.getConfig().getBoolean("survival.enabled", true);
    }

    // ------------------------------------------------------------ materials: gathering and the storage chest

    /**
     * A builder out of materials: take what its build needs from the town's storage chest, or go and chop the
     * nearest tree (then it goes back to building, and comes back here if that wasn't enough).
     */
    void supply(TestPlayer tp, Mannequin m) {
        if (plugin.gather().busy(tp)) return;
        Town t = get(tp.town);
        int[] need = plugin.builder().needs(tp);
        if (t != null && storageHas(t, true, false)) { // it ran out of wood: only worth the walk if there are logs
            Gather.Work w = visitStorage(tp, t, "fetching materials from the storage chest", inv -> {
                int logs = takeFrom(inv, tp, Items::isLog, Math.max(4, Math.min(need[0] + 2, 32)));
                int stone = takeFrom(inv, tp, mat -> mat == Material.COBBLESTONE || mat == Material.COBBLED_DEEPSLATE, Math.min(need[1], 64));
                takeFrom(inv, tp, mat -> mat == Material.GLASS || mat == Material.SAND || mat == Material.COAL, 8);
                tp.lastResult = "took " + logs + " logs and " + stone + " cobblestone from the storage chest";
            });
            if (w != null) {
                plugin.gather().start(tp, w);
                return;
            }
        }
        Location here = m.getLocation();
        Gather.Work w = plugin.gather().tree(here, 24, here, t == null || Town.hdist(here, t.center()) > t.radius * 0.9);
        if (w == null && t != null) w = plugin.gather().tree(t.center(), t.radius + 16, here, false);
        if (w == null) w = plugin.gather().tree(here, 56, here, true);
        if (w == null) {
            plugin.builder().cancel(tp);
            tp.mode = TestPlayer.Mode.IDLE;
            tp.lastResult = "gave up building: out of wood and there are no trees nearby";
            if (t != null) tp.jobUntil = System.currentTimeMillis() + 60_000;
            return;
        }
        plugin.gather().start(tp, w);
        tp.buildStatus = "out of wood: " + w.label;
    }

    /** Things it carries that aren't its own kit (tools, a few torches...). */
    static int haul(TestPlayer tp) {
        int n = 0;
        for (Map.Entry<Material, Integer> e : tp.items.entrySet()) if (!kit(e.getKey())) n += e.getValue();
        return n;
    }

    private static boolean kit(Material m) {
        String n = m.name();
        return n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.endsWith("_SWORD")
                || n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || m == Material.FURNACE || m == Material.BUCKET || m == Material.WATER_BUCKET;
    }

    /** Put what it's hauling in the storage chest (making the chest first if the town has none). */
    boolean deposit(TestPlayer tp, Town t) {
        if (!t.settled) return false; // a team with no base yet keeps what it carries
        if (!storageOk(t)) return makeStorage(tp, t);
        Gather.Work w = visitStorage(tp, t, "putting things in the storage chest", inv -> {
            int n = 0;
            for (Map.Entry<Material, Integer> e : new ArrayList<>(tp.items.entrySet())) {
                Material mat = e.getKey();
                int keep = kit(mat) ? e.getValue() : Life.isFood(mat) ? 16 : mat == Material.TORCH ? 16 : mat == Material.WHEAT_SEEDS ? 8
                        : Items.isLog(mat) ? 4 : mat == Material.COBBLESTONE ? 16 : 0;
                int give = e.getValue() - keep;
                if (give <= 0) continue;
                int left = 0;
                for (ItemStack rest : inv.addItem(new ItemStack(mat, give)).values()) left += rest.getAmount();
                Items.take(tp, mat, give - left);
                n += give - left;
            }
            tp.lastResult = n > 0 ? "put " + n + " things in the " + t.name + " storage chest" : "the storage chest is full";
        });
        if (w == null) return false;
        plugin.gather().start(tp, w);
        return true;
    }

    /** The first one with the wood for it makes the town's storage chest, by the campfire. */
    private boolean makeStorage(TestPlayer tp, Town t) {
        World w = Bukkit.getWorld(t.world);
        if (w == null || Items.logs(tp) < 2 && Items.count(tp, Material.CHEST) == 0) return false;
        Block spot = w.getHighestBlockAt(t.cx + 3, t.cz - 3, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES).getRelative(org.bukkit.block.BlockFace.UP);
        Gather.Work work = new Gather.Work("making a storage chest for " + t.name);
        work.go = spot.getLocation().add(0.5, 0, 0.5);
        work.arrive = () -> {
            if (!spot.getType().isAir() && !spot.isReplaceable()) return;
            if (Items.provide(tp, Material.CHEST) != Material.CHEST) return;
            spot.setType(Material.CHEST);
            spot.getWorld().playSound(spot.getLocation(), Sound.BLOCK_WOOD_PLACE, 1f, 1f);
            t.storage = spot.getLocation();
            dirty = true;
            tp.lastResult = "made a storage chest for " + t.name;
            if (!tp.ai) plugin.players().say(tp, "made us a storage chest by the campfire, put your stuff in there");
        };
        plugin.gather().start(tp, work);
        return true;
    }

    private static boolean storageOk(Town t) {
        return t.storage != null && t.storage.isWorldLoaded() && t.storage.getWorld().isChunkLoaded(t.storage.getBlockX() >> 4, t.storage.getBlockZ() >> 4)
                && t.storage.getBlock().getState() instanceof Chest;
    }

    /** Does the storage chest have logs (wood) or cobblestone (stone) in it? */
    static boolean storageHas(Town t, boolean wood, boolean stone) {
        if (!storageOk(t)) return false;
        for (ItemStack i : ((Chest) t.storage.getBlock().getState()).getInventory().getContents()) {
            if (i == null) continue;
            if (wood && Items.isLog(i.getType())) return true;
            if (stone && (i.getType() == Material.COBBLESTONE || i.getType() == Material.COBBLED_DEEPSLATE)) return true;
        }
        return false;
    }

    /** Walk to the storage chest, open it, do something with what's inside, close it. */
    Gather.Work visitStorage(TestPlayer tp, Town t, String label, java.util.function.Consumer<Inventory> use) {
        if (!storageOk(t)) return null;
        Gather.Work w = new Gather.Work(label);
        w.go = t.storage.clone().add(0.5, 0, 0.5);
        w.arrive = () -> {
            if (!(t.storage.getBlock().getState() instanceof Chest chest)) return;
            chest.open();
            use.accept(chest.getInventory());
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (t.storage.getBlock().getState() instanceof Chest c2) c2.close();
            }, 30);
        };
        return w;
    }

    /** Take up to n items matching from a chest into its inventory. Returns how many. */
    private static int takeFrom(Inventory inv, TestPlayer tp, java.util.function.Predicate<Material> what, int n) {
        int got = 0;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length && got < n; i++) {
            ItemStack s = contents[i];
            if (s == null || !what.test(s.getType())) continue;
            int k = Math.min(s.getAmount(), n - got);
            Items.add(tp, s.getType(), k);
            got += k;
            if (k == s.getAmount()) inv.setItem(i, null);
            else s.setAmount(s.getAmount() - k);
        }
        return got;
    }

    void lumberjack(TestPlayer tp, Town t, Mannequin m, long now) {
        if (Items.logs(tp) >= 32 && deposit(tp, t)) return;
        Location c = t.center();
        // Trees in and around town, nearest first (it doesn't replant inside town: those spots are for houses).
        Gather.Work w = plugin.gather().tree(m.getLocation(), 20, m.getLocation(), Town.hdist(m.getLocation(), c) > t.radius * 0.9);
        if (w == null) w = plugin.gather().tree(c, t.radius + 30, m.getLocation(), true);
        if (w == null) {
            patrol(tp, t, c, t.radius + 20, "looking for trees", now);
            return;
        }
        plugin.gather().start(tp, w);
    }

    void miner(TestPlayer tp, Town t, Mannequin m, long now) {
        if ((Items.stone(tp) >= 64 || haul(tp) >= 48) && deposit(tp, t)) return;
        // Needs a pickaxe: wood for one from the storage chest, or a tree.
        if (Items.tool(tp, "PICKAXE") < 0 && !Items.craftTool(tp, "PICKAXE")) {
            if (storageHas(t, true, false)) {
                Gather.Work w = visitStorage(tp, t, "getting wood for a pickaxe", inv -> takeFrom(inv, tp, Items::isLog, 4));
                if (w != null) {
                    plugin.gather().start(tp, w);
                    return;
                }
            }
            Gather.Work w = plugin.gather().tree(m.getLocation(), 40, m.getLocation(), true);
            if (w != null) plugin.gather().start(tp, w);
            else patrol(tp, t, t.center(), t.radius, "looking for wood for a pickaxe", now);
            return;
        }
        World w = m.getWorld();
        if (t.mineDx == 0 && t.mineDz == 0) {
            // The mine entrance: halfway out east of the square, between the rings of houses.
            t.mineX = t.cx + Math.max(12, (int) (t.radius * 0.5));
            t.mineZ = t.cz + 2;
            // Not right next to a nether portal (it would walk straight in).
            for (int i = 0; i < 4 && Gather.portalNear(new Location(w, t.mineX, w.getHighestBlockYAt(t.mineX, t.mineZ), t.mineZ), 6) != null; i++) t.mineZ += 9;
            t.mineY = w.getHighestBlockYAt(t.mineX, t.mineZ, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
            t.mineDx = 1;
            t.mineDz = 0;
            dirty = true;
            if (!tp.ai) plugin.players().say(tp, "starting a mine east of the square, I'll bring back stone and ore");
        }
        Gather.Work work = plugin.gather().mine(tp, t);
        dirty = true;
        if (work == null) patrol(tp, t, t.center(), 10, "resting", now);
        else plugin.gather().start(tp, work);
    }

    // ------------------------------------------------------------ explorers: trips out, and to the nether

    /** A scout sets off: to a nether portal the town knows about (sometimes), or out to explore. A guard comes along. */
    void startTrip(TestPlayer tp, Town t, Mannequin m, Location c, long now, Boolean wantNether) {
        notePortal(tp, t, m.getLocation());
        List<Location> known = portalsFor(t, m.getLocation());
        boolean nether = !known.isEmpty() && plugin.getConfig().getBoolean("survival.nether-trips", true)
                && (wantNether != null ? wantNether : ThreadLocalRandom.current().nextDouble() < 0.5);
        if (nether) {
            tp.portal = known.stream().min((a, b) -> Double.compare(Town.hdist(a, m.getLocation()), Town.hdist(b, m.getLocation()))).orElse(null);
            tp.trip = "to-portal";
            tp.tripUntil = now + 180_000;
            plugin.players().goTo(tp, tp.portal);
            tp.buildStatus = "heading to the nether portal";
            if (!tp.ai && ThreadLocalRandom.current().nextBoolean()) plugin.players().say(tp, "going to the nether, wish me luck");
        } else {
            // The way it said it would go (its AI), or anywhere.
            double a = tp.tripYaw != null ? Math.atan2(Math.cos(Math.toRadians(tp.tripYaw)), -Math.sin(Math.toRadians(tp.tripYaw)))
                    : ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            tp.tripYaw = null;
            double d = 120 + ThreadLocalRandom.current().nextDouble(240);
            tp.portal = null;
            tp.tripFrom = c;
            tp.trip = "out";
            tp.tripUntil = now + 360_000;
            plugin.players().goTo(tp, new Location(c.getWorld(), c.getX() + Math.cos(a) * d, c.getY(), c.getZ() + Math.sin(a) * d));
            tp.buildStatus = "exploring to the " + TestPlayer.compass((float) Math.toDegrees(Math.atan2(-Math.cos(a), Math.sin(a))));
        }
        // A guard who isn't busy goes along.
        for (Map.Entry<String, String> e : t.jobs.entrySet()) {
            TestPlayer g = plugin.players().get(e.getKey());
            if (g == null || !e.getValue().equals("guard") || g.crewOf != null || g.trip != null || g.entity() == null) continue;
            g.crewOf = tp.id;
            g.trip = "escort";
            g.buildStatus = "going with " + tp.name;
            plugin.players().follow(g, tp.name);
            break;
        }
    }

    void trip(TestPlayer tp, Town t, Mannequin m, long now) {
        TestPlayers players = plugin.players();
        Location loc = m.getLocation();
        boolean inNether = loc.getWorld().getEnvironment() == World.Environment.NETHER;
        Location c = t.center();
        switch (tp.trip) {
            case "out" -> {
                if ((now / 1000) % 5 == 0) notePortal(tp, t, loc);
                if (tp.mode == TestPlayer.Mode.IDLE || now > tp.tripUntil) {
                    String biome = loc.getWorld().getBiome(loc).getKey().getKey().replace('_', ' ');
                    float yaw = c == null ? 0 : (float) Math.toDegrees(Math.atan2(-(loc.getX() - c.getX()), loc.getZ() - c.getZ()));
                    tp.scoutReport = tp.ai ? null : "back from the " + TestPlayer.compass(yaw) + ": it's " + biome + " out there";
                    tp.lastResult = "explored " + Math.round(c == null ? 0 : Town.hdist(loc, c)) + " blocks " + TestPlayer.compass(yaw) + " of " + t.name + ": " + biome;
                    // Something to show for it: a tree on the way back.
                    Gather.Work w = plugin.gather().tree(loc, 16, loc, true);
                    tp.trip = "home";
                    tp.tripUntil = now + 300_000;
                    if (w != null) plugin.gather().start(tp, w);
                }
            }
            case "home" -> {
                if (plugin.gather().busy(tp)) return;
                if (inNether || c == null || loc.getWorld() != c.getWorld()) {
                    // In the nether (a trip, or it walked into a portal): back through the nearest portal.
                    Location back = tp.tripFrom != null && tp.tripFrom.getWorld() == loc.getWorld() ? tp.tripFrom : Gather.portalNear(loc, 24);
                    if (inNether && back != null) {
                        tp.tripFrom = back;
                        intoPortal(tp, m, back);
                    } else if (now > tp.tripUntil - 240_000) {
                        // No portal anywhere near after a minute (it came through one that didn't make a way back): home.
                        m.teleport(tp.portal != null ? tp.portal : c);
                        plugin.getLogger().info(tp.name + " found no portal back from " + loc.getWorld().getName() + "; brought it home");
                    }
                    return;
                }
                if (Town.hdist(loc, c) > 8) {
                    if (now > tp.tripUntil) {
                        // 5 minutes and no way back found (a lake, a ravine...): it finds one.
                        m.teleport(c);
                        plugin.getLogger().info(tp.name + " couldn't find the way back to " + t.name + " for 5 minutes; brought it home");
                    } else if (tp.mode != TestPlayer.Mode.GOTO) players.goTo(tp, c);
                    tp.buildStatus = "heading back to " + t.name;
                    return;
                }
                endTrip(tp, t);
                if (tp.scoutReport != null && now - tp.lastSaidAt > 120_000) players.say(tp, tp.scoutReport);
                tp.scoutReport = null;
                if (haul(tp) > 0) deposit(tp, t);
            }
            case "to-portal" -> {
                if (inNether) {
                    tp.trip = "nether";
                    tp.tripFrom = loc.clone();
                    tp.tripUntil = now + 150_000 + ThreadLocalRandom.current().nextInt(90_000);
                    tp.buildStatus = "exploring the nether";
                    players.idle(tp);
                    return;
                }
                if (tp.portal == null || now > tp.tripUntil) {
                    // Couldn't get there: try another time. If the portal's actually gone, forget it.
                    Location gone = tp.portal;
                    if (gone != null && gone.isChunkLoaded() && Gather.portalNear(gone, 2) == null) t.portals.removeIf(p -> p.getWorld() == gone.getWorld() && p.distanceSquared(gone) < 4);
                    tp.trip = "home";
                    tp.tripUntil = now + 300_000;
                    return;
                }
                intoPortal(tp, m, tp.portal);
            }
            case "nether" -> {
                if (!inNether) {
                    tp.trip = "home";
                    tp.tripUntil = now + 300_000;
                    return;
                }
                if (plugin.gather().busy(tp)) return;
                if (now > tp.tripUntil || haul(tp) >= 48) {
                    tp.trip = "leaving";
                    tp.tripUntil = now + 120_000;
                    return;
                }
                Gather.Work w = plugin.gather().netherLoot(loc);
                if (w != null) plugin.gather().start(tp, w);
                else if (tp.mode == TestPlayer.Mode.IDLE) {
                    Location keep = tp.home;
                    players.wander(tp, 18);
                    tp.home = keep; // wander() moves "home" to here; it should still respawn in town
                }
            }
            case "leaving" -> {
                if (!inNether) {
                    tp.trip = "home";
                    tp.tripUntil = now + 300_000;
                    tp.lastResult = "back from the nether with " + Items.describe(tp, 4);
                    if (!tp.ai) tp.scoutReport = "back from the nether! got " + Items.describe(tp, 3);
                    return;
                }
                if (now > tp.tripUntil) {
                    m.teleport(tp.tripFrom); // can't find the way back for 2 minutes: step through like a player would
                    tp.tripUntil = now + 60_000;
                }
                intoPortal(tp, m, tp.tripFrom);
            }
            case "escort" -> {
                TestPlayer lead = plugin.players().get(tp.crewOf);
                Mannequin lm = lead == null ? null : lead.entity();
                if (lead == null || lead.trip == null || lm == null) {
                    tp.crewOf = null;
                    endTrip(tp, t);
                    if (!inNether && c != null) players.goTo(tp, c);
                    return;
                }
                if (lm.getWorld() == loc.getWorld()) {
                    if (tp.mode != TestPlayer.Mode.FOLLOW) players.follow(tp, lead.name);
                    tp.buildStatus = "guarding " + lead.name;
                } else if (inNether) {
                    Location back = lead.tripFrom != null && lead.tripFrom.getWorld() == loc.getWorld() ? lead.tripFrom : Gather.portalNear(loc, 16);
                    if (back != null) intoPortal(tp, m, back);
                } else if (lead.portal != null) intoPortal(tp, m, lead.portal);
            }
            default -> tp.trip = null;
        }
    }

    void endTrip(TestPlayer tp, Town t) {
        tp.trip = null;
        tp.portal = null;
        tp.tripFrom = null;
        tp.jobUntil = System.currentTimeMillis() + 30_000;
        plugin.players().idle(tp);
        // Its guard can go back to patrolling.
        for (TestPlayer g : plugin.players().all()) {
            if (tp.id.equals(g.crewOf)) {
                g.crewOf = null;
                g.trip = null;
            }
        }
    }

    /** Walk to a portal and step into it (the game itself takes it to the other side). */
    void intoPortal(TestPlayer tp, Mannequin m, Location portal) {
        if (portal.getWorld() != m.getWorld()) return;
        double d = Town.hdist(portal, m.getLocation());
        if (d > 1.5) {
            if (tp.mode != TestPlayer.Mode.GOTO || tp.target == null || tp.target.distanceSquared(portal) > 1) plugin.players().goTo(tp, portal);
            tp.buildStatus = m.getWorld().getEnvironment() == World.Environment.NETHER ? "heading back to the portal" : "heading to the nether portal";
            return;
        }
        // Right next to it: the last step in.
        Location exact = Gather.portalNear(m.getLocation(), 2);
        if (exact != null && m.getLocation().getBlock().getType() != Material.NETHER_PORTAL) {
            exact.setYaw(m.getLocation().getYaw());
            m.teleport(exact);
        }
    }

    /** Portals this town knows, and ones any other town or team knows within 600 blocks (word gets around). */
    List<Location> portalsFor(Town t, Location near) {
        List<Location> out = new ArrayList<>(t.portals);
        for (Town o : towns.values()) {
            if (o == t) continue;
            for (Location p : o.portals) if (p.getWorld() == near.getWorld() && Town.hdist(p, near) < 600) out.add(p);
        }
        return out;
    }

    /** Spotted a nether portal: the town remembers it (and the scout tells everyone). */
    void notePortal(TestPlayer tp, Town t, Location loc) {
        Location p = Gather.portalNear(loc, 32);
        if (p == null || t.portals.stream().anyMatch(o -> o.getWorld() == p.getWorld() && o.distanceSquared(p) < 16 * 16)) return;
        t.portals.add(p);
        dirty = true;
        tp.lastResult = "found a nether portal at " + p.getBlockX() + " " + p.getBlockY() + " " + p.getBlockZ();
        if (!tp.ai) plugin.players().say(tp, "found a nether portal at " + p.getBlockX() + " " + p.getBlockZ() + "!");
    }

    /** Add a portal by hand (/testplayer town portal), for when the scouts haven't found one. */
    void addPortal(Town t, Location near) {
        Location p = Gather.portalNear(near, 6);
        if (p == null) throw new IllegalArgumentException("Stand next to a lit nether portal.");
        t.portals.removeIf(o -> o.getWorld() == p.getWorld() && o.distanceSquared(p) < 16 * 16);
        t.portals.add(p);
        dirty = true;
        save();
    }

    /** What an AI picked for itself: chop, mine, explore, nether, work or campfire. Returns what happened. */
    String order(TestPlayer tp, String action, String direction) {
        Mannequin m = tp.entity();
        if (m == null) return action + " failed: not in the world";
        Town t = get(tp.town);
        long now = System.currentTimeMillis();
        switch (action) {
            case "chop" -> {
                Gather.Work w = plugin.gather().tree(m.getLocation(), 40, m.getLocation(), t == null || Town.hdist(m.getLocation(), t.center()) > t.radius * 0.9);
                if (w == null) return "chop failed: no trees within 40 blocks";
                plugin.players().idle(tp);
                plugin.gather().start(tp, w);
                return "going to chop a tree";
            }
            case "mine" -> {
                if (t == null) return "mine failed: you can only mine in your town's mine, and you don't live in a town";
                plugin.players().idle(tp);
                miner(tp, t, m, now);
                return "heading into the " + t.name + " mine";
            }
            case "explore", "nether" -> {
                Location c = t != null ? t.center() : m.getLocation();
                if (action.equals("nether") && (t == null || portalsFor(t, m.getLocation()).isEmpty())) return "nether failed: nobody has found a nether portal yet";
                if (t == null) {
                    plugin.players().walk(tp, TestPlayers.parseDirection(direction.isEmpty() ? "north" : direction));
                    return "off exploring";
                }
                plugin.players().idle(tp);
                Float yaw = null;
                try {
                    if (!direction.isEmpty()) yaw = TestPlayers.parseDirection(direction);
                } catch (IllegalArgumentException ignored) {
                    // not a direction it understands: pick one
                }
                tp.tripYaw = yaw;
                startTrip(tp, t, m, c, now, action.equals("nether"));
                return "set off: " + tp.buildStatus;
            }
            case "campfire" -> {
                if (t == null) return "campfire failed: you don't live in a town";
                patrol(tp, t, t.center(), 5, "hanging out by the campfire", now);
                tp.jobUntil = now + 60_000;
                return "hanging out by the campfire";
            }
            default -> {
                if (t == null) return "nothing to do: you don't have a job";
                tp.jobUntil = 0;
                return "back to work as a " + tp.job;
            }
        }
    }

    // ------------------------------------------------------------ looking like a player

    /** Look at players who come close, crouch to say hi now and then, wear what armour it has. */
    void behave(TestPlayer tp, Mannequin m, long now) {
        if (tp.mode == TestPlayer.Mode.IDLE || tp.mode == TestPlayer.Mode.FOLLOW) {
            Player near = null;
            double best = 36;
            for (Player p : m.getLocation().getNearbyPlayers(6)) {
                double d = p.getLocation().distanceSquared(m.getLocation());
                if (d < best && p.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
                    best = d;
                    near = p;
                }
            }
            if (near != null) {
                Location e = near.getEyeLocation();
                m.lookAt(e.getX(), e.getY(), e.getZ(), io.papermc.paper.entity.LookAnchor.EYES);
                if (now - tp.greetedAt > 180_000 && best < 16) {
                    tp.greetedAt = now;
                    crouch(m, 0);
                }
            }
        }
        if (tp.job != null && tp.job.equals("guard") && Items.count(tp, Material.IRON_INGOT) >= 8 && Items.count(tp, Material.IRON_CHESTPLATE) == 0) {
            Items.take(tp, Material.IRON_INGOT, 8);
            Items.add(tp, Material.IRON_CHESTPLATE, 1);
        }
        for (String part : new String[] {"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"}) {
            for (String tier : new String[] {"DIAMOND", "IRON", "CHAINMAIL", "GOLDEN", "LEATHER"}) {
                Material a = Material.matchMaterial(tier + "_" + part);
                if (a == null || Items.count(tp, a) == 0) continue;
                org.bukkit.inventory.EntityEquipment eq = m.getEquipment();
                ItemStack now2 = switch (part) { case "HELMET" -> eq.getHelmet(); case "CHESTPLATE" -> eq.getChestplate(); case "LEGGINGS" -> eq.getLeggings(); default -> eq.getBoots(); };
                if (now2 == null || now2.getType() != a) {
                    switch (part) {
                        case "HELMET" -> eq.setHelmet(new ItemStack(a));
                        case "CHESTPLATE" -> eq.setChestplate(new ItemStack(a));
                        case "LEGGINGS" -> eq.setLeggings(new ItemStack(a));
                        default -> eq.setBoots(new ItemStack(a));
                    }
                }
                break;
            }
        }
    }

    /** Crouch twice, quickly: how players say hello. */
    private void crouch(Mannequin m, int n) {
        if (n >= 4 || !m.isValid()) return;
        m.setPose(n % 2 == 0 ? org.bukkit.entity.Pose.SNEAKING : org.bukkit.entity.Pose.STANDING, true);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (n == 3 && m.isValid()) m.setPose(org.bukkit.entity.Pose.STANDING, false);
            else crouch(m, n + 1);
        }, 4);
    }

    /** "Riverton is a village now!", said by the mayor (or anyone, if there's no mayor). */
    private void announce(Town t) {
        String stage = t.stage();
        if (stage.equals(t.stageAnnounced)) return;
        t.stageAnnounced = stage;
        dirty = true;
        TestPlayer speaker = null;
        for (Map.Entry<String, String> e : t.jobs.entrySet()) {
            TestPlayer tp = plugin.players().get(e.getKey());
            if (tp == null || tp.entity() == null) continue;
            if (speaker == null || e.getValue().equals("mayor")) speaker = tp;
        }
        String text = t.name + " is a " + stage + " now! " + t.builtOf("house", "hut") + " homes, " + t.count("built") + " buildings in all";
        if (speaker != null) plugin.players().say(speaker, text);
        plugin.getLogger().info(text);
        plugin.link().townEvent(t, text);
    }

    // ------------------------------------------------------------ towns.yml

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Town t : towns.values()) {
            ConfigurationSection s = y.createSection("towns." + t.name);
            s.set("world", t.world);
            s.set("x", t.cx);
            s.set("z", t.cz);
            s.set("radius", t.radius);
            s.set("stage-announced", t.stageAnnounced);
            if (t.camp) {
                s.set("camp", true);
                s.set("settled", t.settled);
                s.set("humans", new ArrayList<>(t.humans));
            }
            List<String> rows = new ArrayList<>();
            for (Town.Plot p : t.plots) {
                // A build in progress is picked up where it left off after a restart.
                String state = p.state.equals("building") || (p.state.equals("planned") && p.resume) ? "resume" : p.state;
                rows.add(p.structure + "," + p.material + "," + p.x + "," + p.z + "," + p.rot + "," + state
                        + (p.built != null ? "," + p.built.getBlockX() + "," + p.built.getBlockY() + "," + p.built.getBlockZ() : ""));
            }
            s.set("plots", rows);
            if (t.storage != null) s.set("storage", t.storage);
            if (t.mineDx != 0 || t.mineDz != 0) s.set("mine", List.of(t.mineX, t.mineY, t.mineZ, t.mineDx, t.mineDz, t.mineStep, t.mineShafts));
            s.set("portals", new ArrayList<>(t.portals));
            for (Map.Entry<String, String> e : t.jobs.entrySet()) s.set("jobs." + e.getKey(), e.getValue());
            for (Map.Entry<String, Integer> e : t.homes.entrySet()) s.set("homes." + e.getKey(), e.getValue());
        }
        try {
            y.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save towns.yml", e);
        }
    }

    void load() {
        if (!file.exists()) return;
        ConfigurationSection all = YamlConfiguration.loadConfiguration(file).getConfigurationSection("towns");
        if (all == null) return;
        for (String name : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(name);
            if (s == null) continue;
            Town t = new Town(name, s.getString("world", "world"), s.getInt("x"), s.getInt("z"), s.getInt("radius", 45));
            t.stageAnnounced = s.getString("stage-announced", "camp");
            t.camp = s.getBoolean("camp", false);
            t.settled = !t.camp || s.getBoolean("settled", false);
            t.humans.addAll(s.getStringList("humans"));
            World w = Bukkit.getWorld(t.world);
            for (String row : s.getStringList("plots")) {
                String[] p = row.split(",");
                if (p.length < 6) continue;
                try {
                    Town.Plot plot = new Town.Plot(p[0], p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]));
                    plot.state = p[5].equals("resume") ? "planned" : p[5];
                    plot.resume = p[5].equals("resume");
                    if (p.length >= 9 && w != null) plot.built = new Location(w, Integer.parseInt(p[6]) + 0.5, Integer.parseInt(p[7]), Integer.parseInt(p[8]) + 0.5);
                    t.plots.add(plot);
                } catch (NumberFormatException ignored) {
                    // skip a damaged line
                }
            }
            try {
                t.storage = s.getLocation("storage");
            } catch (IllegalArgumentException ignored) {
                // its world isn't loaded
            }
            List<Integer> mine = s.getIntegerList("mine");
            if (mine.size() == 7) {
                t.mineX = mine.get(0);
                t.mineY = mine.get(1);
                t.mineZ = mine.get(2);
                t.mineDx = mine.get(3);
                t.mineDz = mine.get(4);
                t.mineStep = mine.get(5);
                t.mineShafts = mine.get(6);
            }
            for (Object o : s.getList("portals", List.of())) if (o instanceof Location l && l.isWorldLoaded()) t.portals.add(l);
            ConfigurationSection jobs = s.getConfigurationSection("jobs");
            if (jobs != null) for (String id : jobs.getKeys(false)) t.jobs.put(id, jobs.getString(id));
            ConfigurationSection homes = s.getConfigurationSection("homes");
            if (homes != null) for (String id : homes.getKeys(false)) t.homes.put(id, homes.getInt(id));
            towns.put(name.toLowerCase(Locale.ROOT), t);
            for (Map.Entry<String, String> e : t.jobs.entrySet()) {
                TestPlayer tp = plugin.players().get(e.getKey());
                if (tp != null) {
                    tp.town = t.name;
                    tp.job = e.getValue();
                }
            }
        }
    }
}
