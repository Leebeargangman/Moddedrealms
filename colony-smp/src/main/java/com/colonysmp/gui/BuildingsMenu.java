package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Recipe;
import com.colonysmp.core.Recipes;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Perm;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Every registered building, and each one's own page. */
public final class BuildingsMenu extends Menu {

    private final ColonySMP plugin;
    private final Colony c;
    private int page;

    public BuildingsMenu(ColonySMP plugin, Player viewer, Colony c) {
        super(viewer, 6, "<dark_aqua>Buildings · " + Text.esc(c.name));
        this.plugin = plugin;
        this.c = c;
    }

    @Override
    protected void draw() {
        List<Building> list = new ArrayList<>(c.buildings.values());
        list.sort(Comparator.comparing((Building b) -> b.type.category.ordinal()).thenComparing(b -> b.type.ordinal()).thenComparingInt(b -> b.id));
        int from = pager(page, list.size(), 45, p -> {
            page = p;
            refresh();
        });
        for (int i = 0; i < 45 && from + i < list.size(); i++) {
            Building b = list.get(from + i);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Level " + b.level + " · " + c.workers(b).size() + " workers");
            lore.add(b.valid ? "<green>Working" : "<red>" + Text.esc(b.problem == null ? "Failed inspection" : b.problem));
            if (b.waiting != null) lore.add("<yellow>" + Text.esc(b.waiting));
            lore.add("<dark_gray>Click for details");
            ItemStack icon = Items.icon(b.type.icon, (b.valid ? "<white>" : "<red>") + Text.esc(b.label()), lore);
            icon.setAmount(Math.max(1, b.level));
            set(i, icon, k -> new BuildingMenu(b).open());
        }
        if (list.isEmpty()) button(22, Material.BOOK, "<gray>No buildings yet", List.of("<gray>Build a room or field, then Shift + Right-Click", "<gray>inside it with the Blueprint Book."), null);
        button(49, Material.ARROW, "<white>Back to the Town Hall", List.of(), k -> new TownHallMenu(plugin, viewer, c).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    /** One building. */
    private final class BuildingMenu extends Menu {
        private final Building b;

        BuildingMenu(Building b) {
            super(BuildingsMenu.this.viewer, 4, "<dark_aqua>" + Text.esc(b.label()));
            this.b = b;
        }

        @Override
        protected void draw() {
            if (c.building(b.id) == null) {
                button(13, Material.BARRIER, "<gray>That building is gone", List.of(), null);
                return;
            }
            button(4, b.type.icon, "<aqua>" + Text.esc(b.label()), plugin.commands().info().building(c, b), null);
            String can = b.level < b.type.maxLevel ? plugin.buildings().canUpgrade(c, b) : "Highest level";
            button(19, Material.EXPERIENCE_BOTTLE, "<green>Upgrade", List.of(can == null ? "<green>Click to upgrade" : "<red>" + Text.esc(can)), k -> {
                String err = plugin.buildings().upgrade(viewer, c, b);
                if (err != null) Text.send(viewer, "<red>" + err);
                refresh();
            });
            button(20, Material.SPYGLASS, "<green>Inspect now", List.of("<gray>Check it still meets its requirements"), k -> {
                viewer.closeInventory();
                viewer.performCommand("colony validate " + b.id);
            });
            List<String> workers = new ArrayList<>();
            for (Citizen w : c.workers(b)) workers.add("<gray>" + Text.esc(w.name) + " (" + w.job.display + ")");
            if (workers.isEmpty()) workers.add("<gray>Nobody works here");
            button(21, Material.VILLAGER_SPAWN_EGG, "<green>Workers", workers, k -> new CitizensMenu(plugin, viewer, c, x -> x.workplace == b.id).open());
            if (b.type == BuildingType.FARM) {
                button(22, Material.WHEAT, "<green>Crop: " + (b.crop == null ? "follow demand" : b.crop), List.of("<dark_gray>Click to change"), k -> {
                    if (!plugin.access().check(viewer, c, Perm.MANAGE_BUILDINGS)) return;
                    String[] crops = {null, "wheat", "carrots", "potatoes", "beetroots"};
                    int i = 0;
                    for (int j = 0; j < crops.length; j++) if (java.util.Objects.equals(crops[j], b.crop)) i = j;
                    b.crop = crops[(i + 1) % crops.length];
                    plugin.requestSave();
                    refresh();
                });
            }
            if (b.type == BuildingType.ANIMAL_PEN || b.type == BuildingType.STABLES) {
                button(22, Material.LEAD, "<green>Herd size: " + b.herdTarget, List.of("<gray>Animals of each kind kept before", "<gray>the surplus goes to slaughter", "<dark_gray>Left +1 · Right -1"), k -> {
                    if (!plugin.access().check(viewer, c, Perm.MANAGE_BUILDINGS)) return;
                    b.herdTarget = Math.max(2, Math.min(64, b.herdTarget + (k.right() ? -1 : 1)));
                    plugin.requestSave();
                    refresh();
                });
            }
            List<Recipe> recipes = Recipes.of(b.type.station());
            if (!recipes.isEmpty()) {
                button(23, Material.CRAFTING_TABLE, "<green>Recipes", List.of("<gray>" + recipes.size() + " things can be made here", "<dark_gray>Click to choose which"), k -> new RecipesMenu(b).open());
            }
            button(24, Material.NAME_TAG, "<green>Rename", List.of("<gray>Give it a name"), k -> {
                if (!plugin.access().check(viewer, c, Perm.MANAGE_BUILDINGS)) return;
                viewer.closeInventory();
                plugin.prompt().ask(viewer, "Name for " + Text.esc(b.label()) + ":", 30, name -> {
                    b.name = name.isBlank() ? null : name.substring(0, Math.min(24, name.length()));
                    plugin.requestSave();
                    Text.send(viewer, "Renamed to " + Text.esc(b.label()) + ".");
                });
            });
            if (b.type != BuildingType.TOWN_HALL) {
                button(25, Material.TNT, "<red>Unregister", List.of("<gray>The blocks stay; the colony forgets it"), k -> {
                    viewer.closeInventory();
                    viewer.performCommand("colony building remove " + b.id);
                });
            }
            button(31, Material.ARROW, "<white>Back", List.of(), k -> BuildingsMenu.this.open());
            fill(Material.GRAY_STAINED_GLASS_PANE);
        }
    }

    /** Which of a workplace's recipes are made. */
    private final class RecipesMenu extends Menu {
        private final Building b;

        RecipesMenu(Building b) {
            super(BuildingsMenu.this.viewer, 6, "<dark_aqua>Recipes · " + Text.esc(b.label()));
            this.b = b;
        }

        @Override
        protected void draw() {
            int i = 0;
            for (Recipe r : Recipes.of(b.type.station())) {
                if (i >= 45) break;
                boolean on = !b.disabled.contains(r.id);
                List<String> lore = new ArrayList<>();
                List<String> in = new ArrayList<>(), out = new ArrayList<>();
                r.inputs.forEach((k, v) -> in.add(v + " " + (Recipes.isGroup(k) ? Recipes.groupName(k) : k.equals(Recipe.FUEL) ? "fuel" : com.colonysmp.core.Goods.get(k).name())));
                r.outputs.forEach((k, v) -> out.add(v + " " + com.colonysmp.core.Goods.get(k).name()));
                lore.add("<gray>" + String.join(", ", in) + " → <white>" + String.join(", ", out));
                lore.add("<gray>" + Text.num(r.hours) + " hours of work" + (r.onDemand ? " · only when needed" : ""));
                lore.add(on ? "<green>Made here · click to stop" : "<red>Not made · click to allow");
                Material icon = com.colonysmp.util.ItemGoods.icon(r.primary());
                button(i++, icon, (on ? "<white>" : "<gray>") + r.name, lore, k -> {
                    if (!plugin.access().check(viewer, c, Perm.MANAGE_BUILDINGS)) return;
                    if (!b.disabled.remove(r.id)) b.disabled.add(r.id);
                    plugin.requestSave();
                    refresh();
                });
            }
            button(49, Material.ARROW, "<white>Back", List.of(), k -> new BuildingMenu(b).open());
            fill(Material.GRAY_STAINED_GLASS_PANE);
        }
    }
}
