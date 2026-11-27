package net.moddedrealms.smp;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * /start: "what is this server and what can I do?" in one menu, for new players (it opens by itself on a first join)
 * and anyone who's lost. Each button starts the thing or says exactly how.
 */
public final class Guide implements Listener, CommandExecutor {
    private final SmpPlugin plugin;

    Guide(SmpPlugin plugin) {
        this.plugin = plugin;
    }

    static final class Menu implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final int COLONY = 10, QUESTS = 12, WILD = 14, MARKET = 16, JOBS = 28, BOSSES = 30, WATCH = 32, IDEAS = 34, HOME = 22,
            DAILY = 46, AUCTION = 48, DUEL = 50, TOP = 52;

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (sender instanceof Player p) open(p);
        return true;
    }

    public void open(Player p) {
        Menu m = new Menu();
        m.inv = Bukkit.createInventory(m, 54, SmpPlugin.mm("What to do on Moddedrealms"));
        ItemStack glass = item(Material.BROWN_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 54; i++) m.inv.setItem(i, glass);
        var t = plugin.town();
        String colony = t == null ? null : t.colonyName(p);
        List<String> colonyLore = new ArrayList<>();
        if (colony != null) {
            colonyLore.add("<gray>You live in <white>" + colony + "</white>.");
            colonyLore.add("<gray>Right-click its Town Hall core, or");
            colonyLore.add("<yellow>click</yellow> <gray>for its menu.");
        } else {
            colonyLore.add("<gray>Found a settlement in the wilds with");
            colonyLore.add("<gray>friends: villagers move in, farm, build,");
            colonyLore.add("<gray>trade and defend it, even while you're off.");
            colonyLore.add("");
            List<String[]> rec = t == null ? List.of() : t.recruitingColonies();
            if (!rec.isEmpty()) {
                colonyLore.add("<gray>Or join one (ask its owner for an invite):");
                for (int i = 0; i < Math.min(3, rec.size()); i++) colonyLore.add("<white> " + rec.get(i)[0] + " <dark_gray>(" + rec.get(i)[1] + " people, " + rec.get(i)[2] + ")");
                colonyLore.add("");
            }
            colonyLore.add(net.moddedrealms.smp.onboard.ColonyBridge.starterCamps() ? "<yellow>Click</yellow> <gray>for a ready camp with 3 settlers." : "<yellow>Click</yellow> <gray>to start one.");
        }
        boolean colonies = t != null && t.coloniesLinked();
        if (colonies) m.inv.setItem(COLONY, item(Material.LODESTONE, "<aqua><bold>Start a colony", colonyLore));
        m.inv.setItem(QUESTS, item(Material.WRITABLE_BOOK, "<gold><bold>Quests and story", List.of("<gray>Daily quests with money and crate keys,", "<gray>and Elder Rowan's story by the fountain.", "", "<yellow>Click</yellow> <gray>to open your quests.")));
        m.inv.setItem(WILD, item(Material.COMPASS, "<green><bold>Head into the wild", List.of("<gray>A random spot far from town to", "<gray>settle, mine and explore.", "<gray>Supply drops fall out there: <white>/drop", "", "<yellow>Click</yellow> <gray>to go (/warp).")));
        m.inv.setItem(MARKET, item(Material.EMERALD, "<yellow><bold>The market hall", List.of("<gray>Sell what you gather and buy what you need.", "<gray>Prices follow supply. It's east of the", "<gray>spawn plaza, through the east gate.", "", "<yellow>Click</yellow> <gray>to see today's prices.")));
        m.inv.setItem(JOBS, item(Material.IRON_PICKAXE, "<white><bold>Town jobs", List.of("<gray>Work for the town's businesses for pay,", "<gray>or open a business of your own on a plot.", "", "<yellow>Click</yellow> <gray>to see the jobs.")));
        m.inv.setItem(BOSSES, item(Material.WITHER_SKELETON_SKULL, "<red><bold>Bosses", List.of("<gray>Mythic bosses with their own loot.", "", "<yellow>Click</yellow> <gray>to see them.")));
        m.inv.setItem(WATCH, item(Material.SHIELD, "<blue><bold>The Town Watch", List.of("<gray>Join the Watch and arrest outlaws,", "<gray>or become one. Your record: /rep", "", "<yellow>Click</yellow> <gray>to find out more.")));
        m.inv.setItem(IDEAS, item(Material.OAK_SIGN, "<light_purple><bold>Tell us what you want", List.of("<gray>Answer a 1-minute poll or send an", "<gray>idea: the server is built around them.", "", "<yellow>Click</yellow> <gray>for the poll.")));
        m.inv.setItem(DAILY, item(Material.CHEST, "<gold><bold>Daily reward", List.of("<gray>Log in every day for money, gear", "<gray>and crate keys. Day 7 is the big one.", "", "<yellow>Click</yellow> <gray>to claim (/daily).")));
        m.inv.setItem(AUCTION, item(Material.GOLD_INGOT, "<yellow><bold>Auction house", List.of("<gray>Buy and sell with other players,", "<gray>even while they're offline.", "", "<yellow>Click</yellow> <gray>to browse (/ah).")));
        m.inv.setItem(DUEL, item(Material.IRON_SWORD, "<red><bold>Duels", List.of("<gray>1v1 anyone nearby: <white>/duel <name> [wager]", "<gray>Nobody dies, nothing drops.", "", "<yellow>Click</yellow> <gray>for how it works.")));
        m.inv.setItem(TOP, item(Material.GOLD_BLOCK, "<gold><bold>Leaderboards", List.of("<gray>Richest, most played, duel wins,", "<gray>best streaks, biggest colonies.", "", "<yellow>Click</yellow> <gray>to see them (/top).")));
        m.inv.setItem(HOME, item(Material.RED_BED, "<white>Handy commands", List.of("<white>/spawn <gray>back to the plaza", "<white>/sethome <gray>· <white>/home", "<white>/tpa <name> <gray>visit a friend",
                "<white>/quests <gray>· <white>/market <gray>· <white>/jobs", "<white>/daily <gray>· <white>/ah <gray>· <white>/duel <gray>· <white>/top", "<white>/drop <gray>where the supply drop is", "<white>/bank <gray>· <white>/purse <gray>· <white>/stocks <gray>money and shares",
                "<white>/report <gray>tell the staff about a problem",
                "<white>/start <gray>this menu")));
        p.openInventory(m.inv);
    }

    private static ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(SmpPlugin.mm("<!italic>" + name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(SmpPlugin.mm("<!italic>" + s));
        meta.lore(l);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        String run = null;
        switch (e.getRawSlot()) {
            case COLONY -> {
                if (plugin.town() == null || !plugin.town().coloniesLinked()) return;
                if (plugin.town().colonyName(p) != null) run = "colony menu";
                else if (net.moddedrealms.smp.onboard.ColonyBridge.starterCamps()) {
                    p.closeInventory();
                    net.moddedrealms.smp.onboard.ColonyBridge.funnel(p.getUniqueId(), "chose_colony");
                    String err = net.moddedrealms.smp.onboard.ColonyBridge.startColony(p, done -> {
                        if (done != null && !done.equals("offline")) p.sendMessage(SmpPlugin.mm("<red>" + done));
                    });
                    if (err != null) p.sendMessage(SmpPlugin.mm("<red>" + err));
                    return;
                } else {
                    p.closeInventory();
                    p.sendMessage(SmpPlugin.mm("<aqua><bold>Founding a colony</bold></aqua>\n"
                            + "<gray>1. Walk out of town into the wilds (colonies can't be in the Old Town or Meadowbrook). <white>/warp</white> takes you far out.\n"
                            + "<gray>2. <click:suggest_command:'/colony create '><hover:show_text:'<gray>Click, then type a name'><white><u>/colony create <name></u></white></hover></click> gives you a Town Hall Core: place it.\n"
                            + "<gray>3. Four settlers arrive with bread. For your first 4 days nobody can die, so give them beds (a registered house) and a farm, "
                            + "and put a hoe in the storage chest.\n"
                            + "<gray>4. Invite friends: <white>/colony invite <name></white>. Sell spare goods at the market hall for money: <white>/market colony</white>."));
                    return;
                }
            }
            case QUESTS -> run = "quests";
            case WILD -> run = "warp";
            case MARKET -> run = "market";
            case JOBS -> run = "jobs";
            case BOSSES -> run = "boss";
            case WATCH -> run = "police";
            case IDEAS -> run = "poll";
            case DAILY -> run = "daily";
            case AUCTION -> run = "ah";
            case DUEL -> run = "duel help";
            case TOP -> run = "leaderboard money";
            default -> {
                return;
            }
        }
        p.closeInventory();
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        String cmd = run;
        Bukkit.getScheduler().runTask(plugin, () -> p.performCommand(cmd));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}
