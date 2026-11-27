package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Goods;
import com.colonysmp.core.Skill;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Colony;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Keys;
import com.colonysmp.util.Text;
import com.colonysmp.util.Tools;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Goat;
import org.bukkit.entity.Item;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Animal keepers look after the animals inside their pen: gather eggs, shear sheep, milk cows into buckets,
 * breed each kind up to the pen's herd size with feed from the pen's stock or storage, and send the surplus to
 * slaughter for meat, leather and wool. At the stables they breed horses and break them in as war horses for the
 * cavalry (each takes a saddle). What they gather goes into the pen's stock for the haulers.
 *
 * <p>The animals stay in: every animal counted inside the fence is marked as the pen's, the keeper waits around
 * inside the fence rather than wandering in and out of the gate, and one that does get out (a player left the
 * gate open, a fence was broken) is fetched and led back before any other work. Gates themselves are handled by
 * {@link Mover}. Leading an animal out on a lead, or naming it, takes it off the keeper's books.
 *
 * <p>While the pen holds fewer than its herd size of a kind, the keeper goes out and leads in wild animals from
 * around it ({@code citizens.herd-gather-range}): on a lead from colony storage (the lead isn't used up, and never
 * drops as an item), or failing that coaxed along with the animal's feed. Only animals that belong to nobody are
 * taken: never named, tamed or leashed ones, ones counted in a pen, or ones on another colony's land; on unclaimed
 * land only animals that spawned wild and stand away from any fence or wall.
 */
public final class Herding {

    private enum Task { RETURN, EGG, SHEAR, MILK, BREED, CULL, BREAK_IN, GATHER, LEAD }

    /** How long a keeper keeps trying to lead one animal in before letting it go (ticks). */
    private static final long LEAD_TIMEOUT = 20 * 90;
    /** How often a keeper looks around for wild animals when there were none (ticks). */
    private static final long GATHER_SCAN = 20 * 10;

    /** A wild animal a keeper has picked to bring in, so two keepers don't go after the same one. */
    private record Claim(java.util.UUID keeper, long until) {}

    private final Map<java.util.UUID, Claim> claims = new java.util.HashMap<>();
    /** The animal each keeper is leading right now, and since when. */
    private final Map<java.util.UUID, Animals> leading = new java.util.HashMap<>();
    private final Map<java.util.UUID, Long> leadSince = new java.util.HashMap<>();
    private final Map<java.util.UUID, Long> nextScan = new java.util.HashMap<>();

    /** How far outside its pen a keeper goes after a stray (blocks). */
    private static final int STRAY_RANGE = 24;

    private final ColonySMP plugin;
    private final NpcManager m;

    Herding(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    private static boolean herd(Entity e, boolean stables) {
        if (stables) {
            return e instanceof AbstractHorse h && e.getType() == EntityType.HORSE && !h.isTamed() && e.customName() == null && !NpcManager.isBody(e);
        }
        if (!(e instanceof Cow || e instanceof Sheep || e instanceof Pig || e instanceof Chicken || e instanceof Rabbit || e instanceof Goat)) return false;
        if (e.customName() != null || e instanceof Tameable t && t.isTamed()) return false;
        return !(e instanceof org.bukkit.entity.LivingEntity le && le.isLeashed());
    }

    private static List<String> feed(EntityType t) {
        return switch (t) {
            case PIG -> List.of("carrot", "potato", "beetroot");
            case CHICKEN -> List.of("wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds");
            case RABBIT -> List.of("carrot", "dandelion");
            case HORSE -> List.of("golden_carrot", "golden_apple", "hay_block", "wheat");
            default -> List.of("wheat");
        };
    }

    private static String kind(EntityType t) {
        return t.name().toLowerCase(Locale.ROOT);
    }

    private static BoundingBox box(Building pen, double pad) {
        return new BoundingBox(pen.min.x() - pad, pen.min.y() - 1 - pad / 4, pen.min.z() - pad, pen.max.x() + 1 + pad, pen.max.y() + 3 + pad / 4, pen.max.z() + 1 + pad);
    }

    private static String tag(Colony col, Building pen) {
        return col.id + ":" + pen.id;
    }

    /** The animals inside a pen's fence, by kind (also recorded on the building for the background model). */
    Map<EntityType, List<Animals>> census(World w, Colony col, Building pen) {
        Map<EntityType, List<Animals>> kinds = new EnumMap<>(EntityType.class);
        if (pen.min == null || pen.max == null) return kinds;
        boolean stables = pen.type == BuildingType.STABLES;
        String tag = tag(col, pen);
        for (Entity e : w.getNearbyEntities(box(pen, 0), e -> herd(e, stables))) {
            // the bounding box of an L-shaped yard takes in ground outside the fence: only the yard's own tiles count
            if (!Pens.inside(pen, e.getLocation())) continue;
            kinds.computeIfAbsent(e.getType(), k -> new ArrayList<>()).add((Animals) e);
            if (!tag.equals(e.getPersistentDataContainer().get(Keys.PEN, PersistentDataType.STRING))) {
                e.getPersistentDataContainer().set(Keys.PEN, PersistentDataType.STRING, tag);
            }
        }
        pen.counts.clear();
        for (Map.Entry<EntityType, List<Animals>> e : kinds.entrySet()) pen.counts.put(kind(e.getKey()), e.getValue().size());
        return kinds;
    }

    /** This pen's animals that are outside its fence, close enough to fetch. */
    List<Animals> strays(World w, Colony col, Building pen) {
        List<Animals> out = new ArrayList<>();
        if (pen.min == null || pen.max == null || pen.tiles.isEmpty()) return out;
        boolean stables = pen.type == BuildingType.STABLES;
        String tag = tag(col, pen);
        for (Entity e : w.getNearbyEntities(box(pen, STRAY_RANGE), e -> herd(e, stables))) {
            if (!tag.equals(e.getPersistentDataContainer().get(Keys.PEN, PersistentDataType.STRING))) continue;
            if (!Pens.inside(pen, e.getLocation())) out.add((Animals) e);
        }
        return out;
    }

    void think(Npc n, Colony col, Building pen, long now) {
        World w = n.body.getWorld();
        boolean stables = pen.type == BuildingType.STABLES;
        // an animal still on this keeper's lead from work that was interrupted is let go
        Animals led = leading.get(n.c.id);
        if (led != null && (n.subject != led || !"herd:LEAD".equals(n.jobKey))) release(n, led);
        Map<EntityType, List<Animals>> kinds = census(w, col, pen);
        List<Animals> animals = new ArrayList<>();
        for (List<Animals> l : kinds.values()) animals.addAll(l);
        List<Animals> strays = strays(w, col, pen);
        List<Item> eggs = new ArrayList<>();
        if (!stables && pen.min != null) {
            for (Entity e : w.getNearbyEntities(box(pen, 0), e -> e instanceof Item it && it.getItemStack().getType() == Material.EGG && it.getTicksLived() > 40)) eggs.add((Item) e);
        }
        boolean busy = n.jobKey != null && (n.jobKey.equals("herd:GATHER") || n.jobKey.equals("herd:LEAD")) && n.subject != null && n.subject.isValid();
        if (animals.isEmpty() && eggs.isEmpty() && strays.isEmpty() && !busy) {
            Animals wild = wild(w, col, pen, kinds, Math.max(2, pen.herdTarget), n, now);
            if (wild != null) {
                claim(wild, n, now);
                n.subject = wild;
                n.jobKey = "herd:GATHER";
                lead(n, col, pen, wild, Task.GATHER, now);
                return;
            }
            waitInside(n, col, pen, now, (stables ? "No horses in " : "No animals in ") + pen.label() + (plugin.settings().herdGatherRange > 0 ? " (none wild nearby: lead some in)" : " (lead some in)"));
            return;
        }
        Entity subject = n.subject != null && n.subject.isValid() ? n.subject : null;
        Task task = n.jobKey == null || !n.jobKey.startsWith("herd:") ? null : Task.valueOf(n.jobKey.substring(5));
        if (subject == null || task == null || !still(task, subject, pen)) {
            subject = null;
            task = null;
            int size = Math.max(2, pen.herdTarget);
            Location here = n.loc();
            if (!strays.isEmpty()) {
                // an animal is out: nothing else matters until it is back behind the fence
                subject = closest(strays, here, a -> true);
                if (subject != null) task = Task.RETURN;
            }
            if (subject == null && !eggs.isEmpty()) {
                subject = eggs.get(0);
                task = Task.EGG;
            }
            boolean shears = n.c.tool != null && Tools.kind(n.c.tool) == Tools.Kind.SHEARS || col.equipment.has(it -> Tools.kind(it) == Tools.Kind.SHEARS);
            if (subject == null && shears) {
                for (Animals a : animals) {
                    if (a instanceof Sheep s && s.isAdult() && !s.isSheared()) {
                        subject = s;
                        task = Task.SHEAR;
                        break;
                    }
                }
            }
            if (subject == null && pen.stock.available("bucket") > 0 && ThreadLocalRandom.current().nextInt(4) == 0) {
                subject = closest(kinds.getOrDefault(EntityType.COW, List.of()), here, Animals::isAdult);
                if (subject != null) task = Task.MILK;
            }
            if (subject == null && stables && pen.stock.available("saddle") + col.storage.available("saddle") > 0) {
                List<Animals> horses = kinds.getOrDefault(EntityType.HORSE, List.of());
                if (horses.size() > 2) {
                    subject = closest(horses, here, Animals::isAdult);
                    if (subject != null) task = Task.BREAK_IN;
                }
            }
            if (subject == null) {
                Animals wild = wild(w, col, pen, kinds, size, n, now);
                if (wild != null) {
                    claim(wild, n, now);
                    subject = wild;
                    task = Task.GATHER;
                }
            }
            if (subject == null && !stables) {
                for (Map.Entry<EntityType, List<Animals>> e : kinds.entrySet()) {
                    if (e.getValue().size() > size) {
                        subject = closest(e.getValue(), here, a -> a.isAdult() && !a.isLoveMode());
                        task = Task.CULL;
                        if (subject != null) break;
                    }
                }
            }
            if (subject == null) {
                for (Map.Entry<EntityType, List<Animals>> e : kinds.entrySet()) {
                    List<Animals> list = e.getValue();
                    if (list.size() >= size || list.size() < 2) continue;
                    long ready = list.stream().filter(a -> a.isAdult() && a.canBreed() && !a.isLoveMode()).count();
                    long inLove = list.stream().filter(Animals::isLoveMode).count();
                    if (ready + inLove < 2) continue;
                    if (!hasFeed(n, col, pen, feed(e.getKey()))) continue;
                    subject = closest(list, here, a -> a.isAdult() && a.canBreed() && !a.isLoveMode());
                    task = Task.BREED;
                    if (subject != null) break;
                }
            }
            n.subject = subject;
            n.jobKey = task == null ? null : "herd:" + task.name();
        }
        if (subject == null) {
            waitInside(n, col, pen, now, "Watching over the " + (stables ? "horses" : "herd") + " (" + animals.size() + " animals)");
            return;
        }
        if (task == Task.GATHER || task == Task.LEAD) {
            lead(n, col, pen, (Animals) subject, task, now);
            return;
        }
        if (task == Task.SHEAR && !m.ensureTool(n, col, Tools.Kind.SHEARS, now)) return;
        if (task == Task.BREED) {
            List<String> f = feed(subject.getType());
            if (!carriesAny(n, pen, f)) {
                NpcManager.Fetch got = m.fetch(n, col, f, 8, "animal feed");
                if (got == NpcManager.Fetch.FETCHING) return;
                if (got == NpcManager.Fetch.NONE) {
                    n.subject = null;
                    return;
                }
            }
        }
        Location at = subject.getLocation();
        if (!n.mover.near(at, 1.8)) {
            n.mover.moveTo(at, plugin.settings().walkSpeed, 1.4);
            String what = Text.nice(subject.getType().name()).toLowerCase();
            n.doing(switch (task) {
                case RETURN -> "Going after a stray " + what;
                case SHEAR -> "Going to shear a sheep";
                case EGG -> "Collecting eggs";
                case MILK -> "Going to milk a cow";
                case BREED -> "Bringing feed to the " + (what.equals("sheep") ? what : what + "s");
                case CULL -> "Picking out a " + what + " for slaughter";
                case BREAK_IN -> "Saddling a horse";
                case GATHER, LEAD -> "Bringing in a wild " + what;
            });
            return;
        }
        n.mover.stop();
        n.working = true;
        CitizenBrain.face(n, at);
        if (now < n.actionAt) return;
        n.actionAt = now + m.interval(2.0, m.efficiency(col, n.c));
        switch (task) {
            case RETURN -> bringBack(n, pen, (Animals) subject);
            case SHEAR -> shear(n, col, pen, (Sheep) subject);
            case EGG -> {
                Item egg = (Item) subject;
                m.deposit(col, pen, List.of(egg.getItemStack()), at);
                egg.remove();
                Fx.sound(at, "minecraft:entity.item.pickup", 0.6f, 1f);
                n.doing("Collected eggs");
            }
            case MILK -> {
                if (pen.stock.take("bucket", 1) == 1) {
                    if (pen.stock.add("milk_bucket", 1) < 1) col.storage.force("milk_bucket", 1);
                    Fx.sound(at, "minecraft:entity.cow.milk", 0.7f, 1f);
                    n.doing("Milked a cow");
                }
            }
            case BREED -> {
                Animals a = (Animals) subject;
                if (useFeed(n, pen, feed(a.getType()))) {
                    a.setLoveModeTicks(600);
                    a.setBreedCause(null);
                    at.getWorld().spawnParticle(Particle.HEART, at.clone().add(0, 1, 0), 4, 0.3, 0.3, 0.3, 0);
                    Fx.sound(at, "minecraft:entity.generic.eat", 0.6f, 1f);
                    n.doing("Feeding the " + Text.nice(a.getType().name()).toLowerCase() + (a.getType() == EntityType.SHEEP ? "" : "s"));
                }
            }
            case CULL -> cull(n, col, pen, (Animals) subject);
            case BREAK_IN -> breakIn(n, col, pen, (Animals) subject);
            case GATHER, LEAD -> {
            }
        }
        n.c.skills.gain(Skill.HUSBANDRY, 1.5 * n.c.trait.learning());
        n.subject = null;
        n.jobKey = null;
    }

    /** Waits about inside the fence, well away from the gates, so the gate isn't opened for nothing. */
    private void waitInside(Npc n, Colony col, Building pen, long now, String label) {
        World w = n.body.getWorld();
        if (pen.tiles.isEmpty()) {
            m.brain().wanderAt(n, col, pen, now, label);
            return;
        }
        if (n.idleSpot == null || now > n.idleUntil || n.idleSpot.getWorld() != w || !Pens.inside(pen, n.idleSpot)) {
            n.idleSpot = Pens.interiorSpot(w, pen);
            n.idleUntil = now + 200 + ThreadLocalRandom.current().nextInt(300);
        }
        if (n.idleSpot != null) n.mover.moveTo(n.idleSpot, plugin.settings().walkSpeed * 0.7, 1.2);
        n.doing(label);
    }

    /** Leads a stray back to the middle of its pen. */
    private void bringBack(Npc n, Building pen, Animals a) {
        Location to = Pens.interiorSpot(a.getWorld(), pen);
        if (to == null) return;
        Location from = a.getLocation();
        to.setYaw(from.getYaw());
        a.setLeashHolder(null);
        a.teleport(to);
        a.setVelocity(new org.bukkit.util.Vector());
        from.getWorld().spawnParticle(Particle.POOF, from.clone().add(0, 0.6, 0), 6, 0.3, 0.3, 0.3, 0.01);
        to.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, to.clone().add(0, 0.8, 0), 6, 0.3, 0.3, 0.3, 0);
        Fx.sound(to, "minecraft:entity.leash_knot.place", 0.7f, 1f);
        n.doing("Led a stray " + Text.nice(a.getType().name()).toLowerCase() + " back into " + pen.label());
    }

    private static boolean still(Task t, Entity e, Building pen) {
        return switch (t) {
            case RETURN -> e instanceof Animals && e.isValid() && !Pens.inside(pen, e.getLocation());
            case SHEAR -> e instanceof Sheep s && !s.isSheared();
            case EGG -> e instanceof Item;
            case MILK, CULL, BREAK_IN -> e instanceof Animals a && a.isAdult();
            case BREED -> e instanceof Animals a && a.canBreed() && !a.isLoveMode();
            case GATHER -> e instanceof Animals a && e.isValid() && !a.isLeashed() && !Pens.inside(pen, e.getLocation());
            case LEAD -> e instanceof Animals && e.isValid();
        };
    }

    private boolean hasFeed(Npc n, Colony col, Building pen, List<String> keys) {
        for (String k : keys) if (n.c.carried.get(k) > 0 || pen.stock.available(k) > 0 || col.storage.available(k) > 0) return true;
        return false;
    }

    private static boolean carriesAny(Npc n, Building pen, List<String> keys) {
        for (String k : keys) if (n.c.carried.get(k) > 0 || pen.stock.available(k) > 0) return true;
        return false;
    }

    private static boolean useFeed(Npc n, Building pen, List<String> keys) {
        for (String k : keys) if (pen.stock.take(k, 1) == 1 || n.c.carried.take(k, 1) == 1) return true;
        return false;
    }

    private static Animals closest(List<Animals> list, Location here, Predicate<Animals> ok) {
        Animals best = null;
        double bd = Double.MAX_VALUE;
        for (Animals a : list) {
            if (!ok.test(a) || a.getWorld() != here.getWorld()) continue;
            double d = a.getLocation().distanceSquared(here);
            if (d < bd) {
                bd = d;
                best = a;
            }
        }
        return best;
    }

    // ---- bringing in wild animals ----

    /** The closest wild animal around the pen that it has room for and that may be taken, or null. */
    private Animals wild(World w, Colony col, Building pen, Map<EntityType, List<Animals>> kinds, int size, Npc n, long now) {
        int range = plugin.settings().herdGatherRange;
        if (range <= 0 || pen.tiles.isEmpty() || pen.min == null || pen.max == null) return null;
        Long next = nextScan.get(n.c.id);
        if (next != null && now < next) return null;
        nextScan.put(n.c.id, now + GATHER_SCAN);
        boolean stables = pen.type == BuildingType.STABLES;
        boolean lead = n.c.carried.get("lead") > 0 || col.storage.available("lead") > 0;
        List<Animals> found = new ArrayList<>();
        for (Entity e : w.getNearbyEntities(box(pen, range), e -> herd(e, stables))) {
            Animals a = (Animals) e;
            var pdc = e.getPersistentDataContainer();
            if (NpcManager.isBody(e) || pdc.has(Keys.PEN, PersistentDataType.STRING) || pdc.has(Keys.MOUNT, PersistentDataType.STRING)
                    || pdc.has(Keys.MERCHANT, PersistentDataType.STRING) || !e.getPassengers().isEmpty()) continue;
            if (kinds.getOrDefault(e.getType(), List.of()).size() >= size) continue;
            if (Pens.inside(pen, e.getLocation()) || claimedByOther(a, n, now)) continue;
            if (!lead && !hasFeed(n, col, pen, feed(e.getType()))) continue;
            found.add(a);
        }
        Location here = n.loc();
        found.sort(java.util.Comparator.comparingDouble(a -> a.getLocation().distanceSquared(here)));
        for (Animals a : found) {
            if (mayTake(col, a)) {
                nextScan.remove(n.c.id);
                return a;
            }
        }
        return null;
    }

    /** Does this animal belong to nobody: on the colony's own land, or wild on unclaimed land away from any farm? */
    private boolean mayTake(Colony col, Animals a) {
        Location l = a.getLocation();
        Colony owner = plugin.colonies().at(l);
        if (owner == col) return true;
        if (owner != null || !plugin.settings().herdGatherWilds) return false;
        switch (a.getEntitySpawnReason()) {
            case NATURAL, CHUNK_GEN, DEFAULT -> {
            }
            default -> {
                return false;
            }
        }
        // a fence or wall close by is most likely someone's farm
        World w = l.getWorld();
        int bx = l.getBlockX(), by = l.getBlockY(), bz = l.getBlockZ();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                for (int y = -1; y <= 1; y++) {
                    Material m = w.getBlockAt(bx + x, by + y, bz + z).getType();
                    if (org.bukkit.Tag.FENCES.isTagged(m) || org.bukkit.Tag.FENCE_GATES.isTagged(m) || org.bukkit.Tag.WALLS.isTagged(m)) return false;
                }
            }
        }
        return true;
    }

    private boolean claimedByOther(Animals a, Npc n, long now) {
        Claim c = claims.get(a.getUniqueId());
        return c != null && now < c.until() && !c.keeper().equals(n.c.id);
    }

    private void claim(Animals a, Npc n, long now) {
        claims.values().removeIf(c -> c.keeper().equals(n.c.id) || now >= c.until());
        claims.put(a.getUniqueId(), new Claim(n.c.id, now + LEAD_TIMEOUT + 20 * 60));
    }

    private static String what(Animals a) {
        return Text.nice(a.getType().name()).toLowerCase();
    }

    /** Fetches a lead (or feed), walks out to the animal, puts it on the lead and walks it into the pen. */
    private void lead(Npc n, Colony col, Building pen, Animals a, Task task, long now) {
        World w = n.body.getWorld();
        String what = what(a);
        if (a.getWorld() != w) {
            drop(n, a);
            return;
        }
        if (task == Task.GATHER) {
            if (n.c.carried.get("lead") <= 0 && !carriesAny(n, pen, feed(a.getType()))) {
                NpcManager.Fetch got = col.storage.available("lead") > 0
                        ? m.fetch(n, col, List.of("lead"), 1, "a lead")
                        : m.fetch(n, col, feed(a.getType()), 8, "feed to coax a " + what);
                if (got == NpcManager.Fetch.FETCHING) return;
                if (got == NpcManager.Fetch.NONE) {
                    drop(n, a);
                    return;
                }
            }
            Location at = a.getLocation();
            if (!n.mover.near(at, 2.0)) {
                n.mover.moveTo(at, plugin.settings().walkSpeed, 1.6);
                n.doing("Going to bring in a wild " + what);
                return;
            }
            n.mover.stop();
            n.working = true;
            CitizenBrain.face(n, at);
            if (now < n.actionAt) return;
            n.actionAt = now + m.interval(1.0, m.efficiency(col, n.c));
            a.getPersistentDataContainer().set(Keys.LED, PersistentDataType.STRING, n.c.id.toString());
            boolean onLead = false;
            if (n.c.carried.get("lead") > 0) {
                try {
                    onLead = a.setLeashHolder(n.body);
                } catch (IllegalArgumentException | IllegalStateException ignored) {
                    // an animal that can't be leashed is coaxed instead
                }
            }
            Fx.sound(at, onLead ? "minecraft:item.lead.tied" : "minecraft:entity.generic.eat", 0.7f, 1f);
            leading.put(n.c.id, a);
            leadSince.put(n.c.id, now);
            n.idleSpot = Pens.interiorSpot(w, pen);
            n.jobKey = "herd:LEAD";
            n.doing(onLead ? "Put a wild " + what + " on a lead" : "Coaxing a wild " + what + " along with feed");
            return;
        }
        // leading it in
        leading.put(n.c.id, a);
        long since = leadSince.computeIfAbsent(n.c.id, k -> now);
        Location al = a.getLocation();
        if (Pens.inside(pen, al)) {
            release(n, a);
            a.getPersistentDataContainer().set(Keys.PEN, PersistentDataType.STRING, tag(col, pen));
            al.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, al.clone().add(0, 0.8, 0), 6, 0.3, 0.3, 0.3, 0);
            Fx.sound(al, "minecraft:entity.leash_knot.break", 0.6f, 1f);
            n.c.skills.gain(Skill.HUSBANDRY, 2.0 * n.c.trait.learning());
            n.doing("Led a wild " + what + " into " + pen.label());
            done(n);
            return;
        }
        if (a.isLeashed() && a.getLeashHolder() != n.body) {
            // somebody else took it on their lead: it's theirs
            leading.remove(n.c.id);
            done(n);
            return;
        }
        if (now - since > LEAD_TIMEOUT) {
            n.doing("The " + what + " wouldn't come along");
            drop(n, a);
            return;
        }
        Location here = n.loc();
        double d = al.distance(here);
        if (!a.isLeashed() && n.c.carried.get("lead") > 0 && d < 6) {
            try {
                a.setLeashHolder(n.body);
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // coaxed instead
            }
        }
        if (d > 9) {
            // it got hung up on something: it catches up (well before a lead would snap)
            Location to = m.spotNear(here, 1, 2);
            if (to != null) a.teleport(to);
        } else if (!a.isLeashed() && d > 2.5) {
            a.getPathfinder().moveTo(n.body, 1.25);
        }
        if (n.idleSpot == null || n.idleSpot.getWorld() != w || !Pens.inside(pen, n.idleSpot)) n.idleSpot = Pens.interiorSpot(w, pen);
        if (n.idleSpot == null) {
            drop(n, a);
            return;
        }
        if (Pens.inside(pen, here) && n.mover.near(n.idleSpot, 2.5)) {
            // the keeper is in and the gate is shut behind them: the animal is let through after them
            if (d < 12) {
                Location to = n.idleSpot.clone();
                to.setYaw(al.getYaw());
                a.teleport(to);
                Fx.sound(to, "minecraft:block.fence_gate.close", 0.6f, 1f);
            }
            return;
        }
        n.mover.moveTo(n.idleSpot, plugin.settings().walkSpeed * 0.85, 1.2);
        n.doing("Leading a wild " + what + " to " + pen.label());
    }

    /** Takes the animal off this keeper's lead (the lead stays with the keeper; it never drops as an item). */
    private void release(Npc n, Animals a) {
        leading.remove(n.c.id);
        leadSince.remove(n.c.id);
        if (a.isValid() && a.isLeashed() && a.getLeashHolder() == n.body) a.setLeashHolder(null);
        String id = a.getPersistentDataContainer().get(Keys.LED, PersistentDataType.STRING);
        if (n.c.id.toString().equals(id)) a.getPersistentDataContainer().remove(Keys.LED);
    }

    /** Gives up on an animal: it is let go where it stands. */
    private void drop(Npc n, Animals a) {
        release(n, a);
        done(n);
    }

    private void done(Npc n) {
        claims.values().removeIf(c -> c.keeper().equals(n.c.id));
        n.subject = null;
        n.jobKey = null;
        n.idleSpot = null;
    }

    private void shear(Npc n, Colony col, Building pen, Sheep s) {
        if (n.c.tool == null || Tools.kind(n.c.tool) != Tools.Kind.SHEARS) return;
        s.setSheared(true);
        DyeColor c = s.getColor() == null ? DyeColor.WHITE : s.getColor();
        Material wool = Material.matchMaterial(c.name() + "_WOOL");
        List<ItemStack> fleece = new ArrayList<>(List.of(new ItemStack(wool == null ? Material.WHITE_WOOL : wool, 1 + ThreadLocalRandom.current().nextInt(3))));
        m.toolBonus(n, fleece);
        m.deposit(col, pen, fleece, s.getLocation());
        Fx.sound(s.getLocation(), "minecraft:entity.sheep.shear", 0.8f, 1f);
        m.wearTool(n, col);
        n.doing("Shearing sheep");
    }

    private void cull(Npc n, Colony col, Building pen, Animals a) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<ItemStack> drops = new ArrayList<>();
        switch (a.getType()) {
            case COW, MOOSHROOM -> {
                drops.add(new ItemStack(Material.BEEF, 1 + r.nextInt(3)));
                if (r.nextInt(3) > 0) drops.add(new ItemStack(Material.LEATHER, 1 + r.nextInt(2)));
            }
            case PIG -> drops.add(new ItemStack(Material.PORKCHOP, 1 + r.nextInt(3)));
            case GOAT -> drops.add(new ItemStack(Material.MUTTON, 1 + r.nextInt(2)));
            case SHEEP -> {
                drops.add(new ItemStack(Material.MUTTON, 1 + r.nextInt(2)));
                Sheep s = (Sheep) a;
                if (!s.isSheared()) {
                    Material wool = Material.matchMaterial((s.getColor() == null ? DyeColor.WHITE : s.getColor()).name() + "_WOOL");
                    drops.add(new ItemStack(wool == null ? Material.WHITE_WOOL : wool));
                }
            }
            case CHICKEN -> {
                drops.add(new ItemStack(Material.CHICKEN));
                if (r.nextBoolean()) drops.add(new ItemStack(Material.FEATHER, 1 + r.nextInt(2)));
            }
            case RABBIT -> {
                drops.add(new ItemStack(Material.RABBIT));
                if (r.nextBoolean()) drops.add(new ItemStack(Material.RABBIT_HIDE));
            }
            default -> {
            }
        }
        Location at = a.getLocation();
        at.getWorld().spawnParticle(Particle.SWEEP_ATTACK, at.clone().add(0, 0.8, 0), 1);
        Fx.sound(at, "minecraft:entity.player.attack.sweep", 0.7f, 1f);
        a.remove();
        m.deposit(col, pen, drops, at);
        n.doing("Slaughtered a " + Text.nice(a.getType().name()).toLowerCase() + " (over the herd size)");
    }

    /** A horse is saddled and trained into a war horse for the cavalry (it leaves the paddock for the stalls). */
    private void breakIn(Npc n, Colony col, Building pen, Animals horse) {
        if (pen.stock.take("saddle", 1) != 1 && col.storage.take("saddle", 1) != 1) return;
        Location at = horse.getLocation();
        horse.remove();
        if (pen.stock.add(Goods.WAR_HORSE, 1) < 1) col.storage.force(Goods.WAR_HORSE, 1);
        at.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0, 1, 0), 8, 0.4, 0.4, 0.4, 0);
        Fx.sound(at, "minecraft:entity.horse.saddle", 0.8f, 1f);
        n.doing("Broke in a war horse");
    }
}
