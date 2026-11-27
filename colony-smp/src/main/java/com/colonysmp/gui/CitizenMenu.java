package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.MilitaryClass;
import com.colonysmp.model.Building;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Job;
import com.colonysmp.model.LifeStage;
import com.colonysmp.model.Perm;
import com.colonysmp.model.Status;
import com.colonysmp.sim.Jobs;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** One citizen: everything about them, and what can be done with them. */
public final class CitizenMenu extends Menu {

    private final ColonySMP plugin;
    private final Colony c;
    private final Citizen ct;

    public CitizenMenu(ColonySMP plugin, Player viewer, Colony c, Citizen ct) {
        super(viewer, 4, "<dark_aqua>" + Text.esc(ct.name));
        this.plugin = plugin;
        this.c = c;
        this.ct = ct;
    }

    @Override
    protected void draw() {
        if (!c.citizens.containsKey(ct.id)) {
            button(13, Material.BARRIER, "<gray>" + Text.esc(ct.name) + " is no longer here", List.of(), null);
            return;
        }
        button(4, ct.job.icon, ct.status.color + Text.esc(ct.name), plugin.commands().info().citizen(c, ct), null);
        boolean free = ct.status.free();
        LifeStage stage = plugin.sim().citizens().stage(ct);
        if (free) {
            boolean step = c.questStage == com.colonysmp.colony.QuestManager.JOB;
            List<String> jobLore = new ArrayList<>(List.of("<gray>Now: " + ct.job.display, "<dark_gray>Click to choose"));
            if (step) jobLore.add(0, "<gold>◀ Next step: make them a Woodcutter");
            var jobIcon = com.colonysmp.util.Items.icon(Material.IRON_PICKAXE, (step ? "<gold><bold>" : "<green>") + "Change job", jobLore);
            set(19, step ? com.colonysmp.util.Items.glow(jobIcon) : jobIcon, k -> {
                if (plugin.access().check(viewer, c, Perm.MANAGE_JOBS)) new JobPicker().open();
            });
            if (stage != LifeStage.CHILD) {
                button(20, Material.IRON_SWORD, ct.job == Job.SOLDIER ? "<green>Change military class" : "<green>Recruit as a soldier",
                        List.of("<gray>" + (ct.militaryClass == null ? "Not a soldier" : ct.militaryClass.display), "<gray>Army " + plugin.military().army(c).size() + "/"
                                + plugin.military().capacity(c)), k -> {
                            if (plugin.access().check(viewer, c, Perm.MILITARY)) new ClassPicker().open();
                        });
            }
            if (Jobs.wantsTool(ct) && c.equipment.has(it -> com.colonysmp.util.Tools.kind(it) == ct.job.tool)) {
                button(21, Material.ANVIL, "<green>Hand over a " + com.colonysmp.util.Text.nice(ct.job.tool.name()).toLowerCase(), List.of("<gray>From the armoury"), k -> {
                    if (plugin.access().check(viewer, c, Perm.MANAGE_JOBS) && plugin.sim().jobs().issueTool(c, ct)) plugin.npcs().jobChanged(ct);
                    refresh();
                });
            }
            var asks = plugin.requests().of(c, ct);
            if (asks != null) {
                var good = com.colonysmp.core.Goods.get(asks.good());
                button(23, Material.PAPER, "<yellow>Asks for " + asks.amount() + " " + good.name(), List.of("<gray><italic>\"" + Text.esc(asks.reason()) + "\"",
                        asks.reward() > 0 ? "<gray>Pays <gold>" + Text.value(asks.reward()) : "<gray>Has nothing to pay with, only thanks",
                        "<dark_gray>Click to hand it over from your inventory"), k -> {
                    String err = plugin.requests().deliver(viewer, c, asks);
                    if (err != null) Text.send(viewer, "<red>" + err);
                    refresh();
                });
            }
            Building home = c.building(ct.home);
            button(22, Material.RED_BED, "<green>Home", List.of("<gray>" + (home == null ? "Homeless" : Text.esc(home.label())), "<dark_gray>/colony home " + ct.name + " <house id>"), null);
            button(25, Material.OAK_DOOR, "<red>Exile", List.of("<gray>Send them away for good"), k -> {
                viewer.closeInventory();
                viewer.performCommand("colony exile " + ct.name);
            });
        } else {
            button(20, Material.IRON_BARS, "<yellow>Release", List.of("<gray>" + (ct.origin == null ? "End their sentence early" : "Let them go home")), k -> {
                if (!plugin.access().check(viewer, c, Perm.MANAGE_CITIZENS)) return;
                String err = plugin.prison().release(viewer, c, ct);
                if (err != null) Text.send(viewer, "<red>" + err);
                viewer.closeInventory();
            });
            if (ct.origin != null && plugin.colonies().get(ct.origin) != null) {
                button(21, Material.GOLD_INGOT, "<yellow>Ask a ransom", List.of("<gray>" + Text.value(plugin.prison().ransom(ct))), k -> {
                    viewer.closeInventory();
                    viewer.performCommand("colony prison ransom " + ct.name);
                });
            }
        }
        button(31, Material.ARROW, "<white>Back", List.of(), k -> new CitizensMenu(plugin, viewer, c, null).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    /**
     * The eight main jobs first (Woodcutter highlighted while that's the colony's next step); the rest one click
     * further. Jobs the colony's tier hasn't opened yet say which tier does.
     */
    private static final Job[] MAIN = {Job.FARMER, Job.FORESTER, Job.MINER, Job.BUILDER, Job.GUARD, Job.BLACKSMITH, Job.ENGINEER, Job.SOLDIER};

    private boolean locked(Job j) {
        return j != ct.job && plugin.tiers().jobLocked(c, j) != null;
    }

    private List<String> jobLore(Job j) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + j.description);
        if (j.needsWorkplace()) lore.add("<gray>Free places: <white>" + plugin.sim().jobs().vacancies(c, j));
        if (j.education > 0) lore.add("<gray>Needs education " + j.education);
        String why = plugin.sim().jobs().canTake(c, ct, j, null);
        lore.add(why == null ? "<green>Click to assign" : "<red>" + Text.esc(why));
        return lore;
    }

    private void pick(Job j) {
        String err = plugin.sim().jobs().assign(c, ct, j, null);
        if (err != null) Text.send(viewer, "<red>" + err);
        else if (j != Job.UNEMPLOYED) {
            com.colonysmp.model.Building w = c.building(ct.workplace);
            Text.send(viewer, com.colonysmp.util.Messages.get(w != null ? "jobs.assigned" : j.needsWorkplace() ? "jobs.assigned-no-place" : "jobs.assigned-free",
                    "name", ct.name, "job", j.display, "place", w == null ? "" : w.label()));
            plugin.funnel().record(viewer.getUniqueId(), "first_job_assigned");
            plugin.quests().event(c, viewer, "job");
        }
        new CitizenMenu(plugin, viewer, c, ct).open();
    }

    private final class JobPicker extends Menu {
        JobPicker() {
            super(CitizenMenu.this.viewer, 4, "<dark_aqua>A job for " + Text.esc(ct.name));
        }

        @Override
        protected void draw() {
            boolean step = c.questStage == com.colonysmp.colony.QuestManager.JOB;
            for (int i = 0; i < MAIN.length; i++) {
                Job j = MAIN[i];
                List<String> lore = jobLore(j);
                boolean star = step && j == Job.FORESTER;
                if (star) lore.add(0, "<gold>◀ Pick this one: your next step");
                var icon = com.colonysmp.util.Items.icon(j.icon, (j == ct.job ? "<gold>" : star ? "<gold><bold>" : locked(j) ? "<dark_gray>" : "<white>") + j.display, lore);
                // a soldier needs a class (and its arms), so that goes through the recruiting screen
                set(10 + i, star ? com.colonysmp.util.Items.glow(icon) : icon, j == Job.SOLDIER && ct.job != Job.SOLDIER ? k -> {
                    if (locked(j)) Text.send(viewer, "<red>" + Text.esc(plugin.tiers().jobLocked(c, j)));
                    else if (plugin.access().check(viewer, c, Perm.MILITARY)) new ClassPicker().open();
                } : k -> pick(j));
            }
            button(23, Material.BOOKSHELF, "<aqua>More jobs...", List.of("<gray>Fisher, cook, baker, doctor, teacher", "<gray>and the rest, as your colony grows"),
                    k -> new MoreJobs().open());
            button(21, Job.UNEMPLOYED.icon, (ct.job == Job.UNEMPLOYED ? "<gold>" : "<white>") + Job.UNEMPLOYED.display, jobLore(Job.UNEMPLOYED), k -> pick(Job.UNEMPLOYED));
            button(31, Material.ARROW, "<white>Back", List.of(), k -> new CitizenMenu(plugin, viewer, c, ct).open());
            fill(Material.GRAY_STAINED_GLASS_PANE);
        }
    }

    private final class MoreJobs extends Menu {
        MoreJobs() {
            super(CitizenMenu.this.viewer, 5, "<dark_aqua>More jobs for " + Text.esc(ct.name));
        }

        @Override
        protected void draw() {
            int i = 0;
            java.util.Set<Job> main = java.util.EnumSet.of(Job.UNEMPLOYED);
            main.addAll(java.util.Arrays.asList(MAIN));
            // open ones first, then the ones a later tier opens
            List<Job> rest = new ArrayList<>();
            for (Job j : Job.values()) if (!main.contains(j)) rest.add(j);
            rest.sort(java.util.Comparator.comparingInt(j -> locked(j) ? plugin.tiers().jobTier(j) : 0));
            for (Job j : rest) {
                if (i >= 36) break;
                button(i++, j.icon, (j == ct.job ? "<gold>" : locked(j) ? "<dark_gray>" : "<white>") + j.display, jobLore(j), k -> pick(j));
            }
            button(40, Material.ARROW, "<white>Back", List.of(), k -> new JobPicker().open());
            fill(Material.GRAY_STAINED_GLASS_PANE);
        }
    }

    /** Every military class, with what's missing for it. */
    private final class ClassPicker extends Menu {
        ClassPicker() {
            super(CitizenMenu.this.viewer, 4, "<dark_red>A class for " + Text.esc(ct.name));
        }

        @Override
        protected void draw() {
            int i = 0;
            for (MilitaryClass k : MilitaryClass.values()) {
                List<String> lore = new ArrayList<>();
                lore.add("<gray>" + k.description);
                lore.add("<gray>" + k.role.name().toLowerCase() + " · upkeep " + k.upkeep);
                String missing = plugin.military().missingFor(c, k);
                lore.add(missing == null ? "<green>Ready to recruit" : "<yellow>Missing: " + missing);
                Material icon = switch (k.role) {
                    case RANGED -> Material.BOW;
                    case CAVALRY -> Material.SADDLE;
                    case SUPPORT -> Material.WHITE_BANNER;
                    default -> k.shield ? Material.SHIELD : Material.IRON_SWORD;
                };
                button(i++, icon, (k == ct.militaryClass ? "<gold>" : "<white>") + k.display, lore, x -> {
                    String r = plugin.military().recruit(viewer, c, ct, k);
                    if (r != null) Text.send(viewer, r.startsWith("!") ? "<yellow>" + r.substring(1) : "<red>" + r);
                    new CitizenMenu(plugin, viewer, c, ct).open();
                });
            }
            if (ct.job == Job.SOLDIER) {
                button(35, Material.BARRIER, "<red>Discharge", List.of("<gray>Back to civilian life"), x -> {
                    plugin.sim().jobs().assign(c, ct, Job.UNEMPLOYED, null);
                    plugin.sim().jobs().autoAssign(c, ct);
                    new CitizenMenu(plugin, viewer, c, ct).open();
                });
            }
            button(31, Material.ARROW, "<white>Back", List.of(), x -> new CitizenMenu(plugin, viewer, c, ct).open());
            fill(Material.GRAY_STAINED_GLASS_PANE);
        }
    }

    static boolean prisoner(Citizen c) {
        return c.status == Status.PRISONER || c.status == Status.CAPTIVE;
    }
}
