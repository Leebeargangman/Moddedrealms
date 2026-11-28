package net.moddedrealms.minigames;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MainHand;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.util.Vector;

/**
 * A bot's body: a mannequin with a player skin and a made-up player name. It moves like a player does: walking
 * 4.3 and sprinting 5.6 blocks a second, jumping 1.25 blocks, keeping its speed in the air with only a little
 * steering, and turning its head and body separately, never in instant snaps.
 */
final class Bot {

    static final double WALK = 0.216;
    static final double SPRINT = 0.28;
    static final double JUMP = 0.42;

    private static String tag = "[BOT]";

    final String name;
    final UUID uuid = UUID.randomUUID();
    final double skill; // 0 clumsy .. 1 very good
    private final Key skin;
    private final PlayerTextures.SkinModel model;
    private Mannequin body;

    // head and body turning
    float yaw;
    float pitch;
    private float bodyYaw;
    private float maxTurn; // degrees a tick

    // movement this tick
    private double wantX, wantZ, wantSpeed;
    private boolean wantJump;
    private int jumpCooldown;
    private double setX, setZ; // the horizontal speed it was given last tick
    private boolean sneaking;
    double movedLastTick;

    Bot(String name, double skill) {
        this.name = name;
        this.skill = skill;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String[] skins = {"steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"};
        boolean slim = r.nextBoolean();
        this.model = slim ? PlayerTextures.SkinModel.SLIM : PlayerTextures.SkinModel.CLASSIC;
        this.skin = Key.key("minecraft", "entity/player/" + (slim ? "slim/" : "wide/") + skins[r.nextInt(skins.length)]);
        this.maxTurn = (float) (18 + skill * 22);
    }

    static void setTag(String t) {
        tag = t == null || t.isBlank() ? "[BOT]" : t;
    }

    static String tag() {
        return tag;
    }

    Mannequin body() {
        return body != null && body.isValid() ? body : null;
    }

    boolean spawned() {
        return body() != null;
    }

    void spawn(MinigamesPlugin plugin, Location at, boolean nameVisible) {
        remove();
        yaw = at.getYaw();
        bodyYaw = yaw;
        body = at.getWorld().spawn(at, Mannequin.class, m -> {
            m.setPersistent(false);
            m.getPersistentDataContainer().set(plugin.botKey, PersistentDataType.STRING, name);
            m.customName(Component.text(name));
            m.setCustomNameVisible(nameVisible);
            m.setDescription(null);
            m.setRemoveWhenFarAway(false);
            m.setCanPickupItems(false);
            m.setInvulnerable(false);
            m.setImmovable(false);
            m.setSilent(false);
            m.setMainHand(ThreadLocalRandom.current().nextInt(10) == 0 ? MainHand.LEFT : MainHand.RIGHT);
            m.setProfile(profile());
        });
    }

    /** Built-in player skins (Steve, Alex, Ari...), slim or wide, so no account's skin is borrowed. */
    ResolvableProfile profile() {
        return ResolvableProfile.resolvableProfile()
                .name(name)
                .uuid(uuid)
                .skinPatch(p -> p.body(skin).model(model))
                .build();
    }

    void remove() {
        if (body != null && body.isValid()) body.remove();
        body = null;
    }

    void setNameVisible(boolean v) {
        Mannequin m = body();
        if (m != null) m.setCustomNameVisible(v);
    }

    void hold(Material item) {
        Mannequin m = body();
        if (m == null) return;
        ItemStack now = m.getEquipment().getItem(EquipmentSlot.HAND);
        if (item == null || item == Material.AIR) {
            if (now.getType() != Material.AIR) m.getEquipment().setItem(EquipmentSlot.HAND, null);
        } else if (now.getType() != item) {
            m.getEquipment().setItem(EquipmentSlot.HAND, new ItemStack(item));
        }
    }

    Material holding() {
        Mannequin m = body();
        return m == null ? Material.AIR : m.getEquipment().getItem(EquipmentSlot.HAND).getType();
    }

    void swing() {
        Mannequin m = body();
        if (m != null) m.swingMainHand();
    }

    void sneak(boolean s) {
        Mannequin m = body();
        if (m == null || s == sneaking) return;
        sneaking = s;
        m.setSneaking(s);
    }

    // ------------------------------------------------------------ movement, applied once a tick by tickBody()

    /** Head toward (x, z) at this speed this tick. */
    void moveToward(double x, double z, double speed) {
        Mannequin m = body();
        if (m == null) return;
        Location l = m.getLocation();
        double dx = x - l.getX(), dz = z - l.getZ();
        double d = Math.hypot(dx, dz);
        if (d < 0.05) {
            stop();
            return;
        }
        wantX = dx / d;
        wantZ = dz / d;
        wantSpeed = Math.min(speed, d);
    }

    /** Head in a direction (yaw in Minecraft degrees: 0 = south, 90 = west). */
    void moveYaw(float dirYaw, double speed) {
        double rad = Math.toRadians(dirYaw);
        wantX = -Math.sin(rad);
        wantZ = Math.cos(rad);
        wantSpeed = speed;
    }

    void stop() {
        wantSpeed = 0;
    }

    /** A push (a double jump): sets its speed right now; the air steering carries on from there. */
    void impulse(double vx, double vy, double vz) {
        Mannequin m = body();
        if (m == null) return;
        m.setVelocity(new Vector(vx, vy, vz));
        setX = vx;
        setZ = vz;
    }

    void jump() {
        wantJump = true;
    }

    /** Turn the head toward a point (the body follows when it moves). */
    void lookAt(Location at) {
        Mannequin m = body();
        if (m == null || at == null) return;
        Location e = m.getEyeLocation();
        double dx = at.getX() - e.getX(), dy = at.getY() - e.getY(), dz = at.getZ() - e.getZ();
        lookYaw((float) Math.toDegrees(Math.atan2(-dx, dz)), (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
    }

    private float wantYaw = Float.NaN, wantPitch;

    void lookYaw(float y, float p) {
        wantYaw = y;
        wantPitch = Math.max(-80, Math.min(80, p));
    }

    boolean facing(Location at, double withinDegrees) {
        Mannequin m = body();
        if (m == null) return false;
        Location e = m.getEyeLocation();
        float want = (float) Math.toDegrees(Math.atan2(-(at.getX() - e.getX()), at.getZ() - e.getZ()));
        return Math.abs(wrap(want - yaw)) <= withinDegrees;
    }

    /** Apply this tick's movement and turning to the mannequin. */
    void tickBody() {
        Mannequin m = body();
        if (m == null) return;
        Location l = m.getLocation();
        Vector v = m.getVelocity();
        boolean ground = m.isOnGround();
        double vx, vz, vy = v.getY();
        if (wantSpeed > 0) {
            double sp = sneaking ? Math.min(wantSpeed, 0.065) : wantSpeed;
            if (ground) {
                // On the ground a player gets up to speed within a couple of ticks.
                vx = lerp(setX, wantX * sp, 0.6);
                vz = lerp(setZ, wantZ * sp, 0.6);
            } else {
                // In the air: momentum, with only a little steering.
                vx = v.getX() + wantX * 0.026;
                vz = v.getZ() + wantZ * 0.026;
                double h = Math.hypot(vx, vz), cap = Math.max(0.36, Math.hypot(v.getX(), v.getZ()));
                if (h > cap) {
                    vx *= cap / h;
                    vz *= cap / h;
                }
            }
        } else if (ground) {
            vx = setX * 0.3;
            vz = setZ * 0.3;
        } else {
            vx = v.getX();
            vz = v.getZ();
        }
        if (jumpCooldown > 0) jumpCooldown--;
        if (wantJump && ground && jumpCooldown == 0 && !sneaking) {
            vy = JUMP;
            jumpCooldown = 6;
            if (wantSpeed >= SPRINT - 0.01) { // sprint-jump boost, like a player's
                vx += wantX * 0.08;
                vz += wantZ * 0.08;
            }
        }
        wantJump = false;
        m.setVelocity(new Vector(vx, vy, vz));
        setX = vx;
        setZ = vz;
        movedLastTick = Math.hypot(vx, vz);

        // Turning: the head turns toward what it looks at (or where it goes), the body follows the movement.
        float target = !Float.isNaN(wantYaw) ? wantYaw : wantSpeed > 0 ? (float) Math.toDegrees(Math.atan2(-wantX, wantZ)) : yaw;
        float d = wrap(target - yaw);
        float step = Math.max(-maxTurn, Math.min(maxTurn, d * 0.55f));
        yaw = wrap(yaw + step);
        pitch += (wantPitch - pitch) * 0.4f;
        if (wantSpeed > 0) bodyYaw = wrap(bodyYaw + wrap((float) Math.toDegrees(Math.atan2(-wantX, wantZ)) - bodyYaw) * 0.5f);
        else if (Math.abs(wrap(yaw - bodyYaw)) > 50) bodyYaw = wrap(bodyYaw + wrap(yaw - bodyYaw) * 0.2f);
        m.setRotation(yaw, pitch);
        m.setBodyYaw(bodyYaw);
        wantYaw = Float.NaN;
        wantPitch = 0;
    }

    static float wrap(float a) {
        a %= 360;
        if (a >= 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    // ------------------------------------------------------------ names

    private static final String[] A = {"Ash", "Moss", "Pixel", "Frost", "Nova", "Blaze", "Echo", "Fern", "Jade", "Kiwi", "Luna",
            "Milo", "Nico", "Oak", "Pip", "Quinn", "Rex", "Sky", "Toby", "Vex", "Wren", "Zed", "Bean", "Coco", "Dash", "Ember",
            "Finn", "Gus", "Hazel", "Ivy", "Juno", "Koda", "Leo", "Mochi", "Nash", "Olive", "Pebble", "Rory", "Sage", "Taco",
            "Ziggy", "Bolt", "Cloud", "Dino", "Fox", "Ghost", "Honey", "Iggy", "Jelly", "Lemon", "Maple", "Noodle", "Otter",
            "Panda", "Rusty", "Sunny", "Tiger", "Waffle", "Yeti", "Comet", "Shadow", "Storm", "Cosmic", "Lava", "Crafty"};
    private static final String[] B = {"Gamer", "Builder", "Miner", "Craft", "Plays", "YT", "MC", "Wolf", "Cat", "Bear", "Dragon",
            "King", "Queen", "Boy", "Girl", "Pro", "Ninja", "Knight", "Hero", "Bee", "Duck", "Blocks", "Runner", "Digs", "Fan",
            "Squad", "Legend", "Toast", "Cookie", "Potato"};

    /** A made-up name that looks like a player's (Ash427, xMossx, pebble_digs), not used by anyone here. */
    static String randomName(Collection<String> taken) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Set<String> lower = new HashSet<>();
        for (String t : taken) lower.add(t.toLowerCase(Locale.ROOT));
        for (Player p : Bukkit.getOnlinePlayers()) lower.add(p.getName().toLowerCase(Locale.ROOT));
        for (int tries = 0; tries < 300; tries++) {
            String a = A[r.nextInt(A.length)];
            String n = switch (r.nextInt(8)) {
                case 0 -> a + r.nextInt(10, 1000);
                case 1 -> a + B[r.nextInt(B.length)];
                case 2 -> a.toLowerCase(Locale.ROOT) + "_" + B[r.nextInt(B.length)].toLowerCase(Locale.ROOT);
                case 3 -> "x" + a + "x";
                case 4 -> a + "_" + A[r.nextInt(A.length)];
                case 5 -> "The" + a + B[r.nextInt(B.length)];
                case 6 -> a.toLowerCase(Locale.ROOT) + r.nextInt(1, 100);
                default -> B[r.nextInt(B.length)] + a + r.nextInt(1, 100);
            };
            if (n.length() < 3 || n.length() > 16 || lower.contains(n.toLowerCase(Locale.ROOT))) continue;
            return n;
        }
        return "Player" + r.nextInt(10_000, 100_000);
    }
}
