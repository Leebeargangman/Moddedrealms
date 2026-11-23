package com.colonysmp.prison;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Job;
import com.colonysmp.model.Proposal;
import com.colonysmp.model.Relation;
import com.colonysmp.model.Status;
import com.colonysmp.model.Trait;
import com.colonysmp.npc.Mover;
import com.colonysmp.npc.Npc;
import com.colonysmp.npc.NpcManager;
import com.colonysmp.sim.CitizenSim;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.ItemGoods;
import com.colonysmp.util.Items;
import com.colonysmp.util.Keys;
import com.colonysmp.util.Names;
import com.colonysmp.util.Text;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Evoker;
import org.bukkit.entity.Illusioner;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Vindicator;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.entity.Witch;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Prisoners. In a fight people may go down instead of dying; a downed enemy can be bound with rope and led to a
 * prison, where wardens feed them and try to win them over (then they join as citizens), or they can be
 * ransomed, exchanged or released. The colony's own criminals serve short sentences in the same cells.
 */
public final class PrisonManager implements Listener {

    private final ColonySMP plugin;
    /** Downed people who aren't citizens (villagers, illagers, travellers): entity -> bleed-out time. */
    private final Map<UUID, Long> downedOthers = new HashMap<>();

    public PrisonManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    // ───────────── who is a person ─────────────

    /** Vanilla mobs that count as people and can be taken prisoner. */
    public static boolean human(Entity e) {
        return (e instanceof Villager || e instanceof WanderingTrader || e instanceof Pillager || e instanceof Vindicator
                || e instanceof Evoker || e instanceof Illusioner || e instanceof Witch) && !NpcManager.isBody(e);
    }

    public boolean isDowned(Entity e) {
        Npc n = plugin.npcs().npc(e);
        if (n != null) return n.c.downed();
        return downedOthers.containsKey(e.getUniqueId());
    }

    // ───────────── going down ─────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le)) return;
        Npc n = plugin.npcs().npc(le);
        boolean traveler = plugin.travelers().isTraveler(le);
        if (n == null && !traveler && !human(le)) return;
        if (isDowned(le)) return;
        if (le.getHealth() - e.getFinalDamage() > 0) return;
        if (!(e instanceof EntityDamageByEntityEvent) || !combat(e.getCause())) return;
        double chance = traveler ? plugin.settings().travelerDownedChance : plugin.settings().downedChance;
        if (ThreadLocalRandom.current().nextDouble() >= chance) return;
        e.setCancelled(true);
        le.setHealth(Math.min(maxHealth(le), 2.0));
        le.playHurtAnimation(0);
        if (n != null) downCitizen(n);
        else downOther(le);
    }

    private static boolean combat(EntityDamageEvent.DamageCause c) {
        return switch (c) {
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE, ENTITY_EXPLOSION, THORNS, MAGIC -> true;
            default -> false;
        };
    }

    private static double maxHealth(LivingEntity e) {
        AttributeInstance a = e.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    private void downCitizen(Npc n) {
        if (n.sleeping) plugin.npcs().brain().wake(n);
        n.c.downedUntil = System.currentTimeMillis() + plugin.settings().downedSeconds * 1000L;
        n.mover.stop();
        n.enemy = null;
        if (n.mount != null && n.mount.isValid()) n.mount.eject();
        n.body.setPose(Pose.SLEEPING, true);
        n.body.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, n.loc().add(0, 0.5, 0), 6, 0.3, 0.2, 0.3, 0.1);
        Fx.sound(n.loc(), "minecraft:entity.villager.hurt", 1f, 0.7f);
        plugin.npcs().showHand(n);
        plugin.npcs().rename(n);
        Colony col = plugin.colonies().colonyOf(n.c);
        if (col != null) {
            for (Player p : col.onlineMembers()) {
                Text.send(p, "<red>" + Text.esc(n.c.name) + " is down!</red> <gray>Right-click them within " + plugin.settings().downedSeconds + "s to get them up, or they'll bleed out.");
            }
        }
    }

    private void downOther(LivingEntity le) {
        long until = System.currentTimeMillis() + plugin.settings().downedSeconds * 1000L;
        downedOthers.put(le.getUniqueId(), until);
        le.getPersistentDataContainer().set(Keys.DOWNED, PersistentDataType.LONG, until);
        if (le instanceof Mob m) {
            m.setAware(false);
            m.setTarget(null);
        }
        le.setPose(Pose.SLEEPING, true);
        le.customName(Text.mm("<red>✚ DOWNED <gray>- bind with Rope"));
        le.setCustomNameVisible(true);
        Fx.sound(le.getLocation(), "minecraft:entity.villager.hurt", 1f, 0.7f);
    }

    /** Every second: bleeding out. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (Colony col : plugin.colonies().list()) {
            for (Citizen c : new ArrayList<>(col.citizens.values())) {
                if (!c.downed() || now < c.downedUntil) continue;
                Npc n = plugin.npcs().npc(c);
                c.downedUntil = 0;
                if (n != null) {
                    n.body.setPose(Pose.STANDING, false);
                    n.body.setHealth(0);
                } else {
                    plugin.sim().population().die(col, c, "bled out");
                }
            }
        }
        var it = downedOthers.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Entity en = Bukkit.getEntity(e.getKey());
            if (!(en instanceof LivingEntity le) || !le.isValid()) {
                it.remove();
                continue;
            }
            if (now >= e.getValue()) {
                it.remove();
                le.setPose(Pose.STANDING, false);
                le.setHealth(0);
            } else {
                le.customName(Text.mm("<red>✚ DOWNED <gray>(" + (e.getValue() - now) / 1000 + "s) - bind with Rope"));
            }
        }
    }

    /** Gets a downed citizen back on their feet. */
    public void revive(Npc n, double health) {
        n.c.downedUntil = 0;
        n.body.setPose(Pose.STANDING, false);
        n.body.setHealth(Math.min(maxHealth(n.body), health));
        n.c.needs.health = n.body.getHealth();
        n.c.injured = true;
        n.mover.sync();
        plugin.npcs().rename(n);
        n.body.getWorld().spawnParticle(Particle.HEART, n.loc().add(0, 1.5, 0), 5, 0.3, 0.3, 0.3, 0);
        Fx.sound(n.loc(), "minecraft:entity.villager.yes", 1f, 1f);
    }

    // ───────────── players and bodies ─────────────

    /** Right-clicks on a citizen body that concern prisoners. True if handled. */
    public boolean interact(Player p, Npc n, Colony col, ItemStack hand) {
        boolean member = col.isMember(p.getUniqueId());
        if (n.c.status == Status.CAPTIVE && member && p.isSneaking()) {
            handToWardens(p, n);
            return true;
        }
        if (Items.is(hand, Items.ROPE)) {
            bind(p, n, hand);
            return true;
        }
        if (n.c.downed()) {
            if (member) {
                revive(n, 6);
                Text.send(p, "You helped <white>" + Text.esc(n.c.name) + "</white> back to their feet.");
            } else {
                Text.send(p, "<gray>" + Text.esc(n.c.name) + " is down. Bind them with <white>Rope</white> to take them prisoner.");
            }
            return true;
        }
        if (n.c.status == Status.PRISONER && member && plugin.sim().citizens().edible(ItemGoods.goodOf(hand) == null ? "" : ItemGoods.goodOf(hand))) {
            feed(p, n, col, hand);
            return true;
        }
        return false;
    }

    /** Rope on a downed enemy citizen: they become a captive of the binder's colony. */
    private void bind(Player p, Npc n, ItemStack rope) {
        if (!n.c.downed()) {
            Text.send(p, "<gray>Only someone who is <red>downed</red> can be bound.");
            return;
        }
        Colony captor = plugin.colonies().of(p);
        if (captor == null || captor.core == null) {
            Text.send(p, "<red>You need a colony with a Town Hall to hold prisoners.");
            return;
        }
        Citizen c = n.c;
        Colony from = plugin.colonies().colonyOf(c);
        if (from == captor && c.status != Status.RIOTER) {
            Text.send(p, "<gray>That's one of your own. Right-click without rope to help them up.");
            return;
        }
        use(p, rope);
        // their arms and tools are taken by the captor
        for (ItemStack it : c.gear()) captor.equipment.force(it);
        c.clearGear();
        c.ammo = 0;
        if (c.horse && from != null) from.storage.force(com.colonysmp.core.Goods.WAR_HORSE, 1);
        c.horse = false;
        plugin.sim().logistics().abandon(from != null ? from : captor, c);
        long day = plugin.sim().day(captor);
        if (from != captor) {
            if (from != null) {
                for (Player m : from.onlineMembers()) Text.send(m, "<red>" + Text.esc(c.name) + " was captured by " + Text.esc(p.getName()) + " of " + Text.esc(captor.name) + "!");
                from.log(ColonyEvent.Type.MILITARY, plugin.sim().day(from), c.name + " was captured by " + captor.name + ".");
                c.origin = from.id;
                plugin.diplomacy().relation(from, captor).change(-5);
            }
            plugin.colonies().transferCitizen(c, captor);
            plugin.sim().housing().assign(from == null ? captor : from);
        }
        c.downedUntil = 0;
        c.status = Status.CAPTIVE;
        c.job = Job.UNEMPLOYED;
        c.militaryClass = null;
        c.escortPlayer = p.getUniqueId();
        c.awaitingEscort = false;
        c.cell = 0;
        c.remember(day, "Captured by " + captor.name);
        n.body.setPose(Pose.STANDING, false);
        n.body.setLeashHolder(p);
        n.resetWork();
        plugin.npcs().refresh(n);
        bound(p, c.name);
        plugin.requestSave();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractOther(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Entity t = e.getRightClicked();
        if (!(t instanceof LivingEntity le) || NpcManager.isBody(t)) return;
        boolean traveler = plugin.travelers().isTraveler(t);
        if (!human(t) && !traveler) return;
        if (!downedOthers.containsKey(t.getUniqueId())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!Items.is(hand, Items.ROPE)) {
            Text.send(p, "<gray>They're down. Bind them with <white>Rope</white> to take them prisoner.");
            return;
        }
        Colony captor = plugin.colonies().of(p);
        if (captor == null || captor.core == null) {
            Text.send(p, "<red>You need a colony with a Town Hall to hold prisoners.");
            return;
        }
        String name = null;
        Trait trait = Trait.random();
        if (traveler) {
            var tr = plugin.travelers().traveler(t);
            if (tr != null) {
                name = tr.name;
                trait = tr.trait;
            }
            plugin.travelers().captured(t, captor);
        } else if (le.customName() != null) {
            name = PlainTextComponentSerializer.plainText().serialize(le.customName());
            if (name.contains("DOWNED")) name = null;
        }
        use(p, hand);
        downedOthers.remove(t.getUniqueId());
        Location at = le.getLocation();
        Citizen c = plugin.sim().population().create(captor, Job.UNEMPLOYED, false);
        c.name = name != null ? name : Names.random();
        c.trait = trait;
        c.status = Status.CAPTIVE;
        c.origin = traveler ? "traveller" : "wilds";
        c.escortPlayer = p.getUniqueId();
        c.remember(plugin.sim().day(captor), "Captured by " + captor.name);
        le.remove();
        Npc n = plugin.npcs().spawn(captor, c, at);
        if (n != null) {
            n.body.setLeashHolder(p);
            plugin.npcs().refresh(n);
        }
        bound(p, c.name);
        plugin.requestSave();
    }

    private void bound(Player p, String name) {
        Fx.sound(p.getLocation(), "minecraft:entity.leash_knot.place", 1f, 1f);
        Text.send(p, "You bound <white>" + Text.esc(name) + "</white>. Lead them into one of your <white>Prisons</white>, "
                + "or <yellow>Shift + Right-Click</yellow> them to leave them for your wardens.");
    }

    private static void use(Player p, ItemStack it) {
        if (p.getGameMode() == GameMode.CREATIVE) return;
        it.setAmount(it.getAmount() - 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onUnleash(EntityUnleashEvent e) {
        if (NpcManager.isBody(e.getEntity())) e.setDropLeash(false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerUnleash(PlayerUnleashEntityEvent e) {
        if (NpcManager.isBody(e.getEntity())) e.setCancelled(true);
    }

    private void handToWardens(Player p, Npc n) {
        n.c.escortPlayer = null;
        n.c.awaitingEscort = true;
        n.body.setLeashHolder(null);
        Colony col = plugin.colonies().colonyOf(n.c);
        if (col == null || col.valid(BuildingType.PRISON).isEmpty()) {
            Text.send(p, "<yellow>" + Text.esc(n.c.name) + " waits, bound. You have no prison: register one with the Blueprint Book.");
        } else if (freeCell(col, n.c) == null) {
            Text.send(p, "<yellow>" + Text.esc(n.c.name) + " waits, bound. Every prison bed is taken: add beds or build another prison.");
        } else if (col.citizens.values().stream().noneMatch(c -> c.job == Job.WARDEN && c.status.free())) {
            Text.send(p, "<yellow>" + Text.esc(n.c.name) + " waits, bound. Hire a warden to escort them, or lead them to a prison yourself.");
        } else {
            Text.send(p, "<white>" + Text.esc(n.c.name) + "</white> will be taken to a free cell by your wardens.");
        }
    }

    private void feed(Player p, Npc n, Colony col, ItemStack hand) {
        use(p, hand);
        n.c.unfedDays = 0;
        n.c.needs.eat(4, 1, plugin.settings().needs);
        n.c.resistance = Math.max(0, n.c.resistance - 2);
        n.body.getWorld().spawnParticle(Particle.HEART, n.loc().add(0, 1.8, 0), 3, 0.3, 0.2, 0.3, 0);
        Fx.sound(n.loc(), "minecraft:entity.generic.eat", 0.8f, 1f);
        Text.send(p, "You fed <white>" + Text.esc(n.c.name) + "</white>. <gray>Resistance " + Math.round(n.c.resistance) + "%");
        plugin.npcs().rename(n);
        if (n.c.resistance <= 0 && n.c.origin != null) convert(col, n.c);
    }

    /** A captive led by a player near them keeps their body even when the captor's colony unloads. */
    public boolean escorted(Npc n) {
        if (n.c.status != Status.CAPTIVE || n.c.escortPlayer == null) return false;
        Player p = Bukkit.getPlayer(n.c.escortPlayer);
        return p != null && p.getWorld() == n.body.getWorld() && p.getLocation().distanceSquared(n.loc()) < 32 * 32;
    }

    // ───────────── cells ─────────────

    /** A prison with a free bed for this person, or null. */
    public Building freeCell(Colony col, Citizen who) {
        for (Building b : col.valid(BuildingType.PRISON)) if (room(col, b, who)) return b;
        return null;
    }

    private boolean room(Colony col, Building cell, Citizen who) {
        int held = 0;
        for (Citizen c : col.citizens.values()) if (c != who && c.cell == cell.id && c.status == Status.PRISONER) held++;
        return held < Math.max(1, cell.beds.size());
    }

    /**
     * Puts one of the colony's own citizens in prison for a sentence (theft, rioting). Returns false if there's
     * no free cell.
     */
    public boolean imprison(Colony col, Citizen c, long untilDay, String crime) {
        Building cell = freeCell(col, c);
        if (cell == null) return false;
        lockUp(col, c, cell);
        c.origin = null;
        c.resistance = 0;
        c.sentenceUntil = untilDay;
        c.remember(plugin.sim().day(col), "Jailed for " + crime + " until day " + untilDay);
        return true;
    }

    private void lockUp(Colony col, Citizen c, Building cell) {
        plugin.sim().logistics().abandon(col, c);
        c.status = Status.PRISONER;
        c.cell = cell.id;
        c.home = 0;
        c.bed = null;
        c.escortPlayer = null;
        c.awaitingEscort = false;
        c.unfedDays = 0;
        Npc n = plugin.npcs().npc(c);
        if (n != null) {
            n.body.setLeashHolder(null);
            n.resetWork();
            n.mover.stop();
            if (!cell.doors.isEmpty() && cell.doors.get(0).loaded(n.body.getWorld())) n.mover.passDoor(cell.doors.get(0).block(n.body.getWorld()), plugin.tick(), 120);
            plugin.npcs().refresh(n);
        }
        plugin.sim().housing().assign(col);
        plugin.sim().jobs().refreshWorkplaces(col);
        plugin.requestSave();
    }

    /** An enemy captive is locked up: their resistance to joining is set. */
    private void imprisonCaptive(Colony col, Citizen c, Building cell) {
        Settings s = plugin.settings();
        lockUp(col, c, cell);
        int base = ThreadLocalRandom.current().nextInt(s.resistanceMin, s.resistanceMax + 1);
        c.resistance = Math.max(1, Math.min(100, base + c.trait.resistance()));
        c.lastVisitDay = -1;
        c.sentenceUntil = -1;
        col.log(ColonyEvent.Type.MILITARY, plugin.sim().day(col), c.name + " was locked in " + cell.label() + " (resistance " + Math.round(c.resistance) + "%).");
        for (Player p : col.onlineMembers()) {
            Text.send(p, "<yellow>" + Text.esc(c.name) + "</yellow> was locked in " + cell.label() + ". <gray>Resistance <white>" + Math.round(c.resistance)
                    + "%</white>: wardens will feed them and win them over, or ransom them back.");
        }
    }

    public Location respawnSpot(Colony col, Citizen c) {
        if (c.status != Status.PRISONER) return null;
        Building cell = col.building(c.cell);
        return cell == null ? null : cellSpot(col, cell);
    }

    // ───────────── bodies of captives and prisoners ─────────────

    public void think(Npc n, Colony col, long now) {
        if (n.c.status == Status.CAPTIVE) captive(n, col, now);
        else prisoner(n, col, now);
    }

    private void captive(Npc n, Colony col, long now) {
        Citizen c = n.c;
        Player p = c.escortPlayer == null ? null : Bukkit.getPlayer(c.escortPlayer);
        if (p != null && p.getWorld() == n.body.getWorld()) {
            follow(n, p.getLocation());
            if (!n.body.isLeashed()) n.body.setLeashHolder(p);
            n.doing("Bound, led by " + p.getName());
            checkCell(n, col);
            return;
        }
        if (c.escortPlayer != null) {
            c.escortPlayer = null;
            c.awaitingEscort = true;
            n.body.setLeashHolder(null);
        }
        Npc guard = null;
        if (c.escortGuard != null) {
            Citizen g = plugin.colonies().citizen(c.escortGuard);
            guard = g == null ? null : plugin.npcs().npc(g);
        }
        if (guard != null) {
            follow(n, guard.loc());
            n.doing("Bound, escorted by " + guard.c.name);
            checkCell(n, col);
            return;
        }
        c.awaitingEscort = true;
        n.mover.stop();
        n.doing(col.valid(BuildingType.PRISON).isEmpty() ? "Bound: there's no prison!" : "Bound, waiting for a warden");
    }

    private void follow(Npc n, Location to) {
        Location me = n.loc();
        double d = me.distance(to);
        if (d > 48) {
            Location s = Mover.safeSpot(to);
            if (s != null) {
                n.body.teleport(s);
                n.mover.sync();
            }
        } else if (d > 2.6) {
            n.mover.moveTo(to, Math.min(0.34, plugin.settings().runSpeed + (d - 2.6) * 0.02), 2.0);
        } else {
            n.mover.stop();
        }
    }

    /** A captive standing in (or at the door of) a prison with a free bed is locked up. */
    private void checkCell(Npc n, Colony col) {
        Location at = n.loc();
        for (Building cell : col.valid(BuildingType.PRISON)) {
            boolean in = cell.inside(at.getX(), at.getY(), at.getZ());
            if (!in) {
                Location out = outsideDoor(col, cell);
                in = out != null && out.getWorld() == at.getWorld() && out.distanceSquared(at) < 2.5 * 2.5;
            }
            if (!in) continue;
            if (!room(col, cell, n.c)) {
                Player p = n.c.escortPlayer == null ? null : Bukkit.getPlayer(n.c.escortPlayer);
                if (p != null) Text.bar(p, "<red>" + cell.label() + " is full.");
                continue;
            }
            Citizen g = n.c.escortGuard == null ? null : plugin.colonies().citizen(n.c.escortGuard);
            n.c.escortGuard = null;
            if (g != null) {
                Npc gn = plugin.npcs().npc(g);
                if (gn != null) gn.visiting = null;
            }
            imprisonCaptive(col, n.c, cell);
            return;
        }
    }

    private void prisoner(Npc n, Colony col, long now) {
        Building cell = col.building(n.c.cell);
        if (cell == null) {
            // the prison is gone: they're loose and bound again until someone rehouses them
            n.c.status = Status.CAPTIVE;
            n.c.awaitingEscort = true;
            n.c.cell = 0;
            plugin.npcs().refresh(n);
            return;
        }
        Location here = n.loc();
        if (!n.sleeping && !cell.inside(here.getX(), here.getY(), here.getZ())) {
            Location in = cellSpot(col, cell);
            if (in != null) {
                if (!cell.doors.isEmpty() && cell.doors.get(0).loaded(n.body.getWorld())) n.mover.passDoor(cell.doors.get(0).block(n.body.getWorld()), now, 60);
                n.mover.moveTo(in, plugin.settings().walkSpeed, 0.8);
            }
            n.doing("Being taken back to " + cell.label());
            return;
        }
        if (plugin.npcs().phase(col, n.body.getWorld()) == NpcManager.Phase.NIGHT) {
            plugin.npcs().brain().sleep(n, col, now);
            n.doing(n.sleeping ? "Sleeping in " + cell.label() : "Locked in " + cell.label());
            return;
        }
        if (n.sleeping) plugin.npcs().brain().wake(n);
        n.mover.stop();
        n.doing(n.c.origin == null ? "Serving a sentence in " + cell.label() + " (until day " + n.c.sentenceUntil + ")"
                : "Locked in " + cell.label() + " (resistance " + Math.round(n.c.resistance) + "%)");
    }

    // ───────────── wardens ─────────────

    /** A warden's work: bring waiting captives to cells, and visit each prisoner once a day. False if there's nothing to do. */
    public boolean warden(Npc w, Colony col, Building prison, long now) {
        // escort a waiting captive
        for (Citizen c : col.citizens.values()) {
            if (c.status != Status.CAPTIVE || !c.awaitingEscort || c.downed()) continue;
            if (c.escortGuard != null && !c.escortGuard.equals(w.c.id)) continue;
            Npc cn = plugin.npcs().npc(c);
            Building cell = freeCell(col, c);
            if (cn == null || cell == null) continue;
            if (c.escortGuard == null) {
                if (!w.mover.near(cn.loc(), 2.2)) {
                    w.mover.moveTo(cn.loc(), plugin.settings().walkSpeed, 1.8);
                    w.doing("Collecting captive " + c.name);
                    return true;
                }
                c.escortGuard = w.c.id;
                cn.body.setLeashHolder(w.body);
            }
            Location target = outsideDoor(col, cell);
            if (target == null) target = cellSpot(col, cell);
            if (target == null) return false;
            if (!w.mover.near(target, 1.8)) {
                w.mover.moveTo(target, plugin.settings().walkSpeed, 1.4);
                w.doing("Escorting " + c.name + " to " + cell.label());
                w.working = true;
                return true;
            }
            c.escortGuard = null;
            imprisonCaptive(col, c, cell);
            return true;
        }
        // the daily visit
        long day = plugin.sim().day(col);
        for (Citizen p : col.citizens.values()) {
            if (p.status != Status.PRISONER || p.lastVisitDay == day) continue;
            Building cell = col.building(p.cell);
            if (cell == null) continue;
            Location door = outsideDoor(col, cell);
            if (door == null) door = cellSpot(col, cell);
            if (door == null) continue;
            if (!w.mover.near(door, 2)) {
                w.mover.moveTo(door, plugin.settings().walkSpeed, 1.6);
                w.doing("Going to see prisoner " + p.name);
                return true;
            }
            w.mover.stop();
            w.working = true;
            visit(col, p, w.c);
            w.doing(p.origin == null ? "Checked on " + p.name : "Talked with " + p.name + " (resistance " + Math.round(p.resistance) + "%)");
            return true;
        }
        return false;
    }

    /** A warden's visit: a meal from storage, and (for enemies) an hour of persuasion. */
    private void visit(Colony col, Citizen p, Citizen warden) {
        Settings s = plugin.settings();
        long day = plugin.sim().day(col);
        p.lastVisitDay = day;
        CitizenSim.Context ctx = plugin.sim().citizens().context(col);
        boolean wages = ctx.wages;
        ctx.wages = false;
        double ate = plugin.sim().citizens().eatFrom(col, p, col.storage, p.needs.appetite(s.needs), ctx, 0, 0);
        ctx.wages = wages;
        boolean fed = ate > 0;
        if (fed) p.unfedDays = 0;
        if (p.origin == null) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double amount = r.nextInt(s.persuasionMin, s.persuasionMax + 1) * (0.6 + col.stability / 100.0 * 0.8) * (fed ? 1 : 0.5)
                * (1 + 0.05 * warden.level(com.colonysmp.core.Skill.ADMINISTRATION)) * (warden.trait == Trait.CHARISMATIC ? 1.3 : 1);
        p.resistance = Math.max(0, p.resistance - amount);
        warden.skills.gain(com.colonysmp.core.Skill.ADMINISTRATION, 5 * warden.trait.learning());
        Npc pn = plugin.npcs().npc(p);
        if (pn != null) {
            Fx.sound(pn.loc(), fed ? "minecraft:entity.generic.eat" : "minecraft:entity.villager.no", 0.7f, 1f);
            plugin.npcs().rename(pn);
        }
        if (p.resistance <= 0) convert(col, p);
    }

    /** Resistance broken: they join the colony as a citizen. */
    public void convert(Colony col, Citizen p) {
        long day = plugin.sim().day(col);
        Building cell = col.building(p.cell);
        p.status = Status.CITIZEN;
        p.cell = 0;
        p.resistance = 0;
        String from = p.origin;
        p.origin = null;
        p.joinedDay = day;
        p.remember(day, "Joined " + col.name);
        col.addStability(1);
        plugin.sim().housing().assign(col);
        plugin.sim().jobs().autoAssign(col, p);
        Npc n = plugin.npcs().npc(p);
        if (n != null) {
            if (cell != null && !cell.doors.isEmpty() && cell.doors.get(0).loaded(n.body.getWorld())) n.mover.passDoor(cell.doors.get(0).block(n.body.getWorld()), plugin.tick(), 400);
            plugin.npcs().refresh(n);
            n.body.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, n.loc().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.2);
        }
        Colony old = from == null ? null : plugin.colonies().get(from);
        col.log(ColonyEvent.Type.POPULATION, day, p.name + (old != null ? ", once of " + old.name + "," : "") + " has joined the colony as " + Text.a(p.job.display) + ".");
        for (Player m : col.onlineMembers()) {
            Text.title(m, "<gold>A New Citizen", "<white>" + Text.esc(p.name) + "</white> <gray>has joined " + Text.esc(col.name), 10, 60, 20);
        }
        plugin.requestSave();
    }

    /** Lets a prisoner go home (or simply leave). */
    public String release(Player by, Colony col, Citizen c) {
        if (c.status != Status.PRISONER && c.status != Status.CAPTIVE) return Text.esc(c.name) + " isn't a prisoner.";
        Colony home = c.origin == null ? null : plugin.colonies().get(c.origin);
        long day = plugin.sim().day(col);
        if (c.origin == null) {
            // one of our own: free again
            free(col, c);
            col.log(ColonyEvent.Type.CRIME, day, c.name + " was released from prison" + (by == null ? "." : " by " + by.getName() + "."));
            return null;
        }
        if (c.origin.equals("traveller")) col.reputation = Math.min(100, col.reputation + 3);
        if (home != null) {
            sendHome(col, home, c);
            plugin.diplomacy().relation(col, home).change(5);
        } else {
            Npc n = plugin.npcs().npc(c);
            if (n != null) n.body.getWorld().spawnParticle(Particle.CLOUD, n.loc().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
            plugin.sim().population().leave(col, c, "was released and left the colony");
        }
        return null;
    }

    private void free(Colony col, Citizen c) {
        Building cell = col.building(c.cell);
        c.status = Status.CITIZEN;
        c.cell = 0;
        c.sentenceUntil = -1;
        plugin.sim().housing().assign(col);
        plugin.sim().jobs().refreshWorkplaces(col);
        Npc n = plugin.npcs().npc(c);
        if (n != null) {
            if (cell != null && !cell.doors.isEmpty() && cell.doors.get(0).loaded(n.body.getWorld())) n.mover.passDoor(cell.doors.get(0).block(n.body.getWorld()), plugin.tick(), 400);
            plugin.npcs().refresh(n);
        }
    }

    /** A prisoner goes back to the colony they came from. */
    private void sendHome(Colony captor, Colony home, Citizen c) {
        long day = plugin.sim().day(captor);
        plugin.npcs().despawn(c);
        c.status = Status.CITIZEN;
        c.cell = 0;
        c.origin = null;
        c.resistance = 0;
        c.escortPlayer = null;
        c.escortGuard = null;
        c.hasLast = false;
        plugin.colonies().transferCitizen(c, home);
        c.remember(day, "Released by " + captor.name + " and came home");
        plugin.sim().housing().assign(captor);
        plugin.sim().housing().assign(home);
        plugin.sim().jobs().autoAssign(home, c);
        captor.log(ColonyEvent.Type.DIPLOMACY, day, c.name + " was sent home to " + home.name + ".");
        home.log(ColonyEvent.Type.DIPLOMACY, plugin.sim().day(home), c.name + " came home from captivity in " + captor.name + ".");
        for (Player m : home.onlineMembers()) Text.send(m, "<green>" + Text.esc(c.name) + " has come home from captivity in " + Text.esc(captor.name) + ".");
        plugin.requestSave();
    }

    /** Ransoms and prisoner exchanges agreed through diplomacy. Returns why it can't be done, or null. */
    public String settle(Proposal p, Colony from, Colony to) {
        // the proposer releases these prisoners (held by the proposer, from the other side)
        for (UUID id : p.release) {
            Citizen c = from.citizens.get(id);
            if (c == null || c.status != Status.PRISONER || !to.id.equals(c.origin)) return "A prisoner in the offer is no longer held by " + Text.esc(from.name) + ".";
        }
        // and asks for these (held by the other side, originally the proposer's)
        for (UUID id : p.demand) {
            Citizen c = to.citizens.get(id);
            if (c == null || c.status != Status.PRISONER || !from.id.equals(c.origin)) return "A prisoner asked for is no longer held by " + Text.esc(to.name) + ".";
        }
        for (UUID id : p.release) sendHome(from, to, from.citizens.get(id));
        for (UUID id : p.demand) sendHome(to, from, to.citizens.get(id));
        Relation r = plugin.diplomacy().relation(from, to);
        r.change(5 + 2 * (p.release.size() + p.demand.size()));
        return null;
    }

    /** The ransom asked for a prisoner (more for skilled and trained people). */
    public long ransom(Citizen c) {
        double v = plugin.settings().ransomBase * (1 + c.education / 50 + c.training);
        for (var e : c.skills.all().entrySet()) v += e.getValue() / 100;
        return Math.round(v);
    }

    // ───────────── the day ─────────────

    /** Once a day: meals for prisoners nobody visited, starvation, escapes, the end of sentences. */
    public void daily(Colony col, long day) {
        Settings s = plugin.settings();
        boolean wardens = false;
        for (Citizen c : col.citizens.values()) if (c.job == Job.WARDEN && c.status.free() && c.workplace != 0) wardens = true;
        for (Citizen p : new ArrayList<>(col.citizens.values())) {
            if (p.status == Status.CAPTIVE && !plugin.npcs().embodied(p)) {
                // a captive left unattended in an unloaded colony is put in a cell if there's one, or slips away
                Building cell = freeCell(col, p);
                if (cell != null) imprisonCaptive(col, p, cell);
                else {
                    plugin.sim().population().leave(col, p, "slipped their bonds and escaped");
                    continue;
                }
            }
            if (p.status != Status.PRISONER) continue;
            if (p.origin == null && p.sentenceUntil >= 0 && day >= p.sentenceUntil) {
                free(col, p);
                p.remember(day, "Served their sentence");
                col.log(ColonyEvent.Type.CRIME, day, p.name + " has served their sentence and is free again.");
                continue;
            }
            // nobody came: a warden in the background still does the rounds
            if (p.lastVisitDay != day && wardens && !plugin.npcs().embodied(p)) {
                Citizen w = null;
                for (Citizen c : col.citizens.values()) if (c.job == Job.WARDEN && c.status.free()) w = c;
                if (w != null) {
                    visit(col, p, w);
                    if (p.status != Status.PRISONER) continue;
                }
            }
            if (p.lastVisitDay < day - 1 || p.needs.satiety < 10) p.unfedDays++;
            if (p.unfedDays >= s.starveDays) {
                plugin.sim().population().die(col, p, "starved in prison");
                continue;
            }
            // with nobody watching, the determined slip out
            if (!wardens && p.origin != null && ThreadLocalRandom.current().nextDouble() < 0.1 * (p.resistance / 100.0)) {
                Colony home = plugin.colonies().get(p.origin);
                if (home != null) sendHome(col, home, p);
                else plugin.sim().population().leave(col, p, "escaped from prison");
            }
        }
    }

    // ───────────── cells ─────────────

    /** A spot inside a cell to stand. */
    public Location cellSpot(Colony col, Building cell) {
        World w = col.world();
        if (w == null || cell == null || cell.min == null) return null;
        List<Location> spots = new ArrayList<>();
        for (int x = cell.min.x(); x <= cell.max.x(); x++) {
            for (int z = cell.min.z(); z <= cell.max.z(); z++) {
                for (int y = cell.min.y(); y <= Math.min(cell.max.y(), cell.min.y() + 1); y++) {
                    if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                    Block b = w.getBlockAt(x, y, z);
                    if (b.isPassable() && b.getRelative(BlockFace.UP).isPassable() && b.getRelative(BlockFace.DOWN).getType().isSolid()
                            && !org.bukkit.Tag.BEDS.isTagged(b.getRelative(BlockFace.DOWN).getType())) {
                        spots.add(new Location(w, x + 0.5, y, z + 0.5));
                    }
                }
            }
        }
        if (spots.isEmpty()) {
            if (!cell.beds.isEmpty()) return cell.beds.get(0).center(w).add(0, 0.6, 0);
            return cell.anchor == null ? null : cell.anchor.center(w);
        }
        return spots.get(ThreadLocalRandom.current().nextInt(spots.size()));
    }

    /** The standing spot just outside a cell's door. */
    public Location outsideDoor(Colony col, Building cell) {
        World w = col.world();
        if (w == null || cell == null || cell.doors.isEmpty()) return null;
        BlockPos door = cell.doors.get(0);
        if (!door.loaded(w)) return null;
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            BlockPos o = door.relative(f);
            if (cell.contains(o)) continue;
            Location l = Mover.safeSpot(o.center(w));
            if (l != null && !cell.inside(l.getX(), l.getY(), l.getZ())) return l;
        }
        return null;
    }

    public List<Citizen> prisoners(Colony col) {
        List<Citizen> out = new ArrayList<>();
        for (Citizen c : col.citizens.values()) if (c.status == Status.PRISONER || c.status == Status.CAPTIVE) out.add(c);
        return out;
    }
}
