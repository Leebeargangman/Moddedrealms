package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.building.BuildingManager;
import com.colonysmp.colony.QuestManager;
import com.colonysmp.model.Building;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Perm;
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
        button(4, Material.LODESTONE, "<aqua><bold>" + Text.esc(c.name), info.colony(c), null);
        boolean jobStep = c.questStage == QuestManager.JOB;
        List<String> citizensLore = new ArrayList<>(List.of("<gray>" + c.population() + " people: needs, jobs, homes", "<dark_gray>Click to open"));
        if (jobStep) citizensLore.add(0, "<gold>◀ Next step: give a settler a job");
        var citizensIcon = com.colonysmp.util.Items.icon(Material.VILLAGER_SPAWN_EGG, (jobStep ? "<gold><bold>" : "<green>") + "Citizens", citizensLore);
        set(19, jobStep ? com.colonysmp.util.Items.glow(citizensIcon) : citizensIcon, k -> new CitizensMenu(plugin, viewer, c, null).open());
        if (jobStep) {
            set(18, com.colonysmp.util.Items.icon(Material.SPECTRAL_ARROW, "<gold><bold>Start here ▶", List.of("<gray>Open Citizens, pick a settler", "<gray>and give them a job.")));
        }
        button(20, Material.IRON_PICKAXE, "<green>Jobs", List.of("<gray>Open places and who does what", "<dark_gray>Click to list in chat"), k -> chat("colony jobs"));
        button(21, Material.BRICKS, "<green>Buildings", List.of("<gray>" + c.buildings.size() + " registered buildings", "<dark_gray>Click to open"),
                k -> new BuildingsMenu(plugin, viewer, c).open());
        button(22, Material.CHEST, "<green>Storage", List.of("<gray>" + c.storage.usedSlots() + "/" + c.storage.capacity() + " slots used", "<dark_gray>Click to open"),
                k -> new StorageMenu(plugin, viewer, c).open());
        button(23, Material.GOLD_INGOT, "<green>Economy", List.of("<gray>Treasury " + Text.value(c.treasury.balance()), "<dark_gray>Click to open"),
                k -> new EconomyMenu(plugin, viewer, c).open());
        button(24, Material.WRITABLE_BOOK, "<green>Policies", List.of("<gray>How the colony is run", "<dark_gray>Click to open"), k -> new PoliciesMenu(plugin, viewer, c).open());
        button(25, Material.IRON_SWORD, "<green>Military", List.of("<gray>" + plugin.military().army(c).size() + " soldiers, " + c.squads.size() + " squads", "<dark_gray>Click to open"),
                k -> new MilitaryMenu(plugin, viewer, c).open());
        button(28, Material.BLUE_BANNER, "<green>Diplomacy", List.of("<gray>Relations, treaties, trade and war", "<dark_gray>Click to open"),
                k -> new DiplomacyMenu(plugin, viewer, c).open());
        button(29, Material.SCAFFOLDING, "<green>Construction", info.construction(c), k -> chat("colony construction"));
        button(30, Material.BOOK, "<green>History", List.of("<gray>Everything that happened, by day", "<dark_gray>Click to list in chat"), k -> chat("colony events"));
        button(31, Material.CLOCK, "<green>Statistics", List.of("<gray>The last days: population, food,", "<gray>mood, stability, treasury", "<dark_gray>Click to list in chat"),
                k -> chat("colony stats"));
        long cost = plugin.settings().recruitCost * (1 + c.population() / 10);
        String why = plugin.sim().population().roomForOne(c);
        button(32, Material.EMERALD, "<green>Recruit a settler", List.of("<gray>Costs " + Text.value(cost), why == null ? "<green>There's room" : "<red>" + Text.esc(why)), k -> {
            if (!plugin.access().check(viewer, c, Perm.MANAGE_CITIZENS)) return;
            String err = plugin.sim().population().recruit(viewer, c);
            if (err != null) Text.send(viewer, "<red>" + err);
            refresh();
        });
        Building th = c.townHall();
        if (th != null) {
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Settlement level " + th.level + "/" + th.type.maxLevel);
            if (th.level < th.type.maxLevel) {
                lore.add("<gray>Next level needs " + BuildingManager.townHallPopulation(th.level + 1) + " citizens and");
                lore.add("<gray>" + BuildingManager.costText(th.type, th.level + 1));
                String can = plugin.buildings().canUpgrade(c, th);
                lore.add(can == null ? "<green>Click to upgrade" : "<red>" + Text.esc(can));
            }
            button(33, Material.BEACON, "<green>Upgrade the Town Hall", lore, k -> {
                String err = plugin.buildings().upgrade(viewer, c, th);
                if (err != null) Text.send(viewer, "<red>" + err);
                refresh();
            });
        }
        button(34, Material.PLAYER_HEAD, "<green>Members", List.of("<gray>" + c.members.size() + " members", "<dark_gray>Click to list in chat"), k -> chat("colony members"));
        var academy = plugin.sim().academy();
        List<String> res = new ArrayList<>();
        res.add(c.researching == null ? "<gray>Nothing under way" : "<gray>" + c.researching.display + ": <white>" + Math.round(academy.progress(c) * 100) + "%");
        res.add("<gray>Known: " + c.research.size() + " projects · University level " + academy.universityLevel(c));
        res.add("<dark_gray>Click to open");
        button(37, Material.KNOWLEDGE_BOOK, "<green>Research", res, k -> new ResearchMenu(plugin, viewer, c).open());
        button(38, Material.PAPER, "<green>Requests", List.of("<gray>" + c.requests.size() + " citizens are asking for something", "<gray>Answered so far: " + c.requestsFilled,
                "<dark_gray>Click to list in chat"), k -> chat("colony requests"));
        List<String> raid = new ArrayList<>(plugin.raids().describe(c));
        raid.add("<dark_gray>Click to list in chat");
        button(39, Material.CROSSBOW, "<green>Raids", raid, k -> chat("colony raid"));
        List<String> today = new ArrayList<>(plugin.happenings().describe(c));
        today.add("<dark_gray>Click to list in chat");
        button(41, Material.FIREWORK_ROCKET, "<green>Today", today, k -> chat("colony today"));
        button(42, Material.NETHER_STAR, "<green>Milestones", List.of("<gray>" + c.milestones.size() + "/" + plugin.milestones().all().size() + " reached", "<dark_gray>Click to list in chat"),
                k -> chat("colony milestones"));
        if (c.questStage <= QuestManager.COUNT) {
            QuestManager q = plugin.quests();
            button(40, Material.MAP, "<gold>First steps: " + q.title(c.questStage), List.of("<gray>" + Text.esc(q.task(c.questStage))), k -> chat("colony quests"));
        }
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
