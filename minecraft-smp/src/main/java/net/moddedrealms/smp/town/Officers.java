package net.moddedrealms.smp.town;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

/**
 * The Town Watch. Officers appear near players inside the police zone (out of sight, behind them), stroll around,
 * and react to what they see: wanted players are told to surrender or, if violent, beaten down and arrested;
 * anyone visibly carrying explosives is searched; fleeing pickpockets are chased.
 */
public final class Officers {
    static final int PATROL = 0, PURSUE = 1, SEARCH = 2, CHASE_THIEF = 3, FIGHT = 4;

    private final TownManager t;
    private final Random r = new Random();

    Officers(TownManager t) {
        this.t = t;
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    private Set<Material> contraband() {
        Set<Material> s = EnumSet.noneOf(Material.class);
        for (String n : cfg().getStringList("town.contraband")) {
            Material m = Material.matchMaterial(n);
            if (m != null) s.add(m);
        }
        return s;
    }

    // ------------------------------------------------------------------ spawning

    void spawnTick() {
        World w = t.world();
        if (w == null || !t.canSpawnWalkers(w)) return;
        int max = cfg().getInt("town.max-officers", 24);
        int total = t.walkers(Walker.Role.OFFICER).size();
        for (Player p : w.getPlayers()) {
            if (total >= max) return;
            TownData d = t.data(p);
            if (t.exempt(p) || !t.inPoliceZone(p.getLocation())) continue;
            int need = cfg().getInt("town.officers-per-player", 2) + Math.min(3, d.stars);
            if (t.countNear(p.getLocation(), 48, true) >= need) continue;
            Location at = d.stars > 0 ? t.spawnSpot(p, 14, 24) : t.spawnSpot(p, 20, 34);
            if (at == null) continue;
            Walker o = spawn(at);
            total++;
            if (d.stars > 0) pursue(o, p);
        }
    }

    public Walker spawn(Location at) {
        String sur = t.freshName("town.names.officers", "Grant", "Guard ", "town.names.townsfolk", true);
        String uniform = cfg().getString("town.officer-uniform-color", "#1F2A44");
        Map<EquipmentSlot, ItemStack> gear = new EnumMap<>(EquipmentSlot.class);
        gear.put(EquipmentSlot.HEAD, new ItemStack(Material.IRON_HELMET));
        gear.put(EquipmentSlot.CHEST, Walker.dyed(Material.LEATHER_CHESTPLATE, uniform));
        gear.put(EquipmentSlot.LEGS, Walker.dyed(Material.LEATHER_LEGGINGS, uniform));
        gear.put(EquipmentSlot.FEET, Walker.dyed(Material.LEATHER_BOOTS, "#111111"));
        gear.put(EquipmentSlot.HAND, new ItemStack(Material.STICK));
        Walker o = Walker.spawn(t.key, at, Walker.Role.OFFICER, "<blue>Guard " + sur, "<gray>Town Watch",
                cfg().getDouble("town.officer-speed", 0.25), cfg().getDouble("town.officer-health", 30), gear);
        o.nextWander = System.currentTimeMillis() + 1000;
        o.corrupt = r.nextDouble() < cfg().getDouble("town.bribe.chance", 0.3);
        return t.register(o);
    }

    // ------------------------------------------------------------------ brain

    void think() {
        long now = System.currentTimeMillis();
        for (Walker o : new ArrayList<>(t.walkers(Walker.Role.OFFICER))) {
            if (!o.valid()) continue;
            Location at = o.loc();
            Player near = t.nearestPlayer(at, o.mode == PURSUE ? 110 : 80);
            if (near == null || !t.inPoliceZone(at) && o.mode != PURSUE) {
                t.unregister(o, false);
                continue;
            }
            stuck(o, now);
            switch (o.mode) {
                case PURSUE -> pursue(o, now);
                case SEARCH -> search(o, now);
                case CHASE_THIEF -> chaseThief(o, now);
                case FIGHT -> fight(o, now);
                default -> patrol(o, now);
            }
        }
    }

    /** Pathfinding can give up (fences, water); after a while hop to the nearest free spot towards the goal. */
    private void stuck(Walker o, long now) {
        Location l = o.loc();
        if (o.lastPos == null || o.lastPos.getWorld() != l.getWorld() || o.lastPos.distanceSquared(l) > 0.25 || o.goal == null) {
            o.lastPos = l;
            o.stuckSince = now;
            return;
        }
        if (now - o.stuckSince > 6000 && o.mode == PATROL) {
            o.stop();
            o.stuckSince = now;
        }
    }

    private void arm(Walker o, boolean armed) {
        if (o.armed == armed) return;
        o.armed = armed;
        o.hold(new ItemStack(armed ? Material.IRON_SWORD : Material.STICK));
    }

    private void pursue(Walker o, long now) {
        Player p = o.target == null ? null : Bukkit.getPlayer(o.target);
        TownData d = p == null ? null : t.data(p);
        if (p == null || p.getWorld() != o.world() || p.isDead() || d.stars <= 0 || d.jailed() || t.exempt(p)) {
            backToPatrol(o);
            return;
        }
        double dist = o.dist(p.getLocation());
        boolean sees = dist <= t.sight * 1.5 && o.sees(p);
        if (sees) d.lastSeenByPolice = now;
        if (!t.inPoliceZone(p.getLocation()) && dist > 12 || !sees && now - d.lastSeenByPolice > 20_000 || dist > 100) {
            backToPatrol(o);
            return;
        }
        arm(o, d.hostile);
        if (d.hostile) {
            if (dist > 2.2) o.moveTo(p, dist > 8 ? 1.55 : 1.3);
            else {
                o.stop();
                o.lookAt(p.getEyeLocation());
            }
            if (dist < 3.0 && sees && now - o.lastAttack > 900) attack(o, p, d);
        } else {
            if (dist > 3.2) o.moveTo(p, dist > 10 ? 1.5 : 1.15);
            else {
                o.stop();
                o.lookAt(p.getEyeLocation());
                t.orderArrest(p, o);
            }
        }
    }

    private void attack(Walker o, Player p, TownData d) {
        o.lastAttack = System.currentTimeMillis();
        o.lookAt(p.getEyeLocation());
        o.swing();
        double dmg = cfg().getDouble("town.officer-damage", 4) + 0.5 * d.stars;
        p.damage(dmg, o.body);
        Vector kb = p.getLocation().toVector().subtract(o.loc().toVector()).setY(0);
        if (kb.lengthSquared() > 1e-4) p.setVelocity(p.getVelocity().add(kb.normalize().multiply(0.35).setY(0.2)));
    }

    private void backToPatrol(Walker o) {
        o.mode = PATROL;
        o.target = null;
        o.stop();
        arm(o, false);
    }

    public void pursue(Walker o, Player p) {
        o.mode = PURSUE;
        o.target = p.getUniqueId();
        o.modeSince = System.currentTimeMillis();
    }

    private void patrol(Walker o, long now) {
        arm(o, false);
        Set<Material> bad = contraband();
        Player closest = null;
        double cd = 1e9;
        for (Player p : o.world().getPlayers()) {
            double dist = o.dist(p.getLocation());
            if (dist > t.sight || t.exempt(p)) continue;
            TownData d = t.data(p);
            if (dist < cd) {
                cd = dist;
                closest = p;
            }
            if (d.stars > 0 && o.sees(p)) {
                o.say(p, d.hostile ? "There you are! Drop your weapon!" : "Hey! You there, " + p.getName() + "! Stop!");
                pursue(o, p);
                t.officers().radio(p, o);
                return;
            }
            PlayerInventory inv = p.getInventory();
            boolean visible = bad.contains(inv.getItemInMainHand().getType()) || bad.contains(inv.getItemInOffHand().getType())
                    || stolen(inv.getItemInMainHand()) || stolen(inv.getItemInOffHand());
            if (visible && dist < 16 && now - d.lastSearch > 60_000 && o.sees(p)) {
                d.lastSearch = now;
                o.say(p, "Hold it right there! What's that you're carrying?");
                startSearch(o, p);
                return;
            }
            if (dist < 6 && now - d.lastSearch > cfg().getLong("town.random-search-minutes", 15) * 60_000
                    && r.nextDouble() < cfg().getDouble("town.random-search-chance", 0.006)) {
                d.lastSearch = now;
                o.say(p, "Routine check, " + p.getName() + ". This will only take a second.");
                startSearch(o, p);
                return;
            }
            if (dist < 5 && t.tier(p) == TownManager.Tier.HERO && now - o.lastGreet > 90_000 && o.sees(p)) {
                o.lastGreet = now;
                o.stop();
                o.lookAt(p.getEyeLocation());
                o.swing();
                p.sendActionBar(SmpPlugin.mm(o.name + " <gray>salutes you. <gold>Hero of the town"));
            }
        }
        for (Walker th : t.walkers(Walker.Role.THIEF)) {
            if (th.mode == Townsfolk.ESCAPE && th.valid() && o.dist(th.loc()) < 22) {
                o.mode = CHASE_THIEF;
                o.target = th.legs.getUniqueId();
                o.modeSince = now;
                return;
            }
        }
        if (o.guarding != null) {
            if (!o.guarding.valid()) {
                o.guarding = null;
            } else {
                if (o.dist(o.guarding.loc()) > 3) o.moveTo(o.guarding.legs, 1.0);
                return;
            }
        }
        if (now - o.lastAttack > 700) {
            org.bukkit.entity.Entity foe = foeNear(o);
            if (foe != null) {
                o.mode = FIGHT;
                o.foe = foe.getUniqueId();
                o.modeSince = now;
                return;
            }
        }
        if (!o.moving() && now > o.nextWander) {
            Location g = patrolPoint(o, closest);
            if (g != null) o.moveTo(g, 0.75);
            o.nextWander = now + 5000 + r.nextInt(9000);
        }
        if (!o.moving() && closest != null && cd < 7) o.lookAt(closest.getEyeLocation());
    }

    private Location patrolPoint(Walker o, Player around) {
        Location base = around != null ? around.getLocation() : o.loc();
        if (around == null) {
            Player p = t.nearestPlayer(o.loc(), 80);
            if (p != null) base = p.getLocation();
        }
        for (int i = 0; i < 6; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = 6 + r.nextDouble() * 12;
            Location l = t.surface(base.getWorld(), (int) Math.floor(base.getX() + Math.cos(a) * d), (int) Math.floor(base.getZ() + Math.sin(a) * d), base.getY(), 5);
            if (l != null && t.inPoliceZone(l)) return l;
        }
        return null;
    }

    private void startSearch(Walker o, Player p) {
        o.mode = SEARCH;
        o.target = p.getUniqueId();
        o.modeSince = System.currentTimeMillis();
    }

    private void search(Walker o, long now) {
        Player p = o.target == null ? null : Bukkit.getPlayer(o.target);
        if (p == null || p.getWorld() != o.world() || now - o.modeSince > 20_000 || o.dist(p.getLocation()) > 24) {
            backToPatrol(o);
            return;
        }
        if (t.data(p).stars > 0) {
            pursue(o, p);
            return;
        }
        double dist = o.dist(p.getLocation());
        if (dist > 2.6) {
            o.moveTo(p, 1.1);
            return;
        }
        o.stop();
        o.lookAt(p.getEyeLocation());
        Set<Material> bad = contraband();
        PlayerInventory inv = p.getInventory();
        int found = 0, hot = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null) continue;
            if (bad.contains(it.getType())) {
                found += it.getAmount();
                inv.setItem(i, null);
            } else if (stolen(it)) {
                hot += it.getAmount();
                inv.setItem(i, null);
            }
        }
        backToPatrol(o);
        o.nextWander = now + 3000;
        if (hot > 0) {
            o.say(p, "And what's this? " + hot + " stolen item" + (hot == 1 ? "" : "s") + "! These are evidence now, and you're under arrest.");
            t.commit(p, Crime.THEFT, p.getLocation(), "stolen" + now, null, true);
            t.markDirty();
            if (found == 0) return;
        }
        if (found == 0) {
            o.say(p, "You're clean. Move along, and have a good day.");
            return;
        }
        TownData d = t.data(p);
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, 1f, 0.8f);
        if (!d.warnedContraband) {
            d.warnedContraband = true;
            o.say(p, "Explosives aren't allowed within " + (int) t.policeRadius + " blocks of the fountain. I'm taking these ("
                    + found + "). Consider this your one warning.");
        } else {
            double fine = t.money.take(p, cfg().getDouble("town.contraband-fine", 50));
            o.say(p, "Explosives again? These are confiscated" + (fine > 0 ? " and that's a " + Money.fmt(fine) + " fine" : "") + ".");
        }
        t.commit(p, Crime.CONTRABAND, p.getLocation(), "search" + now, null, true);
        t.markDirty();
    }

    private void chaseThief(Walker o, long now) {
        Walker th = null;
        for (Walker w : t.walkers(Walker.Role.THIEF)) if (w.legs.getUniqueId().equals(o.target)) th = w;
        if (th == null || !th.valid() || th.mode != Townsfolk.ESCAPE || now - o.modeSince > 30_000) {
            backToPatrol(o);
            return;
        }
        double dist = o.dist(th.loc());
        if (dist < 1.8) {
            o.swing();
            t.townsfolk().thiefCaught(th, null, o);
            backToPatrol(o);
            return;
        }
        o.moveTo(th.legs, 1.6);
    }

    private boolean stolen(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(t.stolenKey, org.bukkit.persistence.PersistentDataType.INTEGER);
    }

    /** A hostile mob or bandit this officer can see nearby (MythicDepths bosses are left to the players). */
    /** A monster is attacking someone in town: up to two guards nearby take it on; with none around, one is called in. */
    public void defend(org.bukkit.entity.LivingEntity mob, Location where) {
        if (!cfg().getBoolean("town.officers-fight-mobs", true) || mob.isDead()) return;
        long now = System.currentTimeMillis();
        List<Walker> near = new ArrayList<>();
        for (Walker o : t.walkers(Walker.Role.OFFICER)) {
            if (!o.valid() || o.world() != mob.getWorld() || o.dist(mob.getLocation()) > 40) continue;
            if (o.mode == FIGHT && mob.getUniqueId().equals(o.foe)) return; // already on it
            if (o.mode == PATROL) near.add(o);
        }
        near.sort(java.util.Comparator.comparingDouble(o -> o.dist(mob.getLocation())));
        for (int i = 0; i < Math.min(2, near.size()); i++) {
            Walker o = near.get(i);
            o.mode = FIGHT;
            o.foe = mob.getUniqueId();
            o.modeSince = now;
        }
        if (near.isEmpty() && t.canSpawnWalkers(mob.getWorld()) && t.walkers(Walker.Role.OFFICER).size() < cfg().getInt("town.max-officers", 24)) {
            Player p = t.nearestPlayer(where, 24);
            Location at = p == null ? null : t.spawnSpot(p, 8, 16);
            if (at == null) return;
            Walker o = spawn(at);
            o.mode = FIGHT;
            o.foe = mob.getUniqueId();
            o.modeSince = now;
        }
    }

    private org.bukkit.entity.Entity foeNear(Walker o) {
        org.bukkit.entity.Entity best = null;
        double bd = cfg().getDouble("town.officer-mob-range", 24);
        for (Walker b : t.walkers(Walker.Role.BANDIT)) {
            double d = o.dist(b.loc());
            if (d < bd + 6 && b.valid() && o.legs.hasLineOfSight(b.body)) {
                bd = d;
                best = b.legs;
            }
        }
        for (Walker b : t.walkers(Walker.Role.GANGSTER)) {
            double d = o.dist(b.loc());
            if (d < bd + 6 && b.valid() && o.legs.hasLineOfSight(b.body)) {
                bd = d;
                best = b.legs;
            }
        }
        if (best != null) return best;
        if (!cfg().getBoolean("town.officers-fight-mobs", true)) return null;
        for (org.bukkit.entity.Entity e : o.legs.getNearbyEntities(bd, 6, bd)) {
            if (!(e instanceof org.bukkit.entity.Enemy) || !(e instanceof org.bukkit.entity.LivingEntity le) || le.isDead() || t.isOurs(e)) continue;
            if (e.getPersistentDataContainer().getKeys().stream().anyMatch(k -> k.getNamespace().equals("mythicdepths"))) continue;
            double d = o.dist(e.getLocation());
            if (e instanceof org.bukkit.entity.Mob m && m.getTarget() instanceof Player) d -= 8; // the ones going for someone first
            if (d < bd && o.legs.hasLineOfSight(e)) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    private void fight(Walker o, long now) {
        org.bukkit.entity.Entity e = o.foe == null ? null : Bukkit.getEntity(o.foe);
        Walker bandit = t.walker(e);
        if (e == null || !e.isValid() || e.getWorld() != o.world() || now - o.modeSince > 30_000 || o.dist(e.getLocation()) > 32
                || bandit == null && e instanceof org.bukkit.entity.LivingEntity le && le.isDead()) {
            backToPatrol(o);
            return;
        }
        arm(o, true);
        double dist = o.dist(e.getLocation());
        if (dist > 2.2) o.moveTo(e.getLocation(), 1.35);
        if (dist < 3.0 && now - o.lastAttack > 600) {
            o.lastAttack = now;
            o.lookAt(e.getLocation().add(0, 1.2, 0));
            o.swing();
            if (bandit != null && bandit.role == Walker.Role.BANDIT) t.bandits().hitByWalker(bandit, o, cfg().getDouble("town.officer-mob-damage", 7));
            else if (bandit != null && bandit.role == Walker.Role.GANGSTER) t.npcGangs().hitByWalker(bandit, o, cfg().getDouble("town.officer-mob-damage", 7));
            else if (bandit != null && bandit.role == Walker.Role.PATRON) t.casino().patronHit(bandit, cfg().getDouble("town.officer-mob-damage", 7));
            else if (bandit != null) {
                // anything else of ours: leave it
            }
            else if (e instanceof org.bukkit.entity.LivingEntity le) le.damage(cfg().getDouble("town.officer-mob-damage", 7), o.body);
        }
    }

    /** Send officers to a reported/robbing player: spawn some nearby and radio the rest. */
    public void dispatch(Player p, int n) {
        World w = p.getWorld();
        if (!t.canSpawnWalkers(w)) return;
        int max = cfg().getInt("town.max-officers", 24);
        for (int i = 0; i < n && t.walkers(Walker.Role.OFFICER).size() < max; i++) {
            Location at = t.spawnSpot(p, 10, 20);
            if (at == null) continue;
            Walker o = spawn(at);
            if (t.data(p).stars > 0) pursue(o, p);
        }
        if (t.data(p).stars > 0) radio(p, null);
    }

    public void dispatch(Player p) {
        dispatch(p, 2);
    }

    /** Officers go after a reported bandit. */
    public void hunt(Walker bandit) {
        World w = bandit.world();
        if (!t.canSpawnWalkers(w)) return;
        for (int i = 0; i < 2; i++) {
            Location b = bandit.loc();
            double a = r.nextDouble() * Math.PI * 2;
            Location at = t.surface(w, (int) Math.floor(b.getX() + Math.cos(a) * 14), (int) Math.floor(b.getZ() + Math.sin(a) * 14), b.getY(), 6);
            if (at == null) continue;
            Walker o = spawn(at);
            o.mode = FIGHT;
            o.foe = bandit.legs.getUniqueId();
            o.modeSince = System.currentTimeMillis();
        }
    }

    /** /bribe: 30% of officers take the money and forget everything; the rest add a charge. */
    public void bribe(Player p) {
        TownData d = t.data(p);
        if (d.stars <= 0) {
            p.sendMessage(SmpPlugin.mm("<gray>Nobody is after you. Keep your money."));
            return;
        }
        Walker o = nearest(p.getLocation(), 6);
        if (o == null) {
            p.sendMessage(SmpPlugin.mm("<gray>You'd have to be standing next to a guard."));
            return;
        }
        if (o.bribeTried.contains(p.getUniqueId())) {
            o.say(p, "You already tried that with me.");
            return;
        }
        double cost = d.stars * cfg().getDouble("town.bribe.per-star", 300);
        if (t.money.balance(p) < cost) {
            p.sendMessage(SmpPlugin.mm("<gray>A bribe for " + TownManager.stars(d.stars) + " <gray>would take " + Money.fmt(cost) + ". You don't have it."));
            return;
        }
        o.bribeTried.add(p.getUniqueId());
        o.stop();
        o.lookAt(p.getEyeLocation());
        boolean takes = o.corrupt || t.gangs().hasPerk(p, "bribes") && r.nextDouble() < 0.15 / 0.7;
        if (takes) {
            t.money.take(p, cost);
            t.clearWanted(d);
            forget(p);
            o.say(p, "<gray>*pockets the " + Money.fmt(cost) + "*</gray> I didn't see a thing. Now get lost.");
            if (d.caseOpen) p.sendMessage(SmpPlugin.mm("<dark_gray>(The Inquisition doesn't take bribes. Your case is still open.)"));
        } else {
            o.say(p, "Are you trying to bribe a guard of the law?! That's another charge.");
            t.commit(p, Crime.BRIBERY, p.getLocation(), "bribe" + o.legs.getUniqueId(), null, true);
            d.hostile = true;
            d.orderedAt = 0;
            pursue(o, p);
        }
    }

    // ------------------------------------------------------------------ called by the town

    /** The officer who saw `p` do something at `where`, if any. */
    Walker witness(Player p, Location where) {
        Walker best = null;
        double bd = 1e9;
        for (Walker o : t.walkers(Walker.Role.OFFICER)) {
            if (!o.valid()) continue;
            double d = o.dist(where);
            if (d > t.sight) continue;
            if (d < 6 || o.sees(p)) {
                if (d < bd) {
                    bd = d;
                    best = o;
                }
            }
        }
        return best;
    }

    /** Every officer nearby joins the chase. */
    void radio(Player p, Walker caller) {
        for (Walker o : t.walkers(Walker.Role.OFFICER)) {
            if (o.mode == PURSUE && p.getUniqueId().equals(o.target)) continue;
            if (o.mode == CHASE_THIEF || o.mode == FIGHT && o.dist(p.getLocation()) > 24) continue;
            if (o == caller || o.dist(p.getLocation()) <= 64) pursue(o, p);
        }
    }

    public Walker nearest(Location l, double r) {
        Walker best = null;
        double bd = r;
        for (Walker o : t.walkers(Walker.Role.OFFICER)) {
            double d = o.dist(l);
            if (d <= bd) {
                bd = d;
                best = o;
            }
        }
        return best;
    }

    void forget(Player p) {
        UUID id = p.getUniqueId();
        for (Walker o : t.walkers(Walker.Role.OFFICER)) if (id.equals(o.target)) backToPatrol(o);
    }

    void hit(Walker o, Player p, double damage) {
        o.health -= damage;
        o.hurt();
        TownData d = t.data(p);
        if (t.exempt(p)) return;
        boolean warned = System.currentTimeMillis() - d.lastGraceAt < 30_000;
        if (warned) o.health += damage; // just warned: no harm done
        if (o.health <= 0) {
            o.shout("<red>Guard down! Guard down!", 32);
            t.news(p.getName() + " killed " + o.name.replaceAll("<[^>]+>", "") + " of the Town Watch!");
            t.unregister(o, true);
            t.commit(p, Crime.COP_KILLING, p.getLocation(), "cop" + o.legs.getUniqueId(), null, true);
            return;
        }
        if (t.commit(p, Crime.ASSAULT_OFFICER, p.getLocation(), "cop" + o.legs.getUniqueId(), null, true)) { // a newcomer's warning
            if (!warned) o.health += damage;
            return;
        }
        d.hostile = true;
        d.orderedAt = 0;
        pursue(o, p);
    }

    void clicked(Walker o, Player p) {
        TownData d = t.data(p);
        o.stop();
        o.lookAt(p.getEyeLocation());
        if (d.stars > 0) {
            t.surrender(p);
            return;
        }
        var tier = t.tier(p);
        if (tier == TownManager.Tier.OUTLAW || tier == TownManager.Tier.ROGUE) {
            o.say(p, "I've got my eye on you, " + p.getName() + ". One wrong move...");
        } else if (tier == TownManager.Tier.HERO) {
            o.say(p, "An honour, " + p.getName() + ". The town's safer with you around.");
        } else {
            String[] lines = {
                    "Keep the peace and we'll get along fine. <gray>(/rep shows your record)",
                    "No explosives, no fires, no fighting within " + (int) t.policeRadius + " blocks of the fountain.",
                    "Seen a pickpocket? Catch them and you'll get the money back, plus a reward.",
                    "If we're after you, crouch next to a guard. Running only makes it worse."
            };
            o.say(p, lines[r.nextInt(lines.length)]);
        }
        if ((tier == TownManager.Tier.RESPECTED || tier == TownManager.Tier.HERO) && !d.cop) {
            o.say(p, "We could use someone like you in the Watch. Sign up at the station. <yellow>/police");
        }
    }
}
