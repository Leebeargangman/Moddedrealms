package net.moddedrealms.smp.season;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.moddedrealms.smp.SafeYaml;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Cosmetics players have unlocked (titles for now), in cosmetics.yml. They're never wiped by a season reset.
 * /titles picks the title to wear; it shows through %mrsmp_title%.
 */
public final class Cosmetics implements Listener, TabExecutor {
    private final SeasonManager s;
    private final File file;
    private YamlConfiguration y;

    Cosmetics(SeasonManager s) {
        this.s = s;
        this.file = new File(s.plugin().getDataFolder(), "cosmetics.yml");
        y = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    private ConfigurationSection def(String id) {
        return s.cfg().getConfigurationSection("cosmetics." + id);
    }

    public boolean exists(String id) {
        return def(id) != null;
    }

    /** The cosmetic as MiniMessage ("<aqua>❄ Snowflake"), or the id if it isn't defined. */
    public String display(String id) {
        ConfigurationSection d = def(id);
        return d == null ? id : d.getString("display", id);
    }

    public String plainName(String id) {
        return PlainTextComponentSerializer.plainText().serialize(SmpPlugin.mm(display(id)));
    }

    public Set<String> unlocked(UUID id) {
        return new LinkedHashSet<>(y.getStringList("players." + id + ".unlocked"));
    }

    public boolean has(UUID id, String cosmetic) {
        return unlocked(id).contains(cosmetic);
    }

    /** Unlocks a cosmetic for good; false if they had it already or it doesn't exist. */
    public boolean unlock(OfflinePlayer p, String cosmetic) {
        if (!exists(cosmetic)) {
            s.plugin().getLogger().warning("Unknown cosmetic '" + cosmetic + "' (add it under cosmetics: in season.yml)");
            return false;
        }
        Set<String> u = unlocked(p.getUniqueId());
        if (!u.add(cosmetic)) return false;
        y.set("players." + p.getUniqueId() + ".unlocked", new ArrayList<>(u));
        if (p.getName() != null) y.set("players." + p.getUniqueId() + ".name", p.getName());
        save();
        if (p instanceof Player online) {
            online.sendMessage(SmpPlugin.mm("<light_purple>✦ New title unlocked:</light_purple> " + display(cosmetic)
                    + " <gray>· <click:run_command:'/titles'><yellow><u>/titles</u></yellow></click> to wear it"));
        }
        return true;
    }

    /** The title someone wears, as MiniMessage, or "" for none. */
    public String title(UUID id) {
        String t = y.getString("players." + id + ".title");
        return t == null || !has(id, t) || !exists(t) ? "" : display(t);
    }

    /** The worn title as legacy colour codes (for TAB and chat plugins that read placeholders). */
    public String legacyTitle(UUID id) {
        String t = title(id);
        return t.isEmpty() ? "" : LegacyComponentSerializer.legacySection().serialize(SmpPlugin.mm(t));
    }

    private void wear(Player p, String cosmetic) {
        y.set("players." + p.getUniqueId() + ".title", cosmetic);
        save();
    }

    private void save() {
        try {
            SafeYaml.save(y, file);
        } catch (IOException e) {
            s.plugin().getLogger().log(Level.SEVERE, "Couldn't save cosmetics.yml", e);
        }
    }

    // ------------------------------------------------------------------ /titles

    private static final class Holder implements InventoryHolder {
        Inventory inv;
        final List<String> ids = new ArrayList<>();

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    public void open(Player p) {
        Holder h = new Holder();
        h.ids.addAll(unlocked(p.getUniqueId()));
        h.ids.removeIf(id -> !exists(id));
        int rows = Math.max(1, Math.min(6, (h.ids.size() + 9) / 9 + 1));
        Inventory inv = Bukkit.createInventory(h, rows * 9, SmpPlugin.mm("<dark_purple>Your titles"));
        h.inv = inv;
        String worn = y.getString("players." + p.getUniqueId() + ".title");
        for (int i = 0; i < h.ids.size() && i < rows * 9 - 1; i++) {
            String id = h.ids.get(i);
            boolean on = id.equals(worn);
            inv.setItem(i, Advent.icon(on ? Material.NAME_TAG : Material.PAPER, display(id),
                    List.of(on ? "<green>Wearing it. <gray>Click to take it off." : "<gray>Click to wear it."), on));
        }
        if (h.ids.isEmpty()) {
            inv.setItem(4, Advent.icon(Material.BARRIER, "<gray>No titles yet", List.of("<gray>Open advent doors (<white>/advent</white>) and take", "<gray>part in season events to unlock them."), false));
        }
        p.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= h.ids.size()) return;
        String id = h.ids.get(slot);
        if (!has(p.getUniqueId(), id)) return;
        boolean off = id.equals(y.getString("players." + p.getUniqueId() + ".title"));
        wear(p, off ? null : id);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        p.sendActionBar(SmpPlugin.mm(off ? "<gray>Title taken off." : "<gray>Now wearing " + display(id)));
        Bukkit.getScheduler().runTask(s.plugin(), () -> open(p));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (a.length >= 3 && a[0].equalsIgnoreCase("give") && sender.hasPermission("mrsmp.admin")) {
            OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(a[1]);
            if (t == null) {
                sender.sendMessage(SmpPlugin.mm("<red>No player called " + a[1] + " has been here."));
                return true;
            }
            if (!exists(a[2])) {
                sender.sendMessage(SmpPlugin.mm("<red>No cosmetic '" + a[2] + "' in season.yml."));
                return true;
            }
            sender.sendMessage(SmpPlugin.mm(unlock(t, a[2]) ? "<green>Unlocked " + a[2] + " for " + t.getName() + "." : "<gray>They have it already."));
            return true;
        }
        if (sender instanceof Player p) open(p);
        else sender.sendMessage("Staff: /titles give <player> <cosmetic>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] a) {
        if (!sender.hasPermission("mrsmp.admin")) return List.of();
        if (a.length == 1) return List.of("give");
        if (a.length == 3) {
            ConfigurationSection c = s.cfg().getConfigurationSection("cosmetics");
            return c == null ? List.of() : new ArrayList<>(c.getKeys(false));
        }
        return null;
    }
}
