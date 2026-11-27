package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.model.Colony;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** The Town Hall's second page: everything that isn't needed every day. */
public final class MoreMenu extends Menu {

    private final ColonySMP plugin;
    private final Colony c;

    public MoreMenu(ColonySMP plugin, Player viewer, Colony c) {
        super(viewer, 3, "<dark_aqua>Town Hall · more");
        this.plugin = plugin;
        this.c = c;
    }

    private void chat(String command) {
        viewer.closeInventory();
        viewer.performCommand(command);
    }

    @Override
    protected void draw() {
        button(10, Material.PLAYER_HEAD, "<green>Members", List.of("<gray>" + c.members.size() + " members and their roles", "<dark_gray>Click to list in chat"), k -> chat("colony members"));
        button(11, Material.IRON_PICKAXE, "<green>Jobs", List.of("<gray>Open places and who does what", "<dark_gray>Click to list in chat"), k -> chat("colony jobs"));
        button(12, Material.PAPER, "<green>Requests", List.of("<gray>" + c.requests.size() + " citizens are asking for something", "<gray>Answered so far: " + c.requestsFilled,
                "<dark_gray>Click to list in chat"), k -> chat("colony requests"));
        List<String> raid = new ArrayList<>(plugin.raids().describe(c));
        raid.add("<dark_gray>Click to list in chat");
        button(13, Material.CROSSBOW, "<green>Raids", raid, k -> chat("colony raid"));
        List<String> today = new ArrayList<>(plugin.happenings().describe(c));
        today.add("<dark_gray>Click to list in chat");
        button(14, Material.FIREWORK_ROCKET, "<green>Today", today, k -> chat("colony today"));
        button(15, Material.NETHER_STAR, "<green>Milestones", List.of("<gray>" + c.milestones.size() + "/" + plugin.milestones().all().size() + " reached", "<dark_gray>Click to list in chat"),
                k -> chat("colony milestones"));
        button(16, Material.BOOK, "<green>History", List.of("<gray>Everything that happened, by day", "<dark_gray>Click to list in chat"), k -> chat("colony events"));
        button(22, Material.CLOCK, "<green>Statistics", List.of("<gray>The last days: population, food,", "<gray>mood, stability, treasury", "<dark_gray>Click to list in chat"),
                k -> chat("colony stats"));
        button(18, Material.ARROW, "<white>Back", List.of("<gray>To the Town Hall"), k -> new TownHallMenu(plugin, viewer, c).open());
        button(26, Material.LODESTONE, "<aqua>" + Text.esc(c.name), List.of("<gray>Colony info in chat", "<dark_gray>Click"), k -> chat("colony info"));
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
