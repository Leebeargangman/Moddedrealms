package net.moddedrealms.testplayers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * A planned settlement: a town square with a campfire, a well and a town hall, rings of houses, market stalls,
 * farms, lamp posts and guard towers, with dirt roads from every door to the square. The plots are built in a
 * fixed order, so the place grows from a camp into a village and then a town.
 */
final class Town {

    /** Jobs, and what each one builds. Scouts and entertainers don't build. */
    static final Map<String, List<String>> JOB_BUILDS = Map.ofEntries(
            Map.entry("builder", List.of("house", "hut", "townhall", "well", "campfire", "stall", "tower", "lamp", "farm")),
            Map.entry("mayor", List.of("campfire", "well", "townhall")),
            Map.entry("farmer", List.of("farm")),
            Map.entry("trader", List.of("stall")),
            Map.entry("guard", List.of("tower")),
            Map.entry("roadworker", List.of("lamp")),
            Map.entry("scout", List.of()),
            Map.entry("lumberjack", List.of()),
            Map.entry("miner", List.of()),
            Map.entry("entertainer", List.of()),
            // A free player (see Life) doing a bit of everything for its own base, like a normal survival player.
            Map.entry("settler", List.of("campfire", "hut", "house", "farm", "tower", "lamp")));

    static final class Plot {
        final String structure;
        final String material;
        final int x;
        final int z;
        final int rot;          // door facing: 0 north, 1 east, 2 south, 3 west (toward the square)
        String state = "planned"; // planned, building, built, skipped
        String by;              // the test player building it
        boolean resume;         // it was started before: finishing it may reuse its own blocks
        int clears;             // times someone cleared trees off it so it would fit
        long busyUntil;         // someone's clearing it: leave it to them until then
        Location built;         // where it ended up (it can be nudged off the plan)

        Plot(String structure, String material, int x, int z, int rot) {
            this.structure = structure;
            this.material = material;
            this.x = x;
            this.z = z;
            this.rot = rot;
        }
    }

    final String name;
    String world;
    int cx;
    int cz;
    int radius;
    // A team of free players (see Life) rather than a planned town. Until it settles, (cx, cz) is just where
    // the team is (it follows its leader around) and nothing is planned; settling plans a small base there.
    boolean camp;
    boolean settled = true;
    final java.util.Set<String> humans = new java.util.LinkedHashSet<>(); // real players on the team (names)
    final List<Plot> plots = new ArrayList<>();
    final Map<String, String> jobs = new HashMap<>();    // citizen id -> job
    final Map<String, Integer> homes = new HashMap<>();  // citizen id -> plot index of its house
    final Deque<Location[]> roads = new ArrayDeque<>();  // {from, to} waiting to be paved
    String roadBy;                                       // who is paving one now
    String stageAnnounced = "camp";
    long revivedAt = System.currentTimeMillis(); // last time skipped plots were given another go
    // The town mine: a staircase down from (mineX, mineY, mineZ) heading (mineDx, mineDz), mineStep blocks dug.
    int mineX, mineY, mineZ, mineDx, mineDz, mineStep, mineShafts;
    Location storage;                                    // the town's storage chest, once someone has made one
    final List<Location> portals = new ArrayList<>();    // nether portals its explorers know about

    Town(String name, String world, int cx, int cz, int radius) {
        this.name = name;
        this.world = world;
        this.cx = cx;
        this.cz = cz;
        this.radius = radius;
    }

    Location center() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, cx + 0.5, w.getHighestBlockYAt(cx, cz) + 1, cz + 0.5);
    }

    // ------------------------------------------------------------ the plan

    /** Lay out the whole town around (cx, cz). Order is build order, so the essentials come first. */
    void plan() {
        plots.clear();
        int r1 = Math.max(14, (int) (radius * 0.4));
        int r2 = Math.max(24, (int) (radius * 0.65));
        int r3 = Math.max(30, (int) (radius * 0.84));
        String[] woods = {"wood", "spruce", "brick", "cherry", "stone"};
        add("campfire", "wood", 0, 0);
        add("well", "stone", 9, 0);
        add("house", woods[0], ring(r1, 0.5, 8));
        add("farm", "wood", ring(r3, 0.5, 4));
        add("house", woods[1], ring(r1, 1.5, 8));
        add("townhall", "stone", 0, -r1 - 2);
        add("stall", "spruce", ring(r1 - 6, 6.5, 8));
        add("house", woods[2], ring(r1, 3.5, 8));
        for (int i = 0; i < 4; i++) add("lamp", "wood", ring(6, i * 2 + 1, 8));
        add("farm", "wood", ring(r3, 2.5, 4));
        add("house", woods[3], ring(r1, 4.5, 8));
        add("house", woods[4], ring(r1, 5.5, 8));
        add("tower", "stone", ring(r3, 0, 4));
        add("stall", "spruce", ring(r1 - 6, 2.5, 8));
        add("house", woods[0], ring(r1, 7.5, 8));
        for (int i = 0; i < 8; i++) add(i % 3 == 2 ? "hut" : "house", woods[(i + 1) % woods.length], ring(r2, i + 0.25, 8));
        add("farm", "wood", ring(r3, 1.5, 4));
        add("farm", "wood", ring(r3, 3.5, 4));
        add("tower", "stone", ring(r3, 2, 4));
        add("tower", "stone", ring(r3, 1, 4));
        add("tower", "stone", ring(r3, 3, 4));
        for (int i = 0; i < 4; i++) add("lamp", "spruce", ring(r1 - 4, i * 2, 8));
    }

    /**
     * A team's base, the way a few survival players set one up: a campfire, a hut each, then a house, a
     * farm, a lookout tower and some lamps. Smaller than a town and grown in the same way.
     */
    void planCamp(int members) {
        plots.clear();
        String[] woods = {"wood", "spruce", "cherry", "wood"};
        add("campfire", "wood", 0, 0);
        int huts = Math.max(1, Math.min(4, members));
        for (int i = 0; i < huts; i++) add("hut", woods[i], ring(9, i * 2 + 0.5, 8));
        add("farm", "wood", ring(15, 5, 8));
        add("house", "spruce", ring(13, 3, 8));
        add("tower", "stone", ring(16, 7, 8));
        add("lamp", "wood", ring(5, 1, 4));
        add("lamp", "wood", ring(5, 3, 4));
    }

    /** A point on a ring: slot (0..n, fractions allowed) of n evenly spaced slots. */
    private int[] ring(int r, double slot, int n) {
        double a = slot * 2 * Math.PI / n;
        return new int[] {(int) Math.round(Math.cos(a) * r), (int) Math.round(Math.sin(a) * r)};
    }

    private void add(String structure, String material, int[] at) {
        add(structure, material, at[0], at[1]);
    }

    private void add(String structure, String material, int dx, int dz) {
        // Door toward the square.
        int rot = Math.abs(dx) > Math.abs(dz) ? (dx < 0 ? 1 : 3) : (dz < 0 ? 2 : 0);
        plots.add(new Plot(structure, material, cx + dx, cz + dz, rot));
    }

    // ------------------------------------------------------------ progress

    int count(String state) {
        return (int) plots.stream().filter(p -> p.state.equals(state)).count();
    }

    int builtOf(String... structures) {
        List<String> s = List.of(structures);
        return (int) plots.stream().filter(p -> p.state.equals("built") && s.contains(p.structure)).count();
    }

    /** camp, village, town or city, from what's been built. */
    String stage() {
        if (camp) return !settled ? "team on the move" : builtOf("hut", "house") >= 2 ? "base" : "camp";
        int homes = builtOf("house", "hut");
        boolean hall = builtOf("townhall") > 0;
        if (homes >= 12 && hall && builtOf("tower") >= 3) return "city";
        if (homes >= 6 && hall) return "town";
        if (homes >= 2) return "village";
        return "camp";
    }

    /** The next plot this job should build (the plan's order), or -1. Builders take anything. */
    int nextPlot(String job) {
        List<String> mine = JOB_BUILDS.getOrDefault(job, List.of());
        for (int i = 0; i < plots.size(); i++) {
            Plot p = plots.get(i);
            if (p.state.equals("planned") && mine.contains(p.structure) && p.busyUntil < System.currentTimeMillis()) return i;
        }
        return -1;
    }

    void finished(int plot, boolean road, Location stand) {
        if (road) {
            roadBy = null;
            return;
        }
        if (plot < 0 || plot >= plots.size()) return;
        Plot p = plots.get(plot);
        p.state = "built";
        p.by = null;
        p.built = stand;
        // Every door gets a road to the square.
        if (stand != null && List.of("house", "hut", "townhall", "stall", "well").contains(p.structure)) {
            Location c = center();
            if (c != null) roads.add(new Location[] {stand, c});
        }
    }

    void released(int plot, boolean road) {
        if (road) {
            roadBy = null;
            return;
        }
        if (plot >= 0 && plot < plots.size() && plots.get(plot).state.equals("building")) {
            plots.get(plot).state = "planned";
            plots.get(plot).by = null;
        }
    }

    /** A built house or hut nobody lives in yet, for this citizen; -1 if none. */
    int homeFor(String id) {
        Integer have = homes.get(id);
        if (have != null && plots.get(have).state.equals("built")) return have;
        for (int i = 0; i < plots.size(); i++) {
            Plot p = plots.get(i);
            if (p.state.equals("built") && (p.structure.equals("house") || p.structure.equals("hut")) && !homes.containsValue(i)) {
                homes.put(id, i);
                return i;
            }
        }
        return -1;
    }

    static double hdist(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    String summary() {
        if (camp) {
            String who = jobs.size() + " test player" + (jobs.size() == 1 ? "" : "s") + (humans.isEmpty() ? "" : " + " + String.join(", ", humans));
            return name + ": " + who + ", " + (settled ? "base at " + cx + " " + cz + " (" + count("built") + "/" + plots.size() + " built)" : "no base yet");
        }
        return name + ": " + stage() + ", " + jobs.size() + " citizens, " + count("built") + "/" + plots.size() + " buildings"
                + (count("skipped") > 0 ? " (" + count("skipped") + " didn't fit)" : "");
    }
}
