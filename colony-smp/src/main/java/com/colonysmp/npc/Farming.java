package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.model.Building;
import com.colonysmp.model.Colony;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Text;
import com.colonysmp.util.Tools;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Directional;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Farmers work their own farm: harvest ripe crops (the harvest goes into the farm's stock for the haulers),
 * replant with the seeds it gave, re-till trampled tiles and sow empty farmland with seeds from the farm's stock
 * or central storage. The crop mix they find is recorded for the background model.
 */
public final class Farming {

    private record Plan(long at, List<BlockPos> jobs) {}

    /** Seed good -> crop block, in sowing preference order. */
    private static final String[] SEEDS = {"wheat_seeds", "carrot", "potato", "beetroot_seeds"};
    private static final Material[] CROPS = {Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS};

    private final ColonySMP plugin;
    private final NpcManager m;
    private final Map<String, Plan> plans = new HashMap<>();

    Farming(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    void forget(Colony col) {
        plans.keySet().removeIf(k -> k.startsWith(col.id + ":"));
    }

    void think(Npc n, Colony col, Building farm, long now) {
        World w = n.body.getWorld();
        if (farm.tiles.isEmpty()) {
            m.brain().wanderAt(n, col, farm, now, farm.label() + " has no farmland");
            return;
        }
        if (!m.needTool(n, col, farm, Tools.Kind.HOE, now)) return;
        Set<BlockPos> claimed = m.claimed.computeIfAbsent(col.id, k -> new HashSet<>());
        if (n.target != null && !actionable(w, n, col, farm, n.target)) {
            claimed.remove(n.target);
            n.target = null;
        }
        if (n.target == null) {
            n.target = pick(n, col, w, farm, now, claimed);
            if (n.target == null) {
                Location c = farm.anchor != null ? farm.anchor.center(w).add(0, 1, 0) : col.coreLocation();
                if (c != null) m.brain().wander(n, c, 5, now, "Watching the crops grow at " + farm.label());
                return;
            }
            claimed.add(n.target);
        }
        Block b = n.target.block(w);
        // sowing needs seeds: from the farm's own stock, the satchel, or storage
        if (b.getType().isAir()) {
            boolean soulSand = b.getRelative(BlockFace.DOWN).getType() == Material.SOUL_SAND;
            List<String> want = soulSand ? List.of("nether_wart") : seedOrder(col, farm);
            boolean have = false;
            for (String k : want) if (farm.stock.available(k) > 0 || n.c.carried.get(k) > 0) have = true;
            if (!have) {
                NpcManager.Fetch f = m.fetch(n, col, want, 32, soulSand ? "nether wart" : "seeds");
                if (f == NpcManager.Fetch.FETCHING) return;
                if (f == NpcManager.Fetch.NONE) {
                    claimed.remove(n.target);
                    n.target = null;
                    n.doing("Waiting for seeds (none on the farm or in storage)");
                    return;
                }
            }
        }
        Location tl = n.target.center(w);
        if (!n.mover.near(tl, 1.9)) {
            n.mover.moveTo(tl, plugin.settings().walkSpeed, 1.4);
            n.doing("Walking to the fields of " + farm.label());
            return;
        }
        n.mover.stop();
        n.working = true;
        CitizenBrain.face(n, tl);
        if (now < n.actionAt) return;
        double eff = m.efficiency(col, n.c);
        n.actionAt = now + m.interval(plugin.settings().farmInterval, eff, n.c.tool);
        if (ripe(b)) harvest(n, col, farm, b);
        else if (tillable(b)) till(n, col, b);
        else if (b.getType().isAir()) plant(n, col, farm, b);
        n.c.skills.gain(com.colonysmp.core.Skill.FARMING, 0.6 * n.c.trait.learning());
        claimed.remove(n.target);
        n.target = null;
    }

    /** Seeds to sow, the farm's preferred crop first, then whatever there's most of. */
    private List<String> seedOrder(Colony col, Building farm) {
        List<String> out = new ArrayList<>();
        if (farm.crop != null) {
            for (int i = 0; i < CROPS.length; i++) if (CROPS[i].name().toLowerCase().equals(farm.crop)) out.add(SEEDS[i]);
        }
        List<String> rest = new ArrayList<>(List.of(SEEDS));
        rest.removeAll(out);
        rest.sort((a, b) -> Long.compare(have(col, farm, b), have(col, farm, a)));
        out.addAll(rest);
        return out;
    }

    private static long have(Colony col, Building farm, String seed) {
        long n = farm.stock.available(seed) + col.storage.available(seed);
        // carrots and potatoes are food too: keep a few back
        if (seed.equals("carrot") || seed.equals("potato")) n -= 8;
        return n;
    }

    private BlockPos pick(Npc n, Colony col, World w, Building farm, long now, Set<BlockPos> claimed) {
        String key = col.id + ":" + farm.id;
        Plan p = plans.get(key);
        if (p == null || now - p.at > 60 || now < p.at) {
            p = new Plan(now, scan(w, farm));
            plans.put(key, p);
        }
        Location here = n.loc();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        Iterator<BlockPos> it = p.jobs.iterator();
        while (it.hasNext()) {
            BlockPos t = it.next();
            if (claimed.contains(t)) continue;
            if (!actionable(w, n, col, farm, t)) {
                it.remove();
                continue;
            }
            double d = t.distSq(here);
            if (d < bd) {
                bd = d;
                best = t;
            }
        }
        return best;
    }

    /** Tiles that need something done, and a count of what's growing (for the background model). */
    private List<BlockPos> scan(World w, Building farm) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        Map<String, Integer> crops = new TreeMap<>();
        for (BlockPos soil : farm.tiles) {
            if (!soil.loaded(w)) continue;
            Block sb = soil.block(w);
            if (tillable(sb)) {
                if (seen.add(soil)) out.add(soil);
                continue;
            }
            BlockPos up = soil.add(0, 1, 0);
            Block a = up.block(w);
            String kind = crop(a.getType());
            if (kind != null) crops.merge(kind, 1, Integer::sum);
            if (ripe(a) || a.getType().isAir()) {
                if (seen.add(up)) out.add(up);
            } else if (a.getType() == Material.ATTACHED_MELON_STEM || a.getType() == Material.ATTACHED_PUMPKIN_STEM) {
                if (a.getBlockData() instanceof Directional d) {
                    BlockPos fruit = up.relative(d.getFacing());
                    if (ripe(fruit.block(w)) && seen.add(fruit)) out.add(fruit);
                }
            }
        }
        if (!crops.isEmpty()) {
            farm.counts.clear();
            farm.counts.putAll(crops);
        }
        return out;
    }

    private static String crop(Material m) {
        return switch (m) {
            case WHEAT -> "wheat";
            case CARROTS -> "carrots";
            case POTATOES -> "potatoes";
            case BEETROOTS -> "beetroots";
            case NETHER_WART -> "nether_wart";
            default -> null;
        };
    }

    private boolean actionable(World w, Npc n, Colony col, Building farm, BlockPos t) {
        if (!t.loaded(w)) return false;
        Block b = t.block(w);
        if (ripe(b)) return true;
        if (tillable(b)) return true;
        if (!b.getType().isAir()) return false;
        Material soil = b.getRelative(BlockFace.DOWN).getType();
        if (soil == Material.FARMLAND) {
            for (String s : SEEDS) if (farm.stock.available(s) > 0 || n.c.carried.get(s) > 0 || col.storage.available(s) > 0) return true;
            return false;
        }
        if (soil == Material.SOUL_SAND) return farm.stock.available("nether_wart") > 0 || n.c.carried.get("nether_wart") > 0 || col.storage.available("nether_wart") > 0;
        return false;
    }

    /** A farm tile that went back to dirt (dug or trampled) with room above it. */
    public static boolean tillable(Block b) {
        Material m = b.getType();
        if (m != Material.DIRT && m != Material.GRASS_BLOCK && m != Material.DIRT_PATH) return false;
        Block up = b.getRelative(BlockFace.UP);
        return up.getType().isAir() || up.isReplaceable() && !up.isLiquid();
    }

    static boolean ripe(Block b) {
        Material m = b.getType();
        if (m == Material.MELON || m == Material.PUMPKIN) {
            for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block s = b.getRelative(f);
                if ((s.getType() == Material.ATTACHED_MELON_STEM || s.getType() == Material.ATTACHED_PUMPKIN_STEM)
                        && s.getBlockData() instanceof Directional d && d.getFacing() == f.getOppositeFace()) return true;
            }
            return false;
        }
        if (crop(m) == null) return false;
        return b.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge();
    }

    static String seedOf(Material crop) {
        return switch (crop) {
            case WHEAT -> "wheat_seeds";
            case CARROTS -> "carrot";
            case POTATOES -> "potato";
            case BEETROOTS -> "beetroot_seeds";
            case NETHER_WART -> "nether_wart";
            default -> null;
        };
    }

    private void harvest(Npc n, Colony col, Building farm, Block b) {
        World w = b.getWorld();
        Material type = b.getType();
        List<ItemStack> drops = new ArrayList<>(b.getDrops(n.c.tool));
        w.playEffect(b.getLocation(), Effect.STEP_SOUND, type);
        if (type == Material.MELON || type == Material.PUMPKIN) {
            b.setType(Material.AIR);
        } else {
            String seed = seedOf(type);
            boolean replant = seed != null && (takeOne(drops, seed) || farm.stock.take(seed, 1) == 1 || n.c.carried.take(seed, 1) == 1);
            if (replant && b.getBlockData() instanceof Ageable a) {
                a.setAge(0);
                b.setBlockData(a);
            } else {
                b.setType(Material.AIR);
            }
        }
        // irrigation and good growing weather: the same share more as a field worked in the background
        double more = plugin.sim().trades().yieldBonus(col, plugin.sim().day(col));
        if (more > 0) {
            for (ItemStack it : drops) {
                double extra = it.getAmount() * more;
                int add = (int) extra;
                if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < extra - add) add++;
                it.setAmount(it.getAmount() + add);
            }
        }
        m.toolBonus(n, drops);
        m.deposit(col, farm, drops, b.getLocation().add(0.5, 0.5, 0.5));
        m.wearTool(n, col);
        n.doing("Harvesting " + Text.nice(type.name()).toLowerCase() + " at " + farm.label());
    }

    private static boolean takeOne(List<ItemStack> drops, String good) {
        for (ItemStack it : drops) {
            if (NpcManager.good(it.getType()).equals(good) && it.getAmount() > 0) {
                it.setAmount(it.getAmount() - 1);
                if (it.getAmount() <= 0) drops.remove(it);
                return true;
            }
        }
        return false;
    }

    private void till(Npc n, Colony col, Block b) {
        Block up = b.getRelative(BlockFace.UP);
        if (!up.getType().isAir()) up.setType(Material.AIR);
        b.setType(Material.FARMLAND);
        Fx.sound(b.getLocation(), "minecraft:item.hoe.till", 0.8f, 1f);
        m.wearTool(n, col);
        n.doing("Re-tilling the fields");
    }

    private void plant(Npc n, Colony col, Building farm, Block b) {
        Material soil = b.getRelative(BlockFace.DOWN).getType();
        Material crop = null;
        if (soil == Material.SOUL_SAND) {
            if (farm.stock.take("nether_wart", 1) == 1 || n.c.carried.take("nether_wart", 1) == 1) crop = Material.NETHER_WART;
        } else if (soil == Material.FARMLAND) {
            for (String s : seedOrder(col, farm)) {
                if (farm.stock.take(s, 1) == 1 || n.c.carried.take(s, 1) == 1) {
                    for (int i = 0; i < SEEDS.length; i++) if (SEEDS[i].equals(s)) crop = CROPS[i];
                    break;
                }
            }
        }
        if (crop == null) return;
        b.setType(crop);
        Fx.sound(b.getLocation(), "minecraft:item.crop.plant", 0.8f, 1f);
        n.doing("Sowing " + Text.nice(crop.name()).toLowerCase());
    }
}
