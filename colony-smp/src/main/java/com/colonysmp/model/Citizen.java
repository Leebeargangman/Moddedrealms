package com.colonysmp.model;

import com.colonysmp.core.Ledger;
import com.colonysmp.core.MilitaryClass;
import com.colonysmp.core.Needs;
import com.colonysmp.core.Skill;
import com.colonysmp.core.SkillSet;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.ItemCodec;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything the colony knows about one person. This record is the citizen: the villager body that walks around
 * while the colony is loaded is spawned from it and written back into it, and while there's no body the
 * simulation advances the same fields in the background.
 */
public final class Citizen {

    public static final int CARRY_SLOTS = 4;
    private static final int HISTORY = 15;

    public final UUID id;
    public String colonyId;
    public String name;
    public Status status = Status.CITIZEN;
    public Job job = Job.UNEMPLOYED;
    /** Building id of the workplace (0 = none). */
    public int workplace;
    public MilitaryClass militaryClass;
    /** Squad id (0 = none). */
    public int squad;
    /** Building id of the home and the bed in it. */
    public int home;
    public BlockPos bed;
    public Trait trait = Trait.ORDINARY;
    /** Villager biome skin (plains, desert, taiga...). */
    public String skin = "plains";

    // life
    public double ageDays = 20;
    public final SkillSet skills = new SkillSet();
    public double education;
    public final Needs needs = new Needs();
    public Disease illness;
    public double illnessDays;
    public boolean treated;
    /** Seriously hurt (from fighting or an accident): rest and treatment needed before heavy work. */
    public boolean injured;
    public final Map<UUID, Bond> bonds = new LinkedHashMap<>();

    // money
    public long wallet;
    public int unpaidDays;

    // equipment issued from the armoury and stores
    public ItemStack tool, weapon, shield, ranged;
    /** Arrows, javelins or sling stones, depending on the weapon. */
    public int ammo;
    /** boots, leggings, chestplate, helmet */
    public final ItemStack[] armor = new ItemStack[4];
    public boolean horse;
    public double horseHealth = 30;
    /** What they're carrying (a satchel of four stacks) and for which haul. */
    public final Ledger carried = new Ledger("carry");
    public String carryTask;

    // soldiers
    public double training;
    public double morale = 60;

    // prisoners and criminals
    /** The prison a prisoner is held in (building id, 0 = none). */
    public int cell;
    /** The colony a prisoner was taken from (null for this colony's own criminals). */
    public String origin;
    public double resistance;
    public long lastVisitDay = -1;
    public int unfedDays;
    /** Day a criminal's sentence ends. */
    public long sentenceUntil = -1;
    public int crimes;

    /** The last day they work with a will because someone brought what they asked for (-1 = not). */
    public long motivatedUntil = -1;

    // record keeping
    public int miserableDays;
    public long joinedDay;
    public final Deque<String> history = new ArrayDeque<>();
    public double lastX, lastY, lastZ;
    public boolean hasLast;

    // runtime only
    public transient long downedUntil;
    public transient String activity = "Idle";
    public transient long activitySince;
    /** Why they're not working, when it's a shortage rather than a fault ("No seeds in storage"). */
    public transient String waiting;
    public transient UUID escortPlayer, escortGuard;
    public transient boolean awaitingEscort;
    /** Work hours done today (wages and the daily report). */
    public transient double hoursToday;
    /** The day of their last meal and the last day they ate at a restaurant. */
    public transient long lastMealDay = Long.MIN_VALUE, restaurantDay = Long.MIN_VALUE;
    public transient double grief;

    public Citizen(UUID id) {
        this.id = id;
        carried.setCapacity(CARRY_SLOTS, 0);
    }

    // ───────────── state ─────────────

    public boolean downed() {
        return downedUntil > 0;
    }

    public LifeStage stage(double adultAt, double elderAt) {
        return LifeStage.of(ageDays, adultAt, elderAt);
    }

    public boolean ill() {
        return illness != null;
    }

    public UUID partner() {
        for (Map.Entry<UUID, Bond> e : bonds.entrySet()) if (e.getValue().kind == Bond.Kind.PARTNER) return e.getKey();
        return null;
    }

    public Bond bond(UUID other) {
        return bonds.get(other);
    }

    public int level(Skill s) {
        return skills.level(s);
    }

    public String title() {
        if (status != Status.CITIZEN) return status.display;
        if (job == Job.SOLDIER && militaryClass != null) return militaryClass.display;
        return job.display;
    }

    public String educationTier() {
        if (education >= 75) return "Scholar";
        if (education >= 50) return "Educated";
        if (education >= 25) return "Literate";
        return "Unschooled";
    }

    public void remember(long day, String what) {
        history.addFirst("Day " + day + ": " + what);
        while (history.size() > HISTORY) history.removeLast();
    }

    /** Every piece of equipment they hold (for returning it to storage). */
    public List<ItemStack> gear() {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack it : new ItemStack[]{tool, weapon, shield, ranged, armor[0], armor[1], armor[2], armor[3]}) {
            if (it != null && !it.getType().isAir()) out.add(it);
        }
        return out;
    }

    public void clearGear() {
        tool = weapon = shield = ranged = null;
        java.util.Arrays.fill(armor, null);
    }

    public double armorPoints() {
        double p = 0;
        for (ItemStack it : armor) if (it != null) p += com.colonysmp.util.Tools.armorPoints(it.getType());
        return p;
    }

    // ───────────── persistence ─────────────

    public void save(ConfigurationSection s) {
        s.set("colony", colonyId);
        s.set("name", name);
        s.set("status", status.name());
        s.set("job", job.name());
        s.set("workplace", workplace);
        s.set("class", militaryClass == null ? null : militaryClass.name());
        s.set("squad", squad);
        s.set("home", home);
        s.set("bed", bed == null ? null : bed.toString());
        s.set("trait", trait.name());
        s.set("skin", skin);
        s.set("age", ageDays);
        for (Map.Entry<Skill, Double> e : skills.all().entrySet()) s.set("skills." + e.getKey().name(), e.getValue());
        s.set("education", education);
        s.set("needs.satiety", needs.satiety);
        s.set("needs.energy", needs.energy);
        s.set("needs.health", needs.health);
        s.set("needs.max-health", needs.maxHealth);
        s.set("needs.mood", needs.mood);
        s.set("needs.social", needs.social);
        s.set("needs.meal-quality", needs.mealQuality);
        s.set("illness", illness == null ? null : illness.name());
        s.set("illness-days", illnessDays);
        s.set("treated", treated);
        s.set("injured", injured);
        for (Map.Entry<UUID, Bond> e : bonds.entrySet()) {
            s.set("bonds." + e.getKey() + ".kind", e.getValue().kind.name());
            s.set("bonds." + e.getKey() + ".affinity", e.getValue().affinity);
        }
        s.set("wallet", wallet);
        s.set("unpaid-days", unpaidDays);
        s.set("tool", ItemCodec.encode(tool));
        s.set("weapon", ItemCodec.encode(weapon));
        s.set("shield", ItemCodec.encode(shield));
        s.set("ranged", ItemCodec.encode(ranged));
        s.set("ammo", ammo);
        for (int i = 0; i < 4; i++) s.set("armor." + i, ItemCodec.encode(armor[i]));
        s.set("horse", horse);
        s.set("horse-health", horseHealth);
        s.set("carry-task", carryTask);
        s.set("training", training);
        s.set("morale", morale);
        s.set("cell", cell);
        s.set("origin", origin);
        s.set("resistance", resistance);
        s.set("visit-day", lastVisitDay);
        s.set("unfed-days", unfedDays);
        s.set("sentence-until", sentenceUntil);
        s.set("crimes", crimes);
        s.set("motivated-until", motivatedUntil);
        s.set("miserable-days", miserableDays);
        s.set("joined", joinedDay);
        s.set("history", new ArrayList<>(history));
        if (hasLast) s.set("last", lastX + "," + lastY + "," + lastZ);
    }

    public static Citizen load(UUID id, ConfigurationSection s) {
        Citizen c = new Citizen(id);
        c.colonyId = s.getString("colony");
        c.name = s.getString("name", "Settler");
        try {
            c.status = Status.valueOf(s.getString("status", "CITIZEN"));
        } catch (IllegalArgumentException e) {
            c.status = Status.CITIZEN;
        }
        Job j = Job.parse(s.getString("job"));
        c.job = j == null ? Job.UNEMPLOYED : j;
        c.workplace = s.getInt("workplace");
        c.militaryClass = MilitaryClass.parse(s.getString("class"));
        c.squad = s.getInt("squad");
        c.home = s.getInt("home");
        c.bed = BlockPos.parse(s.getString("bed"));
        c.trait = Trait.parse(s.getString("trait"));
        c.skin = s.getString("skin", "plains");
        c.ageDays = s.getDouble("age", 20);
        ConfigurationSection sk = s.getConfigurationSection("skills");
        if (sk != null) {
            for (String k : sk.getKeys(false)) {
                Skill skill = Skill.parse(k);
                if (skill != null) c.skills.set(skill, sk.getDouble(k));
            }
        }
        c.education = s.getDouble("education");
        c.needs.satiety = s.getDouble("needs.satiety", 80);
        c.needs.energy = s.getDouble("needs.energy", 90);
        c.needs.maxHealth = s.getDouble("needs.max-health", 20);
        c.needs.health = s.getDouble("needs.health", c.needs.maxHealth);
        c.needs.mood = s.getDouble("needs.mood", 60);
        c.needs.social = s.getDouble("needs.social", 60);
        c.needs.mealQuality = s.getDouble("needs.meal-quality", 1);
        c.illness = Disease.parse(s.getString("illness"));
        c.illnessDays = s.getDouble("illness-days");
        c.treated = s.getBoolean("treated");
        c.injured = s.getBoolean("injured");
        ConfigurationSection b = s.getConfigurationSection("bonds");
        if (b != null) {
            for (String k : b.getKeys(false)) {
                try {
                    c.bonds.put(UUID.fromString(k), new Bond(Bond.Kind.parse(b.getString(k + ".kind")), b.getDouble(k + ".affinity")));
                } catch (IllegalArgumentException ignored) {
                    // a corrupt id: that bond is forgotten
                }
            }
        }
        c.wallet = s.getLong("wallet");
        c.unpaidDays = s.getInt("unpaid-days");
        c.tool = ItemCodec.decode(s.getString("tool"));
        c.weapon = ItemCodec.decode(s.getString("weapon"));
        c.shield = ItemCodec.decode(s.getString("shield"));
        c.ranged = ItemCodec.decode(s.getString("ranged"));
        c.ammo = s.getInt("ammo");
        for (int i = 0; i < 4; i++) c.armor[i] = ItemCodec.decode(s.getString("armor." + i));
        c.horse = s.getBoolean("horse");
        c.horseHealth = s.getDouble("horse-health", 30);
        c.carryTask = s.getString("carry-task");
        c.training = s.getDouble("training");
        c.morale = s.getDouble("morale", 60);
        c.cell = s.getInt("cell");
        c.origin = s.getString("origin");
        c.resistance = s.getDouble("resistance");
        c.lastVisitDay = s.getLong("visit-day", -1);
        c.unfedDays = s.getInt("unfed-days");
        c.sentenceUntil = s.getLong("sentence-until", -1);
        c.crimes = s.getInt("crimes");
        c.motivatedUntil = s.getLong("motivated-until", -1);
        c.miserableDays = s.getInt("miserable-days");
        c.joinedDay = s.getLong("joined");
        for (String h : s.getStringList("history")) c.history.addLast(h);
        String last = s.getString("last");
        if (last != null) {
            String[] p = last.split(",");
            if (p.length == 3) {
                try {
                    c.lastX = Double.parseDouble(p[0]);
                    c.lastY = Double.parseDouble(p[1]);
                    c.lastZ = Double.parseDouble(p[2]);
                    c.hasLast = true;
                } catch (NumberFormatException ignored) {
                    // respawn at the Town Hall instead
                }
            }
        }
        // a riot or an escort can't survive a restart: back to custody or calm
        if (c.status == Status.RIOTER) c.status = Status.CITIZEN;
        return c;
    }
}
