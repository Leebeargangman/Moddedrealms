package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** The building types a Building Marker's building could be (best match first): pick one to register it. */
public final class MarkerMenu extends Menu {

    private final ColonySMP plugin;
    private final Block at;
    private final List<BuildingType> options;
    private final Building existing;

    public MarkerMenu(ColonySMP plugin, Player viewer, Block at, List<BuildingType> options, Building existing) {
        super(viewer, Math.min(6, 1 + (options.size() + 8) / 9), "<dark_aqua>What is this building?");
        this.plugin = plugin;
        this.at = at;
        this.options = options;
        this.existing = existing;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < options.size() && i < 45; i++) {
            BuildingType t = options.get(i);
            boolean current = existing != null && existing.type == t;
            List<String> lore = new ArrayList<>();
            lore.add("<dark_gray>" + t.category.display);
            lore.add("<gray>" + t.description);
            lore.add("");
            if (current) lore.add("<aqua>It's registered as this now.");
            else if (i == 0) lore.add("<green>Best match");
            if (existing != null && !current) lore.add("<yellow>Replaces " + Text.esc(existing.label()) + " (its level and workers are reset).");
            lore.add(current ? "<white>Click</white><gray>: inspect again" : "<white>Click</white><gray>: register it as " + Text.a(t.display));
            var icon = Items.icon(t.icon, (current ? "<aqua>" : i == 0 ? "<green>" : "<white>") + t.display, lore);
            if (i == 0 || current) Items.glow(icon);
            set(i, icon, c -> {
                viewer.closeInventory();
                plugin.markers().register(viewer, at, t, current ? null : existing);
            });
        }
        int last = getInventory().getSize() - 1;
        button(last, Material.BARRIER, "<red>Cancel", List.of("<gray>Leave it as it is."), c -> viewer.closeInventory());
    }
}
