package com.colonysmp.sim;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.MilitaryClass;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Job;
import com.colonysmp.model.LifeStage;
import com.colonysmp.model.Policies;
import com.colonysmp.model.Trait;
import com.colonysmp.util.Text;
import com.colonysmp.util.Tools;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Who does what. Players assign jobs by hand; newcomers and grown-up children are placed automatically, and a
 * working administrator keeps placing the unemployed where the colony most needs hands.
 */
public final class Jobs {

    /** A job opening: a building with free places for a job. */
    public record Opening(Building building, Job job, int free) {}

    private final ColonySMP plugin;

    Jobs(ColonySMP plugin) {
        this.plugin = plugin;
    }

    // ───────────── workplaces ─────────────

    /** Free citizens of a job working at a building, not counting one person. */
    private static int filled(Colony c, Building b, Job j, Citizen except) {
        int n = 0;
        for (Citizen ct : c.citizens.values()) if (ct != except && ct.workplace == b.id && ct.job == j && ct.status.free()) n++;
        return n;
    }

    /** Keeps everyone's workplace consistent with the buildings: removed or full places are left, the jobless placed. */
    public void refreshWorkplaces(Colony c) {
        for (Building b : c.buildings.values()) b.workers.clear();
        Map<Integer, Map<Job, Integer>> used = new HashMap<>();
        List<Citizen> looking = new ArrayList<>();
        for (Citizen ct : c.citizens.values()) {
            if (!ct.job.needsWorkplace()) {
                // guards may still be posted to a watchtower
                if (ct.workplace != 0 && (c.building(ct.workplace) == null || c.building(ct.workplace).slots(ct.job) <= 0)) ct.workplace = 0;
                if (ct.workplace != 0) c.building(ct.workplace).workers.add(ct.id);
                continue;
            }
            if (!ct.status.free()) continue;
            Building b = c.building(ct.workplace);
            if (b == null || b.slots(ct.job) <= 0) {
                ct.workplace = 0;
                looking.add(ct);
                continue;
            }
            Map<Job, Integer> u = used.computeIfAbsent(b.id, k -> new EnumMap<>(Job.class));
            int n = u.getOrDefault(ct.job, 0);
            if (n >= b.slots(ct.job)) {
                ct.workplace = 0;
                looking.add(ct);
                continue;
            }
            u.put(ct.job, n + 1);
            b.workers.add(ct.id);
        }
        for (Citizen ct : looking) {
            Building best = null;
            for (Building b : c.buildings.values()) {
                if (b.slots(ct.job) <= 0) continue;
                int n = used.getOrDefault(b.id, Map.of()).getOrDefault(ct.job, 0);
                if (n >= b.slots(ct.job)) continue;
                // a valid building first, then the one nearest home
                if (best == null || b.valid && !best.valid || b.valid == best.valid && near(c, ct, b) < near(c, ct, best)) best = b;
            }
            if (best == null) continue;
            ct.workplace = best.id;
            used.computeIfAbsent(best.id, k -> new EnumMap<>(Job.class)).merge(ct.job, 1, Integer::sum);
            best.workers.add(ct.id);
        }
    }

    private static double near(Colony c, Citizen ct, Building b) {
        Building home = c.building(ct.home);
        if (home == null || home.anchor == null || b.anchor == null) return 0;
        return home.anchor.distSq(b.anchor);
    }

    /** Every building with free places, by job. */
    public List<Opening> openings(Colony c) {
        List<Opening> out = new ArrayList<>();
        for (Building b : c.buildings.values()) {
            for (Map.Entry<Job, Integer> e : b.type.jobs(b.level).entrySet()) {
                int slots = b.slots(e.getKey());
                int free = slots - filled(c, b, e.getKey(), null);
                if (free > 0) out.add(new Opening(b, e.getKey(), free));
            }
        }
        return out;
    }

    public int vacancies(Colony c, Job j) {
        int n = 0;
        for (Opening o : openings(c)) if (o.job() == j) n += o.free();
        return n;
    }

    // ───────────── assignment ─────────────

    /** Why a citizen can't take a job (at a building, if given), or null. */
    public String canTake(Colony c, Citizen ct, Job j, Building b) {
        if (!ct.status.free()) return ct.name + " is not free to work.";
        LifeStage stage = plugin.sim().citizens().stage(ct);
        if (stage == LifeStage.CHILD && j != Job.STUDENT && j != Job.UNEMPLOYED) return ct.name + " is a child: children can only study.";
        if (stage == LifeStage.ELDER && (j.heavy || j.military())) return ct.name + " is too old for heavy or military work.";
        if (ct.education < j.education) {
            return j.display + " needs education " + j.education + " (" + ct.name + " has " + Math.round(ct.education) + "). A school raises it.";
        }
        if (b != null) {
            if (b.slots(j) <= 0) return b.label() + " has no " + j.display + " places.";
            if (filled(c, b, j, ct) >= b.slots(j)) return b.label() + " already has all the " + j.display.toLowerCase() + "s it can take.";
        }
        if (j == Job.SOLDIER && ct.job != Job.SOLDIER) {
            if (c.policies.military == Policies.Military.VOLUNTEERS && ct.trait == Trait.COWARD) {
                return ct.name + " refuses to enlist (a coward, and your military policy only takes volunteers).";
            }
            String room = plugin.military().room(c);
            if (room != null) return room;
        }
        return null;
    }

    /**
     * Gives a citizen a job (and a workplace, if given or free). Tools and arms that don't suit the new job go back
     * to the stores. Returns why it couldn't be done, or null.
     */
    public String assign(Colony c, Citizen ct, Job j, Building b) {
        String why = canTake(c, ct, j, b);
        if (why != null && !(j == Job.UNEMPLOYED && ct.status.free())) return why;
        Job old = ct.job;
        if (old != j) {
            plugin.sim().logistics().abandon(c, ct);
            if (ct.tool != null && (j.tool == null || Tools.kind(ct.tool) != j.tool)) {
                c.equipment.force(ct.tool);
                ct.tool = null;
            }
            if (old == Job.SOLDIER || old == Job.GUARD) plugin.military().discharge(c, ct, j == Job.SOLDIER || j == Job.GUARD);
            ct.job = j;
            if (j == Job.SOLDIER && ct.militaryClass == null) ct.militaryClass = MilitaryClass.MILITIA;
            if (j != Job.SOLDIER) {
                ct.militaryClass = null;
                ct.squad = 0;
                ct.training = 0;
            }
            ct.remember(plugin.sim().day(c), "Became " + Text.a(j.display) + (old != Job.UNEMPLOYED ? " (was " + Text.a(old.display) + ")" : ""));
        }
        ct.waiting = null;
        ct.workplace = b != null ? b.id : 0;
        if (b == null && j.needsWorkplace()) {
            // the nearest building with room
            for (Opening o : openings(c)) {
                if (o.job() != j) continue;
                ct.workplace = o.building().id;
                if (o.building().valid) break;
            }
        }
        if (!j.needsWorkplace() && !j.military()) ct.workplace = 0;
        refreshWorkplaces(c);
        plugin.npcs().jobChanged(ct);
        plugin.requestSave();
        return null;
    }

    /**
     * Puts a citizen where they're most useful: children in school, adults in the opening that best fits the
     * colony's needs, their trait and their skills. Never drafts anyone into the army.
     */
    public void autoAssign(Colony c, Citizen ct) {
        if (!ct.status.free()) return;
        LifeStage stage = plugin.sim().citizens().stage(ct);
        if (stage == LifeStage.CHILD) {
            Job want = hasTeacher(c) ? Job.STUDENT : Job.UNEMPLOYED;
            if (ct.job != want) assign(c, ct, want, null);
            return;
        }
        Opening best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        Map<Job, Double> need = needs(c);
        for (Opening o : openings(c)) {
            if (o.job().military() || canTake(c, ct, o.job(), o.building()) != null) continue;
            double s = need.getOrDefault(o.job(), 1.0);
            if (!o.building().valid) s -= 3;
            if (ct.trait.likes(o.job())) s += 2;
            if (o.job().skill != null) s += ct.level(o.job().skill) * 0.4;
            s += o.job().education / 40.0;
            if (stage == LifeStage.ELDER && o.job().heavy) s -= 5;
            if (s > bestScore) {
                bestScore = s;
                best = o;
            }
        }
        if (best != null) assign(c, ct, best.job(), best.building());
        else if (ct.job.needsWorkplace() && ct.workplace == 0) assign(c, ct, Job.UNEMPLOYED, null);
    }

    /** How much the colony wants each job filled right now. */
    private Map<Job, Double> needs(Colony c) {
        Map<Job, Double> out = new EnumMap<>(Job.class);
        double food = plugin.sim().finances().foodDays(c);
        double foodNeed = food < 1 ? 8 : food < 3 ? 5 : food < 7 ? 3 : 1;
        for (Job j : new Job[]{Job.FARMER, Job.FISHER, Job.ANIMAL_KEEPER}) out.put(j, foodNeed + 1);
        for (Job j : new Job[]{Job.COOK, Job.BAKER, Job.BUTCHER, Job.MILLER, Job.RESTAURANT_WORKER}) out.put(j, foodNeed);
        if (!c.buildQueue.isEmpty()) out.put(Job.BUILDER, 6.0);
        int ill = 0;
        for (Citizen ct : c.citizens.values()) if (ct.ill() || ct.injured) ill++;
        if (ill > 0) out.put(Job.DOCTOR, 3.0 + ill);
        out.put(Job.FOOD_DISTRIBUTOR, 2.5);
        out.put(Job.WAREHOUSE_WORKER, 2.5);
        out.put(Job.ADMINISTRATOR, 3.0);
        Policies.Priority p = c.policies.priority;
        if (p == Policies.Priority.FOOD) for (Job j : out.keySet()) if (isFood(j)) out.merge(j, 3.0, Double::sum);
        if (p == Policies.Priority.MATERIALS) {
            for (Job j : new Job[]{Job.FORESTER, Job.MINER, Job.MASON, Job.CARPENTER, Job.BUILDER}) out.merge(j, 3.0, Double::sum);
        }
        if (p == Policies.Priority.MILITARY) {
            for (Job j : new Job[]{Job.BLACKSMITH, Job.CRAFTSWORKER, Job.ENGINEER}) out.merge(j, 3.0, Double::sum);
        }
        if (p == Policies.Priority.TRADE) {
            for (Job j : new Job[]{Job.MERCHANT, Job.SHOPKEEPER, Job.CRAFTSWORKER}) out.merge(j, 3.0, Double::sum);
        }
        return out;
    }

    private static boolean isFood(Job j) {
        return switch (j) {
            case FARMER, FISHER, ANIMAL_KEEPER, COOK, BAKER, BUTCHER, MILLER, RESTAURANT_WORKER, FOOD_DISTRIBUTOR -> true;
            default -> false;
        };
    }

    private static boolean hasTeacher(Colony c) {
        for (Building b : c.valid(BuildingType.SCHOOL)) {
            for (Citizen t : c.citizens.values()) if (t.job == Job.TEACHER && t.workplace == b.id && t.status.free()) return true;
        }
        return false;
    }

    /** A working administrator at the Town Hall (enables automatic job placement). */
    public boolean administrated(Colony c) {
        Building th = c.townHall();
        if (th == null) return false;
        for (Citizen ct : c.citizens.values()) if (ct.job == Job.ADMINISTRATOR && ct.workplace == th.id && ct.status.free()) return true;
        return false;
    }

    // ───────────── the day ─────────────

    void daily(Colony c, long day) {
        refreshWorkplaces(c);
        boolean admin = administrated(c);
        boolean school = hasTeacher(c);
        int placed = 0;
        for (Citizen ct : new ArrayList<>(c.citizens.values())) {
            if (!ct.status.free()) continue;
            LifeStage stage = plugin.sim().citizens().stage(ct);
            if (stage == LifeStage.CHILD) {
                if (school && ct.job == Job.UNEMPLOYED) assign(c, ct, Job.STUDENT, null);
                continue;
            }
            // adults who have finished their schooling
            if (ct.job == Job.STUDENT && (ct.education >= 75 || !school)) {
                ct.remember(day, "Finished school");
                autoAssign(c, ct);
                if (ct.job != Job.STUDENT && ct.job != Job.UNEMPLOYED) placed++;
                continue;
            }
            // workers whose job's demands they no longer meet (an elder in heavy work is handled by retirement)
            if (ct.education < ct.job.education) {
                assign(c, ct, Job.UNEMPLOYED, null);
            }
            if (admin && (ct.job == Job.UNEMPLOYED || ct.job.needsWorkplace() && ct.workplace == 0)) {
                Job before = ct.job;
                autoAssign(c, ct);
                if (ct.job != before) placed++;
            }
        }
        if (placed > 0) c.log(ColonyEvent.Type.POPULATION, day, "The administrator found work for " + placed + " citizen" + (placed == 1 ? "" : "s") + ".");
    }

    /** Equipment kinds a job wants issued (tools for workers; arms are the military's business). */
    public static boolean wantsTool(Citizen ct) {
        return ct.job.tool != null && (ct.tool == null || Tools.kind(ct.tool) != ct.job.tool);
    }

    /** Hands a citizen the best tool for their job from the stores, returning the old one. */
    public boolean issueTool(Colony c, Citizen ct) {
        if (ct.job.tool == null) return false;
        Tools.Kind kind = ct.job.tool;
        double have = ct.tool != null && Tools.kind(ct.tool) == kind ? Tools.score(ct.tool) : -1;
        if (c.equipment.bestScore(it -> Tools.kind(it) == kind, Tools::score) <= have) return false;
        ItemStack best = c.equipment.takeBest(it -> Tools.kind(it) == kind, Tools::score);
        if (best == null) return false;
        if (ct.tool != null) c.equipment.force(ct.tool);
        ct.tool = best;
        return true;
    }
}
