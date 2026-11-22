package net.moddedrealms.smp.quest;

import io.papermc.paper.event.player.PlayerTradeEvent;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import net.moddedrealms.smp.SmpPlugin;
import net.moddedrealms.smp.npc.NpcDef;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Daily, weekly and story quests: assignment, progress from game events, rewards and streaks. */
public final class QuestManager implements Listener {
    private final SmpPlugin plugin;
    private final Map<String, QuestDef> daily = new LinkedHashMap<>();
    private final Map<String, QuestDef> weekly = new LinkedHashMap<>();
    private final List<QuestDef> story = new ArrayList<>();
    private final Map<UUID, PlayerQuests> players = new HashMap<>();
    private final File folder;

    public QuestManager(SmpPlugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "playerdata");
        reload();
    }

    public void reload() {
        daily.clear();
        weekly.clear();
        story.clear();
        YamlConfiguration y = plugin.yaml("quests.yml");
        load(y.getConfigurationSection("daily"), QuestDef.Kind.DAILY, daily);
        load(y.getConfigurationSection("weekly"), QuestDef.Kind.WEEKLY, weekly);
        for (Map<?, ?> m : y.getMapList("story")) {
            // createSection turns the nested maps (objective) into real sections.
            ConfigurationSection tmp = new YamlConfiguration().createSection("q", m);
            QuestDef q = QuestDef.load(String.valueOf(m.get("id")), QuestDef.Kind.STORY, tmp);
            if (q != null) story.add(q);
        }
        plugin.getLogger().info("Quests: " + daily.size() + " daily, " + weekly.size() + " weekly, " + story.size() + " story chapters.");
    }

    private void load(ConfigurationSection s, QuestDef.Kind kind, Map<String, QuestDef> into) {
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection qs = s.getConfigurationSection(id);
            QuestDef q = qs == null ? null : QuestDef.load(id, kind, qs);
            if (q != null) into.put(id, q);
            else plugin.getLogger().warning("Bad quest " + id + " in quests.yml");
        }
    }

    public void start() {
        for (Player p : Bukkit.getOnlinePlayers()) data(p);
        Bukkit.getScheduler().runTaskTimer(plugin, this::minute, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> Bukkit.getScheduler().runTask(plugin, this::saveDirty), 6000L, 6000L);
    }

    // ------------------------------------------------------------------ data

    PlayerQuests data(Player p) {
        PlayerQuests q = players.computeIfAbsent(p.getUniqueId(), id -> new PlayerQuests(new File(folder, id + ".yml")));
        roll(q);
        return q;
    }

    private void roll(PlayerQuests q) {
        LocalDate today = LocalDate.now();
        String day = today.toString();
        String week = today.get(IsoFields.WEEK_BASED_YEAR) + "-W" + today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        if (!day.equals(q.day) || q.daily.isEmpty() || !daily.keySet().containsAll(q.daily)) {
            q.day = day;
            q.daily = pick(daily, plugin.getConfig().getInt("quests.daily-count", 3));
            q.progress.keySet().removeIf(k -> k.startsWith("d:"));
            q.done.removeIf(k -> k.startsWith("d:"));
            q.metToday.clear();
            q.dirty = true;
        }
        if (!week.equals(q.week) || q.weekly.isEmpty() || !weekly.keySet().containsAll(q.weekly)) {
            q.week = week;
            q.weekly = pick(weekly, plugin.getConfig().getInt("quests.weekly-count", 2));
            q.progress.keySet().removeIf(k -> k.startsWith("w:"));
            q.done.removeIf(k -> k.startsWith("w:"));
            q.dirty = true;
        }
    }

    private static List<String> pick(Map<String, QuestDef> pool, int n) {
        List<String> ids = new ArrayList<>(pool.keySet());
        Collections.shuffle(ids);
        return new ArrayList<>(ids.subList(0, Math.min(n, ids.size())));
    }

    private void saveDirty() {
        for (PlayerQuests q : players.values()) if (q.dirty) save(q);
    }

    private void save(PlayerQuests q) {
        try {
            q.save();
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save quests " + q.file.getName() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ crate keys (virtual)

    public int keys(UUID id, String crate) {
        Player online = Bukkit.getPlayer(id);
        PlayerQuests q = online != null ? data(online) : new PlayerQuests(new File(folder, id + ".yml"));
        return q.keys.getOrDefault(crate, 0);
    }

    /** Adds (or with a negative amount, takes) keys; works for offline players too. Returns the new balance. */
    public int addKeys(UUID id, String crate, int amount) {
        Player online = Bukkit.getPlayer(id);
        PlayerQuests q = online != null ? data(online) : new PlayerQuests(new File(folder, id + ".yml"));
        int now = Math.max(0, q.keys.getOrDefault(crate, 0) + amount);
        if (now == 0) q.keys.remove(crate);
        else q.keys.put(crate, now);
        q.dirty = true;
        save(q);
        return now;
    }

    public void saveAll() {
        for (PlayerQuests q : players.values()) save(q);
    }

    // ------------------------------------------------------------------ progress

    record Active(QuestDef def, String key, int progress, boolean done) {}

    List<Active> active(Player p) {
        PlayerQuests q = data(p);
        List<Active> out = new ArrayList<>();
        for (String id : q.daily) {
            QuestDef d = daily.get(id);
            if (d != null) out.add(new Active(d, "d:" + id, q.progress.getOrDefault("d:" + id, 0), q.done.contains("d:" + id)));
        }
        for (String id : q.weekly) {
            QuestDef d = weekly.get(id);
            if (d != null) out.add(new Active(d, "w:" + id, q.progress.getOrDefault("w:" + id, 0), q.done.contains("w:" + id)));
        }
        if (q.storyIndex < story.size()) out.add(new Active(story.get(q.storyIndex), "s", q.storyProgress, false));
        return out;
    }

    QuestDef storyStep(Player p) {
        PlayerQuests q = data(p);
        return q.storyIndex < story.size() ? story.get(q.storyIndex) : null;
    }

    int storyCount() { return story.size(); }

    PlayerQuests peek(Player p) { return data(p); }

    public void progress(Player p, QuestDef.Type type, String value, Entity entity, int amount) {
        if (amount <= 0) return;
        PlayerQuests q = data(p);
        for (Active a : active(p)) {
            if (a.done || a.def.type != type || !a.def.matches(value, entity)) continue;
            int now = Math.min(a.def.amount, a.progress + amount);
            if (a.key.equals("s")) q.storyProgress = now;
            else q.progress.put(a.key, now);
            q.dirty = true;
            if (now >= a.def.amount) complete(p, q, a);
            else if (type != QuestDef.Type.WALK || now % 50 == 0 || amount > 1) {
                p.sendActionBar(Component.text(a.def.name + "  ", NamedTextColor.GOLD).append(Component.text(bar(now, a.def.amount) + " " + now + "/" + a.def.amount, NamedTextColor.YELLOW)));
            }
        }
    }

    private void complete(Player p, PlayerQuests q, Active a) {
        QuestDef d = a.def;
        String rewardText = plugin.rewards().describe(d.rewards);
        plugin.rewards().give(p, d.rewards);
        if (a.key.equals("s")) {
            q.storyIndex++;
            q.storyProgress = 0;
            q.storyIntroSeen = false;
            QuestDef next = q.storyIndex < story.size() ? story.get(q.storyIndex) : null;
            p.showTitle(Title.title(SmpPlugin.mm("<gold>Chapter complete"), SmpPlugin.mm("<yellow>" + d.name),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2500), Duration.ofMillis(800))));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
            p.sendMessage(SmpPlugin.mm("<gold>✎ Chapter complete: <yellow>" + d.name + "</yellow> <gray>(" + rewardText + ")"));
            if (next != null) {
                NpcDef giver = next.giver == null ? null : plugin.npcs().get(next.giver);
                p.sendMessage(SmpPlugin.mm("<gray>Next chapter: <white>" + next.name + "</white>"
                        + (giver != null ? " — talk to " + giver.name + " <gray>at spawn." : ".")));
            } else {
                p.sendMessage(SmpPlugin.mm("<gold><bold>You have finished the story of Moddedrealms!</bold>"));
            }
        } else {
            q.done.add(a.key);
            p.showTitle(Title.title(SmpPlugin.mm("<green>Quest complete"), SmpPlugin.mm("<white>" + d.name),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(600))));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
            p.sendMessage(SmpPlugin.mm("<green>✔ " + (d.kind == QuestDef.Kind.WEEKLY ? "Weekly" : "Daily") + " quest complete: <white>"
                    + d.name + "</white> <gray>(" + rewardText + ")"));
            if (d.kind == QuestDef.Kind.DAILY && q.daily.stream().allMatch(id -> q.done.contains("d:" + id))) streak(p, q);
        }
        q.dirty = true;
        save(q);
    }

    private void streak(Player p, PlayerQuests q) {
        String today = LocalDate.now().toString();
        if (today.equals(q.lastStreakDay)) return;
        String yesterday = LocalDate.now().minusDays(1).toString();
        q.streak = yesterday.equals(q.lastStreakDay) ? q.streak + 1 : 1;
        q.lastStreakDay = today;
        p.sendMessage(SmpPlugin.mm("<gold>✹ All daily quests done! Streak: <yellow>" + q.streak + " day" + (q.streak == 1 ? "" : "s")));
        ConfigurationSection sr = plugin.getConfig().getConfigurationSection("quests.streak-rewards");
        if (sr != null && sr.contains(String.valueOf(q.streak))) {
            List<String> r = sr.getStringList(String.valueOf(q.streak));
            plugin.rewards().give(p, r);
            p.sendMessage(SmpPlugin.mm("<gold>Streak bonus: <yellow>" + plugin.rewards().describe(r)));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.5f);
        }
    }

    static String bar(int now, int max) {
        int filled = (int) Math.round(10.0 * now / max);
        return "■".repeat(filled) + "□".repeat(10 - filled);
    }

    // ------------------------------------------------------------------ NPCs and story

    /** Story text for this NPC (a new chapter's intro), or null for its normal dialogue. Also counts TALK. */
    public List<String> talk(Player p, String npcId) {
        PlayerQuests q = data(p);
        List<String> out = null;
        QuestDef step = storyStep(p);
        if (step != null && npcId.equals(step.giver) && !q.storyIntroSeen) {
            q.storyIntroSeen = true;
            q.dirty = true;
            if (!step.talk.isEmpty()) {
                out = new ArrayList<>(step.talk);
                out.add("<gold>✎ New chapter: <yellow>" + step.name + "</yellow> <gray>— " + step.description);
            }
        }
        talked(p, npcId);
        return out;
    }

    public void talked(Player p, String npcId) {
        progress(p, QuestDef.Type.TALK, npcId, null, 1);
    }

    public void showStory(Player p) {
        QuestDef step = storyStep(p);
        PlayerQuests q = data(p);
        if (step == null) {
            p.sendMessage(SmpPlugin.mm("<gold>You have finished every chapter. A true Legend of Moddedrealms!"));
            return;
        }
        NpcDef giver = step.giver == null ? null : plugin.npcs().get(step.giver);
        p.sendMessage(SmpPlugin.mm("<gold>✎ Chapter " + (q.storyIndex + 1) + "/" + story.size() + ": <yellow>" + step.name));
        p.sendMessage(SmpPlugin.mm("<gray>" + step.description + "  <yellow>" + bar(q.storyProgress, step.amount) + " " + q.storyProgress + "/" + step.amount));
        if (!q.storyIntroSeen && giver != null) p.sendMessage(SmpPlugin.mm("<gray>Talk to " + giver.name + " <gray>at spawn to begin it."));
        p.sendMessage(SmpPlugin.mm("<gray>Reward: <white>" + plugin.rewards().describe(step.rewards)));
    }

    public void openGui(Player p) {
        QuestGui.open(plugin, this, p);
    }

    public void reset(UUID id) {
        PlayerQuests q = players.remove(id);
        File f = q != null ? q.file : new File(folder, id + ".yml");
        f.delete();
    }

    public boolean forceComplete(Player p, String which) {
        for (Active a : active(p)) {
            if (a.done) continue;
            if (a.def.id.equalsIgnoreCase(which) || which.equalsIgnoreCase("story") && a.key.equals("s")) {
                PlayerQuests q = data(p);
                if (a.key.equals("s")) q.storyProgress = a.def.amount;
                else q.progress.put(a.key, a.def.amount);
                complete(p, q, new Active(a.def, a.key, a.def.amount, false));
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ playtime

    private void minute() {
        int every = plugin.getConfig().getInt("playtime.every-minutes", 60);
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerQuests q = data(p);
            q.playMinutes++;
            q.dirty = true;
            progress(p, QuestDef.Type.PLAYTIME, "ANY", null, 1);
            if (every > 0 && q.playMinutes % every == 0) {
                List<String> r = plugin.getConfig().getStringList("playtime.rewards");
                plugin.rewards().give(p, r);
                p.sendMessage(SmpPlugin.MM.deserialize(plugin.getConfig().getString("playtime.message", "<gold>Playtime reward: <rewards>")
                        .replace("<rewards>", plugin.rewards().describe(r))));
            }
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        data(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        PlayerQuests q = players.remove(e.getPlayer().getUniqueId());
        if (q != null) save(q);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Material m = e.getBlock().getType();
        progress(p, QuestDef.Type.BREAK, m.name(), null, 1);
        if (e.getBlock().getBlockData() instanceof Ageable ag && ag.getAge() == ag.getMaximumAge() && m != Material.KELP && m != Material.BAMBOO
                && !m.name().contains("VINE") || m == Material.MELON || m == Material.PUMPKIN) {
            progress(p, QuestDef.Type.HARVEST, m.name(), null, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        progress(e.getPlayer(), QuestDef.Type.PLACE, e.getBlockPlaced().getType().name(), null, 1);
    }

    private static final NamespacedKey BOSS = new NamespacedKey("mythicdepths", "boss");
    private static final NamespacedKey CITIZEN = new NamespacedKey("colonysmp", "citizen");

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        PersistentDataContainer pdc = dead.getPersistentDataContainer();
        if (pdc.has(BOSS)) {
            String bossId = pdc.has(BOSS, PersistentDataType.STRING) ? pdc.get(BOSS, PersistentDataType.STRING) : "boss";
            // Every player who was in the fight counts, not just the last hit.
            Location at = dead.getLocation();
            for (Player p : dead.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(at) <= 48 * 48 || p.equals(dead.getKiller())) {
                    progress(p, QuestDef.Type.KILL_BOSS, bossId, dead, 1);
                    List<String> r = plugin.getConfig().getStringList("boss-kill-rewards");
                    if (!r.isEmpty()) {
                        plugin.rewards().give(p, r);
                        p.sendMessage(SmpPlugin.mm("<gold>⚔ Boss defeated! <yellow>" + plugin.rewards().describe(r)));
                    }
                }
            }
        }
        Player killer = dead.getKiller();
        if (killer != null && !(dead instanceof Player)) progress(killer, QuestDef.Type.KILL, dead.getType().name(), dead, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            String what = e.getCaught() instanceof org.bukkit.entity.Item it ? it.getItemStack().getType().name() : "ANY";
            progress(e.getPlayer(), QuestDef.Type.FISH, what, null, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent e) {
        if (e.getBreeder() instanceof Player p) progress(p, QuestDef.Type.BREED, e.getEntity().getType().name(), e.getEntity(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent e) {
        if (e.getOwner() instanceof Player p) progress(p, QuestDef.Type.TAME, e.getEntity().getType().name(), e.getEntity(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent e) {
        progress(e.getEnchanter(), QuestDef.Type.ENCHANT, e.getItem().getType().name(), null, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        if (e.getItem().getType().isEdible()) progress(e.getPlayer(), QuestDef.Type.EAT, e.getItem().getType().name(), null, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack result = e.getRecipe().getResult();
        int crafts = 1;
        if (e.isShiftClick()) {
            int min = Integer.MAX_VALUE;
            for (ItemStack it : e.getInventory().getMatrix()) if (it != null && !it.getType().isAir()) min = Math.min(min, it.getAmount());
            crafts = min == Integer.MAX_VALUE ? 1 : min;
        }
        progress(p, QuestDef.Type.CRAFT, result.getType().name(), null, crafts * Math.max(1, result.getAmount()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSmelt(FurnaceExtractEvent e) {
        progress(e.getPlayer(), QuestDef.Type.SMELT, e.getItemType().name(), null, e.getItemAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent e) {
        progress(e.getPlayer(), QuestDef.Type.TRADE, e.getVillager().getType().name(), e.getVillager(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCitizen(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Entity en = e.getRightClicked();
        if (!en.getPersistentDataContainer().has(CITIZEN)) return;
        PlayerQuests q = data(e.getPlayer());
        if (q.metToday.add(en.getUniqueId().toString())) {
            q.dirty = true;
            progress(e.getPlayer(), QuestDef.Type.COLONY_MEET, en.getType().name(), en, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String label = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        progress(e.getPlayer(), QuestDef.Type.COMMAND, label, null, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedBlock()) return;
        Player p = e.getPlayer();
        if (p.isFlying() || p.isGliding() || p.isInsideVehicle() || e.getFrom().getWorld() != e.getTo().getWorld()) return;
        progress(p, QuestDef.Type.WALK, "ANY", null, 1);
    }

    // The quest GUI is read-only.
    @EventHandler
    public void onGuiClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof QuestGui) e.setCancelled(true);
    }

    @EventHandler
    public void onGuiDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof QuestGui) e.setCancelled(true);
    }
}
