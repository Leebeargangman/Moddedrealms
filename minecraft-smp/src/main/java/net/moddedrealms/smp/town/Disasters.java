package net.moddedrealms.smp.town;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.kyori.adventure.title.Title;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.Rail;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Natural disasters: meteors, tornadoes, floods, tsunamis and earthquakes strike the towns now and then (never the
 * spawn, the jail, the bank, the casino or the railway, nor colony land). The damage is real while it lasts, then the
 * townsfolk rebuild: every block that was changed goes back exactly as it was, a few minutes later. Nothing is ever
 * dropped or lost: blocks holding things (chests, signs, furnaces...), doors, beds, rails and anything something
 * hangs from are left alone; the changed blocks can't be mined, built on or bucketed while the town is damaged; and
 * the record of every change is on disk, so a crash or restart puts the town back at the next start.
 */
final class Disasters implements Listener {
    enum Kind {
        METEOR("Meteor strike"), TORNADO("Tornado"), FLOOD("Flood"), TSUNAMI("Tsunami"), EARTHQUAKE("Earthquake");

        final String label;

        Kind(String label) {
            this.label = label;
        }

        static Kind parse(String s) {
            for (Kind k : values()) if (k.name().equalsIgnoreCase(s)) return k;
            return null;
        }
    }

    private final TownManager t;
    private final File file;
    private final Random r = new Random();
    /** Every block changed by the current damage: packed position -> what it was. */
    private final Map<Long, BlockData> original = new LinkedHashMap<>();
    private final Set<Long> touched = new HashSet<>();
    private World world;
    private String town;
    private Kind active;
    private long restoreAt, nextAt;
    private int task = -1;
    private boolean restoring;

    Disasters(TownManager t) {
        this.t = t;
        this.file = new File(t.plugin().getDataFolder(), "disaster-damage.yml");
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    void start() {
        restoreSaved();
        nextAt = System.currentTimeMillis() + (long) (cfg().getDouble("town.disasters.first-after-hours", 2) * 3_600_000L);
        Bukkit.getScheduler().runTaskTimer(t.plugin(), this::second, 40L, 20L);
    }

    /** Server stopping: put everything back now. */
    void shutdown() {
        if (task >= 0) Bukkit.getScheduler().cancelTask(task);
        task = -1;
        restoreAll();
    }

    // ------------------------------------------------------------------ bookkeeping

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static int ux(long p) {
        return (int) (p >> 38) << 6 >> 6;
    }

    private static int uz(long p) {
        return (int) ((p >> 12) & 0x3FFFFFF) << 6 >> 6;
    }

    private static int uy(long p) {
        return (int) (p & 0xFFF) << 20 >> 20;
    }

    private boolean changed(Block b) {
        return world != null && b.getWorld() == world && original.containsKey(pack(b.getX(), b.getY(), b.getZ()));
    }

    /** Changes a block, remembering what it was the first time. Returns false if it may not be touched. */
    private boolean set(Block b, BlockData to) {
        if (world == null) world = b.getWorld();
        if (b.getWorld() != world) return false;
        long k = pack(b.getX(), b.getY(), b.getZ());
        if (!original.containsKey(k)) {
            if (!may(b)) return false;
            original.put(k, b.getBlockData().clone());
            touched.add(k);
        }
        b.setBlockData(to, false);
        return true;
    }

    private void persist() {
        var y = new org.bukkit.configuration.file.YamlConfiguration();
        if (world == null || original.isEmpty()) {
            file.delete();
            return;
        }
        y.set("world", world.getName());
        y.set("town", town);
        List<String> rows = new ArrayList<>(original.size());
        for (var e : original.entrySet()) rows.add(ux(e.getKey()) + "," + uy(e.getKey()) + "," + uz(e.getKey()) + "," + e.getValue().getAsString());
        y.set("blocks", rows);
        try {
            net.moddedrealms.smp.SafeYaml.save(y, file);
        } catch (IOException ex) {
            t.plugin().getLogger().warning("Couldn't save disaster damage: " + ex.getMessage());
        }
    }

    /** Damage left by a crash: put it back. */
    private void restoreSaved() {
        if (!file.exists()) return;
        var y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        World w = Bukkit.getWorld(y.getString("world", ""));
        if (w == null) return;
        int n = 0;
        for (String row : y.getStringList("blocks")) {
            String[] f = row.split(",", 4);
            try {
                int x = Integer.parseInt(f[0]), yy = Integer.parseInt(f[1]), z = Integer.parseInt(f[2]);
                w.getChunkAt(x >> 4, z >> 4);
                w.getBlockAt(x, yy, z).setBlockData(Bukkit.createBlockData(f[3]), false);
                n++;
            } catch (RuntimeException ignored) {
            }
        }
        file.delete();
        t.plugin().getLogger().info("Repaired " + n + " blocks of disaster damage left from before the restart.");
    }

    // ------------------------------------------------------------------ what may be touched

    private final List<org.bukkit.util.BoundingBox> noGo = new ArrayList<>();
    private World noGoWorld;
    private long noGoAt;

    /** The protected buildings (read from their undo files, so kept for a minute). */
    private List<org.bukkit.util.BoundingBox> noGo(World w) {
        if (w != noGoWorld || System.currentTimeMillis() - noGoAt > 60_000) {
            noGo.clear();
            for (Protection.Area a : t.protection().built()) {
                if (a.world() != w) continue;
                String n = a.name();
                if (n.equals("jail") || n.equals("bank") || n.equals("casino") || n.equals("railway") || n.equals("plaza") || n.equals("market")) noGo.add(a.box().clone().expand(3));
            }
            for (Jail j : t.jails()) for (Location c : j.cells()) if (c.getWorld() == w) noGo.add(org.bukkit.util.BoundingBox.of(c, 14, 14, 14));
            noGoWorld = w;
            noGoAt = System.currentTimeMillis();
        }
        return noGo;
    }

    private boolean excluded(Location l) {
        // the spawn and every building with its own mechanics
        double avoid = cfg().getDouble("town.disasters.avoid-spawn-radius", 80);
        Location spawn = l.getWorld().getSpawnLocation();
        if (flat(l, spawn) < avoid * avoid) return true;
        for (TownManager.Zone z : t.zones()) {
            if (z.id().equals("spawn") && z.world().equals(l.getWorld().getName())) {
                double dx = l.getX() - z.x(), dz = l.getZ() - z.z();
                if (dx * dx + dz * dz < avoid * avoid) return true;
            }
        }
        Vector v = l.toVector();
        for (org.bukkit.util.BoundingBox b : noGo(l.getWorld())) if (b.contains(v)) return true;
        return t.colonyLand(l);
    }

    private static double flat(Location a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static boolean delicate(Block b) {
        if (b.getType().isAir() || b.isLiquid()) return false;
        BlockData d = b.getBlockData();
        return b.getState() instanceof TileState || d instanceof Bisected || d instanceof Bed || d instanceof Rail || !b.getType().isOccluding() && !b.getType().isSolid()
                || Tag.ALL_SIGNS.isTagged(b.getType()) || b.getType() == Material.LADDER || b.getType() == Material.VINE;
    }

    /** True if this block can be wrecked and put back without anything dropping or being lost. */
    private boolean may(Block b) {
        Material m = b.getType();
        if (m.isAir()) return !excluded(b.getLocation());
        if (b.getState() instanceof TileState) return false;
        BlockData d = b.getBlockData();
        if (d instanceof Bisected || d instanceof Bed || d instanceof Rail) return false;
        if (m == Material.BEDROCK || m == Material.BARRIER || m == Material.END_PORTAL_FRAME || m == Material.NETHER_PORTAL || m == Material.SPAWNER
                || m.name().contains("COMMAND_BLOCK") || m == Material.STRUCTURE_BLOCK || m == Material.LIGHT) return false;
        if (excluded(b.getLocation())) return false;
        // nothing may be hanging from it or standing on it that would pop off
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN}) {
            Block n = b.getRelative(f);
            if (changed(n)) continue;
            if (delicate(n)) return false;
            if (n.getRelative(BlockFace.UP).getType() == Material.RAIL || Tag.RAILS.isTagged(n.getType())) return false;
        }
        for (Entity e : b.getWorld().getNearbyEntities(b.getLocation().add(0.5, 0.5, 0.5), 1.4, 1.4, 1.4)) {
            if (e instanceof Hanging || e instanceof org.bukkit.entity.Display || e instanceof org.bukkit.entity.ArmorStand) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ the schedule

    private void second() {
        long now = System.currentTimeMillis();
        if (active == null && !original.isEmpty() && !restoring && now >= restoreAt) rebuild();
        if (active != null || !original.isEmpty() || now < nextAt || !cfg().getBoolean("town.disasters.enabled", true)) return;
        double every = cfg().getDouble("town.disasters.every-hours", 8);
        nextAt = now + (long) ((every * 0.6 + r.nextDouble() * every * 0.8) * 3_600_000L);
        if (Bukkit.getOnlinePlayers().size() < cfg().getInt("town.disasters.min-players", 2)) return;
        strike(null, null, null);
    }

    /** Starts a disaster. Any argument may be null (chosen at random). Returns what happened, for admins. */
    String strike(Kind kind, String zoneId, Player near) {
        if (active != null || !original.isEmpty()) return "A disaster is already under way (or the town is still being repaired).";
        List<TownManager.Zone> zones = new ArrayList<>();
        for (TownManager.Zone z : t.zones()) {
            if (zoneId != null && !z.id().equalsIgnoreCase(zoneId)) continue;
            World w = Bukkit.getWorld(z.world());
            if (w == null) continue;
            boolean watched = near != null;
            for (Player p : w.getPlayers()) if (Math.hypot(p.getLocation().getX() - z.x(), p.getLocation().getZ() - z.z()) < z.police()) watched = true;
            if (watched) zones.add(z);
        }
        if (zones.isEmpty()) return "Nobody is in a town to see it; no disaster.";
        TownManager.Zone z = zones.get(r.nextInt(zones.size()));
        World w = Bukkit.getWorld(z.world());
        Location at = near != null ? near.getLocation() : null;
        for (int i = 0; at == null && i < 40; i++) {
            double ang = r.nextDouble() * Math.PI * 2, d = (z.id().equals("spawn") ? cfg().getDouble("town.disasters.avoid-spawn-radius", 80) + 10 : 0)
                    + r.nextDouble() * Math.max(20, z.town() - 10);
            int x = (int) (z.x() + Math.cos(ang) * d), zz = (int) (z.z() + Math.sin(ang) * d);
            if (!w.isChunkLoaded(x >> 4, zz >> 4)) continue;
            Location c = w.getHighestBlockAt(x, zz, HeightMap.MOTION_BLOCKING_NO_LEAVES).getLocation().add(0.5, 1, 0.5);
            if (!excluded(c)) at = c;
        }
        if (at == null) return "Couldn't find a spot in " + z.name() + " away from the spawn and the protected buildings.";
        if (excluded(at)) return "That spot is protected (spawn, jail, bank, casino, railway, market or colony land).";
        if (kind == null) {
            List<Kind> ks = new ArrayList<>(List.of(Kind.values()));
            if (nearSea(at) == null) ks.remove(Kind.TSUNAMI);
            kind = ks.get(r.nextInt(ks.size()));
        }
        if (kind == Kind.TSUNAMI && nearSea(at) == null) kind = Kind.FLOOD;
        world = w;
        town = z.name();
        active = kind;
        warn(kind, z, at);
        Kind k = kind;
        Location where = at;
        long delay = cfg().getLong("town.disasters.warning-seconds", 30) * 20L;
        Bukkit.getScheduler().runTaskLater(t.plugin(), () -> begin(k, where), delay);
        return k.label + " heading for " + z.name() + " at " + at.getBlockX() + ", " + at.getBlockZ() + ".";
    }

    private void warn(Kind k, TownManager.Zone z, Location at) {
        String msg = switch (k) {
            case METEOR -> "A blazing meteor is falling towards " + z.name() + "!";
            case TORNADO -> "A tornado is forming over " + z.name() + "!";
            case FLOOD -> "The rivers are bursting their banks: " + z.name() + " is flooding!";
            case TSUNAMI -> "The sea is pulling back from the shore at " + z.name() + "... TSUNAMI!";
            case EARTHQUAKE -> "The ground is shaking beneath " + z.name() + "!";
        };
        Bukkit.broadcast(SmpPlugin.mm("<red><bold>⚠ " + k.label.toUpperCase(Locale.ROOT) + "!</bold></red> <gold>" + msg + "</gold> <gray>(near X " + at.getBlockX() + ", Z "
                + at.getBlockZ() + "). The damage won't last: the townsfolk rebuild everything afterwards."));
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) > 200 * 200) continue;
            p.showTitle(Title.title(SmpPlugin.mm("<red><bold>" + k.label.toUpperCase(Locale.ROOT)), SmpPlugin.mm("<gold>" + msg),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
            p.playSound(p.getLocation(), Sound.ITEM_GOAT_HORN_SOUND_7, 1f, 0.6f);
        }
        t.news("A " + k.label.toLowerCase(Locale.ROOT) + " struck " + z.name() + ".");
        t.stockShock(z.id(), cfg().getDouble("town.disasters.stock-shock", 0.88));
    }

    private void begin(Kind k, Location at) {
        switch (k) {
            case METEOR -> meteor(at);
            case TORNADO -> tornado(at);
            case FLOOD -> flood(at, false, null);
            case TSUNAMI -> flood(at, true, nearSea(at));
            case EARTHQUAKE -> earthquake(at);
        }
    }

    /** The disaster has done its worst: the repairs start in a few minutes. */
    private void over() {
        active = null;
        if (task >= 0) Bukkit.getScheduler().cancelTask(task);
        task = -1;
        persist();
        restoreAt = System.currentTimeMillis() + cfg().getLong("town.disasters.rebuild-after-minutes", 4) * 60_000L;
        if (!original.isEmpty()) {
            Bukkit.broadcast(SmpPlugin.mm("<gray>The " + (town == null ? "town" : town) + " townsfolk are counting the damage. Repairs start in "
                    + cfg().getLong("town.disasters.rebuild-after-minutes", 4) + " minutes."));
        }
    }

    // ------------------------------------------------------------------ the disasters

    private void meteor(Location at) {
        World w = at.getWorld();
        int[] step = {0};
        Location sky = at.clone().add(r.nextInt(30) - 15, 70, r.nextInt(30) - 15);
        Vector path = at.toVector().subtract(sky.toVector()).multiply(1.0 / 40);
        task = Bukkit.getScheduler().runTaskTimer(t.plugin(), () -> {
            if (step[0] < 40) {
                Location p = sky.clone().add(path.clone().multiply(step[0]));
                w.spawnParticle(Particle.FLAME, p, 40, 0.6, 0.6, 0.6, 0.05);
                w.spawnParticle(Particle.LARGE_SMOKE, p, 20, 0.8, 0.8, 0.8, 0.02);
                w.spawnParticle(Particle.LAVA, p, 6, 0.4, 0.4, 0.4, 0);
                if (step[0] % 10 == 0) w.playSound(p, Sound.ENTITY_BLAZE_SHOOT, 3f, 0.5f);
                step[0]++;
                return;
            }
            Bukkit.getScheduler().cancelTask(task);
            task = -1;
            impact(at);
        }, 0L, 2L).getTaskId();
    }

    private void impact(Location at) {
        World w = at.getWorld();
        int rad = 4 + r.nextInt(3);
        Block c = at.getBlock().getRelative(BlockFace.DOWN, 2);
        BlockData air = Material.AIR.createBlockData();
        List<Block> crater = new ArrayList<>();
        for (int dy = rad + 3; dy >= -rad; dy--) for (int dx = -rad; dx <= rad; dx++) for (int dz = -rad; dz <= rad; dz++) {
            double d = Math.sqrt(dx * dx + dy * dy * 1.3 + dz * dz);
            if (d > rad + r.nextDouble() * 0.8) continue;
            Block b = c.getRelative(dx, dy, dz);
            if (!b.getType().isAir() && set(b, air)) crater.add(b);
        }
        // the bowl: scorched rock and the meteorite at the heart
        for (Block b : crater) {
            Block below = b.getRelative(BlockFace.DOWN);
            if (below.getType().isAir() || changed(below)) continue;
            if (below.getType().isOccluding() && r.nextInt(3) > 0) set(below, (r.nextInt(4) == 0 ? Material.MAGMA_BLOCK : r.nextBoolean() ? Material.NETHERRACK : Material.BLACKSTONE).createBlockData());
        }
        Block core = c.getRelative(0, -rad + 1, 0);
        set(core, Material.CRYING_OBSIDIAN.createBlockData());
        set(core.getRelative(BlockFace.UP), Material.MAGMA_BLOCK.createBlockData());
        w.spawnParticle(Particle.EXPLOSION_EMITTER, at, 3, 1, 1, 1);
        w.spawnParticle(Particle.FLAME, at, 200, rad, 2, rad, 0.2);
        w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.5f);
        w.playSound(at, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 4f, 0.6f);
        for (Entity e : w.getNearbyEntities(at, 14, 10, 14)) {
            if (!(e instanceof LivingEntity le) || t.isOurs(e)) continue;
            Vector away = e.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
            e.setVelocity(away.normalize().multiply(1.2).setY(0.7));
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0, true, false)); // thrown, not killed
        }
        over();
    }

    private void tornado(Location at) {
        World w = at.getWorld();
        double ang = r.nextDouble() * Math.PI * 2;
        Vector dir = new Vector(Math.cos(ang), 0, Math.sin(ang));
        Location[] pos = {at.clone().subtract(dir.clone().multiply(40))};
        int[] sec = {0};
        int length = cfg().getInt("town.disasters.tornado-seconds", 40);
        task = Bukkit.getScheduler().runTaskTimer(t.plugin(), () -> {
            Location p = pos[0];
            p.setY(w.getHighestBlockYAt(p, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1);
            // the funnel
            for (int y = 0; y < 30; y += 2) {
                double rr = 0.6 + y * 0.18;
                for (int i = 0; i < 4; i++) {
                    double a = (sec[0] * 0.9 + y * 0.5 + i * Math.PI / 2);
                    w.spawnParticle(Particle.CLOUD, p.clone().add(Math.cos(a) * rr, y, Math.sin(a) * rr), 2, 0.2, 0.3, 0.2, 0.02);
                }
                w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, p.clone().add(0, y, 0), 1, rr * 0.5, 0.5, rr * 0.5, 0.01);
            }
            if (sec[0] % 20 == 0) w.playSound(p, Sound.ITEM_ELYTRA_FLYING, 3f, 0.5f);
            if (sec[0] % 5 == 0) {
                rip(p, 4, 14, 10);
                for (Entity e : w.getNearbyEntities(p, 7, 12, 7)) {
                    if (t.isOurs(e) || e instanceof org.bukkit.entity.Display) continue;
                    Vector in = p.toVector().subtract(e.getLocation().toVector()).setY(0);
                    double d = Math.max(1, in.length());
                    Vector v = in.normalize().multiply(0.35).add(new Vector(-in.getZ(), 0, in.getX()).normalize().multiply(0.4)).setY(0.55 / Math.sqrt(d));
                    e.setVelocity(v);
                    if (e instanceof LivingEntity le) le.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 160, 0, true, false)); // lifted, not killed
                }
            }
            pos[0] = p.clone().add(dir.clone().multiply(0.15));
            if (++sec[0] > length * 20) over();
        }, 0L, 1L).getTaskId();
    }

    /** Tears loose blocks up from around the funnel and flings them about (for show: they vanish in the air). */
    private void rip(Location p, int radius, int height, int max) {
        World w = p.getWorld();
        List<Block> cand = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (dx * dx + dz * dz > radius * radius) continue;
            int top = w.getHighestBlockYAt(p.getBlockX() + dx, p.getBlockZ() + dz);
            if (top > p.getBlockY() + height) continue;
            Block b = w.getBlockAt(p.getBlockX() + dx, top, p.getBlockZ() + dz);
            Material m = b.getType();
            boolean light = Tag.LEAVES.isTagged(m) || Tag.PLANKS.isTagged(m) || Tag.WOOL.isTagged(m) || Tag.SLABS.isTagged(m) || Tag.STAIRS.isTagged(m)
                    || Tag.FENCES.isTagged(m) || m.name().contains("GLASS") || m == Material.HAY_BLOCK || m.name().endsWith("_LOG") || m == Material.GRASS_BLOCK
                    || m == Material.DIRT || m == Material.SAND || m.name().contains("TERRACOTTA");
            if (light && !changed(b)) cand.add(b);
        }
        Collections.shuffle(cand, r);
        int n = 0;
        for (Block b : cand) {
            if (n >= max) break;
            BlockData was = b.getBlockData().clone();
            if (!set(b, Material.AIR.createBlockData())) continue;
            n++;
            fling(b.getLocation().add(0.5, 0.5, 0.5), was, p);
        }
    }

    private void fling(Location from, BlockData data, Location eye) {
        World w = from.getWorld();
        BlockDisplay d = w.spawn(from, BlockDisplay.class, bd -> {
            bd.setBlock(data);
            bd.setPersistent(false);
            bd.setTransformation(new Transformation(new Vector3f(-0.5f, -0.5f, -0.5f), new AxisAngle4f(), new Vector3f(0.8f, 0.8f, 0.8f), new AxisAngle4f()));
        });
        int[] age = {0};
        double[] a = {r.nextDouble() * Math.PI * 2};
        int[] id = {0};
        id[0] = Bukkit.getScheduler().runTaskTimer(t.plugin(), () -> {
            if (!d.isValid() || ++age[0] > 50) {
                if (d.isValid()) d.remove();
                Bukkit.getScheduler().cancelTask(id[0]);
                return;
            }
            a[0] += 0.5;
            double rr = 2 + age[0] * 0.12;
            Location to = eye.clone().add(Math.cos(a[0]) * rr, age[0] * 0.45, Math.sin(a[0]) * rr);
            d.teleport(to);
            d.setTransformation(new Transformation(new Vector3f(-0.4f, -0.4f, -0.4f), new AxisAngle4f((float) a[0], 0.3f, 1, 0.2f), new Vector3f(0.8f, 0.8f, 0.8f), new AxisAngle4f()));
        }, 1L, 1L).getTaskId();
    }

    /** The nearest open sea within 90 blocks (a direction to come from), or null. */
    private Vector nearSea(Location at) {
        World w = at.getWorld();
        int sea = w.getSeaLevel() - 1;
        for (int dist = 30; dist <= 90; dist += 15) {
            for (int i = 0; i < 16; i++) {
                double ang = Math.PI * 2 * i / 16;
                int x = at.getBlockX() + (int) (Math.cos(ang) * dist), z = at.getBlockZ() + (int) (Math.sin(ang) * dist);
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                Block b = w.getBlockAt(x, sea, z);
                if (b.getType() == Material.WATER && w.getBlockAt(x, sea - 3, z).getType() == Material.WATER) return new Vector(Math.cos(ang), 0, Math.sin(ang));
            }
        }
        return null;
    }

    /** A flood rises around the spot; a tsunami rolls in from the sea and leaves the streets flooded. */
    private void flood(Location at, boolean wave, Vector fromSea) {
        World w = at.getWorld();
        int radius = cfg().getInt("town.disasters.flood-radius", 22);
        BlockData water = Material.WATER.createBlockData();
        int baseY = at.getBlockY() - 1;
        if (wave) w.setStorm(true);
        else {
            w.setStorm(true);
            w.setThundering(true);
        }
        int[] step = {0};
        int steps = wave ? radius * 2 + 10 : 20;
        task = Bukkit.getScheduler().runTaskTimer(t.plugin(), () -> {
            step[0]++;
            if (wave) {
                // the wall of water: a line across the wave's direction, moving inland
                Vector dir = fromSea.clone().multiply(-1);
                Location front = at.clone().add(fromSea.clone().multiply(radius + 6)).add(dir.clone().multiply(step[0]));
                Vector side = new Vector(-dir.getZ(), 0, dir.getX());
                int height = Math.max(1, 5 - step[0] / Math.max(1, radius / 2));
                for (int s = -radius; s <= radius; s++) {
                    Location col = front.clone().add(side.clone().multiply(s));
                    int g = w.getHighestBlockYAt(col, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                    if (g > baseY + 4) continue;
                    for (int y = g + 1; y <= g + height; y++) {
                        Block b = w.getBlockAt(col.getBlockX(), y, col.getBlockZ());
                        if (b.getType().isAir() || Tag.FLOWERS.isTagged(b.getType()) || b.getType() == Material.SHORT_GRASS) set(b, water);
                    }
                    if (s % 4 == 0) w.spawnParticle(Particle.SPLASH, col.clone().add(0, g + height, 0), 20, 1, 0.5, 1, 0.1);
                }
                if (step[0] % 6 == 0) w.playSound(front, Sound.WEATHER_RAIN_ABOVE, 3f, 0.5f);
                for (Entity e : w.getNearbyEntities(front, radius, 6, 3)) {
                    if (t.isOurs(e) || e instanceof org.bukkit.entity.Display) continue;
                    e.setVelocity(dir.clone().multiply(0.9).setY(0.3));
                    if (e instanceof LivingEntity le) le.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 60, 0, true, false));
                }
            } else {
                // the water rises a little each second in the low ground
                int level = baseY + 1 + (step[0] >= 10 ? 1 : 0);
                for (int i = 0; i < 160; i++) {
                    int x = at.getBlockX() + r.nextInt(radius * 2 + 1) - radius, z = at.getBlockZ() + r.nextInt(radius * 2 + 1) - radius;
                    if ((x - at.getBlockX()) * (x - at.getBlockX()) + (z - at.getBlockZ()) * (z - at.getBlockZ()) > radius * radius) continue;
                    int g = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                    for (int y = g + 1; y <= level; y++) {
                        Block b = w.getBlockAt(x, y, z);
                        if (b.getType().isAir()) set(b, water);
                    }
                }
                w.spawnParticle(Particle.FALLING_WATER, at.clone().add(0, 12, 0), 200, radius, 2, radius, 0);
            }
            if (step[0] >= steps) over();
        }, 0L, wave ? 4L : 20L).getTaskId();
    }

    private void earthquake(Location at) {
        World w = at.getWorld();
        int[] sec = {0};
        double ang = r.nextDouble() * Math.PI;
        Vector dir = new Vector(Math.cos(ang), 0, Math.sin(ang));
        task = Bukkit.getScheduler().runTaskTimer(t.plugin(), () -> {
            sec[0]++;
            for (Player p : w.getPlayers()) {
                if (p.getLocation().distanceSquared(at) > 60 * 60) continue;
                p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 120, 0, true, false));
                if (p.isOnGround()) p.setVelocity(new Vector((r.nextDouble() - 0.5) * 0.3, 0.15, (r.nextDouble() - 0.5) * 0.3));
                p.playSound(p.getLocation(), Sound.BLOCK_DEEPSLATE_BREAK, 1f, 0.5f);
            }
            w.spawnParticle(Particle.BLOCK, at, 120, 20, 0.5, 20, Material.DIRT.createBlockData());
            // cracks in stonework, and a fissure opening along a line
            for (int i = 0; i < 40; i++) {
                int x = at.getBlockX() + r.nextInt(41) - 20, z = at.getBlockZ() + r.nextInt(41) - 20;
                Block b = w.getHighestBlockAt(x, z);
                for (int dy = 0; dy < 6; dy++) {
                    Block c = b.getRelative(0, -dy, 0);
                    Material crack = cracked(c.getType());
                    if (crack != null && !changed(c)) {
                        set(c, crack.createBlockData());
                        break;
                    }
                }
            }
            int len = sec[0] * 2;
            for (int s = -len; s <= len; s += 1) {
                Location l = at.clone().add(dir.clone().multiply(s));
                int g = w.getHighestBlockYAt(l, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                for (int dy = 0; dy < 3; dy++) {
                    Block b = w.getBlockAt(l.getBlockX(), g - dy, l.getBlockZ());
                    if (!b.getType().isAir() && !changed(b)) set(b, Material.AIR.createBlockData());
                }
            }
            if (sec[0] >= 10) over();
        }, 0L, 20L).getTaskId();
    }

    private static Material cracked(Material m) {
        return switch (m) {
            case STONE_BRICKS -> Material.CRACKED_STONE_BRICKS;
            case DEEPSLATE_BRICKS -> Material.CRACKED_DEEPSLATE_BRICKS;
            case DEEPSLATE_TILES -> Material.CRACKED_DEEPSLATE_TILES;
            case POLISHED_BLACKSTONE_BRICKS -> Material.CRACKED_POLISHED_BLACKSTONE_BRICKS;
            case NETHER_BRICKS -> Material.CRACKED_NETHER_BRICKS;
            case COBBLESTONE, STONE -> Material.COBBLESTONE;
            case GRASS_BLOCK, DIRT_PATH -> Material.COARSE_DIRT;
            case GLASS -> Material.AIR;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ rebuilding

    private void rebuild() {
        restoring = true;
        List<Long> order = new ArrayList<>(original.keySet());
        // from the bottom up, so nothing is put back hanging in the air
        order.sort((a, b) -> Integer.compare(uy(a), uy(b)));
        int per = Math.max(20, order.size() / 40);
        int[] i = {0};
        String where = town;
        Bukkit.broadcast(SmpPlugin.mm("<green>The townsfolk of " + (where == null ? "the town" : where) + " are rebuilding."));
        int[] id = {0};
        id[0] = Bukkit.getScheduler().runTaskTimer(t.plugin(), () -> {
            for (int n = 0; n < per && i[0] < order.size(); n++, i[0]++) {
                long k = order.get(i[0]);
                BlockData d = original.remove(k);
                if (d == null) continue;
                Block b = world.getBlockAt(ux(k), uy(k), uz(k));
                b.setBlockData(d, false);
                if (n % 6 == 0) world.spawnParticle(Particle.WAX_OFF, b.getLocation().add(0.5, 0.5, 0.5), 3, 0.3, 0.3, 0.3, 0);
            }
            if (i[0] >= order.size()) {
                Bukkit.getScheduler().cancelTask(id[0]);
                original.clear();
                touched.clear();
                restoring = false;
                file.delete();
                if (world != null) {
                    world.setStorm(false);
                    world.setThundering(false);
                }
                Bukkit.broadcast(SmpPlugin.mm("<green>" + (where == null ? "The town" : where) + " is rebuilt, good as new."));
            }
        }, 0L, 1L).getTaskId();
    }

    private void restoreAll() {
        if (world == null) return;
        for (var e : original.entrySet()) {
            long k = e.getKey();
            world.getChunkAt(ux(k) >> 4, uz(k) >> 4);
            world.getBlockAt(ux(k), uy(k), uz(k)).setBlockData(e.getValue(), false);
        }
        original.clear();
        touched.clear();
        active = null;
        file.delete();
    }

    /** /mrsmp disaster ... */
    String admin(org.bukkit.command.CommandSender s, String[] a) {
        String what = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "status";
        switch (what) {
            case "status" -> {
                return active != null ? active.label + " under way in " + town + "." : !original.isEmpty() ? original.size() + " damaged blocks in " + town + ", repairs "
                        + (restoring ? "under way" : "in " + Math.max(0, (restoreAt - System.currentTimeMillis()) / 1000) + "s") + "."
                        : "All quiet. Next disaster in about " + Math.max(0, (nextAt - System.currentTimeMillis()) / 60_000L) + " minutes.";
            }
            case "repair", "restore" -> {
                if (active != null) return "Wait until it's over.";
                restoreAt = 0;
                return "Repairs start now.";
            }
            default -> {
                Kind k = what.equals("random") ? null : Kind.parse(what);
                if (k == null && !what.equals("random")) return "/mrsmp disaster <meteor|tornado|flood|tsunami|earthquake|random|status|repair> [here|town]";
                String where = a.length > 2 ? a[2] : null;
                Player near = "here".equalsIgnoreCase(where) && s instanceof Player p ? p : null;
                return strike(k, near != null ? null : where, near);
            }
        }
    }

    // ------------------------------------------------------------------ keeping the damage tidy

    private boolean inDamage(Block b) {
        return !original.isEmpty() && changed(b);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!inDamage(e.getBlock())) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(SmpPlugin.mm("<gray>Leave the wreckage: the townsfolk will put it right."));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!inDamage(e.getBlockPlaced())) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(SmpPlugin.mm("<gray>Don't build in the wreckage: the townsfolk will put it right."));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucket(PlayerBucketFillEvent e) {
        if (inDamage(e.getBlockClicked()) || inDamage(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (inDamage(e.getBlock()) || inDamage(e.getToBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent e) {
        if (original.isEmpty()) return;
        Block b = e.getBlock();
        if (changed(b) || changed(b.getRelative(BlockFace.DOWN)) || changed(b.getRelative(BlockFace.UP))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityChangeBlockEvent e) {
        if (!original.isEmpty() && (changed(e.getBlock()) || changed(e.getBlock().getRelative(BlockFace.DOWN)))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent e) {
        if (!original.isEmpty() && world == e.getBlock().getWorld() && near(e.getBlock(), 8)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onItem(ItemSpawnEvent e) {
        if (!original.isEmpty() && world == e.getLocation().getWorld() && near(e.getLocation().getBlock(), 2)) e.setCancelled(true);
    }

    private boolean near(Block b, int r) {
        for (int dx = -r; dx <= r; dx += Math.max(1, r)) for (int dy = -r; dy <= r; dy += Math.max(1, r)) for (int dz = -r; dz <= r; dz += Math.max(1, r)) {
            if (changed(b.getRelative(dx, dy, dz))) return true;
        }
        return changed(b);
    }
}
