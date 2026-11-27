package com.colonysmp.prison;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Skill;
import com.colonysmp.core.TxnType;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Job;
import com.colonysmp.model.Perm;
import com.colonysmp.model.Policies;
import com.colonysmp.model.Trait;
import com.colonysmp.npc.Mover;
import com.colonysmp.npc.Npc;
import com.colonysmp.npc.NpcManager;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Keys;
import com.colonysmp.util.Names;
import com.colonysmp.util.Text;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Travellers: people on the road who stop at a colony's Town Hall looking for a place to live. Each has a trait,
 * some schooling and a trade they know; members can take them in or turn them away. Colonies with a good
 * reputation see more of them, an inn makes them stay longer (and pay for their room), and the recruitment policy
 * decides who may settle. While nobody is around, the administrator takes in suitable newcomers.
 */
public final class TravelerManager implements Listener {

    public enum State { ARRIVING, WAITING, LEAVING }

    public static final class Traveler {
        public final String colonyId;
        public final String name;
        public final Trait trait;
        public final Skill skill;
        public final int skillXp;
        public final int education;
        public final WanderingTrader body;
        final Mover mover;
        public State state = State.ARRIVING;
        long waitUntil, leaveBy;
        boolean hit, lodged;

        Traveler(String colonyId, String name, Trait trait, Skill skill, int skillXp, int education, WanderingTrader body) {
            this.colonyId = colonyId;
            this.name = name;
            this.trait = trait;
            this.skill = skill;
            this.skillXp = skillXp;
            this.education = education;
            this.body = body;
            this.mover = new Mover(body);
        }

        public int level() {
            return Skill.level(skillXp);
        }

        public String describe() {
            return name + " (" + trait.display + ", " + skill.display + " level " + level() + ", education " + education + ")";
        }
    }

    private static final Skill[] TRADES = {Skill.FARMING, Skill.FORESTRY, Skill.MINING, Skill.CONSTRUCTION, Skill.COOKING, Skill.CRAFTING, Skill.SMITHING,
            Skill.HUSBANDRY, Skill.TRADING, Skill.MEDICINE, Skill.TEACHING, Skill.MELEE, Skill.ARCHERY, Skill.FISHING};

    private final ColonySMP plugin;
    private final Map<String, Traveler> byColony = new HashMap<>();
    private final Map<UUID, Traveler> byEntity = new HashMap<>();

    public TravelerManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    public boolean isTraveler(Entity e) {
        return e != null && (byEntity.containsKey(e.getUniqueId()) || e.getPersistentDataContainer().has(Keys.TRAVELER, PersistentDataType.STRING));
    }

    public Traveler of(Colony col) {
        return byColony.get(col.id);
    }

    public Traveler traveler(Entity e) {
        return e == null ? null : byEntity.get(e.getUniqueId());
    }

    public List<Traveler> all() {
        return new ArrayList<>(byEntity.values());
    }

    /** May this traveller settle under the colony's recruitment policy? */
    private static boolean welcome(Colony col, Skill skill, int level, int education) {
        return switch (col.policies.recruitment) {
            case OPEN -> true;
            case LIMITED -> level >= 3 || education >= 40;
            case CLOSED -> false;
        };
    }

    // ───────────── ticking ─────────────

    /** Every tick: walking. */
    public void move(long now) {
        for (Traveler t : new ArrayList<>(byEntity.values())) {
            if (!t.body.isValid()) {
                forget(t);
                continue;
            }
            if (!plugin.prison().isDowned(t.body)) t.mover.tick(plugin.npcs(), now);
        }
    }

    /** Every second: arrivals, waiting, leaving. */
    public void tick() {
        long now = System.currentTimeMillis();
        var s = plugin.settings();
        if (s.travelersEnabled) {
            for (Colony col : plugin.colonies().list()) {
                if (byColony.containsKey(col.id) || col.core == null || !col.active) continue;
                if (col.policies.recruitment == Policies.Recruitment.CLOSED) continue;
                World w = col.world();
                long day = plugin.sim().day(col);
                long t = w.getTime();
                if (col.lastTravelerDay >= day || t < 1000 || t > 9000) continue;
                col.lastTravelerDay = day;
                double chance = col.reputation < 15 ? 0 : s.travelerChance * Math.min(1.6, col.reputation / 50.0)
                        * (1 + col.bonus(com.colonysmp.core.Research.Effect.VISITORS));
                if (ThreadLocalRandom.current().nextDouble() < chance) spawn(col);
            }
        }
        for (Traveler t : new ArrayList<>(byEntity.values())) {
            if (!t.body.isValid()) {
                forget(t);
                continue;
            }
            Colony col = plugin.colonies().get(t.colonyId);
            if (col == null) {
                t.body.remove();
                forget(t);
                continue;
            }
            if (plugin.prison().isDowned(t.body)) continue;
            Location core = col.coreLocation();
            switch (t.state) {
                case ARRIVING -> {
                    if (core == null) {
                        leave(col, t, null);
                        break;
                    }
                    if (t.mover.near(core, 4)) {
                        t.mover.stop();
                        t.state = State.WAITING;
                        Building inn = plugin.sim().citizens().staffed(col, BuildingType.INN);
                        t.waitUntil = now + s.travelerWaitMinutes * 60_000L * (inn != null ? 3 : 1);
                        if (inn != null && !t.lodged) {
                            t.lodged = true;
                            col.treasury.credit(s.lodgingFee, TxnType.SERVICES, t.name + " lodged at " + inn.label(), now, plugin.sim().day(col));
                            col.reputation = Math.min(100, col.reputation + 1);
                        }
                        announce(col, t);
                    } else {
                        t.mover.moveTo(core, s.walkSpeed * 0.85, 3.5);
                    }
                }
                case WAITING -> {
                    if (core != null && !t.mover.near(core, 6)) t.mover.moveTo(core, s.walkSpeed * 0.8, 3.5);
                    if (now > t.waitUntil) leave(col, t, "<gray>" + Text.esc(t.name) + " grew tired of waiting and moved on.");
                }
                case LEAVING -> {
                    if (now > t.leaveBy || col.territory.distanceTo(t.body.getX(), t.body.getZ()) > 8) {
                        t.body.remove();
                        forget(t);
                    }
                }
            }
        }
    }

    private void spawn(Colony col) {
        World w = col.world();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location at = null;
        var tr = col.territory;
        for (int i = 0; i < 12 && at == null; i++) {
            int x, z;
            switch (r.nextInt(4)) {
                case 0 -> {
                    x = tr.minX() - 3;
                    z = r.nextInt(tr.minZ(), tr.maxZ() + 1);
                }
                case 1 -> {
                    x = tr.maxX() + 3;
                    z = r.nextInt(tr.minZ(), tr.maxZ() + 1);
                }
                case 2 -> {
                    z = tr.minZ() - 3;
                    x = r.nextInt(tr.minX(), tr.maxX() + 1);
                }
                default -> {
                    z = tr.maxZ() + 3;
                    x = r.nextInt(tr.minX(), tr.maxX() + 1);
                }
            }
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            if (plugin.colonies().at(w.getName(), x, z) != null) continue;
            int y = w.getHighestBlockYAt(x, z) + 1;
            Location l = Mover.safeSpot(new Location(w, x + 0.5, y, z + 0.5));
            if (l != null && !l.getBlock().isLiquid()) at = l;
        }
        if (at == null) return;
        String name = Names.random();
        Trait trait = Trait.random();
        Skill skill = TRADES[r.nextInt(TRADES.length)];
        int xp = 50 + r.nextInt(900);
        int edu = r.nextInt(plugin.settings().educationMin, plugin.settings().educationMax + 20);
        WanderingTrader body;
        try {
            body = w.spawn(at, WanderingTrader.class, CreatureSpawnEvent.SpawnReason.CUSTOM, wt -> {
                wt.setPersistent(false);
                wt.setRemoveWhenFarAway(false);
                wt.setAware(false);
                wt.setCollidable(false);
                wt.setDespawnDelay(0);
                wt.setCanDrinkMilk(false);
                wt.setCanDrinkPotion(false);
                wt.getPersistentDataContainer().set(Keys.TRAVELER, PersistentDataType.STRING, col.id);
                AttributeInstance fr = wt.getAttribute(Attribute.FOLLOW_RANGE);
                if (fr != null) fr.setBaseValue(96);
                wt.customName(Text.mm("<#c9a26b>🎒 <white>" + name + " <gray>— " + skill.display + " " + Skill.level(xp)));
                wt.setCustomNameVisible(true);
            });
        } catch (IllegalArgumentException e) {
            return;
        }
        if (body == null || !body.isValid()) return;
        Traveler t = new Traveler(col.id, name, trait, skill, xp, Math.min(100, edu), body);
        byColony.put(col.id, t);
        byEntity.put(body.getUniqueId(), t);
        for (Player p : col.onlineMembers()) Text.send(p, "<#c9a26b>A traveller, <white>" + Text.esc(name) + "</white>, is approaching the Town Hall.");
    }

    private void announce(Colony col, Traveler t) {
        boolean ok = welcome(col, t.skill, t.level(), t.education);
        String msg = "<#c9a26b>Traveller <white>" + Text.esc(t.name) + "</white> waits at the Town Hall.</#c9a26b> <gray>" + t.trait.display + " (" + t.trait.description
                + "), " + t.skill.display + " level " + t.level() + ", education " + t.education + ". "
                + (ok ? "<click:run_command:'/colony traveller recruit'><green><bold>[Take in]</bold></green></click> " : "<red>(your recruitment policy turns them away)</red> ")
                + "<click:run_command:'/colony traveller dismiss'><red><bold>[Turn away]</bold></red></click>";
        for (Player p : col.onlineMembers()) {
            Text.send(p, msg);
            Fx.sound(p, "minecraft:entity.wandering_trader.ambient", 0.8f, 1f);
        }
    }

    // ───────────── decisions ─────────────

    public String recruit(Colony col, Player by) {
        if (by != null && !plugin.access().can(by, col, Perm.MANAGE_CITIZENS)) return "You may not take in newcomers for " + Text.esc(col.name) + ".";
        Traveler t = byColony.get(col.id);
        if (t == null || t.state == State.LEAVING || plugin.prison().isDowned(t.body)) return "No traveller is waiting.";
        if (t.state != State.WAITING) return t.name + " hasn't reached the Town Hall yet.";
        if (!welcome(col, t.skill, t.level(), t.education)) return "Your recruitment policy (" + col.policies.recruitment.display + ") doesn't allow " + t.name + " to settle.";
        String why = plugin.sim().population().roomForOne(col);
        if (why != null) return why;
        Location at = t.body.getLocation();
        t.body.remove();
        forget(t);
        Citizen c = settle(col, t.name, t.trait, t.skill, t.skillXp, t.education);
        col.reputation = Math.min(100, col.reputation + plugin.settings().recruitBonus);
        col.addStability(1);
        c.remember(plugin.sim().day(col), "Taken in" + (by == null ? "" : " by " + by.getName()));
        Npc n = col.active ? plugin.npcs().spawn(col, c, at) : null;
        if (n != null) n.body.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0);
        for (Player p : col.onlineMembers()) {
            Text.send(p, "<green>" + Text.esc(c.name) + " has settled in " + Text.esc(col.name) + " as " + Text.a("<white>" + c.job.display) + "</white>.");
        }
        plugin.requestSave();
        return null;
    }

    private Citizen settle(Colony col, String name, Trait trait, Skill skill, int xp, int education) {
        Citizen c = plugin.sim().population().create(col, Job.UNEMPLOYED, false);
        c.name = name;
        c.trait = trait;
        c.education = education;
        c.skills.set(skill, xp);
        plugin.sim().housing().assign(col);
        plugin.sim().jobs().autoAssign(col, c);
        col.log(ColonyEvent.Type.POPULATION, plugin.sim().day(col), name + " (" + trait.display + ", " + skill.display + ") settled in the colony as " + Text.a(c.job.display) + ".");
        return c;
    }

    public String dismiss(Colony col, Player by) {
        Traveler t = byColony.get(col.id);
        if (t == null || t.state == State.LEAVING || plugin.prison().isDowned(t.body)) return "No traveller is waiting.";
        leave(col, t, "<gray>" + Text.esc(by.getName()) + " sent " + Text.esc(t.name) + " on their way.");
        return null;
    }

    private void leave(Colony col, Traveler t, String msg) {
        t.state = State.LEAVING;
        t.leaveBy = System.currentTimeMillis() + 90_000;
        Location here = t.body.getLocation();
        double cx = col.territory.centerX(), cz = col.territory.centerZ();
        double dx = here.getX() - cx, dz = here.getZ() - cz;
        double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        double reach = Math.max(col.territory.maxX() - col.territory.minX(), col.territory.maxZ() - col.territory.minZ()) / 2.0 + 14;
        Location out = Mover.safeSpot(new Location(here.getWorld(), cx + dx / len * reach, here.getY(), cz + dz / len * reach));
        if (out == null) out = Mover.safeSpot(here.clone().add(dx / len * 20, 0, dz / len * 20));
        if (out != null) t.mover.moveTo(out, plugin.settings().walkSpeed, 2);
        if (msg != null) for (Player p : col.onlineMembers()) Text.send(p, msg);
    }

    private void forget(Traveler t) {
        byColony.remove(t.colonyId, t);
        byEntity.remove(t.body.getUniqueId());
    }

    /** A traveller was bound by a colony: word spreads. */
    public void captured(Entity e, Colony captor) {
        Traveler t = byEntity.get(e.getUniqueId());
        if (t == null) return;
        forget(t);
        captor.reputation = Math.max(0, captor.reputation - plugin.settings().capturePenalty);
        for (Player m : captor.onlineMembers()) Text.send(m, "<red>Capturing a traveller cost your colony reputation: " + captor.reputation + "/100. Fewer travellers will visit.");
    }

    /** Daily, for colonies nobody is around to see: a suitable newcomer may settle (if an administrator is at work). */
    public void daily(Colony col, long day) {
        if (!plugin.settings().travelersEnabled || col.active || col.core == null) return;
        if (col.policies.recruitment == Policies.Recruitment.CLOSED || !plugin.sim().jobs().administrated(col)) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double chance = col.reputation < 15 ? 0 : plugin.settings().travelerChance * Math.min(1.6, col.reputation / 50.0) * 0.5;
        if (r.nextDouble() >= chance || plugin.sim().population().roomForOne(col) != null) return;
        Skill skill = TRADES[r.nextInt(TRADES.length)];
        int xp = 50 + r.nextInt(900);
        int edu = Math.min(100, r.nextInt(plugin.settings().educationMin, plugin.settings().educationMax + 20));
        if (!welcome(col, skill, Skill.level(xp), edu)) return;
        Citizen c = settle(col, Names.random(), Trait.random(), skill, xp, edu);
        c.remember(day, "Arrived on the road and was taken in by the administrator");
    }

    // ───────────── violence ─────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Traveler t = byEntity.get(e.getEntity().getUniqueId());
        if (t == null) return;
        Entity src = NpcManager.source(e.getDamager());
        if (!(src instanceof Player p)) return;
        Colony col = plugin.colonies().get(t.colonyId);
        if (col == null || !col.isMember(p.getUniqueId())) return;
        if (!t.hit) {
            t.hit = true;
            col.reputation = Math.max(0, col.reputation - plugin.settings().attackPenalty);
            Text.bar(p, "<red>Attacking a traveller hurts your colony's reputation (" + col.reputation + "/100).");
        }
        if (t.state != State.LEAVING) leave(col, t, null);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Traveler t = byEntity.get(e.getEntity().getUniqueId());
        if (t == null) return;
        e.getDrops().clear();
        Colony col = plugin.colonies().get(t.colonyId);
        Player killer = e.getEntity().getKiller();
        if (col != null && killer != null && col.isMember(killer.getUniqueId())) {
            col.reputation = Math.max(0, col.reputation - plugin.settings().capturePenalty);
            for (Player p : col.onlineMembers()) Text.send(p, "<red>A traveller was killed. Word spreads: reputation " + col.reputation + "/100.");
        }
        forget(t);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEntityEvent e) {
        Traveler t = byEntity.get(e.getRightClicked().getUniqueId());
        if (t == null) {
            if (isTraveler(e.getRightClicked())) {
                e.setCancelled(true);
                e.getRightClicked().remove();
            }
            return;
        }
        if (plugin.prison().isDowned(t.body)) return; // the prison handles binding
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Colony col = plugin.colonies().get(t.colonyId);
        if (col != null && (col.isMember(p.getUniqueId()) || plugin.isBypassing(p))) {
            boolean ok = welcome(col, t.skill, t.level(), t.education);
            Text.send(p, "<#c9a26b>" + Text.esc(t.describe()) + "</#c9a26b> <gray>- " + t.trait.description + " "
                    + (ok ? "<click:run_command:'/colony traveller recruit'><green>[Take in]</green></click> " : "<red>(policy: not welcome)</red> ")
                    + "<click:run_command:'/colony traveller dismiss'><red>[Turn away]</red></click>");
        } else {
            Text.send(p, "<#c9a26b>" + Text.esc(t.name) + "</#c9a26b> <gray>is visiting " + (col == null ? "a colony" : Text.esc(col.name)) + ".");
        }
    }

    public void colonyRemoved(Colony col) {
        Traveler t = byColony.remove(col.id);
        if (t != null) {
            t.body.remove();
            byEntity.remove(t.body.getUniqueId());
        }
    }

    public void shutdown() {
        for (Traveler t : new ArrayList<>(byEntity.values())) t.body.remove();
        byEntity.clear();
        byColony.clear();
    }

    /** Admin: a traveller now. */
    public boolean summon(Colony col) {
        if (byColony.containsKey(col.id) || col.core == null || !col.active) return false;
        spawn(col);
        return byColony.containsKey(col.id);
    }
}
