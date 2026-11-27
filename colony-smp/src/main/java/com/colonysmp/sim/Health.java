package com.colonysmp.sim;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.core.Goods;
import com.colonysmp.core.Skill;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Disease;
import com.colonysmp.model.Job;
import com.colonysmp.model.Modifier;
import com.colonysmp.util.Text;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Illness and treatment. People fall ill more often when hungry, homeless or crowded; illness spreads between
 * people who share a home or workplace; doctors at a clinic treat patients with medicine, which shortens the
 * illness and stops it turning fatal. Quarantine slows the spread at the cost of the sick staying home.
 */
public final class Health {

    /** Patients a doctor can see in a day (more with skill). */
    private static final int PATIENTS_PER_DOCTOR = 4;

    private final ColonySMP plugin;

    Health(ColonySMP plugin) {
        this.plugin = plugin;
    }

    /** Doctors working at a valid clinic. */
    private List<Citizen> doctors(Colony c) {
        List<Citizen> out = new ArrayList<>();
        for (Citizen ct : c.citizens.values()) {
            if (ct.job != Job.DOCTOR || !ct.status.free() || ct.ill()) continue;
            Building b = c.building(ct.workplace);
            if (b != null && b.valid && b.type == BuildingType.CLINIC) out.add(ct);
        }
        return out;
    }

    /** Extra healing per hour for the sick and wounded while a clinic is staffed (more with skilled doctors and beds). */
    public double treatmentBonus(Colony c) {
        List<Citizen> docs = doctors(c);
        if (docs.isEmpty()) return 0;
        int best = 0;
        for (Citizen d : docs) best = Math.max(best, d.level(Skill.MEDICINE));
        int beds = 0;
        for (Building b : c.valid(BuildingType.CLINIC)) beds += b.beds.size();
        return 0.3 + best * 0.05 + Math.min(0.3, beds * 0.05);
    }

    /** Anyone ill or hurt (doctors tend patients before preparing medicine). */
    public boolean patients(Colony c) {
        for (Citizen ct : c.citizens.values()) if (ct.status.free() && (ct.ill() && !ct.treated || ct.injured)) return true;
        return false;
    }

    public int sick(Colony c) {
        int n = 0;
        for (Citizen ct : c.citizens.values()) if (ct.ill()) n++;
        return n;
    }

    /** Medicine in the clinics and in storage. */
    private long medicine(Colony c) {
        long n = c.storage.available(Goods.MEDICINE);
        for (Building b : c.valid(BuildingType.CLINIC)) n += b.stock.available(Goods.MEDICINE);
        return n;
    }

    private boolean useMedicine(Colony c) {
        for (Building b : c.valid(BuildingType.CLINIC)) if (b.stock.take(Goods.MEDICINE, 1) == 1) return true;
        return c.storage.take(Goods.MEDICINE, 1) == 1;
    }

    // ───────────── the day ─────────────

    void daily(Colony c, long day) {
        Settings s = plugin.settings();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<Citizen> people = new ArrayList<>(c.citizens.values());
        int before = sick(c);
        int fell = 0, caught = 0, recovered = 0, treated = 0;
        // new cases
        for (Citizen ct : people) {
            if (ct.ill() || ct.downed()) continue;
            double chance = s.illnessChance * ct.trait.illness() * Math.max(0.1, 1 - c.bonus(com.colonysmp.core.Research.Effect.HEALING));
            if (ct.needs.satiety < 20) chance *= 3;
            if (ct.home == 0) chance *= 2;
            if (ct.needs.mealQuality < 1) chance *= 1.3;
            Building home = c.building(ct.home);
            if (home != null && home.type == BuildingType.HOUSE) {
                int occ = 0;
                for (Citizen o : people) if (o.home == home.id) occ++;
                if (home.crowded(occ)) chance *= 1.5;
            }
            if (!ct.status.free()) chance *= 1.5;
            if (r.nextDouble() >= chance) continue;
            Disease d;
            if (ct.needs.satiety < 25 || ct.needs.mealQuality < 1) d = Disease.DYSENTERY;
            else if (r.nextDouble() < 0.1) d = Disease.FEVER;
            else d = r.nextDouble() < 0.6 ? Disease.COLD : Disease.FLU;
            infect(ct, d, day);
            fell++;
        }
        // spread among those who live or work together
        double quarantine = c.policies.quarantine ? 0.25 : 1;
        List<Citizen> ill = new ArrayList<>();
        for (Citizen ct : people) if (ct.ill()) ill.add(ct);
        for (Citizen sick : ill) {
            for (Citizen o : people) {
                if (o == sick || o.ill()) continue;
                boolean home = sick.home != 0 && o.home == sick.home;
                boolean work = !c.policies.quarantine && sick.workplace != 0 && o.workplace == sick.workplace;
                if (!home && !work) continue;
                double chance = sick.illness.contagion * quarantine * o.trait.illness() * (sick.treated ? 0.5 : 1);
                if (r.nextDouble() < chance) {
                    infect(o, sick.illness, day);
                    caught++;
                }
            }
        }
        // treatment: doctors see the worst cases first, as far as medicine and time allow
        List<Citizen> docs = doctors(c);
        int capacity = 0;
        for (Citizen d : docs) capacity += PATIENTS_PER_DOCTOR + d.level(Skill.MEDICINE) / 3;
        List<Citizen> waiting = new ArrayList<>();
        for (Citizen ct : people) if (ct.status.free() && (ct.ill() && !ct.treated || ct.injured)) waiting.add(ct);
        waiting.sort(Comparator.comparingDouble(ct -> ct.needs.healthFraction()));
        for (Citizen p : waiting) {
            if (capacity <= 0) break;
            if (p.ill() && !p.treated) {
                if (!useMedicine(c)) {
                    p.waiting = "Ill, but there's no medicine";
                    continue;
                }
                p.treated = true;
            }
            p.needs.health = Math.min(p.needs.maxHealth, p.needs.health + s.medicineHeal);
            capacity--;
            treated++;
            for (Citizen d : docs) d.skills.gain(Skill.MEDICINE, 15 * d.trait.learning());
        }
        // the course of the illness
        for (Citizen ct : people) {
            if (!ct.ill()) continue;
            Disease d = ct.illness;
            ct.illnessDays += ct.treated ? 2 : 1;
            if (!ct.treated) ct.needs.health = Math.max(1, ct.needs.health - (1 - d.work) * 6);
            if (ct.illnessDays >= d.days) {
                ct.illness = null;
                ct.illnessDays = 0;
                ct.treated = false;
                ct.remember(day, "Recovered from " + d.display.toLowerCase());
                recovered++;
                continue;
            }
            if (!ct.treated && ct.needs.healthFraction() < 0.5 && r.nextDouble() < d.lethality) {
                plugin.sim().population().die(c, ct, "died of " + d.display.toLowerCase());
            }
        }
        // (for those walking about in body, the day's illness and treatment happened to the record: show it on them)
        for (Citizen ct : people) if (c.citizens.containsKey(ct.id) && (ct.ill() || ct.injured || waiting.contains(ct))) plugin.npcs().pushHealth(ct);
        int now = sick(c);
        if (fell + caught > 0) {
            c.log(ColonyEvent.Type.HEALTH, day, (fell + caught) + " fell ill (" + caught + " caught it from others); " + now + " ill in total"
                    + (docs.isEmpty() ? ". There is no doctor to treat them." : ", " + treated + " treated."));
        }
        if (recovered > 0) c.log(ColonyEvent.Type.HEALTH, day, recovered + " recovered from illness.");
        if (now >= Math.max(3, c.population() / 5) && before < now) {
            c.addModifier(new Modifier("outbreak", "Outbreak of illness", -6, day + 2));
            for (Player p : c.onlineMembers()) {
                Text.send(p, "<light_purple>An outbreak: " + now + " citizens are ill. " + (docs.isEmpty() ? "Build a clinic and hire a doctor; " : "")
                        + "keep medicine in stock" + (c.policies.quarantine ? "." : ", and consider the quarantine policy."));
            }
        }
        if (!docs.isEmpty() && medicine(c) < now) c.log(ColonyEvent.Type.HEALTH, day, "The clinic is short of medicine (" + medicine(c) + " for " + now + " ill).");
    }

    private void infect(Citizen ct, Disease d, long day) {
        ct.illness = d;
        ct.illnessDays = 0;
        ct.treated = false;
        ct.remember(day, "Fell ill with " + d.display.toLowerCase());
    }

    /** Heals an injured or sick citizen in body at the clinic (called by doctors at work). */
    public boolean treat(Colony c, Citizen doctor, Citizen patient) {
        if (patient.ill() && !patient.treated) {
            if (!useMedicine(c)) return false;
            patient.treated = true;
        }
        patient.needs.health = Math.min(patient.needs.maxHealth, patient.needs.health + plugin.settings().medicineHeal);
        plugin.sim().citizens().updateInjury(patient);
        doctor.skills.gain(Skill.MEDICINE, 15 * doctor.trait.learning());
        return true;
    }
}
