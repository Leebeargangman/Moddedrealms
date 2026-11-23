package net.moddedrealms.smp.town;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.BoundingBox;

/**
 * Keeps the towns and every generated building in one piece: explosions (creepers, TNT, ghasts, withers, beds and
 * respawn anchors), fire spreading and burning, mobs that change blocks (endermen, ravagers, withers, zombies breaking
 * doors), trampled crops, and lava or water flowing in from outside. Protected: the area of every generated building
 * (from its undo file) and the core of each town (town.protection.radius around its centre).
 */
final class Protection implements Listener {
    record Area(String name, World world, BoundingBox box) {}

    private final TownManager t;
    private final List<Area> areas = new ArrayList<>();
    private long loadedAt;

    Protection(TownManager t) {
        this.t = t;
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    /** The generated buildings, read from their undo files. */
    List<Area> built() {
        List<Area> out = new ArrayList<>();
        File dir = t.plugin().getDataFolder();
        for (String n : List.of("meadowbrook", "casino", "jail", "bank", "railway", "plaza")) {
            File f = new File(dir, n + "-undo.yml");
            if (!f.exists()) continue;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            World w = Bukkit.getWorld(y.getString("world", ""));
            if (w == null) continue;
            List<Integer> a = y.getIntegerList("area");
            if (a.size() == 6) {
                out.add(new Area(n, w, new BoundingBox(a.get(0), a.get(1), a.get(2), a.get(3) + 1, a.get(4) + 1, a.get(5) + 1)));
            } else if (y.contains("x")) { // the plaza stores its centre
                int x = y.getInt("x"), yy = y.getInt("y"), z = y.getInt("z"), r = net.moddedrealms.smp.spawn.PlazaBuilder.R + 2;
                out.add(new Area(n, w, new BoundingBox(x - r, yy - 8, z - r, x + r + 1, yy + 18, z + r + 1)));
            }
        }
        return out;
    }

    private List<Area> areas() {
        if (System.currentTimeMillis() - loadedAt > 30_000) {
            areas.clear();
            areas.addAll(built());
            double r = cfg().getDouble("town.protection.radius", 160);
            if (r > 0) {
                for (TownManager.Zone z : t.zones()) {
                    World w = Bukkit.getWorld(z.world());
                    if (w != null) areas.add(new Area(z.id(), w, new BoundingBox(z.x() - r, w.getMinHeight(), z.z() - r, z.x() + r, w.getMaxHeight(), z.z() + r)));
                }
            }
            loadedAt = System.currentTimeMillis();
        }
        return areas;
    }

    void reload() {
        loadedAt = 0;
    }

    boolean protectedAt(Location l) {
        if (!cfg().getBoolean("town.protection.enabled", true)) return false;
        for (Area a : areas()) {
            if (a.world() != l.getWorld() || !a.box().contains(l.toVector())) continue;
            if (a.box().getHeight() > 300) { // a town core: round, not square
                double dx = l.getX() - a.box().getCenterX(), dz = l.getZ() - a.box().getCenterZ(), r = a.box().getWidthX() / 2;
                if (dx * dx + dz * dz > r * r) continue;
            }
            return true;
        }
        return false;
    }

    private boolean prot(Block b) {
        return protectedAt(b.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::prot);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::prot);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (prot(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        // players lighting a fire in a fireplace or a campfire is fine; spreading, lava and lightning are not
        if (e.getCause() == BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL && e.getPlayer() != null) return;
        if (prot(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (e.getNewState().getType() == Material.FIRE && prot(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobChange(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Player) return;
        EntityType ty = e.getEntityType();
        // falling sand/gravel landing and sheep eating grass are part of the world; everything else is griefing
        if (ty == EntityType.FALLING_BLOCK || ty == EntityType.SHEEP) return;
        if (prot(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHanging(HangingBreakEvent e) {
        if (e.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION || e.getCause() == HangingBreakEvent.RemoveCause.PHYSICS) {
            if (protectedAt(e.getEntity().getLocation())) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTrample(PlayerInteractEvent e) {
        if (e.getAction() == Action.PHYSICAL && e.getClickedBlock() != null && e.getClickedBlock().getType() == Material.FARMLAND && prot(e.getClickedBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobTrample(org.bukkit.event.entity.EntityInteractEvent e) {
        if (e.getBlock().getType() == Material.FARMLAND && prot(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        // liquid from outside a protected area may not run into it
        if (prot(e.getToBlock()) && !prot(e.getBlock())) e.setCancelled(true);
    }
}
