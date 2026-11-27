package net.moddedrealms.smp.season;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The advent calendar: /advent opens a Christmas tree of 24 doors. Door n can be opened only on n December (UK
 * time); a door that wasn't opened on its day stays shut for good. Each player opens each door once: the claim is
 * written to disk before the reward is handed over, so a crash can never pay a door twice.
 */
public final class Advent implements Listener, TabExecutor {
    /** Where the doors hang: a tree of 3, 5, 7 and 9, under a star. */
    private static final int[] SLOTS = {12, 13, 14, 20, 21, 22, 23, 24, 28, 29, 30, 31, 32, 33, 34, 36, 37, 38, 39, 40, 41, 42, 43, 44};
    /** Which day hangs in each slot: scattered like a real calendar, the same for everyone. */
    private static final int[] DAY_AT = {7, 18, 3, 12, 1, 22, 9, 15, 20, 5, 24, 11, 2, 16, 8, 13, 23, 4, 19, 10, 6, 21, 14, 17};
    private static final int STAR = 4, TRUNK = 49, INFO = 45, CLOSE = 53;

    private final SeasonManager s;
    private LocalDate lastDay;

    Advent(SeasonManager s) {
        this.s = s;
        lastDay = s.today();
    }

    private SmpPlugin plugin() {
        return s.plugin();
    }

    private boolean enabled() {
        return s.on() && s.cfg().getBoolean("advent.enabled", true);
    }

    private int year() {
        return s.cfg().getInt("advent.year", 2026);
    }

    /** The door that can be opened today, or 0. */
    public int today() {
        LocalDate t = s.today();
        return enabled() && t.getYear() == year() && t.getMonthValue() == 12 && t.getDayOfMonth() <= 24 ? t.getDayOfMonth() : 0;
    }

    private enum State { FUTURE, OPEN, CLAIMED, MISSED }

    private State state(UUID id, int day) {
        if (claimed(id).contains(day)) return State.CLAIMED;
        LocalDate d = LocalDate.of(year(), 12, day), t = s.today();
        if (t.isBefore(d)) return State.FUTURE;
        if (t.isEqual(d) && enabled()) return State.OPEN;
        return State.MISSED;
    }

    private Set<Integer> claimed(UUID id) {
        return new HashSet<>(s.data().getIntegerList("advent.claims." + id));
    }

    public boolean claimedToday(Player p) {
        int d = today();
        return d > 0 && claimed(p.getUniqueId()).contains(d);
    }

    // ------------------------------------------------------------------ the menu

    private static final class Holder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    public void open(Player p) {
        Holder h = new Holder();
        Inventory inv = Bukkit.createInventory(h, 54, SmpPlugin.mm("<dark_green>❄ <bold>Advent Calendar</bold> ❄"));
        h.inv = inv;
        ItemStack sky = icon(Material.BLUE_STAINED_GLASS_PANE, "<gray> ", List.of(), false);
        for (int i = 0; i < 54; i++) inv.setItem(i, sky);
        for (int i : new int[]{0, 8, 10, 16, 19, 25, 46, 52}) inv.setItem(i, icon(Material.WHITE_STAINED_GLASS_PANE, "<white>❄", List.of(), false));
        inv.setItem(STAR, icon(Material.NETHER_STAR, "<yellow><bold>The Frozen Crown", List.of("<gray>One door a day, 1 to 24 December.", "<gray>A door you miss stays shut."), true));
        inv.setItem(TRUNK, icon(Material.SPRUCE_LOG, "<gold>Season 1 launches 18 December", List.of(), false));
        inv.setItem(CLOSE, icon(Material.BARRIER, "<red>Close", List.of(), false));
        int opened = claimed(p.getUniqueId()).size();
        inv.setItem(INFO, icon(Material.BOOK, "<aqua>Your calendar", List.of("<gray>Doors opened: <white>" + opened + "/24",
                "<gray>Rewards are yours to keep into Season 1.", "<gray>Titles are worn with <white>/titles</white>."), false));
        UUID id = p.getUniqueId();
        for (int i = 0; i < SLOTS.length; i++) inv.setItem(SLOTS[i], door(id, DAY_AT[i]));
        p.openInventory(inv);
    }

    private ItemStack door(UUID id, int day) {
        ConfigurationSection d = s.cfg().getConfigurationSection("advent.doors." + day);
        String name = d == null ? "<white>Door " + day : d.getString("name", "<white>Door " + day);
        List<String> rewards = d == null ? List.of() : d.getStringList("rewards");
        String date = day + " December";
        ItemStack it;
        switch (state(id, day)) {
            case OPEN -> it = icon(Material.CHEST, "<gold><bold>Door " + day + "</bold> <yellow>· open today!",
                    List.of("<gray>Click to open it.", "<dark_gray>Only today: tomorrow it's gone."), true);
            case CLAIMED -> {
                Material m = d == null ? Material.PAPER : Material.matchMaterial(d.getString("icon", "PAPER"));
                List<String> lore = new ArrayList<>();
                lore.add("<green>Opened ✔");
                lore.add("<gray>" + describe(rewards));
                it = icon(m == null || !m.isItem() ? Material.PAPER : m, name + " <dark_gray>(" + date + ")", lore, false);
            }
            case MISSED -> it = icon(Material.GRAY_STAINED_GLASS, "<dark_gray>Door " + day + " · missed", List.of("<dark_gray>It was open on " + date + "."), false);
            default -> {
                long days = java.time.temporal.ChronoUnit.DAYS.between(s.today(), LocalDate.of(year(), 12, day));
                it = icon(Material.GREEN_STAINED_GLASS, "<green>Door " + day, List.of("<gray>Opens on " + date + (days > 0 ? " (in " + days + " day" + (days == 1 ? "" : "s") + ")" : "") + "."), false);
            }
        }
        it.setAmount(Math.max(1, Math.min(it.getMaxStackSize(), day)));
        return it;
    }

    private String describe(List<String> rewards) {
        List<String> out = new ArrayList<>();
        String base = plugin().rewards().describe(rewards);
        if (!base.isEmpty()) out.add(base);
        for (String r : rewards) {
            if (r.toLowerCase(Locale.ROOT).startsWith("cosmetic:")) out.add("title " + s.cosmetics().plainName(r.substring(9)));
            else if (r.toLowerCase(Locale.ROOT).startsWith("cmd:colonyadmin give") && r.contains("christmas_sapling")) out.add("a Christmas tree for your colony");
        }
        return out.isEmpty() ? "a surprise" : String.join(", ", out);
    }

    static ItemStack icon(Material m, String name, List<String> lore, boolean glow) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(SmpPlugin.mm("<!italic>" + name));
        List<net.kyori.adventure.text.Component> l = new ArrayList<>();
        for (String s : lore) l.add(SmpPlugin.mm("<!italic>" + s));
        meta.lore(l);
        if (glow) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        if (slot == CLOSE) {
            p.closeInventory();
            return;
        }
        for (int i = 0; i < SLOTS.length; i++) {
            if (SLOTS[i] != slot) continue;
            int day = DAY_AT[i];
            switch (state(p.getUniqueId(), day)) {
                case OPEN -> claim(p, day);
                case FUTURE -> p.sendActionBar(SmpPlugin.mm("<gray>Door " + day + " opens on " + day + " December."));
                case MISSED -> p.sendActionBar(SmpPlugin.mm("<gray>That door was open on " + day + " December."));
                case CLAIMED -> p.sendActionBar(SmpPlugin.mm("<gray>You've opened that one."));
            }
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    private void claim(Player p, int day) {
        UUID id = p.getUniqueId();
        Set<Integer> got = claimed(id);
        if (!got.add(day)) return;
        List<Integer> list = new ArrayList<>(got);
        list.sort(null);
        s.data().set("advent.claims." + id, list);
        s.data().set("advent.names." + id, p.getName());
        s.saveData(); // before the reward: a crash in between can't give the door twice
        ConfigurationSection d = s.cfg().getConfigurationSection("advent.doors." + day);
        List<String> rewards = d == null ? List.of() : d.getStringList("rewards");
        plugin().rewards().give(p, rewards);
        plugin().getLogger().info(p.getName() + " opened advent door " + day + ": " + String.join(", ", rewards));
        String name = d == null ? "Door " + day : d.getString("name", "Door " + day);
        p.sendMessage(SmpPlugin.mm("<dark_green>❄</dark_green> <gold>Door " + day + ":</gold> " + name + "<gray> · " + describe(rewards)));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        p.getWorld().spawnParticle(Particle.SNOWFLAKE, p.getLocation().add(0, 1.2, 0), 40, 0.5, 0.6, 0.5, 0.02);
        Bukkit.getScheduler().runTask(plugin(), () -> {
            if (p.isOnline() && p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) open(p);
        });
    }

    // ------------------------------------------------------------------ reminders

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!s.cfg().getBoolean("advent.remind-on-join", true)) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin(), () -> {
            int d = today();
            if (!p.isOnline() || d == 0 || claimedToday(p)) return;
            p.sendMessage(SmpPlugin.mm("<dark_green>❄</dark_green> <gold>Advent door " + d + " is open today.</gold> "
                    + "<click:run_command:'/advent'><hover:show_text:'<gray>Open the calendar'><yellow><u>[Open it]</u></yellow></hover></click>"));
        }, 100L);
    }

    /** Every second: a new door at midnight is announced once. */
    void second() {
        LocalDate t = s.today();
        if (t.equals(lastDay)) return;
        lastDay = t;
        int d = today();
        if (d == 0 || !s.cfg().getBoolean("advent.announce-new-door", true)) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(SmpPlugin.mm("<dark_green>❄</dark_green> <gold>Door " + d + " of the advent calendar is open!</gold> "
                    + "<click:run_command:'/advent'><yellow><u>[Open it]</u></yellow></click>"));
        }
    }

    // ------------------------------------------------------------------ /advent

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (a.length > 0 && sender.hasPermission("mrsmp.admin")) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "date" -> {
                    if (a.length < 2) {
                        sender.sendMessage(SmpPlugin.mm("<gray>Today for the calendar: <white>" + s.today() + (s.testDate() != null ? " (test date)" : "")
                                + "</white>. <white>/advent date <yyyy-mm-dd|off></white>"));
                        return true;
                    }
                    try {
                        s.testDate(a[1].equalsIgnoreCase("off") ? null : LocalDate.parse(a[1]));
                    } catch (RuntimeException ex) {
                        sender.sendMessage(SmpPlugin.mm("<red>Use a date like 2026-12-05, or off."));
                        return true;
                    }
                    sender.sendMessage(SmpPlugin.mm("<green>The calendar now thinks today is " + s.today().format(DateTimeFormatter.ISO_LOCAL_DATE) + "."));
                    return true;
                }
                case "reset" -> {
                    Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                    if (t == null) {
                        sender.sendMessage(SmpPlugin.mm("<gray>/advent reset <online player> <dark_gray>(testing: forgets which doors they opened)"));
                        return true;
                    }
                    s.data().set("advent.claims." + t.getUniqueId(), null);
                    s.saveData();
                    sender.sendMessage(SmpPlugin.mm("<green>Forgot " + t.getName() + "'s advent doors."));
                    return true;
                }
                case "stats" -> {
                    ConfigurationSection c = s.data().getConfigurationSection("advent.claims");
                    int[] per = new int[25];
                    int players = 0;
                    if (c != null) for (String k : c.getKeys(false)) {
                        players++;
                        for (int d : c.getIntegerList(k)) if (d >= 1 && d <= 24) per[d]++;
                    }
                    StringBuilder b = new StringBuilder("<gold>Advent: <white>" + players + "</white> players opened a door.<gray>");
                    for (int d = 1; d <= 24; d++) if (per[d] > 0) b.append(" ").append(d).append(":").append(per[d]);
                    sender.sendMessage(SmpPlugin.mm(b.toString()));
                    return true;
                }
                default -> { }
            }
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only. Staff: /advent date|reset|stats");
            return true;
        }
        if (!enabled()) {
            p.sendMessage(SmpPlugin.mm(s.on() ? "<gray>The advent calendar is closed." : "<gray>The advent calendar isn't open yet. Check back in December!"));
            return true;
        }
        open(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] a) {
        if (!sender.hasPermission("mrsmp.admin")) return List.of();
        if (a.length == 1) return List.of("date", "reset", "stats").stream().filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
        return null;
    }
}
