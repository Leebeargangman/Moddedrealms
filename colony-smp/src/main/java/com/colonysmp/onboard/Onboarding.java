package com.colonysmp.onboard;

import com.colonysmp.ColonySMP;
import com.colonysmp.colony.QuestManager;
import com.colonysmp.core.Goods;
import com.colonysmp.gui.TownHallMenu;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Perm;
import com.colonysmp.npc.CitizenBrain;
import com.colonysmp.npc.Npc;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Items;
import com.colonysmp.util.Messages;
import com.colonysmp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What helps a new colony along after it's founded: the first settlers walking over to greet the founder, the
 * Colony Charter and Starter Farm items, one-time tips on the action bar, a settler's nudge when someone is stuck on
 * a step, and the "while you were away" summary on login. All of it is off with features.onboarding.
 */
public final class Onboarding implements Listener {

    private record Greeting(UUID player, String line, long startAt, long until) {}

    private final ColonySMP plugin;
    private final Map<UUID, Greeting> greetings = new ConcurrentHashMap<>();
    private final Map<UUID, Long> joinedAt = new HashMap<>(), nudgedAt = new HashMap<>();
    private final Map<UUID, int[]> nudges = new HashMap<>();
    private long ticks;

    public Onboarding(ColonySMP plugin) {
        this.plugin = plugin;
    }

    private boolean on() {
        return plugin.settings().feature("onboarding");
    }

    // ───────────── greetings ─────────────

    /** The settler walks over to the player after the delay and says the line (once). */
    public void greet(Citizen c, Player p, String line, long delayTicks) {
        long start = System.currentTimeMillis() + 1500 + delayTicks * 50;
        greetings.put(c.id, new Greeting(p.getUniqueId(), line == null ? "" : line, start, start + 45_000));
    }

    /** Called by a citizen's brain before anything else; true while it's busy greeting. */
    public boolean greet(Npc n) {
        Greeting g = greetings.get(n.c.id);
        if (g == null) return false;
        long t = System.currentTimeMillis();
        Player p = Bukkit.getPlayer(g.player());
        if (p == null || p.getWorld() != n.body.getWorld() || t > g.until() || p.getLocation().distanceSquared(n.body.getLocation()) > 90 * 90) {
            greetings.remove(n.c.id);
            return false;
        }
        if (t < g.startAt()) {
            n.mover.stop();
            CitizenBrain.face(n, p.getEyeLocation());
            n.doing("Arriving at the camp");
            return true;
        }
        Location to = p.getLocation();
        if (!n.mover.near(to, 3.2)) {
            n.mover.moveTo(to, plugin.settings().walkSpeed * 1.2, 2.6);
            n.doing("Coming to meet " + p.getName());
            return true;
        }
        n.mover.stop();
        CitizenBrain.face(n, p.getEyeLocation());
        n.body.swingMainHand();
        if (!g.line().isBlank()) Text.raw(p, "<dark_aqua>" + Text.esc(n.c.name) + "<dark_gray>:</dark_gray> <white>" + g.line());
        Fx.sound(n.body.getLocation(), "minecraft:entity.villager.celebrate", 0.9f, 1.1f);
        greetings.remove(n.c.id);
        return true;
    }

    // ───────────── items ─────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() == Action.PHYSICAL) return;
        ItemStack it = e.getItem();
        String id = Items.id(it);
        if (id == null) return;
        boolean right = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        Player p = e.getPlayer();
        if (id.equals(Items.CHARTER)) {
            e.setCancelled(true);
            if (!right) return;
            Colony c = plugin.colonies().of(p);
            if (c == null) {
                Messages.send(p, "charter.no-colony");
                return;
            }
            if (c.core == null) {
                Messages.send(p, "charter.no-hall");
                return;
            }
            new TownHallMenu(plugin, p, c).open();
            return;
        }
        if (!id.equals(Items.STARTER_FARM)) return;
        e.setCancelled(true);
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) {
            if (right) Messages.bar(p, "farm.click-ground");
            return;
        }
        Colony c = plugin.colonies().of(p);
        if (c == null || c.core == null) {
            Messages.send(p, "charter.no-colony");
            return;
        }
        if (!plugin.access().check(p, c, Perm.MANAGE_BUILDINGS)) return;
        Block ground = e.getClickedBlock();
        // clicked a flower or tall grass: the ground is under it
        if (!ground.getType().isSolid()) ground = ground.getRelative(0, -1, 0);
        String err = plugin.camps().stampFarm(p, c, ground);
        if (err != null) {
            Text.send(p, "<red>" + err);
            return;
        }
        it.setAmount(it.getAmount() - 1);
        Messages.send(p, "farm.done");
    }

    @EventHandler
    public void onHold(PlayerItemHeldEvent e) {
        if (!on()) return;
        Player p = e.getPlayer();
        String id = Items.id(p.getInventory().getItem(e.getNewSlot()));
        if (id == null) return;
        if (id.equals(Items.STARTER_FARM)) hint(p, "farm-item");
        else if (id.equals(Items.CHARTER)) hint(p, "charter");
    }

    /** Monsters leave a founder alone on their own land while the colony is on its first steps. */
    @EventHandler(ignoreCancelled = true)
    public void onTarget(org.bukkit.event.entity.EntityTargetLivingEntityEvent e) {
        if (!(e.getTarget() instanceof Player p) || !(e.getEntity() instanceof org.bukkit.entity.Monster)) return;
        Colony c = plugin.colonies().of(p);
        if (c == null || !(plugin.quests().settling(c) || c.inGrace() && c.questStage < QuestManager.DONE)) return;
        Location l = p.getLocation();
        if (c.world != null && c.world.equals(l.getWorld().getName()) && c.territory.contains(l.getBlockX(), l.getBlockZ())) e.setCancelled(true);
    }

    /** Without a bed or anchor, colony members respawn at their colony, not at spawn (onboarding.respawn-at-colony). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent e) {
        if (!on() || !plugin.getConfig().getBoolean("onboarding.respawn-at-colony", true) || e.isBedSpawn() || e.isAnchorSpawn()) return;
        Colony c = plugin.colonies().of(e.getPlayer());
        Location core = c == null ? null : c.coreLocation();
        if (core == null) return;
        Location spot = plugin.npcs().spotNear(core, 3, 7);
        if (spot == null) return;
        spot.setYaw(e.getRespawnLocation().getYaw());
        e.setRespawnLocation(spot);
    }

    // ───────────── one-time tips ─────────────

    /** Shows a tip on the action bar the first time only. */
    public void hint(Player p, String key) {
        if (!on() || !plugin.funnel().firstTime(p.getUniqueId(), "hint:" + key)) return;
        Messages.bar(p, "hints." + key);
        Fx.sound(p, "minecraft:block.note_block.pling", 0.5f, 1.6f);
    }

    private static final String[] LOOK_HINTS = {"settler", "core", "chest"};

    private void lookHints(Player p, Colony c) {
        UUID u = p.getUniqueId();
        boolean all = true;
        for (String k : LOOK_HINTS) all &= plugin.funnel().seen(u, "hint:" + k);
        if (all) return;
        Entity e = p.getTargetEntity(6);
        Npc n = e == null ? null : plugin.npcs().npc(e);
        if (n != null && c.id.equals(n.c.colonyId)) {
            hint(p, "settler");
            return;
        }
        Block b = p.getTargetBlockExact(6);
        if (b == null) return;
        BlockPos at = BlockPos.of(b);
        if (at.equals(c.core)) hint(p, "core");
        else if (at.equals(c.chest)) hint(p, "chest");
    }

    // ───────────── nudges ─────────────

    /** Every half second. */
    public void tick() {
        ticks++;
        if (!on()) return;
        long now = System.currentTimeMillis();
        boolean nudgeRound = ticks % 10 == 0;
        long idle = Math.max(20, plugin.getConfig().getLong("onboarding.idle-seconds", 60)) * 1000L;
        int most = plugin.getConfig().getInt("onboarding.nudges-per-step", 3);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Colony c = plugin.colonies().of(p);
            if (c == null || c.core == null || c.questStage >= QuestManager.DONE) continue;
            lookHints(p, c);
            if (!nudgeRound || c.questStage > QuestManager.GROW) continue;
            Location core = c.coreLocation();
            if (core == null || core.getWorld() != p.getWorld() || core.distanceSquared(p.getLocation()) > 96 * 96) continue;
            UUID u = p.getUniqueId();
            long last = Math.max(plugin.quests().lastStep(c), Math.max(joinedAt.getOrDefault(u, 0L), nudgedAt.getOrDefault(u, 0L)));
            if (now - last < idle) continue;
            int[] count = nudges.computeIfAbsent(u, k -> new int[QuestManager.DONE + 1]);
            if (count[c.questStage] >= most) continue;
            count[c.questStage]++;
            nudgedAt.put(u, now);
            nudge(p, c);
        }
    }

    private void nudge(Player p, Colony c) {
        String hint = plugin.quests().hint(c.questStage);
        Npc best = null;
        double bd = 32 * 32;
        for (Npc n : plugin.npcs().of(c)) {
            if (!n.valid() || n.sleeping || n.c.downed() || n.body.getWorld() != p.getWorld()) continue;
            double d = n.body.getLocation().distanceSquared(p.getLocation());
            if (d < bd) {
                bd = d;
                best = n;
            }
        }
        if (best != null) {
            if (!best.mover.moving()) CitizenBrain.face(best, p.getEyeLocation());
            Text.raw(p, "<dark_aqua>" + Text.esc(best.c.name) + "<dark_gray>:</dark_gray> <white>" + hint);
            Fx.sound(best.body.getLocation(), "minecraft:entity.villager.ambient", 0.8f, 1.2f);
        }
        Text.bar(p, "<yellow>" + plugin.quests().title(c.questStage) + "</yellow> <gray>· " + plugin.quests().task(c.questStage));
    }

    // ───────────── while you were away ─────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        joinedAt.put(p.getUniqueId(), System.currentTimeMillis());
        nudges.remove(p.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) awaySummary(p);
        }, 70L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        joinedAt.remove(u);
        nudgedAt.remove(u);
        nudges.remove(u);
        Colony c = plugin.colonies().of(p);
        if (c == null || c.core == null) return;
        StringBuilder b = new StringBuilder("#pop=").append(c.population()).append(";#event=").append(c.nextEventId - 1);
        for (Map.Entry<String, Long> g : c.storage.contents().entrySet()) if (g.getValue() > 0) b.append(';').append(g.getKey()).append('=').append(g.getValue());
        plugin.funnel().away(u, new Funnel.Away(c.id, System.currentTimeMillis(), b.toString()));
    }

    private void awaySummary(Player p) {
        if (!on()) return;
        Funnel.Away a = plugin.funnel().away(p.getUniqueId());
        Colony c = plugin.colonies().of(p);
        if (a == null || c == null || !c.id.equals(a.colony())) return;
        long gone = System.currentTimeMillis() - a.time();
        if (gone < plugin.getConfig().getLong("onboarding.away-minutes", 10) * 60_000L) return;
        Map<String, Long> before = new HashMap<>();
        long pop = c.population(), lastEvent = Long.MAX_VALUE;
        for (String part : a.data().split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try {
                long v = Long.parseLong(part.substring(eq + 1));
                String k = part.substring(0, eq);
                if (k.equals("#pop")) pop = v;
                else if (k.equals("#event")) lastEvent = v;
                else before.put(k, v);
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        TreeMap<Long, List<String>> gains = new TreeMap<>(java.util.Comparator.reverseOrder());
        for (Map.Entry<String, Long> g : c.storage.contents().entrySet()) {
            long d = g.getValue() - before.getOrDefault(g.getKey(), 0L);
            if (d > 0 && Goods.known(g.getKey())) gains.computeIfAbsent(d, k -> new ArrayList<>()).add(Goods.get(g.getKey()).name().toLowerCase(java.util.Locale.ROOT));
        }
        List<String> parts = new ArrayList<>();
        int shown = 0;
        for (Map.Entry<Long, List<String>> g : gains.entrySet()) {
            for (String name : g.getValue()) {
                if (shown++ >= 4) break;
                parts.add("<green>+" + g.getKey() + "</green> " + Text.esc(name));
            }
        }
        long dp = c.population() - pop;
        if (dp > 0) parts.add(Messages.get("away.joined", "count", dp));
        else if (dp < 0) parts.add(Messages.get("away.left", "count", -dp));
        List<String> news = new ArrayList<>();
        for (ColonyEvent ev : c.events) {
            if (ev.id() <= lastEvent) continue;
            if (ev.type() == ColonyEvent.Type.MILITARY || ev.type() == ColonyEvent.Type.POPULATION || ev.type() == ColonyEvent.Type.CONSTRUCTION) news.add(ev.message());
        }
        if (parts.isEmpty() && news.isEmpty()) return;
        Text.raw(p, Messages.get("away.header", "colony", c.name, "time", Text.duration(gone)));
        if (!parts.isEmpty()) Text.raw(p, " " + String.join("<gray>, </gray>", parts));
        for (int i = Math.max(0, news.size() - 3); i < news.size(); i++) Text.raw(p, " <dark_gray>·</dark_gray> <gray>" + Text.esc(news.get(i)));
    }
}
