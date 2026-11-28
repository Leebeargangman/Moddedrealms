package net.moddedrealms.minigames;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.util.Vector;

/**
 * How a bot plays Murder Mystery. It only knows what it could have seen or heard: who it saw holding the knife,
 * who it saw kill, where it heard a scream, what others shouted in chat. Innocents wander the rooms, grab gold,
 * stick together and run from danger; bow holders hunt the murderer once they know who it is (and can miss);
 * a murderer bot blends in, waits for someone to be alone, closes in and strikes, then walks off.
 */
final class MurderBrain {

    enum Goal { IDLE, ROAM, GOLD, BOW, FLEE, HUNT, AIM, STALK, AWAY, INVESTIGATE }

    final Murder game;
    final Participant me;
    private final Bot bot;
    private final double skill;
    private final double brave;
    private final ThreadLocalRandom r = ThreadLocalRandom.current();

    Goal goal = Goal.IDLE;
    private long goalUntil;
    private List<int[]> path;
    private int pathIdx;
    private int[] pathGoal;
    private long nextThink;
    private double speed = Bot.WALK;

    // what it knows
    Participant known;          // the murderer, for sure (saw the knife, saw a kill)
    private Location knownAt;
    private long knownSeenAt;
    private Participant suspect; // someone shouted about them, or was near a body
    private final Map<Participant, Double> suspicion = new HashMap<>();
    private Location heardAt;
    private long heardAtTick;
    private Location threatFrom;

    // aiming / attacking
    private Participant target;
    private long aimDoneAt;
    private long retargetAt;
    private long knifeHideAt;
    private long lastCallout;
    private long calmUntil;

    // idle look-around
    private float idleYaw;
    private long idleTurnAt;
    private Location lastPos;
    private int stuckTicks;

    MurderBrain(Murder game, Participant me) {
        this.game = game;
        this.me = me;
        this.bot = me.bot;
        this.skill = bot.skill;
        this.brave = 0.2 + r.nextDouble() * 0.8;
    }

    String status() {
        Location l = me.loc();
        return me.name + " " + me.role + " " + goal + (target != null ? " ->" + target.name : "") + (known != null ? " knows:" + known.name : "")
                + (suspect != null ? " suspects:" + suspect.name : "") + " gold " + me.gold + (me.bow ? " bow(" + me.arrows + ")" : "")
                + (l == null ? "" : String.format(" @%.0f,%.0f", l.getX() - game.origin.getX(), l.getZ() - game.origin.getZ()));
    }

    void start() {
        goal = Goal.IDLE;
        goalUntil = game.t + 10 + r.nextInt(40);
        nextThink = game.t + 5 + r.nextInt(15);
        idleYaw = bot.yaw;
        if (me.role == Participant.Role.DETECTIVE) bot.hold(Material.BOW);
    }

    // ------------------------------------------------------------ the game tells it what happened

    /** Someone died at `at`. If it saw who did it, it knows. */
    void event(Location at, Participant killer, boolean byMurderer) {
        Mannequin m = bot.body();
        if (m == null) return;
        Location l = m.getLocation();
        double d = l.distance(at);
        if (d < 32) {
            heardAt = at.clone();
            heardAtTick = game.t;
        }
        if (killer == null || killer == me || !byMurderer || me.role == Participant.Role.MURDERER) return;
        LivingEntity ke = killer.entity();
        if (ke == null) return;
        double kd = l.distance(ke.getLocation());
        boolean saw = kd < 22 && m.hasLineOfSight(ke) && (inView(ke.getLocation(), 160) || kd < 4);
        if (saw) {
            learn(killer, true);
            threatFrom = ke.getLocation();
            if (!me.bow || me.arrows == 0) setGoal(Goal.FLEE, 60 + r.nextInt(60));
        } else if (d < 10) {
            threatFrom = at.clone();
            if (!me.bow) setGoal(Goal.FLEE, 30 + r.nextInt(40));
        }
    }

    /** It (the murderer) just killed someone: hide the knife, walk off, lie low for a while. */
    void afterKill() {
        long t = game.t;
        knifeHideAt = t + 10 + r.nextInt(15);
        target = null;
        Location l = me.loc();
        if (l != null) {
            int[] far = farCell(l, l);
            setGoal(Goal.AWAY, 50 + r.nextInt(60));
            goToCell(far[0], far[1], Bot.SPRINT);
        }
        calmUntil = t + 20L * (6 + r.nextInt(9)) + (long) ((1 - skill) * 100);
    }

    /** Another bot shouted a name in chat. */
    void heardCallout(Participant who) {
        if (who == me || known != null) return;
        suspect = who;
        suspicion.merge(who, 0.6, Double::sum);
    }

    private void learn(Participant who, boolean sawKill) {
        boolean isNew = known != who;
        known = who;
        LivingEntity e = who.entity();
        if (e != null) knownAt = e.getLocation();
        knownSeenAt = game.t;
        if (isNew && game.t - lastCallout > 100 && Match.chance(game.plugin.getConfig().getDouble("bots.chat-chance", 0.35) * 1.8)) {
            lastCallout = game.t;
            String n = shortName(who.name);
            String line = sawKill
                    ? Match.pick(n + " is the murderer!!", "ITS " + n.toUpperCase(Locale.ROOT), n + " killed someone", "murderer is " + n, n + " is murder", "RUN its " + n)
                    : Match.pick(n + " has the knife", n + " is murderer", "its " + n + "!!", n + " has a knife", "watch out " + n + " is murderer");
            game.botSay(me, line, 12 + r.nextInt(40), true);
            // Others in earshot of the chat take it as a hint.
            for (MurderBrain b : game.brains.values()) if (b != this) b.heardCallout(who);
        }
    }

    private String shortName(String n) {
        String s = n.replaceAll("[^A-Za-z]", "");
        if (s.length() < 3) s = n;
        if (r.nextBoolean() && s.length() > 5) s = s.substring(0, 4 + r.nextInt(2));
        return r.nextInt(3) == 0 ? s : s.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------ every tick

    void tick(long t) {
        Mannequin m = bot.body();
        if (m == null || !me.alive) return;
        Location l = m.getLocation();
        if (t >= nextThink) {
            think(t, m, l);
            nextThink = t + 3 + r.nextInt((int) (3 + (1 - skill) * 6));
        }
        act(t, m, l);
        // Unstick: walking but not getting anywhere.
        if (path != null && lastPos != null && l.distanceSquared(lastPos) < 0.0004 && speedWanted()) {
            if (++stuckTicks > 20) {
                bot.jump();
                path = null;
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
        }
        lastPos = l;
        if (knifeHideAt > 0 && t >= knifeHideAt) {
            knifeHideAt = 0;
            if (goal != Goal.STALK) bot.hold(Material.AIR);
        }
    }

    private boolean speedWanted() {
        return goal != Goal.IDLE && goal != Goal.AIM;
    }

    private void think(long t, Mannequin m, Location l) {
        List<Participant> seen = new ArrayList<>();
        for (Participant p : game.alive()) {
            if (p == me) continue;
            LivingEntity e = p.entity();
            if (e == null) continue;
            double d = e.getLocation().distance(l);
            if (d > 26) continue;
            if ((inView(e.getLocation(), 150) || d < 3) && m.hasLineOfSight(e)) seen.add(p);
        }
        // Spotting a knife in someone's hand.
        if (me.role != Participant.Role.MURDERER) {
            for (Participant p : seen) {
                if (p == known) {
                    knownAt = p.loc();
                    knownSeenAt = t;
                    continue;
                }
                double d = p.loc().distance(l);
                if (game.holdingKnife(p) && d < 14 && r.nextDouble() < 0.3 + skill * 0.55) learn(p, false);
            }
            if (known != null && !known.alive) known = null;
        }
        switch (me.role) {
            case MURDERER -> thinkMurderer(t, m, l, seen);
            default -> {
                if (me.bow && me.arrows > 0) thinkShooter(t, m, l, seen);
                else thinkInnocent(t, m, l, seen);
            }
        }
    }

    // ------------------------------------------------------------ innocents

    private void thinkInnocent(long t, Mannequin m, Location l, List<Participant> seen) {
        Participant danger = null;
        for (Participant p : seen) {
            double d = p.loc().distance(l);
            if ((p == known && d < 16) || (game.holdingKnife(p) && d < 10) || (p == suspect && d < 5)) {
                danger = p;
                break;
            }
        }
        if (danger == null && game.knifeOut && r.nextDouble() < 0.25 + skill * 0.5) {
            for (Participant p : seen) {
                LivingEntity e = p.entity();
                if (e == null || p == me) continue;
                Vector toMe = l.toVector().subtract(e.getLocation().toVector()).setY(0);
                double d = toMe.length();
                Vector v = game.motion(p).setY(0);
                if (d < 6 && d > 0.1 && v.length() > 0.18 && v.normalize().dot(toMe.normalize()) > 0.85) {
                    danger = p; // someone running straight at it: back off
                    break;
                }
            }
        }
        if (danger != null) {
            threatFrom = danger.loc();
            if (goal != Goal.FLEE || t > goalUntil - 20) setGoal(Goal.FLEE, 40 + r.nextInt(40));
            return;
        }
        if (goal == Goal.FLEE && t < goalUntil) return;
        // The detective's bow is lying somewhere: the brave go for it.
        Item bow = game.bowDrop;
        if (bow != null && bow.isValid() && !me.bow && brave > 0.45 && bow.getLocation().distance(l) < 30) {
            if (goal != Goal.BOW) {
                setGoal(Goal.BOW, 200);
                goTo(bow.getLocation(), Bot.SPRINT);
            }
            return;
        }
        if (goal == Goal.GOLD && t < goalUntil && path != null) return;
        Item g = nearestGold(l, m, 11);
        if (g != null) {
            setGoal(Goal.GOLD, 120);
            goTo(g.getLocation(), r.nextDouble() < 0.6 ? Bot.SPRINT : Bot.WALK);
            return;
        }
        wander(t, l, seen);
    }

    private void wander(long t, Location l, List<Participant> seen) {
        if ((goal == Goal.ROAM || goal == Goal.INVESTIGATE) && path != null && t < goalUntil) return;
        if (goal == Goal.IDLE && t < goalUntil) return;
        double roll = r.nextDouble();
        if (heardAt != null && t - heardAtTick < 300 && me.bow && roll < 0.7) {
            setGoal(Goal.INVESTIGATE, 200);
            goTo(heardAt, Bot.SPRINT);
            heardAt = null;
        } else if (roll < 0.22) {
            setGoal(Goal.IDLE, 20 + r.nextInt(70));
            path = null;
            idleYaw = bot.yaw + (float) r.nextDouble(-90, 90);
        } else if (roll < 0.55 && !seen.isEmpty()) {
            // Tag along with someone (people stick together).
            Participant p = seen.get(r.nextInt(seen.size()));
            if (p != known && p != suspect) {
                setGoal(Goal.ROAM, 100 + r.nextInt(100));
                Location to = p.loc().clone().add(r.nextDouble(-2, 2), 0, r.nextDouble(-2, 2));
                goTo(to, Bot.WALK);
            }
        } else {
            int[] c = Mansion.ROOMS[r.nextInt(Mansion.ROOMS.length)];
            if (known != null && knownAt != null && Mansion.center(game.origin, c[0], c[1]).distance(knownAt) < 10) c = Mansion.ROOMS[r.nextInt(Mansion.ROOMS.length)];
            setGoal(Goal.ROAM, 200 + r.nextInt(200));
            goToCell(c[0] + r.nextInt(-2, 3), c[1] + r.nextInt(-2, 3), r.nextDouble() < 0.5 ? Bot.SPRINT : Bot.WALK);
        }
    }

    // ------------------------------------------------------------ bow holders

    private void thinkShooter(long t, Mannequin m, Location l, List<Participant> seen) {
        if (bot.holding() != Material.BOW) bot.hold(Material.BOW);
        Participant aimAt = null;
        if (known != null && known.alive && seen.contains(known) && known.loc().distance(l) < 30) aimAt = known;
        // A hunch: one person right by a fresh body, nobody else around.
        if (aimAt == null && suspect != null && seen.contains(suspect) && suspicion.getOrDefault(suspect, 0.0) >= 1.0
                && game.holdingKnife(suspect)) aimAt = suspect;
        if (aimAt != null) {
            if (goal != Goal.AIM || target != aimAt) {
                target = aimAt;
                setGoal(Goal.AIM, 80);
                path = null;
                aimDoneAt = t + 6 + (long) ((1 - skill) * 16) + r.nextInt(8);
            }
            return;
        }
        if (goal == Goal.AIM) setGoal(Goal.IDLE, 10);
        if (known != null && known.alive && knownAt != null && t - knownSeenAt < 400 && (me.role == Participant.Role.DETECTIVE || brave > 0.5)) {
            if (goal != Goal.HUNT || path == null) {
                setGoal(Goal.HUNT, 120);
                goTo(knownAt, Bot.SPRINT);
            }
            return;
        }
        // Near a fresh body: who's standing around?
        if (game.lastKillLoc != null && t - game.lastKillAt < 120 && game.lastKillLoc.distance(l) < 8 && known == null) {
            List<Participant> near = new ArrayList<>();
            for (Participant p : seen) if (p.loc().distance(game.lastKillLoc) < 9) near.add(p);
            for (Participant p : near) suspicion.merge(p, 1.0 / near.size(), Double::sum);
            if (near.size() == 1) suspect = near.get(0);
        }
        Item g = nearestGold(l, m, 9);
        if (g != null && goal != Goal.INVESTIGATE) {
            setGoal(Goal.GOLD, 100);
            goTo(g.getLocation(), Bot.WALK);
            return;
        }
        wander(t, l, seen);
    }

    // ------------------------------------------------------------ the murderer

    private void thinkMurderer(long t, Mannequin m, Location l, List<Participant> seen) {
        if (!game.knifeOut) {
            thinkInnocent(t, m, l, seen);
            return;
        }
        // Someone with a bow who knows (or a real player with a bow looking at it while the knife is out): get away.
        for (Participant p : seen) {
            if (!p.bow || p.arrows <= 0 || p == target) continue;
            double d = p.loc().distance(l);
            MurderBrain theirs = game.brains.get(p);
            boolean knows = theirs != null ? theirs.known == me : bot.holding() == Material.IRON_SWORD;
            if (knows && d < 22) {
                bot.hold(Material.AIR);
                threatFrom = p.loc();
                setGoal(Goal.FLEE, 40 + r.nextInt(30));
                return;
            }
        }
        if (goal == Goal.FLEE && t < goalUntil) return;
        if (goal == Goal.AWAY && t < goalUntil) return;
        if (t < calmUntil) {
            // Blend in for a while after a kill: wander and pick up gold like everyone else.
            bot.hold(Material.AIR);
            Item g = nearestGold(l, m, 8);
            if (g != null && goal != Goal.GOLD) {
                setGoal(Goal.GOLD, 80);
                goTo(g.getLocation(), Bot.WALK);
            } else if (goal != Goal.GOLD || path == null) wander(t, l, seen);
            return;
        }
        long sinceKill = game.lastKillAt < 0 ? t - game.startedAt : t - game.lastKillAt;
        double aggression = Math.min(1, sinceKill / (20.0 * 45)) + (game.secondsLeft(game.roundEnd) < 75 ? 1 : 0);
        aggression = Math.min(1, aggression * (0.6 + skill * 0.6));
        if (target == null || !target.alive || t >= retargetAt) {
            target = pickVictim(l, aggression);
            retargetAt = t + 40 + r.nextInt(40);
        }
        if (target == null) {
            wander(t, l, seen);
            return;
        }
        Location tl = target.loc();
        if (tl == null) return;
        double d = tl.distance(l);
        int witnesses = witnesses(target, l);
        boolean ok = witnesses == 0 || aggression > 0.75 || (witnesses == 1 && aggression > 0.45);
        if (!ok && d > 6) {
            // Too many eyes: shadow them from a distance.
            setGoal(Goal.STALK, 30);
            if (d > 9) goTo(tl, Bot.WALK);
            else {
                path = null;
                bot.stop();
            }
            bot.hold(Material.AIR);
            return;
        }
        setGoal(Goal.STALK, 30);
        goTo(tl, d < 7 ? Bot.SPRINT : Bot.WALK);
        boolean show = d < (skill > 0.5 ? 4.5 : 7);
        bot.hold(show ? Material.IRON_SWORD : Material.AIR);
        // Throw the knife at someone running away.
        if (d > 5 && d < 15 && t >= me.throwAt && seen.contains(target) && (witnesses == 0 || aggression > 0.6)
                && r.nextDouble() < 0.05 + aggression * 0.15) {
            LivingEntity te = target.entity();
            if (te != null) {
                bot.hold(Material.IRON_SWORD);
                Vector aim = lead(target, te, d / 1.6).add(new Vector(0, 1.1, 0)).subtract(m.getEyeLocation().toVector());
                aim = wobble(aim, 2.5 + (1 - skill) * 6);
                bot.lookAt(te.getLocation().add(0, 1.2, 0));
                game.throwKnife(me, aim);
                knifeHideAt = t + 20;
            }
        }
    }

    private Participant pickVictim(Location l, double aggression) {
        Participant best = null;
        double bestCost = 1e9;
        int[] me0 = Mansion.cell(game.origin, l);
        for (Participant p : game.alive()) {
            if (p == me) continue;
            Location pl = p.loc();
            if (pl == null) continue;
            int[] c = Mansion.cell(game.origin, pl);
            double cost = Mansion.distance(me0[0], me0[1], c[0], c[1]);
            cost += witnesses(p, l) * 12 * (1.1 - aggression);
            if (p.bow) cost += 18 * (1 - aggression);
            cost += r.nextDouble() * 6;
            if (cost < bestCost) {
                bestCost = cost;
                best = p;
            }
        }
        return best;
    }

    /** How many others (besides me and the victim) could see it happen. */
    private int witnesses(Participant victim, Location myLoc) {
        int n = 0;
        LivingEntity mine = bot.body();
        LivingEntity ve = victim.entity();
        if (mine == null || ve == null) return 99;
        for (Participant p : game.alive()) {
            if (p == me || p == victim) continue;
            LivingEntity e = p.entity();
            if (e == null) continue;
            double d = Math.min(e.getLocation().distance(myLoc), e.getLocation().distance(ve.getLocation()));
            if (d < 18 && (e.hasLineOfSight(mine) || e.hasLineOfSight(ve))) n++;
        }
        return n;
    }

    // ------------------------------------------------------------ doing it

    private void act(long t, Mannequin m, Location l) {
        switch (goal) {
            case IDLE -> {
                bot.stop();
                if (t >= idleTurnAt) {
                    idleYaw = bot.yaw + (float) r.nextDouble(-110, 110);
                    idleTurnAt = t + 15 + r.nextInt(35);
                    if (r.nextDouble() < 0.06) bot.jump();
                }
                bot.lookYaw(idleYaw, (float) r.nextGaussian() * 5);
            }
            case FLEE -> {
                if (path == null || pathIdx >= path.size()) {
                    int[] far = farCell(l, threatFrom == null ? l : threatFrom);
                    goToCell(far[0], far[1], Bot.SPRINT);
                }
                follow(m, l);
            }
            case AIM -> {
                Participant tg = target;
                LivingEntity te = tg == null ? null : tg.entity();
                if (te == null || !tg.alive || me.arrows <= 0) {
                    setGoal(Goal.IDLE, 10);
                    return;
                }
                bot.stop();
                double d = te.getLocation().distance(l);
                Vector aim = lead(tg, te, d / 3.0).add(new Vector(0, 0.9 + d * d * 0.0012, 0));
                bot.lookAt(aim.toLocation(l.getWorld()));
                if (t >= aimDoneAt && bot.facing(te.getLocation(), 12) && m.hasLineOfSight(te)) {
                    if (skill > 0.55 && someoneInTheWay(l, te.getLocation(), tg)) {
                        aimDoneAt = t + 5; // wait for a clear shot
                        return;
                    }
                    Vector dir = wobble(aim.clone().subtract(m.getEyeLocation().toVector()), 0.8 + (1 - skill) * 5.5);
                    game.botShoot(me, m.getEyeLocation().add(dir));
                    bot.swing();
                    setGoal(Goal.IDLE, 8);
                }
            }
            case STALK -> {
                LivingEntity te = target == null ? null : target.entity();
                if (te == null || !target.alive) {
                    setGoal(Goal.IDLE, 5);
                    return;
                }
                double d = te.getLocation().distance(l);
                if (d < 2.6 && m.hasLineOfSight(te)) {
                    bot.hold(Material.IRON_SWORD);
                    bot.lookAt(te.getLocation().add(0, 1.2, 0));
                    bot.swing();
                    game.kill(target, me, "knife");
                    return;
                }
                if (path != null && pathIdx < path.size() && d > 3) follow(m, l);
                else if (d <= 3) {
                    bot.moveToward(te.getLocation().getX(), te.getLocation().getZ(), Bot.SPRINT);
                    bot.lookAt(te.getLocation().add(0, 1.2, 0));
                }
                if (t % 10 == 0 && d > 3) goTo(te.getLocation(), d < 7 ? Bot.SPRINT : Bot.WALK);
            }
            default -> {
                if (goal == Goal.BOW && game.bowDrop != null && game.bowDrop.isValid() && t % 20 == 0) goTo(game.bowDrop.getLocation(), Bot.SPRINT);
                if (path == null || pathIdx >= path.size()) {
                    if (goal != Goal.IDLE) setGoal(Goal.IDLE, 10 + r.nextInt(40));
                    bot.stop();
                    return;
                }
                follow(m, l);
                if (goal == Goal.HUNT && known != null && known.alive && game.t % 15 == 0 && knownAt != null) goTo(knownAt, Bot.SPRINT);
            }
        }
    }

    private boolean someoneInTheWay(Location from, Location to, Participant target) {
        Vector a = from.toVector(), ab = to.toVector().subtract(a);
        double len = ab.length();
        if (len < 1e-3) return false;
        ab.multiply(1 / len);
        for (Participant p : game.alive()) {
            if (p == me || p == target) continue;
            Location pl = p.loc();
            if (pl == null) continue;
            Vector ap = pl.toVector().subtract(a);
            double along = ap.dot(ab);
            if (along < 0.5 || along > len - 0.5) continue;
            double off = ap.clone().subtract(ab.clone().multiply(along)).setY(0).length();
            if (off < 0.9) return true;
        }
        return false;
    }

    /** Where someone will be in a few ticks if they keep going (skilled bots lead their shots better). */
    private Vector lead(Participant p, LivingEntity e, double ticks) {
        Vector v = game.motion(p).setY(0);
        if (v.lengthSquared() > 0.16) v.multiply(0.4 / v.length());
        return e.getLocation().toVector().add(v.multiply(Math.min(12, ticks) * (0.3 + skill * 0.7)));
    }

    private Vector wobble(Vector dir, double degrees) {
        double len = dir.length();
        if (len < 1e-6) return dir;
        Vector d = dir.clone().multiply(1 / len);
        double yaw = Math.toRadians(r.nextGaussian() * degrees), pitch = Math.toRadians(r.nextGaussian() * degrees * 0.6);
        d.rotateAroundY(yaw);
        Vector side = d.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() > 1e-6) d.rotateAroundAxis(side.normalize(), pitch);
        return d.multiply(len);
    }

    private boolean inView(Location at, double fov) {
        Mannequin m = bot.body();
        if (m == null) return false;
        Location e = m.getLocation();
        float want = (float) Math.toDegrees(Math.atan2(-(at.getX() - e.getX()), at.getZ() - e.getZ()));
        return Math.abs(Bot.wrap(want - bot.yaw)) <= fov / 2;
    }

    private Item nearestGold(Location l, Mannequin m, double within) {
        Item best = null;
        double bd = within * within;
        for (Item g : game.gold) {
            if (!g.isValid()) continue;
            double d = g.getLocation().distanceSquared(l);
            if (d < bd && m.hasLineOfSight(g)) {
                bd = d;
                best = g;
            }
        }
        return best;
    }

    private int[] farCell(Location l, Location threat) {
        int[] best = Mansion.ROOMS[0];
        double bestScore = -1e9;
        for (int i = 0; i < 10; i++) {
            int[] c = Mansion.randomFloor();
            Location cl = Mansion.center(game.origin, c[0], c[1]);
            double s = cl.distance(threat) - 0.35 * cl.distance(l);
            // Don't run past the threat to get there.
            Vector toC = cl.toVector().subtract(l.toVector()).setY(0), toT = threat.toVector().subtract(l.toVector()).setY(0);
            if (toC.lengthSquared() > 0 && toT.lengthSquared() > 0 && toC.normalize().dot(toT.normalize()) > 0.5) s -= 15;
            if (s > bestScore) {
                bestScore = s;
                best = c;
            }
        }
        return best;
    }

    // ------------------------------------------------------------ paths

    private void setGoal(Goal g, int ticks) {
        if (goal != g) path = null;
        goal = g;
        goalUntil = game.t + ticks;
    }

    private void goTo(Location to, double sp) {
        int[] c = Mansion.cell(game.origin, to);
        goToCell(c[0], c[1], sp);
    }

    private void goToCell(int x, int z, double sp) {
        speed = sp;
        Mannequin m = bot.body();
        if (m == null) return;
        int[] from = Mansion.cell(game.origin, m.getLocation());
        if (path != null && pathGoal != null && pathGoal[0] == x && pathGoal[1] == z && pathIdx < path.size()) return;
        path = Mansion.path(from[0], from[1], x, z);
        pathIdx = 0;
        pathGoal = new int[] {x, z};
    }

    private void follow(Mannequin m, Location l) {
        if (path == null || pathIdx >= path.size()) {
            bot.stop();
            return;
        }
        double lx = l.getX() - game.origin.getBlockX(), lz = l.getZ() - game.origin.getBlockZ();
        int[] c = path.get(pathIdx);
        if (Math.hypot(c[0] + 0.5 - lx, c[1] + 0.5 - lz) < 0.6) {
            pathIdx++;
            if (pathIdx >= path.size()) {
                bot.stop();
                return;
            }
        }
        // Head for the furthest step it can walk to in a straight line (cuts corners like a player).
        int aim = pathIdx;
        for (int k = Math.min(path.size() - 1, pathIdx + 7); k > pathIdx; k--) {
            int[] s = path.get(k);
            if (Mansion.straight(lx, lz, s[0] + 0.5, s[1] + 0.5)) {
                aim = k;
                break;
            }
        }
        int[] a = path.get(aim);
        double tx = game.origin.getBlockX() + a[0] + 0.5, tz = game.origin.getBlockZ() + a[1] + 0.5;
        bot.moveToward(tx, tz, speed);
        if (r.nextDouble() < 0.004) bot.jump();
    }
}
