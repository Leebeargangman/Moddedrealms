package net.moddedrealms.minigames;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Keeps the hub and arenas safe, and passes clicks, hits and pickups to the right match. */
final class Listeners implements Listener {

    private final MinigamesPlugin plugin;

    Listeners(MinigamesPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean staffBuilding(Player p) {
        return p.getGameMode() == GameMode.CREATIVE && p.hasPermission("minigames.admin") && plugin.manager().matchOf(p) == null;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.joinMessage(null);
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) plugin.hub().send(p);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        e.quitMessage(null);
        plugin.manager().leave(e.getPlayer(), true, false);
        plugin.board().forget(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!staffBuilding(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!staffBuilding(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        e.setCancelled(true);
        e.getEntity().setFoodLevel(20);
    }

    @EventHandler
    public void onWeather(WeatherChangeEvent e) {
        if (e.toWeatherState()) e.setCancelled(true);
    }

    @EventHandler
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getEntity() instanceof Mannequin) return;
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM && e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.COMMAND) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        Entity v = e.getEntity();
        // Nothing takes damage the normal way: games decide who's out.
        if (v instanceof Player || v instanceof Mannequin) e.setCancelled(true);
        if (v instanceof Player p && e.getCause() == EntityDamageEvent.DamageCause.VOID && plugin.manager().matchOf(p) == null) {
            p.teleport(plugin.hub().spawn());
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onHit(EntityDamageByEntityEvent e) {
        e.setCancelled(true);
        if (!(e.getDamager() instanceof Player attacker)) return;
        Match m = plugin.manager().matchOf(attacker);
        if (m instanceof Murder mm) {
            Participant a = mm.of(attacker);
            Participant v = mm.ofEntity(e.getEntity());
            mm.melee(a, v);
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        Projectile proj = e.getEntity();
        Entity hit = e.getHitEntity();
        for (Match m : plugin.manager().matches()) {
            if (!(m instanceof Murder mm)) continue;
            Participant v = mm.ofEntity(hit);
            Participant s = proj.getShooter() instanceof Entity se ? mm.ofEntity(se) : null;
            if (s == null && v == null) continue;
            if (v != null) {
                e.setCancelled(true);
                mm.projectileHit(proj, v);
            } else if (e.getHitBlock() != null) {
                proj.remove();
            }
            return;
        }
        if (hit instanceof Player || hit instanceof Mannequin) e.setCancelled(true);
    }

    @EventHandler
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Match m = plugin.manager().matchOf(p);
        if (!(m instanceof Murder mm) || mm.state != Match.State.PLAYING) {
            e.setCancelled(true);
            return;
        }
        Participant pa = mm.of(p);
        if (pa == null || !pa.alive || !pa.bow) {
            e.setCancelled(true);
            return;
        }
        if (e.getProjectile() instanceof org.bukkit.entity.AbstractArrow a) a.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
        mm.trackProjectile(e.getProjectile());
        mm.shot(pa);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        e.setCancelled(true);
        if (!(e.getEntity() instanceof Player p)) return;
        Item item = e.getItem();
        Match m = plugin.manager().matchOf(p);
        if (!(m instanceof Murder mm) || mm.state != Match.State.PLAYING) return;
        Participant pa = mm.of(p);
        if (pa == null || !pa.alive) return;
        if (mm.isGold(item)) {
            mm.gold.remove(item);
            item.remove();
            mm.collectGold(pa);
        } else if (mm.isBowDrop(item) && pa.role != Participant.Role.MURDERER && !pa.bow) {
            mm.pickUpBow(pa);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (!staffBuilding(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        e.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getInventory().getHolder() instanceof Hub.Menu) {
            e.setCancelled(true);
            ItemStack it = e.getCurrentItem();
            if (it == null || !it.hasItemMeta()) return;
            String id = it.getItemMeta().getPersistentDataContainer().get(plugin.itemKey, PersistentDataType.STRING);
            if (id != null && id.startsWith("menu:")) {
                GameType t = GameType.parse(id.substring(5));
                p.closeInventory();
                if (t != null) plugin.manager().join(p, t);
            }
            return;
        }
        if (!staffBuilding(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && !staffBuilding(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (e.getHand() == EquipmentSlot.OFF_HAND) return;
        Action a = e.getAction();
        boolean right = a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK;
        String id = Items.id(e.getItem());
        if (a == Action.PHYSICAL) {
            e.setCancelled(true);
            return;
        }
        if (!staffBuilding(p) && a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null && e.getClickedBlock().getType().isInteractable()) {
            e.setCancelled(true);
        }
        if (id == null || !right) return;
        switch (id) {
            case "games" -> {
                e.setCancelled(true);
                plugin.hub().openMenu(p);
            }
            case "stats" -> {
                e.setCancelled(true);
                MgCommand.showStats(plugin, p, p.getUniqueId(), p.getName());
            }
            case "back" -> {
                e.setCancelled(true);
                String server = plugin.getConfig().getString("network.back-server", "SMP");
                p.sendMessage(net.kyori.adventure.text.Component.text("Sending you to " + plugin.getConfig().getString("network.back-label", "the SMP") + "...", net.kyori.adventure.text.format.NamedTextColor.GREEN));
                plugin.sendToServer(p, server);
            }
            case "leave" -> {
                e.setCancelled(true);
                plugin.manager().leave(p, false, true);
            }
            case "again" -> {
                e.setCancelled(true);
                GameType t = plugin.manager().lastGame(p);
                if (t != null) plugin.manager().join(p, t);
            }
            case "knife" -> {
                Match m = plugin.manager().matchOf(p);
                if (m instanceof Murder mm) {
                    e.setCancelled(true);
                    Participant pa = mm.of(p);
                    if (pa != null) mm.throwKnife(pa, p.getEyeLocation().getDirection());
                }
            }
            default -> {
            }
        }
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        e.setCancelled(true);
        npcClick(e.getPlayer(), e.getRightClicked());
    }

    @EventHandler
    public void onInteractAt(PlayerInteractAtEntityEvent e) {
        e.setCancelled(true);
    }

    private void npcClick(Player p, Entity clicked) {
        String id = plugin.hub().npcId(clicked);
        if (id == null) return;
        if (id.equals("back")) {
            plugin.sendToServer(p, plugin.getConfig().getString("network.back-server", "SMP"));
            return;
        }
        GameType t = GameType.parse(id);
        if (t != null) plugin.manager().join(p, t);
    }

    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) return;
        Match m = plugin.manager().matchOf(p);
        e.setCancelled(true);
        p.setFlying(false);
        p.setAllowFlight(false);
        if (m instanceof TntRun tr && e.isFlying()) tr.doubleJump(p);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent e) {
        // Chat runs off the main thread: use the who-hears-whom snapshot the manager keeps.
        java.util.Set<java.util.UUID> hear = plugin.manager().hearers(e.getPlayer().getUniqueId());
        if (hear == null) return;
        e.viewers().removeIf(a -> a instanceof Player o && !hear.contains(o.getUniqueId()));
    }
}
