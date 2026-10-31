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
    static final Map<String, List<String>> JOB_BUILDS = Map.of(
            "builder", List.of("house", "hut", "townhall", "well", "campfire", "stall", "tower", "lamp", "farm"),
            "mayor", List.of("campfire", "well", "townhall"),
            "farmer", List.of("farm"),
            "trader", List.of("stall"),
            "guard", List.of("tower"),
            "roadworker", List.of("lamp"),
            "scout", List.of(),
            "entertainer", List.of());

    static final class Plot {
        final String structure;
        final String material;
        final int x;
        final int z;
        final int rot;          // door facing: 0 north, 1 east, 2 south, 3 west (toward the square)
        String state = "planned"; // planned, building, built, skipped
        String by;              // the test player building it
        boolean resume;         // it was started before: finishing it may reuse its own blocks
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
    final String world;
    final int cx;
    final int cz;
    final int radius;
    final List<Plot> plots = new ArrayList<>();
    final Map<String, String> jobs = new HashMap<>();    // citizen id -> job
    final Map<String, Integer> homes = new HashMap<>();  // citizen id -> plot index of its house
    final Deque<Location[]> roads = new ArrayDeque<>();  // {from, to} waiting to be paved
    String roadBy;                                       // who is paving one now
    String stageAnnounced = "camp";

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
            if (p.state.equals("planned") && mine.contains(p.structure)) return i;
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
        return name + ": " + stage() + ", " + jobs.size() + " citizens, " + count("built") + "/" + plots.size() + " buildings"
                + (count("skipped") > 0 ? " (" + count("skipped") + " didn't fit)" : "");
    }
}
