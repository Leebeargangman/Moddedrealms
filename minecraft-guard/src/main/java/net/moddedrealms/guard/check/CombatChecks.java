package net.moddedrealms.guard.check;

import java.util.List;
import java.util.Locale;
import net.moddedrealms.guard.GuardPlugin;
import net.moddedrealms.guard.PlayerState;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * Reach and attack angle. The attacker's eye is taken at both its current and previous position, the target at
 * every position it had during the last 0.7 seconds (what a laggy client may still be seeing), and the shortest
 * distance wins. So a flag means even the most generous reading is too far.
 */
public final class CombatChecks implements Listener {
    private final GuardPlugin plugin;

    public CombatChecks(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    // Runs even when another plugin cancelled the hit (e.g. PvP off in a region): the swing still happened.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().name().endsWith("_SPEAR")) return; // spears reach further and lunge
        Entity target = e.getEntity();
        PlayerState s = plugin.state(p);
        if (plugin.tick() < s.graceUntil) return;
        if (p.isInsideVehicle() || target.isInsideVehicle()) return;

        double w = target.getWidth() / 2.0 + 0.1;
        double h = target.getHeight() + 0.1;
        List<double[]> positions = plugin.recorder().recentPositions(target.getUniqueId(), 14);
        Location tl = target.getLocation();
        positions.add(new double[]{tl.getX(), tl.getY(), tl.getZ()});

        Vector eyeNow = p.getEyeLocation().toVector();
        Vector eyePrev = s.prevLocation != null && s.prevLocation.getWorld() == p.getWorld()
                ? s.prevLocation.toVector().add(new Vector(0, p.getEyeHeight(), 0)) : eyeNow;
        // Sneaking changes eye height by ~0.3: also try a standing eye.
        Vector eyeStand = p.getLocation().toVector().add(new Vector(0, 1.62, 0));

        double best = Double.MAX_VALUE;
        double bestAngle = Double.MAX_VALUE;
        Vector look = p.getLocation().getDirection();
        for (double[] pos : positions) {
            for (Vector eye : new Vector[]{eyeNow, eyePrev, eyeStand}) {
                double d = MovementChecks.distanceToBox(eye, pos[0] - w, pos[1] - 0.1, pos[2] - w, pos[0] + w, pos[1] + h, pos[2] + w);
                best = Math.min(best, d);
            }
            Vector toTarget = new Vector(pos[0], pos[1] + h / 2, pos[2]).subtract(eyeNow);
            if (toTarget.lengthSquared() > 1e-6) bestAngle = Math.min(bestAngle, Math.toDegrees(look.angle(toTarget)));
        }

        double range = 3.0;
        AttributeInstance ri = p.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        if (ri != null) range = ri.getValue();

        Check reach = Check.REACH;
        if (reach.enabled) {
            double certainAt = range + reach.num("certain-extra", 1.1);
            double suspectAt = range + reach.num("suspect-extra", 0.6);
            if (best > certainAt) {
                plugin.violations().flag(p, reach, true, String.format(Locale.ROOT, "hit %s from %.2f blocks (range %.1f)", name(target), best, range));
            } else if (best > suspectAt) {
                plugin.violations().flag(p, reach, false, String.format(Locale.ROOT, "hit %s from %.2f blocks (range %.1f)", name(target), best, range));
            }
        }

        Check aura = Check.KILLAURA;
        if (aura.enabled && best > 1.2 && bestAngle != Double.MAX_VALUE) {
            double max = aura.num("max-angle", 115);
            if (bestAngle > max) {
                plugin.violations().flag(p, aura, bestAngle > 160, String.format(Locale.ROOT, "hit %s at %.0f degrees off their view", name(target), bestAngle));
            }
        }
    }

    private static String name(Entity e) {
        if (e instanceof Player p) return p.getName();
        return e instanceof LivingEntity ? e.getType().name().toLowerCase(Locale.ROOT) : e.getType().name().toLowerCase(Locale.ROOT);
    }
}
