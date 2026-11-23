package com.colonysmp.sim;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Goods;
import com.colonysmp.core.Research;
import com.colonysmp.core.TxnType;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Job;
import com.colonysmp.model.Perm;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Text;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Research. The colony chooses a project, pays for it from the treasury and the stores, and its researchers
 * work on it at the University until it is done. What it changes is read from {@link Colony#bonus} wherever it
 * applies; nothing here needs the world, so research carries on while nobody is near.
 */
public final class Academy {

    private final ColonySMP plugin;

    Academy(ColonySMP plugin) {
        this.plugin = plugin;
    }

    /** The level of the colony's best working University (0 = none). */
    public int universityLevel(Colony c) {
        int best = 0;
        for (Building b : c.valid(BuildingType.UNIVERSITY)) best = Math.max(best, b.level);
        return best;
    }

    /** Researchers at work in a working University. */
    public int researchers(Colony c) {
        int n = 0;
        for (Citizen ct : c.citizens.values()) {
            if (ct.job != Job.RESEARCHER || !ct.status.free()) continue;
            Building b = c.building(ct.workplace);
            if (b != null && b.valid && b.type == BuildingType.UNIVERSITY) n++;
        }
        return n;
    }

    /** What a project costs to begin, in words. */
    public String costText(Research r) {
        List<String> parts = new ArrayList<>();
        parts.add(Text.value(r.cost));
        for (Map.Entry<String, Integer> e : r.goods().entrySet()) parts.add(e.getValue() + " " + Goods.get(e.getKey()).name());
        return String.join(", ", parts);
    }

    /** Why the colony can't begin a project right now, or null. */
    public String blocked(Colony c, Research r) {
        String why = r.blocked(c.research, c.researching, universityLevel(c));
        if (why != null) return why;
        List<String> missing = new ArrayList<>();
        if (!c.treasury.canAfford(r.cost)) missing.add(Text.value(r.cost - c.treasury.balance()));
        for (Map.Entry<String, Integer> e : r.goods().entrySet()) {
            long have = c.storage.available(e.getKey());
            if (have < e.getValue()) missing.add((e.getValue() - have) + " " + Goods.get(e.getKey()).name());
        }
        return missing.isEmpty() ? null : "Missing " + String.join(", ", missing) + ".";
    }

    /** Begins a project: the money and goods are handed over now. Returns an error, or null. */
    public String start(Player by, Colony c, Research r) {
        if (by != null && !plugin.access().can(by, c, Perm.POLICIES)) return "You may not choose " + Text.esc(c.name) + "'s research.";
        String why = blocked(c, r);
        if (why != null) return why;
        long day = plugin.sim().day(c);
        if (c.treasury.debit(r.cost, TxnType.RESEARCH, "Research: " + r.display, System.currentTimeMillis(), day) == null) return "The treasury can't pay for it.";
        for (Map.Entry<String, Integer> e : r.goods().entrySet()) {
            if (!c.storage.takeExact(e.getKey(), e.getValue())) {
                // can't happen after the check above unless something took it this very tick: undo and say so
                c.treasury.credit(r.cost, TxnType.RESEARCH, "Research refunded: " + r.display, System.currentTimeMillis(), day);
                return "The stores no longer hold enough " + Goods.get(e.getKey()).name() + ".";
            }
        }
        c.researching = r;
        c.researchHours = 0;
        c.log(ColonyEvent.Type.COLONY, day, (by == null ? "The colony" : by.getName()) + " set the researchers to work on " + r.display + ".");
        int n = researchers(c);
        for (Player p : c.onlineMembers()) {
            Text.send(p, "<light_purple>Research begun: <white>" + r.display + "</white>.</light_purple> <gray>" + r.description
                    + (n == 0 ? " <red>No researcher works at the University yet: assign one, or nothing will come of it." : ""));
        }
        plugin.requestSave();
        return null;
    }

    /** Drops the project under way. What was paid for it is lost. */
    public String abandon(Player by, Colony c) {
        if (by != null && !plugin.access().can(by, c, Perm.POLICIES)) return "You may not choose " + Text.esc(c.name) + "'s research.";
        if (c.researching == null) return "Nothing is being researched.";
        Research r = c.researching;
        c.researching = null;
        c.researchHours = 0;
        c.log(ColonyEvent.Type.COLONY, plugin.sim().day(c), (by == null ? "The colony" : by.getName()) + " abandoned the research into " + r.display + ".");
        plugin.requestSave();
        return null;
    }

    /** A researcher's work (efficiency-weighted hours). Returns true while there is something to work on. */
    public boolean work(Colony c, Citizen ct, double units) {
        Research r = c.researching;
        if (r == null) {
            ct.waiting = "No research chosen (/colony research)";
            return false;
        }
        Building b = c.building(ct.workplace);
        if (b == null || b.level < r.tier) {
            ct.waiting = r.display + " needs a level " + r.tier + " University";
            return false;
        }
        ct.waiting = null;
        c.researchHours += units;
        if (c.researchHours >= r.hours) complete(c, r);
        return true;
    }

    public double progress(Colony c) {
        return c.researching == null ? 0 : Math.max(0, Math.min(1, c.researchHours / c.researching.hours));
    }

    private void complete(Colony c, Research r) {
        c.researching = null;
        c.researchHours = 0;
        if (!c.research.add(r)) return;
        long day = plugin.sim().day(c);
        apply(c, r);
        c.addStability(2);
        c.log(ColonyEvent.Type.COLONY, day, "The researchers finished " + r.display + ": " + r.description);
        for (Player p : c.onlineMembers()) {
            Text.title(p, "<light_purple>Research Complete", "<white>" + r.display, 10, 60, 20);
            Fx.sound(p, "minecraft:block.enchantment_table.use", 1f, 1f);
            Text.send(p, "<light_purple>Research complete: <white>" + r.display + "</white>.</light_purple> <gray>" + r.description
                    + " <click:run_command:'/colony research'><yellow>[Choose the next project]</yellow></click>");
        }
        plugin.milestones().check(c);
        plugin.requestSave();
    }

    /** One-off changes a finished project makes (the rest is read from the colony's bonuses as it goes). */
    private void apply(Colony c, Research r) {
        switch (r.effect) {
            case VITALITY -> {
                for (Citizen ct : c.citizens.values()) {
                    ct.needs.maxHealth += r.value;
                    ct.needs.health = Math.min(ct.needs.maxHealth, ct.needs.health + r.value);
                }
            }
            case STORAGE -> plugin.buildings().applyCapacity(c);
            default -> {
            }
        }
    }

    /** Admin and events: free progress on the project under way. */
    public void boost(Colony c, double hours) {
        Research r = c.researching;
        if (r == null) return;
        c.researchHours += hours;
        if (c.researchHours >= r.hours) complete(c, r);
    }

    /** Admin: grants a project outright. */
    public void grant(Colony c, Research r) {
        if (c.researching == r) c.researching = null;
        if (c.research.add(r)) apply(c, r);
        plugin.requestSave();
    }
}
