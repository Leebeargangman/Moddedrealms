package net.moddedrealms.testplayers;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/**
 * All test players: spawning and removing them, moving them every tick, and remembering them across restarts.
 * Everything here runs on the main thread. Mistakes the admin can fix (unknown name, bad coordinates...) are
 * thrown as IllegalArgumentException with a message meant for them.
 */
final class TestPlayers {

    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final long RESPAWN_MS = 3_000;

    private final TestPlayersPlugin plugin;
    private final ChunkTickets chunks;
    private final NamespacedKey key;
    private final File file;
    private final Map<String, TestPlayer> players = new LinkedHashMap<>();
    private long ticks;
    private boolean dirty;

    TestPlayers(TestPlayersPlugin plugin, ChunkTickets chunks) {
        this.plugin = plugin;
        this.chunks = chunks;
        this.key = new NamespacedKey(plugin, "test_player");
        this.file = new File(plugin.getDataFolder(), "players.yml");
    }

    private Settings s() {
        return plugin.settings();
    }

    Collection<TestPlayer> all() {
        return players.values();
    }

    TestPlayer get(String name) {
        return name == null ? null : players.get(name.toLowerCase(Locale.ROOT));
    }

    /** Which test player a mannequin is (null if it isn't one of ours). */
    TestPlayer of(Entity e) {
        String id = e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        TestPlayer tp = id == null ? null : players.get(id);
        return tp != null && tp.body == e ? tp : null;
    }

    boolean isOurs(Entity e) {
        return e.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    /** "all" or "*", a prefix ending in * ("Load*"), or one name. */
    List<TestPlayer> select(String spec) {
        String s = spec.toLowerCase(Locale.ROOT);
        List<TestPlayer> out = new ArrayList<>();
        if (s.equals("all") || s.equals("*")) out.addAll(players.values());
        else if (s.endsWith("*")) {
            String prefix = s.substring(0, s.length() - 1);
            for (TestPlayer tp : players.values()) if (tp.id.startsWith(prefix)) out.add(tp);
        } else if (players.containsKey(s)) out.add(players.get(s));
        if (out.isEmpty()) throw new IllegalArgumentException("No test player matches \"" + spec + "\". Try /testplayer list.");
        return out;
    }

    // ------------------------------------------------------------ creating and removing

    /** A new test player (not in the world yet). */
    TestPlayer create(String name) {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("Names are 1-16 letters, numbers or _.");
        if (players.containsKey(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("There is already a test player called " + name + ".");
        if (players.size() >= s().maxPlayers()) throw new IllegalArgumentException("That's the limit of " + s().maxPlayers() + " test players (max-players in config.yml).");
        TestPlayer tp = new TestPlayer(name);
        players.put(tp.id, tp);
        dirty = true;
        return tp;
    }

    /** Names for a batch: "Load" x3 gives Load1, Load2, Load3 (skipping names already taken). */
    List<String> batchNames(String base, int count) {
        if (count == 1) return List.of(base);
        if (!NAME.matcher(base).matches() || base.length() > 12) throw new IllegalArgumentException("For several at once use a name of up to 12 letters, numbers or _; they get numbered.");
        List<String> names = new ArrayList<>();
        for (int i = 1; names.size() < count && i < 10_000; i++) {
            if (!players.containsKey((base + i).toLowerCase(Locale.ROOT))) names.add(base + i);
        }
        return names;
    }

    /** Put a test player in the world at loc (once its chunk has loaded). */
    void spawn(TestPlayer tp, Location loc) {
        tp.wanted = true;
        if (tp.entity() != null || tp.spawning) return;
        tp.spawning = true;
        World world = loc.getWorld();
        world.getChunkAtAsync(loc).thenAccept(c -> {
            tp.spawning = false;
            if (!tp.wanted || tp.entity() != null || players.get(tp.id) != tp) return;
            Location at = standable(loc);
            tp.body = world.spawn(at, Mannequin.class, m -> dress(m, tp));
            tp.spawnedAt = System.currentTimeMillis();
            tp.lastKnown = at;
            tp.progressMark = at;
            tp.stuckTicks = 0;
            if (tp.home == null) tp.home = at;
            chunks.update(tp, at, s().chunkRadius());
            dirty = true;
        });
    }

    private void dress(Mannequin m, TestPlayer tp) {
        m.setPersistent(false); // never saved in the world: players.yml is what remembers test players
        m.getPersistentDataContainer().set(key, PersistentDataType.STRING, tp.id);
        m.customName(Component.text(tp.name));
        m.setCustomNameVisible(true);
        m.setDescription(Component.text(tp.model != null && tp.ai ? "test player · " + tp.model : "test player", NamedTextColor.GRAY));
        m.setInvulnerable(s().invulnerable());
        m.setRemoveWhenFarAway(false);
        m.setCanPickupItems(false);
        if (s().skinFromName()) m.setProfile(ResolvableProfile.resolvableProfile().name(tp.name).build());
    }

    /** The AI model changed: update the label under the name. */
    void relabel(TestPlayer tp) {
        Mannequin m = tp.entity();
        if (m != null) m.setDescription(Component.text(tp.model != null && tp.ai ? "test player · " + tp.model : "test player", NamedTextColor.GRAY));
        dirty = true;
    }

    /** Take it out of the world. remove = forget it completely, otherwise it can be started again. */
    void despawn(TestPlayer tp, boolean remove) {
        tp.wanted = false;
        Mannequin m = tp.entity();
        if (m != null) {
            tp.lastKnown = m.getLocation();
            m.remove();
        }
        tp.body = null;
        chunks.release(tp);
        plugin.builder().cancel(tp);
        if (tp.mode == TestPlayer.Mode.BUILD) tp.mode = TestPlayer.Mode.IDLE;
        if (remove) {
            players.remove(tp.id);
            plugin.builder().forget(tp);
        }
        dirty = true;
    }

    /** Remove all mannequins (on disable) without forgetting which test players were in the world. */
    void despawnAllForShutdown() {
        for (TestPlayer tp : players.values()) {
            Mannequin m = tp.entity();
            if (m != null) {
                tp.lastKnown = m.getLocation();
                m.remove();
            }
            tp.body = null;
            chunks.release(tp);
        }
    }

    /** Mannequins left over from a crash (they aren't saved, but a crash can leave some loaded): remove them. */
    void removeStrays() {
        for (World w : Bukkit.getWorlds()) {
            for (Mannequin m : w.getEntitiesByClass(Mannequin.class)) if (isOurs(m) && of(m) == null) m.remove();
        }
    }

    /** It died: come back home in a few seconds. */
    void died(TestPlayer tp) {
        plugin.builder().cancel(tp);
        if (tp.mode == TestPlayer.Mode.BUILD) tp.mode = TestPlayer.Mode.IDLE;
        tp.deaths++;
        tp.body = null;
        tp.respawnAt = System.currentTimeMillis() + RESPAWN_MS;
        tp.lastResult = "died";
        chunks.release(tp);
        dirty = true;
    }

    /** Where to spawn near loc: loc itself if a player could stand there, else on top of the ground. */
    private static Location standable(Location loc) {
        Block feet = loc.getBlock();
        if (feet.isPassable() && feet.getRelative(BlockFace.UP).isPassable()) return loc;
        Location top = loc.getWorld().getHighestBlockAt(loc).getLocation().add(0.5, 1, 0.5);
        top.setYaw(loc.getYaw());
        return top;
    }

    // ------------------------------------------------------------ orders (commands, the website and the AI)

    void idle(TestPlayer tp) {
        // Any new order ends a build in progress, or one it was on its way to.
        plugin.builder().cancel(tp);
        tp.pendingBuild = null;
        tp.mode = TestPlayer.Mode.IDLE;
        tp.target = null;
        dirty = true;
    }

    void goTo(TestPlayer tp, Location where) {
        plugin.builder().cancel(tp);
        tp.pendingBuild = null;
        tp.mode = TestPlayer.Mode.GOTO;
        tp.target = where.clone();
        tp.stuckTicks = 0;
        tp.detourUntil = 0;
        tp.bestDist = Double.MAX_VALUE;
        tp.ticksSinceBetter = 0;
        dirty = true;
    }

    /** Follow a player, or another test player. */
    void follow(TestPlayer tp, String player) {
        plugin.builder().cancel(tp);
        tp.pendingBuild = null;
        Player p = Bukkit.getPlayerExact(player);
        TestPlayer other = p == null ? get(player) : null;
        if (p == null && (other == null || other == tp || other.entity() == null)) throw new IllegalArgumentException("\"" + player + "\" isn't online.");
        tp.mode = TestPlayer.Mode.FOLLOW;
        tp.follow = p != null ? p.getName() : other.name;
        tp.stuckTicks = 0;
        dirty = true;
    }

    void wander(TestPlayer tp, double radius) {
        plugin.builder().cancel(tp);
        tp.pendingBuild = null;
        Mannequin m = tp.entity();
        tp.mode = TestPlayer.Mode.WANDER;
        tp.wanderRadius = Math.max(4, Math.min(radius, 10_000));
        if (m != null) tp.home = m.getLocation();
        tp.target = null;
        dirty = true;
    }

    void walk(TestPlayer tp, float yaw) {
        plugin.builder().cancel(tp);
        tp.pendingBuild = null;
        tp.mode = TestPlayer.Mode.WALK;
        tp.walkYaw = yaw;
        tp.stuckTicks = 0;
        dirty = true;
    }

    /** Chat as the test player, clearly marked as a test player. */
    void say(TestPlayer tp, String text) {
        String t = text.replaceAll("[\\p{Cntrl}§]", "").trim();
        if (t.isEmpty()) return;
        if (t.length() > 200) t = t.substring(0, 200);
        tp.lastSaidAt = System.currentTimeMillis();
        Bukkit.broadcast(Component.text(s().chatPrefix(), NamedTextColor.GRAY)
                .append(Component.text("<" + tp.name + "> ", NamedTextColor.WHITE))
                .append(Component.text(t, NamedTextColor.WHITE)));
        plugin.link().said(tp, t);
        // AI test players nearby hear it, so they can talk to each other (Brain limits how often).
        Mannequin me = tp.entity();
        if (me == null) return;
        for (TestPlayer other : players.values()) {
            Mannequin om = other == tp ? null : other.entity();
            if (om == null || !other.ai || other.model == null || om.getWorld() != me.getWorld()) continue;
            if (om.getLocation().distanceSquared(me.getLocation()) <= 32 * 32 || t.toLowerCase(Locale.ROOT).contains(other.id)) {
                plugin.brain().heardTestPlayer(other, tp.name, t);
            }
        }
    }

    /** "north", "south-west", "e", or a yaw in degrees. */
    static float parseDirection(String s) {
        return switch (s.toLowerCase(Locale.ROOT).replace("_", "-")) {
            case "south", "s" -> 0;
            case "south-west", "southwest", "sw" -> 45;
            case "west", "w" -> 90;
            case "north-west", "northwest", "nw" -> 135;
            case "north", "n" -> 180;
            case "north-east", "northeast", "ne" -> 225;
            case "east", "e" -> 270;
            case "south-east", "southeast", "se" -> 315;
            default -> {
                try {
                    yield Float.parseFloat(s);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Direction: north, south, east, west (or ne, sw...) or a yaw in degrees.");
                }
            }
        };
    }

    // ------------------------------------------------------------ every tick

    void tick() {
        ticks++;
        long now = System.currentTimeMillis();
        for (TestPlayer tp : players.values()) {
            if (!tp.wanted) continue;
            Mannequin m = tp.entity();
            if (m == null) {
                if (tp.body != null) died(tp); // removed by something else (/kill, a plugin...)
                if (!tp.spawning && now >= tp.respawnAt) {
                    Location back = tp.home != null && tp.home.isWorldLoaded() ? tp.home : tp.lastKnown;
                    if (back != null && back.isWorldLoaded()) spawn(tp, back);
                }
                continue;
            }
            try {
                step(tp, m);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Moving " + tp.name + " failed", e);
                idle(tp);
            }
        }
        if (ticks % 600 == 0) {
            if (dirty) save();
            plugin.builder().save();
        }
    }

    private void step(TestPlayer tp, Mannequin m) {
        Location loc = m.getLocation();
        if (tp.lastKnown != null && tp.lastKnown.getWorld() == loc.getWorld()) tp.blocksMoved += hdist(tp.lastKnown, loc);
        tp.lastKnown = loc;
        chunks.update(tp, loc, s().chunkRadius());

        if (ticks % 20 == 0) {
            boolean moving = tp.mode != TestPlayer.Mode.IDLE;
            if (moving && tp.progressMark != null && tp.progressMark.getWorld() == loc.getWorld() && hdist(tp.progressMark, loc) < 0.5) tp.stuckTicks += 20;
            else tp.stuckTicks = 0;
            tp.progressMark = loc;
        }

        double speed = tp.sprint ? s().sprintSpeed() : s().walkSpeed();
        switch (tp.mode) {
            case IDLE -> brake(m);
            case GOTO -> {
                if (tp.target == null || tp.target.getWorld() != loc.getWorld()) {
                    finish(tp, "can't go to another world on foot");
                    brake(m);
                    return;
                }
                double d = hdist(tp.target, loc);
                if (d < 1.5) {
                    finish(tp, "arrived at " + tp.target.getBlockX() + " " + tp.target.getBlockY() + " " + tp.target.getBlockZ());
                    brake(m);
                    String[] build = tp.pendingBuild;
                    tp.pendingBuild = null;
                    if (build != null) {
                        try {
                            tp.lastResult = plugin.builder().start(tp, build[0], build[1]);
                        } catch (IllegalArgumentException | IllegalStateException e) {
                            tp.lastResult = "build failed: " + e.getMessage();
                        }
                    }
                    return;
                }
                if (d < tp.bestDist - 1) {
                    tp.bestDist = d;
                    tp.ticksSinceBetter = 0;
                } else if (++tp.ticksSinceBetter > 600) {
                    String[] build = tp.pendingBuild;
                    Location to = tp.target;
                    finish(tp, "stuck " + Math.round(d) + " blocks from " + to.getBlockX() + " " + to.getBlockZ() + " (no way around found in 30 s)");
                    brake(m);
                    if (build != null) {
                        // On its way to build and stuck (a cliff, a ravine): hop into the build zone rather than give up.
                        Location top = to.getWorld().getHighestBlockAt(to.getBlockX(), to.getBlockZ(), org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES).getLocation().add(0.5, 1, 0.5);
                        m.teleport(top);
                        try {
                            tp.lastResult = "got stuck, hopped into the build zone; " + plugin.builder().start(tp, build[0], build[1]);
                        } catch (IllegalArgumentException | IllegalStateException e) {
                            tp.lastResult = "build failed: " + e.getMessage();
                        }
                    }
                    return;
                }
                head(tp, m, loc, tp.target.getX(), tp.target.getZ(), speed);
            }
            case FOLLOW -> {
                Player p = tp.follow == null ? null : Bukkit.getPlayerExact(tp.follow);
                TestPlayer other = p == null ? get(tp.follow) : null;
                Mannequin om = other == null ? null : other.entity();
                Location pl = p != null ? p.getLocation() : om != null ? om.getLocation() : null;
                if (pl == null || pl.getWorld() != loc.getWorld()) {
                    finish(tp, "lost " + tp.follow);
                    brake(m);
                    return;
                }
                double d = hdist(pl, loc);
                if (d < 2.5) {
                    brake(m);
                    m.lookAt(pl.getX(), pl.getY() + 1.6, pl.getZ(), io.papermc.paper.entity.LookAnchor.EYES);
                } else head(tp, m, loc, pl.getX(), pl.getZ(), d > 8 ? s().sprintSpeed() : speed);
            }
            case WANDER -> {
                if (tp.target == null || hdist(tp.target, loc) < 1.5 || tp.stuckTicks >= 60) {
                    Location c = tp.home != null && tp.home.getWorld() == loc.getWorld() ? tp.home : loc;
                    double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                    double r = tp.wanderRadius * (0.3 + 0.7 * ThreadLocalRandom.current().nextDouble());
                    tp.target = new Location(loc.getWorld(), c.getX() + Math.cos(a) * r, c.getY(), c.getZ() + Math.sin(a) * r);
                    tp.stuckTicks = 0;
                }
                steer(m, loc, tp.target.getX(), tp.target.getZ(), speed);
            }
            case BUILD -> {
                Block next = plugin.builder().next(tp);
                if (next == null) {
                    brake(m);
                    return;
                }
                Location spot = next.getLocation().add(0.5, 0.5, 0.5);
                Location stand = plugin.builder().stand(tp);
                double toStand = stand == null ? 0 : hdist(stand, loc);
                if (toStand < tp.bestDist - 1) {
                    tp.bestDist = toStand;
                    tp.ticksSinceBetter = 0;
                }
                if (toStand > 1.5 && ++tp.ticksSinceBetter > 300) {
                    // 15 s without getting closer to its building spot (up a cliff, across a ravine): hop there.
                    m.teleport(stand);
                    tp.bestDist = 0;
                    tp.ticksSinceBetter = 0;
                } else if (toStand > 1.5) head(tp, m, loc, stand.getX(), stand.getZ(), speed);
                else brake(m);
                // Builds from its spot outside the doorway, or from wherever it got stuck if that's close enough.
                if (hdist(spot, loc) <= 12) {
                    m.lookAt(spot.getX(), spot.getY(), spot.getZ(), io.papermc.paper.entity.LookAnchor.EYES);
                    plugin.builder().work(tp, m);
                }
            }
            case WALK -> {
                if (tp.stuckTicks >= 60) {
                    // Hit a wall or a cliff: turn a bit and carry on.
                    tp.walkYaw += (ThreadLocalRandom.current().nextBoolean() ? 1 : -1) * (45 + ThreadLocalRandom.current().nextInt(46));
                    tp.stuckTicks = 0;
                }
                double rad = Math.toRadians(tp.walkYaw);
                steer(m, loc, loc.getX() - Math.sin(rad) * 8, loc.getZ() + Math.cos(rad) * 8, speed);
            }
        }
    }

    /**
     * Walk toward (tx, tz), going around obstacles: after 2 s without progress (a cliff, a wall, a corner),
     * walk off to one side for a few seconds, then try the direct way again.
     */
    private static void head(TestPlayer tp, Mannequin m, Location loc, double tx, double tz, double speed) {
        long now = System.currentTimeMillis();
        if (tp.stuckTicks >= 40) {
            float direct = (float) Math.toDegrees(Math.atan2(-(tx - loc.getX()), tz - loc.getZ()));
            // Stuck again soon after a detour: that side didn't work, try the other one. Otherwise pick either.
            boolean recent = tp.detourSide != 0 && now - tp.detourUntil < 5000;
            tp.detourSide = recent ? -tp.detourSide : ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
            tp.detourYaw = direct + tp.detourSide * (70 + ThreadLocalRandom.current().nextInt(41));
            tp.detourUntil = now + 2500 + ThreadLocalRandom.current().nextInt(2000);
            tp.stuckTicks = 0;
        }
        if (now < tp.detourUntil) {
            double rad = Math.toRadians(tp.detourYaw);
            steer(m, loc, loc.getX() - Math.sin(rad) * 8, loc.getZ() + Math.cos(rad) * 8, speed);
        } else steer(m, loc, tx, tz, speed);
    }

    private void finish(TestPlayer tp, String result) {
        tp.lastResult = result;
        idle(tp);
    }

    private static void brake(Mannequin m) {
        Vector v = m.getVelocity();
        if (Math.abs(v.getX()) > 1e-3 || Math.abs(v.getZ()) > 1e-3) m.setVelocity(new Vector(0, v.getY(), 0));
    }

    /**
     * Walk toward (tx, tz) at speed blocks per tick: jump onto one-block steps, swim up in water, and wait at the
     * edge of chunks that haven't loaded yet (like a real player's client would) instead of loading them here.
     */
    private static void steer(Mannequin m, Location loc, double tx, double tz, double speed) {
        double dx = tx - loc.getX();
        double dz = tz - loc.getZ();
        double d = Math.hypot(dx, dz);
        if (d < 1e-3) {
            brake(m);
            return;
        }
        double step = Math.min(speed, d);
        dx = dx / d * step;
        dz = dz / d * step;
        World w = loc.getWorld();
        double ax = loc.getX() + dx / step * 0.8;
        double az = loc.getZ() + dz / step * 0.8;
        if (!w.isChunkLoaded((int) Math.floor(ax) >> 4, (int) Math.floor(az) >> 4)) {
            brake(m);
            return;
        }
        Vector v = m.getVelocity();
        double vy = v.getY();
        if (m.isInWater() || m.isInLava()) vy = Math.max(vy, 0.12);
        else if (m.isOnGround() && blocked(w, loc, ax, az)) vy = 0.42;
        m.setVelocity(new Vector(dx, vy, dz));
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        m.setRotation(yaw, 0);
        m.setBodyYaw(yaw);
    }

    /** A one-block step ahead with room above it: worth a jump. */
    private static boolean blocked(World w, Location loc, double ax, double az) {
        Block ahead = w.getBlockAt((int) Math.floor(ax), loc.getBlockY(), (int) Math.floor(az));
        if (ahead.isPassable()) return false;
        return ahead.getRelative(BlockFace.UP).isPassable() && ahead.getRelative(0, 2, 0).isPassable()
                && loc.getBlock().getRelative(0, 2, 0).isPassable();
    }

    static double hdist(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    // ------------------------------------------------------------ saving

    void markDirty() {
        dirty = true;
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (TestPlayer tp : players.values()) {
            ConfigurationSection c = y.createSection("players." + tp.name);
            Mannequin m = tp.entity();
            Location at = m != null ? m.getLocation() : tp.lastKnown;
            c.set("in-world", tp.wanted);
            if (at != null) c.set("location", at);
            if (tp.home != null) c.set("home", tp.home);
            c.set("mode", tp.mode.name());
            if (tp.target != null) c.set("target", tp.target);
            if (tp.follow != null) c.set("follow", tp.follow);
            c.set("wander-radius", tp.wanderRadius);
            c.set("walk-yaw", tp.walkYaw);
            c.set("sprint", tp.sprint);
            if (tp.model != null) c.set("model", tp.model);
            if (tp.staff != null) c.set("staff", tp.staff);
            c.set("ai", tp.ai);
            if (tp.goal != null) c.set("goal", tp.goal);
            if (tp.personality != null) c.set("personality", tp.personality);
            c.set("deaths", tp.deaths);
        }
        try {
            y.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save players.yml", e);
        }
    }

    void load() {
        if (!file.exists()) return;
        ConfigurationSection all = YamlConfiguration.loadConfiguration(file).getConfigurationSection("players");
        if (all == null) return;
        for (String name : all.getKeys(false)) {
            ConfigurationSection c = all.getConfigurationSection(name);
            if (c == null || !NAME.matcher(name).matches() || players.containsKey(name.toLowerCase(Locale.ROOT))) continue;
            TestPlayer tp = new TestPlayer(name);
            tp.lastKnown = location(c, "location");
            tp.home = location(c, "home");
            try {
                tp.mode = TestPlayer.Mode.valueOf(c.getString("mode", "IDLE"));
            } catch (IllegalArgumentException e) {
                tp.mode = TestPlayer.Mode.IDLE;
            }
            if (tp.mode == TestPlayer.Mode.BUILD) tp.mode = TestPlayer.Mode.IDLE; // builds in progress aren't saved
            tp.target = location(c, "target");
            tp.follow = c.getString("follow");
            tp.wanderRadius = c.getDouble("wander-radius", 24);
            tp.walkYaw = (float) c.getDouble("walk-yaw", 0);
            tp.sprint = c.getBoolean("sprint", false);
            tp.model = c.getString("model");
            tp.staff = c.getString("staff");
            tp.ai = c.getBoolean("ai", false) && tp.model != null;
            tp.goal = c.getString("goal");
            tp.personality = c.getString("personality");
            tp.deaths = c.getInt("deaths", 0);
            if ((tp.mode == TestPlayer.Mode.GOTO && tp.target == null) || (tp.mode == TestPlayer.Mode.FOLLOW && tp.follow == null)) tp.mode = TestPlayer.Mode.IDLE;
            players.put(tp.id, tp);
            Location at = tp.lastKnown != null ? tp.lastKnown : tp.home;
            if (c.getBoolean("in-world", false) && s().respawnOnStart() && at != null) spawn(tp, at);
        }
    }

    /** A saved location, or null if it's missing or its world isn't loaded. */
    private static Location location(ConfigurationSection c, String path) {
        try {
            Location l = c.getLocation(path);
            return l != null && l.isWorldLoaded() ? l : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ numbers for /testplayer stats and the website

    int spawnedCount() {
        int n = 0;
        for (TestPlayer tp : players.values()) if (tp.entity() != null) n++;
        return n;
    }

    int chunksHeld() {
        return chunks.held();
    }
}
