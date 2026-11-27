package com.colonysmp.model;

import com.colonysmp.util.BlockPos;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** A military unit: soldiers who move and fight together under one order and formation. */
public final class Squad {

    public enum Order {
        DEFEND("Defend the settlement", "Patrol the territory and engage anything hostile in it."),
        HOLD("Hold position", "Stay at the rally point in formation and fight only what comes in reach."),
        FOLLOW("Follow", "Follow the commanding player in formation."),
        PATROL("Patrol", "Walk the patrol route, engaging enemies on the way."),
        CHARGE("Charge", "Attack the nearest enemies with everything."),
        SHIELD_WALL("Shield wall", "Hold, shields raised wide; archers shoot from behind."),
        SPEAR_WALL("Spear wall", "Hold with spears and pikes braced against cavalry."),
        VOLLEY("Volley fire", "Ranged troops fire together at the target or the nearest enemies."),
        FOCUS("Focus target", "Everyone attacks the marked target."),
        FLANK("Flank", "Circle around the target and hit it from the side."),
        RETREAT("Retreat", "Fall back to the rally point without fighting."),
        PROTECT("Protect", "Stay close to the marked player or citizen and defend them."),
        HOLD_FIRE("Hold fire", "Hold position; ranged troops don't shoot.");

        public final String display, description;

        Order(String display, String description) {
            this.display = display;
            this.description = description;
        }
    }

    public enum Formation {
        LINE("Line"), COLUMN("Column"), WEDGE("Wedge"), SQUARE("Square"), LOOSE("Loose");

        public final String display;

        Formation(String display) {
            this.display = display;
        }
    }

    public final int id;
    public String name;
    public Order order = Order.DEFEND;
    public Formation formation = Formation.LINE;
    /** Rally point and facing. */
    public String world;
    public BlockPos rally;
    public float yaw;
    public final List<BlockPos> patrol = new ArrayList<>();
    /** The player they follow or protect, or the citizen they protect. */
    public UUID follow;
    // runtime only
    public transient UUID focus;
    public transient double morale = 60;
    public transient int startStrength;
    public transient boolean commanderLost;

    public Squad(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public void save(ConfigurationSection s) {
        s.set("name", name);
        s.set("order", order.name());
        s.set("formation", formation.name());
        s.set("world", world);
        s.set("rally", rally == null ? null : rally.toString());
        s.set("yaw", yaw);
        List<String> p = new ArrayList<>();
        for (BlockPos b : patrol) p.add(b.toString());
        s.set("patrol", p);
        s.set("follow", follow == null ? null : follow.toString());
    }

    public static Squad load(int id, ConfigurationSection s) {
        Squad q = new Squad(id, s.getString("name", "Squad " + id));
        q.order = Policies.parse(Order.class, s.getString("order"), Order.DEFEND);
        q.formation = Policies.parse(Formation.class, s.getString("formation"), Formation.LINE);
        q.world = s.getString("world");
        q.rally = BlockPos.parse(s.getString("rally"));
        q.yaw = (float) s.getDouble("yaw");
        for (String p : s.getStringList("patrol")) {
            BlockPos b = BlockPos.parse(p);
            if (b != null) q.patrol.add(b);
        }
        String f = s.getString("follow");
        try {
            q.follow = f == null ? null : UUID.fromString(f);
        } catch (IllegalArgumentException e) {
            q.follow = null;
        }
        return q;
    }

    public static Order order(String s) {
        if (s == null) return null;
        String u = s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (Order o : Order.values()) if (o.name().equals(u) || o.display.equalsIgnoreCase(s.trim())) return o;
        return switch (u) {
            case "HOLD_POSITION", "STAY" -> Order.HOLD;
            case "ATTACK" -> Order.CHARGE;
            case "SHIELDWALL" -> Order.SHIELD_WALL;
            case "SPEARWALL", "PIKE_WALL" -> Order.SPEAR_WALL;
            case "FIRE", "SHOOT" -> Order.VOLLEY;
            case "FALL_BACK" -> Order.RETREAT;
            case "GUARD" -> Order.PROTECT;
            default -> null;
        };
    }
}
