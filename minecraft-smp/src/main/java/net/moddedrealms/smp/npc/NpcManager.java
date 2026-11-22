package net.moddedrealms.smp.npc;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.entity.LookAnchor;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

/**
 * NPCs are vanilla Mannequins (player-shaped, can wear a skin). They are not saved with the world: npcs.yml is
 * the source of truth and they are (re)spawned whenever their chunk is loaded, so nothing can duplicate them.
 */
public final class NpcManager implements Listener {
    private final SmpPlugin plugin;
    private final File file;
    private final Map<String, NpcDef> defs = new LinkedHashMap<>();
    private final Map<String, UUID> entities = new HashMap<>();
    private final Map<String, Location> walkTarget = new HashMap<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();
    public final NamespacedKey key;
    private final Random random = new Random();
    private int tick;

    public NpcManager(SmpPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "npcs.yml");
        this.key = new NamespacedKey(plugin, "npc");
        load();
    }

    private void load() {
        defs.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String id : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(id);
            if (s != null) defs.put(id, NpcDef.load(id, s));
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (NpcDef d : defs.values()) d.save(y.createSection(d.id));
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save npcs.yml: " + e.getMessage());
        }
    }

    public void reload() {
        despawnAll();
        load();
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::onTick, 20L, 2L);
    }

    public Collection<NpcDef> all() {
        return defs.values();
    }

    public NpcDef get(String id) {
        return defs.get(id);
    }

    public void put(NpcDef d) {
        remove(d.id);
        defs.put(d.id, d);
        save();
    }

    public boolean remove(String id) {
        despawn(id);
        boolean had = defs.remove(id) != null;
        if (had) save();
        return had;
    }

    public void removeGroup(String group) {
        defs.values().removeIf(d -> {
            if (group.equals(d.group)) {
                despawn(d.id);
                return true;
            }
            return false;
        });
        save();
    }

    /** Respawn one NPC so changes (skin, name, gear) show. */
    public void refresh(String id) {
        despawn(id);
    }

    private void despawn(String id) {
        UUID u = entities.remove(id);
        walkTarget.remove(id);
        if (u != null) {
            Entity e = Bukkit.getEntity(u);
            if (e != null) e.remove();
        }
    }

    public void despawnAll() {
        for (String id : new java.util.ArrayList<>(entities.keySet())) despawn(id);
    }

    public Entity entity(String id) {
        UUID u = entities.get(id);
        return u == null ? null : Bukkit.getEntity(u);
    }

    private void onTick() {
        tick++;
        double r = plugin.getConfig().getDouble("npc-look-radius", 8);
        for (NpcDef d : defs.values()) {
            Location home = d.home();
            if (home == null) continue;
            Entity e = entity(d.id);
            if (e == null || !e.isValid()) {
                if (tick % 10 != 0) continue;
                if (!home.getWorld().isChunkLoaded(home.getBlockX() >> 4, home.getBlockZ() >> 4)) continue;
                e = spawn(d, home);
                if (e == null) continue;
            }
            Mannequin m = (Mannequin) e;
            if (d.wander > 0 && walk(d, m, home)) continue;
            if (d.look && tick % 2 == 0) {
                Player near = null;
                double best = r * r;
                for (Player p : m.getWorld().getPlayers()) {
                    double ds = p.getLocation().distanceSquared(m.getLocation());
                    if (ds < best) {
                        best = ds;
                        near = p;
                    }
                }
                if (near != null) {
                    Location eye = near.getEyeLocation();
                    m.lookAt(eye.getX(), eye.getY(), eye.getZ(), LookAnchor.EYES);
                } else if (d.wander == 0 && Math.abs(m.getYaw() - d.yaw) > 1) {
                    m.setRotation(d.yaw, 0);
                }
            }
        }
    }

    /** Slow stroll to random nearby spots on the same floor. Returns true while walking. */
    private boolean walk(NpcDef d, Mannequin m, Location home) {
        Location target = walkTarget.get(d.id);
        if (target == null) {
            if (random.nextInt(60) != 0) return false;
            double a = random.nextDouble() * Math.PI * 2, dist = random.nextDouble() * d.wander;
            Location t = home.clone().add(Math.cos(a) * dist, 0, Math.sin(a) * dist);
            Double ground = groundY(t);
            if (ground == null || Math.abs(ground - home.getY()) > 1.1) return false;
            t.setY(ground);
            walkTarget.put(d.id, t);
            return true;
        }
        Location at = m.getLocation();
        double dx = target.getX() - at.getX(), dz = target.getZ() - at.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.2) {
            walkTarget.remove(d.id);
            return false;
        }
        double step = Math.min(dist, 0.09 * 2); // walking pace (2 ticks per update)
        Location next = at.clone().add(dx / dist * step, 0, dz / dist * step);
        Double g = groundY(next);
        if (g == null || Math.abs(g - at.getY()) > 0.6) {
            walkTarget.remove(d.id);
            return false;
        }
        next.setY(g);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        next.setYaw(yaw);
        next.setPitch(0);
        m.teleport(next);
        return true;
    }

    private static Double groundY(Location l) {
        World w = l.getWorld();
        int x = l.getBlockX(), z = l.getBlockZ();
        for (int y = (int) Math.floor(l.getY()) + 1; y >= (int) Math.floor(l.getY()) - 2; y--) {
            var below = w.getBlockAt(x, y - 1, z);
            var feet = w.getBlockAt(x, y, z);
            var head = w.getBlockAt(x, y + 1, z);
            if (!below.isPassable() && feet.isPassable() && head.isPassable() && !feet.isLiquid()) {
                double top = below.getBoundingBox().getMaxY();
                return top > y - 1 ? top : (double) y;
            }
        }
        return null;
    }

    private Entity spawn(NpcDef d, Location at) {
        Mannequin m = at.getWorld().spawn(at, Mannequin.class, e -> {
            e.setPersistent(false);
            e.setInvulnerable(true);
            e.setSilent(true);
            e.setGravity(false);
            e.setImmovable(true);
            e.setAI(false);
            e.setRemoveWhenFarAway(false);
            e.setCollidable(false);
            e.customName(SmpPlugin.mm(d.name));
            e.setCustomNameVisible(true);
            e.setDescription(d.title == null ? null : SmpPlugin.mm(d.title));
            e.getPersistentDataContainer().set(key, PersistentDataType.STRING, d.id);
            if (d.skin != null && !d.skin.isBlank()) e.setProfile(ResolvableProfile.resolvableProfile().name(d.skin).build());
            var eq = e.getEquipment();
            for (var en : d.equipment.entrySet()) {
                ItemStack it = new ItemStack(en.getValue());
                if (d.leatherColor != null && it.getItemMeta() instanceof LeatherArmorMeta lm) {
                    lm.setColor(d.leatherColor);
                    it.setItemMeta(lm);
                }
                eq.setItem(en.getKey(), it);
            }
        });
        entities.put(d.id, m.getUniqueId());
        return m;
    }

    public String idOf(Entity e) {
        return e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------ clicks

    private void clicked(Player p, Entity e) {
        String id = idOf(e);
        if (id == null) return;
        long now = System.currentTimeMillis();
        if (now - lastClick.getOrDefault(p.getUniqueId(), 0L) < 700) return;
        lastClick.put(p.getUniqueId(), now);
        NpcDef d = defs.get(id);
        if (d == null) return;
        plugin.dialogues().interact(p, d);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (idOf(e.getRightClicked()) == null) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) clicked(e.getPlayer(), e.getRightClicked());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractAt(PlayerInteractAtEntityEvent e) {
        if (idOf(e.getRightClicked()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (idOf(e.getEntity()) == null) return;
        e.setCancelled(true);
        if (e instanceof EntityDamageByEntityEvent by && by.getDamager() instanceof Player p) clicked(p, e.getEntity());
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent e) {
        // Leftovers from a crash (they are not meant to be saved): remove so they don't double up.
        for (Entity en : e.getEntities()) {
            String id = idOf(en);
            if (id != null && !en.getUniqueId().equals(entities.get(id))) en.remove();
        }
    }

    static Material mat(String s) {
        return s == null ? null : Material.matchMaterial(s);
    }
}
