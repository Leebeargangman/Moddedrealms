package net.moddedrealms.smp.town;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Community service instead of jail: pick up glowing litter around the plaza (only you can see yours). Walking off
 * (more than service-radius + 20 blocks from the fountain) sends you back to jail with 50% extra time.
 */
public final class Service implements Listener {
    private record Litter(ItemDisplay look, Interaction hitbox) {}

    private final TownManager t;
    private final NamespacedKey key;
    private final Map<UUID, List<Litter>> litter = new HashMap<>();
    private final Random r = new Random();

    Service(TownManager t) {
        this.t = t;
        this.key = new NamespacedKey(t.plugin(), "litter");
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    private double radius() {
        return cfg().getDouble("town.service.radius", 30);
    }

    /** Offered right after an arrest for up to service.max-stars. */
    void offer(Player p, TownData d, int stars) {
        if (!cfg().getBoolean("town.service.enabled", true) || stars > cfg().getInt("town.service.max-stars", 3)) return;
        d.serviceOfferUntil = System.currentTimeMillis() + 60_000;
        d.lastArrestStars = stars;
        p.sendMessage(Component.text().append(SmpPlugin.mm("<gray>Or do your time outside: "))
                .append(SmpPlugin.mm("<green><bold>[Community service]").clickEvent(ClickEvent.runCommand("/service")))
                .append(SmpPlugin.mm(" <dark_gray>(next 60s)")).build());
    }

    public void start(Player p) {
        TownData d = t.data(p);
        if (d.inService()) {
            p.sendMessage(SmpPlugin.mm("<gray>Pick up the glowing litter around the plaza: " + d.serviceLeft + " left."));
            return;
        }
        if (!d.jailed() || System.currentTimeMillis() > d.serviceOfferUntil) {
            p.sendMessage(SmpPlugin.mm("<gray>Community service is offered for a minute after an arrest (up to "
                    + cfg().getInt("town.service.max-stars", 3) + " stars)."));
            return;
        }
        int per = cfg().getInt("town.service.seconds-per-piece", 12);
        d.serviceLeft = Math.max(5, d.jailLeft / per);
        d.serviceFallback = d.jailLeft;
        d.jailLeft = 0;
        d.cell = -1;
        t.markDirty();
        World w = t.world();
        if (w != null) {
            int y = w.getHighestBlockYAt((int) t.cx, (int) t.cz + 12) + 1;
            t.teleport(p, new Location(w, t.cx + 0.5, y, t.cz + 12.5, 180, 0));
        }
        p.sendMessage(SmpPlugin.mm("<green>Community service: pick up <white>" + d.serviceLeft + "</white> pieces of glowing litter around the plaza."
                + " <gray>Stay within " + (int) radius() + " blocks of the fountain or it's back to jail with extra time."));
        refill(p, d);
    }

    private void refill(Player p, TownData d) {
        List<Litter> mine = litter.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        mine.removeIf(l -> !l.hitbox().isValid());
        World w = t.world();
        if (w == null) return;
        for (int tries = 0; tries < 40 && mine.size() < Math.min(3, d.serviceLeft); tries++) {
            double a = r.nextDouble() * Math.PI * 2, dist = 4 + r.nextDouble() * (radius() - 4);
            int x = (int) Math.floor(t.cx + Math.cos(a) * dist), z = (int) Math.floor(t.cz + Math.sin(a) * dist);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Location ground = t.surface(w, x, z, y + 1, 1);
            if (ground == null) continue;
            mine.add(spawn(p, ground));
        }
    }

    private Litter spawn(Player owner, Location at) {
        Material[] junk = {Material.PAPER, Material.BONE, Material.ROTTEN_FLESH, Material.GLASS_BOTTLE, Material.LEATHER, Material.BOWL, Material.STICK};
        Material m = junk[r.nextInt(junk.length)];
        World w = at.getWorld();
        ItemDisplay look = w.spawn(at.clone().add(0, 0.12, 0), ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(m));
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f((float) Math.toRadians(90), 1, 0, 0), new Vector3f(0.55f), new AxisAngle4f()));
            d.setGlowing(true);
            d.setGlowColorOverride(Color.YELLOW);
            d.setBillboard(Display.Billboard.FIXED);
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.getPersistentDataContainer().set(key, PersistentDataType.STRING, owner.getUniqueId().toString());
        });
        Interaction hit = w.spawn(at, Interaction.class, i -> {
            i.setInteractionWidth(0.8f);
            i.setInteractionHeight(0.5f);
            i.setResponsive(true);
            i.setPersistent(false);
            i.setVisibleByDefault(false);
            i.getPersistentDataContainer().set(key, PersistentDataType.STRING, owner.getUniqueId().toString());
        });
        owner.showEntity(t.plugin(), look);
        owner.showEntity(t.plugin(), hit);
        return new Litter(look, hit);
    }

    private void collect(Player p, Entity hitbox) {
        TownData d = t.data(p);
        List<Litter> mine = litter.get(p.getUniqueId());
        if (mine == null || !d.inService()) return;
        Litter found = null;
        for (Litter l : mine) if (l.hitbox().equals(hitbox)) found = l;
        if (found == null) return;
        mine.remove(found);
        found.look().remove();
        found.hitbox().remove();
        p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, hitbox.getLocation().add(0, 0.3, 0), 6, 0.2, 0.2, 0.2);
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f);
        d.serviceLeft--;
        t.markDirty();
        if (d.serviceLeft <= 0) {
            finish(p, d);
            return;
        }
        refill(p, d);
    }

    private void finish(Player p, TownData d) {
        d.serviceLeft = 0;
        d.serviceFallback = 0;
        clear(p.getUniqueId());
        t.addRep(p, cfg().getInt("town.service.reputation", 5));
        p.sendMessage(SmpPlugin.mm("<green>Community service complete. The plaza looks better already. You're free to go!"));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
        t.markDirty();
    }

    void clear(UUID id) {
        List<Litter> mine = litter.remove(id);
        if (mine != null) for (Litter l : mine) {
            l.look().remove();
            l.hitbox().remove();
        }
    }

    void shutdown() {
        for (UUID id : new ArrayList<>(litter.keySet())) clear(id);
    }

    /** Every second for players doing service. */
    void second(Player p, TownData d) {
        Location l = p.getLocation();
        double dx = l.getX() - t.cx, dz = l.getZ() - t.cz;
        boolean away = !l.getWorld().getName().equals(t.worldName) || Math.sqrt(dx * dx + dz * dz) > radius() + 20;
        if (away) {
            int secs = Math.min(cfg().getInt("town.arrest.max-seconds", 300), (int) (Math.max(60, d.serviceFallback) * 1.5));
            d.serviceLeft = 0;
            d.serviceFallback = 0;
            clear(p.getUniqueId());
            p.sendMessage(SmpPlugin.mm("<red>You walked off your community service! Back to jail, with extra time."));
            if (t.jail().ready()) {
                d.jailLeft = secs;
                d.cell = t.jail().freeCell();
                t.jail().toCell(p, d);
            }
            t.markDirty();
            return;
        }
        List<Litter> mine = litter.get(p.getUniqueId());
        if (mine == null || mine.isEmpty()) refill(p, d);
        p.sendActionBar(SmpPlugin.mm("<green>Community service: <white>" + d.serviceLeft + "</white> pieces of glowing litter left around the plaza"));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Interaction i) || !i.getPersistentDataContainer().has(key, PersistentDataType.STRING)) return;
        e.setCancelled(true);
        collect(e.getPlayer(), i);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPunch(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Interaction i) || !i.getPersistentDataContainer().has(key, PersistentDataType.STRING)) return;
        e.setCancelled(true);
        if (e.getDamager() instanceof Player p) collect(p, i);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        clear(e.getPlayer().getUniqueId());
    }
}
