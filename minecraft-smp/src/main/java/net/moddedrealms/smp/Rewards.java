package net.moddedrealms.smp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Reward lines like "money:250", "key:rare:1", "xp:100", "item:BREAD:16", "cmd:...". */
public final class Rewards {
    private final SmpPlugin plugin;

    Rewards(SmpPlugin plugin) {
        this.plugin = plugin;
    }

    public void give(Player p, List<String> rewards) {
        var cfg = plugin.getConfig();
        for (String r : rewards) {
            String[] a = r.split(":", 3);
            String kind = a[0].toLowerCase(Locale.ROOT);
            try {
                switch (kind) {
                    case "money" -> {
                        if (plugin.town() != null && plugin.town().money.physical()) plugin.town().money.give(p, Double.parseDouble(a[1]));
                        else console(cfg.getString("rewards.money-command", "eco give {player} {amount}"), p, a[1], "");
                    }
                    case "key" -> {
                        int n = a.length > 2 ? Integer.parseInt(a[2]) : 1;
                        plugin.crates().giveKeys(p.getUniqueId(), a[1], n);
                        p.sendMessage(SmpPlugin.mm("<light_purple>+" + n + " " + plugin.crates().keyName(a[1]) + (n == 1 ? "" : "s")
                                + " <gray>(open at the Crate Shrine or with /crates)"));
                    }
                    case "xp" -> console(cfg.getString("rewards.xp-command", "xp add {player} {amount} points"), p, a[1], "");
                    case "item" -> {
                        Material m = Material.matchMaterial(a[1]);
                        int n = a.length > 2 ? Integer.parseInt(a[2]) : 1;
                        if (m != null) {
                            for (ItemStack left : p.getInventory().addItem(new ItemStack(m, n)).values()) {
                                p.getWorld().dropItemNaturally(p.getLocation(), left);
                            }
                        }
                    }
                    case "cmd" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), r.substring(4).replace("{player}", p.getName()));
                    default -> plugin.getLogger().warning("Unknown reward: " + r);
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Reward '" + r + "' for " + p.getName() + " failed: " + ex.getMessage());
            }
        }
    }

    private void console(String template, Player p, String amount, String crate) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), template.replace("{player}", p.getName())
                .replace("{amount}", amount).replace("{crate}", crate));
    }

    /** "$250, 1 Rare Key, 100 XP" */
    public String describe(List<String> rewards) {
        List<String> out = new ArrayList<>();
        for (String r : rewards) {
            String[] a = r.split(":", 3);
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "money" -> out.add("$" + a[1]);
                case "key" -> {
                    String n = plugin.crates().keyName(a[1]);
                    String amt = a.length > 2 ? a[2] : "1";
                    out.add(amt + " " + n + ("1".equals(amt) ? "" : "s"));
                }
                case "xp" -> out.add(a[1] + " XP");
                case "item" -> out.add((a.length > 2 ? a[2] + " " : "") + pretty(a[1]));
                default -> { }
            }
        }
        return String.join(", ", out);
    }

    public static String pretty(String enumName) {
        String s = enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder b = new StringBuilder();
        for (String w : s.split(" ")) if (!w.isEmpty()) b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        return b.toString().trim();
    }
}
