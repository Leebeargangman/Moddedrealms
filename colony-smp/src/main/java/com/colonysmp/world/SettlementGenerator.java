package com.colonysmp.world;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.civ.Civ;
import com.colonysmp.civ.CivWorld;
import com.colonysmp.civ.Settlement;
import com.colonysmp.civ.SettlementType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Decides where settlements appear. The map is cut into squares; each square is considered exactly once, for
 * ever (the decision is saved), so walking the same ground again never makes more settlements. A square is only
 * considered once it has been explored, a settlement is only placed out of sight of every player, and the same
 * player isn't used to pick a site twice within the hour.
 */
public final class SettlementGenerator {

    private final ColonySMP plugin;
    private final CivManager civs;
    private final Map<UUID, Long> lastFor = new HashMap<>();
    private final Random rng = new Random();

    SettlementGenerator(ColonySMP plugin, CivManager civs) {
        this.plugin = plugin;
        this.civs = civs;
    }

    private boolean allowed(World w) {
        List<String> worlds = plugin.settings().civs.worlds;
        if (w.getEnvironment() != World.Environment.NORMAL) return false;
        return worlds.isEmpty() ? w.equals(Bukkit.getWorlds().get(0)) : worlds.contains(w.getName());
    }

    static String key(World w, int rx, int rz) {
        return w.getName().replace('.', '_') + ":" + rx + ":" + rz;
    }

    /** Every few minutes: at most one new settlement, in one unconsidered square near one player. */
    public void consider() {
        Settings.Civs cfg = plugin.settings().civs;
        CivWorld world = civs.world();
        if (world.alive().size() >= cfg.maxSettlements) return;
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        java.util.Collections.shuffle(players, rng);
        long now = System.currentTimeMillis();
        for (Player p : players) {
            if (!allowed(p.getWorld()) || now - lastFor.getOrDefault(p.getUniqueId(), 0L) < 3_600_000L) continue;
            World w = p.getWorld();
            int prx = Math.floorDiv(p.getLocation().getBlockX(), cfg.spacing), prz = Math.floorDiv(p.getLocation().getBlockZ(), cfg.spacing);
            List<int[]> cells = new ArrayList<>();
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (!world.regions.containsKey(key(w, prx + dx, prz + dz))) cells.add(new int[]{prx + dx, prz + dz});
            java.util.Collections.shuffle(cells, rng);
            for (int[] cell : cells) {
                if (tryCell(w, cell[0], cell[1])) {
                    lastFor.put(p.getUniqueId(), now);
                    return;
                }
            }
        }
    }

    /** Considers one square. True if a settlement was founded. */
    private boolean tryCell(World w, int rx, int rz) {
        Settings.Civs cfg = plugin.settings().civs;
        CivWorld world = civs.world();
        // whether this square holds anything, and where in it, is fixed by the world's seed: the same on every visit
        Random cell = new Random(world.seed ^ (rx * 341873128712L) ^ (rz * 132897987541L) ^ w.getName().hashCode());
        String key = key(w, rx, rz);
        if (cell.nextDouble() >= cfg.density) {
            world.regions.put(key, "empty");
            return false;
        }
        int x = rx * cfg.spacing + cfg.spacing / 4 + cell.nextInt(cfg.spacing / 2), z = rz * cfg.spacing + cfg.spacing / 4 + cell.nextInt(cfg.spacing / 2);
        // only on ground that exists already and that nobody is looking at
        for (int dx = -32; dx <= 32; dx += 16) {
            for (int dz = -32; dz <= 32; dz += 16) {
                if (cfg.generatedChunksOnly && !w.isChunkGenerated((x + dx) >> 4, (z + dz) >> 4)) return false; // not explored yet: ask again later
            }
        }
        for (Player p : w.getPlayers()) {
            double px = p.getLocation().getX() - x, pz = p.getLocation().getZ() - z;
            if (px * px + pz * pz < (double) cfg.minFromPlayers * cfg.minFromPlayers) return false;
        }
        boolean loaded = true;
        for (int dx = -24; dx <= 24; dx += 8) for (int dz = -24; dz <= 24; dz += 8) if (!w.isChunkLoaded((x + dx) >> 4, (z + dz) >> 4)) loaded = false;
        if (!loaded) {
            // fetch the ground, and judge it on a later look
            for (int cx = (x - 24) >> 4; cx <= (x + 24) >> 4; cx++) for (int cz = (z - 24) >> 4; cz <= (z + 24) >> 4; cz++) w.getChunkAtAsync(cx, cz);
            return false;
        }
        String problem = civs.builder().siteProblem(w, x, z, false);
        if (problem != null) {
            world.regions.put(key, "unfit: " + problem);
            return false;
        }
        SettlementType type = typeFor(w, x, z, cell);
        Civ civ = null;
        if (type != SettlementType.BANDIT_CAMP && cell.nextDouble() < 0.35) {
            // a daughter settlement of a people already near
            for (Civ c : world.civs.values()) {
                if (c.outlaw || world.of(c.id).size() >= cfg.maxPerCiv || world.of(c.id).isEmpty()) continue;
                Settlement cap = world.capital(c.id);
                if (cap != null && cap.world.equals(w.getName()) && Math.hypot(cap.x - x, cap.z - z) < 1500) civ = c;
            }
        }
        Settlement s = civs.found(civ, type, new Location(w, x, SettlementBuilder.ground(w, x, z) + 1, z), cell);
        world.regions.put(key, s.id);
        Civ owner = world.civs.get(s.civ);
        world.news(s.id, s.civ, 2, "Word has come of " + s.name + ", a " + type.noun + " of " + owner.name + " known for " + type.speciality() + ".");
        plugin.getLogger().info("A settlement appears: " + s.name + " (" + type.display + ", " + owner.name + ") at " + x + ", " + z + " in " + w.getName());
        return true;
    }

    /** What kind of settlement suits the land, leaning towards kinds the world doesn't have yet. */
    public SettlementType typeFor(World w, int x, int z, Random r) {
        String biome = w.getBiome(x, SettlementBuilder.ground(w, x, z), z).getKey().getKey();
        int water = 0, stone = 0, n = 0;
        for (int dx = -40; dx <= 40; dx += 10) {
            for (int dz = -40; dz <= 40; dz += 10) {
                if (!w.isChunkLoaded((x + dx) >> 4, (z + dz) >> 4)) continue;
                int y = SettlementBuilder.ground(w, x + dx, z + dz);
                Material above = w.getBlockAt(x + dx, y + 1, z + dz).getType(), at = w.getBlockAt(x + dx, y, z + dz).getType();
                if (above == Material.WATER) water++;
                if (org.bukkit.Tag.BASE_STONE_OVERWORLD.isTagged(at) || at == Material.GRAVEL) stone++;
                n++;
            }
        }
        Map<SettlementType, Double> weight = new EnumMap<>(SettlementType.class);
        for (SettlementType t : SettlementType.values()) weight.put(t, 1.0);
        boolean wet = n > 0 && water * 5 >= n || biome.contains("beach") || biome.contains("river") || biome.contains("ocean") || biome.contains("swamp");
        boolean high = n > 0 && stone * 4 >= n || biome.contains("peak") || biome.contains("windswept") || biome.contains("stony") || biome.contains("slope") || biome.contains("hill");
        boolean dry = biome.contains("desert") || biome.contains("badlands") || biome.contains("savanna");
        boolean woods = biome.contains("forest") || biome.contains("taiga") || biome.contains("jungle") || biome.contains("grove");
        weight.merge(SettlementType.FISHING_VILLAGE, wet ? 6.0 : 0.0, (a, b) -> b);
        weight.merge(SettlementType.COASTAL_SETTLEMENT, wet ? 5.0 : 0.0, (a, b) -> b);
        weight.merge(SettlementType.MINING_SETTLEMENT, high ? 7.0 : 0.6, (a, b) -> b);
        weight.merge(SettlementType.FARMING_VILLAGE, wet || high || dry ? 0.5 : woods ? 1.5 : 5.0, (a, b) -> b);
        weight.merge(SettlementType.TRADING_TOWN, dry ? 4.0 : 1.5, (a, b) -> b);
        weight.merge(SettlementType.FRONTIER_OUTPOST, woods ? 4.0 : 1.0, (a, b) -> b);
        weight.merge(SettlementType.ISOLATED_COMMUNITY, woods || high ? 3.0 : 0.8, (a, b) -> b);
        weight.merge(SettlementType.RELIGIOUS_SETTLEMENT, high || woods ? 2.0 : 1.0, (a, b) -> b);
        weight.merge(SettlementType.BANDIT_CAMP, dry || woods ? 1.6 : 0.8, (a, b) -> b);
        weight.merge(SettlementType.INDUSTRIAL_TOWN, high ? 2.0 : 1.2, (a, b) -> b);
        weight.merge(SettlementType.FORTIFIED_KINGDOM, wet ? 0.5 : 1.4, (a, b) -> b);
        weight.merge(SettlementType.MILITARY_SETTLEMENT, 1.0, (a, b) -> b);
        // a world wants one of everything before it wants two of anything
        Map<SettlementType, Integer> have = new EnumMap<>(SettlementType.class);
        for (Settlement s : civs.world().alive()) have.merge(s.type, 1, Integer::sum);
        double total = 0;
        for (SettlementType t : SettlementType.values()) {
            double v = weight.get(t) / (1 + 1.5 * have.getOrDefault(t, 0));
            weight.put(t, v);
            total += v;
        }
        double pick = r.nextDouble() * total;
        for (SettlementType t : SettlementType.values()) {
            pick -= weight.get(t);
            if (pick <= 0) return t;
        }
        return SettlementType.FARMING_VILLAGE;
    }

    /** A thriving civilisation founds an outpost: somewhere fit, explored and loaded, a day's walk from its seat. */
    public void outpost(String civId) {
        Settings.Civs cfg = plugin.settings().civs;
        CivWorld world = civs.world();
        Civ civ = world.civs.get(civId);
        Settlement cap = world.capital(civId);
        World w = cap == null ? null : Bukkit.getWorld(cap.world);
        if (civ == null || w == null) return;
        for (int i = 0; i < 16; i++) {
            double ang = rng.nextDouble() * Math.PI * 2, dist = cfg.spacing * (0.7 + rng.nextDouble() * 0.6);
            int x = cap.x + (int) (Math.cos(ang) * dist), z = cap.z + (int) (Math.sin(ang) * dist);
            boolean ready = true;
            for (int dx = -24; dx <= 24 && ready; dx += 8) for (int dz = -24; dz <= 24; dz += 8) if (!w.isChunkLoaded((x + dx) >> 4, (z + dz) >> 4)) ready = false;
            if (!ready) continue;
            boolean seen = false;
            for (Player p : w.getPlayers()) if (Math.hypot(p.getLocation().getX() - x, p.getLocation().getZ() - z) < cfg.minFromPlayers) seen = true;
            if (seen || civs.builder().siteProblem(w, x, z, false) != null) continue;
            SettlementType type = rng.nextInt(3) == 0 ? SettlementType.FRONTIER_OUTPOST : typeFor(w, x, z, rng);
            if (type == SettlementType.BANDIT_CAMP || type == SettlementType.FORTIFIED_KINGDOM) type = SettlementType.FRONTIER_OUTPOST;
            Settlement s = civs.found(civ, type, new Location(w, x, SettlementBuilder.ground(w, x, z) + 1, z), rng);
            world.regions.put(key(w, Math.floorDiv(x, cfg.spacing), Math.floorDiv(z, cfg.spacing)), s.id);
            civ.record(world.day, civ.ruler() + " sent settlers to found " + s.name + ".");
            world.news(s.id, civ.id, 2, civ.name + " has founded a new " + type.noun + ", " + s.name + ".");
            return;
        }
    }
}
