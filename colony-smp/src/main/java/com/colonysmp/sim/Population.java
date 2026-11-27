package com.colonysmp.sim;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.core.Skill;
import com.colonysmp.core.TxnType;
import com.colonysmp.model.Bond;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Job;
import com.colonysmp.model.LifeStage;
import com.colonysmp.model.Modifier;
import com.colonysmp.model.Policies;
import com.colonysmp.model.Status;
import com.colonysmp.model.Trait;
import com.colonysmp.util.ItemGoods;
import com.colonysmp.util.Names;
import com.colonysmp.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Who lives in the colony: newcomers, births, growing up, growing old, friendships and families, leaving and
 * dying. Growth is limited by homes, food and the colony's own policies.
 */
public final class Population {

    private static final String[] SKINS = {"plains", "desert", "jungle", "savanna", "snow", "swamp", "taiga"};
    private final ColonySMP plugin;

    Population(ColonySMP plugin) {
        this.plugin = plugin;
    }

    // ───────────── newcomers ─────────────

    /** A new citizen of a colony. Adults arrive with some schooling and a little experience. */
    public Citizen create(Colony col, Job job, boolean child) {
        Settings s = plugin.settings();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Citizen c = new Citizen(UUID.randomUUID());
        c.name = Names.random();
        c.trait = Trait.random();
        c.skin = SKINS[r.nextInt(SKINS.length)];
        c.needs.maxHealth = s.citizenHealth + col.bonus(com.colonysmp.core.Research.Effect.VITALITY);
        c.needs.health = c.needs.maxHealth;
        c.joinedDay = plugin.sim().day(col);
        if (child) {
            c.ageDays = 0;
            c.education = 0;
            c.job = Job.UNEMPLOYED;
        } else {
            c.ageDays = s.adultAge + r.nextDouble() * (s.elderAge - s.adultAge) * 0.5;
            c.education = r.nextInt(s.educationMin, s.educationMax + 1);
            // a little experience in something
            Skill[] some = {Skill.FARMING, Skill.FORESTRY, Skill.MINING, Skill.CONSTRUCTION, Skill.COOKING, Skill.CRAFTING, Skill.HUSBANDRY, Skill.TRADING, Skill.MELEE};
            c.skills.set(some[r.nextInt(some.length)], 50 + r.nextInt(250));
            c.job = job;
        }
        plugin.colonies().addCitizen(col, c);
        return c;
    }

    /** The first settlers of a newly founded colony. */
    public void settlers(Colony col) {
        settlers(col, plugin.settings().startingJobs, true);
    }

    /** The first band of settlers, with these jobs, and their provisions and tools; tell = explain it in chat. */
    public void settlers(Colony col, java.util.List<Job> jobs, boolean tell) {
        for (Job j : jobs) {
            Citizen c = create(col, j, false);
            c.remember(plugin.sim().day(col), "Arrived as one of the first settlers");
        }
        // the provisions they bring, to last until the first harvest
        for (ItemStack it : plugin.settings().startingGoods) {
            String good = ItemGoods.goodOf(it);
            if (good != null) col.storage.force(good, it.getAmount());
            else col.equipment.force(it);
        }
        // and their own tools, so the farmer and the forester can start at once
        for (ItemStack it : plugin.settings().startingTools) {
            for (int i = 0; i < it.getAmount(); i++) col.equipment.force(new ItemStack(it.getType()));
        }
        plugin.sim().housing().assign(col);
        plugin.sim().jobs().refreshWorkplaces(col);
        col.lastSettlersDay = plugin.sim().day(col);
        col.graceHours = Math.max(col.graceHours, plugin.settings().foundingGraceDays * 24);
        col.log(ColonyEvent.Type.POPULATION, plugin.sim().day(col), col.citizens.size() + " settlers arrived to found the colony.");
        for (Player p : tell ? col.onlineMembers() : java.util.List.<Player>of()) {
            Text.send(p, "<green>Your first <white>" + col.citizens.size() + "</white> settlers have arrived. Right-click one to see them, or use the Town Hall. "
                    + "Register a house so they have beds, and workplaces so they can work. "
                    + (plugin.settings().startingTools.isEmpty()
                    ? "<yellow>They bring no tools:</yellow> put a hoe and an axe in storage (or sneak + right-click a settler with one) before they can farm or fell."
                    : "They brought a few tools (in the armoury); better ones make them faster."));
            if (col.inGrace()) {
                Text.send(p, "<aqua>Founding grace:</aqua> <gray>for the first <white>" + graceDays(col) + "</white> days you play, nobody in "
                        + Text.esc(col.name) + " can die. Use them to give everyone a bed and get a farm going. Leave the bread in storage: it's their food.");
            }
        }
        plugin.requestSave();
    }

    /** Why the colony can't take one more person, or null. */
    public String roomForOne(Colony col) {
        Settings s = plugin.settings();
        int pop = col.citizens.size();
        if (pop >= s.maxPopulation) return "The colony is at the server's population limit (" + s.maxPopulation + ").";
        if (col.policies.populationLimit > 0 && pop >= col.policies.populationLimit) return "The colony's population policy caps it at " + col.policies.populationLimit + ".";
        String full = plugin.tiers().full(col);
        if (full != null) return full;
        if (freeBeds(col) <= 0) return "There's no free bed. Build or register another house.";
        if (plugin.sim().finances().foodDays(col) < 1) return "There isn't a day's food in storage for one more mouth.";
        return null;
    }

    public int freeBeds(Colony col) {
        int beds = plugin.buildings().houseBeds(col);
        for (Building b : col.valid(BuildingType.BARRACKS)) beds += b.beds.size();
        int used = 0;
        for (Citizen c : col.citizens.values()) if (c.status.free()) used++;
        return beds - used;
    }

    /** Recruits a settler from the region, paid from the treasury (the Town Hall's recruit button). */
    public String recruit(Player by, Colony col) {
        if (col.policies.recruitment == Policies.Recruitment.CLOSED) return "Your recruitment policy is Closed.";
        String why = roomForOne(col);
        if (why != null) return why;
        long cost = plugin.settings().recruitCost * (1 + col.population() / 10);
        long day = plugin.sim().day(col);
        if (col.treasury.debit(cost, TxnType.RECRUITMENT, "Recruited a settler", System.currentTimeMillis(), day) == null) {
            return "Recruiting costs " + Text.value(cost) + " (the treasury has " + Text.value(col.treasury.balance()) + ").";
        }
        Citizen c = create(col, Job.UNEMPLOYED, false);
        c.remember(day, "Recruited by " + by.getName());
        plugin.sim().housing().assign(col);
        plugin.sim().jobs().autoAssign(col, c);
        col.log(ColonyEvent.Type.POPULATION, day, by.getName() + " recruited " + c.name + " (" + c.trait.display + ", now " + Text.a(c.job.display) + ").");
        plugin.requestSave();
        return null;
    }

    // ───────────── the day ─────────────

    void daily(Colony col, long day) {
        Settings s = plugin.settings();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<Citizen> all = new ArrayList<>(col.citizens.values());
        for (Citizen c : all) {
            if (!c.status.free()) continue;
            LifeStage stage = plugin.sim().citizens().stage(c);
            // growing up: a child who reaches working age takes a job
            if (stage != LifeStage.CHILD && c.job == Job.UNEMPLOYED && c.ageDays - 1 < s.adultAge && c.ageDays >= s.adultAge) {
                c.remember(day, "Grew up");
                plugin.sim().jobs().autoAssign(col, c);
                col.log(ColonyEvent.Type.POPULATION, day, c.name + " has grown up and become a " + c.job.display + ".");
            }
            // retiring from heavy or military work
            if (stage == LifeStage.ELDER && (c.job.heavy || c.job.military())) {
                Job old = c.job;
                plugin.sim().jobs().assign(col, c, Job.UNEMPLOYED, null);
                plugin.sim().jobs().autoAssign(col, c);
                col.log(ColonyEvent.Type.POPULATION, day, c.name + " is getting old and gave up work as " + Text.a(old.display) + (c.job != Job.UNEMPLOYED ? " for lighter work as " + Text.a(c.job.display) : "") + ".");
            }
            // old age
            if (c.ageDays > s.maxAge && r.nextDouble() < 0.15 + (c.ageDays - s.maxAge) * 0.05) {
                die(col, c, "passed away peacefully of old age");
                continue;
            }
            // misery
            if (c.needs.mood < s.emigrateBelow && c.trait != Trait.LOYAL) c.miserableDays++;
            else c.miserableDays = 0;
            if (c.miserableDays >= s.emigrateDays && col.population() > 2 && !col.inGrace() && r.nextDouble() < s.emigrateChance && plugin.sim().winter().mayLeave(col)) {
                leave(col, c, "was miserable for too long and left the colony (" + plugin.sim().citizens().why(col, c).toLowerCase() + ")");
            }
        }
        bonds(col, day);
        births(col, day);
    }

    /** Affinity grows between people who share a home or a workplace; partners form; rivalries flare. */
    private void bonds(Colony col, long day) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<Citizen> adults = new ArrayList<>();
        for (Citizen c : col.citizens.values()) if (c.status.free() && plugin.sim().citizens().stage(c) != LifeStage.CHILD) adults.add(c);
        if (adults.size() < 2) return;
        // each person meets a few others a day
        for (Citizen a : adults) {
            for (int i = 0; i < 2; i++) {
                Citizen b = adults.get(r.nextInt(adults.size()));
                if (b == a) continue;
                double gain = 2;
                if (a.home != 0 && a.home == b.home) gain += 3;
                if (a.workplace != 0 && a.workplace == b.workplace) gain += 2;
                if (a.trait == Trait.SOCIABLE || b.trait == Trait.SOCIABLE) gain += 1;
                if (a.trait == Trait.GREEDY && b.trait == Trait.GREEDY || r.nextDouble() < 0.05) gain = -8;
                Bond ab = a.bonds.computeIfAbsent(b.id, k -> new Bond(Bond.Kind.ACQUAINTANCE, 0));
                Bond ba = b.bonds.computeIfAbsent(a.id, k -> new Bond(Bond.Kind.ACQUAINTANCE, 0));
                ab.affinity = Math.max(-100, Math.min(100, ab.affinity + gain));
                ba.affinity = Math.max(-100, Math.min(100, ba.affinity + gain));
                Bond.Kind before = ab.kind;
                ab.settle();
                ba.settle();
                if (before != Bond.Kind.RIVAL && ab.kind == Bond.Kind.RIVAL) {
                    col.log(ColonyEvent.Type.CRIME, day, a.name + " and " + b.name + " have fallen out.");
                    a.needs.mood = Math.max(0, a.needs.mood - 5);
                    b.needs.mood = Math.max(0, b.needs.mood - 5);
                }
                // partners: two unattached adults who are very fond of each other
                if (ab.affinity >= plugin.settings().partnerAffinity && a.partner() == null && b.partner() == null
                        && plugin.sim().citizens().stage(a) == LifeStage.ADULT && plugin.sim().citizens().stage(b) == LifeStage.ADULT
                        && !related(a, b) && r.nextDouble() < 0.3) {
                    ab.kind = Bond.Kind.PARTNER;
                    ba.kind = Bond.Kind.PARTNER;
                    a.remember(day, "Became partners with " + b.name);
                    b.remember(day, "Became partners with " + a.name);
                    col.log(ColonyEvent.Type.POPULATION, day, a.name + " and " + b.name + " are now partners.");
                    plugin.sim().housing().assign(col);
                }
            }
        }
        // keep the bond lists small: forget lukewarm acquaintances
        for (Citizen c : col.citizens.values()) {
            if (c.bonds.size() <= 12) continue;
            c.bonds.entrySet().removeIf(e -> e.getValue().kind == Bond.Kind.ACQUAINTANCE && Math.abs(e.getValue().affinity) < 20);
        }
    }

    private static boolean related(Citizen a, Citizen b) {
        Bond x = a.bonds.get(b.id);
        return x != null && (x.kind == Bond.Kind.PARENT || x.kind == Bond.Kind.CHILD || x.kind == Bond.Kind.SIBLING);
    }

    /** Couples with a family home, enough food and good spirits may have a child. */
    private void births(Colony col, long day) {
        Settings s = plugin.settings();
        if (!col.policies.births || roomForOne(col) != null) return;
        if (plugin.sim().finances().foodDays(col) < s.birthReserveDays) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Citizen a : new ArrayList<>(col.citizens.values())) {
            UUID pid = a.partner();
            if (pid == null || a.id.compareTo(pid) > 0) continue;
            Citizen b = col.citizens.get(pid);
            if (b == null || !a.status.free() || !b.status.free()) continue;
            if (plugin.sim().citizens().stage(a) != LifeStage.ADULT || plugin.sim().citizens().stage(b) != LifeStage.ADULT) continue;
            if (a.home == 0 || a.home != b.home) continue;
            if ((a.needs.mood + b.needs.mood) / 2 < s.birthMinMood) continue;
            if (r.nextDouble() >= s.birthChance) continue;
            Citizen child = create(col, Job.UNEMPLOYED, true);
            child.name = Names.child(a.name);
            child.skin = r.nextBoolean() ? a.skin : b.skin;
            for (Citizen parent : List.of(a, b)) {
                child.bonds.put(parent.id, new Bond(Bond.Kind.PARENT, 80));
                parent.bonds.put(child.id, new Bond(Bond.Kind.CHILD, 80));
                for (Map.Entry<UUID, Bond> e : parent.bonds.entrySet()) {
                    if (e.getValue().kind != Bond.Kind.CHILD || e.getKey().equals(child.id)) continue;
                    Citizen sib = col.citizens.get(e.getKey());
                    if (sib == null) continue;
                    child.bonds.put(sib.id, new Bond(Bond.Kind.SIBLING, 50));
                    sib.bonds.put(child.id, new Bond(Bond.Kind.SIBLING, 50));
                }
            }
            child.remember(day, "Born to " + a.name + " and " + b.name);
            a.remember(day, "Had a child, " + child.name);
            b.remember(day, "Had a child, " + child.name);
            col.log(ColonyEvent.Type.POPULATION, day, child.name + " was born to " + a.name + " and " + b.name + ".");
            col.addStability(1);
            plugin.sim().housing().assign(col);
            for (Player p : col.onlineMembers()) Text.send(p, "<light_purple>A child, <white>" + Text.esc(child.name) + "</white>, was born in " + Text.esc(col.name) + "!");
            if (roomForOne(col) != null) return;
        }
    }

    // ───────────── school ─────────────

    /** Schooling for students and children without a body (citizens in body learn in class). */
    public void study(Colony col, Citizen c, double hours) {
        Building school = null;
        for (Building b : col.valid(BuildingType.SCHOOL)) {
            for (Citizen t : col.citizens.values()) if (t.job == Job.TEACHER && t.workplace == b.id && t.status.free()) school = b;
        }
        if (school == null) {
            c.waiting = "No school with a teacher";
            return;
        }
        Settings s = plugin.settings();
        double lessons = hours / 0.6;
        double shelves = 1 + Math.min(0.5, school.feature(com.colonysmp.model.Feature.BOOKSHELF) * 0.05);
        double gain = s.perLesson * lessons * shelves * (plugin.sim().citizens().stage(c) == LifeStage.CHILD ? s.childLearning : 1) * c.trait.learning()
                * (1 + col.bonus(com.colonysmp.core.Research.Effect.LEARNING));
        c.education = Math.min(100, c.education + gain);
        c.waiting = null;
    }

    // ───────────── leaving ─────────────

    /** A citizen dies: their gear goes back to the armoury and their family grieves. */
    /** Whole days of founding grace left (rounded up). */
    public static long graceDays(Colony col) {
        return (long) Math.ceil(Math.max(0, col.graceHours) / 24);
    }

    /**
     * A band of settlers for a colony nobody lives in any more, free once every {@code colonies.resettle-cooldown-days}.
     * Returns an error, or null when they came.
     */
    public String resettle(Player by, Colony col) {
        if (col.core == null) return "Place the Town Hall Core first.";
        if (!col.citizens.isEmpty()) return Text.esc(col.name) + " still has people. Recruit more from the Town Hall (Recruitment).";
        long day = plugin.sim().day(col);
        long wait = col.lastSettlersDay == Long.MIN_VALUE ? 0 : col.lastSettlersDay + plugin.settings().resettleCooldownDays - day;
        if (wait > 0) return "New settlers will hear of the empty town hall in " + wait + " day" + (wait == 1 ? "" : "s") + ".";
        col.graceHours = 0;
        settlers(col);
        col.log(ColonyEvent.Type.COLONY, day, (by == null ? "New settlers" : by.getName() + " called new settlers") + " to resettle " + col.name + ".");
        return null;
    }

    public void die(Colony col, Citizen c, String how) {
        long day = plugin.sim().day(col);
        if (col.inGrace() && c.status.free() && !how.contains("old age")) {
            rescue(col, c, how, day);
            return;
        }
        for (Map.Entry<UUID, Bond> e : c.bonds.entrySet()) {
            Citizen o = col.citizens.get(e.getKey());
            if (o == null) continue;
            if (e.getValue().kind.family() || e.getValue().kind == Bond.Kind.FRIEND) {
                o.grief = e.getValue().kind.family() ? 10 : 4;
                o.remember(day, "Lost " + c.name);
            }
            o.bonds.remove(c.id);
        }
        col.log(ColonyEvent.Type.POPULATION, day, c.name + " (" + c.title() + ") " + how + ".");
        if (c.status.free()) col.addStability(-1.5);
        for (Player p : col.onlineMembers()) Text.send(p, "<red>✝ " + Text.esc(c.name) + " <gray>(" + c.title() + ") " + Text.esc(how) + ".");
        remove(col, c);
    }

    /** Founding grace: instead of dying, the citizen pulls through (the others share the last of their rations). */
    private void rescue(Colony col, Citizen c, String how, long day) {
        c.needs.health = Math.max(c.needs.health, c.needs.maxHealth * 0.4);
        if (c.needs.satiety <= 5) c.needs.satiety = 25;
        c.downedUntil = 0;
        c.remember(day, "Pulled through the hard first days");
        String what = how.startsWith("starved") ? "nearly starved" : how.startsWith("was killed by ") ? "was badly hurt (" + how.substring("was killed ".length()) + ")"
                : how.startsWith("was killed") ? "was badly hurt" : "nearly " + how;
        col.log(ColonyEvent.Type.POPULATION, day, c.name + " (" + c.title() + ") " + what + ", but pulled through: founding grace, " + graceDays(col) + " day(s) left.");
        long now = System.currentTimeMillis();
        boolean hint = now - col.lastGraceHint > 120_000;
        if (hint) col.lastGraceHint = now;
        for (Player p : col.onlineMembers()) {
            Text.send(p, "<yellow>⚠ " + Text.esc(c.name) + " <gray>" + Text.esc(what) + ". <aqua>Founding grace</aqua> saved them (" + graceDays(col) + " day(s) left).");
            if (hint && how.startsWith("starved")) {
                Text.send(p, "<gray>Your people need food in <gold>colony storage</gold> (the chest beside the Town Hall): bread, cooked meat, carrots... "
                        + "A <white>farmer</white> with a <white>hoe</white> and a registered farm grows more. You can also buy food at the Old Town market.");
            }
        }
        plugin.requestSave();
    }

    /** A citizen leaves (emigration, release, escape) without dying. */
    public void leave(Colony col, Citizen c, String why) {
        col.log(ColonyEvent.Type.POPULATION, plugin.sim().day(col), c.name + " " + why + ".");
        for (Player p : col.onlineMembers()) Text.send(p, "<yellow>" + Text.esc(c.name) + " " + Text.esc(why) + ".");
        for (Citizen o : col.citizens.values()) o.bonds.remove(c.id);
        remove(col, c);
    }

    private void remove(Colony col, Citizen c) {
        plugin.sim().logistics().abandon(col, c);
        plugin.sim().logistics().dumpCarried(col, c);
        for (var it : c.gear()) col.equipment.force(it);
        c.clearGear();
        if (c.horse) col.storage.force(com.colonysmp.core.Goods.WAR_HORSE, 1);
        plugin.npcs().despawn(c);
        plugin.colonies().removeCitizen(c);
        plugin.sim().housing().assign(col);
        plugin.requestSave();
    }

    public void grieve(Colony col, long day, String label, double mood, int days) {
        col.addModifier(new Modifier("grief", label, mood, day + days));
    }

    public Status free() {
        return Status.CITIZEN;
    }
}
