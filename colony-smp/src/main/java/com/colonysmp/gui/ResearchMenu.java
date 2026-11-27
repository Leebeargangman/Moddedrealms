package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Research;
import com.colonysmp.model.Colony;
import com.colonysmp.sim.Academy;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** The colony's research: what is known, what is being worked on, and what could be begun. */
public final class ResearchMenu extends Menu {

    private final ColonySMP plugin;
    private final Colony c;

    public ResearchMenu(ColonySMP plugin, Player viewer, Colony c) {
        super(viewer, 6, "<dark_purple>Research · " + Text.esc(c.name));
        this.plugin = plugin;
        this.c = c;
    }

    private static Material icon(Research.Branch b) {
        return switch (b) {
            case FARMING -> Material.WHEAT;
            case INDUSTRY -> Material.IRON_PICKAXE;
            case SOCIETY -> Material.BREAD;
            case KNOWLEDGE -> Material.BOOK;
            case DEFENCE -> Material.SHIELD;
        };
    }

    @Override
    protected void draw() {
        Academy academy = plugin.sim().academy();
        int level = academy.universityLevel(c), staff = academy.researchers(c);
        List<String> head = new ArrayList<>();
        head.add(level == 0 ? "<red>No University yet" : "<gray>University level <white>" + level + "</white>, <white>" + staff + "</white> researcher" + (staff == 1 ? "" : "s"));
        if (level == 0) {
            head.add("<gray>Build a room with a lectern and 8 bookshelves,");
            head.add("<gray>put a Building Marker in it and choose University.");
        } else if (staff == 0) head.add("<red>Nobody is researching: assign a Researcher (education 50+).");
        if (c.researching != null) {
            head.add("<light_purple>Working on: <white>" + c.researching.display);
            head.add("<gray>" + Text.bar(academy.progress(c), 20, "<light_purple>|", "<dark_gray>|") + " <white>" + Math.round(academy.progress(c) * 100) + "%");
            head.add("<dark_gray>Shift-click here to abandon it (what was paid is lost)");
        } else head.add("<gray>Nothing is being researched. Choose a project below.");
        head.add("<gray>Known: <white>" + c.research.size() + "/" + Research.values().length);
        button(4, Material.KNOWLEDGE_BOOK, "<light_purple><bold>Research", head, k -> {
            if (!k.shift() || c.researching == null) return;
            String err = academy.abandon(viewer, c);
            if (err != null) Text.send(viewer, "<red>" + err);
            refresh();
        });
        int row = 1;
        for (Research.Branch b : Research.Branch.values()) {
            int slot = row * 9;
            button(slot, icon(b), "<aqua>" + b.display, List.of(), null);
            slot += 2;
            for (Research r : Research.values()) {
                if (r.branch != b) continue;
                boolean known = c.research.contains(r), current = c.researching == r;
                String why = known || current ? null : academy.blocked(c, r);
                List<String> lore = new ArrayList<>();
                lore.add("<gray>" + r.description);
                lore.add("");
                if (known) lore.add("<green>✔ Known");
                else if (current) lore.add("<light_purple>Being researched: " + Math.round(academy.progress(c) * 100) + "%");
                else {
                    lore.add("<gray>Costs: <white>" + academy.costText(r));
                    lore.add("<gray>Work: <white>" + Math.round(r.hours) + " researcher hours</white>, University level " + r.tier);
                    if (r.requires() != null) lore.add("<gray>After: <white>" + r.requires().display);
                    lore.add(why == null ? "<green>Click to begin" : "<red>" + Text.esc(why));
                }
                Material m = known ? Material.ENCHANTED_BOOK : current ? Material.WRITABLE_BOOK : why == null ? Material.BOOK : Material.PAPER;
                button(slot++, m, (known ? "<green>" : current ? "<light_purple>" : why == null ? "<white>" : "<gray>") + r.display, lore, k -> {
                    if (known || current) return;
                    String err = academy.start(viewer, c, r);
                    if (err != null) Text.send(viewer, "<red>" + err);
                    refresh();
                });
            }
            row++;
        }
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
