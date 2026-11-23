package com.colonysmp.npc;

import com.colonysmp.building.RoomScanner;
import com.colonysmp.core.GatePolicy;
import com.colonysmp.util.Keys;
import com.destroystokyo.paper.entity.Pathfinder;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Mob;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Walks an NPC body along a path. The body has no brain of its own (it is "unaware"), so it never wanders off:
 * it goes exactly where its routine sends it. Paths come from Paper's villager pathfinder, with a block-grid
 * search as the fallback for ladders, iron doors and fresh tunnels. Long trips are walked in legs. Only a
 * citizen that has been stuck for a long time is moved straight to its destination, and only while no player
 * is watching (or after a much longer wait).
 *
 * <p>Fence gates are handled with care, because a pen is only as good as its gate: a body opens a gate only from
 * right beside it and only when no animal stands near, shuts it the moment it is through (never waiting on the
 * animals) or the moment an animal comes near it, puts any animal caught in the gateway back inside, and if the animals won't move away it goes
 * through without opening the gate at all. See {@link GatePolicy}.
 */
public final class Mover {

    private static final Vector ZERO = new Vector();
    /** How gates have been handled since start-up, for /colony admin pen: opened, waited at, slipped through shut, shut early for an animal, animals put back. */
    public static final long[] GATE_STATS = new long[5];
    private static final int GRID_NODES = 3000;

    private final Mob body;
    /** The horse it rides, if any: the horse is what walks. */
    private Mob vehicle;
    private Location dest;
    private double speed, arrive;
    private List<Location> path;
    private int idx;
    private Location pos;
    private long nextPathAt, pausedUntil, lastProgressAt, stuckSince;
    private int fails;
    private double planDist;
    private boolean arrived;
    private boolean ironKeys;
    private final List<long[]> doors = new ArrayList<>();
    private final List<Block> doorBlocks = new ArrayList<>();
    private Block passDoor;
    private long passUntil;
    /** The gate this body is waiting at for the animals to move away, and since when. */
    private Block gateHold;
    private long gateHoldSince;
    /** A gate it goes through without opening, because animals wouldn't move away from it. */
    private Block gateSlip;
    private long gateSlipUntil;
    /** Tells which side of a gate is the inside of a pen (set by the NPC manager). */
    private Gates gates;

    /** What a mover needs to know about the pens around it. */
    public interface Gates {
        /** A standing point just inside the pen this gate belongs to, or null if it isn't a pen's gate. */
        Location inside(Block gate);
    }

    public void setGates(Gates g) {
        gates = g;
    }

    public Mover(Mob body) {
        this.body = body;
    }

    /** Mounted bodies move their horse, rider and all. */
    public void setVehicle(Mob v) {
        vehicle = v;
        path = null;
    }

    private Mob walker() {
        return vehicle != null && vehicle.isValid() ? vehicle : body;
    }

    /** Wardens and guards carry the keys: they open iron doors too. */
    public void setIronKeys(boolean keys) {
        ironKeys = keys;
    }

    /**
     * Lets this body through one particular door for a while, even an iron one: prisoners being locked up or
     * let out, wardens on a visit. The door is opened now and closes again behind them.
     */
    public void passDoor(Block door, long now, long ticks) {
        if (door == null) return;
        if (door.getBlockData() instanceof Bisected bi && bi.getHalf() == Bisected.Half.TOP) door = door.getRelative(org.bukkit.block.BlockFace.DOWN);
        boolean same = passDoor != null && passDoor.equals(door) && now <= passUntil;
        passDoor = door;
        passUntil = Math.max(passUntil, now + ticks);
        if (!same) {
            openFor(door, now, Math.min(ticks, 60));
            path = null;
        }
    }

    private boolean mayPass(Block b, long now) {
        if (passDoor == null || now > passUntil) return false;
        if (!b.getWorld().equals(passDoor.getWorld())) return false;
        return b.getX() == passDoor.getX() && b.getZ() == passDoor.getZ() && (b.getY() == passDoor.getY() || b.getY() == passDoor.getY() + 1);
    }

    public boolean moving() {
        return dest != null;
    }

    public boolean arrived() {
        return arrived;
    }

    public Location dest() {
        return dest;
    }

    /** True while no way to the destination has been found (the citizen is waiting and retrying). */
    public boolean blocked() {
        return dest != null && fails >= 2;
    }

    public void moveTo(Location to, double speed, double arrive) {
        if (to == null || to.getWorld() == null) return;
        if (dest != null && dest.getWorld() == to.getWorld() && dest.distanceSquared(to) < 1.0) {
            this.speed = speed;
            this.arrive = arrive;
            return;
        }
        boolean keep = path != null && dest != null && dest.getWorld() == to.getWorld() && dest.distanceSquared(to) < 9;
        dest = to.clone();
        this.speed = speed;
        this.arrive = arrive;
        arrived = false;
        if (!keep) {
            path = null;
            fails = 0;
            stuckSince = 0;
            nextPathAt = 0;
        }
    }

    /** True if within the given distance of a point (horizontally, and not far above or below). */
    public boolean near(Location l, double dist) {
        Location c = walker().getLocation();
        if (l == null || c.getWorld() != l.getWorld()) return false;
        double dx = c.getX() - l.getX(), dz = c.getZ() - l.getZ();
        return dx * dx + dz * dz <= dist * dist && Math.abs(c.getY() - l.getY()) < 3;
    }

    public void stop() {
        dest = null;
        path = null;
        fails = 0;
        stuckSince = 0;
    }

    /** Lets physics move the body (knockback) before walking on. */
    public void pause(int ticks, long now) {
        pausedUntil = now + ticks;
        path = null;
    }

    public void tick(NpcManager mgr, long now) {
        closeDoors(now);
        if (dest == null || !body.isValid()) return;
        if (now < pausedUntil) return;
        if (body.getWorld() != dest.getWorld()) {
            stop();
            return;
        }
        Location cur = walker().getLocation();
        if (near(dest, arrive)) {
            arrived = true;
            stop();
            return;
        }
        if (pos != null && path != null && pos.getWorld() == cur.getWorld() && pos.distanceSquared(cur) > 2.25) {
            // pushed or knocked off the path: plan again from here
            path = null;
        }
        if (path == null) {
            if (now < nextPathAt || !mgr.pathBudget()) return;
            plan(mgr, now, cur);
            if (path == null) return;
        }
        follow(mgr, now);
    }

    private void plan(NpcManager mgr, long now, Location cur) {
        nextPathAt = now + 20;
        double reach = arrive + 0.75;
        List<Location> paper = paperPath();
        List<Location> pts = paper;
        if (!reaches(paper, reach)) {
            // Paper gave up or stopped short (ladders, iron doors, gaps): try the grid search
            List<Location> grid = GridPath.find(cur, dest, arrive, ironKeys, b -> mayPass(b, now), GRID_NODES);
            if (reaches(grid, reach) || left(grid) < left(paper)) pts = grid;
        }
        double now0 = flat(cur, dest);
        if (pts == null || pts.isEmpty() || !reaches(pts, reach) && now0 - left(pts) < 1.5) {
            fail(mgr, now);
            return;
        }
        path = pts;
        idx = 0;
        pos = cur.clone();
        lastProgressAt = now;
        planDist = now0;
        // skip a first node we're already standing on
        if (path.get(0).distanceSquared(cur) < 0.36) idx = 1;
    }

    private List<Location> paperPath() {
        Pathfinder.PathResult r = null;
        try {
            r = walker().getPathfinder().findPath(dest);
        } catch (RuntimeException ignored) {
            // the pathfinder can throw for unloaded regions; treat as no path
        }
        if (r == null || r.getPoints().isEmpty()) return null;
        List<Location> pts = new ArrayList<>();
        for (Location l : r.getPoints()) pts.add(stand(l));
        return pts;
    }

    private boolean reaches(List<Location> pts, double reach) {
        if (pts == null || pts.isEmpty()) return false;
        Location last = pts.get(pts.size() - 1);
        return flat(last, dest) <= reach && Math.abs(last.getY() - dest.getY()) < 3;
    }

    /** How far from the destination a path ends (infinite for no path). */
    private double left(List<Location> pts) {
        if (pts == null || pts.isEmpty()) return Double.MAX_VALUE;
        Location last = pts.get(pts.size() - 1);
        return flat(last, dest) + Math.abs(last.getY() - dest.getY()) * 0.5;
    }

    private static double flat(Location a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void follow(NpcManager mgr, long now) {
        if (holdAtGate(now)) {
            // waiting for the animals to move away from the gate: this isn't being stuck
            lastProgressAt = now;
            return;
        }
        double remaining = speed;
        Location p = pos.clone();
        Location before = pos.clone();
        while (remaining > 1e-4 && idx < path.size()) {
            Location n = path.get(idx);
            double d = p.distance(n);
            if (d <= remaining) {
                p = n.clone();
                remaining -= d;
                idx++;
            } else {
                Vector v = n.toVector().subtract(p.toVector()).multiply(remaining / d);
                p.add(v);
                remaining = 0;
            }
        }
        World w = p.getWorld();
        if (w == null || !w.isChunkLoaded(p.getBlockX() >> 4, p.getBlockZ() >> 4)) {
            stop();
            return;
        }
        openDoors(p, now);
        if (idx < path.size()) openDoors(path.get(idx), now);
        double dx = p.getX() - before.getX(), dz = p.getZ() - before.getZ();
        if (dx * dx + dz * dz > 1e-4) {
            p.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        } else {
            p.setYaw(walker().getLocation().getYaw());
        }
        if (p.distanceSquared(before) > 1e-4) lastProgressAt = now;
        p.setPitch(0);
        move(p);
        pos = p;
        if (idx >= path.size()) {
            path = null;
            if (near(dest, arrive + 0.75)) return;
            if (planDist - flat(p, dest) >= 1.0) {
                // a leg of a long trip: keep going from here
                fails = 0;
                stuckSince = 0;
                nextPathAt = now;
            } else {
                fail(mgr, now);
            }
        } else if (now - lastProgressAt > 60) {
            path = null;
            fail(mgr, now);
        }
    }

    private void fail(NpcManager mgr, long now) {
        fails++;
        nextPathAt = now + Math.min(100, 20L * fails);
        if (stuckSince == 0) stuckSince = now;
        long stuck = now - stuckSince;
        var s = mgr.plugin().settings();
        boolean unseen = stuck >= s.stuckTeleportSeconds * 20L && !watched();
        if (unseen || stuck >= s.stuckForceSeconds * 20L) {
            // truly stuck (walled in, or the destination has no way in): step straight there
            Location safe = safeSpot(dest);
            if (safe != null) {
                safe.setYaw(walker().getLocation().getYaw());
                move(safe);
                pos = safe;
                arrived = true;
                stop();
            }
        }
    }

    private void move(Location to) {
        Mob w = walker();
        w.setVelocity(ZERO);
        if (w == body) body.teleport(to);
        else w.teleport(to, TeleportFlag.EntityState.RETAIN_PASSENGERS);
    }

    /** Is a player close enough to see this citizen (or its destination)? */
    private boolean watched() {
        Location here = body.getLocation();
        if (!here.getWorld().getNearbyPlayers(here, 32).isEmpty()) return true;
        return dest != null && dest.getWorld() == here.getWorld() && !dest.getWorld().getNearbyPlayers(dest, 32).isEmpty();
    }

    /** The standing point on a path node (on top of slabs, farmland, carpets...). */
    static Location stand(Location node) {
        World w = node.getWorld();
        Block b = node.getBlock();
        double y = node.getBlockY();
        Material m = b.getType();
        if (!b.isPassable() && !Tag.DOORS.isTagged(m) && !Tag.FENCE_GATES.isTagged(m) && !Tag.TRAPDOORS.isTagged(m)) {
            BoundingBox bb = b.getBoundingBox();
            double top = bb.getMaxY();
            if (top > y && top - y <= 0.6) y = top;
        }
        return new Location(w, node.getBlockX() + 0.5, y, node.getBlockZ() + 0.5);
    }

    /** A free spot at or near a location (for stepping straight there). */
    public static Location safeSpot(Location l) {
        if (l == null || l.getWorld() == null) return null;
        World w = l.getWorld();
        int bx = l.getBlockX(), by = l.getBlockY(), bz = l.getBlockZ();
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return null;
        if (RoomScanner.standable(w, bx, by, bz)) return new Location(w, l.getX(), floorAt(w, bx, by, bz), l.getZ());
        for (int r = 1; r <= 3; r++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                        int x = bx + dx, y = by + dy, z = bz + dz;
                        if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                        if (RoomScanner.standable(w, x, y, z)) return new Location(w, x + 0.5, floorAt(w, x, y, z), z + 0.5);
                    }
                }
            }
        }
        return null;
    }

    private static double floorAt(World w, int x, int y, int z) {
        Block b = w.getBlockAt(x, y, z);
        if (!b.isPassable()) {
            double top = b.getBoundingBox().getMaxY();
            if (top > y && top - y <= 0.6) return top;
        }
        return y;
    }

    // ───────────── doors ─────────────

    private void openDoors(Location at, long now) {
        Block feet = at.getBlock();
        tryOpen(feet, now);
    }

    private void tryOpen(Block b, long now) {
        Material m = b.getType();
        boolean gate = Tag.FENCE_GATES.isTagged(m);
        boolean wooden = Tag.WOODEN_DOORS.isTagged(m) || gate;
        boolean iron = m == Material.IRON_DOOR;
        if (!wooden && !(iron && (ironKeys || mayPass(b, now)))) return;
        BlockData d = b.getBlockData();
        if (!(d instanceof Openable o) || o.isOpen()) return;
        if (gate) {
            // a gate is only opened from right beside it, and never while animals stand at it
            Location c = walker().getLocation();
            double dx = c.getX() - (b.getX() + 0.5), dz = c.getZ() - (b.getZ() + 0.5);
            if (!GatePolicy.inReach(dx * dx + dz * dz)) return;
            if (b.equals(gateSlip) && now <= gateSlipUntil) return;
            if (!looseAnimals(b, GatePolicy.CLEAR_RADIUS).isEmpty()) return;
        }
        o.setOpen(true);
        b.setBlockData(o, true);
        b.getWorld().playSound(b.getLocation(), iron ? Sound.BLOCK_IRON_DOOR_OPEN : gate ? Sound.BLOCK_FENCE_GATE_OPEN : Sound.BLOCK_WOODEN_DOOR_OPEN, 0.7f, 1f);
        doorBlocks.add(b);
        if (gate) GATE_STATS[0]++;
        // gates shut the moment the body is through (see closeDoors); this is only the longest they stay open
        doors.add(new long[]{now + (gate ? GatePolicy.MAX_OPEN_TICKS : 50), now});
    }

    /**
     * The closed gate this body is about to walk through, if animals are standing at it. The body waits a
     * moment while they are shooed off; if they won't move it slips through without opening the gate at all.
     * True while it should stand still.
     */
    private boolean holdAtGate(long now) {
        if (path == null || idx >= path.size()) return false;
        Block gate = null;
        for (int i = idx; i < Math.min(path.size(), idx + 2) && gate == null; i++) {
            Block b = path.get(i).getBlock();
            if (Tag.FENCE_GATES.isTagged(b.getType()) && b.getBlockData() instanceof Openable o && !o.isOpen()) gate = b;
        }
        if (gate == null) {
            gateHold = null;
            return false;
        }
        Location c = walker().getLocation();
        double dx = c.getX() - (gate.getX() + 0.5), dz = c.getZ() - (gate.getZ() + 0.5);
        if (!GatePolicy.inReach(dx * dx + dz * dz)) return false;
        if (gate.equals(gateSlip) && now <= gateSlipUntil) return false;
        List<Animals> crowd = looseAnimals(gate, GatePolicy.CLEAR_RADIUS);
        if (!gate.equals(gateHold)) {
            gateHold = gate;
            gateHoldSince = now;
            if (!crowd.isEmpty()) GATE_STATS[1]++;
        }
        switch (GatePolicy.approach(!crowd.isEmpty(), now - gateHoldSince)) {
            case WAIT -> {
                if ((now - gateHoldSince) % 10 == 0) shoo(gate, crowd);
                return true;
            }
            case SLIP -> {
                GATE_STATS[2]++;
                gateSlip = gate;
                gateSlipUntil = now + 60;
                gateHold = null;
                return false;
            }
            default -> {
                gateHold = null;
                return false;
            }
        }
    }

    /** Farm animals at a gate that nobody is riding or leading (citizens' bodies and war horses don't count). */
    private static List<Animals> looseAnimals(Block gate, double radius) {
        List<Animals> out = new ArrayList<>();
        Location c = gate.getLocation().add(0.5, 0, 0.5);
        for (org.bukkit.entity.Entity e : gate.getWorld().getNearbyEntities(c, radius, 2, radius)) {
            if (!(e instanceof Animals a) || NpcManager.isBody(e) || !e.getPassengers().isEmpty() || a.isLeashed()) continue;
            if (e.getPersistentDataContainer().has(Keys.MOUNT, PersistentDataType.STRING)) continue;
            out.add(a);
        }
        return out;
    }

    /** Sends the animals at a gate back into their pen (or just away from the gate). */
    private void shoo(Block gate, List<Animals> crowd) {
        Location in = gates == null ? null : gates.inside(gate);
        Location c = gate.getLocation().add(0.5, 0, 0.5);
        for (Animals a : crowd) {
            Location at = a.getLocation();
            Vector away = in != null ? in.toVector().subtract(c.toVector()) : at.toVector().subtract(c.toVector());
            away.setY(0);
            if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
            away.normalize();
            Location to = at.clone().add(away.clone().multiply(4));
            try {
                a.getPathfinder().moveTo(to, 1.3);
            } catch (RuntimeException ignored) {
                // no path: the nudge below still moves it
            }
            a.setVelocity(away.multiply(0.25).setY(0.08));
        }
        gate.getWorld().playSound(c, Sound.ENTITY_VILLAGER_NO, 0.5f, 1.1f);
    }

    private void closeDoors(long now) {
        if (doors.isEmpty()) return;
        Iterator<long[]> it = doors.iterator();
        Iterator<Block> bit = doorBlocks.iterator();
        while (it.hasNext()) {
            long[] t = it.next();
            Block b = bit.next();
            boolean gate = Tag.FENCE_GATES.isTagged(b.getType());
            if (gate && t.length > 1) {
                // a gate shuts as soon as its opener is through, whoever else is standing about
                Location c = walker().getLocation();
                double dx = c.getX() - (b.getX() + 0.5), dz = c.getZ() - (b.getZ() + 0.5);
                boolean sameWorld = c.getWorld() == b.getWorld();
                boolean animalNear = !looseAnimals(b, GatePolicy.GUARD_RADIUS).isEmpty();
                if (!GatePolicy.shouldClose(sameWorld ? dx * dx + dz * dz : Double.MAX_VALUE, now - t[1], now >= t[0], animalNear)) continue;
                if (animalNear) GATE_STATS[3]++;
                shutGate(b);
                it.remove();
                bit.remove();
                continue;
            }
            if (now < t[0]) continue;
            // wait until nobody is standing in the doorway
            if (!b.getWorld().getNearbyEntities(b.getBoundingBox().expand(0.2, 1, 0.2), e -> e instanceof org.bukkit.entity.LivingEntity).isEmpty()) {
                t[0] = now + 20;
                continue;
            }
            BlockData d = b.getBlockData();
            if (d instanceof Openable o && o.isOpen()) {
                o.setOpen(false);
                b.setBlockData(o, true);
                b.getWorld().playSound(b.getLocation(), b.getType() == Material.IRON_DOOR ? Sound.BLOCK_IRON_DOOR_CLOSE : Sound.BLOCK_WOODEN_DOOR_CLOSE, 0.7f, 1f);
            }
            it.remove();
            bit.remove();
        }
    }

    /** Closes a gate, first putting any animal caught in the gateway back inside its pen. */
    private void shutGate(Block b) {
        BlockData d = b.getBlockData();
        if (!(d instanceof Openable o) || !o.isOpen()) return;
        Location in = gates == null ? null : gates.inside(b);
        if (in != null) {
            BoundingBox way = new BoundingBox(b.getX() - 0.3, b.getY() - 1, b.getZ() - 0.3, b.getX() + 1.3, b.getY() + 2, b.getZ() + 1.3);
            for (Animals a : looseAnimals(b, 2)) {
                if (!a.getBoundingBox().overlaps(way)) continue;
                Location to = in.clone();
                to.setYaw(a.getLocation().getYaw());
                a.teleport(to);
                a.setVelocity(ZERO);
                GATE_STATS[4]++;
            }
        }
        o.setOpen(false);
        b.setBlockData(o, true);
        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_FENCE_GATE_CLOSE, 0.7f, 1f);
    }

    /** Opens a door for someone with keys (wardens) and closes it again after a while. */
    public void openFor(Block door, long now, long closeAfter) {
        BlockData d = door.getBlockData();
        if (!(d instanceof Openable o)) return;
        if (!o.isOpen()) {
            o.setOpen(true);
            door.setBlockData(o, true);
            door.getWorld().playSound(door.getLocation(), door.getType() == Material.IRON_DOOR ? Sound.BLOCK_IRON_DOOR_OPEN : Sound.BLOCK_WOODEN_DOOR_OPEN, 0.7f, 1f);
        }
        int i = doorBlocks.indexOf(door);
        if (i >= 0) {
            doors.get(i)[0] = Math.max(doors.get(i)[0], now + closeAfter);
            return;
        }
        doorBlocks.add(door);
        doors.add(new long[]{now + closeAfter});
    }

    /** Closes every door this NPC opened (despawn). */
    public void closeAll() {
        for (Block b : doorBlocks) {
            if (!b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) continue;
            if (Tag.FENCE_GATES.isTagged(b.getType())) {
                shutGate(b);
                continue;
            }
            BlockData d = b.getBlockData();
            if (d instanceof Openable o && o.isOpen()) {
                o.setOpen(false);
                b.setBlockData(o, true);
            }
        }
        doors.clear();
        doorBlocks.clear();
    }

    public void sync() {
        pos = walker().getLocation();
    }
}
