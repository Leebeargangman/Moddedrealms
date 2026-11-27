package com.colonysmp.colony;

import com.colonysmp.ColonySMP;
import com.colonysmp.gui.StorageMenu;
import com.colonysmp.gui.TownHallMenu;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Perm;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.ItemGoods;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The Town Hall Core block, the storage chest beside it, founding colonies and feeding storage by hopper. */
public final class TownHallListener implements Listener {

    private static final BlockFace[] SIDES = {BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.NORTH};

    private final ColonySMP plugin;

    public TownHallListener(ColonySMP plugin) {
        this.plugin = plugin;
    }

    // ───────────── founding ─────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        ItemStack it = e.getItemInHand();
        Player p = e.getPlayer();
        if (Items.is(it, Items.CORE)) {
            Colony c = checkCore(p, e.getBlockPlaced(), it);
            if (c == null) {
                e.setCancelled(true);
                return;
            }
            Block placed = e.getBlockPlaced();
            Bukkit.getScheduler().runTask(plugin, () -> raise(p, c, placed));
            return;
        }
        // a chest next to the storage chest would turn it into a double chest
        Material m = e.getBlockPlaced().getType();
        if (m == Material.CHEST || m == Material.TRAPPED_CHEST) {
            for (BlockFace f : SIDES) {
                if (isChest(e.getBlockPlaced().getRelative(f)) != null) {
                    e.setCancelled(true);
                    Text.send(p, "<red>You can't build a chest against the Town Hall storage chest.");
                    return;
                }
            }
        }
    }

    private Colony checkCore(Player p, Block block, ItemStack it) {
        Colony c = plugin.colonies().get(Items.colonyOf(it));
        if (c == null) {
            Text.send(p, "<red>This Town Hall Core belongs to a colony that no longer exists.");
            return null;
        }
        if (!plugin.access().can(p, c, Perm.SETTINGS)) {
            Text.send(p, "<red>Only the owners of " + Text.esc(c.name) + " can place its Town Hall Core.");
            return null;
        }
        if (c.core != null) {
            Text.send(p, "<red>" + Text.esc(c.name) + " already has a Town Hall.");
            return null;
        }
        if (!c.territory.isEmpty() && !c.territory.contains(block.getX(), block.getZ())) {
            Text.send(p, "<red>Place the Town Hall Core inside your colony's land.");
            return null;
        }
        if (c.territory.isEmpty()) {
            int r = plugin.settings().startRadius;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    String err = plugin.territory().checkChunk(c, block.getWorld().getName(), (block.getX() >> 4) + dx, (block.getZ() >> 4) + dz, true);
                    if (err != null) {
                        Text.send(p, "<red>You can't found " + Text.esc(c.name) + " here: " + err);
                        return null;
                    }
                }
            }
        }
        if (chestSpot(block) == null) {
            Text.send(p, "<red>The storage chest needs a free block beside the core (not next to another chest).");
            return null;
        }
        return c;
    }

    private static Block chestSpot(Block core) {
        for (BlockFace f : SIDES) {
            Block b = core.getRelative(f);
            if (!b.getType().isAir() && !b.isReplaceable()) continue;
            boolean nextToChest = false;
            for (BlockFace g : SIDES) {
                Block n = b.getRelative(g);
                if (n.equals(core)) continue;
                if (n.getType() == Material.CHEST || n.getType() == Material.TRAPPED_CHEST) nextToChest = true;
            }
            if (!nextToChest) return b;
        }
        return null;
    }

    /** The core is down: claim the land, build the storage chest, register the Town Hall, welcome the settlers. */
    public void raise(Player p, Colony c, Block block) {
        if (c.core != null || plugin.colonies().get(c.id) == null || block.getType() != Material.LODESTONE) return;
        Block chestBlock = chestSpot(block);
        if (chestBlock == null) {
            refund(p, c, block, "The storage chest needs a free block beside the core.");
            return;
        }
        if (c.territory.isEmpty()) {
            String err = plugin.territory().found(c, block);
            if (err != null) {
                refund(p, c, block, err);
                return;
            }
        }
        chestBlock.setType(Material.CHEST, false);
        if (chestBlock.getBlockData() instanceof Chest cd) {
            BlockFace away = block.getFace(chestBlock);
            cd.setFacing(away == null ? BlockFace.SOUTH : away);
            cd.setType(Chest.Type.SINGLE);
            chestBlock.setBlockData(cd, false);
        }
        c.core = BlockPos.of(block);
        c.chest = BlockPos.of(chestBlock);
        c.world = block.getWorld().getName();
        plugin.buildings().createTownHall(c);
        long day = plugin.sim().day(c);
        if (c.foundedDay == 0) c.foundedDay = day;
        Location at = block.getLocation().add(0.5, 1.2, 0.5);
        Fx.particle(at, Particle.TOTEM_OF_UNDYING, 60, 0.6);
        Fx.sound(at, "minecraft:block.beacon.activate", 1.2f, 0.9f);
        for (Player m : c.onlineMembers()) Text.title(m, "<aqua>Town Hall Raised", "<gold>" + Text.esc(c.name) + "</gold> <gray>is founded", 10, 60, 20);
        if (p != null) {
            Text.send(p, "The <aqua>Town Hall</aqua> stands. Its land (" + c.territory.size() + " chunks) is yours, and the <gold>storage chest</gold> beside it holds the colony's goods. "
                    + "Right-click the core for the Town Hall menu.");
        }
        c.log(ColonyEvent.Type.COLONY, day, (p == null ? "The colony of " + c.name + " was founded" : p.getName() + " founded " + c.name) + ".");
        if (!c.founded) {
            c.founded = true;
            plugin.sim().population().settlers(c);
        }
        plugin.quests().check(c);
        plugin.requestSave();
    }

    private void refund(Player p, Colony c, Block block, String why) {
        block.setType(Material.AIR);
        if (p == null) {
            plugin.getLogger().warning("Couldn't found " + c.name + ": " + why);
            return;
        }
        com.colonysmp.command.ColonyCommand.give(p, plugin.items().core(c.id, c.name));
        Text.send(p, "<red>" + why);
    }

    // ───────────── protecting the core and chest ─────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Colony core = isCore(b);
        if (core != null) {
            if (plugin.wars().onCoreBreak(e.getPlayer(), core)) {
                e.setDropItems(false);
                return;
            }
            e.setCancelled(true);
            Text.send(e.getPlayer(), core.isMember(e.getPlayer().getUniqueId())
                    ? "<red>The Town Hall Core can't be broken."
                    : "<red>The Town Hall Core of " + Text.esc(core.name) + " can only be destroyed by besiegers in a siege.");
            return;
        }
        if (isChest(b) != null) {
            e.setCancelled(true);
            Text.send(e.getPlayer(), "<red>The Town Hall storage chest can't be broken.");
        }
    }

    public Colony isCore(Block b) {
        Colony c = plugin.colonies().at(b);
        return c != null && c.core != null && c.core.x() == b.getX() && c.core.y() == b.getY() && c.core.z() == b.getZ() ? c : null;
    }

    public Colony isChest(Block b) {
        Colony c = plugin.colonies().at(b);
        return c != null && c.chest != null && c.chest.x() == b.getX() && c.chest.y() == b.getY() && c.chest.z() == b.getZ() ? c : null;
    }

    // ───────────── using them ─────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (p.isSneaking() && e.getItem() != null && e.getItem().getType().isBlock() && Items.id(e.getItem()) == null) return;
        Block b = e.getClickedBlock();
        Colony core = isCore(b);
        if (core != null) {
            e.setCancelled(true);
            if (core.isMember(p.getUniqueId()) || plugin.isBypassing(p)) new TownHallMenu(plugin, p, core).open();
            else plugin.commands().sendInfo(p, core);
            return;
        }
        Colony chest = isChest(b);
        if (chest != null) {
            e.setCancelled(true);
            if (plugin.access().can(p, chest, Perm.STORAGE_DEPOSIT) || plugin.access().can(p, chest, Perm.STORAGE_WITHDRAW)) {
                new StorageMenu(plugin, p, chest).open();
                Fx.sound(p, "minecraft:block.chest.open", 0.6f, 1f);
            } else {
                Text.send(p, "<red>The storage of " + Text.esc(chest.name) + " is not yours to use.");
            }
        }
    }

    // ───────────── hoppers feed storage ─────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        Colony into = chestOf(e.getDestination());
        Colony from = chestOf(e.getSource());
        if (from != null) {
            e.setCancelled(true);
            return;
        }
        if (into == null) return;
        e.setCancelled(true);
        Inventory src = e.getSource();
        ItemStack item = e.getItem().clone();
        Bukkit.getScheduler().runTask(plugin, () -> {
            String good = ItemGoods.goodOf(item);
            if (good == null && !ItemGoods.equipment(item)) return;
            Map<Integer, ItemStack> left = src.removeItem(item.clone());
            int removed = item.getAmount();
            for (ItemStack l : left.values()) removed -= l.getAmount();
            if (removed <= 0) return;
            int over;
            if (good != null) over = removed - (int) into.storage.add(good, removed);
            else {
                ItemStack one = item.clone();
                one.setAmount(removed);
                over = into.equipment.add(one) ? 0 : removed;
            }
            if (over <= 0) return;
            ItemStack back = item.clone();
            back.setAmount(over);
            for (ItemStack b : src.addItem(back).values()) {
                Location l = src.getLocation();
                if (l != null && l.getWorld() != null) l.getWorld().dropItemNaturally(l.add(0.5, 1, 0.5), b);
            }
        });
    }

    private Colony chestOf(Inventory inv) {
        if (inv == null) return null;
        Location l = inv.getLocation();
        if (l == null || l.getWorld() == null) return null;
        return isChest(l.getBlock());
    }

    /** Puts the core and chest back if something removed them (WorldEdit, a lost siege restored). */
    public void verify(Colony c) {
        World w = c.world();
        if (w == null || c.core == null || c.chest == null || !c.chest.loaded(w)) return;
        Block cb = c.chest.block(w);
        if (cb.getType() != Material.CHEST) cb.setType(Material.CHEST, false);
        Block core = c.core.block(w);
        if (core.getType() != Material.LODESTONE && !plugin.wars().coreDestroyed(c)) core.setType(Material.LODESTONE, false);
    }

    /** A colony is dissolved: its core and chest go, and some of its stock is left on the ground. */
    public void dissolve(Colony c) {
        World w = c.world();
        if (w == null) return;
        Location at = c.chestLocation() != null ? c.chestLocation().add(0, 1, 0) : c.coreLocation();
        if (c.core != null && c.core.loaded(w) && c.core.block(w).getType() == Material.LODESTONE) c.core.block(w).setType(Material.AIR);
        if (c.chest != null && c.chest.loaded(w) && c.chest.block(w).getType() == Material.CHEST) c.chest.block(w).setType(Material.AIR);
        if (at == null || !w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return;
        List<ItemStack> drop = new ArrayList<>(c.equipment.contents());
        for (Map.Entry<String, Long> e : c.storage.snapshot().entrySet()) {
            if (drop.size() >= 108) break;
            drop.addAll(ItemGoods.stacks(e.getKey(), Math.min(e.getValue(), 64L * 4)));
        }
        int n = 0;
        for (ItemStack it : drop) {
            if (n++ >= 108) break;
            w.dropItemNaturally(at, it);
        }
    }
}
