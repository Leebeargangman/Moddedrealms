package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Perm;
import com.colonysmp.model.Policies;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** How the colony is run: click a policy to change it. */
public final class PoliciesMenu extends Menu {

    public static final List<String> NAMES = List.of("economy", "wages", "tax", "hours", "recruitment", "population", "military", "posture", "emergency",
            "priority", "foodreserve", "autosell", "autobuy", "neutraltrade", "quarantine", "rationing", "births");

    private final ColonySMP plugin;
    private final Colony c;

    public PoliciesMenu(ColonySMP plugin, Player viewer, Colony c) {
        super(viewer, 5, "<dark_aqua>Policies · " + Text.esc(c.name));
        this.plugin = plugin;
        this.c = c;
    }

    /** Sets a policy by name from text (the command). Returns why not, or null. */
    public static String set(ColonySMP plugin, Colony c, String name, String value) {
        Policies p = c.policies;
        String v = value.trim();
        switch (name.toLowerCase(Locale.ROOT)) {
            case "economy" -> p.economy = Policies.parse(Policies.Economy.class, v, p.economy);
            case "wages" -> p.wages = Policies.parse(Policies.Wages.class, v, p.wages);
            case "tax" -> {
                double t = number(v);
                if (Double.isNaN(t)) return "Tax is a percentage from 0 to 30.";
                p.taxRate = Math.max(0, Math.min(0.3, t > 1 ? t / 100 : t));
            }
            case "hours" -> p.hours = Policies.parse(Policies.Hours.class, v, p.hours);
            case "recruitment" -> p.recruitment = Policies.parse(Policies.Recruitment.class, v, p.recruitment);
            case "population" -> {
                double n = number(v);
                if (Double.isNaN(n)) return "The population limit is a number (0 for none).";
                p.populationLimit = (int) Math.max(0, n);
            }
            case "military" -> p.military = Policies.parse(Policies.Military.class, v, p.military);
            case "posture" -> p.posture = Policies.parse(Policies.Posture.class, v, p.posture);
            case "emergency" -> p.emergency = Policies.parse(Policies.Emergency.class, v, p.emergency);
            case "priority" -> p.priority = Policies.parse(Policies.Priority.class, v, p.priority);
            case "foodreserve" -> {
                double n = number(v);
                if (Double.isNaN(n)) return "Food reserve is a number of days.";
                p.foodReserveDays = (int) Math.max(0, Math.min(30, n));
            }
            case "autosell" -> p.autoSell = bool(v);
            case "autobuy" -> p.autoBuy = bool(v);
            case "neutraltrade" -> p.tradeWithNeutrals = bool(v);
            case "quarantine" -> p.quarantine = bool(v);
            case "rationing" -> p.rationing = bool(v);
            case "births" -> p.births = bool(v);
            default -> {
                return "Policies: " + String.join(", ", NAMES);
            }
        }
        plugin.sim().jobs().refreshWorkplaces(c);
        return null;
    }

    private static double number(String s) {
        try {
            return Double.parseDouble(s.replace("%", ""));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static boolean bool(String s) {
        return s.equalsIgnoreCase("on") || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes");
    }

    private void change(Runnable r, String what) {
        if (!plugin.access().check(viewer, c, Perm.POLICIES)) return;
        r.run();
        c.log(ColonyEvent.Type.COLONY, plugin.sim().day(c), viewer.getName() + " changed the " + what + " policy.");
        plugin.requestSave();
        refresh();
    }

    @Override
    protected void draw() {
        Policies p = c.policies;
        button(10, Material.GOLD_INGOT, "<gold>Economy: <white>" + p.economy.display, List.of("<gray>" + p.economy.description, "<dark_gray>Click to change"),
                k -> change(() -> p.economy = Policies.next(p.economy), "economy"));
        button(11, Material.EMERALD, "<gold>Wages: <white>" + p.wages.display, List.of("<gray>Higher wages cheer workers up and cost more", "<dark_gray>Click to change"),
                k -> change(() -> p.wages = Policies.next(p.wages), "wages"));
        button(12, Material.PAPER, "<gold>Tax: <white>" + Math.round(p.taxRate * 100) + "%", List.of("<gray>Withheld from wages into the treasury;", "<gray>citizens dislike high taxes",
                "<dark_gray>Left +5% · Right -5%"), k -> change(() -> p.taxRate = Math.max(0, Math.min(0.3, p.taxRate + (k.right() ? -0.05 : 0.05))), "tax"));
        button(13, Material.CLOCK, "<gold>Working hours: <white>" + p.hours.display, List.of("<gray>Longer hours, more work, less happiness", "<dark_gray>Click to change"),
                k -> change(() -> p.hours = Policies.next(p.hours), "hours"));
        button(14, Material.OAK_DOOR, "<gold>Recruitment: <white>" + p.recruitment.display, List.of("<gray>" + p.recruitment.description, "<dark_gray>Click to change"),
                k -> change(() -> p.recruitment = Policies.next(p.recruitment), "recruitment"));
        button(15, Material.RED_BED, "<gold>Population limit: <white>" + (p.populationLimit == 0 ? "none" : p.populationLimit), List.of("<gray>Stop growing past this many",
                "<dark_gray>Left +5 · Right -5"), k -> change(() -> p.populationLimit = Math.max(0, p.populationLimit + (k.right() ? -5 : 5)), "population"));
        button(16, Material.CAKE, "<gold>Births: <white>" + (p.births ? "allowed" : "discouraged"), List.of("<gray>Couples with a home may have children", "<dark_gray>Click to change"),
                k -> change(() -> p.births = !p.births, "births"));
        button(19, Material.IRON_SWORD, "<gold>Military service: <white>" + p.military.display, List.of("<gray>" + p.military.description, "<dark_gray>Click to change"),
                k -> change(() -> p.military = Policies.next(p.military), "military"));
        button(20, Material.SHIELD, "<gold>Defence posture: <white>" + p.posture.display, List.of("<gray>" + p.posture.description, "<dark_gray>Click to change"),
                k -> change(() -> p.posture = Policies.next(p.posture), "posture"));
        button(21, Material.BELL, "<gold>In an emergency: <white>" + p.emergency.display, List.of("<gray>" + p.emergency.description, "<dark_gray>Click to change"),
                k -> change(() -> p.emergency = Policies.next(p.emergency), "emergency"));
        button(22, Material.HOPPER, "<gold>Production priority: <white>" + p.priority.display, List.of("<gray>What workshops make first and where", "<gray>the administrator sends new workers",
                "<dark_gray>Click to change"), k -> change(() -> p.priority = Policies.next(p.priority), "priority"));
        button(23, Material.BREAD, "<gold>Food reserve: <white>" + p.foodReserveDays + " days", List.of("<gray>Food never sold or traded below this", "<dark_gray>Left +1 · Right -1"),
                k -> change(() -> p.foodReserveDays = Math.max(0, Math.min(30, p.foodReserveDays + (k.right() ? -1 : 1))), "food reserve"));
        button(24, Material.WHEAT, "<gold>Rationing: <white>" + (p.rationing ? "on" : "off"), List.of("<gray>Smaller meals: food lasts longer,", "<gray>people are less content", "<dark_gray>Click to change"),
                k -> change(() -> p.rationing = !p.rationing, "rationing"));
        button(25, Material.GLISTERING_MELON_SLICE, "<gold>Quarantine: <white>" + (p.quarantine ? "on" : "off"), List.of("<gray>The ill stay home: slows outbreaks,", "<gray>but they don't work",
                "<dark_gray>Click to change"), k -> change(() -> p.quarantine = !p.quarantine, "quarantine"));
        button(28, Material.CHEST_MINECART, "<gold>Auto-sell surplus: <white>" + (p.autoSell ? "on" : "off"), List.of("<gray>Merchants sell what's over the stock", "<gray>targets each day (needs a Trading Post)",
                "<dark_gray>Click to change"), k -> change(() -> p.autoSell = !p.autoSell, "auto-sell"));
        button(29, Material.HOPPER_MINECART, "<gold>Auto-buy shortfalls: <white>" + (p.autoBuy ? "on" : "off"), List.of("<gray>Merchants buy goods below their minimum", "<gray>stock target each day",
                "<dark_gray>Click to change"), k -> change(() -> p.autoBuy = !p.autoBuy, "auto-buy"));
        button(30, Material.MAP, "<gold>Trade with neutrals: <white>" + (p.tradeWithNeutrals ? "yes" : "friends only"), List.of("<gray>Who may trade with your colony", "<dark_gray>Click to change"),
                k -> change(() -> p.tradeWithNeutrals = !p.tradeWithNeutrals, "trade"));
        button(40, Material.ARROW, "<white>Back to the Town Hall", List.of(), k -> new TownHallMenu(plugin, viewer, c).open());
        fill(Material.GRAY_STAINED_GLASS_PANE);
    }
}
