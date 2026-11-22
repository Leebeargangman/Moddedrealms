package net.moddedrealms.smp.town;

import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;

/**
 * Spots crimes inside the police zone. Everything listens with ignoreCancelled, so whatever WorldGuard or another
 * plugin already blocked (no PvP, no building at spawn) is never a crime. Self-defence and fighting someone the
 * police want are never crimes either.
 */
public final class Crimes implements Listener {
    private final TownManager t;

    Crimes(TownManager t) {
        this.t = t;
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    private long selfDefenceMs() {
        return t.plugin().getConfig().getLong("town.self-defence-seconds", 10) * 1000L;
    }

    /**
     * Officers and agents never kill: the hit that would is an arrest instead. The same goes for anyone who brings
     * down a wanted player (a citizen's arrest): they claim the bounty.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLethal(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        if (e.getFinalDamage() < victim.getHealth()) return;
        Walker w = t.walker(e.getDamager());
        if (w != null && (w.role == Walker.Role.OFFICER || w.role == Walker.Role.AGENT)) {
            e.setCancelled(true);
            t.arrest(victim, w, true, null);
            return;
        }
        Player a = attacker(e.getDamager());
        if (a == null || a == victim || !t.plugin().getConfig().getBoolean("town.bounty.citizens-arrest", true)) return;
        TownData vd = t.data(victim), ad = t.data(a);
        if (vd.stars <= 0 || ad.stars > 0 || t.exempt(a) || !t.bureau().inReach(victim)) return;
        e.setCancelled(true);
        vd.damagedBy.put(a.getUniqueId(), System.currentTimeMillis());
        a.sendMessage(SmpPlugin.mm("<gold>⚑ Citizen's arrest! <gray>You brought down " + victim.getName() + "."));
        victim.sendMessage(SmpPlugin.mm("<red>" + a.getName() + " made a citizen's arrest!"));
        t.arrest(victim, null, true, a);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Player a = attacker(e.getDamager());
        if (a == null) return;
        long now = System.currentTimeMillis();
        if (e.getEntity() instanceof Player v && v != a) {
            TownData ad = t.data(a), vd = t.data(v);
            ad.attacked.put(v.getUniqueId(), now);
            vd.damagedBy.put(a.getUniqueId(), now);
            if (vd.stars > 0) return; // helping the police
            Long theyHitFirst = vd.attacked.get(a.getUniqueId());
            if (theyHitFirst != null && now - theyHitFirst <= selfDefenceMs()) return;
            t.commit(a, Crime.ASSAULT, v.getLocation(), v.getUniqueId().toString(), null, false);
        } else if (e.getEntity() instanceof AbstractVillager vil && t.walker(vil) == null) {
            t.commit(a, Crime.ASSAULT_NPC, vil.getLocation(), vil.getUniqueId().toString(), null, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player v = e.getEntity();
        Player k = v.getKiller();
        if (k == null || k == v) return;
        TownData vd = t.data(v), kd = t.data(k);
        if (vd.stars > 0) return;
        Long theyHitFirst = vd.attacked.get(k.getUniqueId());
        if (theyHitFirst != null && System.currentTimeMillis() - theyHitFirst <= selfDefenceMs() * 3) return;
        t.commit(k, Crime.MURDER, v.getLocation(), v.getUniqueId().toString(), null, false);
        if (kd.stars > 0) t.news(k.getName() + " murdered " + v.getName() + " in cold blood!");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player) return;
        Player k = e.getEntity().getKiller();
        if (k == null) return;
        if (e.getEntity() instanceof Tameable pet && pet.isTamed() && pet.getOwnerUniqueId() != null && !pet.getOwnerUniqueId().equals(k.getUniqueId())) {
            t.commit(k, Crime.PET_KILLING, pet.getLocation(), pet.getUniqueId().toString(), null, false);
        } else if (e.getEntity() instanceof AbstractVillager vil && t.walker(vil) == null) {
            t.commit(k, Crime.MANSLAUGHTER, vil.getLocation(), vil.getUniqueId().toString(), null, false);
        }
    }

    /** Fire next to something that burns: a fireplace on netherrack or a portal isn't arson. */
    private static boolean nearFlammable(Block b) {
        World w = b.getWorld();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (w.getBlockAt(b.getX() + dx, b.getY() + dy, b.getZ() + dz).getType().isFlammable()) return true;
                }
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        Player p = e.getPlayer();
        if (p == null) {
            if (e.getIgnitingEntity() instanceof Projectile pr && pr.getShooter() instanceof Player s) p = s;
            else return;
        }
        if (e.getCause() != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL && e.getCause() != BlockIgniteEvent.IgniteCause.FIREBALL
                && e.getCause() != BlockIgniteEvent.IgniteCause.ARROW) return;
        Material below = e.getBlock().getRelative(0, -1, 0).getType();
        if (below == Material.NETHERRACK || below == Material.SOUL_SAND || below == Material.SOUL_SOIL || below == Material.OBSIDIAN
                || below == Material.MAGMA_BLOCK) return;
        if (!nearFlammable(e.getBlock())) return;
        t.commit(p, Crime.ARSON, e.getBlock().getLocation(), "fire", null, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLava(PlayerBucketEmptyEvent e) {
        if (e.getBucket() != Material.LAVA_BUCKET) return;
        Block b = e.getBlockClicked().getRelative(e.getBlockFace());
        if (nearFlammable(b)) t.commit(e.getPlayer(), Crime.ARSON, b.getLocation(), "lava", null, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Material m = e.getBlockPlaced().getType();
        boolean anchor = m == Material.RESPAWN_ANCHOR && e.getBlock().getWorld().getEnvironment() != World.Environment.NETHER;
        if (m == Material.TNT || anchor) t.commit(e.getPlayer(), Crime.EXPLOSIVES, e.getBlock().getLocation(), "tnt", null, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent e) {
        if (e.getPlayer() == null) return;
        var type = e.getEntity().getType().name();
        if (type.equals("END_CRYSTAL") || type.equals("TNT_MINECART")) t.commit(e.getPlayer(), Crime.EXPLOSIVES, e.getEntity().getLocation(), "tnt", null, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPrime(TNTPrimeEvent e) {
        Entity by = e.getPrimingEntity();
        Player p = by instanceof Player pl ? pl : attacker(by);
        if (p != null) t.commit(p, Crime.EXPLOSIVES, e.getBlock().getLocation(), "tnt", null, false);
    }

    // ------------------------------------------------------------------ robbing players

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickpocket(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !(e.getRightClicked() instanceof Player v)) return;
        Player p = e.getPlayer();
        if (!p.isSneaking() || !p.getInventory().getItemInMainHand().getType().isAir()) return;
        if (t.police().onDuty(p) && t.data(v).stars > 0) {
            e.setCancelled(true);
            t.police().cuff(p, v);
            return;
        }
        var c = t.plugin().getConfig();
        if (!c.getBoolean("town.rob-players", true) || !t.money.ready()) return;
        if (t.exempt(p) || t.exempt(v) || !t.inPoliceZone(p.getLocation())) return;
        if (p.getLocation().distance(v.getLocation()) > 2.5) return;
        TownData pd = t.data(p), vd = t.data(v);
        long now = System.currentTimeMillis();
        long cooldown = c.getLong("town.rob.cooldown-minutes", 5) * 60_000;
        if (now - pd.lastRobTry < cooldown) {
            p.sendMessage(SmpPlugin.mm("<gray>Your hands are still shaking from the last time. Wait " + ((cooldown - (now - pd.lastRobTry)) / 60_000 + 1) + " min."));
            return;
        }
        if (now - vd.lastRobbed < c.getLong("town.rob.victim-protection-minutes", 20) * 60_000) {
            p.sendMessage(SmpPlugin.mm("<gray>" + v.getName() + " is clutching their purse tightly. Someone got them recently."));
            return;
        }
        double bal = t.money.balance(v);
        if (bal < c.getDouble("town.rob.min-balance", 50)) {
            p.sendMessage(SmpPlugin.mm("<gray>Their pockets are empty."));
            return;
        }
        pd.lastRobTry = now;
        Vector facing = v.getLocation().getDirection().setY(0);
        Vector toThief = p.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
        boolean behind = facing.lengthSquared() > 0 && toThief.lengthSquared() > 0 && facing.normalize().dot(toThief.normalize()) < -0.2;
        double chance = behind ? c.getDouble("town.rob.chance-behind", 0.5) : c.getDouble("town.rob.chance-front", 0.1);
        TownManager.Tier tier = t.tier(p);
        if (tier == TownManager.Tier.ROGUE || tier == TownManager.Tier.OUTLAW) chance += 0.1;
        if (Math.random() < chance) {
            double amount = Math.max(1, Math.floor(Math.min(bal * c.getDouble("town.rob.fraction", 0.05), c.getDouble("town.rob.max", 500))));
            double took = t.money.take(v, amount);
            if (took <= 0) return;
            t.money.give(p, took);
            vd.lastRobbed = now;
            p.sendMessage(SmpPlugin.mm("<dark_green>You lifted <white>" + Money.fmt(took) + "</white> from " + v.getName() + "."));
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.6f);
            Bukkit.getScheduler().runTaskLater(t.plugin(), () -> {
                if (v.isOnline()) v.sendMessage(SmpPlugin.mm("<red>Your coin purse feels lighter... <white>(-" + Money.fmt(took) + ")"));
            }, 60L);
            t.commit(p, Crime.ROBBERY, p.getLocation(), v.getUniqueId().toString(), null, false);
        } else {
            p.sendMessage(SmpPlugin.mm("<red>" + v.getName() + " caught your hand in their pocket!"));
            v.sendMessage(SmpPlugin.mm("<red>" + p.getName() + " just tried to pick your pocket! <gray>The watch has been told."));
            v.playSound(v.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
            t.commit(p, Crime.ROBBERY, p.getLocation(), v.getUniqueId().toString(), null, true);
        }
        e.setCancelled(true);
    }

    static boolean same(Location a, Location b) {
        return a.getWorld() == b.getWorld();
    }
}
