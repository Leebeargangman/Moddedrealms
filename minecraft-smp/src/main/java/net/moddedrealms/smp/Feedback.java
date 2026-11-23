package net.moddedrealms.smp;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Statistic;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Asking players what they like: a short in-game poll (/poll) of a few multiple-choice questions from the config,
 * offered once a player has played a while, with a small in-game reward for answering; and /suggest for anything
 * else. Answers go to polls.yml and suggestions.yml; staff read the tallies with /mrsmp poll and /mrsmp suggestions.
 * Changing a question's id in the config starts it afresh.
 */
public final class Feedback implements Listener, CommandExecutor {
    private final SmpPlugin plugin;
    private final File pollFile, ideaFile;
    private final YamlConfiguration polls, ideas;
    private final Set<UUID> askedThisSession = new HashSet<>();
    private final Map<UUID, Long> lastIdea = new HashMap<>();
    private boolean dirty;

    record Question(String id, String text, List<String> options) {
    }

    Feedback(SmpPlugin plugin) {
        this.plugin = plugin;
        this.pollFile = new File(plugin.getDataFolder(), "polls.yml");
        this.ideaFile = new File(plugin.getDataFolder(), "suggestions.yml");
        this.polls = YamlConfiguration.loadConfiguration(pollFile);
        this.ideas = YamlConfiguration.loadConfiguration(ideaFile);
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::minute, 1200L, 1200L);
    }

    void save() {
        if (!dirty) return;
        dirty = false;
        try {
            polls.save(pollFile);
            ideas.save(ideaFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save the poll answers: " + e.getMessage());
        }
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("poll.enabled", true);
    }

    List<Question> questions() {
        List<Question> out = new ArrayList<>();
        List<Map<?, ?>> list = plugin.getConfig().getMapList("poll.questions");
        for (Map<?, ?> m : list) {
            Object id = m.get("id"), text = m.get("question"), opts = m.get("options");
            if (id == null || text == null || !(opts instanceof List<?> l) || l.isEmpty()) continue;
            List<String> options = new ArrayList<>();
            for (Object o : l) if (o != null && options.size() < 9) options.add(String.valueOf(o));
            out.add(new Question(String.valueOf(id).replace('.', '_'), String.valueOf(text), options));
        }
        if (out.isEmpty()) {
            out.add(new Question("what-next", "What should Moddedrealms add next?", List.of("More colony features", "Bosses and dungeons",
                    "PvP events", "Town jobs and money", "Building contests", "Quests and story")));
        }
        return out;
    }

    private Integer answer(Question q, UUID u) {
        String path = "answers." + q.id() + "." + u;
        return polls.contains(path) ? polls.getInt(path) : null;
    }

    private List<Question> unanswered(Player p) {
        List<Question> out = new ArrayList<>();
        for (Question q : questions()) if (answer(q, p.getUniqueId()) == null) out.add(q);
        return out;
    }

    // ------------------------------------------------------------------ asking

    /** Once a minute: offer the poll to players who've played long enough and haven't answered. */
    private void minute() {
        save();
        if (!enabled()) return;
        long after = plugin.getConfig().getLong("poll.ask-after-minutes", 15) * 60 * 20;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (askedThisSession.contains(p.getUniqueId()) || p.getStatistic(Statistic.PLAY_ONE_MINUTE) < after) continue;
            List<Question> left = unanswered(p);
            if (left.isEmpty()) continue;
            askedThisSession.add(p.getUniqueId());
            double reward = plugin.getConfig().getDouble("poll.reward", 100);
            p.sendMessage(SmpPlugin.mm("<gold>📜 Quick question!</gold> <gray>Help decide what Moddedrealms adds next: " + left.size() + " click"
                    + (left.size() == 1 ? "" : "s") + (reward > 0 && !rewarded(p) ? ", and <green>$" + Math.round(reward) + "</green> in-game for your time" : "")
                    + ". <click:run_command:'/poll'><hover:show_text:'<gray>Open the poll'><yellow><u>[Answer]</u></yellow></hover></click>"));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.4f);
        }
    }

    private boolean rewarded(Player p) {
        return polls.getBoolean("rewarded." + pollKey() + "." + p.getUniqueId());
    }

    /** Rewards are per set of questions, so a new poll can reward again. */
    private String pollKey() {
        StringBuilder b = new StringBuilder();
        for (Question q : questions()) b.append(q.id()).append('+');
        return Integer.toHexString(b.toString().hashCode());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        askedThisSession.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ the menu

    static final class Menu implements InventoryHolder {
        final Question q;
        Inventory inv;

        Menu(Question q) {
            this.q = q;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final Material[] COLOURS = {Material.LIME_WOOL, Material.LIGHT_BLUE_WOOL, Material.ORANGE_WOOL, Material.MAGENTA_WOOL,
            Material.YELLOW_WOOL, Material.CYAN_WOOL, Material.PINK_WOOL, Material.RED_WOOL, Material.PURPLE_WOOL};

    void open(Player p) {
        if (!enabled()) {
            p.sendMessage(SmpPlugin.mm("<gray>There's no poll running right now. Ideas are always welcome: <white>/suggest <your idea>"));
            return;
        }
        List<Question> left = unanswered(p);
        if (left.isEmpty()) {
            p.sendMessage(SmpPlugin.mm("<green>You've answered everything. Thank you!</green> <gray>Got another idea? <white>/suggest <your idea>"));
            return;
        }
        Question q = left.get(0);
        int total = questions().size(), n = total - left.size() + 1;
        Menu m = new Menu(q);
        m.inv = Bukkit.createInventory(m, 27, SmpPlugin.mm("Poll " + n + "/" + total));
        ItemStack glass = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) m.inv.setItem(i, glass);
        m.inv.setItem(4, item(Material.WRITABLE_BOOK, "<gold>" + q.text(), List.of("<gray>Pick the one you'd like most.", "<dark_gray>Question " + n + " of " + total)));
        int[] slots = slotsFor(q.options().size());
        for (int i = 0; i < q.options().size(); i++) {
            m.inv.setItem(slots[i], item(COLOURS[i % COLOURS.length], "<white>" + q.options().get(i), List.of("<yellow>Click to choose")));
        }
        m.inv.setItem(22, item(Material.OAK_SIGN, "<gray>Something else?", List.of("<gray>Close this and type", "<white>/suggest <your idea>")));
        p.openInventory(m.inv);
    }

    private static int[] slotsFor(int n) {
        return switch (n) {
            case 1 -> new int[]{13};
            case 2 -> new int[]{11, 15};
            case 3 -> new int[]{11, 13, 15};
            case 4 -> new int[]{10, 12, 14, 16};
            case 5 -> new int[]{9, 11, 13, 15, 17};
            case 6 -> new int[]{10, 11, 12, 14, 15, 16};
            case 7 -> new int[]{10, 11, 12, 13, 14, 15, 16};
            case 8 -> new int[]{9, 10, 11, 12, 14, 15, 16, 17};
            default -> new int[]{9, 10, 11, 12, 13, 14, 15, 16, 17};
        };
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
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (e.getRawSlot() == 22) {
            p.closeInventory();
            p.sendMessage(SmpPlugin.mm("<gray>Type <white>/suggest <your idea></white>: staff read every one."));
            return;
        }
        int[] slots = slotsFor(m.q.options().size());
        for (int i = 0; i < slots.length && i < m.q.options().size(); i++) {
            if (slots[i] != e.getRawSlot()) continue;
            if (answer(m.q, p.getUniqueId()) != null) return;
            polls.set("answers." + m.q.id() + "." + p.getUniqueId(), i);
            dirty = true;
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.3f);
            if (unanswered(p).isEmpty()) {
                p.closeInventory();
                finish(p);
            } else Bukkit.getScheduler().runTask(plugin, () -> open(p));
            return;
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    private void finish(Player p) {
        double reward = plugin.getConfig().getDouble("poll.reward", 100);
        String key = "rewarded." + pollKey() + "." + p.getUniqueId();
        if (reward > 0 && !polls.getBoolean(key) && plugin.town() != null && plugin.town().money.ready()) {
            polls.set(key, true);
            dirty = true;
            plugin.town().money.give(p, reward);
            p.sendMessage(SmpPlugin.mm("<green>Thanks! Your answers are in, and <white>$" + Math.round(reward) + "</white> is in your purse.</green> <gray>Anything else? <white>/suggest <idea>"));
        } else {
            p.sendMessage(SmpPlugin.mm("<green>Thanks! Your answers are in.</green> <gray>Anything else? <white>/suggest <idea>"));
        }
        save();
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only. Results: /mrsmp poll");
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("poll")) {
            open(p);
            return true;
        }
        // /suggest
        String text = String.join(" ", args).trim();
        if (text.length() < 4) {
            p.sendMessage(SmpPlugin.mm("<gray>Tell us what you'd like: <white>/suggest more dungeons near Meadowbrook</white>. Staff read every one."));
            return true;
        }
        long now = System.currentTimeMillis();
        Long last = lastIdea.get(p.getUniqueId());
        if (last != null && now - last < 60_000L) {
            p.sendMessage(SmpPlugin.mm("<gray>Thanks, got it! Give it a minute before the next one."));
            return true;
        }
        lastIdea.put(p.getUniqueId(), now);
        if (text.length() > 240) text = text.substring(0, 240);
        List<String> list = new ArrayList<>(ideas.getStringList("suggestions"));
        list.add(now + "\u0001" + p.getName() + "\u0001" + text);
        while (list.size() > 500) list.remove(0);
        ideas.set("suggestions", list);
        dirty = true;
        save();
        p.sendMessage(SmpPlugin.mm("<green>Thank you! Your idea is saved for the staff to read."));
        for (Player s : Bukkit.getOnlinePlayers()) {
            if (s != p && s.hasPermission("mrsmp.admin")) s.sendMessage(SmpPlugin.mm("<gold>[Idea] <gray>" + p.getName() + ": <white>").append(Component.text(text)));
        }
        plugin.getLogger().info("Suggestion from " + p.getName() + ": " + text);
        return true;
    }

    /** /mrsmp poll: the tallies. */
    List<String> results() {
        List<String> out = new ArrayList<>();
        for (Question q : questions()) {
            ConfigurationSection s = polls.getConfigurationSection("answers." + q.id());
            int[] n = new int[q.options().size()];
            int total = 0;
            if (s != null) {
                for (String u : s.getKeys(false)) {
                    int i = s.getInt(u);
                    if (i >= 0 && i < n.length) {
                        n[i]++;
                        total++;
                    }
                }
            }
            out.add("<gold>" + q.text() + " <gray>(" + total + " answer" + (total == 1 ? "" : "s") + ")");
            for (int i = 0; i < n.length; i++) {
                int pct = total == 0 ? 0 : Math.round(n[i] * 100f / total);
                out.add("  <white>" + n[i] + " <gray>" + "█".repeat(pct / 10) + " " + pct + "% <white>" + q.options().get(i));
            }
        }
        return out;
    }

    /** /mrsmp suggestions: the newest ideas. */
    List<String> suggestions(int max) {
        List<String> all = ideas.getStringList("suggestions");
        List<String> out = new ArrayList<>();
        DateTimeFormatter f = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneOffset.UTC);
        for (int i = all.size() - 1; i >= 0 && out.size() < max; i--) {
            String[] x = all.get(i).split("\u0001", 3);
            if (x.length < 3) continue;
            String when;
            try {
                when = f.format(Instant.ofEpochMilli(Long.parseLong(x[0])));
            } catch (NumberFormatException e) {
                when = "?";
            }
            out.add("<dark_gray>" + when + " <gray>" + x[1] + ": <white>" + SmpPlugin.MM.escapeTags(x[2]));
        }
        if (out.isEmpty()) out.add("<gray>No suggestions yet.");
        return out;
    }
}
