package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.building.RoomScanner;
import com.colonysmp.core.GameTime;
import com.colonysmp.core.Research;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Feature;
import com.colonysmp.sim.Trades;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.Beehive;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The rarer trades in body: researchers at their lecterns, bards playing at the tavern, druids walking the
 * fields, enchanters at their table and beekeepers among the hives. The rules of what their work produces are
 * the same as in the background ({@link com.colonysmp.sim.Academy}, {@link Trades}).
 */
public final class Specialists {

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    /** A pentatonic scale in note-block pitches, so whatever a bard plays next sounds like a tune. */
    private static final float[] SCALE = {0.5f, 0.561f, 0.667f, 0.749f, 0.841f, 1.0f, 1.122f, 1.335f, 1.498f, 1.682f, 2.0f};
    /** Jingle Bells, in note-block steps above F sharp: the bard's tune through the Christmas season. */
    private static final int[] CAROL = {10, 10, 10, -1, 10, 10, 10, -1, 10, 13, 6, 8, 10, -1, -1, 11, 11, 11, 11, 11, 10, 10, 10, 10, 8, 8, 10, 8, -1, 13, -1};
    private static final String[] INSTRUMENTS = {"minecraft:block.note_block.harp", "minecraft:block.note_block.guitar", "minecraft:block.note_block.flute"};

    private final ColonySMP plugin;
    private final NpcManager m;

    Specialists(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    /** Walks to the worker's station; true once standing at it. */
    private boolean atStation(Npc n, Building b, String going) {
        World w = n.body.getWorld();
        Location spot = m.brain().stations.spot(n, w, b);
        if (spot == null) return true;
        if (!n.mover.near(spot, 1.6)) {
            n.mover.moveTo(spot, plugin.settings().walkSpeed, 1.1);
            n.doing(going);
            return false;
        }
        n.mover.stop();
        BlockPos st = m.brain().stations.station(n, b);
        if (st != null) CitizenBrain.face(n, st.center(w));
        return true;
    }

    // ───────────── researchers ─────────────

    void research(Npc n, Colony col, Building uni, long now) {
        Research r = col.researching;
        if (r == null) {
            m.brain().stations.attend(n, col, uni, now, "No research chosen: waiting at " + uni.label());
            return;
        }
        if (uni.level < r.tier) {
            m.brain().stations.attend(n, col, uni, now, r.display + " needs a level " + r.tier + " University");
            return;
        }
        if (!atStation(n, uni, "Going to study at " + uni.label())) return;
        n.working = true;
        n.doing("Researching " + r.display + " (" + Math.round(plugin.sim().academy().progress(col) * 100) + "%)");
        if (now < n.actionAt) return;
        long ticks = Math.round(plugin.settings().stationInterval * 20);
        n.actionAt = now + ticks;
        plugin.sim().academy().work(col, n.c, GameTime.hours(ticks) * m.efficiency(col, n.c));
        World w = n.body.getWorld();
        BlockPos st = m.brain().stations.station(n, uni);
        Location at = st != null && st.loaded(w) ? st.center(w).add(0, 1.1, 0) : n.loc().add(0, 1.6, 0);
        w.spawnParticle(Particle.ENCHANT, at, 10, 0.4, 0.4, 0.4, 0.6);
        if (ThreadLocalRandom.current().nextInt(4) == 0) Fx.sound(at, "minecraft:item.book.page_turn", 0.6f, 1f);
    }

    // ───────────── bards ─────────────

    /** Plays at the tavern (in working hours and through the evening). */
    void perform(Npc n, Colony col, Building tavern, long now) {
        if (tavern == null || !tavern.valid) {
            m.brain().idle(n, col, now, "No tavern to play at");
            return;
        }
        // beside the note block or jukebox if it can be reached, otherwise wherever the tavern's work is done
        World world = n.body.getWorld();
        BlockPos music = null;
        for (BlockPos p : tavern.stations) {
            if (p.loaded(world) && Feature.MUSIC.matches(p.block(world).getType())) {
                music = p;
                break;
            }
        }
        if (music != null) {
            Location spot = beside(world, music);
            if (!n.mover.near(spot, 1.8)) {
                n.mover.moveTo(spot, plugin.settings().walkSpeed, 1.2);
                n.doing("Going to play at " + tavern.label());
                return;
            }
            n.mover.stop();
            CitizenBrain.face(n, music.center(world));
        } else if (!atStation(n, tavern, "Going to play at " + tavern.label())) return;
        n.working = true;
        boolean carols = plugin.christmas().decorated(col);
        n.doing((carols ? "Playing carols at " : "Playing music at ") + tavern.label());
        if (now < n.actionAt) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location at = n.loc();
        World w = n.body.getWorld();
        if (carols) {
            // a carol, note by note (a rest where the tune has one)
            n.actionAt = now + 10;
            n.workIdx = Math.floorMod(n.workIdx + 1, CAROL.length);
            int step = CAROL[n.workIdx];
            if (step < 0) return;
            Fx.sound(at, "minecraft:block.note_block.bell", 1.4f, (float) Math.pow(2, (step - 12) / 12.0));
        } else {
            n.actionAt = now + 10 + r.nextInt(3) * 10;
            // step up or down the scale from the last note
            n.workIdx = Math.max(0, Math.min(SCALE.length - 1, n.workIdx + r.nextInt(-2, 3)));
            Fx.sound(at, INSTRUMENTS[Math.floorMod(n.c.id.hashCode(), INSTRUMENTS.length)], 1.4f, SCALE[n.workIdx]);
        }
        w.spawnParticle(Particle.NOTE, at.clone().add(0, 2.3, 0), 1, 0.3, 0.1, 0.3, 1);
        // the people listening enjoy the company
        if (r.nextInt(4) == 0) {
            for (Entity e : n.body.getNearbyEntities(10, 4, 10)) {
                Npc o = m.npc(e);
                if (o == null || o == n || !col.id.equals(o.c.colonyId) || o.sleeping) continue;
                o.c.needs.social = Math.min(100, o.c.needs.social + 1.5);
            }
        }
    }

    // ───────────── druids ─────────────

    void tend(Npc n, Colony col, Building grove, long now) {
        World w = n.body.getWorld();
        if (n.target != null && !growing(n.target.block(w))) n.target = null;
        if (n.target == null && now >= n.searchAt) {
            n.target = crop(col, w, n.loc());
            if (n.target == null) n.searchAt = now + 200;
        }
        if (n.target == null) {
            m.brain().wanderAt(n, col, grove, now, "Communing at " + grove.label() + " (nothing needs growing)");
            return;
        }
        Location tl = n.target.center(w);
        if (!n.mover.near(tl, 2.6)) {
            n.mover.moveTo(tl, plugin.settings().walkSpeed, 1.8);
            n.doing("Walking the fields");
            return;
        }
        n.mover.stop();
        n.working = true;
        CitizenBrain.face(n, tl);
        n.doing("Coaxing the crops to grow");
        if (now < n.actionAt) return;
        n.actionAt = now + m.interval(6, m.efficiency(col, n.c));
        // the crop and its neighbours
        int grown = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (grown >= 3) break;
                if (grow(n.target.add(dx, 0, dz).block(w))) grown++;
            }
        }
        n.body.swingMainHand();
        Fx.sound(tl, "minecraft:item.bone_meal.use", 0.7f, 1.1f);
        n.target = null;
    }

    private static boolean crop(Material t) {
        return switch (t) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, MELON_STEM, PUMPKIN_STEM -> true;
            default -> false;
        };
    }

    private static boolean growing(Block b) {
        return crop(b.getType()) && b.getBlockData() instanceof Ageable a && a.getAge() < a.getMaximumAge();
    }

    private static boolean grow(Block b) {
        if (!crop(b.getType()) || !(b.getBlockData() instanceof Ageable a) || a.getAge() >= a.getMaximumAge()) return false;
        a.setAge(Math.min(a.getMaximumAge(), a.getAge() + 1 + ThreadLocalRandom.current().nextInt(2)));
        b.setBlockData(a);
        b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3, 0);
        return true;
    }

    /** A crop still growing on one of the colony's farms, the nearest of a sample. */
    private BlockPos crop(Colony col, World w, Location here) {
        List<BlockPos> found = new ArrayList<>();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Building farm : col.valid(BuildingType.FARM)) {
            int n = farm.tiles.size();
            if (n == 0) continue;
            // a sample of the field is enough: the druid comes round again
            int step = Math.max(1, n / 48), from = r.nextInt(step);
            for (int i = from; i < n; i += step) {
                BlockPos up = farm.tiles.get(i).add(0, 1, 0);
                if (up.loaded(w) && growing(up.block(w))) found.add(up);
            }
        }
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : found) {
            double d = p.distSq(here) * (0.5 + r.nextDouble());
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    // ───────────── enchanters ─────────────

    void enchant(Npc n, Colony col, Building tower, long now) {
        if (!atStation(n, tower, "Going to work at " + tower.label())) return;
        if (now < n.actionAt) {
            n.working = tower.waiting == null;
            n.doing(tower.waiting != null ? tower.waiting : "Enchanting at " + tower.label() + " (" + Math.min(99, Math.round(tower.progress / Trades.ENCHANT_HOURS * 100)) + "%)");
            return;
        }
        long ticks = Math.round(plugin.settings().stationInterval * 20);
        boolean busy = plugin.sim().trades().enchant(col, tower, n.c, GameTime.hours(ticks) * m.efficiency(col, n.c));
        if (!busy) {
            // look again in a little while
            n.actionAt = now + 100;
            n.c.waiting = tower.waiting;
            n.doing(tower.waiting == null ? "Nothing to enchant" : tower.waiting);
            return;
        }
        n.actionAt = now + ticks;
        n.working = true;
        n.doing("Enchanting at " + tower.label() + " (" + Math.min(99, Math.round(tower.progress / Trades.ENCHANT_HOURS * 100)) + "%)");
        World w = n.body.getWorld();
        BlockPos st = m.brain().stations.station(n, tower);
        Location at = st != null && st.loaded(w) ? st.center(w).add(0, 1.2, 0) : n.loc().add(0, 1.6, 0);
        w.spawnParticle(Particle.ENCHANT, at, 14, 0.5, 0.5, 0.5, 0.8);
        if (ThreadLocalRandom.current().nextInt(6) == 0) Fx.sound(at, "minecraft:block.enchantment_table.use", 0.35f, 1.1f);
    }

    // ───────────── beekeepers ─────────────

    void bees(Npc n, Colony col, Building apiary, long now) {
        World w = n.body.getWorld();
        List<BlockPos> hives = new ArrayList<>();
        BlockPos full = null;
        for (BlockPos p : apiary.stations) {
            if (!p.loaded(w)) continue;
            Block b = p.block(w);
            if (!Feature.BEEHIVE.matches(b.getType()) || !(b.getBlockData() instanceof Beehive h)) continue;
            hives.add(p);
            if (full == null && h.getHoneyLevel() >= h.getMaximumHoneyLevel()) full = p;
        }
        if (hives.isEmpty()) {
            m.brain().wanderAt(n, col, apiary, now, "No hives at " + apiary.label());
            return;
        }
        // out of bottles while storage has some: that's worth a walk
        apiary.waiting = apiary.stock.available(Trades.BOTTLE) <= 0 && col.storage.available(Trades.BOTTLE) > 0 ? "Waiting for bottles" : null;
        if (full == null) {
            // the rounds: from hive to hive, looking in on the bees
            if (n.waypoint < 0 || n.waypoint >= hives.size() || now > n.waypointUntil) {
                n.waypoint = ThreadLocalRandom.current().nextInt(hives.size());
                n.waypointUntil = now + 300;
            }
            n.working = true;
            m.brain().wander(n, hives.get(n.waypoint).center(w), 2, now, "Tending the hives at " + apiary.label());
            return;
        }
        Location at = beside(w, full);
        if (!n.mover.near(at, 2.2)) {
            n.mover.moveTo(at, plugin.settings().walkSpeed, 1.5);
            n.doing("Going to a full hive at " + apiary.label());
            return;
        }
        n.mover.stop();
        n.working = true;
        CitizenBrain.face(n, full.center(w));
        if (now < n.actionAt) return;
        n.actionAt = now + m.interval(4, m.efficiency(col, n.c));
        if (!plugin.sim().trades().harvest(apiary)) {
            n.c.waiting = apiary.label() + " is full: the honey is waiting for haulers";
            n.doing(n.c.waiting);
            return;
        }
        Block b = full.block(w);
        if (b.getBlockData() instanceof Beehive h) {
            h.setHoneyLevel(0);
            b.setBlockData(h);
        }
        col.producedToday++;
        n.body.swingMainHand();
        n.doing("Collecting honey at " + apiary.label());
        Fx.sound(at, "minecraft:block.beehive.shear", 0.8f, 1f);
        w.spawnParticle(Particle.FALLING_HONEY, full.center(w).add(0, 0.2, 0), 6, 0.3, 0.2, 0.3, 0);
    }

    /** Somewhere to stand next to a block. */
    private static Location beside(World w, BlockPos p) {
        for (int dy = 0; dy >= -2; dy--) {
            for (BlockFace f : SIDES) {
                Block s = p.relative(f).block(w).getRelative(0, dy, 0);
                if (RoomScanner.standable(w, s.getX(), s.getY(), s.getZ())) return s.getLocation().add(0.5, 0, 0.5);
            }
        }
        return p.center(w);
    }
}
