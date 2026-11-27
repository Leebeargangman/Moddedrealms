package com.colonysmp.sim;

import com.colonysmp.ColonySMP;
import com.colonysmp.model.Building;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Job;
import com.colonysmp.model.LifeStage;
import com.colonysmp.util.Text;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The morning report: a short summary of yesterday for the members online, with the colony's most pressing
 * problems, also kept in the colony's history.
 */
public final class Reports {

    private final ColonySMP plugin;

    Reports(ColonySMP plugin) {
        this.plugin = plugin;
    }

    void daily(Colony c, long day, double foodDays, double mood, long income, long expenses) {
        List<String> problems = problems(c, foodDays);
        String summary = "Day " + day + ": " + c.population() + " citizens, food for " + Text.num(foodDays) + " days, mood " + Math.round(mood)
                + ", stability " + Math.round(c.stability) + ", " + (income >= expenses ? "+" : "") + (income - expenses) + " worth of valuables.";
        c.log(ColonyEvent.Type.COLONY, day, summary + (problems.isEmpty() ? "" : " Problems: " + String.join("; ", problems) + "."));
        for (Player p : plugin.quests().settling(c) ? List.<Player>of() : c.onlineMembers()) {
            Text.send(p, "<white>" + Text.esc(c.name) + "</white> — day " + day + ": <white>" + c.population() + "</white> citizens, food "
                    + (foodDays < 2 ? "<red>" : "<green>") + Text.num(foodDays) + "d<gray>, mood " + Text.color(mood) + Math.round(mood)
                    + "<gray>, stability " + Text.color(c.stability) + Math.round(c.stability) + "<gray>, treasury <gold>"
                    + Text.value(c.treasury.balance()) + "</gold> (" + Text.signed(income - expenses) + ")");
            for (String pr : problems.subList(0, Math.min(3, problems.size()))) Text.send(p, "<yellow>⚠ " + pr);
        }
    }

    /** The colony's most pressing problems, worst first. */
    public List<String> problems(Colony c, double foodDays) {
        List<String> out = new ArrayList<>();
        if (c.population() == 0) {
            out.add("nobody lives here any more: /colony resettle calls a new band of settlers");
            return out;
        }
        if (foodDays < 1) out.add("almost out of food");
        else if (foodDays < 3) out.add("food is running low");
        int homeless = plugin.sim().housing().homeless(c);
        if (homeless > 0) out.add(homeless + " without a bed");
        int unpaid = 0, idle = 0, waiting = 0;
        for (Citizen ct : c.citizens.values()) {
            if (!ct.status.free()) continue;
            if (ct.unpaidDays > 0) unpaid++;
            if (ct.job == Job.UNEMPLOYED && plugin.sim().citizens().stage(ct) != LifeStage.CHILD) idle++;
            if (ct.job.needsWorkplace() && ct.workplace == 0) waiting++;
        }
        if (unpaid > 0) out.add(unpaid + " unpaid");
        if (waiting > 0) out.add(waiting + " have a job but no workplace");
        if (idle > 0) out.add(idle + " unemployed");
        int broken = 0;
        for (Building b : c.buildings.values()) if (!b.valid) broken++;
        if (broken > 0) out.add(broken + " building" + (broken == 1 ? "" : "s") + " failed inspection");
        int sick = plugin.sim().health().sick(c);
        if (sick > 0) out.add(sick + " ill");
        if (c.storage.full()) out.add("storage is full");
        if (c.protest) out.add("citizens are protesting (" + plugin.sim().order().worstFactor(c) + ")");
        long costs = plugin.sim().finances().dailyCosts(c);
        if (costs > 0 && c.treasury.balance() < costs) out.add("the treasury can't cover tomorrow's wages and upkeep");
        return out;
    }
}
