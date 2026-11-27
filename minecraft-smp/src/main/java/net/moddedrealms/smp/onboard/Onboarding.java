package net.moddedrealms.smp.onboard;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import net.moddedrealms.smp.SafeYaml;
import net.moddedrealms.smp.SmpPlugin;
import net.moddedrealms.smp.npc.NpcDef;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

/**
 * The first minute on the server: a new player arrives facing Elder Rowan, who glows and has "START HERE" over his
 * head; a task bar says "Talk to Elder Rowan"; the other spawn NPCs send new players to him; Rowan gives a short
 * welcome and two big choices, Found a Colony (a ready camp from ColonySMP) or Join the Battles (the arena server).
 * Rowan calls out to anyone who hasn't come over after a minute. Settings: onboarding in config.yml. Who has chosen
 * what: onboarding.yml.
 */
public final class Onboarding implements Listener {
    private final SmpPlugin plugin;
    private final File file;
    private final YamlConfiguration data;
    private final NamespacedKey holoKey;
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, Long> joinedAt = new HashMap<>(), nudgedAt = new HashMap<>();
    private final Map<UUID, Integer> nudges = new HashMap<>();
    private final Map<UUID, String> barText = new HashMap<>();
    private TextDisplay holo;
    private long ticks;

    public Onboarding(SmpPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "onboarding.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        this.holoKey = new NamespacedKey(plugin, "start_here");
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 10L);
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("onboarding");
        return s != null ? s : new YamlConfiguration();
    }

    /** The whole flow is on (onboarding.enabled). */
    public boolean enabled() {
        return cfg().getBoolean("enabled", false);
    }

    private String guideId() {
        return str("guide-npc", "elder");
    }

    /** A text setting; keys missing from the server's config.yml come from the jar's copy. */
    private String str(String key, String def) {
        String v = plugin.getConfig().getString("onboarding." + key);
        return v != null ? v : def;
    }

    private List<String> list(String key) {
        return plugin.getConfig().getStringList("onboarding." + key);
    }

    private String line(String key, Player p) {
        return str(key, "").replace("{player}", p.getName());
    }

    // ------------------------------------------------------------------ who is new

    private String path(UUID u) {
        return "players." + u;
    }

    /** Arrived since onboarding was switched on and hasn't chosen a path with Rowan yet. */
    public boolean isNew(UUID u) {
        return data.contains(path(u) + ".first") && !data.contains(path(u) + ".chose");
    }

    private boolean talked(UUID u) {
        return data.contains(path(u) + ".talked");
    }

    private void set(UUID u, String key, Object v) {
        data.set(path(u) + "." + key, v);
        try {
            SafeYaml.save(data, file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't save onboarding.yml", e);
        }
    }

    // ------------------------------------------------------------------ arriving

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        joinedAt.put(u, System.currentTimeMillis());
        if (!enabled()) return;
        boolean first = !p.hasPlayedBefore() && !data.contains(path(u) + ".first");
        if (first) set(u, "first", System.currentTimeMillis());
        if (!isNew(u)) return;
        // after Essentials has put them at spawn
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (first) {
                Location at = arrival();
                if (at != null) p.teleport(at);
                p.showTitle(Title.title(SmpPlugin.mm(str("title", "<gold><bold>Welcome to ModdedRealms")),
                        SmpPlugin.mm(str("subtitle", "<white>Build your kingdom. Rule an empire.")),
                        Title.Times.times(java.time.Duration.ofMillis(500), java.time.Duration.ofMillis(4000), java.time.Duration.ofMillis(1000))));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
            }
            showBar(p);
        }, cfg().getLong("arrival-delay-ticks", 8L));
    }

    /** True when this plugin greets first-time players itself (so Welcome skips its title and the /start menu). */
    public boolean handlesFirstJoin() {
        return enabled();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        BossBar b = bars.remove(u);
        if (b != null) e.getPlayer().hideBossBar(b);
        joinedAt.remove(u);
        nudgedAt.remove(u);
        nudges.remove(u);
    }

    /** A few blocks in front of Rowan, facing him. */
    private Location arrival() {
        NpcDef d = plugin.npcs().get(guideId());
        Location home = d == null ? null : d.home();
        if (home == null) return null;
        double yaw = Math.toRadians(d.yaw);
        Vector dir = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        double want = cfg().getDouble("arrival-distance", 4.5);
        for (double dist : new double[]{want, want - 1, want + 1, want - 2, 2.5}) {
            Location l = home.clone().add(dir.clone().multiply(dist));
            Location ok = standable(l);
            if (ok != null) {
                ok.setYaw(d.yaw + 180);
                ok.setPitch(5);
                return ok;
            }
        }
        return null;
    }

    private static Location standable(Location l) {
        Block base = l.getBlock();
        for (int dy = 2; dy >= -3; dy--) {
            Block feet = base.getRelative(0, dy, 0);
            if (feet.isPassable() && !feet.isLiquid() && feet.getRelative(0, 1, 0).isPassable() && feet.getRelative(0, -1, 0).getType().isSolid()) {
                return new Location(l.getWorld(), l.getX(), feet.getY(), l.getZ());
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the task bar

    private void showBar(Player p) {
        if (!enabled() || !isNew(p.getUniqueId())) {
            hideBar(p);
            return;
        }
        String text = talked(p.getUniqueId()) ? str("bar-choose", "<gold>Choose your path</gold> <gray>· right-click Elder Rowan again")
                : str("bar-talk", "<gold>Talk to Elder Rowan</gold> <gray>· he's glowing by the fountain");
        BossBar b = bars.get(p.getUniqueId());
        float prog = talked(p.getUniqueId()) ? 0.5f : 0.1f;
        if (b == null) {
            b = BossBar.bossBar(SmpPlugin.mm(text), prog, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            bars.put(p.getUniqueId(), b);
            p.showBossBar(b);
        } else if (b.progress() != prog || !text.equals(barText.get(p.getUniqueId()))) {
            b.name(SmpPlugin.mm(text));
            b.progress(prog);
        }
        barText.put(p.getUniqueId(), text);
    }

    private void hideBar(Player p) {
        barText.remove(p.getUniqueId());
        BossBar b = bars.remove(p.getUniqueId());
        if (b != null) p.hideBossBar(b);
    }

    // ------------------------------------------------------------------ talking to the NPCs

    /** Called before an NPC's usual conversation; true when onboarding answered instead. */
    public boolean intercept(Player p, NpcDef npc) {
        if (!enabled()) return false;
        UUID u = p.getUniqueId();
        if (npc.id.equals(guideId())) {
            if (plugin.town() != null && plugin.town().colonyName(p) != null) return false;
            talk(p, npc);
            return true;
        }
        if (!isNew(u)) return false;
        p.sendMessage(Component.text().append(SmpPlugin.mm(npc.name)).append(Component.text(" » ", NamedTextColor.DARK_GRAY))
                .append(SmpPlugin.mm("<white>" + line("other-npc", p))).build());
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BANJO, 0.5f, 1.3f);
        trail(p);
        return true;
    }

    private void talk(Player p, NpcDef npc) {
        UUID u = p.getUniqueId();
        boolean firstTime = !talked(u);
        if (firstTime) set(u, "talked", System.currentTimeMillis());
        ColonyBridge.funnel(u, "talked_to_rowan");
        showBar(p);
        if (!firstTime) {
            plugin.dialogues().play(p, npc, List.of(line("again", p)), List.of());
            Bukkit.getScheduler().runTaskLater(plugin, () -> openChoice(p), 12L);
            return;
        }
        List<String> intro = new ArrayList<>();
        for (String s : list("intro")) intro.add(s.replace("{player}", p.getName()));
        if (intro.isEmpty()) intro.add("Welcome, " + p.getName() + ".");
        plugin.dialogues().play(p, npc, intro, List.of());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && isNew(u)) openChoice(p);
        }, intro.size() * 28L + 6L);
    }

    // ------------------------------------------------------------------ the choice

    static final class Menu implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final int COLONY = 11, FRIEND = 13, BATTLES = 15, STORY = 22, BACK = 22, INVITED = 4;

    /** "A friend invited me": the players online, to name who brought you. */
    static final class InviterMenu implements InventoryHolder {
        Inventory inv;
        final java.util.Map<Integer, UUID> players = new java.util.HashMap<>();

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    /** Elder Rowan's list of colonies with players online, to ask to join. */
    static final class FriendMenu implements InventoryHolder {
        Inventory inv;
        final java.util.Map<Integer, String> colonies = new java.util.HashMap<>();

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    public void openChoice(Player p) {
        if (!p.isOnline()) return;
        Menu m = new Menu();
        m.inv = Bukkit.createInventory(m, 27, SmpPlugin.mm(str("menu-title", "Elder Rowan · Choose your path")));
        ItemStack glass = icon(Material.BROWN_STAINED_GLASS_PANE, " ", List.of(), false);
        for (int i = 0; i < 27; i++) m.inv.setItem(i, glass);
        boolean camps = ColonyBridge.starterCamps();
        List<String> colonyLore = new ArrayList<>(list("colony-lore"));
        if (!camps) colonyLore.add("<gray>(Rowan tells you how)");
        m.inv.setItem(COLONY, icon(Material.LODESTONE, str("colony-name", "<green><bold>Found a Colony"), colonyLore, true));
        m.inv.setItem(BATTLES, icon(Material.IRON_SWORD, str("battles-name", "<red><bold>Join the Battles"), list("battles-lore"), false));
        if (plugin.referrals() != null && plugin.referrals().canClaim(p)) {
            m.inv.setItem(INVITED, icon(Material.NAME_TAG, str("invited-name", "<light_purple>A friend invited me"), list("invited-lore"), false));
        }
        if (ColonyBridge.friends()) {
            int n = ColonyBridge.joinable(p).size();
            List<String> lore = new ArrayList<>(list("friend-lore"));
            lore.add(n == 0 ? str("friend-none", "<dark_gray>Nobody with a colony is online right now")
                    : str("friend-some", "<aqua>{n} colonies have players online").replace("{n}", String.valueOf(n)));
            lore.add("");
            lore.add("<yellow>Click to see them");
            m.inv.setItem(FRIEND, icon(Material.PLAYER_HEAD, str("friend-name", "<aqua><bold>Join a Friend's Colony"), lore, false));
        }
        m.inv.setItem(STORY, icon(Material.WRITABLE_BOOK, str("story-name", "<yellow>Talk with Rowan"),
                List.of("<gray>His story and the daily quests"), false));
        p.openInventory(m.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.4f);
    }

    private static ItemStack icon(Material m, String name, List<String> lore, boolean glow) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(SmpPlugin.mm("<!italic>" + name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(SmpPlugin.mm("<!italic>" + s));
        meta.lore(l);
        if (glow) meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (e.getRawSlot() == COLONY) {
            p.closeInventory();
            chooseColony(p);
        } else if (e.getRawSlot() == BATTLES) {
            p.closeInventory();
            chooseBattles(p);
        } else if (e.getRawSlot() == FRIEND && ColonyBridge.friends()) {
            openFriends(p);
        } else if (e.getRawSlot() == INVITED && plugin.referrals() != null && plugin.referrals().canClaim(p)) {
            openInviter(p);
        } else if (e.getRawSlot() == STORY) {
            p.closeInventory();
            NpcDef d = plugin.npcs().get(guideId());
            if (d != null) plugin.dialogues().converse(p, d);
        }
    }

    private void openFriends(Player p) {
        FriendMenu m = new FriendMenu();
        m.inv = Bukkit.createInventory(m, 27, SmpPlugin.mm(str("friends-title", "Elder Rowan · Join a friend")));
        ItemStack glass = icon(Material.BROWN_STAINED_GLASS_PANE, " ", List.of(), false);
        for (int i = 0; i < 27; i++) m.inv.setItem(i, glass);
        var list = ColonyBridge.joinable(p);
        int slot = 0;
        for (var j : list) {
            if (slot >= 18) break;
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + j.tier() + " · " + j.population() + " settlers");
            lore.add("<gray>Online: <white>" + String.join(", ", j.online()));
            lore.add("");
            lore.add("<yellow>Click to ask to join");
            ItemStack head = icon(Material.PLAYER_HEAD, "<aqua>" + SmpPlugin.MM.escapeTags(j.name()), lore, false);
            Player first = j.online().isEmpty() ? null : Bukkit.getPlayerExact(j.online().get(0));
            if (first != null && head.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta sm) {
                sm.setOwningPlayer(first);
                head.setItemMeta(sm);
            }
            m.inv.setItem(slot, head);
            m.colonies.put(slot, j.id());
            slot++;
        }
        if (list.isEmpty()) {
            m.inv.setItem(13, icon(Material.BARRIER, str("friend-none", "<dark_gray>Nobody with a colony is online right now"),
                    list("friend-none-lore"), false));
        }
        m.inv.setItem(BACK, icon(Material.ARROW, "<white>Back", List.of("<gray>To Elder Rowan's choices"), false));
        p.openInventory(m.inv);
    }

    private void openInviter(Player p) {
        InviterMenu m = new InviterMenu();
        m.inv = Bukkit.createInventory(m, 27, SmpPlugin.mm(str("invited-title", "Who invited you?")));
        ItemStack glass = icon(Material.BROWN_STAINED_GLASS_PANE, " ", List.of(), false);
        for (int i = 0; i < 27; i++) m.inv.setItem(i, glass);
        int slot = 0;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(p) || slot >= 18) continue;
            ItemStack head = icon(Material.PLAYER_HEAD, "<white>" + SmpPlugin.MM.escapeTags(o.getName()), List.of("<yellow>Click: they invited me"), false);
            if (head.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta sm) {
                sm.setOwningPlayer(o);
                head.setItemMeta(sm);
            }
            m.inv.setItem(slot, head);
            m.players.put(slot, o.getUniqueId());
            slot++;
        }
        m.inv.setItem(13 + 9, icon(Material.ARROW, "<white>Back", List.of("<gray>To Elder Rowan's choices", "<gray>(or type <white>/invite <code></white>)"), false));
        p.openInventory(m.inv);
    }

    @EventHandler
    public void onInviterClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof InviterMenu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (e.getRawSlot() == BACK) {
            openChoice(p);
            return;
        }
        UUID by = m.players.get(e.getRawSlot());
        if (by == null) return;
        String err = plugin.referrals().claim(p, by);
        if (err != null) p.sendMessage(SmpPlugin.mm("<red>" + SmpPlugin.MM.escapeTags(err)));
        openChoice(p);
    }

    @EventHandler
    public void onFriendClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof FriendMenu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (e.getRawSlot() == BACK) {
            openChoice(p);
            return;
        }
        String id = m.colonies.get(e.getRawSlot());
        if (id == null) return;
        p.closeInventory();
        String err = ColonyBridge.askToJoin(p, id);
        if (err != null) p.sendMessage(SmpPlugin.mm("<red>" + SmpPlugin.MM.escapeTags(err)));
        else say(p, line("friend-asked", p));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        var h = e.getInventory().getHolder();
        if (h instanceof Menu || h instanceof FriendMenu || h instanceof InviterMenu) e.setCancelled(true);
    }

    private void say(Player p, String text) {
        NpcDef d = plugin.npcs().get(guideId());
        Component name = SmpPlugin.mm(d == null ? "<gold>Elder Rowan" : d.name);
        p.sendMessage(Component.text().append(name).append(Component.text(" » ", NamedTextColor.DARK_GRAY)).append(SmpPlugin.mm("<white>" + text)).build());
    }

    private void chooseColony(Player p) {
        UUID u = p.getUniqueId();
        ColonyBridge.funnel(u, "chose_colony");
        set(u, "chose", "colony");
        set(u, "chose-at", System.currentTimeMillis());
        hideBar(p);
        if (!ColonyBridge.starterCamps()) {
            say(p, line("colony-manual", p));
            if (plugin.guide() != null) plugin.guide().open(p);
            return;
        }
        say(p, line("colony-start", p));
        String err = ColonyBridge.startColony(p, done -> {
            if (done == null || done.equals("offline")) return;
            // it didn't work: they can try again with Rowan
            set(u, "chose", null);
            p.sendMessage(SmpPlugin.mm("<red>" + done));
            showBar(p);
        });
        if (err != null) {
            set(u, "chose", null);
            p.sendMessage(SmpPlugin.mm("<red>" + err));
            showBar(p);
        }
    }

    private void chooseBattles(Player p) {
        UUID u = p.getUniqueId();
        ColonyBridge.funnel(u, "chose_battles");
        set(u, "chose", "battles");
        set(u, "chose-at", System.currentTimeMillis());
        hideBar(p);
        say(p, line("battles", p));
        String server = str("battles-server", "arena");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeUTF("Connect");
                out.writeUTF(server);
                p.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
            } catch (IOException | RuntimeException ex) {
                p.sendMessage(SmpPlugin.mm("<gray>Use <white>/server " + server + "</white> to go to the Battles."));
            }
        }, 30L);
    }

    // ------------------------------------------------------------------ Rowan glows, START HERE, nudges

    private void tick() {
        ticks++;
        if (!enabled()) {
            removeHolo();
            return;
        }
        Entity guide = plugin.npcs().entity(guideId());
        NpcDef d = plugin.npcs().get(guideId());
        decorate(guide, d);
        if (ticks % 2 != 0) return;
        long now = System.currentTimeMillis();
        long idle = Math.max(20, cfg().getLong("idle-seconds", 60)) * 1000L;
        int most = cfg().getInt("nudges", 3);
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            if (!isNew(u)) {
                hideBar(p);
                continue;
            }
            // joined someone's colony (let in, or /colony invite): that's their choice made
            if (ColonyBridge.hasColony(u)) {
                set(u, "chose", "friend");
                set(u, "chose-at", now);
                hideBar(p);
                continue;
            }
            showBar(p);
            long last = Math.max(joinedAt.getOrDefault(u, now), Math.max(nudgedAt.getOrDefault(u, 0L), data.getLong(path(u) + ".talked", 0L)));
            if (now - last < idle || nudges.getOrDefault(u, 0) >= most || p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) continue;
            nudges.merge(u, 1, Integer::sum);
            nudgedAt.put(u, now);
            say(p, line(talked(u) ? "nudge-choose" : "nudge", p));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.2f);
            trail(p);
        }
    }

    private void decorate(Entity guide, NpcDef d) {
        if (guide == null || !guide.isValid() || d == null) {
            removeHolo();
            return;
        }
        if (!guide.isGlowing()) guide.setGlowing(true);
        team(guide);
        Location top = guide.getLocation().add(0, cfg().getDouble("hologram-height", 2.75), 0);
        if (holo == null || !holo.isValid()) {
            for (Entity e : top.getWorld().getNearbyEntities(top, 2, 3, 2)) if (e.getPersistentDataContainer().has(holoKey, PersistentDataType.BYTE)) e.remove();
            holo = top.getWorld().spawn(top, TextDisplay.class, t -> {
                t.setPersistent(false);
                t.setBillboard(Display.Billboard.CENTER);
                t.setShadowed(true);
                t.setDefaultBackground(false);
                t.setBackgroundColor(Color.fromARGB(140, 40, 20, 0));
                t.setViewRange(0.8f);
                t.getPersistentDataContainer().set(holoKey, PersistentDataType.BYTE, (byte) 1);
            });
        } else if (holo.getLocation().distanceSquared(top) > 0.04) {
            holo.teleport(top);
        }
        holo.text(SmpPlugin.mm(str("hologram", "<gold><bold>⚔ START HERE ⚔</bold></gold>\n<yellow>Right-click Elder Rowan")));
        // a slow ring of sparks, only while someone is close enough to see it
        boolean watched = false;
        for (Player p : guide.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(guide.getLocation()) < 32 * 32) {
                watched = true;
                break;
            }
        }
        if (!watched) return;
        Location c = guide.getLocation();
        for (int i = 0; i < 3; i++) {
            double a = (ticks * 0.35) + i * (Math.PI * 2 / 3);
            guide.getWorld().spawnParticle(Particle.END_ROD, c.getX() + Math.cos(a) * 0.8, c.getY() + 0.3 + (ticks % 10) * 0.18, c.getZ() + Math.sin(a) * 0.8, 1, 0, 0, 0, 0);
        }
        if (ticks % 4 == 0) guide.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, c.clone().add(0, 2.2, 0), 2, 0.3, 0.2, 0.3, 0);
    }

    private void team(Entity guide) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team t = sb.getTeam("mr_start_here");
        if (t == null) {
            t = sb.registerNewTeam("mr_start_here");
            t.color(NamedTextColor.GOLD);
        }
        String entry = guide.getUniqueId().toString();
        if (!t.hasEntry(entry)) t.addEntry(entry);
    }

    private void removeHolo() {
        if (holo != null && holo.isValid()) holo.remove();
        holo = null;
    }

    /** Gold sparks from the player toward Rowan, only they see them. */
    private void trail(Player p) {
        Entity guide = plugin.npcs().entity(guideId());
        if (guide == null || guide.getWorld() != p.getWorld()) return;
        Location from = p.getEyeLocation().subtract(0, 0.4, 0), to = guide.getLocation().add(0, 1.2, 0);
        Vector step = to.toVector().subtract(from.toVector());
        double len = step.length();
        if (len < 2 || len > 80) return;
        step.normalize();
        Particle.DustOptions gold = new Particle.DustOptions(Color.fromRGB(255, 200, 40), 1.1f);
        for (double t = 1; t < Math.min(len, 24); t += 0.8) {
            Location at = from.clone().add(step.clone().multiply(t));
            p.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, gold);
        }
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en.getPersistentDataContainer().has(holoKey, PersistentDataType.BYTE) && (holo == null || !en.getUniqueId().equals(holo.getUniqueId()))) en.remove();
        }
    }

    public void shutdown() {
        removeHolo();
        for (Map.Entry<UUID, BossBar> b : bars.entrySet()) {
            Player p = Bukkit.getPlayer(b.getKey());
            if (p != null) p.hideBossBar(b.getValue());
        }
        bars.clear();
    }

    /** For /mrsmp onboarding: how many arrived, talked and chose what. */
    public List<String> stats() {
        int first = 0, talked = 0, colony = 0, battles = 0, friend = 0;
        ConfigurationSection s = data.getConfigurationSection("players");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                ConfigurationSection c = s.getConfigurationSection(k);
                if (c == null) continue;
                if (c.contains("first")) first++;
                if (c.contains("talked")) talked++;
                if ("colony".equals(c.getString("chose"))) colony++;
                if ("battles".equals(c.getString("chose"))) battles++;
                if ("friend".equals(c.getString("chose"))) friend++;
            }
        }
        return List.of("<gold>Onboarding " + (enabled() ? "<green>on" : "<red>off") + "</gold> <gray>· " + first + " arrived, " + talked + " talked to Rowan, "
                + colony + " chose a colony, " + battles + " chose the Battles, " + friend + " joined a friend");
    }
}
