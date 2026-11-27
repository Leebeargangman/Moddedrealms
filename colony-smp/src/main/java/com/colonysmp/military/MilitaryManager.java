package com.colonysmp.military;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Goods;
import com.colonysmp.core.MilitaryClass;
import com.colonysmp.core.Morale;
import com.colonysmp.core.TxnType;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Job;
import com.colonysmp.model.Perm;
import com.colonysmp.model.Squad;
import com.colonysmp.npc.Npc;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Items;
import com.colonysmp.util.Keys;
import com.colonysmp.util.Text;
import com.colonysmp.util.Tools;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The colony's army: recruiting soldiers into classes (each needs its arms, some a training building), arming
 * them from the armoury, training and upkeep, squads with orders and formations, the Command Baton, war horses
 * for the cavalry, and morale. Combat itself is in {@link SoldierBrain} and {@link CombatListener}.
 */
public final class MilitaryManager implements Listener {

    private final ColonySMP plugin;
    private final SoldierBrain brain;
    /** Colony id -> until (ms): something hostile was seen. */
    private final Map<String, Long> alarm = new HashMap<>();
    /** Where someone last called for the watch (attacked or chased there), and until when the call stands (ms). */
    private final Map<String, Location> callAt = new HashMap<>();
    private final Map<String, Long> callUntil = new HashMap<>();
    /** Player -> selected squad id (for the baton). */
    private final Map<UUID, Integer> selected = new HashMap<>();

    public MilitaryManager(ColonySMP plugin) {
        this.plugin = plugin;
        this.brain = new SoldierBrain(plugin, this);
    }

    public SoldierBrain brain() {
        return brain;
    }

    // ───────────── state ─────────────

    public int soldiers(Colony c) {
        int n = 0;
        for (Citizen ct : c.citizens.values()) if (ct.status.free() && ct.job.military()) n++;
        return n;
    }

    public List<Citizen> army(Colony c) {
        List<Citizen> out = new ArrayList<>();
        for (Citizen ct : c.citizens.values()) if (ct.status.free() && ct.job == Job.SOLDIER) out.add(ct);
        return out;
    }

    /** Most soldiers the colony can keep: a few without barracks, plus a barracks bed each. */
    public int capacity(Colony c) {
        int beds = 0;
        for (Building b : c.valid(BuildingType.BARRACKS)) beds += b.beds.size();
        return plugin.settings().freeSoldiers + beds;
    }

    /** Why the colony can't take another soldier, or null. */
    public String room(Colony c) {
        int cap = capacity(c);
        if (army(c).size() >= cap) return "The army is full (" + cap + "): build or upgrade barracks for more soldiers.";
        return null;
    }

    /** Something hostile was seen near the colony. */
    public void markThreat(Colony c) {
        alarm.put(c.id, System.currentTimeMillis() + 30_000);
    }

    /**
     * A shout for the watch: something hostile is at this spot. Guards come running, but the colony isn't put
     * on alert for it (one zombie in the night is the guards' business, not everyone's).
     */
    public void callWatch(Colony c, Location at) {
        if (at == null || at.getWorld() == null) return;
        callAt.put(c.id, at.clone());
        callUntil.put(c.id, System.currentTimeMillis() + 15_000);
    }

    /** Where the watch was last called to, while the call stands; null when all is quiet. */
    public Location alarmPoint(Colony c) {
        Long until = callUntil.get(c.id);
        return until != null && until > System.currentTimeMillis() ? callAt.get(c.id) : null;
    }

    public boolean threatened(Colony c) {
        Long until = alarm.get(c.id);
        return until != null && until > System.currentTimeMillis();
    }

    /** Soldiers stand to: a war, an alarm, or a mobilisation. */
    public boolean alert(Colony c) {
        return plugin.wars().fighting(c) || plugin.wars().warmup(c) || threatened(c) || c.mobilized();
    }

    // ───────────── recruiting ─────────────

    /** What a class still needs to be recruited and fully armed (null if nothing is missing). */
    public String missingFor(Colony c, MilitaryClass k) {
        List<String> out = new ArrayList<>();
        if (k.training != null) {
            BuildingType t = BuildingType.parse(k.training);
            if (t != null && c.valid(t).isEmpty()) out.add(Text.a(t.display));
        }
        for (MilitaryClass.Arms a : k.arms) if (!available(c, a)) out.add(a.display);
        return out.isEmpty() ? null : String.join(", ", out);
    }

    private boolean available(Colony c, MilitaryClass.Arms a) {
        return switch (a) {
            case MELEE -> c.equipment.has(it -> Tools.isMelee(Tools.kind(it)));
            case SHIELD -> c.equipment.has(it -> Tools.kind(it) == Tools.Kind.SHIELD);
            case BOW -> c.equipment.has(it -> Tools.kind(it) == Tools.Kind.BOW);
            case CROSSBOW -> c.equipment.has(it -> Tools.kind(it) == Tools.Kind.CROSSBOW);
            case ARROWS -> c.storage.available("arrow") > 0;
            case JAVELINS -> c.storage.available(Goods.JAVELIN) > 0;
            case SLING_STONES -> c.storage.available("cobblestone") > 0;
            case HORSE -> c.storage.available(Goods.WAR_HORSE) > 0;
            case MEDICINE -> c.storage.available(Goods.MEDICINE) > 0;
            case SIEGE_CHARGES -> c.storage.available(Goods.SIEGE_CHARGE) > 0;
            case BANNER -> banner(c) != null;
        };
    }

    private static String banner(Colony c) {
        for (String k : c.storage.contents().keySet()) if (k.endsWith("_banner") && c.storage.available(k) > 0) return k;
        return null;
    }

    /** Makes a citizen a soldier of a class (or changes their class). Returns why not, or null. */
    public String recruit(Player by, Colony c, Citizen ct, MilitaryClass k) {
        if (by != null && !plugin.access().can(by, c, Perm.MILITARY)) return "You may not command " + Text.esc(c.name) + "'s army.";
        if (k == null) return "Which class?";
        String missing = missingFor(c, k);
        if (missing != null && k.training != null && missing.contains(BuildingType.parse(k.training).display)) {
            return k.display + "s need " + missing + ".";
        }
        long day = plugin.sim().day(c);
        if (ct.job != Job.SOLDIER) {
            String why = plugin.sim().jobs().canTake(c, ct, Job.SOLDIER, null);
            if (why != null) return why;
            long cost = plugin.settings().recruitSoldierCost;
            if (cost > 0 && c.treasury.debit(cost, TxnType.MILITARY, "Recruited " + ct.name + " as " + Text.a(k.display), System.currentTimeMillis(), day) == null) {
                return "Recruiting costs " + Text.value(cost) + ".";
            }
            plugin.sim().jobs().assign(c, ct, Job.SOLDIER, null);
        } else {
            returnArms(c, ct);
            ct.training *= 0.5;
        }
        ct.militaryClass = k;
        ct.morale = Morale.baseline(k, ct.training, ct.needs.mood, true);
        equip(c, ct, true);
        plugin.npcs().jobChanged(ct);
        c.log(ColonyEvent.Type.MILITARY, day, ct.name + " is now " + Text.a(k.display) + (missing != null ? " (still missing " + missing + ")" : "") + ".");
        plugin.requestSave();
        return missing == null ? null : "!" + ct.name + " joined as " + Text.a(k.display) + ", but the armoury is missing " + missing + ".";
    }

    /** Leaving the army (or the watch): arms go back to the armoury and the horse to the stables. */
    public void discharge(Colony c, Citizen ct, boolean staysMilitary) {
        returnArms(c, ct);
        if (!staysMilitary) {
            ct.squad = 0;
            ct.training = 0;
        }
    }

    private void returnArms(Colony c, Citizen ct) {
        for (ItemStack it : new ItemStack[]{ct.weapon, ct.shield, ct.ranged, ct.armor[0], ct.armor[1], ct.armor[2], ct.armor[3]}) {
            if (it == null || it.getType().isAir()) continue;
            // plain goods (a standard bearer's banner) go back to storage, equipment to the armoury
            String good = com.colonysmp.util.ItemGoods.goodOf(it);
            if (good != null) c.storage.force(good, it.getAmount());
            else c.equipment.force(it);
        }
        ct.weapon = ct.shield = ct.ranged = null;
        java.util.Arrays.fill(ct.armor, null);
        String ammo = ammoGood(ct);
        if (ct.ammo > 0 && ammo != null) c.storage.force(ammo, ct.ammo);
        ct.ammo = 0;
        if (ct.horse) {
            c.storage.force(Goods.WAR_HORSE, 1);
            ct.horse = false;
            Npc n = plugin.npcs().npc(ct);
            if (n != null && n.mount != null) {
                n.mount.remove();
                n.mount = null;
                n.mover.setVehicle(null);
            }
        }
    }

    /** The good a citizen's ammunition count refers to. */
    public static String ammoGood(Citizen ct) {
        MilitaryClass k = ct.militaryClass;
        if (k == MilitaryClass.JAVELINEER) return Goods.JAVELIN;
        if (k == MilitaryClass.MEDIC) return Goods.MEDICINE;
        if (k == MilitaryClass.SAPPER || k == MilitaryClass.SIEGE_ENGINEER) return Goods.SIEGE_CHARGE;
        if (k == MilitaryClass.SLINGER) return "cobblestone";
        if (ct.ranged != null || k != null && k.arms.contains(MilitaryClass.Arms.ARROWS)) return "arrow";
        return ct.ammo > 0 ? "arrow" : null;
    }

    // ───────────── arms ─────────────

    /** Does this fighter want something the armoury has? */
    public boolean wantsGear(Colony c, Citizen ct, boolean alert) {
        MilitaryClass k = ct.militaryClass;
        if (ct.weapon == null && (k == null || k.arms.contains(MilitaryClass.Arms.MELEE)) && c.equipment.has(it -> Tools.isMelee(Tools.kind(it)))) return true;
        if (k != null) {
            if (k.arms.contains(MilitaryClass.Arms.SHIELD) && ct.shield == null && available(c, MilitaryClass.Arms.SHIELD)) return true;
            if (k.arms.contains(MilitaryClass.Arms.BOW) && ct.ranged == null && available(c, MilitaryClass.Arms.BOW)) return true;
            if (k.arms.contains(MilitaryClass.Arms.CROSSBOW) && ct.ranged == null && available(c, MilitaryClass.Arms.CROSSBOW)) return true;
            if (k.arms.contains(MilitaryClass.Arms.HORSE) && !ct.horse && available(c, MilitaryClass.Arms.HORSE)) return true;
            String ammo = ammoGood(ct);
            if (ammo != null && ct.ammo < (k.shooter() ? 16 : 2) && c.storage.available(ammo) > 0) return true;
        }
        if (!alert && ct.job != Job.SOLDIER) return false;
        Tools.Kind[] slots = {Tools.Kind.BOOTS, Tools.Kind.LEGGINGS, Tools.Kind.CHESTPLATE, Tools.Kind.HELMET};
        for (int i = 0; i < 4; i++) {
            Tools.Kind kind = slots[i];
            if (c.equipment.bestScore(it -> Tools.kind(it) == kind, Tools::score) > Tools.score(ct.armor[i])) return true;
        }
        return false;
    }

    /** Swaps in anything better from the armoury (what it replaces goes back in). */
    public void equip(Colony c, Citizen ct, boolean full) {
        MilitaryClass k = ct.militaryClass;
        if (k == null || k.arms.contains(MilitaryClass.Arms.MELEE)) ct.weapon = swap(c, ct.weapon, melee(k));
        if (k == null) {
            if (full) {
                ct.ranged = swap(c, ct.ranged, it -> Tools.kind(it) == Tools.Kind.BOW);
                ammo(c, ct, "arrow", 32);
            }
        } else {
            if (k.arms.contains(MilitaryClass.Arms.SHIELD) || k.shield) ct.shield = swap(c, ct.shield, it -> Tools.kind(it) == Tools.Kind.SHIELD);
            if (k.arms.contains(MilitaryClass.Arms.BOW)) ct.ranged = swap(c, ct.ranged, it -> Tools.kind(it) == Tools.Kind.BOW);
            if (k.arms.contains(MilitaryClass.Arms.CROSSBOW)) ct.ranged = swap(c, ct.ranged, it -> Tools.kind(it) == Tools.Kind.CROSSBOW);
            String ammo = ammoGood(ct);
            if (ammo != null) ammo(c, ct, ammo, k.shooter() ? 48 : 8);
            if (k.arms.contains(MilitaryClass.Arms.HORSE) && !ct.horse && c.storage.take(Goods.WAR_HORSE, 1) == 1) {
                ct.horse = true;
                ct.horseHealth = 30;
            }
            if (k.arms.contains(MilitaryClass.Arms.BANNER) && ct.weapon == null) {
                String b = banner(c);
                if (b != null && c.storage.take(b, 1) == 1) ct.weapon = com.colonysmp.util.ItemGoods.item(b, 1);
            }
        }
        if (!full && ct.job != Job.SOLDIER) return;
        Tools.Kind[] slots = {Tools.Kind.BOOTS, Tools.Kind.LEGGINGS, Tools.Kind.CHESTPLATE, Tools.Kind.HELMET};
        for (int i = 0; i < 4; i++) {
            Tools.Kind kind = slots[i];
            ct.armor[i] = swap(c, ct.armor[i], it -> Tools.kind(it) == kind);
        }
    }

    private static Predicate<ItemStack> melee(MilitaryClass k) {
        if (k == MilitaryClass.AXEMAN) return it -> Tools.kind(it) == Tools.Kind.AXE;
        if (k == MilitaryClass.MACEMAN) return it -> Tools.kind(it) == Tools.Kind.MACE || Tools.kind(it) == Tools.Kind.AXE;
        if (k == MilitaryClass.SPEARMAN || k == MilitaryClass.PIKEMAN || k == MilitaryClass.LANCER) return it -> Tools.kind(it) == Tools.Kind.TRIDENT || Tools.isMelee(Tools.kind(it));
        return it -> Tools.isMelee(Tools.kind(it));
    }

    private void ammo(Colony c, Citizen ct, String good, int upTo) {
        if (ct.ammo >= upTo) return;
        long got = c.storage.take(good, upTo - ct.ammo);
        ct.ammo += (int) got;
    }

    private ItemStack swap(Colony c, ItemStack current, Predicate<ItemStack> kind) {
        double have = Tools.score(current);
        if (c.equipment.bestScore(kind, Tools::score) <= have) return current;
        ItemStack better = c.equipment.takeBest(kind, Tools::score);
        if (better == null) return current;
        if (current != null) c.equipment.force(current);
        return better;
    }

    // ───────────── training, upkeep, morale ─────────────

    /** Drill: training rises (faster at the class's training building). */
    public void train(Colony c, Citizen ct, double hours) {
        MilitaryClass k = ct.militaryClass;
        double rate = plugin.settings().trainingPerHour * ct.trait.learning() * (1 + c.bonus(com.colonysmp.core.Research.Effect.TRAINING));
        if (k != null && k.training != null) {
            BuildingType t = BuildingType.parse(k.training);
            if (t == null || c.valid(t).isEmpty()) rate *= 0.3;
        }
        ct.training = Math.min(1, ct.training + rate * hours);
        if (ct.militaryClass != null) {
            var skill = ct.militaryClass.shooter() ? com.colonysmp.core.Skill.ARCHERY : ct.militaryClass.mounted() ? com.colonysmp.core.Skill.HORSEMANSHIP : com.colonysmp.core.Skill.MELEE;
            ct.skills.gain(skill, hours * 8 * ct.trait.learning());
        }
    }

    /** Soldiers without a body: morale settles toward its baseline. */
    public void background(Colony c, double hours) {
        for (Citizen ct : c.citizens.values()) {
            if (ct.job != Job.SOLDIER || !ct.status.free() || plugin.npcs().embodied(ct)) continue;
            double base = Morale.baseline(ct.militaryClass, ct.training, ct.needs.mood, ct.needs.satiety > 20);
            double step = 5 * hours;
            ct.morale = Math.abs(base - ct.morale) <= step ? base : ct.morale + Math.signum(base - ct.morale) * step;
        }
    }

    /** Once a day: report soldiers lacking arms, resupply ammunition. */
    public void daily(Colony c, long day) {
        int unarmed = 0;
        for (Citizen ct : army(c)) {
            if (!plugin.npcs().embodied(ct)) equip(c, ct, false);
            if (ct.militaryClass != null && missingArms(ct) != null) unarmed++;
        }
        if (unarmed > 0) c.log(ColonyEvent.Type.MILITARY, day, unarmed + " soldier" + (unarmed == 1 ? " lacks" : "s lack") + " their arms. Stock the armoury (/colony military).");
        int over = army(c).size() - capacity(c);
        if (over > 0) c.log(ColonyEvent.Type.MILITARY, day, "The army is " + over + " over its barracks capacity: morale suffers.");
        if (over > 0) for (Citizen ct : army(c)) ct.morale = Math.max(0, ct.morale - 5);
    }

    /** What a soldier still lacks of their class's arms, or null. */
    public String missingArms(Citizen ct) {
        MilitaryClass k = ct.militaryClass;
        if (k == null) return ct.weapon == null ? "a weapon" : null;
        List<String> out = new ArrayList<>();
        for (MilitaryClass.Arms a : k.arms) {
            boolean ok = switch (a) {
                case MELEE, BANNER -> ct.weapon != null;
                case SHIELD -> ct.shield != null;
                case BOW, CROSSBOW -> ct.ranged != null;
                case ARROWS, JAVELINS, SLING_STONES, MEDICINE, SIEGE_CHARGES -> ct.ammo > 0;
                case HORSE -> ct.horse;
                default -> true;
            };
            if (!ok) out.add(a.display);
        }
        return out.isEmpty() ? null : String.join(", ", out);
    }

    public void warEnded(Colony c, boolean won, boolean lost) {
        for (Citizen ct : army(c)) {
            if (won) ct.morale = Morale.apply(ct.morale, Morale.Event.VICTORY, false);
            if (lost) ct.morale = Morale.apply(ct.morale, Morale.Event.DEFEAT, ct.militaryClass == MilitaryClass.BERSERKER);
        }
    }

    /** A citizen in body fell: their squad loses heart, and the war keeps score. */
    public void died(Npc n, Colony col, Player killer) {
        Citizen c = n.c;
        Colony enemy = killer == null ? null : plugin.colonies().of(killer);
        if (n.body.getLastDamageCause() instanceof EntityDamageByEntityEvent e) {
            Npc other = plugin.npcs().npc(com.colonysmp.npc.NpcManager.source(e.getDamager()));
            if (other != null) {
                enemy = plugin.colonies().colonyOf(other.c);
                other.c.morale = Morale.apply(other.c.morale, Morale.Event.ENEMY_KILLED, false);
            }
        }
        plugin.wars().casualty(col, enemy);
        if (c.job != Job.SOLDIER) return;
        boolean commander = c.militaryClass == MilitaryClass.COMMANDER;
        for (Npc o : plugin.npcs().of(col)) {
            if (o == n || o.c.job != Job.SOLDIER) continue;
            boolean near = o.loc().getWorld() == n.loc().getWorld() && o.loc().distanceSquared(n.loc()) < 16 * 16;
            boolean sameSquad = c.squad != 0 && o.c.squad == c.squad;
            boolean fearless = o.c.militaryClass == MilitaryClass.BERSERKER;
            if (commander && (sameSquad || near)) o.c.morale = Morale.apply(o.c.morale, Morale.Event.COMMANDER_DIED, fearless);
            else if (near) o.c.morale = Morale.apply(o.c.morale, Morale.Event.ALLY_KILLED_NEARBY, fearless);
        }
        if (commander && c.squad != 0) {
            Squad q = col.squad(c.squad);
            if (q != null) q.commanderLost = true;
        }
    }

    // ───────────── squads ─────────────

    public Squad createSquad(Colony c, String name) {
        Squad q = new Squad(c.nextSquadId++, name == null || name.isBlank() ? "Squad " + c.nextSquadId : name);
        c.squads.put(q.id, q);
        plugin.requestSave();
        return q;
    }

    public void deleteSquad(Colony c, Squad q) {
        c.squads.remove(q.id);
        for (Citizen ct : c.citizens.values()) if (ct.squad == q.id) ct.squad = 0;
        plugin.requestSave();
    }

    public List<Citizen> members(Colony c, Squad q) {
        List<Citizen> out = new ArrayList<>();
        for (Citizen ct : c.citizens.values()) if (ct.squad == q.id && ct.job == Job.SOLDIER && ct.status.free()) out.add(ct);
        out.sort((a, b) -> a.id.compareTo(b.id));
        return out;
    }

    public String order(Player by, Colony c, Squad q, Squad.Order o, Location at) {
        if (by != null && !plugin.access().can(by, c, Perm.MILITARY)) return "You may not command " + Text.esc(c.name) + "'s army.";
        q.order = o;
        if (at != null) {
            q.world = at.getWorld().getName();
            q.rally = BlockPos.of(at);
            q.yaw = at.getYaw();
        }
        if (o == Squad.Order.FOLLOW && by != null) q.follow = by.getUniqueId();
        if (o == Squad.Order.PROTECT && by != null && q.follow == null) q.follow = by.getUniqueId();
        if (q.rally == null && (o == Squad.Order.HOLD || o == Squad.Order.SHIELD_WALL || o == Squad.Order.SPEAR_WALL || o == Squad.Order.HOLD_FIRE
                || o == Squad.Order.RETREAT || o == Squad.Order.VOLLEY)) {
            Location core = c.coreLocation();
            if (core != null) {
                q.world = core.getWorld().getName();
                q.rally = BlockPos.of(core);
            }
        }
        for (Citizen ct : members(c, q)) {
            Npc n = plugin.npcs().npc(ct);
            if (n != null) {
                n.resetWork();
                n.enemy = null;
            }
        }
        plugin.requestSave();
        return null;
    }

    public Squad selected(Player p, Colony c) {
        Integer id = selected.get(p.getUniqueId());
        Squad q = id == null ? null : c.squad(id);
        if (q == null && !c.squads.isEmpty()) {
            q = c.squads.values().iterator().next();
            selected.put(p.getUniqueId(), q.id);
        }
        return q;
    }

    public void select(Player p, Squad q) {
        selected.put(p.getUniqueId(), q.id);
    }

    // ───────────── soldiers in body ─────────────

    /** The soldier's turn; false when they're off duty (the normal routine takes over). */
    public boolean think(Npc n, Colony col, long now) {
        return brain.think(n, col, now);
    }

    /** A body appeared: cavalry mount up. */
    public void bodySpawned(Npc n, Colony col) {
        Citizen c = n.c;
        if (!c.horse || c.militaryClass == null || !c.militaryClass.mounted() || c.job != Job.SOLDIER) return;
        mount(n, col);
    }

    void mount(Npc n, Colony col) {
        if (n.mount != null && n.mount.isValid()) return;
        Location at = n.loc();
        Horse h = at.getWorld().spawn(at, Horse.class, CreatureSpawnEvent.SpawnReason.CUSTOM, horse -> {
            horse.setPersistent(false);
            horse.setRemoveWhenFarAway(false);
            horse.setTamed(true);
            horse.setAdult();
            horse.setAware(false);
            horse.getInventory().setSaddle(new ItemStack(Material.SADDLE));
            horse.getPersistentDataContainer().set(Keys.MOUNT, PersistentDataType.STRING, col.id);
            var max = horse.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) max.setBaseValue(30);
            horse.setHealth(Math.max(1, Math.min(30, n.c.horseHealth)));
            if (n.c.militaryClass == MilitaryClass.HEAVY_CAVALRY) horse.getInventory().setArmor(new ItemStack(Material.IRON_HORSE_ARMOR));
        });
        h.addPassenger(n.body);
        n.mount = h;
        n.mover.setVehicle(h);
    }

    @EventHandler
    public void onMountDeath(EntityDeathEvent e) {
        if (!e.getEntity().getPersistentDataContainer().has(Keys.MOUNT, PersistentDataType.STRING)) return;
        e.getDrops().clear();
        for (Entity p : e.getEntity().getPassengers()) {
            Npc n = plugin.npcs().npc(p);
            if (n == null) continue;
            n.c.horse = false;
            n.mount = null;
            n.mover.setVehicle(null);
            Colony col = plugin.colonies().colonyOf(n.c);
            if (col != null) col.log(ColonyEvent.Type.MILITARY, plugin.sim().day(col), n.c.name + "'s war horse was killed.");
        }
    }

    // ───────────── the Command Baton ─────────────

    /** Right-clicking one of the colony's people with the baton puts a soldier in the selected squad. */
    public boolean interact(Player p, Npc n, Colony col, ItemStack hand) {
        if (!Items.is(hand, Items.BATON)) return false;
        if (!plugin.access().can(p, col, Perm.MILITARY)) {
            Text.bar(p, "<red>You may not command " + Text.esc(col.name) + "'s army.");
            return true;
        }
        if (n.c.job != Job.SOLDIER) {
            Text.bar(p, "<gray>" + Text.esc(n.c.name) + " isn't a soldier. Recruit them from their menu or /colony recruit.");
            return true;
        }
        Squad q = selected(p, col);
        if (q == null) q = createSquad(col, "First Squad");
        select(p, q);
        n.c.squad = n.c.squad == q.id ? 0 : q.id;
        Text.bar(p, n.c.squad == 0 ? "<gray>" + Text.esc(n.c.name) + " left " + Text.esc(q.name) : "<green>" + Text.esc(n.c.name) + " joined " + Text.esc(q.name));
        plugin.requestSave();
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBaton(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !Items.is(e.getItem(), Items.BATON)) return;
        Player p = e.getPlayer();
        Colony col = plugin.colonies().of(p);
        e.setCancelled(true);
        if (col == null || !plugin.access().can(p, col, Perm.MILITARY)) {
            Text.bar(p, "<red>You don't command an army.");
            return;
        }
        Squad q = selected(p, col);
        if (q == null) {
            Text.bar(p, "<yellow>No squads yet: right-click a soldier with the baton to start one.");
            return;
        }
        Action a = e.getAction();
        if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) {
            if (!p.isSneaking()) return;
            List<Squad> all = new ArrayList<>(col.squads.values());
            int i = all.indexOf(q);
            q = all.get((i + 1) % all.size());
            select(p, q);
            Text.bar(p, "<white>Selected <gold>" + Text.esc(q.name) + "</gold> <gray>(" + members(col, q).size() + " soldiers, " + q.order.display + ")");
            return;
        }
        if (p.isSneaking()) {
            Squad.Order next = Squad.Order.values()[(q.order.ordinal() + 1) % Squad.Order.values().length];
            order(p, col, q, next, null);
            Text.bar(p, "<gold>" + Text.esc(q.name) + "</gold><gray>: <white>" + next.display + " <dark_gray>- " + next.description);
            return;
        }
        if (a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
            Location at = e.getClickedBlock().getRelative(org.bukkit.block.BlockFace.UP).getLocation().add(0.5, 0, 0.5);
            at.setYaw(p.getLocation().getYaw());
            Squad.Order o = q.order == Squad.Order.DEFEND || q.order == Squad.Order.FOLLOW || q.order == Squad.Order.PATROL ? Squad.Order.HOLD : q.order;
            order(p, col, q, o, at);
            Fx.sound(p, "minecraft:item.goat_horn.sound.0", 0.6f, 1.2f);
            Text.bar(p, "<gold>" + Text.esc(q.name) + "</gold><gray>: " + o.display + " at <white>" + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ());
        }
    }

    /** Left-clicking an enemy with the baton marks it for the selected squad. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFocus(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !Items.is(p.getInventory().getItemInMainHand(), Items.BATON)) return;
        e.setCancelled(true);
        Colony col = plugin.colonies().of(p);
        if (col == null || !plugin.access().can(p, col, Perm.MILITARY) || !(e.getEntity() instanceof LivingEntity le)) return;
        Squad q = selected(p, col);
        if (q == null) return;
        q.focus = le.getUniqueId();
        order(p, col, q, Squad.Order.FOCUS, null);
        Text.bar(p, "<red>" + Text.esc(q.name) + " focuses on " + Text.esc(le.getName()));
    }

    // ───────────── mobilisation ─────────────

    public String mobilize(Player p, Colony c) {
        if (!plugin.access().can(p, c, Perm.MILITARY)) return "You may not command " + Text.esc(c.name) + "'s army.";
        long now = System.currentTimeMillis();
        var s = plugin.settings();
        if (c.mobilized()) return "Already mobilised for " + Text.duration(c.mobilizedUntil - now) + ".";
        long ready = c.mobilizedUntil + s.mobilizeCooldownMinutes * 60_000L;
        if (now < ready) return "The people need " + Text.duration(ready - now) + " before they can be mobilised again.";
        c.mobilizedUntil = now + s.mobilizeMinutes * 60_000L;
        c.addStability(-s.mobilizeCost);
        markThreat(c);
        c.log(ColonyEvent.Type.MILITARY, plugin.sim().day(c), p.getName() + " mobilised the colony.");
        for (Player m : c.onlineMembers()) Text.title(m, "<red>⚔ TO ARMS", "<gray>" + Text.esc(c.name) + " is mobilised for " + s.mobilizeMinutes + " minutes", 10, 60, 20);
        plugin.npcs().refreshColony(c);
        return null;
    }
}
