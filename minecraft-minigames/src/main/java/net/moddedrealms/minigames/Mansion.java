package net.moddedrealms.minigames;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Stairs;

/**
 * The Murder Mystery map: a one-floor mansion of eight rooms (library, ballroom, kitchen, dining room, great
 * hall, bedroom, garden, study) with doorways between them so there are loops to run around. It is drawn on a
 * grid, and the same grid is what bots find their way on.
 */
final class Mansion {

    static final int W = 45, H = 35;
    static final char WALL = '#', HALL = '.', WOOD = ',', CARPET = ':', GRASS = ';',
            SHELF = 'B', TABLE = 'T', CHAIR = 'C', PILLAR = 'P', HEDGE = 'h', STATUE = 'S', BED = 'b', COUNTER = 'K';

    /** The plan, [x][z]. */
    static final char[][] PLAN = plan();
    /** Room centres, for bots to wander between. */
    static final int[][] ROOMS = {
            {7, 5}, {22, 5}, {37, 4}, {7, 14}, {22, 13}, {37, 18}, {11, 25}, {30, 30}, {19, 20}, {25, 20}, {4, 32}, {41, 27}, {16, 29}, {38, 8}};

    private Mansion() {
    }

    private static char[][] plan() {
        char[][] g = new char[W][H];
        for (char[] col : g) Arrays.fill(col, WALL);
        room(g, 1, 1, 13, 10, CARPET);    // library
        room(g, 15, 1, 29, 10, HALL);     // ballroom
        room(g, 31, 1, 43, 10, WOOD);     // kitchen
        room(g, 1, 12, 13, 22, WOOD);     // dining room
        room(g, 15, 12, 29, 22, HALL);    // great hall
        room(g, 31, 12, 43, 22, CARPET);  // bedroom
        room(g, 1, 24, 21, 33, GRASS);    // garden
        room(g, 23, 24, 43, 33, WOOD);    // study
        // Doorways (2-3 wide)
        door(g, 14, 5, 14, 6, CARPET);
        door(g, 30, 5, 30, 6, WOOD);
        door(g, 6, 11, 7, 11, WOOD);
        door(g, 21, 11, 23, 11, HALL);
        door(g, 37, 11, 38, 11, CARPET);
        door(g, 14, 16, 14, 18, WOOD);
        door(g, 30, 16, 30, 18, HALL);
        door(g, 6, 23, 7, 23, GRASS);
        door(g, 18, 23, 19, 23, HALL);
        door(g, 26, 23, 27, 23, WOOD);
        door(g, 38, 23, 39, 23, WOOD);
        door(g, 22, 29, 22, 30, GRASS);
        // Library shelves
        fill(g, 3, 3, 6, 4, SHELF);
        fill(g, 8, 3, 11, 4, SHELF);
        fill(g, 3, 7, 6, 8, SHELF);
        fill(g, 8, 7, 11, 8, SHELF);
        // Ballroom pillars
        for (int[] p : new int[][] {{17, 3}, {27, 3}, {17, 8}, {27, 8}}) g[p[0]][p[1]] = PILLAR;
        // Kitchen counters and island
        fill(g, 31, 1, 36, 1, COUNTER);
        fill(g, 39, 1, 43, 1, COUNTER);
        fill(g, 35, 5, 39, 6, COUNTER);
        // Dining table and chairs
        fill(g, 4, 16, 10, 18, TABLE);
        for (int x = 4; x <= 10; x += 2) {
            g[x][15] = CHAIR;
            g[x][19] = CHAIR;
        }
        // Great hall pillars and statue
        for (int[] p : new int[][] {{17, 14}, {27, 14}, {17, 20}, {27, 20}}) g[p[0]][p[1]] = PILLAR;
        fill(g, 21, 16, 23, 18, STATUE);
        // Bedroom beds and wardrobe
        fill(g, 33, 13, 34, 15, BED);
        fill(g, 40, 13, 41, 15, BED);
        fill(g, 43, 19, 43, 21, SHELF);
        // Garden hedges
        fill(g, 3, 26, 9, 26, HEDGE);
        fill(g, 13, 26, 19, 26, HEDGE);
        fill(g, 3, 31, 9, 31, HEDGE);
        fill(g, 13, 31, 19, 31, HEDGE);
        fill(g, 11, 27, 11, 30, HEDGE);
        // Study desk and shelves
        fill(g, 26, 27, 29, 27, TABLE);
        fill(g, 24, 33, 36, 33, SHELF);
        fill(g, 40, 33, 42, 33, SHELF);
        fill(g, 33, 26, 34, 30, SHELF);
        return g;
    }

    private static void room(char[][] g, int x1, int z1, int x2, int z2, char floor) {
        fill(g, x1, z1, x2, z2, floor);
    }

    private static void door(char[][] g, int x1, int z1, int x2, int z2, char floor) {
        fill(g, x1, z1, x2, z2, floor);
    }

    private static void fill(char[][] g, int x1, int z1, int x2, int z2, char c) {
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) g[x][z] = c;
    }

    static boolean walkable(int x, int z) {
        if (x < 0 || z < 0 || x >= W || z >= H) return false;
        char c = PLAN[x][z];
        return c == HALL || c == WOOD || c == CARPET || c == GRASS;
    }

    // ------------------------------------------------------------ building it

    static void build(Location o) {
        World w = o.getWorld();
        int ox = o.getBlockX(), oy = o.getBlockY(), oz = o.getBlockZ();
        for (int x = -2; x < W + 2; x++) {
            for (int z = -2; z < H + 2; z++) {
                for (int y = oy - 1; y <= oy + 7; y++) w.getBlockAt(ox + x, y, oz + z).setType(Material.AIR, false);
            }
        }
        for (int x = 0; x < W; x++) {
            for (int z = 0; z < H; z++) {
                char c = PLAN[x][z];
                int bx = ox + x, bz = oz + z;
                w.getBlockAt(bx, oy - 1, bz).setType(Material.STONE, false);
                w.getBlockAt(bx, oy, bz).setType(floor(c, x, z), false);
                boolean garden = x >= 1 && x <= 21 && z >= 24 && z <= 33;
                switch (c) {
                    case WALL -> {
                        boolean outer = x == 0 || z == 0 || x == W - 1 || z == H - 1;
                        for (int y = 1; y <= 4; y++) {
                            Material m = y == 1 ? Material.STONE_BRICKS : y == 4 ? Material.DARK_OAK_PLANKS : Material.SPRUCE_PLANKS;
                            if (outer && y == 2 && (x + z) % 4 == 0 && x > 0 && z > 0 && x < W - 1 && z < H - 1) m = Material.GLASS;
                            if (outer && (y == 2 || y == 3) && ((x % 5 == 2 && (z == 0 || z == H - 1)) || (z % 5 == 2 && (x == 0 || x == W - 1)))) m = Material.GLASS_PANE;
                            if (!outer && isCorner(x, z)) m = Material.STRIPPED_DARK_OAK_LOG;
                            if (garden && !outer && y >= 3) m = Material.AIR;
                            w.getBlockAt(bx, oy + y, bz).setType(m, false);
                        }
                    }
                    case SHELF -> column(w, bx, oy, bz, Material.BOOKSHELF, 2);
                    case PILLAR -> column(w, bx, oy, bz, Material.QUARTZ_PILLAR, 4);
                    case HEDGE -> column(w, bx, oy, bz, Material.OAK_LEAVES, 2);
                    case STATUE -> {
                        boolean mid = x == 22 && z == 17;
                        w.getBlockAt(bx, oy + 1, bz).setType(Material.CHISELED_STONE_BRICKS, false);
                        if (mid) {
                            w.getBlockAt(bx, oy + 2, bz).setType(Material.STONE_BRICK_WALL, false);
                            w.getBlockAt(bx, oy + 3, bz).setType(Material.CARVED_PUMPKIN, false);
                        } else {
                            w.getBlockAt(bx, oy + 2, bz).setType(Material.POTTED_RED_TULIP, false);
                        }
                    }
                    case TABLE -> {
                        w.getBlockAt(bx, oy + 1, bz).setType(Material.DARK_OAK_PLANKS, false);
                        if ((x + z) % 3 == 0) w.getBlockAt(bx, oy + 2, bz).setType(Material.CANDLE, false);
                    }
                    case COUNTER -> {
                        w.getBlockAt(bx, oy + 1, bz).setType((x % 3 == 0) ? Material.SMOKER : Material.POLISHED_ANDESITE, false);
                    }
                    case CHAIR -> {
                        Block b = w.getBlockAt(bx, oy + 1, bz);
                        b.setType(Material.SPRUCE_STAIRS, false);
                        BlockData d = b.getBlockData();
                        if (d instanceof Stairs s) {
                            s.setFacing(z == 15 ? BlockFace.NORTH : BlockFace.SOUTH);
                            s.setHalf(Bisected.Half.BOTTOM);
                            b.setBlockData(s, false);
                        }
                    }
                    case BED -> w.getBlockAt(bx, oy + 1, bz).setType((z == 13) ? Material.WHITE_WOOL : Material.RED_WOOL, false);
                    default -> {
                    }
                }
                // Ceiling, with lights; the garden is open to the sky.
                if (!garden || c == WALL && (x == 0 || z == H - 1 || x == 22 || z == 23)) {
                    boolean light = c != WALL && x % 5 == 2 && z % 5 == 2;
                    w.getBlockAt(bx, oy + 5, bz).setType(light ? Material.GLOWSTONE : Material.DARK_OAK_PLANKS, false);
                    if (light) {
                        Block lb = w.getBlockAt(bx, oy + 4, bz);
                        if (lb.getType() == Material.AIR) {
                            lb.setType(Material.LANTERN, false);
                            if (lb.getBlockData() instanceof Lantern ln) {
                                ln.setHanging(true);
                                lb.setBlockData(ln, false);
                            }
                        }
                    }
                }
            }
        }
        // Garden lights and flowers
        Material[] flowers = {Material.POPPY, Material.DANDELION, Material.CORNFLOWER, Material.OXEYE_DAISY, Material.ALLIUM};
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int x = 1; x <= 21; x++) {
            for (int z = 24; z <= 33; z++) {
                if (PLAN[x][z] != GRASS) continue;
                if ((x == 2 || x == 20) && (z == 25 || z == 32)) {
                    w.getBlockAt(ox + x, oy + 1, oz + z).setType(Material.DARK_OAK_FENCE, false);
                    w.getBlockAt(ox + x, oy + 2, oz + z).setType(Material.LANTERN, false);
                    continue;
                }
                if (r.nextInt(9) == 0) w.getBlockAt(ox + x, oy + 1, oz + z).setType(flowers[r.nextInt(flowers.length)], false);
            }
        }
        // A roof edge so it looks like a house from the void.
        for (int x = -1; x <= W; x++) {
            for (int z = -1; z <= H; z++) {
                if (x == -1 || z == -1 || x == W || z == H) w.getBlockAt(ox + x, oy + 5, oz + z).setType(Material.DARK_OAK_SLAB, false);
            }
        }
    }

    private static boolean isCorner(int x, int z) {
        int n = 0;
        if (x > 0 && PLAN[x - 1][z] == WALL) n++;
        if (x < W - 1 && PLAN[x + 1][z] == WALL) n++;
        boolean h = n > 0;
        int m = 0;
        if (z > 0 && PLAN[x][z - 1] == WALL) m++;
        if (z < H - 1 && PLAN[x][z + 1] == WALL) m++;
        return h && m > 0;
    }

    private static void column(World w, int x, int oy, int z, Material m, int height) {
        for (int y = 1; y <= height; y++) w.getBlockAt(x, oy + y, z).setType(m, false);
    }

    private static Material floor(char c, int x, int z) {
        return switch (c) {
            case HALL -> ((x + z) & 1) == 0 ? Material.POLISHED_ANDESITE : Material.SMOOTH_STONE;
            case WOOD -> Material.SPRUCE_PLANKS;
            case CARPET -> Material.DARK_OAK_PLANKS;
            case GRASS -> Material.GRASS_BLOCK;
            case SHELF, TABLE, CHAIR, BED -> Material.DARK_OAK_PLANKS;
            default -> Material.STONE_BRICKS;
        };
    }

    // ------------------------------------------------------------ finding the way (bots)

    static int[] cell(Location o, Location at) {
        return new int[] {(int) Math.floor(at.getX()) - o.getBlockX(), (int) Math.floor(at.getZ()) - o.getBlockZ()};
    }

    static Location center(Location o, int x, int z) {
        return new Location(o.getWorld(), o.getBlockX() + x + 0.5, o.getBlockY() + 1, o.getBlockZ() + z + 0.5);
    }

    static int[] randomFloor() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            int x = r.nextInt(W), z = r.nextInt(H);
            if (walkable(x, z) && open(x, z)) return new int[] {x, z};
        }
        return ROOMS[0];
    }

    /** Floor with floor all around it (not hugging a wall): nicer spots for gold and spawns. */
    static boolean open(int x, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (!walkable(x + dx, z + dz)) return false;
        return true;
    }

    /** The walkable cell nearest to (x, z). */
    static int[] nearestWalkable(int x, int z) {
        if (walkable(x, z)) return new int[] {x, z};
        for (int r = 1; r < 6; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    if (walkable(x + dx, z + dz)) return new int[] {x + dx, z + dz};
                }
            }
        }
        return null;
    }

    /** A* over the grid (8 directions, no cutting corners). Cells from start (excluded) to goal, or null. */
    static List<int[]> path(int sx, int sz, int gx, int gz) {
        int[] s = nearestWalkable(sx, sz), g = nearestWalkable(gx, gz);
        if (s == null || g == null) return null;
        sx = s[0];
        sz = s[1];
        gx = g[0];
        gz = g[1];
        int n = W * H;
        double[] cost = new double[n];
        Arrays.fill(cost, Double.MAX_VALUE);
        int[] from = new int[n];
        Arrays.fill(from, -1);
        boolean[] done = new boolean[n];
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        int start = sx * H + sz, goal = gx * H + gz;
        cost[start] = 0;
        open.add(new double[] {h(sx, sz, gx, gz), start});
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        while (!open.isEmpty()) {
            int cur = (int) open.poll()[1];
            if (done[cur]) continue;
            done[cur] = true;
            if (cur == goal) break;
            int cx = cur / H, cz = cur % H;
            for (int[] d : dirs) {
                int nx = cx + d[0], nz = cz + d[1];
                if (!walkable(nx, nz)) continue;
                if (d[0] != 0 && d[1] != 0 && (!walkable(cx + d[0], cz) || !walkable(cx, cz + d[1]))) continue;
                int ni = nx * H + nz;
                // Prefer the middle of corridors to hugging walls.
                double step = (d[0] != 0 && d[1] != 0 ? 1.414 : 1.0) + (open(nx, nz) ? 0 : 0.35);
                double c = cost[cur] + step;
                if (c < cost[ni]) {
                    cost[ni] = c;
                    from[ni] = cur;
                    open.add(new double[] {c + h(nx, nz, gx, gz), ni});
                }
            }
        }
        if (from[goal] < 0 && goal != start) return null;
        List<int[]> out = new ArrayList<>();
        for (int c = goal; c != start && c >= 0; c = from[c]) out.add(0, new int[] {c / H, c % H});
        return out;
    }

    private static double h(int x, int z, int gx, int gz) {
        int dx = Math.abs(x - gx), dz = Math.abs(z - gz);
        return Math.max(dx, dz) + 0.414 * Math.min(dx, dz);
    }

    /** Can someone walk in a straight line from a to b (cell centres) without touching a non-floor cell? */
    static boolean straight(double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az;
        double d = Math.hypot(dx, dz);
        int steps = (int) Math.ceil(d / 0.25);
        for (int i = 0; i <= steps; i++) {
            double x = ax + dx * i / Math.max(1, steps), z = az + dz * i / Math.max(1, steps);
            // a body is 0.6 wide: check both sides of the line
            for (double off : new double[] {-0.32, 0.32}) {
                double px = x + (d > 0 ? -dz / d * off : 0), pz = z + (d > 0 ? dx / d * off : 0);
                if (!walkable((int) Math.floor(px), (int) Math.floor(pz))) return false;
            }
        }
        return true;
    }

    /** Path length in cells between two cells (for the murderer picking the nearest victim). */
    static int distance(int ax, int az, int bx, int bz) {
        List<int[]> p = path(ax, az, bx, bz);
        return p == null ? 999 : p.size();
    }
}
