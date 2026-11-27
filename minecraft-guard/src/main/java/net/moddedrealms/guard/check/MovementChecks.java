package net.moddedrealms.guard.check;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import net.moddedrealms.guard.GuardPlugin;
import net.moddedrealms.guard.PlayerState;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * Fly and speed. Both are measured against what the server sees, with very wide margins, and are switched off
 * by anything that can legitimately throw a player around (knockback, explosions, wind charges, bounces,
 * pistons, elytra, riptide, vehicles, effects, ice, liquids...). Until the player lands again nothing counts.
 */
public final class MovementChecks implements Listener {
    private final GuardPlugin plugin;

    /** Blocks that hold a player up or slow their fall without being "solid". */
    private static final Set<Material> SUPPORT = EnumSet.noneOf(Material.class);
    /** Blocks that make players faster than our model allows. */
    private static final Set<Material> FAST = EnumSet.noneOf(Material.class);
    /** Blocks that bounce players up. */
    private static final Set<Material> BOUNCE = EnumSet.noneOf(Material.class);

    static {
        for (Material m : Material.values()) {
            if (m.isLegacy()) continue;
            String n = m.name();
            if (n.equals("WATER") || n.equals("LAVA") || n.equals("COBWEB") || n.equals("POWDER_SNOW") || n.equals("SCAFFOLDING")
                    || n.equals("BUBBLE_COLUMN") || n.equals("HONEY_BLOCK") || n.equals("SLIME_BLOCK") || n.contains("KELP")
                    || n.contains("SEAGRASS") || n.equals("SWEET_BERRY_BUSH") || n.endsWith("VINES") || n.endsWith("VINES_PLANT")
                    || n.equals("VINE") || n.equals("LADDER") || n.equals("END_PORTAL") || n.equals("NETHER_PORTAL")
                    || n.equals("END_GATEWAY") || n.equals("LILY_PAD") || n.endsWith("_CARPET") || n.equals("SNOW")
                    || n.endsWith("_PRESSURE_PLATE")) {
                SUPPORT.add(m);
            }
            if (n.contains("ICE") || n.equals("SOUL_SAND") || n.equals("SOUL_SOIL") || n.equals("SLIME_BLOCK")) FAST.add(m);
            if (n.equals("SLIME_BLOCK") || n.endsWith("_BED")) BOUNCE.add(m);
        }
    }

    public MovementChecks(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ exemption events

    private void land(Player p) {
        PlayerState s = plugin.state(p);
        s.awaitingGround = true;
        s.awaitingSince = plugin.tick();
        s.airTicks = 0;
        s.airY.clear();
        s.windowDist = 0;
        s.windowMoves = 0;
        s.speedStreak = 0;
    }

    private void grace(Player p, Location to) {
        PlayerState s = plugin.state(p);
        s.graceUntil = plugin.tick() + plugin.settings().graceTicks;
        if (to != null) {
            s.lastSafe = to.clone();
            s.lastGroundY = to.getY();
        }
        land(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) { land(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) land(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) { grace(e.getPlayer(), e.getTo()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) { grace(e.getPlayer(), e.getRespawnLocation()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) { grace(e.getPlayer(), e.getPlayer().getLocation()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameMode(PlayerGameModeChangeEvent e) { grace(e.getPlayer(), null); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFlightToggle(PlayerToggleFlightEvent e) { land(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGlide(EntityToggleGlideEvent e) {
        if (e.getEntity() instanceof Player p) land(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRiptide(PlayerRiptideEvent e) { land(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDismount(EntityDismountEvent e) {
        if (e.getEntity() instanceof Player p) land(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        // Spears (lunge), fireworks, wind charges and the like launch the player.
        if (e.getAction() == Action.PHYSICAL) return;
        ItemStack it = e.getItem();
        if (it == null) return;
        String n = it.getType().name();
        if (n.endsWith("_SPEAR") || n.equals("WIND_CHARGE") || n.equals("FIREWORK_ROCKET") || n.equals("TRIDENT")
                || n.equals("ENDER_PEARL") || n.equals("CHORUS_FRUIT") || n.equals("MACE")) {
            land(e.getPlayer());
        }
    }

    private void nearbyBlast(Location at, double radius) {
        if (at == null || at.getWorld() == null) return;
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) <= radius * radius) land(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityExplode(EntityExplodeEvent e) { nearbyBlast(e.getLocation(), 12); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockExplode(BlockExplodeEvent e) { nearbyBlast(e.getBlock().getLocation(), 12); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileHit(ProjectileHitEvent e) {
        if (e.getEntity() instanceof WindCharge || e.getEntity().getType().name().contains("WIND_CHARGE")) {
            nearbyBlast(e.getEntity().getLocation(), 8);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPistonOut(BlockPistonExtendEvent e) { nearbyBlast(e.getBlock().getLocation(), 6); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPistonIn(BlockPistonRetractEvent e) { nearbyBlast(e.getBlock().getLocation(), 6); }

    // ------------------------------------------------------------------ checks

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location from = e.getFrom(), to = e.getTo();
        PlayerState s = plugin.state(p);
        s.prevLocation = from;
        if (from.getWorld() != to.getWorld()) return;
        long now = plugin.tick();
        long elapsed = Math.max(1, Math.min(20, now - s.lastMoveTick));
        s.lastMoveTick = now;

        boolean supported = supported(p, to) || supported(p, from);
        if (supported) {
            Block below = to.clone().subtract(0, 0.2, 0).getBlock();
            if (BOUNCE.contains(below.getType()) && to.getY() - Math.floor(to.getY()) < 0.6) {
                // Bounced: not "grounded" for the rise check.
                land(p);
                s.awaitingGround = true;
            } else {
                if (s.awaitingGround && now - s.awaitingSince >= 10) s.awaitingGround = false;
                s.lastGroundY = to.getY();
                s.lastSafe = to.clone();
            }
            s.airTicks = 0;
            s.airY.clear();
        } else if (s.awaitingGround && now - s.awaitingSince > 100 && now >= s.graceUntil) {
            // Still airborne 5s after a teleport/knockback: stop waiting for a landing (or someone could just never
            // land after /warp). Measure from here: a falling player can't trip the rise or hover checks.
            s.awaitingGround = false;
            s.lastGroundY = to.getY();
            s.airTicks = 0;
            s.airY.clear();
        }

        String why = exemptReason(p, s, now);
        if (s.debug) {
            plugin.getLogger().info(String.format(Locale.ROOT, "[debug] %s y=%.2f supported=%s exempt=%s air=%d groundY=%.2f awaiting=%s",
                    p.getName(), to.getY(), supported, why, s.airTicks, s.lastGroundY, s.awaitingGround));
        }
        if (why != null) {
            s.windowDist = 0;
            s.windowMoves = 0;
            s.speedStreak = 0;
            if (supported) s.lastSafe = to.clone();
            return;
        }

        if (!supported) checkFly(p, s, to, now, elapsed);
        checkSpeed(p, s, from, to);
    }

    /** Why this move is not checked, or null if it is. */
    private String exemptReason(Player p, PlayerState s, long now) {
        if (now < s.graceUntil) return "grace";
        if (s.awaitingGround) return "awaiting-ground";
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return "gamemode";
        if (p.getAllowFlight() || p.isFlying()) return "flight-allowed";
        if (p.isInsideVehicle() || p.isGliding() || p.isRiptiding() || p.isDead() || p.isSleeping() || p.isSwimming()
                || p.isInWater() || p.isClimbing()) {
            return "state";
        }
        for (PotionEffect fx : p.getActivePotionEffects()) {
            PotionEffectType t = fx.getType();
            if (t.equals(PotionEffectType.LEVITATION) || t.equals(PotionEffectType.SLOW_FALLING) || t.equals(PotionEffectType.DOLPHINS_GRACE)) {
                s.graceUntil = Math.max(s.graceUntil, now + 60);
                return "effect";
            }
            if (t.equals(PotionEffectType.JUMP_BOOST) && fx.getAmplifier() > 3) return "effect";
            if (t.equals(PotionEffectType.SPEED) && fx.getAmplifier() > 3) return "effect";
        }
        return null;
    }

    private void checkFly(Player p, PlayerState s, Location to, long now, long elapsed) {
        Check c = Check.FLY;
        if (!c.enabled) return;
        s.airTicks += (int) elapsed;
        s.airY.addLast(new double[]{now, to.getY()});
        while (s.airY.size() > 1 && now - s.airY.peekFirst()[0] > 60) s.airY.pollFirst();

        int jump = amplifier(p, PotionEffectType.JUMP_BOOST);
        double maxRise = c.num("max-rise", 2.3) + (jump >= 0 ? 0.75 * (jump + 1) : 0);
        double rise = to.getY() - s.lastGroundY;
        if (rise > maxRise && s.airTicks > 8 && now - s.lastFlyFlagTick >= 10) {
            s.lastFlyFlagTick = now;
            plugin.violations().flag(p, c, true, String.format(Locale.ROOT, "rose %.2f blocks without support (max %.2f)", rise, maxRise));
            plugin.violations().setback(p, s);
            return;
        }

        int hoverTicks = (int) c.num("hover-ticks", 80);
        if (s.airTicks >= hoverTicks && s.airY.size() >= 20 && now - s.lastFlyFlagTick >= 20) {
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (double[] v : s.airY) {
                min = Math.min(min, v[1]);
                max = Math.max(max, v[1]);
            }
            if (max - min < 0.6) {
                s.lastFlyFlagTick = now;
                plugin.violations().flag(p, c, true, String.format(Locale.ROOT, "hovered %d ticks (height range %.2f)", s.airTicks, max - min));
                plugin.violations().setback(p, s);
            }
        }
    }

    private void checkSpeed(Player p, PlayerState s, Location from, Location to) {
        Check c = Check.SPEED;
        if (!c.enabled) return;
        if (nearFast(to) || hasBootEnchant(p)) {
            s.windowDist = 0;
            s.windowMoves = 0;
            s.speedStreak = 0;
            return;
        }
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1e-4) return;
        s.windowDist += d;
        s.windowMoves++;
        if (s.windowMoves < 20) return;

        double avg = s.windowDist / s.windowMoves;
        s.windowDist = 0;
        s.windowMoves = 0;
        AttributeInstance attr = p.getAttribute(Attribute.MOVEMENT_SPEED);
        double speedAttr = attr == null ? 0.1 : attr.getValue();
        double factor = Math.max(1.3, speedAttr / 0.1);
        // Sprint-jumping averages ~0.28 * factor per move; 0.1 covers stairs, slabs and step-ups.
        double allowed = 0.28 * factor + 0.1;
        double suspect = allowed * c.num("suspect-multiplier", 1.6);
        double certain = allowed * c.num("certain-multiplier", 2.2);
        if (avg > certain) {
            s.speedStreak++;
            if (s.speedStreak >= 3) {
                plugin.violations().flag(p, c, true, String.format(Locale.ROOT, "%.2f blocks/move for 60 moves (max %.2f)", avg, allowed));
                plugin.violations().setback(p, s);
                s.speedStreak = 0;
            }
        } else {
            s.speedStreak = 0;
            if (avg > suspect) {
                s.speedSuspectStreak++;
                if (s.speedSuspectStreak >= 3) {
                    plugin.violations().flag(p, c, false, String.format(Locale.ROOT, "%.2f blocks/move (max %.2f)", avg, allowed));
                    s.speedSuspectStreak = 0;
                }
            } else {
                s.speedSuspectStreak = 0;
            }
        }
    }

    // ------------------------------------------------------------------ world helpers

    /** Anything under, around or inside the player that could hold them up, with a generous margin. */
    static boolean supported(Player p, Location at) {
        World w = at.getWorld();
        double half = 0.3 + 0.35;
        double height = p.getHeight();
        int x0 = floor(at.getX() - half), x1 = floor(at.getX() + half);
        int z0 = floor(at.getZ() - half), z1 = floor(at.getZ() + half);
        int y0 = floor(at.getY() - 0.65), y1 = floor(at.getY() + height + 0.2);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (m.isAir()) continue;
                    if (SUPPORT.contains(m) || Tag.CLIMBABLE.isTagged(m) || b.isLiquid() || !b.isPassable()) return true;
                    if (b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()) return true;
                }
            }
        }
        // Standing on something solid that is an entity: boats, shulkers, happy ghasts, minecarts.
        for (Entity e : w.getNearbyEntities(at, 2.5, 3.0, 2.5)) {
            if (e == p) continue;
            EntityType t = e.getType();
            String n = t.name();
            if (n.contains("BOAT") || n.contains("RAFT") || n.contains("MINECART") || n.equals("SHULKER") || n.contains("GHAST")) return true;
        }
        return false;
    }

    private static boolean nearFast(Location at) {
        World w = at.getWorld();
        int bx = floor(at.getX()), by = floor(at.getY()), bz = floor(at.getZ());
        for (int x = bx - 1; x <= bx + 1; x++) {
            for (int z = bz - 1; z <= bz + 1; z++) {
                for (int y = by - 2; y <= by; y++) {
                    if (FAST.contains(w.getBlockAt(x, y, z).getType())) return true;
                }
            }
        }
        return false;
    }

    private static boolean hasBootEnchant(Player p) {
        ItemStack boots = p.getInventory().getBoots();
        ItemStack legs = p.getInventory().getLeggings();
        return boots != null && (boots.containsEnchantment(Enchantment.SOUL_SPEED) || boots.containsEnchantment(Enchantment.DEPTH_STRIDER))
                || legs != null && legs.getEnchantments().keySet().stream().anyMatch(en -> en.getKey().getKey().contains("swift"));
    }

    private static int amplifier(Player p, PotionEffectType t) {
        PotionEffect fx = p.getPotionEffect(t);
        return fx == null ? -1 : fx.getAmplifier();
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    static double distanceToBox(Vector eye, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double cx = Math.max(minX, Math.min(eye.getX(), maxX));
        double cy = Math.max(minY, Math.min(eye.getY(), maxY));
        double cz = Math.max(minZ, Math.min(eye.getZ(), maxZ));
        double dx = eye.getX() - cx, dy = eye.getY() - cy, dz = eye.getZ() - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
