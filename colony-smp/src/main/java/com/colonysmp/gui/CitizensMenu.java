package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.model.Building;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.LifeStage;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Every citizen of a colony (or a filtered few, to pick one). */
public final class CitizensMenu extends Menu {

    private final ColonySMP plugin;
    private final Colony c;
    private final Predicate<Citizen> filter;
    private final Consumer<Citizen> pick;
    private int page;

    public CitizensMenu(ColonySMP plugin, Player viewer, Colony c, Predicate<Citizen> filter) {
        this(plugin, viewer, c, filter, null, "<dark_aqua>Citizens · " + Text.esc(c.name));
    }

    /** A picker: clicking a citizen hands them to {@code pick}. */
    public CitizensMenu(ColonySMP plugin, Player viewer, Colony c, Predicate<Citizen> filter, Consumer<Citizen> pick, String title) {
        super(viewer, 6, title);
        this.plugin = plugin;
        this.c = c;
        this.filter = filter;
        this.pick = pick;
    }

    @Override
    protected void draw() {
        List<Citizen> list = new ArrayList<>();
        for (Citizen ct : c.citizens.values()) if (filter == null || filter.test(ct)) list.add(ct);
        list.sort(Comparator.comparing((Citizen x) -> x.job.ordinal()).thenComparing(x -> x.name));
        int from = pager(page, list.size(), 45, p -> {
            page = p;
            refresh();
        });
        for (int i = 0; i < 45 && from + i < list.size(); i++) {
            Citizen ct = list.get(from + i);
            set(i, icon(ct), k -> {
                if (pick != null) pick.accept(ct);
                else new CitizenMenu(plugin, viewer, c, ct).open();
            });
        }
        if (list.isEmpty()) button(22, Material.BARRIER, "<gray>Nobody here", List.of(), null);
        button(49, Material.ARROW, "<white>Back to the Town Hall", List.of(), k -> new TownHallMenu(plugin, viewer, c).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }

    private ItemStack icon(Citizen ct) {
        List<String> lore = new ArrayList<>();
        LifeStage stage = plugin.sim().citizens().stage(ct);
        Building w = c.building(ct.workplace);
        lore.add("<gray>" + stage.display + " · " + ct.trait.display);
        if (w != null) lore.add("<gray>At " + Text.esc(w.label()));
        lore.add("<gray>Mood " + Text.meter(ct.needs.mood));
        lore.add("<gray>Health " + Text.meter(ct.needs.healthFraction() * 100));
        lore.add("<gray>Hunger " + Text.meter(ct.needs.satiety));
        lore.add("<dark_gray>" + Text.esc(ct.activity));
        if (ct.waiting != null) lore.add("<yellow>" + Text.esc(ct.waiting));
        if (ct.ill()) lore.add("<light_purple>" + ct.illness.display);
        Material m = !ct.status.free() ? Material.IRON_BARS : stage == LifeStage.CHILD ? Material.EGG : ct.job.icon;
        return Items.icon(m, ct.status.color + Text.esc(ct.name) + " <gray>(" + ct.title() + ")", lore);
    }
}
