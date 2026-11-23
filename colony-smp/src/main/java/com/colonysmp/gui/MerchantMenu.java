package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.colony.Happenings;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** A visiting merchant's wares: livestock handed over on a lead, and goods the colony can't make itself. */
public final class MerchantMenu extends Menu {

    private final ColonySMP plugin;
    private final Happenings.Caravan caravan;

    public MerchantMenu(ColonySMP plugin, Player viewer, Happenings.Caravan caravan) {
        super(viewer, 3, "<gold>" + Text.esc(caravan.name) + "'s wares");
        this.plugin = plugin;
        this.caravan = caravan;
    }

    @Override
    protected void draw() {
        int slot = 1;
        for (Happenings.Offer o : caravan.offers) {
            // livestock on the top row, goods on the second
            if (o.animal == null && slot < 10) slot = 10;
            if (slot == 17) slot = 19;
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Price: <gold>" + Text.value(o.price));
            lore.add(o.stock > 0 ? "<gray>Left: <white>" + o.stock : "<red>Sold out");
            if (o.animal != null) lore.add("<dark_gray>Handed over on a lead: walk it home");
            if (o.stock > 0) lore.add("<dark_gray>Click to buy");
            button(slot++, o.stock > 0 ? o.icon : Material.BARRIER, (o.stock > 0 ? "<white>" : "<gray>") + o.name, lore, k -> {
                String err = plugin.happenings().buy(viewer, caravan, o);
                if (err != null) Text.bar(viewer, "<red>" + err);
                refresh();
            });
        }
        button(22, Material.EMERALD, "<green>You carry: " + plugin.economy().money(viewer),
                List.of("<gray>" + Text.esc(caravan.name) + " moves on after a few minutes."), null);
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
