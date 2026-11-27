package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.colony.Tiers;
import com.colonysmp.model.Colony;
import com.colonysmp.util.Items;
import com.colonysmp.util.Messages;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** The road from Camp to Empire: where the colony is, what the next tier needs, and the button to move up. */
public final class TierMenu extends Menu {

    private static final Material[] ICONS = {Material.AIR, Material.CAMPFIRE, Material.OAK_DOOR, Material.BELL,
            Material.LODESTONE, Material.GOLDEN_HELMET, Material.NETHER_STAR};

    private final ColonySMP plugin;
    private final Colony c;

    public TierMenu(ColonySMP plugin, Player viewer, Colony c) {
        super(viewer, 4, "<dark_aqua>Tiers · " + Text.esc(c.name));
        this.plugin = plugin;
        this.c = c;
    }

    @Override
    protected void draw() {
        Tiers tiers = plugin.tiers();
        int now = tiers.tier(c);
        for (int n = Tiers.CAMP; n <= Tiers.EMPIRE; n++) {
            Tiers.Tier t = tiers.get(n);
            List<String> lore = new ArrayList<>();
            if (n < now) lore.add("<green>✔ Reached");
            else if (n == now) lore.add("<gold>◆ You are here");
            else if (n == now + 1) lore.add("<yellow>Next");
            else lore.add("<dark_gray>Later");
            if (n > Tiers.CAMP && n <= Tiers.KINGDOM) {
                lore.add("<gray>Needs " + t.settlers() + " settlers" + (t.money() > 0 ? " and " + Text.value(t.money()) : ""));
            }
            if (!t.unlocks().isEmpty()) {
                lore.add(Messages.get("tiers.menu.unlocks"));
                for (String u : t.unlocks()) lore.add("<white> • " + u);
            }
            var icon = Items.icon(ICONS[n], t.coloured() + (n == now ? " <gray>(now)" : ""), lore);
            set(10 + n - 1, n == now ? Items.glow(icon) : icon, null);
        }
        // the next step up
        List<String> lore = new ArrayList<>();
        if (now >= Tiers.KINGDOM) {
            lore.add(Messages.get("tiers.menu.top"));
            button(31, Material.BEACON, "<gold>Move up a tier", lore, null);
        } else {
            Tiers.Tier next = tiers.get(now + 1);
            lore.add(Messages.compose("tiers.menu.next", "tier", next.coloured()));
            lore.add(Messages.get("tiers.menu.need"));
            for (Tiers.Check ch : tiers.checks(c)) {
                lore.add(Messages.compose(ch.done() ? "tiers.menu.have" : "tiers.menu.missing", "what", Text.esc(ch.label())));
            }
            String why = tiers.canTierUp(c);
            lore.add(why == null ? Messages.compose("tiers.menu.ready", "tier", next.coloured()) : "<dark_gray>" + Text.esc(why));
            var icon = Items.icon(Material.BEACON, "<gold>Become a " + next.coloured(), lore);
            set(31, why == null ? Items.glow(icon) : icon, k -> {
                String err = tiers.tierUp(viewer, c);
                if (err != null) {
                    Text.send(viewer, "<red>" + Text.esc(err));
                    viewer.playSound(viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
                } else {
                    Text.send(viewer, Messages.compose("tiers.done", "colony", Text.esc(c.name), "tier", tiers.of(c).coloured()));
                }
                refresh();
            });
        }
        button(27, Material.ARROW, "<white>Back", List.of("<gray>To the Town Hall"), k -> new TownHallMenu(plugin, viewer, c).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
