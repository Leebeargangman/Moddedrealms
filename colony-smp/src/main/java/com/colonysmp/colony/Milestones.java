package com.colonysmp.colony;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.TxnType;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Text;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Milestones: marks along a colony's way (a tenth citizen, a first raid beaten off, a first discovery), each
 * rewarded once with a grant to the treasury and a little renown. They give a colony something to aim at once
 * the first steps are done.
 */
public final class Milestones {

    public record Milestone(String id, String title, String task, long reward, Predicate<Colony> reached) {}

    private final ColonySMP plugin;
    private final List<Milestone> all = new ArrayList<>();

    public Milestones(ColonySMP plugin) {
        this.plugin = plugin;
        add("pop10", "Hamlet", "Reach 10 citizens", 50, c -> c.population() >= 10);
        add("pop25", "Village", "Reach 25 citizens", 150, c -> c.population() >= 25);
        add("pop50", "Town", "Reach 50 citizens", 400, c -> c.population() >= 50);
        add("level3", "Seat of Government", "Raise the Town Hall to level 3", 200, c -> c.level() >= 3);
        add("trades", "Division of Labour", "Have 8 different kinds of working building", 100, c -> kinds(c) >= 8);
        add("fed", "Land of Plenty", "Hold two weeks of food for 10 or more citizens", 80, c -> c.population() >= 10 && plugin.sim().finances().foodDays(c) >= 14);
        add("tavern", "Song and Supper", "Have a bard playing at a Tavern", 50, c -> plugin.sim().citizens().staffed(c, BuildingType.TAVERN) != null);
        add("honey", "Milk and Honey", "Have a beekeeper at an Apiary", 40, c -> plugin.sim().citizens().staffed(c, BuildingType.APIARY) != null);
        add("yule", "Deck the Halls", "Raise a Christmas tree in the Christmas season", 40, c -> plugin.christmas().decorated(c));
        add("research1", "Eureka", "Finish a research project", 60, c -> !c.research.isEmpty());
        add("research8", "Age of Reason", "Finish 8 research projects", 300, c -> c.research.size() >= 8);
        add("raid1", "Baptism of Fire", "Drive off a raid", 80, c -> c.raidsRepelled >= 1);
        add("raid5", "Unbroken", "Drive off 5 raids", 300, c -> c.raidsRepelled >= 5);
        add("requests5", "A Friend to All", "Answer 5 citizens' requests", 60, c -> c.requestsFilled >= 5);
        add("requests25", "Beloved", "Answer 25 citizens' requests", 250, c -> c.requestsFilled >= 25);
        add("army", "Standing Army", "Keep 8 soldiers under arms", 100, c -> plugin.military().soldiers(c) >= 8);
        add("rich", "Full Coffers", "Hold 1000 in the treasury", 0, c -> c.treasury.balance() >= 1000);
    }

    private void add(String id, String title, String task, long reward, Predicate<Colony> reached) {
        all.add(new Milestone(id, title, task, reward, reached));
    }

    private static int kinds(Colony c) {
        Set<BuildingType> seen = EnumSet.noneOf(BuildingType.class);
        for (Building b : c.buildings.values()) if (b.valid) seen.add(b.type);
        return seen.size();
    }

    public List<Milestone> all() {
        return all;
    }

    /** Awards every milestone the colony has newly reached. Cheap enough to call whenever something changes. */
    public void check(Colony c) {
        if (c.core == null || !plugin.settings().milestonesEnabled) return;
        for (Milestone m : all) {
            if (c.milestones.contains(m.id())) continue;
            boolean reached;
            try {
                reached = m.reached().test(c);
            } catch (RuntimeException e) {
                continue;
            }
            if (!reached) continue;
            c.milestones.add(m.id());
            long day = plugin.sim().day(c);
            long reward = Math.round(m.reward() * plugin.settings().milestoneRewardFactor);
            if (reward > 0) c.treasury.credit(reward, TxnType.REWARDS, "Milestone: " + m.title(), System.currentTimeMillis(), day);
            c.reputation = Math.min(100, c.reputation + 2);
            c.log(ColonyEvent.Type.COLONY, day, "Milestone reached: " + m.title() + " (" + m.task().toLowerCase() + ").");
            for (Player p : c.onlineMembers()) {
                Text.title(p, "<gold>★ " + m.title(), "<gray>" + m.task(), 10, 60, 20);
                Fx.sound(p, "minecraft:ui.toast.challenge_complete", 0.9f, 1.1f);
                Text.send(p, "<gold>★ Milestone: <white>" + m.title() + "</white></gold> <gray>- " + m.task() + "."
                        + (reward > 0 ? " A grant of <gold>" + Text.value(reward) + "</gold> was paid into the treasury." : "") + " Word of " + Text.esc(c.name) + " spreads.");
            }
            plugin.requestSave();
        }
    }

    /** The list for the command: what's done and what's still ahead. */
    public List<String> list(Colony c) {
        List<String> out = new ArrayList<>();
        int done = 0;
        for (Milestone m : all) {
            boolean has = c.milestones.contains(m.id());
            if (has) done++;
            out.add((has ? " <green>✔ <white>" : " <dark_gray>✘ <gray>") + m.title() + (has ? "</white>" : "") + " <dark_gray>- " + m.task()
                    + (m.reward() > 0 && !has ? " <gold>(" + Text.value(Math.round(m.reward() * plugin.settings().milestoneRewardFactor)) + ")" : ""));
        }
        out.add(0, "<gold>Milestones <gray>(" + done + "/" + all.size() + ")");
        return out;
    }
}
