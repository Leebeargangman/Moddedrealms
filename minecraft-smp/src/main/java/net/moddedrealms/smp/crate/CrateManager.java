package net.moddedrealms.smp.crate;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.moddedrealms.smp.Rewards;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

/**
 * Crates with virtual keys. A crate menu (Keeper Isolde, the shrine vaults, /crates), a preview of every reward
 * with its odds, and an opening animation: a reel of rewards that slows down and stops on what you won.
 */
public final class CrateManager implements Listener {
    public record Reward(String name, Material icon, double chance, List<String> give, boolean announce) {}

    public record Crate(String id, String name, Material icon, List<Reward> rewards, double total) {}

    private final SmpPlugin plugin;
    private final Map<String, Crate> crates = new LinkedHashMap<>();
    private final File blocksFile;
    private final YamlConfiguration blocks;
    private final Map<UUID, Spin> spinning = new HashMap<>();
    private final Random random = new Random();

    private enum View { MAIN, PREVIEW, SPIN }

    private static final class Menu implements InventoryHolder {
        final View view;
        final String crate;
        final Map<Integer, String> slots = new HashMap<>();
        Inventory inv;

        Menu(View view, String crate) {
            this.view = view;
            this.crate = crate;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final class Spin {
        final Player p;
        final Crate crate;
        final Reward win;
        final Inventory inv;
        final ArrayDeque<Reward> reel = new ArrayDeque<>();
        int step;
        final int steps = 34;
        BukkitTask task;
        boolean done;

        Spin(Player p, Crate crate, Reward win, Inventory inv) {
            this.p = p;
            this.crate = crate;
            this.win = win;
            this.inv = inv;
            for (int i = 0; i < 9; i++) reel.add(roll(crate));
        }

        void next() {
            if (done) return;
            step++;
            reel.pollFirst();
            reel.addLast(steps - step == 4 ? win : roll(crate));
            int i = 9;
            for (Reward r : reel) inv.setItem(i++, rewardItem(r, crate, false));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.0f + step / (float) steps);
            if (step >= steps) {
                finish();
                return;
            }
            int delay = step < 14 ? 1 : step < 22 ? 2 : step < 28 ? 3 : step < 32 ? 5 : 8;
            task = Bukkit.getScheduler().runTaskLater(plugin, this::next, delay);
        }

        void finish() {
            if (done) return;
            done = true;
            if (task != null) task.cancel();
            spinning.remove(p.getUniqueId());
            plugin.rewards().give(p, win.give());
            if (p.isOnline()) {
                p.sendMessage(SmpPlugin.mm("<light_purple>✦ " + crate.name() + "<reset><gray> gave you <white>" + win.name() + "<gray>!"));
                p.playSound(p.getLocation(), win.announce() ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
                p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0.3);
                for (int s : new int[]{4, 22}) inv.setItem(s, named(Material.LIME_STAINED_GLASS_PANE, "<green><bold>" + win.name(), List.of()));
            }
            if (win.announce()) {
                Bukkit.broadcast(SmpPlugin.mm("<light_purple>✦ <white>" + p.getName() + "<gray> won <gold>" + win.name()
                        + "<gray> from a " + crate.name() + "<reset><gray>!"));
            }
        }
    }

    public CrateManager(SmpPlugin plugin) {
        this.plugin = plugin;
        this.blocksFile = new File(plugin.getDataFolder(), "crate-blocks.yml");
        this.blocks = YamlConfiguration.loadConfiguration(blocksFile);
        reload();
    }

    public void reload() {
        crates.clear();
        YamlConfiguration y = plugin.yaml("crates.yml");
        for (String id : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(id);
            if (s == null) continue;
            List<Reward> rewards = new ArrayList<>();
            double total = 0;
            for (Map<?, ?> m : s.getMapList("rewards")) {
                Material icon = Material.matchMaterial(String.valueOf(m.get("icon")));
                double chance = m.get("chance") instanceof Number n ? n.doubleValue() : 1;
                List<String> give = new ArrayList<>();
                if (m.get("give") instanceof List<?> l) for (Object o : l) give.add(String.valueOf(o));
                rewards.add(new Reward(String.valueOf(m.get("name")), icon == null ? Material.PAPER : icon, chance, give,
                        Boolean.TRUE.equals(m.get("announce"))));
                total += chance;
            }
            Material icon = Material.matchMaterial(s.getString("icon", "CHEST"));
            crates.put(id, new Crate(id, s.getString("name", id), icon == null ? Material.CHEST : icon, rewards, total));
        }
        plugin.getLogger().info("Crates: " + String.join(", ", crates.keySet()));
    }

    public String keyName(String crate) {
        Crate c = crates.get(crate);
        String n = c == null ? crate : c.name().replaceAll("<[^>]+>", "").replace(" Crate", "");
        return n + " Key";
    }

    public void giveKeys(UUID id, String crate, int n) {
        plugin.quests().addKeys(id, crate, n);
    }

    public int keys(Player p, String crate) {
        return plugin.quests().keys(p.getUniqueId(), crate);
    }

    // ------------------------------------------------------------------ shrine blocks

    public void registerBlock(Location l, String crate) {
        blocks.set(key(l), crate);
        save();
    }

    public void clearBlocks() {
        for (String k : blocks.getKeys(false)) blocks.set(k, null);
        save();
    }

    private void save() {
        try {
            net.moddedrealms.smp.SafeYaml.save(blocks, blocksFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save crate-blocks.yml: " + e.getMessage());
        }
    }

    private static String key(Location l) {
        return l.getWorld().getName() + "," + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlock(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || e.getHand() != EquipmentSlot.HAND) return;
        String crate = blocks.getString(key(b.getLocation()));
        if (crate == null || !crates.containsKey(crate)) return;
        e.setCancelled(true);
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) preview(e.getPlayer(), crate);
        else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) tryOpen(e.getPlayer(), crate);
    }

    // ------------------------------------------------------------------ menus

    public void openMain(Player p) {
        Menu m = new Menu(View.MAIN, null);
        Inventory inv = Bukkit.createInventory(m, 27, SmpPlugin.mm("<dark_purple>Crates"));
        m.inv = inv;
        ItemStack pane = named(Material.PURPLE_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);
        int[] slots = crates.size() <= 4 ? new int[]{10, 12, 14, 16} : new int[]{9, 10, 11, 12, 13, 14, 15, 16, 17};
        int i = 0;
        for (Crate c : crates.values()) {
            if (i >= slots.length) break;
            int k = keys(p, c.id());
            ItemStack it = named(c.icon(), c.name(), List.of("<gray>Your keys: <white>" + k, "",
                    k > 0 ? "<green>Left-click to open" : "<red>You need a " + keyName(c.id()),
                    "<yellow>Right-click to see the rewards"));
            if (k > 0) {
                ItemMeta meta = it.getItemMeta();
                meta.setEnchantmentGlintOverride(true);
                it.setItemMeta(meta);
            }
            m.slots.put(slots[i], c.id());
            inv.setItem(slots[i++], it);
        }
        inv.setItem(22, named(Material.BOOK, "<gold>How to get keys", List.of("<gray>Daily & weekly quests (/quests)",
                "<gray>Every hour you play", "<gray>Defeating MythicDepths bosses", "<gray>Quest streaks and the story")));
        p.openInventory(inv);
    }

    public void preview(Player p, String crateId) {
        Crate c = crates.get(crateId);
        if (c == null) return;
        Menu m = new Menu(View.PREVIEW, crateId);
        Inventory inv = Bukkit.createInventory(m, 54, SmpPlugin.mm("<dark_purple>Rewards: ").append(SmpPlugin.mm(c.name())));
        m.inv = inv;
        int i = 0;
        for (Reward r : c.rewards()) {
            if (i >= 45) break;
            inv.setItem(i++, rewardItem(r, c, true));
        }
        inv.setItem(49, named(Material.ARROW, "<yellow>Back", List.of()));
        p.openInventory(inv);
    }

    public void tryOpen(Player p, String crateId) {
        Crate c = crates.get(crateId);
        if (c == null || c.rewards().isEmpty()) return;
        if (spinning.containsKey(p.getUniqueId())) return;
        if (keys(p, crateId) <= 0) {
            p.sendMessage(SmpPlugin.mm("<red>You need a " + keyName(crateId) + ". <gray>Earn keys from quests, bosses and playtime."));
            p.playSound(p.getLocation(), Sound.BLOCK_CHEST_LOCKED, 0.7f, 1f);
            return;
        }
        giveKeys(p.getUniqueId(), crateId, -1);
        Menu m = new Menu(View.SPIN, crateId);
        Inventory inv = Bukkit.createInventory(m, 27, SmpPlugin.mm("<dark_purple>Opening ").append(SmpPlugin.mm(c.name())));
        m.inv = inv;
        ItemStack pane = named(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);
        inv.setItem(4, named(Material.HOPPER, "<yellow>▼", List.of()));
        inv.setItem(22, named(Material.HOPPER, "<yellow>▲", List.of()));
        p.openInventory(inv);
        Spin s = new Spin(p, c, roll(c), inv);
        spinning.put(p.getUniqueId(), s);
        s.next();
    }

    private Reward roll(Crate c) {
        double r = random.nextDouble() * c.total();
        for (Reward w : c.rewards()) {
            r -= w.chance();
            if (r <= 0) return w;
        }
        return c.rewards().get(c.rewards().size() - 1);
    }

    private ItemStack rewardItem(Reward r, Crate c, boolean odds) {
        List<String> lore = new ArrayList<>();
        if (odds) lore.add(String.format("<gray>Chance: <white>%.1f%%", 100 * r.chance() / c.total()));
        if (r.announce()) lore.add("<light_purple>✦ Rare find");
        return named(r.icon(), "<white>" + r.name(), lore);
    }

    private static ItemStack named(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m.isItem() ? m : Material.PAPER);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(SmpPlugin.mm(name).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(SmpPlugin.mm(s).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        switch (m.view) {
            case MAIN -> {
                String id = m.slots.get(slot);
                if (id == null) return;
                if (e.isRightClick()) preview(p, id);
                else tryOpen(p, id);
            }
            case PREVIEW -> {
                if (slot == 49) openMain(p);
            }
            case SPIN -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Spin s = spinning.get(e.getPlayer().getUniqueId());
        if (s != null) s.finish(); // closing or leaving never loses the prize
    }

    public void finishAll() {
        for (Spin s : new ArrayList<>(spinning.values())) s.finish();
    }

    public Map<String, Crate> all() {
        return crates;
    }
}
