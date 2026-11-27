package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Proposal;
import com.colonysmp.model.Relation;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Other colonies: how things stand, offers waiting, and what can be proposed. */
public final class DiplomacyMenu extends Menu {

    private final ColonySMP plugin;
    private final Colony c;

    public DiplomacyMenu(ColonySMP plugin, Player viewer, Colony c) {
        super(viewer, 6, "<dark_blue>Diplomacy · " + Text.esc(c.name));
        this.plugin = plugin;
        this.c = c;
    }

    private void chat(String cmd) {
        viewer.closeInventory();
        viewer.performCommand(cmd);
    }

    @Override
    protected void draw() {
        var dip = plugin.diplomacy();
        long day = plugin.sim().day(c);
        int slot = 0;
        for (Colony o : plugin.colonies().list()) {
            if (o == c || slot >= 36) continue;
            Relation r = dip.existing(c, o);
            Relation.Status s = r == null ? Relation.Status.NEUTRAL : r.status(day);
            List<String> lore = new ArrayList<>();
            lore.add(s.color + s.display + " <gray>(" + (r == null ? 0 : Math.round(r.score)) + ")");
            lore.add("<gray>" + o.population() + " citizens · level " + o.level());
            if (r != null) for (Relation.Treaty t : r.treaties) lore.add("<aqua>" + t.display);
            if (r != null && !r.history.isEmpty()) lore.add("<dark_gray>" + Text.esc(r.history.peekFirst()));
            lore.add("<dark_gray>Click for options");
            Material icon = switch (s) {
                case ALLIED -> Material.LIGHT_BLUE_BANNER;
                case FRIENDLY -> Material.LIME_BANNER;
                case HOSTILE -> Material.ORANGE_BANNER;
                case AT_WAR -> Material.RED_BANNER;
                case TRUCE -> Material.YELLOW_BANNER;
                default -> Material.WHITE_BANNER;
            };
            button(slot++, icon, "<white>" + Text.esc(o.name), lore, k -> new ColonyOptions(o).open());
        }
        if (slot == 0) button(13, Material.MAP, "<gray>No other colonies yet", List.of(), null);
        int i = 36;
        for (Proposal p : dip.incoming(c)) {
            if (i >= 45) break;
            Colony from = plugin.colonies().get(p.from);
            button(i++, Material.PAPER, "<yellow>Offer from " + (from == null ? "?" : Text.esc(from.name)), List.of("<gray>" + Text.esc(dip.describe(p)),
                    "<green>Left: accept <gray>· <red>Right: decline"), k -> {
                String err = k.right() ? dip.decline(viewer, p) : dip.accept(viewer, p);
                if (err != null) Text.send(viewer, "<red>" + err);
                refresh();
            });
        }
        button(49, Material.ARROW, "<white>Back to the Town Hall", List.of(), k -> new TownHallMenu(plugin, viewer, c).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    /** What can be done with one other colony. */
    private final class ColonyOptions extends Menu {
        private final Colony o;

        ColonyOptions(Colony o) {
            super(DiplomacyMenu.this.viewer, 4, "<dark_blue>" + Text.esc(o.name));
            this.o = o;
        }

        @Override
        protected void draw() {
            var dip = plugin.diplomacy();
            Relation r = dip.existing(c, o);
            String n = o.name;
            set(4, Items.icon(Material.LODESTONE, "<white>" + Text.esc(n), plugin.commands().info().colony(o)));
            button(10, Material.LIGHT_BLUE_BANNER, r != null && r.allied ? "<red>Break the alliance" : "<green>Propose an alliance",
                    List.of("<gray>Allies defend each other and", "<gray>may use each other's doors"), k -> chat(r != null && r.allied ? "colony diplomacy cancel " + n + " alliance" : "colony diplomacy ally " + n));
            int s = 11;
            for (Relation.Treaty t : Relation.Treaty.values()) {
                boolean has = r != null && r.treaties.contains(t);
                button(s++, has ? Material.WRITTEN_BOOK : Material.WRITABLE_BOOK, (has ? "<red>Cancel " : "<green>Propose ") + t.display, List.of("<gray>" + t.description),
                        k -> chat(has ? "colony diplomacy cancel " + n + " " + t.name() : "colony diplomacy treaty " + n + " " + t.name()));
            }
            button(16, Material.EMERALD, "<green>Send a gift", List.of("<gray>Valuables from the treasury improve relations"), k -> {
                viewer.closeInventory();
                plugin.prompt().ask(viewer, "How much worth of valuables do you give " + Text.esc(n) + "?", 30, a -> viewer.performCommand("colony diplomacy gift " + n + " " + a));
            });
            boolean war = plugin.wars().atWar(c, o);
            if (war) {
                button(22, Material.WHITE_BANNER, "<green>Offer peace", List.of("<gray>Ends the war with a truce"), k -> chat("colony peace " + n));
            } else {
                button(21, Material.IRON_SWORD, "<red>Declare a raid", List.of("<gray>A short attack to loot and score"), k -> chat("colony declarewar " + n + " raid"));
                button(23, Material.TNT, "<red>Declare a siege", List.of("<gray>Raise a war camp; destroy their", "<gray>Town Hall or cut off their stores"), k -> chat("colony declarewar " + n + " siege"));
            }
            button(31, Material.ARROW, "<white>Back", List.of(), k -> DiplomacyMenu.this.open());
            fill(Material.GRAY_STAINED_GLASS_PANE);
        }
    }
}
