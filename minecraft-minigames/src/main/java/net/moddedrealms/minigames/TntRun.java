package net.moddedrealms.minigames;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * TNT Run: four floors of sand on TNT, one above the other. Blocks vanish a moment after someone steps on them,
 * so you have to keep moving; fall through all four and you're out. Last one standing wins.
 */
final class TntRun extends Match {

    static final int RADIUS = 17;
    static final int[] LAYERS = {36, 28, 20, 12}; // above the arena origin, top first
    static final Material[] TOPS = {Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.WHITE_CONCRETE_POWDER};
    static final int DEATH = 6;

    private final Map<Long, Long> pending = new HashMap<>(); // block -> tick it vanishes
    private final Map<Participant, TntBrain> brains = new HashMap<>();
    private final List<Participant> outOrder = new ArrayList<>();
    private long breakFrom;
    private long endTick;

    TntRun(MinigamesPlugin plugin, int slot) {
        super(plugin, GameType.TNTRUN, slot);
    }

    // ------------------------------------------------------------ the arena

    static void buildArena(Location o, boolean full) {
        World w = o.getWorld();
        int ox = o.getBlockX(), oy = o.getBlockY(), oz = o.getBlockZ();
        if (full) {
            for (int x = -RADIUS - 2; x <= RADIUS + 2; x++) {
                for (int z = -RADIUS - 2; z <= RADIUS + 2; z++) {
                    double d = Math.sqrt(x * x + z * z);
                    for (int y = oy + DEATH - 4; y <= oy + LAYERS[0] + 6; y++) {
                        Material m = Material.AIR;
                        if (d > RADIUS + 0.5 && d <= RADIUS + 1.5) {
                            int rel = y - oy;
                            boolean band = false;
                            for (int L : LAYERS) if (rel == L || rel == L - 1) band = true;
                            m = band ? Material.STONE_BRICKS : Material.LIGHT_GRAY_STAINED_GLASS;
                            if (rel == LAYERS[0] + 6) m = Material.STONE_BRICK_SLAB;
                        }
                        if (y == oy + DEATH - 4 && d <= RADIUS + 1.5) m = Material.BARRIER;
                        w.getBlockAt(ox + x, y, oz + z).setType(m, false);
                    }
                }
            }
            // A lava pool far below, for looks: you're out long before you reach it.
            for (int x = -RADIUS; x <= RADIUS; x++) {
                for (int z = -RADIUS; z <= RADIUS; z++) {
                    if (Math.sqrt(x * x + z * z) <= RADIUS + 0.5) w.getBlockAt(ox + x, oy + DEATH - 3, oz + z).setType(Material.MAGMA_BLOCK, false);
                }
            }
        }
        buildLayers(o);
    }

    static void buildLayers(Location o) {
        World w = o.getWorld();
        int ox = o.getBlockX(), oy = o.getBlockY(), oz = o.getBlockZ();
        for (int i = 0; i < LAYERS.length; i++) {
            int y = oy + LAYERS[i];
            for (int x = -RADIUS; x <= RADIUS; x++) {
                for (int z = -RADIUS; z <= RADIUS; z++) {
                    if (Math.sqrt(x * x + z * z) > RADIUS + 0.5) continue;
                    w.getBlockAt(ox + x, y - 1, oz + z).setType(Material.TNT, false);
                    w.getBlockAt(ox + x, y, oz + z).setType(TOPS[i], false);
                }
            }
        }
    }

    static boolean isTop(Material m) {
        for (Material t : TOPS) if (t == m) return true;
        return false;
    }

    @Override
    void prepareArena() {
        buildLayers(origin);
        pending.clear();
    }

    @Override
    Location waitingSpot(int index) {
        double a = Math.toRadians(index * 137.5);
        double r = 2.5 + (index % 4) * 2.5;
        Location l = origin.clone().add(0.5 - Math.sin(a) * r, LAYERS[0] + 1, 0.5 + Math.cos(a) * r);
        l.setYaw((float) Math.toDegrees(Math.atan2(-Math.sin(a) * r, -Math.cos(a) * r)));
        return l;
    }

    Location spectatorSpot() {
        Location l = origin.clone().add(0.5, LAYERS[0] + 8, RADIUS - 2);
        l.setPitch(40);
        l.setYaw(180);
        return l;
    }

    int floorY(int layer) {
        return origin.getBlockY() + LAYERS[layer];
    }

    @Override
    void onBotJoined(Participant p) {
        brains.put(p, new TntBrain(this, p));
    }

    // ------------------------------------------------------------ the game

    @Override
    void onStart() {
        int jumps = Math.max(0, cfg().getInt("double-jumps", 2));
        breakFrom = t + 40;
        endTick = t + 20L * 60 * 5;
        for (Participant p : players) {
            p.doubleJumps = jumps;
            Player pl = p.player();
            if (pl != null) {
                pl.getInventory().clear();
                if (jumps > 0) pl.getInventory().setItem(0, Items.doubleJump(jumps));
                pl.getInventory().setItem(8, Items.leave());
                pl.setAllowFlight(jumps > 0);
                pl.showTitle(Title.title(Component.text("RUN!", NamedTextColor.RED, TextDecoration.BOLD),
                        Component.text("Don't stop, the floor vanishes behind you", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(500))));
            }
            if (p.isBot() && !brains.containsKey(p)) brains.put(p, new TntBrain(this, p));
        }
        broadcast(Component.text("» ", NamedTextColor.GOLD).append(Component.text("Go! Double jump: press jump twice. Last one standing wins.", NamedTextColor.YELLOW)));
    }

    @Override
    void tickGame() {
        World w = origin.getWorld();
        int deathY = origin.getBlockY() + DEATH;
        for (Participant p : players) {
            if (!p.alive || p.gone) continue;
            LivingEntity e = p.entity();
            if (e == null) continue;
            Location l = e.getLocation();
            if (l.getY() < deathY || l.getWorld() != w) {
                out(p);
                continue;
            }
            if (t >= breakFrom) stepOn(l);
            Player pl = p.player();
            if (pl != null && p.doubleJumps > 0 && pl.getGameMode() == GameMode.ADVENTURE && ((org.bukkit.entity.Entity) pl).isOnGround() && !pl.getAllowFlight()) {
                pl.setAllowFlight(true);
            }
        }
        // Blocks that were stepped on vanish now.
        Iterator<Map.Entry<Long, Long>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> en = it.next();
            if (en.getValue() > t) continue;
            it.remove();
            long k = en.getKey();
            Block b = w.getBlockAt(unX(k), unY(k), unZ(k));
            if (isTop(b.getType())) {
                b.setType(Material.AIR, false);
                Block below = b.getRelative(0, -1, 0);
                if (below.getType() == Material.TNT) below.setType(Material.AIR, false);
            }
        }
        for (TntBrain b : brains.values()) b.tick(t);
        if (t == breakFrom - 20) actionBar(Component.text("The floor starts vanishing in 1...", NamedTextColor.RED));
        List<Participant> alive = alive();
        if (alive.size() <= 1 || t >= endTick) {
            List<Participant> winners = new ArrayList<>(alive);
            if (winners.isEmpty() && !outOrder.isEmpty()) winners.add(outOrder.get(outOrder.size() - 1));
            finish(winners, Component.text("TNT Run", NamedTextColor.RED, TextDecoration.BOLD).append(Component.text(" is over!", NamedTextColor.YELLOW)));
        }
        if (t % 20 == 0) {
            for (Player pl : humansOnline()) {
                Participant me = of(pl);
                if (me != null && me.alive) pl.sendActionBar(Component.text(alive.size() + " left", NamedTextColor.YELLOW));
            }
        }
    }

    /** Schedule the blocks under someone's feet to vanish. */
    void stepOn(Location l) {
        double y = l.getY();
        int by = (int) Math.floor(y - 0.05);
        if (y - (by + 1) > 0.1) return;
        World w = l.getWorld();
        for (double dx : new double[] {-0.3, 0.3}) {
            for (double dz : new double[] {-0.3, 0.3}) {
                int bx = (int) Math.floor(l.getX() + dx), bz = (int) Math.floor(l.getZ() + dz);
                long k = key(bx, by, bz);
                if (pending.containsKey(k)) continue;
                if (isTop(w.getBlockAt(bx, by, bz).getType())) pending.put(k, t + Math.max(2, cfg().getInt("vanish-ticks", 7)));
            }
        }
    }

    /** Is there floor here that will still be there for a moment (for bots looking ahead)? */
    boolean intact(double x, int y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!isTop(origin.getWorld().getBlockAt(bx, y, bz).getType())) return false;
        Long due = pending.get(key(bx, y, bz));
        return due == null || due > t + 4;
    }

    /** The highest floor with blocks at or below y (the one someone falling will land on). */
    int layerAtOrBelow(double y) {
        for (int i = 0; i < LAYERS.length; i++) if (y >= floorY(i) + 0.5) return i;
        return LAYERS.length;
    }

    void doubleJump(Player pl) {
        Participant p = of(pl);
        if (p == null || !p.alive || state != State.PLAYING || p.doubleJumps <= 0) return;
        p.doubleJumps--;
        Vector dir = pl.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 0);
        else dir.normalize().multiply(0.75);
        pl.setVelocity(dir.setY(0.78));
        pl.setAllowFlight(false);
        pl.setFlying(false);
        pl.playSound(pl.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.6f, 1.6f);
        pl.getWorld().spawnParticle(Particle.CLOUD, pl.getLocation(), 8, 0.3, 0.1, 0.3, 0.02);
        if (p.doubleJumps > 0) pl.getInventory().setItem(0, Items.doubleJump(p.doubleJumps));
        else pl.getInventory().setItem(0, null);
    }

    void botDoubleJump(Participant p, double dirX, double dirZ) {
        if (p.doubleJumps <= 0 || p.bot == null) return;
        p.doubleJumps--;
        double d = Math.hypot(dirX, dirZ);
        if (d > 1e-4) {
            dirX = dirX / d * 0.75;
            dirZ = dirZ / d * 0.75;
        }
        p.bot.impulse(dirX, 0.78, dirZ);
        Location l = p.loc();
        if (l != null) {
            l.getWorld().playSound(l, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.6f, 1.6f);
            l.getWorld().spawnParticle(Particle.CLOUD, l, 8, 0.3, 0.1, 0.3, 0.02);
        }
    }

    private void out(Participant p) {
        p.alive = false;
        p.outAt = t;
        outOrder.add(p);
        onOut(p, "fell");
    }

    @Override
    void onOut(Participant p, String why) {
        int left = alive().size();
        if (!why.equals("left")) {
            broadcast(p.display(NamedTextColor.RED).append(Component.text(" fell! ", NamedTextColor.GRAY))
                    .append(Component.text(left + " left", NamedTextColor.YELLOW)));
        }
        brains.remove(p);
        if (p.isBot()) {
            p.bot.remove();
            if (chance(plugin.getConfig().getDouble("bots.chat-chance", 0.35) * 0.6)) {
                botSay(p, pick("nooo", "rip", "lag", "bruh", "noo", "how", "dang", "almost", "ugh"), 10 + rnd(40));
            }
        } else {
            Player pl = p.player();
            if (pl != null && !why.equals("left")) {
                pl.showTitle(Title.title(Component.text("YOU FELL!", NamedTextColor.RED, TextDecoration.BOLD),
                        Component.text("Place: #" + (left + 1), NamedTextColor.GRAY),
                        Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(500))));
                spectate(pl, spectatorSpot());
                pl.getInventory().setItem(0, Items.again());
                pl.getInventory().setItem(8, Items.leave());
            }
        }
    }

    @Override
    void onFinish(List<Participant> winners) {
        List<Participant> order = new ArrayList<>(winners);
        for (int i = outOrder.size() - 1; i >= 0; i--) if (!order.contains(outOrder.get(i))) order.add(outOrder.get(i));
        for (int i = 0; i < Math.min(3, order.size()); i++) {
            broadcast(Component.text("  #" + (i + 1) + " ", i == 0 ? NamedTextColor.GOLD : NamedTextColor.GRAY).append(order.get(i).display()));
        }
        for (Participant w : winners) {
            Location l = w.loc();
            if (l != null) l.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, l.clone().add(0, 1, 0), 60, 0.5, 1, 0.5, 0.3);
            if (w.isBot()) w.bot.stop();
        }
        brains.clear();
    }

    @Override
    void onCleanup() {
        brains.clear();
        pending.clear();
        buildLayers(origin);
    }

    @Override
    List<Component> sidebar(Participant viewer) {
        List<Component> lines = new ArrayList<>();
        List<Participant> alive = alive();
        lines.add(Component.empty());
        lines.add(Component.text("Alive: ", NamedTextColor.GRAY).append(Component.text(alive.size() + "/" + players.size(), NamedTextColor.GREEN)));
        int shown = 0;
        for (Participant p : alive) {
            if (shown++ >= 8) {
                lines.add(Component.text("  ...and " + (alive.size() - 8) + " more", NamedTextColor.DARK_GRAY));
                break;
            }
            lines.add(Component.text("  ").append(p.display(p == viewer ? NamedTextColor.GREEN : NamedTextColor.WHITE)));
        }
        lines.add(Component.empty());
        if (viewer.alive) lines.add(Component.text("Double jumps: ", NamedTextColor.GRAY).append(Component.text(String.valueOf(viewer.doubleJumps), NamedTextColor.AQUA)));
        long s = Math.max(0, (t - startedAt) / 20);
        lines.add(Component.text("Time: ", NamedTextColor.GRAY).append(Component.text(String.format("%d:%02d", s / 60, s % 60), NamedTextColor.WHITE)));
        lines.add(Component.empty());
        lines.add(Component.text("play.moddedrealms.net", NamedTextColor.YELLOW));
        return lines;
    }

    // ------------------------------------------------------------ packed block positions

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static int unX(long k) {
        return (int) (k >> 38);
    }

    private static int unZ(long k) {
        return (int) (k << 26 >> 38);
    }

    private static int unY(long k) {
        return (int) (k << 52 >> 52);
    }
}
