package net.moddedrealms.guard.replay;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.moddedrealms.guard.GuardPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * One staff member watching one replay. Players are shown as Mannequins wearing their skin, mobs as their own
 * type with no AI. All of it is visible only to the viewer, who flies around in spectator mode.
 */
public final class ReplaySession {
    private static final double[] SPEEDS = {0.25, 0.5, 1, 2, 4};

    private final GuardPlugin plugin;
    private final ReplayManager manager;
    private final Player viewer;
    private final Clip clip;
    private final Map<Integer, Entity> actors = new HashMap<>();
    private final Map<Integer, Frame> lastShown = new HashMap<>();
    private World world;
    private double cursor;
    private double speed = 1;
    private boolean paused;
    private int lastTick = -1;
    private int blocksApplied;
    private BukkitTask task;
    private Clip.Track subjectTrack;
    private int subjectIdx = -1;

    ReplaySession(GuardPlugin plugin, ReplayManager manager, Player viewer, Clip clip) {
        this.plugin = plugin;
        this.manager = manager;
        this.viewer = viewer;
        this.clip = clip;
    }

    boolean start() {
        world = Bukkit.getWorld(clip.world);
        if (world == null) {
            viewer.sendMessage(Component.text("World " + clip.world + " is not loaded.", NamedTextColor.RED));
            return false;
        }
        for (int i = 0; i < clip.tracks.size(); i++) {
            if (clip.tracks.get(i).id.equals(clip.subject)) {
                subjectTrack = clip.tracks.get(i);
                subjectIdx = i;
            }
        }
        Frame first = subjectTrack != null && subjectTrack.frames.length > 0 ? subjectTrack.frames[0]
                : clip.tracks.isEmpty() || clip.tracks.get(0).frames.length == 0 ? null : clip.tracks.get(0).frames[0];
        if (first == null) {
            viewer.sendMessage(Component.text("This replay is empty (the player may have left before it started).", NamedTextColor.RED));
            return false;
        }

        Location back = viewer.getLocation();
        String restore = back.getWorld().getName() + ";" + back.getX() + ";" + back.getY() + ";" + back.getZ() + ";" + back.getYaw() + ";"
                + back.getPitch() + ";" + viewer.getGameMode().name();
        if (!viewer.getPersistentDataContainer().has(manager.restoreKey, PersistentDataType.STRING)) {
            viewer.getPersistentDataContainer().set(manager.restoreKey, PersistentDataType.STRING, restore);
        }
        viewer.setGameMode(GameMode.SPECTATOR);
        Location start = new Location(world, first.x, first.y + 3, first.z, first.yaw, 30);
        start.add(start.getDirection().multiply(-4));
        viewer.teleport(start);

        // Skip the empty lead-in when the subject joined part-way through the clip.
        cursor = Math.max(0, first.tick - 20);
        blocksApplied = clip.blocks.size();
        syncBlocks((int) cursor);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);

        viewer.sendMessage(Component.text()
                .append(Component.text("▶ Replay " + clip.id, NamedTextColor.AQUA, TextDecoration.BOLD))
                .append(Component.text(" of " + clip.subjectName + " — " + clip.reason + " (" + clip.length / 20 + "s, "
                        + clip.tracks.size() + " entities)", NamedTextColor.GRAY)).build());
        controls();
        return true;
    }

    void controls() {
        viewer.sendMessage(Component.text()
                .append(button("⏯ Pause", "/replay pause", "Pause / resume"))
                .append(button("⏪ 10s", "/replay skip -10", "Back 10 seconds"))
                .append(button("⏩ 10s", "/replay skip 10", "Forward 10 seconds"))
                .append(button("0.5x", "/replay speed 0.5", "Half speed"))
                .append(button("1x", "/replay speed 1", "Normal speed"))
                .append(button("2x", "/replay speed 2", "Double speed"))
                .append(button("◉ POV", "/replay pov", "See through " + clip.subjectName + "'s eyes"))
                .append(button("✖ Stop", "/replay stop", "Stop watching")).build());
    }

    private static Component button(String label, String cmd, String hover) {
        return Component.text("[" + label + "] ", NamedTextColor.YELLOW)
                .hoverEvent(HoverEvent.showText(Component.text(hover)))
                .clickEvent(ClickEvent.runCommand(cmd));
    }

    private void tick() {
        if (!viewer.isOnline()) {
            stop();
            return;
        }
        if (!paused) {
            cursor += speed;
            if (cursor >= clip.length) {
                cursor = clip.length;
                paused = true;
                viewer.sendMessage(Component.text("Replay finished. ", NamedTextColor.GRAY)
                        .append(button("↺ Restart", "/replay skip -9999", "Watch again"))
                        .append(button("✖ Stop", "/replay stop", "Stop watching")));
            }
        }
        int t = (int) cursor;
        if (t != lastTick) {
            render(t);
            chatBetween(lastTick, t);
            lastTick = t;
        }
        viewer.sendActionBar(Component.text()
                .append(Component.text(paused ? "⏸ " : "▶ ", NamedTextColor.AQUA))
                .append(Component.text(time(t) + " / " + time(clip.length), NamedTextColor.WHITE))
                .append(Component.text("  " + fmtSpeed(speed) + "  ", NamedTextColor.YELLOW))
                .append(Component.text(clip.subjectName + " · " + clip.reason, NamedTextColor.GRAY)).build());
    }

    private void render(int t) {
        syncBlocks(t);
        for (int i = 0; i < clip.tracks.size(); i++) {
            Clip.Track tr = clip.tracks.get(i);
            Frame f = tr.at(t);
            Entity a = actors.get(i);
            if (f == null) {
                if (a != null) {
                    a.remove();
                    actors.remove(i);
                    lastShown.remove(i);
                }
                continue;
            }
            if (a == null || !a.isValid()) {
                a = spawn(tr, f);
                if (a == null) continue;
                actors.put(i, a);
            }
            Location to = new Location(world, f.x, f.y, f.z, f.yaw, f.pitch);
            a.teleport(to);
            a.setRotation(f.yaw, f.pitch);
            Frame prev = lastShown.get(i);
            if (a instanceof LivingEntity le) {
                // Swings and hurts that happened in skipped ticks (fast playback) still show.
                boolean swing = false, hurt = false, off = false;
                int from = lastTick < 0 || lastTick > t ? t : lastTick + 1;
                for (int k = from; k <= t; k++) {
                    Frame fk = tr.at(k);
                    if (fk == null || fk.tick != k) continue;
                    swing |= fk.has(Frame.SWING);
                    off |= fk.has(Frame.OFFHAND_SWING);
                    hurt |= fk.has(Frame.HURT);
                }
                if (swing) le.swingMainHand();
                if (off) le.swingOffHand();
                if (hurt) le.playHurtAnimation(f.yaw);
                if (prev == null || prev.flags != f.flags) {
                    Pose pose = f.has(Frame.SLEEP) ? Pose.SLEEPING : f.has(Frame.GLIDE) ? Pose.FALL_FLYING
                            : f.has(Frame.SWIM) ? Pose.SWIMMING : f.has(Frame.SNEAK) ? Pose.SNEAKING : Pose.STANDING;
                    if (a instanceof Mannequin || pose == Pose.STANDING || pose == Pose.SNEAKING) le.setPose(pose, true);
                }
                if (prev == null || prev.main != f.main || prev.off != f.off || prev.head != f.head || prev.chest != f.chest
                        || prev.legs != f.legs || prev.feet != f.feet) {
                    EntityEquipment eq = le.getEquipment();
                    if (eq != null) {
                        eq.setItemInMainHand(item(f.main));
                        eq.setItemInOffHand(item(f.off));
                        eq.setHelmet(item(f.head));
                        eq.setChestplate(item(f.chest));
                        eq.setLeggings(item(f.legs));
                        eq.setBoots(item(f.feet));
                    }
                }
            }
            lastShown.put(i, f);
        }
    }

    private static ItemStack item(Material m) {
        return m == null ? null : new ItemStack(m);
    }

    private Entity spawn(Clip.Track tr, Frame f) {
        Location at = new Location(world, f.x, f.y, f.z, f.yaw, f.pitch);
        Entity e = null;
        if (!tr.player) {
            try {
                EntityType type = EntityType.valueOf(tr.type);
                if (type.isAlive() && type.isSpawnable() && type.getEntityClass() != null) {
                    e = world.spawn(at, type.getEntityClass(), en -> setup(en, tr));
                    if (!e.isValid()) e = null;
                }
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                e = null;
            }
        }
        if (e == null) {
            e = world.spawn(at, Mannequin.class, m -> {
                setup(m, tr);
                if (tr.texture != null) {
                    PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), safeName(tr.name));
                    profile.setProperty(new ProfileProperty("textures", tr.texture, tr.signature));
                    m.setProfile(ResolvableProfile.resolvableProfile(profile));
                }
                m.setImmovable(true);
                m.setDescription(Component.text(tr.id.equals(clip.subject) ? "SUBJECT" : "replay",
                        tr.id.equals(clip.subject) ? NamedTextColor.RED : NamedTextColor.DARK_GRAY));
            });
        }
        viewer.showEntity(plugin, e);
        return e;
    }

    private static String safeName(String n) {
        String s = n.replaceAll("[^A-Za-z0-9_]", "");
        if (s.isEmpty()) s = "replay";
        return s.length() > 16 ? s.substring(0, 16) : s;
    }

    private void setup(Entity e, Clip.Track tr) {
        e.setVisibleByDefault(false);
        e.setPersistent(false);
        e.setSilent(true);
        e.setInvulnerable(true);
        e.setGravity(false);
        e.getPersistentDataContainer().set(manager.actorKey, PersistentDataType.BYTE, (byte) 1);
        e.customName(Component.text(tr.name, tr.id.equals(clip.subject) ? NamedTextColor.RED : NamedTextColor.WHITE));
        e.setCustomNameVisible(true);
        if (e instanceof LivingEntity le) {
            le.setAI(false);
            le.setCollidable(false);
            le.setRemoveWhenFarAway(false);
            le.setCanPickupItems(false);
        }
    }

    /** Blocks: show the world as it was at tick t, to this viewer only. */
    private void syncBlocks(int t) {
        List<Clip.BlockChange> b = clip.blocks;
        while (blocksApplied < b.size() && b.get(blocksApplied).tick() <= t) {
            send(b.get(blocksApplied), false);
            blocksApplied++;
        }
        while (blocksApplied > 0 && b.get(blocksApplied - 1).tick() > t) {
            blocksApplied--;
            send(b.get(blocksApplied), true);
        }
    }

    private void send(Clip.BlockChange c, boolean before) {
        try {
            BlockData data = Bukkit.createBlockData(before ? c.before() : c.after());
            viewer.sendBlockChange(new Location(world, c.x(), c.y(), c.z()), data);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void chatBetween(int from, int to) {
        if (to <= from) return;
        for (Clip.ChatLine l : clip.chat) {
            if (l.tick() > from && l.tick() <= to) {
                viewer.sendMessage(Component.text("[Replay] <" + l.name() + "> ", NamedTextColor.DARK_AQUA)
                        .append(Component.text(l.message(), NamedTextColor.GRAY)));
            }
        }
    }

    // ------------------------------------------------------------------ controls

    public void togglePause() {
        if (cursor >= clip.length) cursor = 0;
        paused = !paused;
    }

    public void setSpeed(double s) {
        double best = 1;
        for (double v : SPEEDS) if (Math.abs(v - s) < Math.abs(best - s)) best = v;
        speed = best;
    }

    public void skip(int seconds) {
        cursor = Math.max(0, Math.min(clip.length, cursor + seconds * 20.0));
        if (cursor < clip.length && paused && seconds < 0) paused = false;
        lastTick = -1;
        render((int) cursor);
        lastTick = (int) cursor;
    }

    public boolean pov() {
        if (viewer.getSpectatorTarget() != null) {
            viewer.setSpectatorTarget(null);
            return false;
        }
        Entity a = actors.get(subjectIdx);
        if (a == null) return false;
        viewer.setSpectatorTarget(a);
        return true;
    }

    public Clip clip() {
        return clip;
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
        for (Entity a : actors.values()) a.remove();
        actors.clear();
        if (viewer.isOnline()) {
            viewer.setSpectatorTarget(null);
            for (Clip.BlockChange c : clip.blocks) {
                Location l = new Location(world, c.x(), c.y(), c.z());
                viewer.sendBlockChange(l, l.getBlock().getBlockData());
            }
            restoreIfNeeded(manager, viewer);
            viewer.sendActionBar(Component.empty());
            viewer.sendMessage(Component.text("Stopped watching replay " + clip.id + ".", NamedTextColor.GRAY));
        }
        manager.ended(this);
    }

    static void restoreIfNeeded(ReplayManager manager, Player viewer) {
        String r = viewer.getPersistentDataContainer().get(manager.restoreKey, PersistentDataType.STRING);
        if (r == null) return;
        viewer.getPersistentDataContainer().remove(manager.restoreKey);
        String[] p = r.split(";");
        try {
            World w = Bukkit.getWorld(p[0]);
            GameMode gm = GameMode.valueOf(p[6]);
            if (w != null) {
                viewer.teleport(new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                        Float.parseFloat(p[4]), Float.parseFloat(p[5])));
            }
            viewer.setGameMode(gm);
        } catch (RuntimeException ex) {
            viewer.setGameMode(GameMode.SURVIVAL);
        }
    }

    private static String time(int ticks) {
        int s = ticks / 20;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static String fmtSpeed(double s) {
        return (s == Math.floor(s) ? String.valueOf((int) s) : String.valueOf(s)) + "x";
    }
}
