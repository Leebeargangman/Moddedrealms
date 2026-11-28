package net.moddedrealms.minigames;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** The items players are given: hub menu, leave, knife, bow, gold... tagged so clicks can be recognised. */
final class Items {

    static NamespacedKey key;

    private Items() {
    }

    static ItemStack make(Material m, String id, Component name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        if (!l.isEmpty()) meta.lore(l);
        meta.addItemFlags(ItemFlag.values());
        meta.setUnbreakable(true);
        if (key != null) meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
        it.setItemMeta(meta);
        return it;
    }

    static String id(ItemStack it) {
        if (it == null || key == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    static ItemStack games() {
        return make(Material.COMPASS, "games", Component.text("Play a game", NamedTextColor.GREEN, TextDecoration.BOLD), "Right-click to pick a game");
    }

    static ItemStack stats() {
        return make(Material.BOOK, "stats", Component.text("Your stats", NamedTextColor.AQUA), "Right-click to see your wins");
    }

    static ItemStack back(String label) {
        return make(Material.RED_BED, "back", Component.text("Back to " + label, NamedTextColor.RED), "Right-click to leave the minigames");
    }

    static ItemStack leave() {
        return make(Material.RED_BED, "leave", Component.text("Leave the game", NamedTextColor.RED), "Right-click to go back to the hub");
    }

    static ItemStack again() {
        return make(Material.PAPER, "again", Component.text("Play again", NamedTextColor.GREEN), "Right-click to join the next game");
    }

    static ItemStack knife() {
        return make(Material.IRON_SWORD, "knife", Component.text("Knife", NamedTextColor.RED, TextDecoration.BOLD),
                "Hit someone to kill them.", "Right-click to throw it.");
    }

    static ItemStack bow(boolean detective) {
        return make(Material.BOW, "bow", Component.text(detective ? "Detective's bow" : "Bow", NamedTextColor.AQUA, TextDecoration.BOLD),
                "Shoot the murderer.", "Shoot an innocent and you die too!");
    }

    static ItemStack arrow() {
        return make(Material.ARROW, "arrow", Component.text("Arrow", NamedTextColor.WHITE));
    }

    static ItemStack gold(int n) {
        ItemStack it = make(Material.GOLD_INGOT, "gold", Component.text("Gold", NamedTextColor.GOLD), "Collect 10 for a bow and an arrow.");
        it.setAmount(Math.max(1, Math.min(64, n)));
        return it;
    }

    static ItemStack doubleJump(int n) {
        ItemStack it = make(Material.FEATHER, "jump", Component.text("Double jump", NamedTextColor.AQUA), "Press jump twice in the air.");
        it.setAmount(Math.max(1, n));
        return it;
    }
}
