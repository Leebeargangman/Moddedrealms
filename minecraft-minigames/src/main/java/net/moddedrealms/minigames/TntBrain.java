package net.moddedrealms.minigames;

import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.entity.Mannequin;
import org.bukkit.util.Vector;

/**
 * How a bot plays TNT Run, like a player does: sprint toward the biggest patch of floor ahead, curve rather than
 * zig-zag, jump small gaps, bunny-hop on open floor, and when it drops through a hole, sometimes save itself with
 * a double jump back up. A low-skill bot looks less far ahead, reacts later and sometimes heads the wrong way.
 */
final class TntBrain {

    private final TntRun game;
    private final Participant me;
    private final Bot bot;
    private final double skill;
    private float heading;
    private long nextThink;
    private long startDelay = -1;
    private int lastFloorY = Integer.MIN_VALUE;
    private boolean usedJumpThisFall;
    private long blunderUntil;
    private float blunderYaw;
    private final boolean hopper;

    TntBrain(TntRun game, Participant me) {
        this.game = game;
        this.me = me;
        this.bot = me.bot;
        this.skill = bot.skill;
        this.hopper = ThreadLocalRandom.current().nextDouble() < 0.3 + skill * 0.4;
        this.heading = bot.yaw;
    }

    void tick(long t) {
        Mannequin m = bot.body();
        if (m == null || !me.alive) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (startDelay < 0) startDelay = t + 3 + r.nextInt((int) (4 + (1 - skill) * 12));
        if (t < startDelay) {
            bot.stop();
            return;
        }
        Location l = m.getLocation();
        boolean ground = m.isOnGround();
        if (ground) {
            lastFloorY = (int) Math.floor(l.getY() - 0.05);
            usedJumpThisFall = false;
        }
        int floorY = lastFloorY == Integer.MIN_VALUE ? (int) Math.floor(l.getY() - 0.05) : lastFloorY;

        if (ground && t >= nextThink) {
            if (t < blunderUntil) heading = blunderYaw;
            else if (r.nextDouble() < (1 - skill) * 0.03) {
                // A human moment: heads off the wrong way for a bit.
                blunderYaw = (float) (heading + r.nextDouble(-120, 120));
                blunderUntil = t + 6 + r.nextInt(10);
                heading = blunderYaw;
            } else {
                heading = choose(l, floorY, r);
            }
            nextThink = t + 2 + r.nextInt((int) (2 + (1 - skill) * 5));
        }

        double rad = Math.toRadians(heading);
        double dx = -Math.sin(rad), dz = Math.cos(rad);
        if (ground) {
            boolean under1 = game.intact(l.getX() + dx * 0.9, floorY, l.getZ() + dz * 0.9);
            boolean under2 = game.intact(l.getX() + dx * 1.9, floorY, l.getZ() + dz * 1.9)
                    || game.intact(l.getX() + dx * 2.8, floorY, l.getZ() + dz * 2.8);
            if (!under1 && under2 && r.nextDouble() < 0.55 + skill * 0.45) bot.jump();
            else if (hopper && under1 && under2 && r.nextDouble() < 0.12) bot.jump();
        } else if (!usedJumpThisFall && me.doubleJumps > 0 && m.getVelocity().getY() < -0.05 && l.getY() < lastFloorY + 0.6
                && lastFloorY != Integer.MIN_VALUE) {
            // Dropping through a hole: is there floor close enough to get back onto?
            double[] back = nearestFloor(l, lastFloorY, 3.2);
            boolean bottom = game.layerAtOrBelow(l.getY() - 1) >= TntRun.LAYERS.length;
            double want = bottom ? 0.6 + skill * 0.4 : 0.15 + skill * 0.35;
            if (back != null && r.nextDouble() < want) {
                usedJumpThisFall = true;
                game.botDoubleJump(me, back[0] - l.getX(), back[1] - l.getZ());
                heading = (float) Math.toDegrees(Math.atan2(-(back[0] - l.getX()), back[1] - l.getZ()));
            } else {
                usedJumpThisFall = true; // decided: falls to the next floor
            }
        }
        bot.moveYaw(heading, r.nextDouble() < 0.04 ? Bot.WALK : Bot.SPRINT);
        bot.lookYaw(heading + (float) r.nextGaussian() * 6, 25 + (float) r.nextGaussian() * 8);
    }

    /** The heading with the most floor ahead, preferring to keep going roughly the same way. */
    private float choose(Location l, int floorY, ThreadLocalRandom r) {
        double look = 2.5 + skill * 5.5;
        float best = heading;
        double bestScore = -1e9;
        for (int i = 0; i < 24; i++) {
            float yaw = i * 15f + (float) r.nextDouble(-4, 4);
            double rad = Math.toRadians(yaw);
            double dx = -Math.sin(rad), dz = Math.cos(rad);
            double s = 0;
            int misses = 0;
            for (double d = 0.7; d <= look; d += 0.7) {
                if (game.intact(l.getX() + dx * d, floorY, l.getZ() + dz * d)) {
                    s += 1.0 / (1 + 0.18 * d);
                } else if (++misses >= 2 || d < 1.0) {
                    break;
                }
            }
            double ex = l.getX() + dx * Math.min(look, 3.5), ez = l.getZ() + dz * Math.min(look, 3.5);
            s += 0.35 * openness(ex, floorY, ez) * skill;
            double turn = Math.abs(Bot.wrap(yaw - heading));
            s -= turn / 180.0 * (0.9 + skill * 0.6);
            s += r.nextGaussian() * (1 - skill) * 0.9;
            if (s > bestScore) {
                bestScore = s;
                best = yaw;
            }
        }
        return best;
    }

    private double openness(double x, int y, double z) {
        int n = 0;
        for (int ix = -2; ix <= 2; ix++) for (int iz = -2; iz <= 2; iz++) if (game.intact(x + ix, y, z + iz)) n++;
        return n / 25.0 * 3;
    }

    private double[] nearestFloor(Location l, int y, double within) {
        double best = within * within;
        double[] at = null;
        for (double ox = -within; ox <= within; ox += 0.5) {
            for (double oz = -within; oz <= within; oz += 0.5) {
                double d = ox * ox + oz * oz;
                if (d >= best || d < 0.8) continue;
                if (game.intact(l.getX() + ox, y, l.getZ() + oz)) {
                    best = d;
                    at = new double[] {l.getX() + ox, l.getZ() + oz};
                }
            }
        }
        return at;
    }

    @SuppressWarnings("unused")
    private static Vector dir(float yaw) {
        double rad = Math.toRadians(yaw);
        return new Vector(-Math.sin(rad), 0, Math.cos(rad));
    }
}
