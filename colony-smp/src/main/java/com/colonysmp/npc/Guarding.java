package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Colony;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Text;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The watch: guards draw arms from the armoury, keep watch from their watchtowers (or walk a ring around the
 * Town Hall), and fight monsters and enemies on the colony's land. In an emergency under the militia policy,
 * ordinary adults take up arms the same way.
 */
public final class Guarding {

    private final ColonySMP plugin;
    private final NpcManager m;
    final Fighting fighting;

    Guarding(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
        this.fighting = new Fighting(plugin, m);
    }

    public Fighting fighting() {
        return fighting;
    }

    public boolean hostile(Npc n, Colony col, LivingEntity e) {
        return fighting.hostile(n, col, e);
    }

    /** An enemy in sight: fight it, or fall back if badly hurt and not yet cornered. True if there was one. */
    boolean fight(Npc n, Colony col, long now) {
        LivingEntity t = fighting.target(n, col, now, n.c.ranged != null && n.c.ammo > 0 ? 24 : 18, false);
        if (t == null) {
            n.enemy = null;
            return false;
        }
        plugin.military().callWatch(col, t.getLocation());
        n.working = true;
        if (n.c.needs.healthFraction() < 0.3 && t.getLocation().distanceSquared(n.loc()) > 3.5 * 3.5 && fallBack(n, col, t)) return true;
        fighting.engage(n, col, t, now);
        return true;
    }

    /** Badly hurt: back towards the Town Hall and the others, away from the enemy. False if there's nowhere to go. */
    private boolean fallBack(Npc n, Colony col, LivingEntity t) {
        Location core = col.coreLocation();
        if (core == null || core.getWorld() != n.body.getWorld()) return false;
        Location me = n.loc();
        // only if that's away from the enemy, and we're not already there
        if (core.distanceSquared(me) < 16 || core.distanceSquared(t.getLocation()) <= core.distanceSquared(me)) return false;
        n.mover.moveTo(core, plugin.settings().runSpeed, 2.5);
        n.doing("Falling back: badly hurt");
        return true;
    }

    /** Someone of the colony is down nearby and nobody is fighting: get them on their feet. True while at it. */
    boolean rescue(Npc n, Colony col, long now) {
        Npc down = null;
        double bd = 40 * 40;
        for (Npc o : m.of(col)) {
            if (o == n || !o.c.downed() || !o.c.status.free() || o.body.getWorld() != n.body.getWorld()) continue;
            double d = o.loc().distanceSquared(n.loc());
            if (d < bd) {
                bd = d;
                down = o;
            }
        }
        if (down == null) return false;
        if (!n.mover.near(down.loc(), 2.2)) {
            n.mover.moveTo(down.loc(), plugin.settings().runSpeed, 1.6);
            n.doing("Running to help " + down.c.name);
            return true;
        }
        n.mover.stop();
        CitizenBrain.face(n, down.loc());
        n.body.swingMainHand();
        plugin.prison().revive(down, 6);
        n.doing("Helped " + down.c.name + " up");
        for (Player p : col.onlineMembers()) Text.send(p, "<green>" + Text.esc(n.c.name) + " got " + Text.esc(down.c.name) + " back on their feet.");
        return true;
    }

    /** No enemy in sight: draw arms if needed, answer the alarm, or walk the rounds. */
    void duty(Npc n, Colony col, long now) {
        n.enemy = null;
        boolean alert = plugin.military().alert(col);
        if (!alert) n.gearChecked = false;
        if (!gear(n, col, alert)) return;
        Location alarm = plugin.military().alarmPoint(col);
        if (alarm != null && alarm.getWorld() == n.body.getWorld() && col.territory.distanceTo(alarm.getX(), alarm.getZ()) <= fighting.reach(col)) {
            n.working = true;
            if (!n.mover.near(alarm, 5)) {
                n.mover.moveTo(alarm, plugin.settings().runSpeed, 3);
                n.doing("Answering the alarm");
            } else {
                m.brain().wander(n, alarm, 5, now, "Searching for intruders");
            }
            return;
        }
        patrol(n, col, now);
    }

    /** A citizen called up as militia: arms, then defend the Town Hall. */
    boolean militia(Npc n, Colony col, long now) {
        LivingEntity t = fighting.target(n, col, now, 16, false);
        if (t != null) {
            fighting.engage(n, col, t, now);
            return true;
        }
        n.enemy = null;
        if (!gear(n, col, true)) return true;
        Location core = col.coreLocation();
        if (core == null) return false;
        m.brain().wander(n, core, 6, now, "Militia: defending the Town Hall");
        return true;
    }

    /** Draws a weapon (and in an emergency the best armour too) from the armoury. False while walking there. */
    public boolean gear(Npc n, Colony col, boolean alert) {
        boolean want = plugin.military().wantsGear(col, n.c, alert) && !n.gearChecked;
        if (!want) return true;
        if (!m.goToStorage(n, col, alert ? "Drawing arms from the armoury!" : "Fetching a weapon from the armoury")) return false;
        plugin.military().equip(col, n.c, alert);
        n.gearChecked = true;
        m.refresh(n);
        Fx.sound(n.loc(), "minecraft:item.armor.equip_iron", 0.8f, 1f);
        return true;
    }

    private void patrol(Npc n, Colony col, long now) {
        World w = n.body.getWorld();
        List<Location> points = waypoints(n, col, w);
        if (points.isEmpty()) return;
        if (n.waypoint < 0 || n.waypoint >= points.size()) {
            n.waypoint = Math.floorMod(n.c.id.hashCode(), points.size());
            n.waypointUntil = 0;
        }
        Location wp = points.get(n.waypoint);
        n.working = true;
        if (!n.mover.near(wp, 1.8)) {
            n.mover.moveTo(wp, plugin.settings().walkSpeed, 1.3);
            n.doing("Patrolling");
            n.waypointUntil = 0;
            return;
        }
        n.mover.stop();
        if (n.waypointUntil == 0) n.waypointUntil = now + 300 + ThreadLocalRandom.current().nextInt(300);
        n.doing("Keeping watch");
        if (now > n.waypointUntil) {
            n.waypoint = (n.waypoint + 1) % points.size();
            n.waypointUntil = 0;
        }
    }

    /** Their own watchtower, every watchtower, or a ring around the Town Hall. */
    private List<Location> waypoints(Npc n, Colony col, World w) {
        List<Location> out = new ArrayList<>();
        Building own = col.building(n.c.workplace);
        if (own != null && own.type == BuildingType.WATCHTOWER && own.valid && own.anchor != null) {
            out.add(own.anchor.center(w));
            return out;
        }
        for (Building b : col.valid(BuildingType.WATCHTOWER)) if (b.anchor != null) out.add(b.anchor.center(w));
        Location core = col.coreLocation();
        if (core == null) return out;
        int r = Math.max(6, Math.min(24, (int) Math.sqrt(col.territory.size()) * 6));
        int[][] dirs = {{r, 0}, {0, r}, {-r, 0}, {0, -r}};
        for (int[] d : dirs) {
            Location l = Mover.safeSpot(core.clone().add(d[0], 0, d[1]));
            if (l == null) l = Mover.safeSpot(core.clone().add(d[0] / 2.0, 0, d[1] / 2.0));
            if (l != null) out.add(l);
        }
        return out;
    }

    public static String label(LivingEntity e) {
        if (e instanceof Player p) return p.getName();
        if (e.customName() != null) return PlainTextComponentSerializer.plainText().serialize(e.customName());
        return Text.a(e.getType().name().toLowerCase().replace('_', ' '));
    }
}
