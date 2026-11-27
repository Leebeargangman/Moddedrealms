package net.moddedrealms.guard.replay;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.moddedrealms.guard.GuardPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

/**
 * Keeps the last few minutes of every online player in memory (and of any mob that fights one), so that when
 * something is flagged or reported we can save what happened <em>before</em> it, like Hypixel's replays.
 */
public final class Recorder implements Listener {
    private final GuardPlugin plugin;
    private long tick;
    private final Map<UUID, Live> tracks = new HashMap<>();
    private final ArrayDeque<Change> changes = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<Chat> chat = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Integer> pendingFlags = new HashMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();

    private static final class Live {
        UUID id;
        String type, name, texture, signature;
        float width, height;
        boolean player;
        long lastInvolved;
        final ArrayDeque<Frame> frames = new ArrayDeque<>();
    }

    private record Change(long tick, String world, int x, int y, int z, String before, String after) {}

    private record Chat(long tick, String name, String message) {}

    private record Pending(String id, UUID subject, String subjectName, String reason, long trigger, long end, long created,
                           Consumer<Clip> done) {}

    public Recorder(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    public long tick() {
        return tick;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::onTick, 1L, 1L);
    }

    private int bufferTicks() {
        return plugin.settings().bufferSeconds * 20 + plugin.settings().secondsAfter * 20 + 40;
    }

    private void onTick() {
        tick++;
        int keep = bufferTicks();
        for (Player p : Bukkit.getOnlinePlayers()) record(p, true);
        Iterator<Live> it = tracks.values().iterator();
        while (it.hasNext()) {
            Live l = it.next();
            if (!l.player && tick - l.lastInvolved <= 300) {
                Entity e = Bukkit.getEntity(l.id);
                if (e != null && e.isValid()) record(e, false);
            }
            while (!l.frames.isEmpty() && tick - l.frames.peekFirst().tick > keep) l.frames.pollFirst();
            if (l.frames.isEmpty()) it.remove();
        }
        while (!changes.isEmpty() && tick - changes.peekFirst().tick > keep) changes.pollFirst();
        while (!chat.isEmpty() && tick - chat.peek().tick > keep) chat.poll();
        pendingFlags.clear();

        if (!pending.isEmpty()) {
            List<Pending> due = new ArrayList<>();
            for (Pending p : pending.values()) if (tick >= p.end) due.add(p);
            for (Pending p : due) {
                pending.remove(p.id);
                finish(p);
            }
        }
    }

    private void record(Entity e, boolean isPlayer) {
        Live l = tracks.get(e.getUniqueId());
        if (l == null) {
            l = new Live();
            l.id = e.getUniqueId();
            l.type = e.getType().name();
            l.player = isPlayer;
            l.name = e instanceof Player p ? p.getName() : e.customName() != null
                    ? PlainTextComponentSerializer.plainText().serialize(e.customName()) : pretty(e.getType().name());
            l.width = (float) e.getWidth();
            l.height = (float) e.getHeight();
            if (e instanceof Player p) {
                for (ProfileProperty prop : p.getPlayerProfile().getProperties()) {
                    if (prop.getName().equals("textures")) {
                        l.texture = prop.getValue();
                        l.signature = prop.getSignature();
                    }
                }
            }
            tracks.put(l.id, l);
        }
        Location loc = e.getLocation();
        Frame f = new Frame();
        f.tick = tick;
        f.world = loc.getWorld().getName();
        f.x = loc.getX();
        f.y = loc.getY();
        f.z = loc.getZ();
        f.yaw = e instanceof LivingEntity le ? le.getBodyYaw() : loc.getYaw();
        if (e instanceof Player) f.yaw = loc.getYaw();
        f.pitch = loc.getPitch();
        int flags = pendingFlags.getOrDefault(l.id, 0);
        if (e instanceof Player p) {
            if (p.isSneaking()) flags |= Frame.SNEAK;
            if (p.isSprinting()) flags |= Frame.SPRINT;
            if (p.isSwimming()) flags |= Frame.SWIM;
            if (p.isGliding()) flags |= Frame.GLIDE;
            if (p.isSleeping()) flags |= Frame.SLEEP;
        } else if (e.getPose() == Pose.SNEAKING) {
            flags |= Frame.SNEAK;
        }
        f.flags = flags;
        if (e instanceof LivingEntity le) {
            EntityEquipment eq = le.getEquipment();
            if (eq != null) {
                f.main = type(eq.getItemInMainHand());
                f.off = type(eq.getItemInOffHand());
                f.head = type(eq.getHelmet());
                f.chest = type(eq.getChestplate());
                f.legs = type(eq.getLeggings());
                f.feet = type(eq.getBoots());
            }
        }
        l.frames.addLast(f);
    }

    private static Material type(ItemStack it) {
        return it == null || it.getType().isAir() ? null : it.getType();
    }

    private static String pretty(String type) {
        String s = type.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Positions of an entity during the last {@code ticks} ticks (newest last). Main thread. */
    public List<double[]> recentPositions(UUID id, int ticks) {
        List<double[]> out = new ArrayList<>();
        Live l = tracks.get(id);
        if (l == null) return out;
        Iterator<Frame> it = l.frames.descendingIterator();
        while (it.hasNext()) {
            Frame f = it.next();
            if (tick - f.tick > ticks) break;
            out.add(0, new double[]{f.x, f.y, f.z});
        }
        return out;
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() == PlayerAnimationType.ARM_SWING) pendingFlags.merge(e.getPlayer().getUniqueId(), Frame.SWING, (a, b) -> a | b);
        else if (e.getAnimationType() == PlayerAnimationType.OFF_ARM_SWING) pendingFlags.merge(e.getPlayer().getUniqueId(), Frame.OFFHAND_SWING, (a, b) -> a | b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        Entity victim = e.getEntity();
        if (!(victim instanceof LivingEntity)) return;
        pendingFlags.merge(victim.getUniqueId(), Frame.HURT, (a, b) -> a | b);
        if (e instanceof EntityDamageByEntityEvent by) {
            Entity d = by.getDamager();
            if (d instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) d = shooter;
            if (d instanceof Player && !(victim instanceof Player)) involve(victim);
            if (victim instanceof Player && !(d instanceof Player) && d instanceof LivingEntity) involve(d);
        }
    }

    /** Hits from players are also tracked when cancelled (e.g. in protected regions), for reach history. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player && !(e.getEntity() instanceof Player) && e.getEntity() instanceof LivingEntity) involve(e.getEntity());
    }

    private void involve(Entity e) {
        Live l = tracks.get(e.getUniqueId());
        if (l == null) {
            record(e, false);
            l = tracks.get(e.getUniqueId());
        }
        l.lastInvolved = tick;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        var b = e.getBlock();
        changes.addLast(new Change(tick, b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), b.getBlockData().getAsString(), "minecraft:air"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        var b = e.getBlockPlaced();
        changes.addLast(new Change(tick, b.getWorld().getName(), b.getX(), b.getY(), b.getZ(),
                e.getBlockReplacedState().getBlockData().getAsString(), b.getBlockData().getAsString()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        chat.add(new Chat(tick, e.getPlayer().getName(), PlainTextComponentSerializer.plainText().serialize(e.message())));
    }

    // ------------------------------------------------------------------ clips

    public boolean isPending(String id) {
        return pending.containsKey(id);
    }

    public String pendingFor(UUID subject) {
        for (Pending p : pending.values()) if (p.subject.equals(subject)) return p.id;
        return null;
    }

    public long ticksUntilReady(String id) {
        Pending p = pending.get(id);
        return p == null ? 0 : Math.max(0, p.end - tick);
    }

    /** Starts a clip now; it is written {@code after} seconds from now and handed to {@code done} (main thread). */
    public void request(String id, UUID subject, String name, String reason, int before, int after, Consumer<Clip> done) {
        pending.put(id, new Pending(id, subject, name, reason, tick - before * 20L, tick + after * 20L, System.currentTimeMillis(), done));
    }

    /** On shutdown: write everything that is still recording, with what we have. */
    public void flushPending() {
        for (Pending p : new ArrayList<>(pending.values())) finish(new Pending(p.id, p.subject, p.subjectName, p.reason, p.trigger, tick, p.created, p.done));
        pending.clear();
    }

    private void finish(Pending p) {
        Clip c = new Clip();
        c.id = p.id;
        c.subject = p.subject;
        c.subjectName = p.subjectName;
        c.reason = p.reason;
        c.created = p.created;
        long start = p.trigger;
        long end = p.end;
        c.length = (int) (end - start);

        Live subj = tracks.get(p.subject);
        Map<Long, Frame> subjAt = new HashMap<>();
        String world = null;
        if (subj != null) {
            for (Frame f : subj.frames) {
                if (f.tick >= start && f.tick <= end) {
                    subjAt.put(f.tick, f);
                    world = f.world;
                }
            }
        }
        if (world == null) {
            Player online = Bukkit.getPlayer(p.subject);
            world = online != null ? online.getWorld().getName() : Bukkit.getWorlds().get(0).getName();
        }
        c.world = world;
        double r2 = plugin.settings().replayRadius * plugin.settings().replayRadius;
        Set<String> names = new HashSet<>();

        for (Live l : tracks.values()) {
            List<Frame> keep = new ArrayList<>();
            for (Frame f : l.frames) {
                if (f.tick < start || f.tick > end || !f.world.equals(world)) continue;
                if (l != subj) {
                    Frame s = nearestSubject(subjAt, f.tick);
                    if (s == null) continue;
                    double dx = s.x - f.x, dy = s.y - f.y, dz = s.z - f.z;
                    if (dx * dx + dy * dy + dz * dz > r2) continue;
                }
                Frame copy = copy(f);
                copy.tick = f.tick - start;
                keep.add(copy);
            }
            if (keep.isEmpty()) continue;
            Clip.Track t = new Clip.Track();
            t.id = l.id;
            t.type = l.type;
            t.name = l.name;
            t.texture = l.texture;
            t.signature = l.signature;
            t.width = l.width;
            t.height = l.height;
            t.player = l.player;
            t.frames = keep.toArray(new Frame[0]);
            c.tracks.add(t);
            if (l.player) names.add(l.name);
        }
        double rb = r2 * 1.5;
        for (Change ch : changes) {
            if (ch.tick < start || ch.tick > end || !ch.world.equals(world)) continue;
            Frame s = nearestSubject(subjAt, ch.tick);
            if (s == null) continue;
            double dx = s.x - ch.x, dy = s.y - ch.y, dz = s.z - ch.z;
            if (dx * dx + dy * dy + dz * dz > rb) continue;
            c.blocks.add(new Clip.BlockChange((int) (ch.tick - start), ch.x, ch.y, ch.z, ch.before, ch.after));
        }
        for (Chat l : chat) {
            if (l.tick >= start && l.tick <= end && names.contains(l.name)) c.chat.add(new Clip.ChatLine((int) (l.tick - start), l.name, l.message));
        }
        p.done.accept(c);
    }

    private static Frame nearestSubject(Map<Long, Frame> at, long tick) {
        for (int d = 0; d <= 20; d++) {
            Frame f = at.get(tick - d);
            if (f != null) return f;
            f = at.get(tick + d);
            if (f != null) return f;
        }
        return null;
    }

    private static Frame copy(Frame f) {
        Frame c = new Frame();
        c.tick = f.tick;
        c.world = f.world;
        c.x = f.x;
        c.y = f.y;
        c.z = f.z;
        c.yaw = f.yaw;
        c.pitch = f.pitch;
        c.flags = f.flags;
        c.main = f.main;
        c.off = f.off;
        c.head = f.head;
        c.chest = f.chest;
        c.legs = f.legs;
        c.feet = f.feet;
        return c;
    }

    public File folder() {
        return new File(plugin.getDataFolder(), "replays");
    }
}
