package net.moddedrealms.smp.play;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Daily login rewards with a streak: claim once a day (UTC) with /daily; coming back the next day moves the streak
 * on, missing a day starts it again. Seven reward days from the config, the seventh the best, then the week repeats.
 * Rewards are in-game only (money, items, crate keys).
 */
public final class DailyStreak implements Listener, CommandExecutor {
    private final SmpPlugin plugin;
    private final File file;
    private final YamlConfiguration y;

    public DailyStreak(SmpPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "streaks.yml");
        this.y = YamlConfiguration.loadConfiguration(file);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("daily.enabled", true);
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private void save() {
        try {
            net.moddedrealms.smp.SafeYaml.save(y, file);
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save streaks.yml: " + e.getMessage());
        }
    }

    /** The reward lines for day 1..7 of the week. */
    List<List<String>> days() {
        List<List<String>> out = new ArrayList<>();
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("daily.rewards");
        for (int d = 1; d <= 7; d++) {
            List<String> r = s == null ? List.of() : s.getStringList("day-" + d);
            if (r.isEmpty()) r = defaults(d);
            out.add(r);
        }
        return out;
    }

    private static List<String> defaults(int d) {
        return switch (d) {
            case 1 -> List.of("money:100");
            case 2 -> List.of("money:150", "item:COOKED_BEEF:16");
            case 3 -> List.of("money:200", "key:common:1");
            case 4 -> List.of("money:250", "item:IRON_INGOT:12");
            case 5 -> List.of("money:300", "key:rare:1");
            case 6 -> List.of("money:400", "item:GOLDEN_APPLE:2");
            default -> List.of("money:600", "key:rare:1", "item:DIAMOND:3");
        };
    }

    /** Streak count a claim today would have (1 if the last claim wasn't yesterday). */
    int nextStreak(UUID u) {
        String last = y.getString(u + ".last");
        int streak = y.getInt(u + ".streak");
        if (last == null) return 1;
        LocalDate l = LocalDate.parse(last);
        if (l.equals(today())) return streak;
        return l.equals(today().minusDays(1)) ? streak + 1 : 1;
    }

    boolean claimedToday(UUID u) {
        return today().toString().equals(y.getString(u + ".last"));
    }

    public int streak(UUID u) {
        String last = y.getString(u + ".last");
        if (last == null) return 0;
        LocalDate l = LocalDate.parse(last);
        return l.equals(today()) || l.equals(today().minusDays(1)) ? y.getInt(u + ".streak") : 0;
    }

    public int best(UUID u) {
        return y.getInt(u + ".best");
    }

    /** Everyone's best streak, for the leaderboards: uuid -> best. */
    public java.util.Map<UUID, Integer> bests() {
        java.util.Map<UUID, Integer> out = new java.util.HashMap<>();
        for (String k : y.getKeys(false)) {
            try {
                out.put(UUID.fromString(k), y.getInt(k + ".best"));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled()) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || claimedToday(p.getUniqueId())) return;
            int n = nextStreak(p.getUniqueId());
            int day = (n - 1) % 7 + 1;
            String what = plugin.rewards().describe(days().get(day - 1));
            p.sendMessage(SmpPlugin.mm("<gold>★ Daily reward ready!</gold> <gray>Day " + day + " of 7" + (n > 1 ? " · <yellow>" + n + "-day streak</yellow>" : "")
                    + ": <white>" + what + "</white> <click:run_command:'/daily'><hover:show_text:'<gray>Claim it'><green><u>[Claim]</u></green></hover></click>"));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.6f);
        }, 160L);
    }

    static final class Menu implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        if (!enabled()) {
            p.sendMessage(SmpPlugin.mm("<gray>Daily rewards are off."));
            return true;
        }
        open(p);
        return true;
    }

    void open(Player p) {
        UUID u = p.getUniqueId();
        boolean claimed = claimedToday(u);
        int n = claimed ? y.getInt(u + ".streak") : nextStreak(u);
        int today = (n - 1) % 7 + 1;
        Menu m = new Menu();
        m.inv = Bukkit.createInventory(m, 27, SmpPlugin.mm("Daily rewards · streak " + (claimed ? n : n - 1)));
        ItemStack glass = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) m.inv.setItem(i, glass);
        List<List<String>> days = days();
        for (int d = 1; d <= 7; d++) {
            String what = plugin.rewards().describe(days.get(d - 1));
            Material icon;
            List<String> lore = new ArrayList<>();
            lore.add("<white>" + what);
            lore.add("");
            if (d < today || d == today && claimed) {
                icon = Material.LIME_STAINED_GLASS_PANE;
                lore.add("<green>Claimed");
            } else if (d == today) {
                icon = d == 7 ? Material.ENDER_CHEST : Material.CHEST;
                lore.add("<yellow>Click to claim today's reward!");
            } else {
                icon = d == 7 ? Material.ENDER_CHEST : Material.BARREL;
                lore.add("<gray>Come back in " + (d - today) + " day" + (d - today == 1 ? "" : "s"));
            }
            m.inv.setItem(9 + d, item(icon, (d == today && !claimed ? "<gold><bold>" : "<white>") + "Day " + d + (d == 7 ? " · the big one" : ""), lore));
        }
        m.inv.setItem(22, item(Material.CLOCK, "<gold>How streaks work", List.of("<gray>Claim once a day (resets at midnight UTC).", "<gray>Come back tomorrow to keep your streak;",
                "<gray>miss a day and it starts again at day 1.", "<gray>Best streak: <white>" + best(u) + " <gray>days")));
        p.openInventory(m.inv);
    }

    private static ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(SmpPlugin.mm("<!italic>" + name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(SmpPlugin.mm("<!italic>" + s));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        if (slot < 10 || slot > 16) return;
        UUID u = p.getUniqueId();
        if (claimedToday(u)) {
            p.sendMessage(SmpPlugin.mm("<gray>You've had today's reward. Come back tomorrow to keep your streak!"));
            return;
        }
        int n = nextStreak(u);
        int day = (n - 1) % 7 + 1;
        if (slot != 9 + day) return;
        y.set(u + ".last", today().toString());
        y.set(u + ".streak", n);
        y.set(u + ".best", Math.max(n, y.getInt(u + ".best")));
        y.set(u + ".name", p.getName());
        save(); // before the reward, so a crash can't hand it out twice
        List<String> r = days().get(day - 1);
        plugin.rewards().give(p, r);
        p.playSound(p.getLocation(), day == 7 ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
        p.sendMessage(SmpPlugin.mm("<green>Day " + day + " claimed: <white>" + plugin.rewards().describe(r) + "</white>." + (n > 1 ? " <yellow>" + n + "-day streak!" : "")
                + " <gray>Come back tomorrow for day " + (day % 7 + 1) + "."));
        if (n % 7 == 0) Bukkit.broadcast(SmpPlugin.mm("<gold>★ " + p.getName() + " has logged in " + n + " days in a row!"));
        open(p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}
