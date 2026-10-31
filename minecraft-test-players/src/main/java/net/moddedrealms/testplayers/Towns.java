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
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;

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

    List<Town> all() {
        return new ArrayList<>(towns.values());
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
            plugin.builder().removeZone("town-" + t.name);
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
                live(tp, t, m);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, tp.name + "'s town routine failed", e);
                tp.jobUntil = System.currentTimeMillis() + 10_000;
            }
        }
        for (Town t : towns.values()) announce(t);
        if (dirty) save();
    }

    private void live(TestPlayer tp, Town t, Mannequin m) {
        long now = System.currentTimeMillis();
        TestPlayers players = plugin.players();
        if (tp.mode == TestPlayer.Mode.BUILD || tp.pendingBuild != null) return; // busy building
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
        int plot = t.nextPlot(tp.job);
        if (plot < 0 && !List.of("scout", "entertainer").contains(tp.job) && ThreadLocalRandom.current().nextBoolean()) plot = t.nextPlot("builder");
        if (plot >= 0) {
            Town.Plot p = t.plots.get(plot);
            p.state = "building";
            p.by = tp.id;
            if (!plugin.builder().startPlot(tp, t, plot)) {
                p.state = "skipped";
                p.by = null;
                plugin.getLogger().info(t.name + ": the " + p.structure + " at " + p.x + " " + p.z + " doesn't fit there, skipping it");
            }
            dirty = true;
            return;
        }
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
            case "guard" -> patrol(tp, t, c, t.radius * 0.8, "on patrol", now);
            case "scout" -> scout(tp, t, m, c, now);
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

    private void patrol(TestPlayer tp, Town t, Location c, double r, String what, long now) {
        double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        double d = r * (0.5 + ThreadLocalRandom.current().nextDouble() * 0.5);
        plugin.players().goTo(tp, new Location(c.getWorld(), c.getX() + Math.cos(a) * d, c.getY(), c.getZ() + Math.sin(a) * d));
        tp.buildStatus = what;
        tp.jobUntil = now + 15_000 + ThreadLocalRandom.current().nextInt(15_000);
    }

    /** Walk out 70-110 blocks, note what's there, come back and tell everyone. */
    private void scout(TestPlayer tp, Town t, Mannequin m, Location c, long now) {
        if (tp.scoutOut == null) {
            double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            double d = 70 + ThreadLocalRandom.current().nextDouble(40);
            tp.scoutOut = new Location(c.getWorld(), c.getX() + Math.cos(a) * d, c.getY(), c.getZ() + Math.sin(a) * d);
            plugin.players().goTo(tp, tp.scoutOut);
            tp.buildStatus = "scouting to the " + TestPlayer.compass((float) Math.toDegrees(Math.atan2(-Math.cos(a), Math.sin(a))));
            tp.jobUntil = now + 90_000;
            return;
        }
        Location l = m.getLocation();
        String biome = l.getWorld().getBiome(l).getKey().getKey().replace('_', ' ');
        float yaw = (float) Math.toDegrees(Math.atan2(-(l.getX() - c.getX()), l.getZ() - c.getZ()));
        tp.scoutReport = tp.ai && tp.model != null ? null : "back from the " + TestPlayer.compass(yaw) + ": it's " + biome + " out there";
        tp.lastResult = "scouted " + Math.round(Town.hdist(l, c)) + " blocks " + TestPlayer.compass(yaw) + " of " + t.name + ": " + biome;
        tp.scoutOut = null;
        plugin.players().goTo(tp, c);
        tp.buildStatus = "heading back to " + t.name;
        tp.jobUntil = now + 90_000;
    }

    /** Walk the farms, harvesting ripe wheat (it's replanted at once). Builds a farm first if there's none. */
    private void farm(TestPlayer tp, Town t, Mannequin m, long now) {
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
                        crop.setAge(0);
                        b.setBlockData(crop);
                        harvested++;
                    }
                }
            }
        }
        if (harvested > 0) {
            m.swingMainHand();
            w.playSound(m.getLocation(), Sound.BLOCK_CROP_BREAK, 1f, 1f);
            tp.lastResult = "harvested " + harvested + " wheat and replanted";
            tp.harvested += harvested;
        }
        tp.buildStatus = harvested > 0 ? "harvesting wheat" : "tending the farm";
        plugin.players().wander(tp, 3);
        tp.jobUntil = now + 20_000;
    }

    private Location builtSpot(Town t, String structure) {
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
            List<String> rows = new ArrayList<>();
            for (Town.Plot p : t.plots) {
                // A build in progress is picked up where it left off after a restart.
                String state = p.state.equals("building") || (p.state.equals("planned") && p.resume) ? "resume" : p.state;
                rows.add(p.structure + "," + p.material + "," + p.x + "," + p.z + "," + p.rot + "," + state
                        + (p.built != null ? "," + p.built.getBlockX() + "," + p.built.getBlockY() + "," + p.built.getBlockZ() : ""));
            }
            s.set("plots", rows);
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
