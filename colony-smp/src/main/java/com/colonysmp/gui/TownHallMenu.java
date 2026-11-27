package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.building.BuildingManager;
import com.colonysmp.colony.QuestManager;
import com.colonysmp.model.Building;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Perm;
import com.colonysmp.colony.Tiers;
import com.colonysmp.util.Items;
import com.colonysmp.util.Messages;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** The Town Hall: the colony at a glance and the way into everything else. */
public final class TownHallMenu extends Menu {

    private final ColonySMP plugin;
    private final Colony c;

    public TownHallMenu(ColonySMP plugin, Player viewer, Colony c) {
        super(viewer, 5, "<dark_aqua>Town Hall · " + Text.esc(c.name));
        this.plugin = plugin;
        this.c = c;
        plugin.funnel().record(viewer.getUniqueId(), "opened_menu");
        plugin.quests().event(c, viewer, "menu");
    }

    private void chat(String command) {
        viewer.closeInventory();
        viewer.performCommand(command);
    }

    @Override
    protected void draw() {
        var info = plugin.commands().info();
        var tiers = plugin.tiers();
        List<String> overview = new ArrayList<>();
        if (tiers.on()) overview.add(Messages.compose("tiers.menu.name", "tier", tiers.of(c).coloured(), "n", tiers.tier(c)));
        overview.addAll(info.colony(c));
        button(4, Material.LODESTONE, "<aqua><bold>" + Text.esc(c.name), overview, null);

        // the everyday row
        boolean jobStep = c.questStage == QuestManager.JOB;
        List<String> citizensLore = new ArrayList<>(List.of("<gray>" + c.population() + " people: needs, jobs, homes", "<dark_gray>Click to open"));
        if (jobStep) citizensLore.add(0, "<gold>◀ Next step: give a settler a job");
        var citizensIcon = Items.icon(Material.VILLAGER_SPAWN_EGG, (jobStep ? "<gold><bold>" : "<green>") + "Citizens and jobs", citizensLore);
        set(19, jobStep ? Items.glow(citizensIcon) : citizensIcon, k -> new CitizensMenu(plugin, viewer, c, null).open());
        if (jobStep) {
            set(18, Items.icon(Material.SPECTRAL_ARROW, "<gold><bold>Start here ▶", List.of("<gray>Open Citizens, pick a settler", "<gray>and give them a job.")));
        }
        button(20, Material.BRICKS, "<green>Buildings", List.of("<gray>" + c.buildings.size() + " registered: upgrade, inspect, remove", "<dark_gray>Click to open"),
                k -> new BuildingsMenu(plugin, viewer, c).open());
        button(21, Material.CHEST, "<green>Resources", List.of("<gray>Storage: " + c.storage.usedSlots() + "/" + c.storage.capacity() + " slots used", "<dark_gray>Click to open"),
                k -> new StorageMenu(plugin, viewer, c).open());
        tier(22);
        goals(23);
        button(24, Material.IRON_SWORD, "<green>Military", List.of("<gray>" + plugin.military().army(c).size() + " soldiers, " + c.squads.size() + " squads", "<dark_gray>Click to open"),
                k -> new MilitaryMenu(plugin, viewer, c).open());
        button(25, Material.BLUE_BANNER, "<green>Diplomacy", List.of("<gray>Relations, treaties, trade and war", "<dark_gray>Click to open"),
                k -> new DiplomacyMenu(plugin, viewer, c).open());

        // running the colony
        button(28, Material.GOLD_INGOT, "<green>Economy", List.of("<gray>Treasury " + Text.value(c.treasury.balance()), "<dark_gray>Click to open"),
                k -> new EconomyMenu(plugin, viewer, c).open());
        button(29, Material.SCAFFOLDING, "<green>Construction", info.construction(c), k -> chat("colony construction"));
        long cost = plugin.settings().recruitCost * (1 + c.population() / 10);
        String why = plugin.sim().population().roomForOne(c);
        button(30, Material.EMERALD, "<green>Recruit a settler", List.of("<gray>Costs " + Text.value(cost), why == null ? "<green>There's room" : "<red>" + Text.esc(why)), k -> {
            if (!plugin.access().check(viewer, c, Perm.MANAGE_CITIZENS)) return;
            String err = plugin.sim().population().recruit(viewer, c);
            if (err != null) Text.send(viewer, "<red>" + err);
            refresh();
        });
        warmth(31);
        var academy = plugin.sim().academy();
        List<String> res = new ArrayList<>();
        res.add(c.researching == null ? "<gray>Nothing under way" : "<gray>" + c.researching.display + ": <white>" + Math.round(academy.progress(c) * 100) + "%");
        res.add("<gray>Known: " + c.research.size() + " projects · University level " + academy.universityLevel(c));
        res.add("<dark_gray>Click to open");
        button(32, Material.KNOWLEDGE_BOOK, "<green>Research", res, k -> new ResearchMenu(plugin, viewer, c).open());
        button(33, Material.COMPARATOR, "<green>Settings", List.of("<gray>Policies: how the colony is run", "<dark_gray>Click to open"), k -> new PoliciesMenu(plugin, viewer, c).open());
        button(34, Material.BOOKSHELF, "<green>More…", List.of("<gray>Members, history, statistics, raids,", "<gray>requests, milestones and today's news", "<dark_gray>Click to open"),
                k -> new MoreMenu(plugin, viewer, c).open());
        if (c.questStage <= QuestManager.COUNT) {
            QuestManager q = plugin.quests();
            button(40, Material.MAP, "<gold>First steps: " + q.title(c.questStage), List.of("<gray>" + Text.esc(q.task(c.questStage))), k -> chat("colony quests"));
        }
        button(44, Material.BOOK, "<green>Help", List.of("<gray>How colonies work, page by page", "<dark_gray>Click to open"), k -> new HelpMenu(plugin, viewer).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    /** The tier and the way up (with tiers off: the Town Hall upgrade, as before). */
    private void tier(int slot) {
        var tiers = plugin.tiers();
        Building th = c.townHall();
        if (tiers.on()) {
            List<String> lore = new ArrayList<>();
            lore.add(Messages.compose("tiers.menu.name", "tier", tiers.of(c).coloured(), "n", tiers.tier(c)));
            if (tiers.tier(c) < Tiers.KINGDOM) {
                var checks = tiers.checks(c);
                long done = checks.stream().filter(Tiers.Check::done).count();
                lore.add(Messages.compose("tiers.menu.next", "tier", tiers.get(tiers.tier(c) + 1).coloured()) + " <dark_gray>(" + done + "/" + checks.size() + " ready)");
                if (tiers.canTierUp(c) == null) lore.add(Messages.compose("tiers.menu.ready", "tier", tiers.get(tiers.tier(c) + 1).coloured()));
            } else {
                lore.add(Messages.get("tiers.menu.top"));
            }
            lore.add("<dark_gray>Click to see the tiers");
            var icon = Items.icon(Material.BEACON, "<gold>Tier", lore);
            set(slot, tiers.canTierUp(c) == null ? Items.glow(icon) : icon, k -> new TierMenu(plugin, viewer, c).open());
            return;
        }
        if (th == null) return;
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Settlement level " + th.level + "/" + th.type.maxLevel);
        if (th.level < th.type.maxLevel) {
            lore.add("<gray>Next level needs " + BuildingManager.townHallPopulation(th.level + 1) + " citizens and");
            lore.add("<gray>" + BuildingManager.costText(th.type, th.level + 1));
            String can = plugin.buildings().canUpgrade(c, th);
            lore.add(can == null ? "<green>Click to upgrade" : "<red>" + Text.esc(can));
        }
        button(slot, Material.BEACON, "<green>Upgrade the Town Hall", lore, k -> {
            String err = plugin.buildings().upgrade(viewer, c, th);
            if (err != null) Text.send(viewer, "<red>" + err);
            refresh();
        });
    }

    /** What the colony is working towards: first steps, requests, milestones. */
    private void goals(int slot) {
        List<String> lore = new ArrayList<>();
        boolean steps = c.questStage <= QuestManager.COUNT;
        if (steps) lore.add("<gold>First steps: <white>" + Text.esc(plugin.quests().title(c.questStage)));
        lore.add("<gray>" + c.requests.size() + " citizen requests open");
        lore.add("<gray>Milestones " + c.milestones.size() + "/" + plugin.milestones().all().size());
        lore.add("<dark_gray>Click to list in chat");
        button(slot, Material.WRITABLE_BOOK, "<green>Tasks and goals", lore, k -> chat(steps ? "colony quests" : "colony requests"));
    }

    /** Winter warmth, when winter needs are on. */
    private void warmth(int slot) {
        var winter = plugin.sim().winter();
        if (!winter.season()) return;
        List<String> lore = new ArrayList<>();
        var sum = winter.summary(c);
        if (!winter.active(c)) lore.add(Messages.get("winter.menu.spared"));
        else if (sum == null) lore.add(Messages.get("winter.menu.waiting"));
        else {
            lore.add(Messages.get("winter.menu.people", "warm", sum.warm(), "cold", sum.cold(), "freezing", sum.freezing()));
            lore.add(Messages.get("winter.menu.houses", "heated", sum.heatedHouses(), "unheated", sum.unheatedHouses()));
            lore.add(Messages.compose("winter.menu.hall", "state", Messages.get(sum.hallLit() ? "winter.menu.hall-lit" : "winter.menu.hall-out")));
        }
        long wood = winter.firewood(c);
        lore.add(Messages.get("winter.menu.firewood", "n", wood, "days", sum == null ? "?" : String.valueOf((int) Math.floor(Math.min(99, sum.days())))));
        lore.add(Messages.get("winter.menu.how"));
        var icon = Items.icon(Material.CAMPFIRE, Messages.get("winter.menu.name"), lore);
        set(slot, sum != null && sum.cold() + sum.freezing() > 0 ? Items.glow(icon) : icon, null);
    }
}
