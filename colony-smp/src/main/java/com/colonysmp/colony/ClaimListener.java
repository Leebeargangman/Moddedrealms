package com.colonysmp.colony;

import com.colonysmp.ColonySMP;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Perm;
import com.colonysmp.model.Role;
import com.colonysmp.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Protects colony land: building, containers, doors, animals and fixtures follow the colony's permissions; the
 * only exceptions are the explicit rules of a war in progress.
 */
public final class ClaimListener implements Listener {

    private final ColonySMP plugin;
    private final Map<UUID, Long> lastDeny = new HashMap<>();
    private final Map<UUID, String> lastArea = new HashMap<>();

    public ClaimListener(ColonySMP plugin) {
        this.plugin = plugin;
    }

    /** Can this player change blocks here? (Records the original block when an attacker does.) */
    public boolean canBuild(Player p, Block b, boolean record) {
        Colony c = plugin.colonies().at(b);
        if (c == null || plugin.access().can(p, c, Perm.BUILD)) return true;
        if (plugin.wars().attackerMayBuild(p, c)) {
            if (record) plugin.wars().record(c, b);
            return true;
        }
        return false;
    }

    private void deny(Player p, Colony c, String what) {
        long now = System.currentTimeMillis();
        Long last = lastDeny.get(p.getUniqueId());
        if (last != null && now - last < 1500) return;
        lastDeny.put(p.getUniqueId(), now);
        Text.bar(p, "<aqua>" + Text.esc(c.name) + "</aqua> <red>is protected: you can't " + what + " here.");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!canBuild(e.getPlayer(), e.getBlock(), true)) {
            e.setCancelled(true);
            deny(e.getPlayer(), plugin.colonies().at(e.getBlock()), "break blocks");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        Colony c = plugin.colonies().at(b);
        if (c == null || plugin.access().can(e.getPlayer(), c, Perm.BUILD)) return;
        if (plugin.wars().attackerMayBuild(e.getPlayer(), c)) {
            plugin.wars().recordState(c, e.getBlockReplacedState());
            return;
        }
        e.setCancelled(true);
        deny(e.getPlayer(), c, "build");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (!canBuild(e.getPlayer(), e.getBlock(), true)) {
            e.setCancelled(true);
            deny(e.getPlayer(), plugin.colonies().at(e.getBlock()), "pour liquids");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFill(PlayerBucketFillEvent e) {
        if (!canBuild(e.getPlayer(), e.getBlock(), true)) {
            e.setCancelled(true);
            deny(e.getPlayer(), plugin.colonies().at(e.getBlock()), "take liquids");
        }
    }

    @SuppressWarnings("deprecation") // Material#isInteractable is still the best broad check
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        Colony c = plugin.colonies().at(b);
        if (c == null) return;
        Player p = e.getPlayer();
        // nobody tramples farmland in a colony, members included
        if (e.getAction() == Action.PHYSICAL && b.getType() == Material.FARMLAND && plugin.settings().protectFarmland) {
            e.setCancelled(true);
            return;
        }
        if (plugin.isBypassing(p)) return;
        boolean attacker = plugin.wars().attackerMayBuild(p, c);
        if (e.getAction() == Action.PHYSICAL) {
            if (!plugin.access().can(p, c, Perm.INTERACT) && !attacker) e.setCancelled(true);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        BlockState st = b.getState(false);
        if (st instanceof Container) {
            if (plugin.access().can(p, c, Perm.CONTAINERS)) return;
            // attackers may build against containers (to wall in storage) but never open them
            if (attacker && p.isSneaking() && e.getItem() != null && e.getItem().getType().isBlock()) return;
            e.setCancelled(true);
            deny(p, c, "open containers");
            return;
        }
        if (b.getType().isInteractable() && !plugin.access().can(p, c, Perm.INTERACT) && !attacker) {
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            e.setCancelled(true);
            deny(p, c, "use that");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Entity t = e.getRightClicked();
        if (!(t instanceof Hanging || t instanceof ArmorStand || t instanceof Vehicle && !(t instanceof org.bukkit.entity.LivingEntity))) return;
        Colony c = plugin.colonies().at(t.getLocation());
        Player p = e.getPlayer();
        if (c != null && !plugin.access().can(p, c, Perm.BUILD) && !plugin.wars().attackerMayBuild(p, c)) {
            e.setCancelled(true);
            deny(p, c, "touch that");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        Colony c = plugin.colonies().at(e.getRightClicked().getLocation());
        if (c != null && !plugin.access().can(e.getPlayer(), c, Perm.BUILD)) {
            e.setCancelled(true);
            deny(e.getPlayer(), c, "touch that");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        Player p = attacker(e.getRemover());
        if (p == null) return;
        Colony c = plugin.colonies().at(e.getEntity().getLocation());
        if (c != null && !plugin.access().can(p, c, Perm.BUILD) && !plugin.wars().attackerMayBuild(p, c)) {
            e.setCancelled(true);
            deny(p, c, "break that");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleBreak(VehicleDestroyEvent e) {
        Player p = attacker(e.getAttacker());
        if (p == null) return;
        Colony c = plugin.colonies().at(e.getVehicle().getLocation());
        if (c != null && !plugin.access().can(p, c, Perm.BUILD) && !plugin.wars().attackerMayBuild(p, c)) {
            e.setCancelled(true);
            deny(p, c, "break that");
        }
    }

    /** Livestock, armour stands and item frames in a colony; and PvP. (Citizens are handled by the NPC manager.) */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Player p = attacker(e.getDamager());
        if (p == null) return;
        Entity victim = e.getEntity();
        Colony c = plugin.colonies().at(victim.getLocation());
        if (victim instanceof Player target) {
            if (target.equals(p) || plugin.wars().pvpAllowed(p, target)) return;
            Colony here = c != null ? c : plugin.colonies().at(p.getLocation());
            if (here != null && plugin.settings().blockPvp) {
                e.setCancelled(true);
                deny(p, here, "fight players (no war)");
            }
            return;
        }
        if (c == null || plugin.access().can(p, c, Perm.BUILD)) return;
        if (victim instanceof Animals || victim instanceof ArmorStand || victim instanceof Hanging) {
            if (plugin.wars().attackerMayBuild(p, c)) return;
            e.setCancelled(true);
            deny(p, c, "hurt that");
        }
    }

    static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr) {
            ProjectileSource s = pr.getShooter();
            if (s instanceof Player p) return p;
        }
        if (damager instanceof org.bukkit.entity.TNTPrimed t && t.getSource() instanceof Player p) return p;
        return null;
    }

    // ───────────── world damage ─────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        filter(e.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        filter(e.blockList());
    }

    private void filter(List<Block> blocks) {
        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            Block b = it.next();
            Colony c = plugin.colonies().at(b);
            if (c == null) continue;
            if (plugin.townHall().isCore(b) != null || plugin.townHall().isChest(b) != null) {
                it.remove();
                continue;
            }
            if (plugin.wars().fighting(c)) {
                plugin.wars().record(c, b);
                continue;
            }
            if (plugin.settings().protectExplosions) it.remove();
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (plugin.settings().protectFire && plugin.colonies().at(e.getBlock()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        Colony c = plugin.colonies().at(e.getBlock());
        if (c == null) return;
        if (e.getPlayer() != null) {
            if (!canBuild(e.getPlayer(), e.getBlock(), true)) {
                e.setCancelled(true);
                deny(e.getPlayer(), c, "light fires");
            }
            return;
        }
        if (plugin.settings().protectFire && e.getCause() == BlockIgniteEvent.IgniteCause.SPREAD) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (plugin.settings().protectFire && e.getNewState().getType() == Material.FIRE && plugin.colonies().at(e.getBlock()) != null) e.setCancelled(true);
    }

    /** Liquids can't flow into colony land from outside it. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        Colony to = plugin.colonies().at(e.getToBlock());
        if (to == null) return;
        if (plugin.colonies().at(e.getBlock()) != to) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        Colony pc = plugin.colonies().at(e.getBlock());
        for (Block b : e.getBlocks()) {
            if (blocked(pc, b) || blocked(pc, b.getRelative(e.getDirection()))) {
                e.setCancelled(true);
                return;
            }
        }
        if (blocked(pc, e.getBlock().getRelative(e.getDirection()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        Colony pc = plugin.colonies().at(e.getBlock());
        for (Block b : e.getBlocks()) {
            if (blocked(pc, b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    private boolean blocked(Colony pistonColony, Block b) {
        if (plugin.townHall().isCore(b) != null || plugin.townHall().isChest(b) != null) return true;
        Colony c = plugin.colonies().at(b);
        return c != null && c != pistonColony;
    }

    /** Mobs trampling farmland, endermen taking blocks and the like. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        Block b = e.getBlock();
        Colony c = plugin.colonies().at(b);
        if (c == null) return;
        if (b.getType() == Material.FARMLAND && e.getTo() == Material.DIRT && plugin.settings().protectFarmland) {
            e.setCancelled(true);
            return;
        }
        if (e.getEntity() instanceof Player p) {
            if (!canBuild(p, b, true)) e.setCancelled(true);
            return;
        }
        if (b.getType() == Material.FARMLAND) {
            e.setCancelled(true);
            return;
        }
        if (e.getEntity() instanceof org.bukkit.entity.Enderman || e.getEntity() instanceof org.bukkit.entity.Ravager
                || e.getEntity() instanceof org.bukkit.entity.Wither || e.getEntity() instanceof org.bukkit.entity.EnderDragon) {
            if (!plugin.wars().fighting(c)) e.setCancelled(true);
        }
        if (Tag.DOORS.isTagged(b.getType()) && e.getEntity() instanceof org.bukkit.entity.Zombie) e.setCancelled(true);
    }

    /** Farmland in a colony doesn't dry back into dirt... */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        if (e.getBlock().getType() == Material.FARMLAND && plugin.settings().protectFarmland && plugin.colonies().at(e.getBlock()) != null) e.setCancelled(true);
    }

    /** ...and stays moist, so crops keep growing at full speed. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMoisture(MoistureChangeEvent e) {
        if (!plugin.settings().protectFarmland || plugin.colonies().at(e.getBlock()) == null) return;
        if (e.getBlock().getBlockData() instanceof org.bukkit.block.data.type.Farmland now
                && e.getNewState().getBlockData() instanceof org.bukkit.block.data.type.Farmland next
                && next.getMoisture() < now.getMoisture()) {
            e.setCancelled(true);
        }
    }

    // ───────────── entering and leaving ─────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!plugin.settings().enterMessages) return;
        Location from = e.getFrom(), to = e.getTo();
        if (from.getBlockX() >> 4 == to.getBlockX() >> 4 && from.getBlockZ() >> 4 == to.getBlockZ() >> 4 && from.getWorld() == to.getWorld()) return;
        Colony c = plugin.colonies().at(to);
        String id = c == null ? "" : c.id;
        String last = lastArea.put(e.getPlayer().getUniqueId(), id);
        if (last == null || last.equals(id)) return;
        Player p = e.getPlayer();
        if (c != null) {
            Role r = plugin.access().role(p, c);
            String rel = switch (r) {
                case OWNER, CO_OWNER, MANAGER, RESIDENT -> "<green>Welcome home";
                case ALLY -> "<aqua>Allied colony";
                case ENEMY -> "<red>Enemy territory!";
                default -> c.shielded() ? "<aqua>Under a peace shield" : "<gray>Visitor";
            };
            Text.bar(p, "<gold>Entering " + Text.esc(c.name) + "</gold> <dark_gray>|</dark_gray> " + rel);
        } else {
            Colony old = plugin.colonies().get(last);
            if (old != null) Text.bar(p, "<gray>Leaving " + Text.esc(old.name));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastArea.remove(e.getPlayer().getUniqueId());
        lastDeny.remove(e.getPlayer().getUniqueId());
    }
}
