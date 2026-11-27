package com.colonysmp.raid;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.core.Goods;
import com.colonysmp.core.RaidPlan;
import com.colonysmp.core.Research;
import com.colonysmp.core.TxnType;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Modifier;
import com.colonysmp.model.Perm;
import com.colonysmp.npc.Mover;
import com.colonysmp.npc.Npc;
import com.colonysmp.npc.NpcManager;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Keys;
import com.colonysmp.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Evoker;
import org.bukkit.entity.Husk;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Raider;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Spellcaster;
import org.bukkit.entity.Vindicator;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpellCastEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Raids from the wilds. Once a colony is big enough to be worth robbing, bands of barbarians come for its
 * stores, and now and then the dead rise behind a necromancer. Scouts give warning in the morning; the raid
 * arrives at nightfall and lasts until the raiders are dead or the sun comes up. Barbarians can be bought off.
 *
 * <p>Raiders are ordinary hostile mobs, so the colony's guards, soldiers and militia fight them as they fight
 * any monster. A raid only happens while members are online and the colony is loaded.
 */
public final class RaidManager implements Listener {

    public enum Kind {
        BARBARIANS("Barbarians", "barbarian raid", "raiders"), UNDEAD("The Undead", "undead horde", "undead");

        public final String display, raid, members;

        Kind(String display, String raid, String members) {
            this.display = display;
            this.raid = raid;
            this.members = members;
        }

        public static Kind parse(String s) {
            if (s == null) return null;
            String u = s.trim().toUpperCase(Locale.ROOT);
            if (u.startsWith("BARB") || u.startsWith("PILLAG") || u.startsWith("BANDIT")) return BARBARIANS;
            if (u.startsWith("UNDEAD") || u.startsWith("NECRO") || u.startsWith("ZOMBIE") || u.startsWith("HORDE")) return UNDEAD;
            return null;
        }
    }

    /** A raid in progress. */
    public static final class Raid {
        public final String colonyId;
        public final Kind kind;
        final long day, startedAt;
        final List<Mob> raiders = new ArrayList<>();
        final Map<String, Long> plunder = new TreeMap<>();
        final Set<UUID> viewers = new HashSet<>();
        final Map<UUID, Integer> loads = new HashMap<>();
        BossBar bar;
        Mob leader;
        boolean hadLeader;
        int spawned, slain, raised, citizensLost;
        long nextRaise, lootWarnAt, emptySince;
        int seconds;

        Raid(String colonyId, Kind kind, long day) {
            this.colonyId = colonyId;
            this.kind = kind;
            this.day = day;
            this.startedAt = System.currentTimeMillis();
        }

        public int alive() {
            return raiders.size();
        }
    }

    /** Just as beds become usable, so nobody sleeps through the raid they were warned of. */
    private static final long NIGHTFALL = 12600, TOO_LATE = 21000;
    private final ColonySMP plugin;
    private final Map<String, Raid> active = new HashMap<>();

    public RaidManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    public Raid raid(Colony c) {
        return active.get(c.id);
    }

    public static boolean isRaider(Entity e) {
        return e != null && e.getPersistentDataContainer().has(Keys.RAIDER, PersistentDataType.STRING);
    }

    /** The raiders expected tonight, or null. */
    public Kind expected(Colony c) {
        return c.raidDay >= 0 ? Kind.parse(c.raidKind) : null;
    }

    // ───────────── the day: do raiders set out? ─────────────

    public void daily(Colony c, long day) {
        Settings s = plugin.settings();
        if (c.raidDay >= 0 && c.raidDay < day && !active.containsKey(c.id)) clearPlan(c);
        if (!s.raidsEnabled || c.core == null || c.raidDay >= 0 || active.containsKey(c.id)) return;
        if (c.population() < s.raidMinPopulation || day - c.foundedDay < s.raidGraceDays) return;
        if (plugin.wars().fighting(c) || plugin.wars().warmup(c)) return;
        World w = c.world();
        if (w == null || w.getDifficulty() == Difficulty.PEACEFUL) return;
        // nobody is raided while they're away
        if (!c.anyMemberOnline()) return;
        long since = c.lastRaidDay < 0 ? s.raidMinDays : day - c.lastRaidDay;
        if (ThreadLocalRandom.current().nextDouble() >= RaidPlan.chance(since, s.raidMinDays, s.raidChance)) return;
        plan(c, day, ThreadLocalRandom.current().nextDouble() < s.raidUndeadShare ? Kind.UNDEAD : Kind.BARBARIANS);
    }

    private int size(Colony c) {
        Settings s = plugin.settings();
        return RaidPlan.size(c.population(), c.level(), c.raidsRepelled, c.bonus(Research.Effect.RAID_DEFENCE), s.raidMinSize, s.raidMaxSize);
    }

    /** Scouts report raiders: they'll come at nightfall today. */
    public void plan(Colony c, long day, Kind kind) {
        c.raidDay = day;
        c.raidKind = kind.name();
        c.raidSize = size(c);
        long tribute = RaidPlan.tribute(c.raidSize, plugin.settings().raidTributePerRaider);
        String what = kind == Kind.UNDEAD ? "Graves have been disturbed in the wilds: about " + c.raidSize + " of the dead will rise against " + c.name + " at nightfall."
                : "Scouts report a band of about " + c.raidSize + " barbarians making for " + c.name + ". They will attack at nightfall.";
        c.log(ColonyEvent.Type.MILITARY, day, what);
        for (Player p : c.onlineMembers()) {
            Text.title(p, kind == Kind.UNDEAD ? "<dark_purple>The Dead Are Stirring" : "<red>Raiders Sighted", "<gray>They will come at nightfall", 10, 80, 20);
            Fx.sound(p, "minecraft:block.bell.use", 1f, 0.7f);
            Text.send(p, "<red>" + Text.esc(what) + "</red> <gray>Post guards, arm your soldiers (<white>/colony mobilize</white>) and be here."
                    + (kind == Kind.BARBARIANS && tribute > 0 ? " <click:run_command:'/colony raid tribute'><gold>[Pay them off: " + Text.value(tribute) + "]</gold></click>" : ""));
        }
        plugin.requestSave();
    }

    private static void clearPlan(Colony c) {
        c.raidDay = -1;
        c.raidKind = null;
        c.raidSize = 0;
    }

    /** Buys barbarians off before they arrive. Returns an error, or null. */
    public String tribute(Player by, Colony c) {
        Kind kind = expected(c);
        if (kind == null || active.containsKey(c.id)) return active.containsKey(c.id) ? "It's too late for that: they're here." : "No raiders are expected.";
        if (kind != Kind.BARBARIANS) return "The dead take no tribute.";
        if (by != null && !plugin.access().can(by, c, Perm.TREASURY)) return "You may not spend " + Text.esc(c.name) + "'s treasury.";
        long cost = RaidPlan.tribute(c.raidSize, plugin.settings().raidTributePerRaider);
        long day = plugin.sim().day(c);
        if (c.treasury.debit(cost, TxnType.TRIBUTE, "Tribute to a barbarian band", System.currentTimeMillis(), day) == null) {
            return "They want " + Text.value(cost) + " (the treasury has " + Text.value(c.treasury.balance()) + ").";
        }
        clearPlan(c);
        c.lastRaidDay = day;
        c.addStability(-1);
        c.log(ColonyEvent.Type.MILITARY, day, (by == null ? "The colony" : by.getName()) + " paid the barbarians " + Text.value(cost) + " to stay away.");
        for (Player p : c.onlineMembers()) Text.send(p, "<gold>The barbarians took " + Text.value(cost) + " and turned back.</gold> <gray>They'll remember how easy that was.");
        plugin.requestSave();
        return null;
    }

    // ───────────── every second ─────────────

    public void tick() {
        for (Colony c : plugin.colonies().list()) {
            if (c.raidDay < 0 || active.containsKey(c.id)) continue;
            World w = c.world();
            if (w == null) continue;
            long day = plugin.sim().day(c);
            if (c.raidDay != day) continue; // a stale plan is cleared at the next day change
            long t = w.getTime();
            if (t < NIGHTFALL) continue;
            if (t > TOO_LATE) {
                clearPlan(c);
                for (Player p : c.onlineMembers()) Text.send(p, "<gray>The night passed and the raiders never came.");
                continue;
            }
            // they wait for the colony to be awake: an empty colony isn't worth the walk
            if (!c.active || defenders(c, w).isEmpty()) continue;
            Kind kind = Kind.parse(c.raidKind);
            start(c, kind == null ? Kind.BARBARIANS : kind, c.raidSize > 0 ? c.raidSize : size(c));
        }
        for (Raid r : new ArrayList<>(active.values())) {
            Colony c = plugin.colonies().get(r.colonyId);
            if (c == null) {
                end(r, null);
                continue;
            }
            try {
                update(r, c);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Raid on " + c.name + " failed", ex);
                withdraw(r, c, "The raiders scattered.");
            }
        }
    }

    /** Members close enough to defend the colony: on its land or within a short run of it. */
    private static List<Player> defenders(Colony c, World w) {
        List<Player> out = new ArrayList<>();
        for (Player p : c.onlineMembers()) {
            if (p.getWorld() != w || p.isDead()) continue;
            Location l = p.getLocation();
            if (c.territory.distanceTo(l.getX(), l.getZ()) <= 96) out.add(p);
        }
        return out;
    }

    // ───────────── starting ─────────────

    /** Starts a raid now (also the admin command). Returns an error, or null. */
    public String start(Colony c, Kind kind, int size) {
        if (active.containsKey(c.id)) return c.name + " is already being raided.";
        World w = c.world();
        Location core = c.coreLocation();
        if (w == null || core == null || !c.active) return c.name + " isn't loaded.";
        long day = plugin.sim().day(c);
        clearPlan(c);
        if (w.getDifficulty() == Difficulty.PEACEFUL) return "Raiders can't exist in a peaceful world.";
        c.lastRaidDay = day;
        List<Location> gates = spawnPoints(c, w, core, size > 8 ? 2 : 1);
        if (gates.isEmpty()) {
            for (Player p : c.onlineMembers()) Text.send(p, "<gray>The raiders found no way to reach the colony and gave up.");
            return "No loaded ground around " + c.name + " for raiders to come from.";
        }
        Raid r = new Raid(c.id, kind, day);
        List<RaidPlan.Role> roster = RaidPlan.roster(kind == Kind.UNDEAD, size);
        for (int i = 0; i < roster.size(); i++) {
            Location gate = gates.get(i % gates.size());
            Location at = plugin.npcs().spotNear(gate, 0, 4);
            Mob mob = spawn(c, at, roster.get(i));
            if (mob == null) continue;
            r.raiders.add(mob);
            if (roster.get(i) == RaidPlan.Role.CHIEF || roster.get(i) == RaidPlan.Role.NECROMANCER) {
                r.leader = mob;
                r.hadLeader = true;
            }
        }
        if (r.raiders.isEmpty()) {
            for (Player p : c.onlineMembers()) Text.send(p, "<gray>The raiders turned back before they reached the colony.");
            return "No raiders could be spawned (is the world on peaceful?).";
        }
        r.spawned = r.raiders.size();
        r.nextRaise = System.currentTimeMillis() + 25_000;
        active.put(c.id, r);
        plugin.military().markThreat(c);
        r.bar = BossBar.bossBar(Text.mm(barName(r)), 1f, kind == Kind.UNDEAD ? BossBar.Color.PURPLE : BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        Location gate = gates.get(0);
        String from = direction(core, gate);
        c.log(ColonyEvent.Type.MILITARY, day, (kind == Kind.UNDEAD ? "The dead rose against the colony: " : "Barbarians attacked the colony: ") + r.spawned + " of them, from the " + from + ".");
        for (Player p : c.onlineMembers()) {
            Text.title(p, kind == Kind.UNDEAD ? "<dark_purple>☠ The Dead Walk" : "<dark_red>⚔ Raid!", "<gray>" + r.spawned + " " + kind.members + " from the " + from, 5, 70, 20);
            Fx.sound(p, "minecraft:event.raid.horn", 1f, kind == Kind.UNDEAD ? 0.6f : 1f);
            Text.send(p, (kind == Kind.UNDEAD ? "<dark_purple>The dead are at the gates of " : "<red>Barbarians are attacking ") + Text.esc(c.name) + "!</red> <gray>" + r.spawned + " are coming from the "
                    + from + (kind == Kind.UNDEAD ? ". Kill the Necromancer and the rest will fall." : ". They're after the stores: hold them until sunrise."));
        }
        plugin.requestSave();
        return null;
    }

    private static String direction(Location core, Location from) {
        double dx = from.getX() - core.getX(), dz = from.getZ() - core.getZ();
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? "east" : "west";
        return dz > 0 ? "south" : "north";
    }

    private static boolean ticking(World w, int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        return w.isChunkLoaded(cx, cz) && w.getChunkAt(cx, cz).getLoadLevel() == Chunk.LoadLevel.ENTITY_TICKING;
    }

    /** Places just outside the colony's land where the ground is loaded, or failing that inside its edge. */
    private List<Location> spawnPoints(Colony c, World w, Location core, int groups) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        var tr = c.territory;
        int out = plugin.settings().raidSpawnDistance;
        List<Location> found = new ArrayList<>();
        for (int pass = 0; pass < 3 && found.size() < groups; pass++) {
            // first well outside, then at the border, then just inside it
            int off = pass == 0 ? out : pass == 1 ? 2 : -6;
            for (int i = 0; i < 24 && found.size() < groups; i++) {
                int x, z;
                switch (r.nextInt(4)) {
                    case 0 -> {
                        x = tr.minX() - off;
                        z = r.nextInt(tr.minZ(), tr.maxZ() + 1);
                    }
                    case 1 -> {
                        x = tr.maxX() + off;
                        z = r.nextInt(tr.minZ(), tr.maxZ() + 1);
                    }
                    case 2 -> {
                        z = tr.minZ() - off;
                        x = r.nextInt(tr.minX(), tr.maxX() + 1);
                    }
                    default -> {
                        z = tr.maxZ() + off;
                        x = r.nextInt(tr.minX(), tr.maxX() + 1);
                    }
                }
                if (!ticking(w, x, z)) continue;
                Colony here = plugin.colonies().at(w.getName(), x, z);
                if (here != null && here != c) continue;
                Location l = Mover.safeSpot(new Location(w, x + 0.5, w.getHighestBlockYAt(x, z) + 1, z + 0.5));
                if (l == null || l.getBlock().isLiquid() || l.distanceSquared(core) < 12 * 12) continue;
                // the second band comes from somewhere else
                boolean apart = true;
                for (Location f : found) if (f.distanceSquared(l) < 20 * 20) apart = false;
                if (apart) found.add(l);
            }
        }
        return found;
    }

    private Mob spawn(Colony c, Location at, RaidPlan.Role role) {
        World w = at.getWorld();
        if (w == null) return null;
        Class<? extends Mob> type = switch (role) {
            case BRUTE, CHIEF -> Vindicator.class;
            case ARCHER -> Pillager.class;
            case ZOMBIE -> Zombie.class;
            case HUSK -> Husk.class;
            case SKELETON -> Skeleton.class;
            case NECROMANCER -> Evoker.class;
        };
        Mob mob;
        try {
            mob = w.spawn(at, type, CreatureSpawnEvent.SpawnReason.CUSTOM, m -> dress(m, c, role));
        } catch (IllegalArgumentException e) {
            return null;
        }
        return mob == null || !mob.isValid() ? null : mob;
    }

    private void dress(Mob m, Colony c, RaidPlan.Role role) {
        m.setPersistent(false);
        m.setRemoveWhenFarAway(false);
        m.setCanPickupItems(false);
        m.getPersistentDataContainer().set(Keys.RAIDER, PersistentDataType.STRING, c.id);
        attr(m, Attribute.FOLLOW_RANGE, 48);
        if (m instanceof Raider rd) {
            rd.setCanJoinRaid(false);
            rd.setPatrolLeader(false);
        }
        if (m instanceof Zombie z) {
            z.setAdult();
            z.setShouldBurnInDay(false);
            attr(m, Attribute.SPAWN_REINFORCEMENTS, 0);
        }
        if (m instanceof AbstractSkeleton sk) sk.setShouldBurnInDay(false);
        EntityEquipment eq = m.getEquipment();
        String name = switch (role) {
            case BRUTE -> "<red>Barbarian";
            case ARCHER -> "<red>Barbarian Archer";
            case CHIEF -> "<dark_red><bold>Barbarian Chief";
            case ZOMBIE, HUSK -> "<dark_purple>Risen Dead";
            case SKELETON -> "<dark_purple>Risen Archer";
            case NECROMANCER -> "<dark_purple><bold>Necromancer";
        };
        m.customName(Text.mm(name));
        boolean leader = role == RaidPlan.Role.CHIEF || role == RaidPlan.Role.NECROMANCER;
        m.setCustomNameVisible(leader);
        if (leader) {
            double hp = role == RaidPlan.Role.CHIEF ? 60 : 50;
            attr(m, Attribute.MAX_HEALTH, hp);
            m.setHealth(hp);
            m.setGlowing(true);
        }
        if (role == RaidPlan.Role.CHIEF) {
            eq.setHelmet(new ItemStack(Material.IRON_HELMET));
            eq.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        }
        if (role == RaidPlan.Role.ZOMBIE && ThreadLocalRandom.current().nextInt(3) == 0) eq.setItemInMainHand(new ItemStack(Material.STONE_SWORD));
        // nothing they were given is dropped (their own loot still is)
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST}) {
            try {
                eq.setDropChance(slot, 0f);
            } catch (IllegalArgumentException ignored) {
                // slot not present on this mob
            }
        }
    }

    private static void attr(LivingEntity e, Attribute a, double v) {
        AttributeInstance i = e.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    // ───────────── while it lasts ─────────────

    private void update(Raid r, Colony c) {
        World w = c.world();
        r.seconds++;
        // the fallen, the fled and the captured
        for (Mob mob : new ArrayList<>(r.raiders)) {
            if (!mob.isValid() || mob.isDead()) {
                r.raiders.remove(mob);
            } else if (plugin.prison().isDowned(mob)) {
                // down and at the colony's mercy: out of the fight
                r.raiders.remove(mob);
                r.slain++;
                if (mob == r.leader) leaderFell(r, c, null);
            }
        }
        if (r.raiders.isEmpty()) {
            if (r.slain * 2 >= r.spawned) victory(r, c);
            else withdraw(r, c, "The " + r.kind.members + " melted away into the night.");
            return;
        }
        // barbarians are men, not monsters: with their chief dead and most of the band down, the rest run for it
        if (r.kind == Kind.BARBARIANS && r.hadLeader && r.leader == null && r.spawned >= 5 && r.raiders.size() * 4 <= r.spawned) {
            for (Mob mob : r.raiders) {
                if (!mob.isValid()) continue;
                mob.getWorld().spawnParticle(Particle.LARGE_SMOKE, mob.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.01);
                mob.remove();
            }
            int fled = r.raiders.size();
            r.raiders.clear();
            for (Player p : c.onlineMembers()) Text.send(p, "<gold>The last " + (fled == 1 ? "barbarian breaks and runs" : fled + " barbarians break and run") + ", dropping what they carried.");
            victory(r, c);
            return;
        }
        Settings s = plugin.settings();
        boolean dawn = w == null || plugin.sim().day(c) > r.day;
        boolean late = System.currentTimeMillis() - r.startedAt > s.raidMaxMinutes * 60_000L;
        if (!c.active) {
            withdraw(r, c, "The " + r.kind.members + " found nobody home and moved on.");
            return;
        }
        if (dawn || late) {
            withdraw(r, c, r.kind == Kind.UNDEAD ? "The sun rose and the dead sank back into the earth." : "The barbarians withdrew at first light.");
            return;
        }
        // with nobody there to fight for it, the colony isn't worth the blood
        if (defenders(c, w).isEmpty()) {
            if (r.emptySince == 0) r.emptySince = System.currentTimeMillis();
            else if (System.currentTimeMillis() - r.emptySince > 60_000) {
                withdraw(r, c, "The " + r.kind.members + " moved on.");
                return;
            }
        } else r.emptySince = 0;
        plugin.military().markThreat(c);
        if (r.seconds % 2 == 0) steer(r, c, w);
        if (r.kind == Kind.BARBARIANS && s.raidLoot && r.seconds % 5 == 0) loot(r, c, w);
        if (r.kind == Kind.UNDEAD) raise(r, c, w);
        bar(r, c);
    }

    /** Raiders with nothing to fight make for the colony: its people, its stores, its Town Hall. */
    private void steer(Raid r, Colony c, World w) {
        Location core = c.coreLocation();
        Location goal = r.kind == Kind.BARBARIANS ? plugin.npcs().storagePoint(c, core) : core;
        if (goal == null) goal = core;
        List<LivingEntity> victims = new ArrayList<>();
        for (Npc n : plugin.npcs().of(c)) if (!n.c.downed() && n.body.getWorld() == w) victims.add(n.body);
        for (Player p : defenders(c, w)) if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) victims.add(p);
        // the watch is told where the nearest of them has got to, once they're on (or at the edge of) the land
        if (core != null) {
            Mob nearest = null;
            double nd = Double.MAX_VALUE;
            for (Mob mob : r.raiders) {
                if (mob.getWorld() != w || c.territory.distanceTo(mob.getX(), mob.getZ()) > 2) continue;
                double d = mob.getLocation().distanceSquared(core);
                if (d < nd) {
                    nd = d;
                    nearest = mob;
                }
            }
            if (nearest != null) plugin.military().callWatch(c, nearest.getLocation());
        }
        for (Mob mob : r.raiders) {
            if (mob.getWorld() != w) continue;
            LivingEntity t = mob.getTarget();
            if (t != null && t.isValid() && !t.isDead() && t.getWorld() == w && !plugin.prison().isDowned(t)) continue;
            LivingEntity best = null;
            double bd = 40 * 40;
            Location here = mob.getLocation();
            for (LivingEntity v : victims) {
                double d = v.getLocation().distanceSquared(here);
                if (d < bd) {
                    bd = d;
                    best = v;
                }
            }
            // looters with a load would rather reach the chest than chase a farmer
            if (best != null && (bd < 12 * 12 || r.kind == Kind.UNDEAD || goal == null)) {
                mob.setTarget(best);
            } else if (goal != null && here.distanceSquared(goal) > 9) {
                mob.setTarget(null);
                mob.getPathfinder().moveTo(goal, 1.1);
            }
        }
    }

    /** Barbarians standing at the stores carry off what they can. */
    private void loot(Raid r, Colony c, World w) {
        Location chest = c.chestLocation();
        if (chest == null) return;
        List<Location> points = new ArrayList<>();
        points.add(chest);
        Location near = plugin.npcs().storagePoint(c, chest);
        if (near != null && near != chest) points.add(near);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        boolean took = false;
        for (Mob mob : r.raiders) {
            if (mob.getWorld() != w || r.loads.getOrDefault(mob.getUniqueId(), 0) >= 3) continue;
            boolean at = false;
            for (Location p : points) if (p.getWorld() == w && p.distanceSquared(mob.getLocation()) < 4 * 4) at = true;
            if (!at) continue;
            List<String> goods = new ArrayList<>();
            for (String k : c.storage.contents().keySet()) if (c.storage.available(k) > 0) goods.add(k);
            if (goods.isEmpty()) break;
            String k = goods.get(rnd.nextInt(goods.size()));
            long n = c.storage.take(k, Math.max(1, Math.min(c.storage.available(k), Goods.stack(k) / 2)));
            if (n <= 0) continue;
            r.plunder.merge(k, n, Long::sum);
            r.loads.merge(mob.getUniqueId(), 1, Integer::sum);
            took = true;
            w.spawnParticle(Particle.ANGRY_VILLAGER, mob.getLocation().add(0, 2, 0), 2, 0.2, 0.2, 0.2, 0);
            Fx.sound(mob.getLocation(), "minecraft:block.chest.open", 0.8f, 0.7f);
        }
        long now = System.currentTimeMillis();
        if (took && now - r.lootWarnAt > 20_000) {
            r.lootWarnAt = now;
            for (Player p : c.onlineMembers()) Text.send(p, "<red>Barbarians are looting the stores!</red> <gray>Kill them all to get it back.");
        }
    }

    /** The necromancer keeps raising the dead until someone stops them. */
    private void raise(Raid r, Colony c, World w) {
        Mob boss = r.leader;
        long now = System.currentTimeMillis();
        if (boss == null || !boss.isValid() || boss.isDead() || now < r.nextRaise) return;
        r.nextRaise = now + 20_000;
        if (r.raised >= r.spawned / 2 + 2 || r.raiders.size() >= r.spawned + 4) return;
        Location at = boss.getLocation();
        Fx.sound(at, "minecraft:entity.evoker.prepare_summon", 1.2f, 0.7f);
        for (int i = 0; i < 2; i++) {
            Location l = plugin.npcs().spotNear(at, 1, 4);
            Mob mob = spawn(c, l, RaidPlan.Role.ZOMBIE);
            if (mob == null) continue;
            r.raiders.add(mob);
            r.spawned++;
            r.raised++;
            w.spawnParticle(Particle.SOUL, l.clone().add(0, 0.5, 0), 20, 0.3, 0.6, 0.3, 0.02);
        }
    }

    private String barName(Raid r) {
        boolean undead = r.kind == Kind.UNDEAD;
        return (undead ? "<dark_purple>☠ Undead horde" : "<red>⚔ Barbarian raid") + " <gray>- <white>" + r.raiders.size() + "</white> left"
                + (r.plunder.isEmpty() ? "" : " <gray>· <gold>stores looted");
    }

    private void bar(Raid r, Colony c) {
        if (r.bar == null) return;
        r.bar.name(Text.mm(barName(r)));
        r.bar.progress((float) Math.max(0, Math.min(1, r.raiders.size() / (double) Math.max(1, r.spawned))));
        Set<UUID> want = new HashSet<>();
        for (Player p : c.onlineMembers()) want.add(p.getUniqueId());
        for (UUID u : new HashSet<>(r.viewers)) {
            if (want.contains(u)) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.hideBossBar(r.bar);
            r.viewers.remove(u);
        }
        for (UUID u : want) {
            if (!r.viewers.add(u)) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.showBossBar(r.bar);
        }
    }

    // ───────────── how it ends ─────────────

    private void victory(Raid r, Colony c) {
        long day = plugin.sim().day(c);
        Settings s = plugin.settings();
        // what they took is still on them
        long recovered = 0;
        for (Map.Entry<String, Long> e : r.plunder.entrySet()) {
            c.storage.force(e.getKey(), e.getValue());
            recovered += e.getValue();
        }
        long bounty = RaidPlan.bounty(r.slain, s.raidBounty);
        if (bounty > 0) c.treasury.credit(bounty, TxnType.REWARDS, "Spoils taken from " + r.kind.members, System.currentTimeMillis(), day);
        c.raidsRepelled++;
        c.addStability(3);
        c.reputation = Math.min(100, c.reputation + 3);
        c.addModifier(new Modifier("raid", r.kind == Kind.UNDEAD ? "The dead were laid to rest" : "Raiders driven off", 5, day + 2));
        c.log(ColonyEvent.Type.MILITARY, day, "The colony " + (r.kind == Kind.UNDEAD ? "destroyed the undead horde" : "drove off the barbarians") + ": " + r.slain + " " + r.kind.members + " fell"
                + (r.citizensLost > 0 ? ", and " + r.citizensLost + " of its own" : "") + ". " + Text.value(bounty) + " taken as spoils.");
        for (Player p : c.onlineMembers()) {
            Text.title(p, "<gold>Victory!", "<gray>" + Text.esc(c.name) + " stands", 10, 70, 20);
            Fx.sound(p, "minecraft:ui.toast.challenge_complete", 1f, 1f);
            Text.send(p, "<gold>The " + r.kind.raid + " is beaten!</gold> <gray>" + r.slain + " fell. Spoils: <gold>" + Text.value(bounty) + "</gold> to the treasury"
                    + (recovered > 0 ? ", and the looted goods are back in storage" : "") + ". The colony will sleep easier for it.");
        }
        end(r, c);
        plugin.milestones().check(c);
        plugin.requestSave();
    }

    private void withdraw(Raid r, Colony c, String how) {
        long day = plugin.sim().day(c);
        for (Mob mob : r.raiders) {
            if (!mob.isValid()) continue;
            mob.getWorld().spawnParticle(Particle.LARGE_SMOKE, mob.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.01);
            mob.remove();
        }
        long taken = 0;
        for (long n : r.plunder.values()) taken += n;
        boolean lost = taken > 0 || r.citizensLost > 0 && r.slain * 2 < r.spawned;
        if (lost) {
            c.raidsLost++;
            c.addStability(-4);
            c.addModifier(new Modifier("raid", r.kind == Kind.UNDEAD ? "Ravaged by the dead" : "Plundered by raiders", -5, day + 2));
            c.log(ColonyEvent.Type.MILITARY, day, "The " + r.kind.raid + " got away" + (taken > 0 ? " with " + taken + " goods from the stores" : "")
                    + (r.citizensLost > 0 ? (taken > 0 ? " and" : "") + " having killed " + r.citizensLost + " citizen" + (r.citizensLost == 1 ? "" : "s") : "") + ".");
        } else {
            c.addStability(1);
            c.log(ColonyEvent.Type.MILITARY, day, "The colony held out against the " + r.kind.raid + " (" + r.slain + " of " + r.spawned + " " + r.kind.members + " fell).");
        }
        for (Player p : c.onlineMembers()) {
            Text.send(p, "<gray>" + how + (lost ? " <red>They took " + (taken > 0 ? taken + " goods from the stores" : "lives") + " with them." : " <green>The colony held."));
            Fx.sound(p, lost ? "minecraft:entity.villager.no" : "minecraft:entity.villager.celebrate", 1f, 1f);
        }
        end(r, c);
        plugin.requestSave();
    }

    private void end(Raid r, Colony c) {
        active.remove(r.colonyId, r);
        if (r.bar != null) {
            for (UUID u : r.viewers) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.hideBossBar(r.bar);
            }
        }
        r.viewers.clear();
    }

    public void colonyRemoved(Colony c) {
        Raid r = active.get(c.id);
        if (r == null) return;
        for (Mob mob : r.raiders) if (mob.isValid()) mob.remove();
        end(r, c);
    }

    public void shutdown() {
        for (Raid r : new ArrayList<>(active.values())) {
            for (Mob mob : r.raiders) if (mob.isValid()) mob.remove();
            end(r, null);
        }
        active.clear();
    }

    /** For the status command. */
    public List<String> describe(Colony c) {
        List<String> out = new ArrayList<>();
        Raid r = active.get(c.id);
        if (r != null) {
            out.add("<red>Under attack: " + r.kind.raid + ", " + r.raiders.size() + " of " + r.spawned + " " + r.kind.members + " left.");
        } else if (c.raidDay >= 0) {
            Kind k = Kind.parse(c.raidKind);
            out.add("<red>Expected at nightfall: " + (k == Kind.UNDEAD ? "the undead" : "barbarians") + ", about " + c.raidSize + " of them.");
            if (k != Kind.UNDEAD) {
                out.add("<gray>They can be paid off for <gold>" + Text.value(RaidPlan.tribute(c.raidSize, plugin.settings().raidTributePerRaider))
                        + "</gold>: <click:run_command:'/colony raid tribute'><yellow>[Pay tribute]</yellow></click>");
            }
        } else if (!plugin.settings().raidsEnabled) {
            out.add("<gray>Raids are turned off on this server.");
        } else if (c.population() < plugin.settings().raidMinPopulation) {
            out.add("<gray>The colony is too small to tempt raiders yet (they come once it has " + plugin.settings().raidMinPopulation + " citizens).");
        } else {
            out.add("<gray>The scouts report nothing. Raiders come at night, and only while members are here.");
        }
        out.add("<gray>Raids driven off: <white>" + c.raidsRepelled + "</white> · lost: <white>" + c.raidsLost + "</white>"
                + (c.lastRaidDay >= 0 ? " · last on day " + c.lastRaidDay : ""));
        double def = c.bonus(Research.Effect.RAID_DEFENCE);
        out.add("<gray>A raid now would bring about <white>" + size(c) + "</white> raiders" + (def > 0 ? " (the watch system turns " + Math.round(def * 100) + "% away)" : "") + ".");
        return out;
    }

    // ───────────── events ─────────────

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        String id = dead.getPersistentDataContainer().get(Keys.RAIDER, PersistentDataType.STRING);
        if (id == null) {
            // one of the colony's own, killed by a raider
            if (!NpcManager.isBody(dead) || !(dead.getLastDamageCause() instanceof EntityDamageByEntityEvent by)) return;
            Entity src = NpcManager.source(by.getDamager());
            String raided = src == null ? null : src.getPersistentDataContainer().get(Keys.RAIDER, PersistentDataType.STRING);
            Raid r = raided == null ? null : active.get(raided);
            if (r != null) r.citizensLost++;
            return;
        }
        Raid r = active.get(id);
        if (r == null || !r.raiders.remove(dead)) return;
        r.slain++;
        if (dead == r.leader) leaderFell(r, plugin.colonies().get(id), e.getDrops());
        else if (ThreadLocalRandom.current().nextInt(5) == 0) e.getDrops().add(new ItemStack(Material.EMERALD));
    }

    /** The chief or the necromancer is dead (or down): their loot falls, and the dead lose what held them up. */
    private void leaderFell(Raid r, Colony c, List<ItemStack> drops) {
        r.leader = null;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (drops != null) {
            drops.add(new ItemStack(Material.EMERALD, 3 + rnd.nextInt(4)));
            if (r.kind == Kind.UNDEAD) {
                drops.add(new ItemStack(Material.LAPIS_LAZULI, 4 + rnd.nextInt(6)));
                drops.add(new ItemStack(Material.BOOK, 1 + rnd.nextInt(2)));
            } else {
                drops.add(new ItemStack(Material.IRON_INGOT, 2 + rnd.nextInt(4)));
            }
        }
        if (r.kind != Kind.UNDEAD) {
            if (c != null) for (Player p : c.onlineMembers()) Text.send(p, "<gold>The Barbarian Chief has fallen!</gold> <gray>The rest fight on without him.");
            return;
        }
        // the spell is broken: the dead fall where they stand
        int fell = 0;
        for (Mob mob : new ArrayList<>(r.raiders)) {
            if (!mob.isValid() || mob.isDead()) continue;
            mob.getWorld().spawnParticle(Particle.SOUL, mob.getLocation().add(0, 1, 0), 15, 0.3, 0.6, 0.3, 0.03);
            // counted here: their own deaths then find them already gone from the roll
            r.raiders.remove(mob);
            r.slain++;
            fell++;
            mob.setHealth(0);
        }
        if (c != null) for (Player p : c.onlineMembers()) Text.send(p, "<dark_purple>The Necromancer has fallen!</dark_purple>" + (fell > 0 ? " <gray>The " + fell + " dead still standing crumble." : ""));
    }

    /** Nobody sleeps through a raid, or through the night one is expected. */
    @EventHandler(ignoreCancelled = true)
    public void onBed(PlayerBedEnterEvent e) {
        Player p = e.getPlayer();
        Colony c = plugin.colonies().of(p);
        if (c == null || c.world() != p.getWorld()) return;
        boolean now = active.containsKey(c.id);
        if (!now && (c.raidDay < 0 || c.raidDay != plugin.sim().day(c))) return;
        // someone far from home may sleep: the raiders aren't coming for them
        if (c.territory.distanceTo(p.getLocation().getX(), p.getLocation().getZ()) > 160) return;
        e.setCancelled(true);
        Text.bar(p, now ? "<red>You can't sleep while raiders are at the gates." : "<red>Raiders are expected tonight: there'll be no sleeping.");
    }

    /** The necromancer raises the dead, not vexes. */
    @EventHandler(ignoreCancelled = true)
    public void onSpell(EntitySpellCastEvent e) {
        if (e.getSpell() == Spellcaster.Spell.SUMMON_VEX && isRaider(e.getEntity())) e.setCancelled(true);
    }

    /** Raiders aren't saved with the world; any that were (a crash) are removed when their chunk loads. */
    @EventHandler
    public void onLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            String id = en.getPersistentDataContainer().get(Keys.RAIDER, PersistentDataType.STRING);
            if (id == null) continue;
            Raid r = active.get(id);
            if (r == null || !r.raiders.contains(en)) en.remove();
        }
    }
}
