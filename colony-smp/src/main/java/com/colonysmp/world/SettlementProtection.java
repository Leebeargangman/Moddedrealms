package com.colonysmp.world;

import com.colonysmp.ColonySMP;
import com.colonysmp.civ.Settlement;
import com.colonysmp.gui.BarterMenu;
import com.colonysmp.util.Keys;
import com.colonysmp.util.Text;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataType;

/**
 * Keeps a living settlement's buildings and people from harm: its recorded structures can't be broken, built
 * on, burned, blown up or pushed about, its containers are scenery (the real stores are in its books and are
 * reached only through the merchant), and its villagers can't be hurt, traded with the vanilla way or changed.
 * Ruins are not protected. Nothing here touches ground the settlement didn't build on.
 */
public final class SettlementProtection implements Listener {

    private final ColonySMP plugin;
    private final CivManager civs;

    public SettlementProtection(ColonySMP plugin, CivManager civs) {
        this.plugin = plugin;
        this.civs = civs;
    }

    private boolean guarded(Block b) {
        return plugin.settings().civs.protect && civs.builder().owner(b) != null;
    }

    private void deny(Cancellable e, Player p, Block b) {
        if (p != null && plugin.isBypassing(p)) return;
        Settlement s = civs.builder().owner(b);
        if (s == null || !plugin.settings().civs.protect) return;
        e.setCancelled(true);
        if (p != null) Text.bar(p, "<red>This belongs to " + Text.esc(s.name) + ".");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        deny(e, e.getPlayer(), e.getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        deny(e, e.getPlayer(), e.getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        deny(e, e.getPlayer(), e.getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (guarded(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (guarded(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (plugin.settings().civs.protect) e.blockList().removeIf(b -> civs.builder().owner(b) != null);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (plugin.settings().civs.protect) e.blockList().removeIf(b -> civs.builder().owner(b) != null);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) if (guarded(b) || guarded(b.getRelative(e.getDirection()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPiston(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) if (guarded(b)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChange(EntityChangeBlockEvent e) {
        if (!(e.getEntity() instanceof Player) && guarded(e.getBlock())) e.setCancelled(true);
    }

    /** The barrels and furnaces of a settlement are scenery: its stores are in its books, behind the merchant. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || plugin.isBypassing(e.getPlayer())) return;
        if (e.getAction() == Action.PHYSICAL && b.getType() == org.bukkit.Material.FARMLAND && guarded(b)) {
            e.setCancelled(true);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || !(b.getState(false) instanceof InventoryHolder)) return;
        Settlement s = plugin.settings().civs.protect ? civs.builder().owner(b) : null;
        if (s == null) return;
        e.setCancelled(true);
        Text.bar(e.getPlayer(), "<gray>The stores of " + Text.esc(s.name) + " are kept by its merchant.");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntity(PlayerInteractEntityEvent e) {
        Entity en = e.getRightClicked();
        String id = SettlementBodies.settlementOf(en);
        if (id == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Settlement s = civs.world().settlements.get(id);
        if (s == null) return;
        Player p = e.getPlayer();
        if (SettlementBodies.merchant(en)) {
            String closed = civs.closed(p, s);
            if (closed != null) Text.send(p, "<red>" + Text.esc(closed));
            else new BarterMenu(plugin, p, s).open();
        } else {
            Text.send(p, "<white>" + Text.esc(en.getName()) + "</white><gray>: <italic>" + Text.esc(civs.bodies().remark(s)));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(Keys.CIV_NPC, PersistentDataType.STRING)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(Keys.CIV_NPC, PersistentDataType.STRING)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCareer(VillagerCareerChangeEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(Keys.CIV_NPC, PersistentDataType.STRING)) e.setCancelled(true);
    }

    /** A settlement villager left behind by a crash (they are never saved, but belt and braces). */
    @EventHandler
    public void onLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) if (en.getPersistentDataContainer().has(Keys.CIV_NPC, PersistentDataType.STRING)) en.remove();
    }
}
