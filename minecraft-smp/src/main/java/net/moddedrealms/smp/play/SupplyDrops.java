package net.moddedrealms.smp.play;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;

/**
 * Supply drops: every so often a crate falls somewhere in the wilds (away from the towns and colony land). Everyone
 * is told where a minute before it lands; a beam of light marks it; the first to open it is announced and gets a
 * crate key, and whatever's inside goes to whoever grabs it. Unopened crates vanish after a while. Loot comes from
 * the config (weighted), so nothing here touches anyone's property: the crate is placed on open ground and removed
 * again, and only the chest block itself is ever placed or cleared.
 */
public final class SupplyDrops implements Listener, CommandExecutor {
    private final SmpPlugin plugin;
    private final File file;
    private final Random r = new Random();
    private Location pending, active;
    private long landsAt, expiresAt, nextAt;
    private String opener;

    public SupplyDrops(SmpPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "supply-drop.yml");
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public void start() {
        // a crate left by a crash or a hard stop is cleared
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        if (y.isSet("world")) {
            World w = Bukkit.getWorld(y.getString("world", ""));
            if (w != null) {
                Location l = new Location(w, y.getInt("x"), y.getInt("y"), y.getInt("z"));
                w.getChunkAtAsync(l).thenRun(() -> clear(l));
            }
            file.delete();
        }
        nextAt = System.currentTimeMillis() + cfg().getLong("supply-drops.first-after-minutes", 20) * 60_000L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::second, 20L, 20L);
    }

    public void shutdown() {
        if (active != null) clear(active);
        file.delete();
    }

    private void remember(Location l) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("world", l.getWorld().getName());
        y.set("x", l.getBlockX());
        y.set("y", l.getBlockY());
        y.set("z", l.getBlockZ());
        try {
            y.save(file);
        } catch (IOException ignored) {
        }
    }

    private void second() {
        long now = System.currentTimeMillis();
        if (pending != null && now >= landsAt) land();
        if (active != null) {
            beam(active);
            if (now >= expiresAt) {
                Location l = active;
                broadcast("<gray>The supply drop at " + where(l) + " was " + (opener == null ? "never found" : "picked clean") + " and is gone.");
                clear(l);
                active = null;
                file.delete();
            }
        }
        if (pending == null && active == null && now >= nextAt && cfg().getBoolean("supply-drops.enabled", true)) {
            nextAt = now + cfg().getLong("supply-drops.every-minutes", 45) * 60_000L;
            if (Bukkit.getOnlinePlayers().size() >= cfg().getInt("supply-drops.min-players", 1)) announce(null);
        }
    }

    /** Picks a spot and tells everyone. by = admin who forced it, or null. */
    String announce(CommandSender by) {
        if (pending != null || active != null) return "A drop is already out.";
        World w = Bukkit.getWorld(plugin.town() == null ? "world" : plugin.town().townWorld());
        if (w == null) return "The town world isn't loaded.";
        double cx = plugin.town().centerX(), cz = plugin.town().centerZ();
        int min = cfg().getInt("supply-drops.min-distance", 600), max = cfg().getInt("supply-drops.max-distance", 1600);
        tryPlace(w, cx, cz, min, max, 12, by);
        return "Looking for open ground...";
    }

    private void tryPlace(World w, double cx, double cz, int min, int max, int tries, CommandSender by) {
        if (tries <= 0) {
            if (by != null) by.sendMessage(SmpPlugin.mm("<red>Couldn't find open ground for a drop."));
            return;
        }
        double ang = r.nextDouble() * Math.PI * 2, dist = min + r.nextDouble() * (max - min);
        int x = (int) (cx + Math.cos(ang) * dist), z = (int) (cz + Math.sin(ang) * dist);
        var border = w.getWorldBorder();
        if (!border.isInside(new Location(w, x, 64, z))) {
            tryPlace(w, cx, cz, min, Math.max(min + 1, max - 200), tries - 1, by);
            return;
        }
        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(ch -> {
            Block top = w.getHighestBlockAt(x, z);
            Material m = top.getType();
            Location at = top.getLocation().add(0, 1, 0);
            boolean wet = top.isLiquid() || m == Material.ICE || m.name().contains("LEAVES") || m == Material.BAMBOO || m == Material.CACTUS;
            boolean claimed = plugin.town() != null && (plugin.town().colonyLand(at) || plugin.town().inPoliceZone(at));
            if (wet || claimed || !at.getBlock().getType().isAir() && !at.getBlock().isReplaceable() || at.getBlockY() > w.getMaxHeight() - 4) {
                tryPlace(w, cx, cz, min, max, tries - 1, by);
                return;
            }
            pending = at;
            landsAt = System.currentTimeMillis() + cfg().getLong("supply-drops.warning-seconds", 60) * 1000L;
            broadcast("<gold>📦 A supply drop is falling at <white>" + where(at) + "</white>!</gold> <gray>It lands in "
                    + cfg().getLong("supply-drops.warning-seconds", 60) + " seconds. First to open it gets a crate key. <white>/drop</white> shows the way.");
            for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.ITEM_GOAT_HORN_SOUND_2, 0.5f, 1.0f);
        });
    }

    private void land() {
        Location at = pending;
        pending = null;
        World w = at.getWorld();
        if (w == null) return;
        Block b = at.getBlock();
        if (!b.getType().isAir() && !b.isReplaceable()) {
            broadcast("<gray>The supply drop was lost (something was built where it fell).");
            return;
        }
        b.setType(Material.CHEST, false);
        if (b.getState() instanceof Chest chest) {
            chest.customName(SmpPlugin.mm("<gold>Supply drop"));
            chest.update(true, false);
            fill(chest);
        }
        active = at;
        opener = null;
        expiresAt = System.currentTimeMillis() + cfg().getLong("supply-drops.lasts-minutes", 15) * 60_000L;
        remember(at);
        w.strikeLightningEffect(at);
        Firework fw = w.spawn(at.clone().add(0.5, 1, 0.5), Firework.class);
        FireworkMeta fm = fw.getFireworkMeta();
        fm.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(Color.ORANGE, Color.YELLOW).withFade(Color.RED).build());
        fm.setPower(1);
        fw.setFireworkMeta(fm);
        broadcast("<gold>📦 The supply drop has landed at <white>" + where(at) + "</white>!");
    }

    /** Weighted loot from supply-drops.loot: "MATERIAL:min-max:weight". */
    private void fill(Chest chest) {
        List<String> loot = cfg().getStringList("supply-drops.loot");
        if (loot.isEmpty()) {
            loot = List.of("DIAMOND:2-5:10", "GOLDEN_APPLE:2-4:10", "IRON_INGOT:8-24:14", "GOLD_INGOT:4-12:10", "ENDER_PEARL:2-6:8", "EXPERIENCE_BOTTLE:8-24:10",
                    "COOKED_BEEF:12-32:12", "ARROW:16-48:8", "EMERALD:4-12:8", "NETHERITE_SCRAP:1-1:2", "ENCHANTED_GOLDEN_APPLE:1-1:1", "TOTEM_OF_UNDYING:1-1:2",
                    "OBSIDIAN:8-16:5", "SADDLE:1-1:3", "NAME_TAG:1-2:4", "TNT:2-6:3");
        }
        List<Object[]> pool = new ArrayList<>();
        int total = 0;
        for (String s : loot) {
            String[] f = s.split(":");
            Material m = f.length > 0 ? Material.matchMaterial(f[0].trim()) : null;
            if (m == null || !m.isItem()) continue;
            int lo = 1, hi = 1, wt = 1;
            try {
                if (f.length > 1) {
                    String[] rg = f[1].split("-");
                    lo = Integer.parseInt(rg[0].trim());
                    hi = rg.length > 1 ? Integer.parseInt(rg[1].trim()) : lo;
                }
                if (f.length > 2) wt = Integer.parseInt(f[2].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            pool.add(new Object[]{m, lo, Math.max(lo, hi), Math.max(1, wt)});
            total += Math.max(1, wt);
        }
        if (pool.isEmpty()) return;
        int picks = cfg().getInt("supply-drops.items", 6);
        var inv = chest.getBlockInventory();
        for (int i = 0; i < picks; i++) {
            int roll = r.nextInt(total);
            for (Object[] o : pool) {
                roll -= (int) o[3];
                if (roll >= 0) continue;
                Material m = (Material) o[0];
                int n = (int) o[1] + r.nextInt((int) o[2] - (int) o[1] + 1);
                int slot;
                int guard = 0;
                do {
                    slot = r.nextInt(inv.getSize());
                } while (inv.getItem(slot) != null && ++guard < 40);
                inv.setItem(slot, new ItemStack(m, Math.min(n, m.getMaxStackSize())));
                break;
            }
        }
    }

    private void beam(Location l) {
        World w = l.getWorld();
        if (w == null) return;
        for (int y = 1; y < 40; y += 2) w.spawnParticle(Particle.END_ROD, l.clone().add(0.5, y, 0.5), 1, 0.05, 0.4, 0.05, 0);
    }

    private void clear(Location l) {
        Block b = l.getBlock();
        if (b.getState() instanceof Chest c) {
            c.getBlockInventory().clear();
            b.setType(Material.AIR, false);
        }
    }

    private static String where(Location l) {
        return "X " + l.getBlockX() + ", Z " + l.getBlockZ();
    }

    private void broadcast(String s) {
        Bukkit.broadcast(SmpPlugin.mm(s));
    }

    private boolean isDrop(Block b) {
        return active != null && b.getWorld() == active.getWorld() && b.getX() == active.getBlockX() && b.getY() == active.getBlockY() && b.getZ() == active.getBlockZ();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (active == null || opener != null || !(e.getPlayer() instanceof Player p)) return;
        Location l = e.getInventory().getLocation();
        if (l == null || !isDrop(l.getBlock())) return;
        opener = p.getName();
        expiresAt = Math.min(expiresAt, System.currentTimeMillis() + 3 * 60_000L); // a few minutes for the others to fight over it
        broadcast("<gold>📦 " + p.getName() + " reached the supply drop first!");
        List<String> bonus = cfg().getStringList("supply-drops.first-reward");
        plugin.rewards().give(p, bonus.isEmpty() ? List.of("key:rare:1") : bonus);
        if (plugin.town() != null) plugin.town().news(p.getName() + " reached a supply drop first, out in the wilds.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (isDrop(e.getBlock())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(SmpPlugin.mm("<gray>Open it instead: the crate vanishes when it's done."));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::isDrop);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isDrop);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Location l = pending != null ? pending : active;
        if (l == null) {
            long mins = Math.max(1, (nextAt - System.currentTimeMillis()) / 60_000L);
            sender.sendMessage(SmpPlugin.mm("<gray>No supply drop right now. The next one falls in about <white>" + mins + "</white> minute" + (mins == 1 ? "" : "s") + "."));
            return true;
        }
        String s = "<gold>📦 Supply drop " + (pending != null ? "falling" : "on the ground") + " at <white>" + where(l) + "</white>";
        if (sender instanceof Player p && p.getWorld() == l.getWorld()) {
            double dx = l.getX() - p.getLocation().getX(), dz = l.getZ() - p.getLocation().getZ();
            String dir = compass(Math.toDegrees(Math.atan2(-dx, dz)));
            s += " <gray>· " + Math.round(Math.hypot(dx, dz)) + " blocks " + dir;
        }
        sender.sendMessage(SmpPlugin.mm(s + (opener != null ? " <gray>(" + opener + " got there first)" : "")));
        return true;
    }

    private static String compass(double yaw) {
        String[] d = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        int i = (int) Math.round(((yaw % 360) + 360) % 360 / 45.0) % 8;
        return d[i];
    }

    /** /mrsmp drop [now|status] */
    public String admin(CommandSender s, String what) {
        if ("now".equals(what)) {
            nextAt = System.currentTimeMillis() + cfg().getLong("supply-drops.every-minutes", 45) * 60_000L;
            return announce(s);
        }
        Location l = pending != null ? pending : active;
        return l == null ? "No drop out; next in " + Math.max(0, (nextAt - System.currentTimeMillis()) / 60_000L) + " min." : "Drop at " + where(l) + (pending != null ? " (falling)" : "") + ".";
    }
}
