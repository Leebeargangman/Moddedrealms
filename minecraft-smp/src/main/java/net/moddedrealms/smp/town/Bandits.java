package net.moddedrealms.smp.town;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Bandits roam the wilds just outside town. Each one is wanted (1-4 stars) with a bounty on its head. They attack
 * players who come close and grab coins with every hit; beat one down and it surrenders: the bounty is yours and the
 * coins go back to whoever they were taken from. Inside the police zone officers fight them, and the Bureau hunts
 * the dangerous ones (3+ stars).
 */
public final class Bandits {
    static final int IDLE = 0, ATTACK = 1, FLEE = 2;

    private final TownManager t;
    private final Random r = new Random();

    Bandits(TownManager t) {
        this.t = t;
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    private double fromCentre(Location l) {
        double dx = l.getX() - t.cx, dz = l.getZ() - t.cz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    void spawnTick() {
        World w = t.world();
        if (w == null || !cfg().getBoolean("town.bandits.enabled", true) || !t.canSpawnWalkers(w)) return;
        int max = cfg().getInt("town.bandits.max", 8);
        int total = t.walkers(Walker.Role.BANDIT).size();
        double min = cfg().getDouble("town.bandits.min-distance", 170), far = cfg().getDouble("town.bandits.max-distance", 1500);
        double chance = t.isNight(w) ? cfg().getDouble("town.bandits.chance-night", 0.5) : cfg().getDouble("town.bandits.chance-day", 0.25);
        for (Player p : w.getPlayers()) {
            if (total >= max) return;
            if (t.exempt(p)) continue;
            double d = fromCentre(p.getLocation());
            if (d < min || d > far || r.nextDouble() > chance || t.nearOtherTown(p.getLocation(), 80)) continue;
            boolean near = false;
            for (Walker b : t.walkers(Walker.Role.BANDIT)) if (b.dist(p.getLocation()) < 80) near = true;
            if (near) continue;
            int gang = r.nextInt(100) < 30 ? 2 : 1;
            for (int i = 0; i < gang && total < max; i++) {
                Location at = t.spawnSpot(p, 26, 40);
                if (at == null || fromCentre(at) < min) continue;
                spawn(at, rollStars());
                total++;
            }
        }
    }

    private int rollStars() {
        int x = r.nextInt(100);
        return x < 40 ? 1 : x < 70 ? 2 : x < 90 ? 3 : 4;
    }

    public Walker spawn(Location at, int stars) {
        List<String> names = cfg().getStringList("town.names.bandits");
        String nick = names.isEmpty() ? "Grim" : names.get(r.nextInt(names.size()));
        Map<EquipmentSlot, ItemStack> gear = new EnumMap<>(EquipmentSlot.class);
        gear.put(EquipmentSlot.HEAD, Walker.dyed(Material.LEATHER_HELMET, "#1A1A1A"));
        gear.put(EquipmentSlot.CHEST, Walker.dyed(Material.LEATHER_CHESTPLATE, stars >= 3 ? "#5A1414" : "#6B4A2B"));
        gear.put(EquipmentSlot.LEGS, Walker.dyed(Material.LEATHER_LEGGINGS, "#2B2B2B"));
        gear.put(EquipmentSlot.FEET, Walker.dyed(Material.LEATHER_BOOTS, "#1A1A1A"));
        gear.put(EquipmentSlot.HAND, new ItemStack(stars >= 4 ? Material.DIAMOND_SWORD : stars >= 2 ? Material.IRON_SWORD : Material.STONE_SWORD));
        double hp = cfg().getDouble("town.bandits.health", 24) + 6 * stars;
        Walker b = Walker.spawn(t.key, at, Walker.Role.BANDIT, "<red>Bandit " + nick, null, 0.27, hp, gear);
        b.stars = stars;
        b.bounty = stars * cfg().getDouble("town.bandits.bounty-per-star", 200);
        b.lifetime = 15 * 60_000L;
        b.nextWander = System.currentTimeMillis() + 2000;
        retitle(b);
        return t.register(b);
    }

    private void retitle(Walker b) {
        b.rename(b.name, TownManager.stars(b.stars) + " <gold>Bounty " + Money.fmt(b.bounty));
    }

    void think() {
        long now = System.currentTimeMillis();
        for (Walker b : new ArrayList<>(t.walkers(Walker.Role.BANDIT))) {
            if (!b.valid()) continue;
            Player near = t.nearestPlayer(b.loc(), 96);
            if (near == null || now - b.born > b.lifetime && near.getLocation().distance(b.loc()) > 32) {
                escaped(b);
                continue;
            }
            switch (b.mode) {
                case ATTACK -> attack(b, now);
                case FLEE -> flee(b, now);
                default -> idle(b, now);
            }
            if (b.stars >= cfg().getInt("town.bureau.from-stars", 3) && b.foe == null && r.nextInt(1200) == 0 && t.bureau().inReach(b.loc())) {
                t.bureau().hunt(b);
            }
        }
    }

    private boolean victim(Player p) {
        TownData d = t.data(p);
        return !t.exempt(p) && !d.inService();
    }

    private void idle(Walker b, long now) {
        for (Player p : b.world().getPlayers()) {
            if (b.dist(p.getLocation()) < 14 && victim(p) && b.sees(p)) {
                b.mode = ATTACK;
                b.target = p.getUniqueId();
                b.modeSince = now;
                b.say(p, r.nextBoolean() ? "Your money or your life!" : "Well, well. Hand over the coin, friend.");
                return;
            }
        }
        if (!b.moving() && now > b.nextWander) {
            Location base = b.loc();
            double a = r.nextDouble() * Math.PI * 2, d = 5 + r.nextDouble() * 10;
            Location g = t.surface(base.getWorld(), (int) Math.floor(base.getX() + Math.cos(a) * d), (int) Math.floor(base.getZ() + Math.sin(a) * d), base.getY(), 4);
            if (g != null) b.moveTo(g, 0.6);
            b.nextWander = now + 4000 + r.nextInt(8000);
        }
    }

    private void attack(Walker b, long now) {
        Player p = b.target == null ? null : Bukkit.getPlayer(b.target);
        if (p == null || p.getWorld() != b.world() || p.isDead() || !victim(p) || b.dist(p.getLocation()) > 32) {
            b.mode = IDLE;
            b.target = null;
            b.stop();
            return;
        }
        if (b.health < b.maxHealth * 0.25) {
            b.mode = FLEE;
            b.modeSince = now;
            b.shout("<gray>Not worth it! I'm out of here!", 20);
            return;
        }
        double d = b.dist(p.getLocation());
        if (d > 2.2) b.moveTo(p, 1.3);
        else {
            b.stop();
            b.lookAt(p.getEyeLocation());
        }
        if (d < 2.8 && now - b.lastAttack > 1000 && b.sees(p)) {
            b.lastAttack = now;
            b.lookAt(p.getEyeLocation());
            b.swing();
            p.damage(cfg().getDouble("town.bandits.damage", 3) + 0.5 * b.stars, b.body);
            double take = Math.floor(Math.min(t.money.balance(p) * 0.01, cfg().getDouble("town.bandits.steal-per-hit", 40)));
            if (take >= 1) {
                double took = t.money.take(p, take);
                if (took > 0) {
                    b.loot += took;
                    b.stolenFrom.merge(p.getUniqueId(), took, Double::sum);
                    p.sendActionBar(SmpPlugin.mm("<red>" + b.name + " <red>grabbed " + Money.fmt(took) + " from you!"));
                }
            }
        }
    }

    private void flee(Walker b, long now) {
        Player chaser = t.nearestPlayer(b.loc(), 40);
        if (chaser == null || now - b.modeSince > 25_000) {
            escaped(b);
            return;
        }
        if (!b.moving() || r.nextInt(3) == 0) {
            Location away = t.awayFrom(b.loc(), chaser.getLocation(), 14);
            if (away != null) b.moveTo(away, 1.5);
        }
    }

    private void escaped(Walker b) {
        for (var e : b.stolenFrom.entrySet()) {
            Player v = Bukkit.getPlayer(e.getKey());
            if (v != null) v.sendMessage(SmpPlugin.mm("<gray>" + b.name + " <gray>got away with your <white>" + Money.fmt(e.getValue()) + "</white>."));
        }
        t.unregister(b, true);
    }

    /** A player hit the bandit. */
    void hit(Walker b, Player p, double dmg) {
        if (b.mode == IDLE) {
            b.mode = ATTACK;
            b.target = p.getUniqueId();
            b.modeSince = System.currentTimeMillis();
        }
        b.damagers.merge(p.getUniqueId(), dmg, Double::sum);
        hurt(b, dmg, p, null);
    }

    /** An officer or agent hit the bandit. */
    void hitByWalker(Walker b, Walker by, double dmg) {
        hurt(b, dmg, null, by);
    }

    private void hurt(Walker b, double dmg, Player p, Walker npc) {
        b.health -= dmg;
        b.hurt();
        if (b.health <= 0) defeated(b, p, npc);
    }

    private void defeated(Walker b, Player by, Walker npc) {
        b.shout("<yellow>I yield! I yield! Take me in...", 24);
        for (var e : b.stolenFrom.entrySet()) {
            OfflinePlayer v = Bukkit.getOfflinePlayer(e.getKey());
            t.money.give(v, e.getValue());
            if (v.getPlayer() != null) v.getPlayer().sendMessage(SmpPlugin.mm("<green>You got back the <white>" + Money.fmt(e.getValue()) + "</white> the bandit took."));
        }
        String plain = b.name.replaceAll("<[^>]+>", "");
        if (by != null) {
            t.money.give(by, b.bounty);
            t.addRep(by, cfg().getInt("town.reputation.bandit", 10));
            by.sendMessage(SmpPlugin.mm("<gold>⚑ You brought in " + b.name + "<gold>! Bounty: <yellow>+" + Money.fmt(b.bounty)));
            by.playSound(by.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
            if (t.data(by).cop) {
                t.data(by).copArrests++;
                t.markDirty();
            }
            t.news(by.getName() + " brought in " + plain + " (" + b.stars + " stars) for a " + Money.fmt(b.bounty) + " bounty!");
        } else {
            UUID tipper = b.tipper;
            if (tipper != null && System.currentTimeMillis() - b.tippedAt < 10 * 60_000L) {
                double share = Math.floor(b.bounty * cfg().getDouble("town.bounty.tip-share", 0.5));
                OfflinePlayer tp = Bukkit.getOfflinePlayer(tipper);
                t.money.give(tp, share);
                if (tp.getPlayer() != null) tp.getPlayer().sendMessage(SmpPlugin.mm("<gold>⚑ Your tip paid off: " + plain + " was brought in. <yellow>+" + Money.fmt(share)));
            }
            t.news((npc != null && npc.role == Walker.Role.AGENT ? "Inquisitors" : "The Town Watch") + " brought in " + plain + ".");
        }
        t.unregister(b, true);
    }

    public Walker nearest(Location l, double r) {
        Walker best = null;
        double bd = r;
        for (Walker b : t.walkers(Walker.Role.BANDIT)) {
            double d = b.dist(l);
            if (d <= bd) {
                bd = d;
                best = b;
            }
        }
        return best;
    }
}
