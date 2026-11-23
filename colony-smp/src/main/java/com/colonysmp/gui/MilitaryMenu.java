package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Job;
import com.colonysmp.model.LifeStage;
import com.colonysmp.model.Perm;
import com.colonysmp.model.Squad;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** The army: soldiers, squads, their orders and formations. */
public final class MilitaryMenu extends Menu {

    private final ColonySMP plugin;
    private final Colony c;

    public MilitaryMenu(ColonySMP plugin, Player viewer, Colony c) {
        super(viewer, 5, "<dark_red>Military · " + Text.esc(c.name));
        this.plugin = plugin;
        this.c = c;
    }

    @Override
    protected void draw() {
        var mm = plugin.military();
        button(4, Material.IRON_SWORD, "<red>The army", plugin.commands().info().military(c), null);
        button(10, Material.VILLAGER_SPAWN_EGG, "<green>Recruit", List.of("<gray>Pick a citizen to enlist", "<gray>Army " + mm.army(c).size() + "/" + mm.capacity(c)), k -> {
            if (!plugin.access().check(viewer, c, Perm.MILITARY)) return;
            new CitizensMenu(plugin, viewer, c, ct -> ct.status.free() && ct.job != Job.SOLDIER && plugin.sim().citizens().stage(ct) == LifeStage.ADULT,
                    ct -> new CitizenMenu(plugin, viewer, c, ct).open(), "<dark_red>Who will enlist?").open();
        });
        button(11, Material.SHIELD, "<green>Soldiers", List.of("<gray>Everyone under arms"), k -> new CitizensMenu(plugin, viewer, c, ct -> ct.job.military()).open());
        button(12, Material.BLAZE_ROD, "<green>Command Baton", List.of("<gray>Right-click a block: rally the squad", "<gray>Shift + right-click: next order",
                "<gray>Left-click an enemy: focus it", "<gray>Shift + left-click: next squad"), k -> {
            if (!plugin.access().check(viewer, c, Perm.MILITARY)) return;
            com.colonysmp.command.ColonyCommand.give(viewer, plugin.items().baton());
        });
        button(13, Material.WHITE_BANNER, "<green>New squad", List.of("<gray>Group soldiers under one order"), k -> {
            if (!plugin.access().check(viewer, c, Perm.MILITARY)) return;
            Squad q = mm.createSquad(c, "Squad " + c.nextSquadId);
            mm.select(viewer, q);
            new SquadMenu(q).open();
        });
        button(14, Material.BELL, c.mobilized() ? "<red>Mobilised" : "<green>Mobilise", List.of("<gray>Everyone stands to arms for " + plugin.settings().mobilizeMinutes + " minutes",
                "<gray>Costs " + Math.round(plugin.settings().mobilizeCost) + " stability"), k -> {
            String err = mm.mobilize(viewer, c);
            if (err != null) Text.send(viewer, "<red>" + err);
            refresh();
        });
        int slot = 19;
        for (Squad q : c.squads.values()) {
            if (slot > 34) break;
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + mm.members(c, q).size() + " soldiers · " + q.order.display + " · " + q.formation.display);
            lore.add("<gray>Morale " + Math.round(q.morale));
            lore.add("<dark_gray>Click to command");
            button(slot++, Material.RED_BANNER, "<white>" + Text.esc(q.name), lore, k -> new SquadMenu(q).open());
            if (slot % 9 == 8) slot += 2;
        }
        button(40, Material.ARROW, "<white>Back to the Town Hall", List.of(), k -> new TownHallMenu(plugin, viewer, c).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    /** One squad: orders, formation, members. */
    private final class SquadMenu extends Menu {
        private final Squad q;

        SquadMenu(Squad q) {
            super(MilitaryMenu.this.viewer, 5, "<dark_red>" + Text.esc(q.name));
            this.q = q;
        }

        @Override
        protected void draw() {
            var mm = plugin.military();
            if (c.squad(q.id) == null) {
                button(13, Material.BARRIER, "<gray>Disbanded", List.of(), null);
                return;
            }
            int i = 0;
            for (Squad.Order o : Squad.Order.values()) {
                Material icon = switch (o) {
                    case DEFEND -> Material.SHIELD;
                    case HOLD, HOLD_FIRE -> Material.COBBLESTONE_WALL;
                    case FOLLOW, PROTECT -> Material.LEAD;
                    case PATROL -> Material.COMPASS;
                    case CHARGE, FLANK -> Material.IRON_HORSE_ARMOR;
                    case SHIELD_WALL -> Material.OAK_FENCE;
                    case SPEAR_WALL -> Material.TRIDENT;
                    case VOLLEY -> Material.BOW;
                    case FOCUS -> Material.TARGET;
                    case RETREAT -> Material.WHITE_BANNER;
                };
                button(i++, icon, (q.order == o ? "<gold>" : "<white>") + o.display, List.of("<gray>" + o.description, "<dark_gray>Click: order here (where you stand)"), k -> {
                    String err = mm.order(viewer, c, q, o, viewer.getLocation());
                    if (err != null) Text.send(viewer, "<red>" + err);
                    refresh();
                });
            }
            button(20, Material.ITEM_FRAME, "<green>Formation: " + q.formation.display, List.of("<dark_gray>Click to change"), k -> {
                if (!plugin.access().check(viewer, c, Perm.MILITARY)) return;
                q.formation = com.colonysmp.model.Policies.next(q.formation);
                plugin.requestSave();
                refresh();
            });
            List<String> names = new ArrayList<>();
            for (Citizen ct : mm.members(c, q)) names.add("<gray>" + Text.esc(ct.name) + " (" + (ct.militaryClass == null ? "?" : ct.militaryClass.display) + ")");
            if (names.isEmpty()) names.add("<gray>No soldiers: add them with the baton");
            button(22, Material.ARMOR_STAND, "<green>Soldiers", names, k -> new CitizensMenu(plugin, viewer, c, ct -> ct.job == Job.SOLDIER && ct.squad != q.id,
                    ct -> {
                        ct.squad = q.id;
                        plugin.requestSave();
                        new SquadMenu(q).open();
                    }, "<dark_red>Add to " + Text.esc(q.name)).open());
            button(24, Material.NAME_TAG, "<green>Rename", List.of(), k -> {
                viewer.closeInventory();
                plugin.prompt().ask(viewer, "Name for the squad:", 30, n -> {
                    if (!n.isBlank()) q.name = n.substring(0, Math.min(24, n.length()));
                    plugin.requestSave();
                });
            });
            button(26, Material.BARRIER, "<red>Disband", List.of("<gray>The soldiers stay in the army"), k -> {
                mm.deleteSquad(c, q);
                MilitaryMenu.this.open();
            });
            button(40, Material.ARROW, "<white>Back", List.of(), k -> MilitaryMenu.this.open());
            fill(Material.GRAY_STAINED_GLASS_PANE);
        }
    }
}
