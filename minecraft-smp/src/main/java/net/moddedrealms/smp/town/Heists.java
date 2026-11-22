package net.moddedrealms.smp.town;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Crew heists, planned with the Planner in the casino's back room. One job runs at a time.
 * <ul>
 * <li><b>Bank Deep Vault</b>: cut the alarm behind the bank (or don't: then the police come at once), drill the vault
 * while holding off the police, then carry the heavy loot bags to a getaway spot 560-720 blocks out.</li>
 * <li><b>Armored Wagon</b>: a treasury courier with an escort walks from the bank to the police station. Stop it before
 * it arrives, take the strongbox, and get the bags to the getaway spot.</li>
 * </ul>
 * Everyone who takes part becomes wanted at 5 stars (so the Bureau opens a case). Loot bags slow you down; get
 * arrested and they're evidence. Leftover bags are stolen goods the Fence buys for half.
 */
public final class Heists implements Listener {
    enum Kind { BANK, WAGON }
    enum Stage { PLANNING, ALARM, DRILL, WAGON, GETAWAY }

    private static final class Job {
        Kind kind;
        UUID leader;
        final Set<UUID> crew = new LinkedHashSet<>();
        final Set<UUID> invited = new LinkedHashSet<>();
        Stage stage = Stage.PLANNING;
        long started, stageSince, policeAt;
        boolean alarmCut, policeCalled;
        double progress;
        Location getaway, panel;
        Walker courier;
        final BossBar bar = BossBar.bossBar(Component.text("Heist"), 0f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
    }

    private final TownManager t;
    private final File file;
    private final NamespacedKey bagKey;
    public final NamespacedKey cutterKey;
    private final Random r = new Random();
    private Job job;
    private long bankReady, wagonReady;

    Heists(TownManager t) {
        this.t = t;
        this.file = new File(t.plugin().getDataFolder(), "heists.yml");
        this.bagKey = new NamespacedKey(t.plugin(), "lootbag");
        this.cutterKey = new NamespacedKey(t.plugin(), "boltcutters");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        bankReady = y.getLong("bank-ready");
        wagonReady = y.getLong("wagon-ready");
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("bank-ready", bankReady);
        y.set("wagon-ready", wagonReady);
        try {
            y.save(file);
        } catch (IOException e) {
            t.plugin().getLogger().warning("Could not save heists.yml: " + e.getMessage());
        }
    }

    private static String left(long until) {
        long ms = Math.max(0, until - System.currentTimeMillis());
        return ms / 3_600_000 + "h " + ms / 60_000 % 60 + "m";
    }

    private boolean nearPlanner(Player p) {
        if (p.hasPermission("mrsmp.admin")) return true;
        Entity planner = t.plugin().npcs().entity("planner");
        return planner != null && planner.getWorld() == p.getWorld() && planner.getLocation().distance(p.getLocation()) < 8;
    }

    private void crewSay(String msg) {
        if (job == null) return;
        for (UUID u : job.crew) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(SmpPlugin.mm("<dark_purple>[Heist] " + msg));
        }
    }

    public boolean onCrew(Player p) {
        return job != null && job.crew.contains(p.getUniqueId());
    }

    // ------------------------------------------------------------------ items

    public ItemStack boltCutters() {
        ItemStack it = new ItemStack(Material.SHEARS);
        ItemMeta m = it.getItemMeta();
        m.displayName(SmpPlugin.mm("<gray>Bolt Cutters").decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(SmpPlugin.mm("<dark_gray>Cuts through jail cell bars.").decoration(TextDecoration.ITALIC, false),
                SmpPlugin.mm("<dark_gray>Use on a cell's window from outside the station.").decoration(TextDecoration.ITALIC, false)));
        m.getPersistentDataContainer().set(cutterKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    public boolean isCutters(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(cutterKey, PersistentDataType.BYTE);
    }

    private ItemStack lootBag(int value) {
        ItemStack it = new ItemStack(Material.BUNDLE);
        ItemMeta m = it.getItemMeta();
        m.displayName(SmpPlugin.mm("<gold>Loot Bag").decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(SmpPlugin.mm("<gray>Heavy. Worth " + Money.fmt(value) + " at the getaway spot.").decoration(TextDecoration.ITALIC, false),
                SmpPlugin.mm("<dark_gray>Stolen goods: the Fence pays half, the police take it.").decoration(TextDecoration.ITALIC, false)));
        m.getPersistentDataContainer().set(bagKey, PersistentDataType.INTEGER, value);
        m.getPersistentDataContainer().set(t.stolenKey, PersistentDataType.INTEGER, value / 2);
        m.setMaxStackSize(16);
        it.setItemMeta(m);
        return it;
    }

    private int bagValue(Player p, boolean take) {
        int total = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || !it.hasItemMeta()) continue;
            Integer v = it.getItemMeta().getPersistentDataContainer().get(bagKey, PersistentDataType.INTEGER);
            if (v == null) continue;
            total += v * it.getAmount();
            if (take) inv.setItem(i, null);
        }
        return total;
    }

    // ------------------------------------------------------------------ the planner

    static final class Board implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static ItemStack icon(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(SmpPlugin.mm(name).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(SmpPlugin.mm(s).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    public void open(Player p) {
        if (!nearPlanner(p)) {
            p.sendMessage(SmpPlugin.mm("<gray>Heists are planned with the Planner, in the back room of the underground casino."));
            return;
        }
        long now = System.currentTimeMillis();
        Board b = new Board();
        b.inv = Bukkit.createInventory(b, 27, SmpPlugin.mm("<dark_red>The Planner"));
        boolean bankOk = t.bank().ready();
        b.inv.setItem(11, icon(Material.GOLD_BLOCK, "<gold><bold>Bank: the Deep Vault", "<gray>1. Cut the alarm behind the bank (or don't)",
                "<gray>2. Drill the vault while the police come", "<gray>3. Carry the loot bags to the getaway spot", "",
                "<gray>Crew 2-4 · about " + Money.fmt(cfg().getDouble("town.heists.bank-bags", 3) * cfg().getDouble("town.heists.bank-bag-value", 2500)) + " each",
                !bankOk ? "<red>There's no bank to hit" : now < bankReady ? "<red>Too hot right now: " + left(bankReady) : "<green>Click to plan (" + Money.fmt(cfg().getDouble("town.heists.plan-cost", 1000)) + " for equipment)"));
        boolean wagonOk = bankOk && t.jail().releasePoint() != null;
        b.inv.setItem(13, icon(Material.CHEST_MINECART, "<gold><bold>The Armored Wagon", "<gray>A treasury courier walks from the bank",
                "<gray>to the police station with an escort.", "<gray>Stop it, take the strongbox, get away.", "",
                "<gray>Crew 2-4 · about " + Money.fmt(cfg().getDouble("town.heists.wagon-bags", 2) * cfg().getDouble("town.heists.wagon-bag-value", 2000)) + " each",
                !wagonOk ? "<red>Needs the bank and the police station" : now < wagonReady ? "<red>Next wagon in " + left(wagonReady) : "<green>Click to plan (" + Money.fmt(cfg().getDouble("town.heists.plan-cost", 1000)) + " for equipment)"));
        b.inv.setItem(15, icon(Material.SHEARS, "<gray><bold>Bolt Cutters", "<gray>For breaking a friend out of jail:", "<gray>use them on a cell's window from outside.",
                "", "<gold>" + Money.fmt(cfg().getDouble("town.prison-break.cutter-price", 1500))));
        if (job != null) {
            List<String> names = new ArrayList<>();
            for (UUID u : job.crew) names.add(Bukkit.getOfflinePlayer(u).getName());
            b.inv.setItem(22, icon(Material.MAP, "<light_purple>Current job: " + (job.kind == Kind.BANK ? "the Deep Vault" : "the Armored Wagon"),
                    "<gray>Stage: " + job.stage.name().toLowerCase(), "<gray>Crew: " + String.join(", ", names)));
        }
        p.openInventory(b.inv);
    }

    @EventHandler
    public void onBoard(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Board)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        switch (e.getRawSlot()) {
            case 11 -> plan(p, Kind.BANK);
            case 13 -> plan(p, Kind.WAGON);
            case 15 -> {
                if (!t.money.pay(p, cfg().getDouble("town.prison-break.cutter-price", 1500))) {
                    p.sendMessage(SmpPlugin.mm("<red>You can't afford them."));
                    return;
                }
                for (ItemStack left : p.getInventory().addItem(boltCutters()).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
                p.sendMessage(SmpPlugin.mm("<gray>\"Don't tell anyone where you got those.\""));
            }
            default -> { }
        }
    }

    @EventHandler
    public void onBoardDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Board) e.setCancelled(true);
    }

    private void plan(Player p, Kind kind) {
        long now = System.currentTimeMillis();
        if (job != null) {
            p.sendMessage(SmpPlugin.mm("<red>There's already a job on. One at a time."));
            return;
        }
        if (!t.bank().ready() || kind == Kind.WAGON && t.jail().releasePoint() == null) {
            p.sendMessage(SmpPlugin.mm("<red>That target doesn't exist."));
            return;
        }
        if (now < (kind == Kind.BANK ? bankReady : wagonReady)) {
            p.sendMessage(SmpPlugin.mm("<red>Too hot right now. Wait " + left(kind == Kind.BANK ? bankReady : wagonReady) + "."));
            return;
        }
        if (t.data(p).stars > 0 || t.exempt(p)) {
            p.sendMessage(SmpPlugin.mm("<red>Lie low first: you're wanted."));
            return;
        }
        if (!t.money.pay(p, cfg().getDouble("town.heists.plan-cost", 1000))) {
            p.sendMessage(SmpPlugin.mm("<red>The equipment costs " + Money.fmt(cfg().getDouble("town.heists.plan-cost", 1000)) + "."));
            return;
        }
        job = new Job();
        job.kind = kind;
        job.leader = p.getUniqueId();
        job.crew.add(p.getUniqueId());
        job.started = now;
        p.closeInventory();
        p.sendMessage(SmpPlugin.mm("<dark_purple>[Heist] <white>You're planning " + (kind == Kind.BANK ? "the Deep Vault" : "the Armored Wagon")
                + ". <gray>Get a crew: <white>/heist invite <player></white> (2-4 people), then <white>/heist start</white>. <white>/heist cancel</white> to call it off."));
    }

    // ------------------------------------------------------------------ crew commands

    public void command(Player p, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase();
        switch (sub) {
            case "invite" -> {
                if (job == null || !job.leader.equals(p.getUniqueId()) || job.stage != Stage.PLANNING) {
                    p.sendMessage(SmpPlugin.mm("<gray>Only the planner of a job can invite, before it starts."));
                    return;
                }
                Player o = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                if (o == null || o == p) {
                    p.sendMessage(SmpPlugin.mm("<yellow>/heist invite <player>"));
                    return;
                }
                if (job.crew.size() >= cfg().getInt("town.heists.max-crew", 4)) {
                    p.sendMessage(SmpPlugin.mm("<red>The crew is full."));
                    return;
                }
                job.invited.add(o.getUniqueId());
                p.sendMessage(SmpPlugin.mm("<dark_purple>[Heist] <gray>Invited " + o.getName() + "."));
                o.sendMessage(Component.text().append(SmpPlugin.mm("<dark_purple>[Heist] <white>" + p.getName() + " wants you in on a job. "))
                        .append(SmpPlugin.mm("<green><bold>[I'm in]").clickEvent(ClickEvent.runCommand("/heist join"))).build());
            }
            case "join" -> {
                if (job == null || !job.invited.remove(p.getUniqueId()) || job.stage != Stage.PLANNING) {
                    p.sendMessage(SmpPlugin.mm("<gray>Nobody has invited you to a job."));
                    return;
                }
                if (t.data(p).jailed()) return;
                job.crew.add(p.getUniqueId());
                crewSay("<green>" + p.getName() + " is in. Crew: " + job.crew.size());
            }
            case "leave" -> {
                if (job == null || !job.crew.remove(p.getUniqueId())) return;
                p.hideBossBar(job.bar);
                crewSay("<gray>" + p.getName() + " walked away from the job.");
                if (job.crew.isEmpty() || job.leader.equals(p.getUniqueId()) && job.stage == Stage.PLANNING) end("<gray>The job is off.", false);
            }
            case "cancel" -> {
                if (job != null && job.leader.equals(p.getUniqueId()) && (job.stage == Stage.PLANNING || job.stage == Stage.ALARM)) end("<gray>The job is off.", false);
            }
            case "start" -> start(p);
            case "status" -> status(p);
            default -> open(p);
        }
    }

    private void status(Player p) {
        if (job == null) {
            p.sendMessage(SmpPlugin.mm("<gray>No job running. Bank vault: " + (System.currentTimeMillis() < bankReady ? left(bankReady) : "ready")
                    + ", wagon: " + (System.currentTimeMillis() < wagonReady ? left(wagonReady) : "ready") + "."));
            return;
        }
        p.sendMessage(SmpPlugin.mm("<dark_purple>[Heist] <gray>" + (job.kind == Kind.BANK ? "Deep Vault" : "Armored Wagon") + ", stage " + job.stage.name().toLowerCase()
                + ", crew " + job.crew.size() + (job.getaway != null ? ", getaway at " + job.getaway.getBlockX() + ", " + job.getaway.getBlockZ() : "")));
    }

    private void start(Player p) {
        if (job == null || !job.leader.equals(p.getUniqueId()) || job.stage != Stage.PLANNING) {
            p.sendMessage(SmpPlugin.mm("<gray>You're not planning a job."));
            return;
        }
        job.crew.removeIf(u -> Bukkit.getPlayer(u) == null || t.data(Bukkit.getPlayer(u)).jailed());
        if (job.crew.size() < cfg().getInt("town.heists.min-crew", 2)) {
            p.sendMessage(SmpPlugin.mm("<red>You need a crew of at least " + cfg().getInt("town.heists.min-crew", 2) + " (online, not in jail)."));
            return;
        }
        World w = t.world();
        job.getaway = getaway(w);
        long now = System.currentTimeMillis();
        job.stageSince = now;
        for (UUID u : job.crew) Bukkit.getPlayer(u).showBossBar(job.bar);
        if (job.kind == Kind.BANK) {
            Location v = t.bank().vault();
            job.panel = new Location(v.getWorld(), v.getBlockX() + 0.5, v.getBlockY() - 1, v.getBlockZ() + 5.5);
            job.stage = Stage.ALARM;
            bankReady = now + cfg().getLong("town.heists.bank-cooldown-minutes", 360) * 60_000L;
            crewSay("<white><bold>The job is on.</bold> <gray>First, the alarm: stand at the panel behind the bank (red sparks) for "
                    + cfg().getInt("town.heists.alarm-seconds", 20) + "s without an officer seeing you. Or skip it and go straight for the vault, but then the police come at once.");
        } else {
            Location v = t.bank().vault();
            Location from = new Location(v.getWorld(), v.getBlockX() + 0.5, v.getBlockY() - 1, v.getBlockZ() - 10.5);
            job.courier = courier(from);
            job.stage = Stage.WAGON;
            wagonReady = now + cfg().getLong("town.heists.wagon-cooldown-minutes", 180) * 60_000L;
            crewSay("<white><bold>The job is on.</bold> <gray>The treasury courier is leaving the bank for the police station with 3 officers. Stop it before it gets there!");
        }
        save();
    }

    private Location getaway(World w) {
        double a = r.nextDouble() * Math.PI * 2, d = cfg().getDouble("town.heists.getaway-min", 560) + r.nextDouble() * 160;
        int x = (int) (t.cx + Math.cos(a) * d), z = (int) (t.cz + Math.sin(a) * d);
        return new Location(w, x + 0.5, 0, z + 0.5);
    }

    private Walker courier(Location at) {
        Map<EquipmentSlot, ItemStack> gear = new EnumMap<>(EquipmentSlot.class);
        gear.put(EquipmentSlot.HEAD, new ItemStack(Material.IRON_HELMET));
        gear.put(EquipmentSlot.CHEST, new ItemStack(Material.IRON_CHESTPLATE));
        gear.put(EquipmentSlot.LEGS, new ItemStack(Material.IRON_LEGGINGS));
        gear.put(EquipmentSlot.HAND, new ItemStack(Material.CHEST));
        Walker c = t.register(Walker.spawn(t.key, at, Walker.Role.COURIER, "<gold>Treasury Courier", "<gray>Carrying the strongbox", 0.2,
                cfg().getDouble("town.heists.courier-health", 60), gear));
        for (int i = 0; i < 3; i++) {
            Walker o = t.officers().spawn(at.clone().add(i - 1, 0, -1.5));
            o.guarding = c;
            o.corrupt = false;
        }
        return c;
    }

    // ------------------------------------------------------------------ the bank vault

    /** Bank calls this first; true = the heist used the click. */
    boolean vaultClicked(Player p) {
        if (job == null || job.kind != Kind.BANK || !job.crew.contains(p.getUniqueId())) return false;
        if (job.stage == Stage.ALARM) {
            job.stage = Stage.DRILL;
            job.stageSince = System.currentTimeMillis();
            job.progress = 0;
            job.policeAt = job.alarmCut ? System.currentTimeMillis() + cfg().getLong("town.heists.alarm-delay-seconds", 60) * 1000L : System.currentTimeMillis();
            crewSay("<white>The drill is on the vault. <gray>Stay within 5 blocks. " + (job.alarmCut ? "The alarm is cut: you have a minute before anyone notices."
                    : "<red>The alarm is live: the police are coming now!"));
            return true;
        }
        if (job.stage == Stage.DRILL) {
            p.sendActionBar(SmpPlugin.mm("<gray>The drill is running. Stay close and hold them off."));
            return true;
        }
        return false;
    }

    private void callPolice(List<Player> at) {
        if (job.policeCalled) return;
        job.policeCalled = true;
        for (Player o : Bukkit.getOnlinePlayers()) {
            o.sendMessage(SmpPlugin.mm("<red><bold>⚠ HEIST!</bold> " + (job.kind == Kind.BANK ? "Robbers are drilling the bank's deep vault!" : "The treasury wagon is under attack!")));
            o.playSound(o.getLocation(), Sound.BLOCK_BELL_USE, 0.6f, 0.5f);
        }
        for (Player p : at) {
            t.commit(p, Crime.HEIST, p.getLocation(), "heist" + job.started, null, true);
            t.officers().dispatch(p, 2);
        }
        t.news("A heist is under way" + (job.kind == Kind.BANK ? " at the bank!" : " on the treasury wagon!"));
    }

    // ------------------------------------------------------------------ every second

    void second() {
        if (job == null || job.stage == Stage.PLANNING) {
            if (job != null && System.currentTimeMillis() - job.started > 30 * 60_000L) end("<gray>The plan went stale. Job's off.", false);
            return;
        }
        long now = System.currentTimeMillis();
        List<Player> crew = new ArrayList<>();
        for (UUID u : job.crew) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && !t.data(p).jailed()) crew.add(p);
        }
        if (now - job.stageSince > cfg().getLong("town.heists.stage-minutes", 20) * 60_000L) {
            end("<red>Too slow. The job fell apart.", false);
            return;
        }
        switch (job.stage) {
            case ALARM -> alarm(crew, now);
            case DRILL -> drill(crew, now);
            case WAGON -> wagon(crew);
            case GETAWAY -> getaway(crew);
            default -> { }
        }
    }

    private void alarm(List<Player> crew, long now) {
        if (crew.isEmpty()) return;
        Location panel = job.panel;
        for (Player p : crew) {
            if (p.getWorld() == panel.getWorld()) p.spawnParticle(Particle.DUST, panel.clone().add(0, 1, 0), 8, 0.3, 0.5, 0.3, new Particle.DustOptions(Color.RED, 1.2f));
        }
        int at = 0;
        for (Player p : crew) {
            if (p.getWorld() != panel.getWorld() || p.getLocation().distance(panel) > 2.5) continue;
            Walker cop = t.officers().witness(p, p.getLocation());
            if (cop != null) {
                cop.say(p, "Hey! What are you doing back there?!");
                job.alarmCut = false;
                job.stage = Stage.DRILL;
                job.stageSince = now;
                job.policeAt = now;
                crewSay("<red>Spotted at the panel! The alarm is live. Get to the vault and drill, fast!");
                return;
            }
            at++;
        }
        double need = cfg().getDouble("town.heists.alarm-seconds", 20);
        if (at > 0) job.progress += 1;
        job.bar.name(SmpPlugin.mm("<light_purple>Cut the alarm (behind the bank): <white>" + (int) job.progress + "/" + (int) need + "s"));
        job.bar.progress((float) Math.min(1, job.progress / need));
        if (job.progress >= need && !job.alarmCut) {
            job.alarmCut = true;
            job.progress = 0;
            crewSay("<green>Alarm cut. <white>Now go inside and right-click the vault behind the counter.");
            job.bar.name(SmpPlugin.mm("<light_purple>Alarm cut: drill the vault"));
        }
    }

    private void drill(List<Player> crew, long now) {
        Location v = t.bank().vault();
        List<Player> at = new ArrayList<>();
        for (Player p : crew) if (p.getWorld() == v.getWorld() && p.getLocation().distance(v.clone().add(0.5, 0, 0.5)) <= 5) at.add(p);
        if (now >= job.policeAt && !job.policeCalled && !at.isEmpty()) callPolice(at);
        double need = cfg().getDouble("town.heists.drill-seconds", 120);
        for (Player p : crew) if (t.gangs().hasPerk(p, "safecracker")) need *= 0.7;
        job.progress += Math.min(3, at.size());
        job.bar.name(SmpPlugin.mm("<light_purple>Drilling the deep vault: <white>" + (int) (100 * Math.min(1, job.progress / need)) + "%"
                + (at.isEmpty() ? " <yellow>(get back to the vault!)" : "") + (!job.policeCalled ? " <gray>police in " + Math.max(0, (job.policeAt - now) / 1000) + "s" : "")));
        job.bar.progress((float) Math.min(1, job.progress / need));
        if (now % 3000 < 1000) v.getWorld().playSound(v, Sound.BLOCK_GRINDSTONE_USE, 1.5f, 0.6f);
        if (job.progress < need) return;
        if (!job.policeCalled) callPolice(at);
        int bags = cfg().getInt("town.heists.bank-bags", 3), value = cfg().getInt("town.heists.bank-bag-value", 2500);
        for (Player p : at) giveBags(p, bags, value);
        t.bank().markRobbed();
        startGetaway("<gold><bold>The deep vault is open!</bold> <white>Grab the bags and run.");
    }

    private void giveBags(Player p, int n, int value) {
        ItemStack bag = lootBag(value);
        bag.setAmount(n);
        for (ItemStack left : p.getInventory().addItem(bag).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        p.playSound(p.getLocation(), Sound.ITEM_BUNDLE_INSERT, 1f, 0.8f);
    }

    private void startGetaway(String msg) {
        job.stage = Stage.GETAWAY;
        job.stageSince = System.currentTimeMillis();
        crewSay(msg + " <gray>Take the loot bags to the getaway spot at <white>" + job.getaway.getBlockX() + ", " + job.getaway.getBlockZ()
                + "</white>. The bags are heavy, and if you're arrested they're evidence.");
        job.bar.color(BossBar.Color.YELLOW);
    }

    private void wagon(List<Player> crew) {
        Walker c = job.courier;
        if (c == null || !c.valid()) {
            end("<red>The wagon vanished. Job's off.", false);
            return;
        }
        Location dest = t.jail().releasePoint();
        if (c.dist(dest) < 3) {
            for (Walker o : new ArrayList<>(t.walkers(Walker.Role.OFFICER))) if (o.guarding == c) t.unregister(o, false);
            t.unregister(c, false);
            end("<red>The wagon reached the police station. The job failed.", false);
            return;
        }
        if (!c.moving()) c.moveTo(dest, 0.75);
        Player near = null;
        double nd = 1e9;
        for (Player p : crew) {
            double d = c.dist(p.getLocation());
            if (d < nd) {
                nd = d;
                near = p;
            }
        }
        job.bar.name(SmpPlugin.mm("<light_purple>Stop the treasury courier: <white>" + (int) Math.max(0, c.health) + " HP <gray>· " + (int) c.dist(dest)
                + "m from the station" + (near != null && nd < 1e8 ? " · " + (int) nd + "m " + PoliceForce.direction(near.getLocation(), c.loc()) : "")));
        job.bar.progress((float) Math.max(0, c.health / c.maxHealth));
    }

    /** A player hit the courier. */
    void courierHit(Walker c, Player p, double dmg) {
        c.health -= dmg;
        c.hurt();
        t.commit(p, Crime.HEIST, p.getLocation(), "wagon" + (job == null ? 0 : job.started), null, true);
        if (job != null && !job.policeCalled) {
            List<Player> l = new ArrayList<>();
            l.add(p);
            callPolice(l);
        }
        for (Walker o : t.walkers(Walker.Role.OFFICER)) if (o.guarding == c) t.officers().pursue(o, p);
        if (c.health > 0) return;
        c.shout("<gray>The strongbox! They've got the strongbox!", 24);
        Location at = c.loc();
        t.unregister(c, true);
        if (job == null || job.courier != c) return;
        int bags = cfg().getInt("town.heists.wagon-bags", 2), value = cfg().getInt("town.heists.wagon-bag-value", 2000);
        for (UUID u : job.crew) {
            Player m = Bukkit.getPlayer(u);
            if (m != null && m.getWorld() == at.getWorld() && m.getLocation().distance(at) < 12) giveBags(m, bags, value);
        }
        startGetaway("<gold><bold>The strongbox is yours!</bold>");
    }

    private void getaway(List<Player> crew) {
        boolean anyBags = false;
        for (Player p : crew) {
            int v = bagValue(p, false);
            if (v <= 0) continue;
            anyBags = true;
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0, false, false));
            Location l = p.getLocation();
            double dx = job.getaway.getX() - l.getX(), dz = job.getaway.getZ() - l.getZ(), d = Math.sqrt(dx * dx + dz * dz);
            if (p.getWorld() == job.getaway.getWorld() && d < 8) {
                bagValue(p, true);
                t.money.give(p, v);
                t.gangs().crimeIncome(p, v, 500);
                p.sendMessage(SmpPlugin.mm("<gold><bold>You made it!</bold> <yellow>+" + Money.fmt(v) + "</yellow> <gray>from the loot bags."));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                t.news(p.getName() + " got away with " + Money.fmt(v) + " from the " + (job.kind == Kind.BANK ? "bank job" : "wagon job") + "!");
                continue;
            }
            if (p.getWorld() == job.getaway.getWorld()) {
                job.getaway.setY(l.getY());
                p.spawnParticle(Particle.END_ROD, l.clone().add(dx / d * 3, 1.5, dz / d * 3), 2, 0.05, 0.05, 0.05, 0);
            }
            p.sendActionBar(SmpPlugin.mm("<gold>Getaway: <white>" + (int) d + "m " + PoliceForce.direction(l, job.getaway) + " <gray>· bags worth " + Money.fmt(v)));
        }
        job.bar.name(SmpPlugin.mm("<yellow>Getaway at " + job.getaway.getBlockX() + ", " + job.getaway.getBlockZ()));
        job.bar.progress(1f);
        if (!anyBags) end("<gray>No loot bags left on the crew. The job is over.", true);
    }

    private void end(String msg, boolean done) {
        if (job == null) return;
        crewSay(msg);
        for (UUID u : job.crew) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.hideBossBar(job.bar);
        }
        if (job.courier != null && job.courier.valid()) t.unregister(job.courier, false);
        for (Walker o : new ArrayList<>(t.walkers(Walker.Role.OFFICER))) if (o.guarding != null && !o.guarding.valid()) o.guarding = null;
        job = null;
    }

    void shutdown() {
        if (job != null) end("<gray>The job was interrupted.", false);
    }

    public String adminReset() {
        bankReady = 0;
        wagonReady = 0;
        save();
        if (job != null) end("<gray>Job cancelled by staff.", false);
        return "Heist cooldowns cleared.";
    }
}
