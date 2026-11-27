package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.util.Items;
import com.colonysmp.util.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /colony help: a short guide in pages (Basics, Jobs, Buildings, Tiers, Empire, War, Siege Weapons), each a few
 * lines with example icons. All of it lives in messages.yml under help, so it can be rewritten without a new jar.
 */
public final class HelpMenu extends Menu {

    private final ColonySMP plugin;

    public HelpMenu(ColonySMP plugin, Player viewer) {
        super(viewer, 3, Messages.get("help.title"));
        this.plugin = plugin;
    }

    static Material material(String name, Material fallback) {
        Material m = name == null ? null : Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
        return m != null && m.isItem() ? m : fallback;
    }

    @Override
    protected void draw() {
        List<String> pages = Messages.keys("help.pages");
        int slot = 10;
        for (String page : pages) {
            if (slot > 16) break;
            String k = "help.pages." + page;
            List<String> lore = new java.util.ArrayList<>(Messages.lines(k + ".summary"));
            lore.add("");
            lore.add("<yellow>Click to read");
            set(slot++, Items.icon(material(Messages.get(k + ".icon"), Material.BOOK), Messages.get(k + ".name"), lore), c -> new Page(page).open());
        }
        button(22, Material.BARRIER, "<white>Close", List.of(), c -> viewer.closeInventory());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    private final class Page extends Menu {
        private final String page;

        Page(String page) {
            super(HelpMenu.this.viewer, 4, Messages.get("help.pages." + page + ".name"));
            this.page = page;
        }

        @Override
        protected void draw() {
            String k = "help.pages." + page;
            set(4, Items.icon(material(Messages.get(k + ".icon"), Material.BOOK), Messages.get(k + ".name"), Messages.lines(k + ".text")));
            int slot = 19;
            for (String ex : Messages.lines(k + ".examples")) {
                if (slot > 25) break;
                // "MATERIAL|name|lore line|lore line"
                String[] f = ex.split("\\|");
                List<String> lore = f.length > 2 ? Arrays.asList(Arrays.copyOfRange(f, 2, f.length)) : List.of();
                set(slot++, Items.icon(material(f[0], Material.PAPER), f.length > 1 ? f[1] : "", lore));
            }
            button(31, Material.ARROW, "<white>Back", List.of(), c -> new HelpMenu(plugin, viewer).open());
            fill(Material.GRAY_STAINED_GLASS_PANE);
        }
    }
}
