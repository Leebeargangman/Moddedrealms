package com.colonysmp.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Yes / no. */
public final class ConfirmMenu extends Menu {

    private final String question;
    private final List<String> details;
    private final Runnable yes;
    private final Runnable no;

    public ConfirmMenu(Player viewer, String question, List<String> details, Runnable yes, Runnable no) {
        super(viewer, 3, "<dark_red>Confirm");
        this.question = question;
        this.details = details;
        this.yes = yes;
        this.no = no;
    }

    @Override
    protected void draw() {
        button(13, Material.PAPER, question, details, null);
        button(11, Material.LIME_CONCRETE, "<green><bold>Yes", List.of("<gray>" + "Confirm"), c -> {
            viewer.closeInventory();
            yes.run();
        });
        button(15, Material.RED_CONCRETE, "<red><bold>No", List.of("<gray>Cancel"), c -> {
            viewer.closeInventory();
            if (no != null) no.run();
        });
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
