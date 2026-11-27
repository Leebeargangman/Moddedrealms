package net.moddedrealms.smp.town;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.kyori.adventure.title.Title;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * The Bureau: special agents who take over from the Town Watch once someone reaches 3 stars. A case stays open for
 * a few Minecraft days (refreshed by new crimes) and agents follow the suspect anywhere within 2000 blocks of the
 * fountain, not just the 500-block police zone: random ambushes, a stakeout at their bed, and snares laid where they
 * keep coming back to. Arrest closes the case. They also hunt dangerous bandits. They never take bribes.
 */
public final class Bureau {
    public enum Kind { AMBUSH, STAKEOUT, TRAP, TIP }

    private final TownManager t;
    private final Random r = new Random();

    Bureau(TownManager t) {
        this.t = t;
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    public double reach() {
        return cfg().getDouble("town.bureau.reach", 2000);
    }

    public boolean inReach(Location l) {
        if (l.getWorld() == null || !l.getWorld().getName().equals(t.worldName)) return false;
        double dx = l.getX() - t.cx, dz = l.getZ() - t.cz;
        return dx * dx + dz * dz <= reach() * reach();
    }

    public boolean inReach(Player p) {
        return inReach(p.getLocation());
    }

    private long worldTime() {
        World w = t.world();
        return w == null ? 0 : w.getFullTime();
    }

    // ------------------------------------------------------------------ cases

    /** Called whenever someone's wanted level goes up. */
    void maybeOpen(Player p, TownData d, Crime c) {
        if (d.stars < cfg().getInt("town.bureau.from-stars", 3) || !cfg().getBoolean("town.bureau.enabled", true)) return;
        long until = worldTime() + cfg().getLong("town.bureau.case-days", 3) * 24000L;
        boolean fresh = !d.caseOpen;
        d.caseOpen = true;
        d.caseUntil = Math.max(d.caseUntil, until);
        d.caseReason = c.label;
        t.markDirty();
        if (fresh) {
            d.stakeoutDone = false;
            d.trap = null;
            d.visits.clear();
            d.visitSpot.clear();
            d.nextAmbush = System.currentTimeMillis() + (120 + r.nextInt(120)) * 1000L;
            p.sendMessage(SmpPlugin.mm("<dark_red><bold>The Inquisition has opened a case on you.</bold> <gray>Inquisitors will track you anywhere within "
                    + (int) reach() + " blocks of spawn for " + cfg().getLong("town.bureau.case-days", 3)
                    + " Minecraft days. They set traps and watch your home. Get further away, or give yourself up."));
            p.playSound(p.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.5f, 1.2f);
            t.news("The Inquisition opened a case on " + p.getName() + ".");
        }
    }

    void close(TownData d, Player p, String why) {
        if (!d.caseOpen) return;
        d.caseOpen = false;
        d.trap = null;
        d.visits.clear();
        d.visitSpot.clear();
        t.markDirty();
        for (Walker a : t.walkers(Walker.Role.AGENT)) if (p != null && p.getUniqueId().equals(a.target)) t.unregister(a, true);
        if (p != null && why != null) p.sendMessage(SmpPlugin.mm(why));
    }

    /** Every 10 seconds. */
    void tick() {
        long now = System.currentTimeMillis();
        long wt = worldTime();
        for (Player p : Bukkit.getOnlinePlayers()) {
            TownData d = t.data(p);
            if (!d.caseOpen) continue;
            if (wt >= d.caseUntil) {
                close(d, p, "<green>The Inquisition has closed your case. You're in the clear... for now.");
                continue;
            }
            if (d.jailed() || d.inService() || t.exempt(p)) continue;
            if (!inReach(p)) {
                if (!d.outOfReachNotified) p.sendMessage(SmpPlugin.mm("<gray>You're beyond the Inquisition's reach here (" + (int) reach() + " blocks from spawn)."));
                d.outOfReachNotified = true;
                continue;
            }
            if (d.outOfReachNotified) {
                d.outOfReachNotified = false;
                p.sendMessage(SmpPlugin.mm("<dark_red>You're back within the Inquisition's reach."));
            }
            Location l = p.getLocation();
            // Track where they keep coming back to, and lay a snare there.
            if (now - d.lastTrack > 60_000) {
                d.lastTrack = now;
                long key = ((long) (l.getBlockX() >> 4) << 32) ^ ((l.getBlockZ() >> 4) & 0xffffffffL);
                int n = d.visits.merge(key, 1, Integer::sum);
                d.visitSpot.put(key, l.clone());
                if (n >= 3 && d.trap == null && !t.inPoliceZone(l)) d.trap = l.clone();
            }
            if (d.trap != null && d.trap.getWorld() == l.getWorld() && d.trap.distance(l) < 6 && d.visits.size() > 0) {
                d.trap = null;
                d.visits.clear();
                ambush(p, Kind.TRAP);
                continue;
            }
            Location bed = p.getRespawnLocation();
            if (!d.stakeoutDone && bed != null && bed.getWorld() == l.getWorld() && bed.distance(l) < 20) {
                d.stakeoutDone = true;
                ambush(p, Kind.STAKEOUT);
                continue;
            }
            if (now >= d.nextAmbush && agentsNear(l, 48) == 0) {
                ambush(p, Kind.AMBUSH);
            }
        }
    }

    private int agentsNear(Location l, double r) {
        int n = 0;
        for (Walker a : t.walkers(Walker.Role.AGENT)) if (a.dist(l) <= r) n++;
        return n;
    }

    // ------------------------------------------------------------------ agents

    public void ambush(Player p, Kind kind) {
        World w = p.getWorld();
        if (!t.canSpawnWalkers(w)) return;
        TownData d = t.data(p);
        d.nextAmbush = System.currentTimeMillis() + (cfg().getLong("town.bureau.ambush-minutes-min", 4) * 60
                + r.nextInt((int) Math.max(1, cfg().getLong("town.bureau.ambush-minutes-extra", 4) * 60))) * 1000L;
        int n = cfg().getInt("town.bureau.agents", 3) + (d.stars >= 4 ? 1 : 0);
        Location around = kind == Kind.STAKEOUT && p.getRespawnLocation() != null ? p.getRespawnLocation() : p.getLocation();
        int made = 0;
        for (int i = 0; i < n * 4 && made < n; i++) {
            double a = r.nextDouble() * Math.PI * 2, dist = kind == Kind.TRAP ? 5 + r.nextDouble() * 4 : 9 + r.nextDouble() * 6;
            Location at = t.surface(w, (int) Math.floor(around.getX() + Math.cos(a) * dist), (int) Math.floor(around.getZ() + Math.sin(a) * dist), around.getY(), 6);
            if (at == null) continue;
            Walker ag = spawn(at);
            ag.target = p.getUniqueId();
            ag.mode = 1;
            ag.modeSince = System.currentTimeMillis();
            w.spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 1, 0), 10, 0.3, 0.6, 0.3, 0.01);
            made++;
        }
        if (made == 0) return;
        String sub = switch (kind) {
            case STAKEOUT -> "They were waiting for you at home!";
            case TRAP -> "You walked into an Inquisition snare!";
            case TIP -> "Someone tipped them off!";
            default -> "Freeze, " + p.getName() + "! You're under arrest!";
        };
        if (kind == Kind.TRAP) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 4, false, true));
            w.spawnParticle(Particle.BLOCK, p.getLocation(), 40, 0.5, 0.2, 0.5, Material.COBWEB.createBlockData());
            p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1f, 0.6f);
        }
        p.showTitle(Title.title(SmpPlugin.mm("<dark_red><bold>BUREAU!"), SmpPlugin.mm("<white>" + sub),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2200), Duration.ofMillis(400))));
        p.playSound(p.getLocation(), Sound.ENTITY_EVOKER_PREPARE_ATTACK, 1f, 0.7f);
        p.sendMessage(SmpPlugin.mm("<dark_red>Inquisitors! <gray>Crouch next to one to give yourself up, or fight your way out."));
    }

    public Walker spawn(Location at) {
        List<String> names = cfg().getStringList("town.names.agents");
        String sur = names.isEmpty() ? "Cole" : names.get(r.nextInt(names.size()));
        Map<EquipmentSlot, ItemStack> gear = new EnumMap<>(EquipmentSlot.class);
        gear.put(EquipmentSlot.CHEST, Walker.dyed(Material.LEATHER_CHESTPLATE, "#151515"));
        gear.put(EquipmentSlot.LEGS, Walker.dyed(Material.LEATHER_LEGGINGS, "#151515"));
        gear.put(EquipmentSlot.FEET, Walker.dyed(Material.LEATHER_BOOTS, "#0A0A0A"));
        gear.put(EquipmentSlot.HAND, new ItemStack(Material.IRON_SWORD));
        Walker a = Walker.spawn(t.key, at, Walker.Role.AGENT, "<dark_gray>Inquisitor " + sur, "<white>The Inquisition",
                cfg().getDouble("town.bureau.speed", 0.27), cfg().getDouble("town.bureau.health", 40), gear);
        return t.register(a);
    }

    /** Send agents after a dangerous bandit. */
    public void hunt(Walker bandit) {
        for (int i = 0; i < 2; i++) {
            Location b = bandit.loc();
            double a = r.nextDouble() * Math.PI * 2;
            Location at = t.surface(b.getWorld(), (int) Math.floor(b.getX() + Math.cos(a) * 12), (int) Math.floor(b.getZ() + Math.sin(a) * 12), b.getY(), 6);
            if (at == null) continue;
            Walker ag = spawn(at);
            ag.foe = bandit.legs.getUniqueId();
            ag.mode = 2;
            ag.modeSince = System.currentTimeMillis();
        }
    }

    void think() {
        long now = System.currentTimeMillis();
        for (Walker a : new ArrayList<>(t.walkers(Walker.Role.AGENT))) {
            if (!a.valid()) continue;
            if (now - a.modeSince > cfg().getLong("town.bureau.agent-minutes", 4) * 60_000L || t.nearestPlayer(a.loc(), 110) == null) {
                t.unregister(a, true);
                continue;
            }
            if (a.mode == 2) fightBandit(a, now);
            else chase(a, now);
        }
    }

    private void chase(Walker a, long now) {
        Player p = a.target == null ? null : Bukkit.getPlayer(a.target);
        TownData d = p == null ? null : t.data(p);
        if (p == null || p.getWorld() != a.world() || p.isDead() || !d.caseOpen || d.jailed() || d.inService() || t.exempt(p) || !inReach(p)
                || a.dist(p.getLocation()) > 80) {
            t.unregister(a, true);
            return;
        }
        double dist = a.dist(p.getLocation());
        if (dist > 2.2) a.moveTo(p, dist > 8 ? 1.55 : 1.3);
        else {
            a.stop();
            a.lookAt(p.getEyeLocation());
        }
        if (dist < 3.0 && now - a.lastAttack > 900 && a.sees(p)) {
            a.lastAttack = now;
            a.lookAt(p.getEyeLocation());
            a.swing();
            p.damage(cfg().getDouble("town.bureau.damage", 5), a.body);
        }
    }

    private void fightBandit(Walker a, long now) {
        Walker b = null;
        for (Walker x : t.walkers(Walker.Role.BANDIT)) if (x.legs.getUniqueId().equals(a.foe)) b = x;
        if (b == null || !b.valid()) {
            t.unregister(a, true);
            return;
        }
        double dist = a.dist(b.loc());
        if (dist > 2.2) a.moveTo(b.legs, 1.5);
        if (dist < 3.0 && now - a.lastAttack > 900) {
            a.lastAttack = now;
            a.lookAt(b.body.getEyeLocation());
            a.swing();
            t.bandits().hitByWalker(b, a, 7);
        }
    }

    // ------------------------------------------------------------------ players vs agents

    void hit(Walker a, Player p, double damage) {
        a.health -= damage;
        a.hurt();
        if (t.exempt(p)) return;
        if (a.health <= 0) {
            a.shout("<dark_red>Inquisitor down!", 32);
            t.unregister(a, true);
            t.wanted(p, Crime.COP_KILLING, null);
            t.addRep(p, -Crime.COP_KILLING.rep);
            return;
        }
        TownData d = t.data(p);
        if (!d.recentCrimes.containsKey("AGENT:" + a.legs.getUniqueId())) {
            d.recentCrimes.put("AGENT:" + a.legs.getUniqueId(), System.currentTimeMillis());
            t.wanted(p, Crime.ASSAULT_OFFICER, null);
            t.addRep(p, -Crime.ASSAULT_OFFICER.rep);
        }
        if (a.mode != 2) {
            a.target = p.getUniqueId();
            a.mode = 1;
        }
    }

    void clicked(Walker a, Player p) {
        TownData d = t.data(p);
        a.stop();
        a.lookAt(p.getEyeLocation());
        if (d.caseOpen || d.stars > 0) {
            a.say(p, "Smart move. You're coming with us.");
            t.arrest(p, a, false, null);
        } else {
            a.say(p, r.nextBoolean() ? "Inquisition business. Move along." : "Nothing to see here, citizen.");
        }
    }

    public Walker nearest(Location l, double r) {
        Walker best = null;
        double bd = r;
        for (Walker a : t.walkers(Walker.Role.AGENT)) {
            double d = a.dist(l);
            if (d <= bd) {
                bd = d;
                best = a;
            }
        }
        return best;
    }
}
