package com.colonysmp.building;

import com.colonysmp.ColonySMP;
import com.colonysmp.gui.MarkerMenu;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Perm;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Items;
import com.colonysmp.util.Keys;
import com.colonysmp.util.Text;
import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * The Building Marker: a banner placed inside a building works out what the building is and registers it.
 * Right-click it to inspect again, Shift + Right-Click to pick another type. Once the building is registered
 * the marker can be broken (it drops as a marker again) and the building stays.
 */
public final class BuildingMarker implements Listener {

    private final ColonySMP plugin;

    public BuildingMarker(ColonySMP plugin) {
        this.plugin = plugin;
    }

    /** Is this block a placed Building Marker? */
    public static boolean is(Block b) {
        if (!Tag.BANNERS.isTagged(b.getType())) return false;
        BlockState st = b.getState(false);
        return st instanceof Banner banner && Items.MARKER.equals(banner.getPersistentDataContainer().get(Keys.ITEM, PersistentDataType.STRING));
    }

    // ───────────── placing, using and breaking ─────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!Items.is(e.getItemInHand(), Items.MARKER)) return;
        Player p = e.getPlayer();
        Block b = e.getBlockPlaced();
        Colony c = plugin.colonies().at(b);
        if (c == null || !plugin.access().can(p, c, Perm.MANAGE_BUILDINGS)) {
            e.setCancelled(true);
            Text.send(p, "<red>Place the Building Marker inside a building in a colony where you manage buildings.");
            return;
        }
        // a tick later, once the banner is really there (another plugin may still cancel the placement)
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!(b.getState() instanceof Banner banner)) return;
            banner.getPersistentDataContainer().set(Keys.ITEM, PersistentDataType.STRING, Items.MARKER);
            banner.update(true, false);
            if (p.isOnline()) inspect(p, b, false);
        });
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        if (!is(b)) return;
        e.setCancelled(true);
        inspect(e.getPlayer(), b, e.getPlayer().isSneaking());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!is(e.getBlock())) return;
        e.setDropItems(false);
        if (e.getPlayer().getGameMode() != GameMode.CREATIVE) drop(e.getBlock());
    }

    /** Knocked off by physics (its support broken, a piston...): still drops as a marker. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDestroy(BlockDestroyEvent e) {
        if (!is(e.getBlock())) return;
        e.setWillDrop(false);
        drop(e.getBlock());
    }

    private void drop(Block b) {
        b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), plugin.items().marker());
    }

    // ───────────── detecting and registering ─────────────

    /** Looks at the building around a marker: registers it, re-inspects it, or asks which type it is. */
    public void inspect(Player p, Block at, boolean choose) {
        Colony c = plugin.colonies().at(at);
        if (c == null || !plugin.access().can(p, c, Perm.MANAGE_BUILDINGS)) {
            Text.send(p, "<red>You can only register buildings in a colony where you manage buildings.");
            return;
        }
        BuildingManager.Detection d = plugin.buildings().detect(c, at, choose);
        if (d.error() != null) {
            fail(p, d.error());
            return;
        }
        Building existing = d.existing();
        if (choose) {
            List<BuildingType> options = new ArrayList<>(d.matches());
            if (existing != null) options.remove(existing.type);
            if (options.isEmpty()) {
                Text.send(p, existing != null ? "<yellow>It can't be anything other than " + Text.a(existing.type.display) + "."
                        : "<yellow>It can only be " + Text.a(d.matches().get(0).display) + ".");
                if (existing == null) register(p, at, d.matches().get(0), null);
                return;
            }
            new MarkerMenu(plugin, p, at, d.matches(), existing).open();
            return;
        }
        if (existing != null) {
            register(p, at, existing.type, null);
            return;
        }
        if (d.sure()) {
            register(p, at, d.matches().get(0), null);
            if (d.matches().size() > 1) Text.raw(p, "<dark_gray>  Not right? <yellow>Shift + Right-Click</yellow> the marker to choose another type.");
            return;
        }
        Text.send(p, "<yellow>This building could be several things. Choose what it is.");
        new MarkerMenu(plugin, p, at, d.matches(), null).open();
    }

    /** Registers the building around a marker as a type, replacing {@code replace} (a building of another type there). */
    public void register(Player p, Block at, BuildingType type, Building replace) {
        if (!is(at)) {
            Text.send(p, "<red>The Building Marker is gone. Place it again.");
            return;
        }
        Colony c = plugin.colonies().at(at);
        if (c == null || !plugin.access().can(p, c, Perm.MANAGE_BUILDINGS)) {
            Text.send(p, "<red>You can only register buildings in a colony where you manage buildings.");
            return;
        }
        if (replace != null && replace.type != type) {
            if (c.buildings.get(replace.id) != replace) {
                Text.send(p, "<red>That building has changed. Right-click the marker again.");
                return;
            }
            plugin.buildings().remove(c, replace, p.getName() + " made it " + Text.a(type.display) + " instead");
        }
        Block start = type.shape == BuildingType.Shape.TOWER ? at.getRelative(BlockFace.DOWN) : at;
        int before = c.buildings.size();
        BuildingManager.Result r = plugin.buildings().register(p, c, type, start, BlueprintManager.cardinal(p.getLocation().getYaw()));
        if (r.error() != null) {
            fail(p, r.error());
            // don't leave the building unregistered when the new type didn't take
            if (replace != null && replace.type != type) plugin.buildings().register(p, c, replace.type, start, replace.facing);
            return;
        }
        Building b = r.building();
        Fx.sound(p, "minecraft:entity.villager.work_cartographer", 1f, 1f);
        p.spawnParticle(Particle.HAPPY_VILLAGER, at.getLocation().add(0.5, 1.0, 0.5), 30, 0.6, 0.6, 0.6, 0);
        boolean fresh = replace != null || c.buildings.size() > before;
        if (fresh) {
            Text.send(p, "<green>Detected " + Text.a(type.display) + "! Registered <white>" + b.label() + "</white> " + plugin.blueprints().summary(b) + ".");
            String jobs = plugin.blueprints().jobs(c, b);
            if (!jobs.isEmpty()) Text.raw(p, "<dark_gray>  Workers: " + jobs);
            Text.raw(p, "<dark_gray>  You can break the marker now: the building stays registered.");
        } else {
            Text.send(p, "<green><white>" + b.label() + "</white> passes its inspection " + plugin.blueprints().summary(b) + ".");
        }
        plugin.quests().check(c);
    }

    private static void fail(Player p, String why) {
        Text.send(p, "<red>" + why);
        Fx.sound(p, "minecraft:block.note_block.bass", 1f, 0.6f);
    }
}
