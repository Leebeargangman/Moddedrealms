package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.core.GameTime;
import com.colonysmp.core.Goods;
import com.colonysmp.gui.CitizenMenu;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Job;
import com.colonysmp.model.LifeStage;
import com.colonysmp.model.Status;
import com.colonysmp.sim.CitizenSim;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.ItemGoods;
import com.colonysmp.util.Keys;
import com.colonysmp.util.Text;
import com.colonysmp.util.Tools;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * The citizens who are in the world in body. A colony's people walk around while its Town Hall is loaded, up to
 * a body budget per colony and for the whole server; who gets a body goes by priority (soldiers in danger,
 * workers whose fields are loaded, people near players). Everyone else is simulated in the background from the
 * same record, so a body appearing or vanishing never changes what a citizen has or knows. Bodies are never
 * saved with the world (the database is the record), so there are no duplicates after a crash.
 */
public final class NpcManager implements Listener {

    public enum Phase { WORK, LEISURE, NIGHT }

    private final ColonySMP plugin;
    private final Map<UUID, Npc> byCitizen = new HashMap<>();
    private final Map<UUID, Npc> byEntity = new HashMap<>();
    private final CitizenBrain brain;
    private final Chatter chatter;
    private int pathBudget;
    /** Players who attacked a colony's people recently: colony id -> player -> until (ms). */
    private final Map<String, Map<UUID, Long>> aggressors = new HashMap<>();
    /** Farm tiles and trees being worked, so two workers don't pick the same one. */
    final Map<String, Set<BlockPos>> claimed = new HashMap<>();
    private final Map<String, CitizenSim.Context> contexts = new HashMap<>();
    private final Map<String, Long> contextAt = new HashMap<>();
    /** Bodies wanted per colony at the last activity check (admin view). */
    private final Map<String, Integer> wanted = new HashMap<>();

    public NpcManager(ColonySMP plugin) {
        this.plugin = plugin;
        this.brain = new CitizenBrain(plugin, this);
        this.chatter = new Chatter(plugin, this);
    }

    public Chatter chatter() {
        return chatter;
    }

    public CitizenBrain brain() {
        return brain;
    }

    public ColonySMP plugin() {
        return plugin;
    }

    // ───────────── lookups ─────────────

    public Npc npc(Citizen c) {
        Npc n = c == null ? null : byCitizen.get(c.id);
        return n != null && n.valid() ? n : null;
    }

    public Npc npc(Entity e) {
        if (e == null) return null;
        Npc n = byEntity.get(e.getUniqueId());
        return n != null && n.valid() ? n : null;
    }

    public Collection<Npc> all() {
        return byCitizen.values();
    }

    public List<Npc> of(Colony col) {
        List<Npc> out = new ArrayList<>();
        for (Npc n : byCitizen.values()) if (n.valid() && col.id.equals(n.c.colonyId)) out.add(n);
        return out;
    }

    public int count(Colony col) {
        int n = 0;
        for (Npc x : byCitizen.values()) if (col.id.equals(x.c.colonyId)) n++;
        return n;
    }

    public int wantedBodies(Colony col) {
        return wanted.getOrDefault(col.id, 0);
    }

    public static boolean isBody(Entity e) {
        return e != null && e.getPersistentDataContainer().has(Keys.CITIZEN, PersistentDataType.STRING);
    }

    /** Has this citizen a body right now (then the background simulation leaves them alone)? */
    public boolean embodied(Citizen c) {
        return npc(c) != null;
    }

    /** Is someone in body working at this building (then its work and hauling happen in the world)? */
    public boolean worked(Colony col, Building b) {
        for (Npc n : byCitizen.values()) if (n.c.workplace == b.id && col.id.equals(n.c.colonyId) && n.valid() && n.c.status.free()) return true;
        return false;
    }

    /** Couriers in body: if there are none, workers carry their own goods. */
    public boolean hasCourier(Colony col) {
        for (Npc n : byCitizen.values()) {
            if (!col.id.equals(n.c.colonyId) || !n.c.status.free()) continue;
            if (n.c.job == Job.WAREHOUSE_WORKER || n.c.job == Job.FOOD_DISTRIBUTOR) return true;
        }
        return false;
    }

    /** At most a handful of path searches per tick, so a crowd never causes a lag spike. */
    boolean pathBudget() {
        if (pathBudget <= 0) return false;
        pathBudget--;
        return true;
    }

    // ───────────── time and shared state ─────────────

    public Phase phase(Colony col, World w) {
        long t = w.getTime();
        long start = col.policies.hours.start, end = col.policies.hours.end, bed = plugin.settings().eveningEnd;
        if (GameTime.within(t, start, end)) return Phase.WORK;
        if (GameTime.within(t, end, bed)) return Phase.LEISURE;
        return Phase.NIGHT;
    }

    /** The colony's shared context, refreshed every few seconds. */
    public CitizenSim.Context context(Colony col) {
        long now = plugin.tick();
        Long at = contextAt.get(col.id);
        CitizenSim.Context ctx = contexts.get(col.id);
        if (ctx == null || at == null || now - at > 100) {
            ctx = plugin.sim().citizens().context(col);
            contexts.put(col.id, ctx);
            contextAt.put(col.id, now);
        }
        return ctx;
    }

    public double efficiency(Colony col, Citizen c) {
        return plugin.sim().citizens().efficiency(col, c, context(col));
    }

    /** Ticks between actions for work that doesn't use a tool. */
    public long interval(double seconds, double eff) {
        if (eff <= 0.01) return Long.MAX_VALUE / 4;
        return Math.max(4, Math.round(seconds * 20 / eff));
    }

    public long interval(double seconds, double eff, ItemStack tool) {
        if (eff <= 0.01 || Tools.speed(tool) <= 0.01) return Long.MAX_VALUE / 4;
        return Math.max(4, Math.round(seconds * 20 / (eff * Tools.speed(tool))));
    }

    // ───────────── storage on foot ─────────────

    /** Where central storage can be reached: the Town Hall chest, warehouses and granaries. Nearest first. */
    public Location storagePoint(Colony col, Location near) {
        World w = col.world();
        if (w == null) return null;
        Location best = col.chestLocation();
        double bd = best == null || near == null || best.getWorld() != near.getWorld() ? Double.MAX_VALUE : best.distanceSquared(near);
        for (Building b : col.buildings.values()) {
            if (!b.valid || b.type != BuildingType.WAREHOUSE && b.type != BuildingType.GRANARY) continue;
            Location l = place(w, b);
            if (l == null) continue;
            double d = near == null ? 0 : l.distanceSquared(near);
            if (d < bd) {
                bd = d;
                best = l;
            }
        }
        return best;
    }

    /** Where to go to reach a building's stock: a container, a station, or the anchor. */
    public Location place(World w, Building b) {
        BlockPos p = !b.containers.isEmpty() ? b.containers.get(0) : !b.stations.isEmpty() ? b.stations.get(0) : b.anchor;
        return p == null ? null : p.center(w);
    }

    public boolean atStorage(Npc n, Colony col) {
        Location s = storagePoint(col, n.loc());
        return s != null && n.mover.near(s, 2.8);
    }

    /** Walks to the nearest storage point; true once there. */
    public boolean goToStorage(Npc n, Colony col, String label) {
        Location s = storagePoint(col, n.loc());
        if (s == null) return true;
        if (n.mover.near(s, 2.8)) {
            n.mover.stop();
            return true;
        }
        n.mover.moveTo(s, plugin.settings().walkSpeed, 2.2);
        n.doing(label);
        return false;
    }

    /**
     * Puts real items (harvests, drops, a catch) into a workplace's stock, or central storage if there's no
     * workplace; equipment goes to the armoury; whatever has no room is dropped where the worker stands.
     */
    public void deposit(Colony col, Building into, Collection<ItemStack> items, Location dropAt) {
        if (items == null || items.isEmpty()) return;
        List<ItemStack> left = new ArrayList<>();
        long made = 0;
        for (ItemStack it : items) {
            if (it == null || it.getType().isAir() || it.getAmount() <= 0) continue;
            String good = ItemGoods.goodOf(it);
            if (good == null) {
                if (!col.equipment.add(it.clone())) left.add(it);
                continue;
            }
            long n = it.getAmount();
            long added = into != null ? into.stock.add(good, n) : 0;
            if (added < n) added += col.storage.add(good, n - added);
            made += added;
            if (added < n) {
                ItemStack rest = it.clone();
                rest.setAmount((int) (n - added));
                left.add(rest);
            }
        }
        col.producedToday += made;
        if (left.isEmpty()) return;
        World w = dropAt == null ? null : dropAt.getWorld();
        if (w != null) for (ItemStack it : left) w.dropItemNaturally(dropAt, it);
        long now = System.currentTimeMillis();
        if (now - col.lastFullWarn > 300_000) {
            col.lastFullWarn = now;
            for (Player p : col.onlineMembers()) {
                Text.send(p, "<red>Storage is full! Workers are dropping goods where they stand. Build or upgrade a warehouse or granary.");
            }
        }
    }

    /** Makes sure a worker holds the right kind of tool. Returns false while walking to storage for one. */
    public boolean ensureTool(Npc n, Colony col, Tools.Kind kind, long now) {
        Citizen c = n.c;
        if (c.tool != null && Tools.kind(c.tool) != kind) {
            col.equipment.force(c.tool);
            c.tool = null;
            showHand(n);
        }
        if (c.tool != null) return true;
        if (now < n.noToolUntil) return true;
        if (!col.equipment.has(it -> Tools.kind(it) == kind)) {
            n.noToolUntil = now + 1200;
            return true;
        }
        if (!goToStorage(n, col, "Fetching a " + Text.nice(kind.name()).toLowerCase() + " from storage")) return false;
        ItemStack best = col.equipment.takeBest(it -> Tools.kind(it) == kind, Tools::score);
        if (best != null) {
            c.tool = best;
            showHand(n);
            Fx.sound(n.loc(), "minecraft:block.chest.open", 0.5f, 1.1f);
        } else {
            n.noToolUntil = now + 1200;
        }
        return true;
    }

    /**
     * For work that can't be done bare-handed: fetches the tool from the stores, and if there is none the worker
     * waits at the workplace saying what is missing. Tools never appear from nowhere: one has to be put in
     * storage, handed to the citizen, or made by a blacksmith or workshop. True when they hold the tool.
     */
    public boolean needTool(Npc n, Colony col, Building at, Tools.Kind kind, long now) {
        if (!ensureTool(n, col, kind, now)) return false;
        if (n.c.tool != null || !plugin.settings().requireTools) return true;
        String what = Text.nice(kind.name()).toLowerCase();
        n.working = false;
        brain.wanderAt(n, col, at, now, "Needs " + Text.a(what) + ": put one in storage, hand one over, or have one made");
        return false;
    }

    /** Extra produce from working with a good tool (see {@link Tools#yield}): adds to the stacks in place. */
    public void toolBonus(Npc n, Collection<ItemStack> drops) {
        double more = Tools.yield(n.c.tool);
        if (more <= 0) return;
        for (ItemStack it : drops) {
            if (it == null || it.getType().isAir() || ItemGoods.goodOf(it) == null) continue;
            double extra = it.getAmount() * more;
            int add = (int) extra;
            if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < extra - add) add++;
            if (add > 0) it.setAmount(Math.min(it.getMaxStackSize(), it.getAmount() + add));
        }
    }

    /**
     * A member hands a citizen the tool in their hand (sneak + right-click). The citizen takes it if their job
     * uses that kind of tool and it is better than what they carry; the old one comes back to the player.
     */
    private boolean handOver(Player p, Npc n, Colony col, ItemStack hand) {
        if (!p.isSneaking() || hand == null || hand.getType().isAir()) return false;
        Tools.Kind kind = Tools.kind(hand);
        if (kind == Tools.Kind.OTHER || n.c.job.tool != kind || !n.c.status.free()) return false;
        if (!plugin.access().can(p, col, com.colonysmp.model.Perm.MANAGE_JOBS)) {
            Text.bar(p, "<red>You may not equip " + Text.esc(col.name) + "'s citizens.");
            return true;
        }
        ItemStack old = n.c.tool;
        if (old != null && Tools.kind(old) == kind && Tools.score(old) >= Tools.score(hand)) {
            Text.bar(p, "<gray>" + Text.esc(n.c.name) + " already has a better " + Text.nice(kind.name()).toLowerCase() + ".");
            return true;
        }
        ItemStack given = hand.clone();
        given.setAmount(1);
        hand.setAmount(hand.getAmount() - 1);
        n.c.tool = given;
        if (old != null) {
            for (ItemStack left : p.getInventory().addItem(old).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
        n.noToolUntil = 0;
        showHand(n);
        Fx.sound(n.loc(), "minecraft:entity.item.pickup", 0.7f, 1f);
        Text.bar(p, "<green>" + Text.esc(n.c.name) + " takes the " + Text.nice(given.getType().name()).toLowerCase() + ".");
        plugin.requestSave();
        return true;
    }

    public enum Fetch { READY, FETCHING, NONE }

    /**
     * Makes sure the citizen carries some of a good (the first of the keys that storage has). If not, they walk
     * to storage and take up to {@code amount}. NONE means storage has none either.
     */
    public Fetch fetch(Npc n, Colony col, List<String> keys, long amount, String label) {
        for (String k : keys) if (n.c.carried.get(k) > 0) return Fetch.READY;
        String pick = null;
        for (String k : keys) {
            if (col.storage.available(k) > 0) {
                pick = k;
                break;
            }
        }
        if (pick == null) return Fetch.NONE;
        if (!goToStorage(n, col, "Fetching " + label + " from storage")) return Fetch.FETCHING;
        // put back what they won't need
        for (Map.Entry<String, Long> e : n.c.carried.snapshot().entrySet()) {
            if (keys.contains(e.getKey())) continue;
            long t = n.c.carried.take(e.getKey(), e.getValue());
            col.storage.force(e.getKey(), t);
        }
        long want = Math.min(amount, n.c.carried.room(pick));
        long got = col.storage.take(pick, want);
        long added = n.c.carried.add(pick, got);
        if (added < got) col.storage.force(pick, got - added);
        Fx.sound(n.loc(), "minecraft:block.chest.open", 0.5f, 1.1f);
        return n.c.carried.get(pick) > 0 ? Fetch.READY : Fetch.NONE;
    }

    /** Walks leftover supplies back to storage. Returns true while doing so. */
    public boolean returnCarried(Npc n, Colony col) {
        if (n.c.carried.isEmpty() || plugin.sim().logistics().current(n.c) != null) return false;
        if (!goToStorage(n, col, "Returning supplies to storage")) return true;
        plugin.sim().logistics().dumpCarried(col, n.c);
        return false;
    }

    /** Wears the citizen's tool; if it breaks, they fetch a new one. */
    public void wearTool(Npc n, Colony col) {
        if (n.c.tool == null) return;
        if (Tools.wear(n.c.tool, 1)) {
            String what = Text.nice(n.c.tool.getType().name());
            n.c.tool = null;
            n.noToolUntil = 0;
            showHand(n);
            Fx.sound(n.loc(), "minecraft:entity.item.break", 0.8f, 1f);
            n.body.getWorld().spawnParticle(Particle.SMOKE, n.loc().add(0, 1.2, 0), 8, 0.2, 0.2, 0.2, 0.01);
            for (Player p : col.onlineMembers()) {
                if (p.getWorld() == n.body.getWorld() && p.getLocation().distanceSquared(n.loc()) < 48 * 48) {
                    Text.bar(p, "<gray>" + Text.esc(n.c.name) + "'s " + what + " broke. <white>Fetching a replacement.");
                }
            }
        }
    }

    // ───────────── bodies ─────────────

    /** The Town Hall is loaded with entities ticking: the colony is "active" and its people walk about. */
    public boolean active(Colony col) {
        World w = col.world();
        if (w == null || col.core == null) return false;
        int cx = col.core.x() >> 4, cz = col.core.z() >> 4;
        if (!w.isChunkLoaded(cx, cz)) return false;
        return w.getChunkAt(cx, cz).getLoadLevel() == Chunk.LoadLevel.ENTITY_TICKING;
    }

    static boolean ticking(World w, Location l) {
        int cx = l.getBlockX() >> 4, cz = l.getBlockZ() >> 4;
        return w.isChunkLoaded(cx, cz) && w.getChunkAt(cx, cz).getLoadLevel() == Chunk.LoadLevel.ENTITY_TICKING;
    }

    /** Every second: who should have a body, and spawning or removing bodies to match. */
    public void activityLoop() {
        Settings s = plugin.settings();
        int budget = s.maxBodiesTotal;
        List<Colony> colonies = new ArrayList<>(plugin.colonies().list());
        // colonies with players in them get bodies first
        colonies.sort(Comparator.comparingInt((Colony c) -> c.anyMemberOnline() ? 0 : 1));
        for (Colony col : colonies) {
            boolean active = active(col);
            if (active != col.active) {
                col.active = active;
                col.activeSince = System.currentTimeMillis();
                if (active) plugin.sim().step(col);
            }
            if (!active) {
                wanted.remove(col.id);
                for (Npc n : new ArrayList<>(byCitizen.values())) {
                    if (col.id.equals(n.c.colonyId) && !plugin.prison().escorted(n)) despawn(n);
                }
                continue;
            }
            int cap = Math.min(s.maxBodiesPerColony, Math.max(0, budget));
            List<Citizen> want = chosen(col, cap);
            wanted.put(col.id, want.size());
            budget -= want.size();
            Set<UUID> keep = new HashSet<>();
            for (Citizen c : want) keep.add(c.id);
            for (Npc n : new ArrayList<>(byCitizen.values())) {
                if (!col.id.equals(n.c.colonyId)) continue;
                if (!n.valid()) {
                    lost(n);
                    continue;
                }
                if (!keep.contains(n.c.id) && !plugin.prison().escorted(n)) despawn(n);
            }
            int spawned = 0;
            for (Citizen c : want) {
                if (spawned >= 6) break;
                if (npc(c) != null) continue;
                Npc old = byCitizen.get(c.id);
                if (old != null) lost(old);
                // bring the record up to the present before it walks again
                Location at = respawnSpot(col, c);
                if (at == null) continue;
                if (spawn(col, c, at) != null) spawned++;
            }
        }
    }

    /** The citizens of an active colony who get a body, best first, up to the cap. */
    private List<Citizen> chosen(Colony col, int cap) {
        List<Citizen> all = new ArrayList<>();
        for (Citizen c : col.citizens.values()) if (!c.downed() || npc(c) != null) all.add(c);
        if (all.size() <= cap) return all;
        World w = col.world();
        List<Player> players = w == null ? List.of() : w.getPlayers();
        boolean alarm = plugin.wars().fighting(col) || plugin.military().threatened(col);
        Map<UUID, Double> score = new HashMap<>();
        for (Citizen c : all) {
            double p = 0;
            if (!c.status.free()) p += 50;
            if (alarm && c.job.military()) p += 100;
            if (npc(c) != null) p += 5; // don't flicker
            Building b = col.building(c.workplace);
            if (b != null && b.anchor != null && w != null && b.anchor.loaded(w)) {
                Job.Background bg = c.job.background;
                if (bg == Job.Background.NONE || bg == Job.Background.FARM || bg == Job.Background.PEN || bg == Job.Background.GROW || bg == Job.Background.BEES) p += 40;
                else p += 10;
            }
            Location here = c.hasLast && w != null ? new Location(w, c.lastX, c.lastY, c.lastZ) : col.coreLocation();
            if (here != null) {
                for (Player pl : players) {
                    if (pl.getLocation().distanceSquared(here) < 48 * 48) {
                        p += 30;
                        break;
                    }
                }
            }
            p += Math.floorMod(c.id.hashCode(), 100) / 1000.0;
            score.put(c.id, p);
        }
        all.sort((a, b) -> Double.compare(score.get(b.id), score.get(a.id)));
        return new ArrayList<>(all.subList(0, cap));
    }

    private Location respawnSpot(Colony col, Citizen c) {
        World w = col.world();
        Location core = col.coreLocation();
        if (w == null || core == null) return null;
        Location cell = plugin.prison().respawnSpot(col, c);
        if (cell != null && ticking(w, cell)) return cell;
        if (c.hasLast) {
            Location l = new Location(w, c.lastX, c.lastY, c.lastZ);
            if (ticking(w, l) && col.territory.near(l.getX(), l.getZ(), plugin.settings().workMargin + 16)) {
                Location safe = Mover.safeSpot(l);
                if (safe != null) return safe;
            }
        }
        if (c.bed != null && ticking(w, c.bed.center(w))) {
            Location l = Mover.safeSpot(c.bed.center(w).add(1, 0, 0));
            if (l != null) return l;
        }
        return spotNear(core, 2, 5);
    }

    /** A standable spot around a point, between min and max blocks away. */
    public Location spotNear(Location center, int min, int max) {
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 24; i++) {
            int dx = r.nextInt(-max, max + 1), dz = r.nextInt(-max, max + 1);
            if (Math.abs(dx) < min && Math.abs(dz) < min) continue;
            Location l = Mover.safeSpot(center.clone().add(dx, 0, dz));
            if (l != null) return l;
        }
        Location l = Mover.safeSpot(center.clone().add(0, 1, 0));
        return l != null ? l : center.clone().add(0.5, 1, 0.5);
    }

    public Npc spawn(Colony col, Citizen c, Location at) {
        World w = at.getWorld();
        if (w == null) return null;
        Settings s = plugin.settings();
        Villager v;
        try {
            v = w.spawn(at, Villager.class, CreatureSpawnEvent.SpawnReason.CUSTOM, vil -> {
                vil.setPersistent(false);
                vil.setRemoveWhenFarAway(false);
                vil.setAware(false);
                vil.setCollidable(false);
                vil.setCanPickupItems(false);
                vil.getPersistentDataContainer().set(Keys.CITIZEN, PersistentDataType.STRING, c.id.toString());
                vil.setVillagerExperience(1);
                attr(vil, Attribute.MAX_HEALTH, Math.max(1, c.needs.maxHealth));
                attr(vil, Attribute.FOLLOW_RANGE, 64);
                vil.setHealth(Math.max(1, Math.min(c.needs.maxHealth, c.needs.health <= 0 ? c.needs.maxHealth : c.needs.health)));
                EntityEquipment eq = vil.getEquipment();
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    try {
                        eq.setDropChance(slot, 0f);
                    } catch (IllegalArgumentException ignored) {
                        // body and saddle slots on some versions
                    }
                }
            });
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (v == null || !v.isValid()) return null;
        Npc n = new Npc(c, v);
        // which side of a pen's gate is the inside (stray animals in a gateway are put back there)
        n.mover.setGates(gate -> {
            Colony owner = plugin.colonies().at(gate);
            return owner == null ? null : Pens.insideOf(owner, gate);
        });
        n.lastLife = plugin.sim().gameTime(col);
        byCitizen.put(c.id, n);
        byEntity.put(v.getUniqueId(), n);
        refresh(n);
        plugin.military().bodySpawned(n, col);
        if (s.walkSpeed > 0) n.mover.sync();
        return n;
    }

    private static void attr(LivingEntity e, Attribute a, double v) {
        AttributeInstance i = e.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    /** Updates looks, name and equipment after a change of job or status. */
    public void refresh(Npc n) {
        if (!(n.body instanceof Villager v)) return;
        Citizen c = n.c;
        Villager.Type type = Registry.VILLAGER_TYPE.get(NamespacedKey.minecraft(c.skin.toLowerCase(Locale.ROOT)));
        if (type != null) v.setVillagerType(type);
        Villager.Profession prof = c.status == Status.CITIZEN ? c.job.profession() : Villager.Profession.NITWIT;
        if (plugin.sim().citizens().stage(c) == LifeStage.CHILD) prof = Villager.Profession.NONE;
        if (v.getProfession() != prof) v.setProfession(prof);
        badge(n);
        if (plugin.sim().citizens().stage(c) == LifeStage.CHILD) v.setBaby();
        else v.setAdult();
        v.setAgeLock(true);
        n.mover.setIronKeys(c.status == Status.CITIZEN && (c.job == Job.WARDEN || c.job == Job.GUARD || c.job == Job.SOLDIER));
        EntityEquipment eq = v.getEquipment();
        eq.setBoots(c.armor[0]);
        eq.setLeggings(c.armor[1]);
        eq.setChestplate(c.armor[2]);
        eq.setHelmet(c.armor[3]);
        eq.setItemInOffHand(c.shield);
        showHand(n);
        rename(n);
    }

    /** The badge (villager level) shows how well they work: stone to diamond. */
    private void badge(Npc n) {
        if (!(n.body instanceof Villager v)) return;
        Colony col = plugin.colonies().colonyOf(n.c);
        double eff = col == null ? 1 : efficiency(col, n.c);
        int lvl = Math.max(1, Math.min(5, (int) Math.round(eff * 4 - 1)));
        if (lvl != n.shownLevel) {
            n.shownLevel = lvl;
            v.setVillagerLevel(lvl);
        }
    }

    public void showHand(Npc n) {
        Citizen c = n.c;
        ItemStack hand = null;
        if (!c.downed()) {
            if (c.status == Status.RIOTER || c.status == Status.CITIZEN && c.job.military()) {
                hand = n.enemy != null && c.ranged != null && c.ammo > 0 && n.loc().distanceSquared(n.enemy.getLocation()) > 25 ? c.ranged
                        : c.weapon != null ? c.weapon : c.ranged;
            } else if (c.status == Status.CITIZEN) {
                hand = c.tool;
            }
        }
        if (same(hand, n.shownHand)) return;
        n.shownHand = hand == null ? null : hand.clone();
        n.body.getEquipment().setItemInMainHand(hand == null ? null : hand.clone());
    }

    private static boolean same(ItemStack a, ItemStack b) {
        if (a == null || b == null) return a == b;
        return a.getType() == b.getType();
    }

    public void rename(Npc n) {
        Citizen c = n.c;
        String icon = switch (c.status) {
            case CITIZEN -> c.job.military() ? "<red>⚔" : plugin.sim().citizens().stage(c) == LifeStage.CHILD ? "<aqua>✿" : "<green>●";
            case CAPTIVE -> "<gold>✋";
            case PRISONER -> "<yellow>▦";
            case RIOTER -> "<dark_red>⚠";
        };
        String name;
        if (c.downed()) {
            long left = Math.max(0, (c.downedUntil - System.currentTimeMillis()) / 1000);
            name = "<red>✚ DOWNED <white>" + Text.esc(c.name) + " <gray>(" + left + "s)";
        } else {
            name = icon + " <white>" + Text.esc(c.name) + " <gray>[" + c.title() + "]";
            if (c.status == Status.PRISONER && c.origin != null) name += " <yellow>" + Math.round(c.resistance) + "%";
        }
        if (name.equals(n.shownName)) return;
        n.shownName = name;
        n.body.customName(Text.mm(name));
        n.body.setCustomNameVisible(c.downed() || c.status == Status.CAPTIVE || c.status == Status.RIOTER);
    }

    /** Body lost (chunk unloaded, killed by another plugin...): forget it; the activity loop respawns it. */
    private void lost(Npc n) {
        byCitizen.remove(n.c.id);
        byEntity.remove(n.body.getUniqueId());
        release(n);
    }

    private void release(Npc n) {
        Set<BlockPos> cl = claimed.get(n.c.colonyId);
        if (cl != null) {
            if (n.target != null) cl.remove(n.target);
            if (n.jobKey != null && n.jobKey.startsWith("tree:")) cl.remove(BlockPos.parse(n.jobKey.substring(5)));
        }
        Colony col = plugin.colonies().colonyOf(n.c);
        if (col != null) {
            // whatever they were carrying for a job goes back to storage: the background doesn't walk
            plugin.sim().logistics().abandon(col, n.c);
            plugin.sim().logistics().dumpCarried(col, n.c);
        }
        if (n.mount != null && n.mount.isValid()) n.mount.remove();
        n.mount = null;
    }

    public void despawn(Npc n) {
        remember(n.c);
        if (n.sleeping && n.body instanceof Villager v) {
            try {
                v.wakeup();
            } catch (IllegalStateException ignored) {
                // not asleep after all
            }
        }
        n.mover.closeAll();
        byCitizen.remove(n.c.id);
        byEntity.remove(n.body.getUniqueId());
        release(n);
        if (n.body.isValid()) n.body.remove();
    }

    public void despawn(Citizen c) {
        Npc n = byCitizen.get(c.id);
        if (n != null) despawn(n);
    }

    public void despawnColony(Colony col) {
        for (Npc n : new ArrayList<>(byCitizen.values())) if (col.id.equals(n.c.colonyId)) despawn(n);
    }

    public void despawnAll() {
        for (Npc n : new ArrayList<>(byCitizen.values())) despawn(n);
    }

    /** Writes the body's position and health back into the record. */
    public void remember(Citizen c) {
        Npc n = byCitizen.get(c.id);
        if (n == null || !n.body.isValid()) return;
        Location l = n.body.getLocation();
        c.lastX = l.getX();
        c.lastY = l.getY();
        c.lastZ = l.getZ();
        c.hasLast = true;
        c.needs.health = Math.min(c.needs.maxHealth, n.body.getHealth());
    }

    /**
     * Writes a citizen's health from the record into their body, after something changed it off-stage (an
     * illness, a doctor's daily round, a brawl). The body is otherwise the truth for health, so without this a
     * change to the record alone is undone the next time the body is read.
     */
    public void pushHealth(Citizen c) {
        Npc n = npc(c);
        if (n == null || c.downed()) return;
        double hp = Math.max(1, Math.min(c.needs.maxHealth, c.needs.health));
        var max = n.body.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) hp = Math.min(hp, max.getValue());
        n.body.setHealth(hp);
    }

    public void rememberAll() {
        for (Npc n : byCitizen.values()) remember(n.c);
    }

    /** Respawns a body somewhere (into a cell, after a transfer). */
    public Npc respawnAt(Citizen c, Location at) {
        Npc old = byCitizen.get(c.id);
        if (old != null) despawn(old);
        Colony col = plugin.colonies().colonyOf(c);
        if (col == null || !active(col)) return null;
        return spawn(col, c, at);
    }

    /** A building is gone: workers there forget what they were doing with it. */
    public void buildingRemoved(Colony col, Building b) {
        for (Npc n : of(col)) {
            if (n.c.workplace == b.id || n.c.home == b.id) {
                n.resetWork();
                n.mover.stop();
            }
        }
        brain.forget(col);
    }

    /** A citizen's job changed: new looks, new routine. */
    public void jobChanged(Citizen c) {
        Npc n = npc(c);
        if (n == null) return;
        n.resetWork();
        n.mover.stop();
        refresh(n);
    }

    /** Something about the whole colony changed (riot, mobilisation): refresh everyone's looks. */
    public void refreshColony(Colony col) {
        for (Npc n : of(col)) {
            n.resetWork();
            refresh(n);
        }
    }

    // ───────────── ticking ─────────────

    public void tick(long now) {
        pathBudget = 8;
        for (Npc n : new ArrayList<>(byCitizen.values())) {
            if (!n.valid()) {
                lost(n);
                continue;
            }
            try {
                n.mover.tick(this, now);
                if ((now + n.thinkOffset) % 20 == 0) {
                    Location l = n.mount != null && n.mount.isValid() ? n.mount.getLocation() : n.loc();
                    double dx = l.getX() - n.prevX, dz = l.getZ() - n.prevZ;
                    n.speed = Math.sqrt(dx * dx + dz * dz) / 20;
                    n.prevX = l.getX();
                    n.prevZ = l.getZ();
                    unstick(n);
                    badge(n);
                }
                if ((now + n.thinkOffset) % 10 == 0) brain.think(n, now);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "Citizen " + n.c.name + " hit an error", ex);
                n.resetWork();
                n.mover.stop();
            }
        }
        if (now % 600 == 0) {
            for (Colony col : plugin.colonies().all()) {
                try {
                    if (col.active) brain.schooling.lessons(col);
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.WARNING, "School lessons failed for " + col.name, ex);
                }
            }
        }
    }

    /** A body buried in blocks (a player built over it, a wall went up) steps out instead of suffocating. */
    private void unstick(Npc n) {
        if (n.sleeping || n.c.downed()) return;
        org.bukkit.block.Block feet = n.loc().getBlock(), head = feet.getRelative(org.bukkit.block.BlockFace.UP);
        if (!(feet.getType().isOccluding() || head.getType().isOccluding())) return;
        Location free = Mover.safeSpot(n.loc().add(0, 1, 0));
        if (free == null) free = Mover.safeSpot(n.loc().add(0, 3, 0));
        if (free != null) {
            n.body.teleport(free);
            n.mover.sync();
        }
    }

    // ───────────── hostility ─────────────

    public void markAggressor(Colony col, Player p) {
        aggressors.computeIfAbsent(col.id, k -> new HashMap<>()).put(p.getUniqueId(), System.currentTimeMillis() + 30_000);
    }

    public boolean aggressor(Colony col, Player p) {
        Map<UUID, Long> m = aggressors.get(col.id);
        if (m == null) return false;
        Long until = m.get(p.getUniqueId());
        if (until == null) return false;
        if (until < System.currentTimeMillis()) {
            m.remove(p.getUniqueId());
            return false;
        }
        return true;
    }

    /** Who really did it (the shooter of an arrow, the lighter of TNT). */
    public static Entity source(Entity damager) {
        if (damager instanceof Projectile pr) {
            ProjectileSource s = pr.getShooter();
            return s instanceof Entity e ? e : null;
        }
        if (damager instanceof org.bukkit.entity.TNTPrimed t) return t.getSource();
        return damager;
    }

    /** May this attacker hurt this citizen? */
    public boolean mayHurt(Entity src, Npc victim) {
        Colony col = plugin.colonies().colonyOf(victim.c);
        if (src == null || col == null) return true;
        Npc atk = npc(src);
        if (atk != null) {
            Colony ac = plugin.colonies().colonyOf(atk.c);
            if (atk.c.status == Status.RIOTER || victim.c.status == Status.RIOTER) return atk.c.status != victim.c.status || ac != col;
            if (ac == col) return false;
            return ac != null && plugin.wars().atWar(ac, col);
        }
        if (src instanceof Player p) {
            if (plugin.isBypassing(p)) return true;
            if (victim.c.status == Status.RIOTER) return true;
            if (col.isMember(p.getUniqueId())) return false;
            Colony pc = plugin.colonies().of(p);
            return pc != null && plugin.wars().atWar(pc, col);
        }
        if (plugin.travelers().isTraveler(src)) return false;
        return true;
    }

    // ───────────── events ─────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamageBy(EntityDamageByEntityEvent e) {
        Npc attacker = npc(source(e.getDamager()));
        Npc victim = npc(e.getEntity());
        if (victim == null && e.getEntity() instanceof Mob m && m.getPersistentDataContainer().has(Keys.MOUNT, PersistentDataType.STRING)) {
            // a war horse: only enemies at war may hurt it
            Npc rider = null;
            for (Entity p : m.getPassengers()) if (npc(p) != null) rider = npc(p);
            if (rider != null && !mayHurt(source(e.getDamager()), rider)) e.setCancelled(true);
            return;
        }
        if (victim != null) {
            Entity src = source(e.getDamager());
            if (!mayHurt(src, victim)) {
                e.setCancelled(true);
                if (src instanceof Player p) {
                    Colony col = plugin.colonies().colonyOf(victim.c);
                    if (col != null && !col.isMember(p.getUniqueId())) {
                        markAggressor(col, p);
                        Text.bar(p, "<red>The citizens of " + Text.esc(col.name) + " are protected (no war). Their guards have seen you!");
                    } else {
                        Text.bar(p, "<red>You can't hurt your own citizens.");
                    }
                }
                return;
            }
            victim.mover.pause(8, plugin.tick());
            return;
        }
        // citizens never hurt their own colony's members by accident
        if (attacker != null && e.getEntity() instanceof Player p) {
            Colony col = plugin.colonies().colonyOf(attacker.c);
            if (col != null && col.isMember(p.getUniqueId()) && attacker.c.status != Status.RIOTER) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSuffocate(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.SUFFOCATION) return;
        Npc n = npc(e.getEntity());
        if (n == null) return;
        e.setCancelled(true);
        unstick(n);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent e) {
        Npc n = npc(e.getEntity());
        if (n == null) return;
        if (n.sleeping && n.body instanceof Villager v) {
            try {
                v.wakeup();
            } catch (IllegalStateException ignored) {
                // already awake
            }
            n.sleeping = false;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!n.valid()) return;
            n.c.needs.health = Math.min(n.c.needs.maxHealth, n.body.getHealth());
            plugin.sim().citizens().updateInjury(n.c);
        });
    }

    /** Founding grace: a blow that would kill a new colony's citizen leaves them standing (and sends them home). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGraceDamage(EntityDamageEvent e) {
        Npc n = npc(e.getEntity());
        if (n == null || n.body == null) return;
        org.bukkit.entity.Mob le = n.body;
        Colony col = plugin.colonies().colonyOf(n.c);
        if (col == null || !col.inGrace() || !n.c.status.free()) return;
        if (le.getHealth() - e.getFinalDamage() > 0) return;
        e.setCancelled(true);
        String how = n.c.needs.satiety <= 0 ? "starved" : "was killed";
        if (e instanceof EntityDamageByEntityEvent be) {
            Entity src = source(be.getDamager());
            if (src instanceof Player pl) how = "was killed by " + pl.getName();
            else if (src != null) how = "was killed by a " + src.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        plugin.sim().population().die(col, n.c, how);
        org.bukkit.attribute.AttributeInstance max = le.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        le.setHealth(Math.max(1, Math.min(max == null ? 20 : max.getValue(), n.c.needs.health)));
        le.setNoDamageTicks(100);
        n.enemy = null;
        Location home = col.coreLocation();
        if (home != null && home.getWorld() == le.getWorld() && home.distanceSquared(le.getLocation()) > 9) {
            n.mover.stop();
            le.teleport(home.clone().add(0.5, 1, 1.5));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        Npc n = byEntity.get(e.getEntity().getUniqueId());
        if (n == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        Citizen c = n.c;
        Colony col = plugin.colonies().colonyOf(c);
        // what they had on them falls where they die (it can be picked up by whoever is there)
        e.getDrops().addAll(c.gear());
        c.clearGear();
        for (Map.Entry<String, Long> g : c.carried.snapshot().entrySet()) e.getDrops().addAll(ItemGoods.stacks(g.getKey(), g.getValue()));
        c.carried.clear();
        // a rider's horse falls with them
        c.horse = false;
        byCitizen.remove(c.id);
        byEntity.remove(n.body.getUniqueId());
        if (n.mount != null && n.mount.isValid()) n.mount.remove();
        if (col == null) {
            plugin.colonies().removeCitizen(c);
            return;
        }
        Player killer = e.getEntity().getKiller();
        plugin.military().died(n, col, killer);
        String how = killer != null ? "was killed by " + killer.getName() : c.needs.satiety <= 0 ? "starved" : "died";
        if (e.getEntity().getLastDamageCause() != null && killer == null) {
            Entity src = e.getEntity().getLastDamageCause() instanceof EntityDamageByEntityEvent be ? source(be.getDamager()) : null;
            Npc other = npc(src);
            if (other != null) how = "was killed by " + other.c.name + " (" + other.c.title() + ")";
            else if (src != null) how = "was killed by a " + src.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        plugin.sim().population().die(col, c, how);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEntityEvent e) {
        Npc n = npc(e.getRightClicked());
        if (n == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        Colony col = plugin.colonies().colonyOf(n.c);
        if (col == null) return;
        if (plugin.prison().interact(p, n, col, hand)) return;
        if (plugin.military().interact(p, n, col, hand)) return;
        if (handOver(p, n, col, hand)) return;
        if (plugin.requests().interact(p, n.c, col, hand)) return;
        if (col.isMember(p.getUniqueId()) || plugin.isBypassing(p)) {
            new CitizenMenu(plugin, p, col, n.c).open();
        } else {
            Text.send(p, "<white>" + Text.esc(n.c.name) + "</white> <gray>- " + n.c.title() + " of <gold>" + Text.esc(col.name) + "</gold>. <dark_gray>" + n.c.activity);
        }
    }

    /** An animal a player puts on a lead is theirs to take: its keeper stops counting it as a stray. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeash(org.bukkit.event.entity.PlayerLeashEntityEvent e) {
        e.getEntity().getPersistentDataContainer().remove(Keys.PEN);
        e.getEntity().getPersistentDataContainer().remove(Keys.LED);
    }

    /** A keeper's lead is the colony's (it stays in their hands): an animal coming off it never drops a lead item. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLedUnleash(org.bukkit.event.entity.EntityUnleashEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(Keys.LED, PersistentDataType.STRING)) e.setDropLeash(false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) {
        if (isBody(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (isBody(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(EntityPortalEvent e) {
        if (isBody(e.getEntity()) || e.getEntity().getPersistentDataContainer().has(Keys.MOUNT, PersistentDataType.STRING)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCareer(VillagerCareerChangeEvent e) {
        if (isBody(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onUnload(EntitiesUnloadEvent e) {
        for (Entity en : e.getEntities()) {
            Npc n = byEntity.get(en.getUniqueId());
            if (n == null) continue;
            remember(n.c);
            n.mover.closeAll();
            byCitizen.remove(n.c.id);
            byEntity.remove(en.getUniqueId());
            release(n);
            en.remove();
        }
    }

    /** Stray bodies and horses from a crash (they aren't meant to be saved): removed when their chunk loads. */
    @EventHandler
    public void onLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (isBody(en) && !byEntity.containsKey(en.getUniqueId())) en.remove();
            else if (en.getPersistentDataContainer().has(Keys.MOUNT, PersistentDataType.STRING) && en.getPassengers().isEmpty()) en.remove();
        }
    }

    /** Edible goods (for walking to a meal). */
    public boolean hasFood(Colony col) {
        for (String k : col.storage.contents().keySet()) if (plugin.sim().citizens().edible(k) && col.storage.available(k) > 0) return true;
        return false;
    }

    static Predicate<String> goods(String... keys) {
        Set<String> s = Set.of(keys);
        return s::contains;
    }

    static String good(org.bukkit.Material m) {
        return Goods.key(m.name());
    }
}
