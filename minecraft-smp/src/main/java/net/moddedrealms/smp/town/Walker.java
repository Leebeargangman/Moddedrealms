package net.moddedrealms.smp.town;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/**
 * A town NPC that really walks: an invisible, silent skeleton with its AI goals stripped does the pathfinding
 * ("legs"), and a Mannequin wearing the clothes follows it every tick ("body"). Neither is saved with the world.
 * Skeletons rather than zombies because villagers don't panic around them.
 */
public final class Walker {
    public enum Role { OFFICER, MERCHANT, GOSSIP, TRAVELLER, THIEF, FENCE }

    public final Role role;
    public final Skeleton legs;
    public final Mannequin body;
    public String name;
    public String title;
    public double health;
    public final double maxHealth;
    public final long born = System.currentTimeMillis();
    public long lifetime;

    // AI state, used by Officers / Townsfolk
    public int mode;
    public long modeSince;
    public UUID target;
    public long nextWander;
    public long lastAttack;
    public long lastGreet;
    public Location goal;
    public Location lastPos;
    public long stuckSince;
    public Float faceYaw;
    public float facePitch;
    public boolean armed;
    public final Map<UUID, Long> talked = new HashMap<>();
    public final java.util.Set<UUID> helped = new java.util.HashSet<>();
    // thieves
    public UUID robbed;
    public double loot;
    // merchants
    public List<String> offers = List.of();

    private Walker(Role role, Skeleton legs, Mannequin body, String name, String title, double hp) {
        this.role = role;
        this.legs = legs;
        this.body = body;
        this.name = name;
        this.title = title;
        this.health = hp;
        this.maxHealth = hp;
    }

    static Walker spawn(NamespacedKey key, Location at, Role role, String name, String title, double speed, double hp,
                        Map<EquipmentSlot, ItemStack> gear) {
        World w = at.getWorld();
        Skeleton legs = w.spawn(at, Skeleton.class, s -> {
            s.setPersistent(false);
            s.setRemoveWhenFarAway(false);
            s.setSilent(true);
            s.setInvisible(true);
            s.setCollidable(false);
            s.setShouldBurnInDay(false);
            s.setCanPickupItems(false);
            s.getEquipment().clear();
            for (EquipmentSlot sl : EquipmentSlot.values()) {
                try {
                    s.getEquipment().setDropChance(sl, 0f);
                } catch (IllegalArgumentException ignored) {
                }
            }
            var ms = s.getAttribute(Attribute.MOVEMENT_SPEED);
            if (ms != null) ms.setBaseValue(speed);
            var fr = s.getAttribute(Attribute.FOLLOW_RANGE);
            if (fr != null) fr.setBaseValue(64);
            s.getPersistentDataContainer().set(key, PersistentDataType.STRING, role.name());
        });
        Bukkit.getMobGoals().removeAllGoals(legs);
        Mannequin body = w.spawn(at, Mannequin.class, m -> {
            m.setPersistent(false);
            m.setGravity(false);
            m.setAI(false);
            m.setSilent(true);
            m.setCollidable(false);
            m.setImmovable(true);
            m.setRemoveWhenFarAway(false);
            m.customName(SmpPlugin.mm(name));
            m.setCustomNameVisible(true);
            m.setDescription(title == null ? null : SmpPlugin.mm(title));
            m.getPersistentDataContainer().set(key, PersistentDataType.STRING, role.name());
            var eq = m.getEquipment();
            for (var e : gear.entrySet()) eq.setItem(e.getKey(), e.getValue());
        });
        return new Walker(role, legs, body, name, title, hp);
    }

    static ItemStack dyed(Material m, String hex) {
        ItemStack it = new ItemStack(m);
        if (hex != null && it.getItemMeta() instanceof LeatherArmorMeta lm) {
            lm.setColor(Color.fromRGB(Integer.parseInt(hex.substring(1), 16)));
            it.setItemMeta(lm);
        }
        return it;
    }

    public boolean valid() {
        return legs.isValid() && body.isValid();
    }

    public Location loc() {
        return legs.getLocation();
    }

    public World world() {
        return legs.getWorld();
    }

    /** Keep the body on the legs; swim upwards in water (the float goal was removed with the rest). */
    void sync() {
        Location l = legs.getLocation();
        if (legs.isInWater()) {
            Vector v = legs.getVelocity();
            if (v.getY() < 0.1) legs.setVelocity(v.setY(0.1));
        }
        if (faceYaw != null) {
            l.setYaw(faceYaw);
            l.setPitch(facePitch);
        } else {
            l.setPitch(0);
        }
        Location b = body.getLocation();
        if (b.getWorld() != l.getWorld() || b.distanceSquared(l) > 1e-4 || Math.abs(b.getYaw() - l.getYaw()) > 0.5f || Math.abs(b.getPitch() - l.getPitch()) > 0.5f) {
            body.teleport(l);
        }
    }

    public boolean moveTo(Location l, double speed) {
        faceYaw = null;
        goal = l;
        return legs.getPathfinder().moveTo(l, speed);
    }

    public boolean moveTo(LivingEntity e, double speed) {
        faceYaw = null;
        goal = e.getLocation();
        return legs.getPathfinder().moveTo(e, speed);
    }

    public void stop() {
        legs.getPathfinder().stopPathfinding();
        goal = null;
    }

    public boolean moving() {
        return legs.getPathfinder().hasPath();
    }

    public void lookAt(Location target) {
        Location from = body.getEyeLocation();
        Vector d = target.toVector().subtract(from.toVector());
        if (d.lengthSquared() < 1e-6) return;
        faceYaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        facePitch = (float) Math.toDegrees(-Math.atan2(d.getY(), Math.sqrt(d.getX() * d.getX() + d.getZ() * d.getZ())));
    }

    public boolean sees(Player p) {
        return p.getWorld() == world() && legs.hasLineOfSight(p);
    }

    public double dist(Location l) {
        return l.getWorld() == world() ? l.distance(loc()) : Double.MAX_VALUE;
    }

    public void say(Player p, String msg) {
        p.sendMessage(Component.text().append(SmpPlugin.mm(name)).append(Component.text(" » ", NamedTextColor.DARK_GRAY))
                .append(SmpPlugin.mm("<white>" + msg)).build());
        p.playSound(body.getLocation(), Sound.BLOCK_NOTE_BLOCK_BANJO, 0.5f, 1.4f);
    }

    /** Said out loud: everyone within the radius hears it. */
    public void shout(String msg, double radius) {
        for (Player p : world().getPlayers()) if (p.getLocation().distanceSquared(loc()) <= radius * radius) say(p, msg);
    }

    public void rename(String newName, String newTitle) {
        name = newName;
        title = newTitle;
        body.customName(SmpPlugin.mm(newName));
        body.setDescription(newTitle == null ? null : SmpPlugin.mm(newTitle));
    }

    public void hold(ItemStack it) {
        body.getEquipment().setItemInMainHand(it);
    }

    public void swing() {
        body.swingMainHand();
    }

    public void hurt() {
        body.playHurtAnimation(0);
        world().playSound(body.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.8f, 1f);
    }

    public void remove(boolean poof) {
        if (poof && body.isValid()) {
            Location l = body.getLocation().add(0, 1, 0);
            l.getWorld().spawnParticle(Particle.CLOUD, l, 12, 0.3, 0.5, 0.3, 0.02);
        }
        legs.remove();
        body.remove();
    }
}
