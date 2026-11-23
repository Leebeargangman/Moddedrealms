package com.colonysmp.war;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Category;
import com.colonysmp.core.Goods;
import com.colonysmp.core.TxnType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Modifier;
import com.colonysmp.model.Perm;
import com.colonysmp.model.Relation;
import com.colonysmp.model.Status;
import com.colonysmp.model.War;
import com.colonysmp.npc.Mover;
import com.colonysmp.npc.Npc;
import com.colonysmp.store.Snapshot;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Colony warfare. A colony declares war on another (a raid or a siege); after a warm-up the fighting starts.
 * In a raid the attackers score by looting the defenders' storage and defeating their people, and the side with
 * the better score when time runs out wins. In a siege the attackers must first raise a War Camp (a War Banner
 * placed near the enemy's border); they win by destroying the Town Hall Core or cutting the citizens off from
 * their storage until stability collapses, and the defenders win by holding out or tearing the camp down.
 * Allies with a defensive pact join the defenders. Damage done to the defenders' land is put back afterwards.
 */
public final class WarManager implements Listener {

    private final ColonySMP plugin;
    private final List<War> wars = new ArrayList<>();
    private final Map<String, BossBar> bars = new HashMap<>();
    private final Map<String, Set<UUID>> viewers = new HashMap<>();
    private int seq;

    public WarManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    // ───────────── queries ─────────────

    public List<War> all() {
        return wars;
    }

    public List<War> active() {
        List<War> out = new ArrayList<>();
        for (War w : wars) if (w.phase != War.Phase.ENDED) out.add(w);
        return out;
    }

    public War get(String id) {
        for (War w : wars) if (w.id.equals(id)) return w;
        return null;
    }

    /** The war a colony is in (as a leader or an ally), or null. */
    public War warOf(Colony c) {
        for (War w : wars) if (w.phase != War.Phase.ENDED && w.involves(c.id)) return w;
        return null;
    }

    /** Are these two colonies fighting each other right now? */
    public boolean atWar(Colony a, Colony b) {
        if (a == null || b == null || a == b) return false;
        for (War w : wars) if (w.phase == War.Phase.ACTIVE && w.enemies(a.id, b.id)) return true;
        return false;
    }

    /** Is the colony in an active war (on either side)? */
    public boolean fighting(Colony c) {
        for (War w : wars) if (w.phase == War.Phase.ACTIVE && w.involves(c.id)) return true;
        return false;
    }

    public boolean warmup(Colony c) {
        for (War w : wars) if (w.phase == War.Phase.WARMUP && w.involves(c.id)) return true;
        return false;
    }

    /** The active war in which this colony is on the defending side. */
    private War defending(Colony c) {
        for (War w : wars) if (w.phase == War.Phase.ACTIVE && w.defenders.contains(c.id)) return w;
        return null;
    }

    /** A player on the attacking side of an active war against this colony may break and place blocks on its land. */
    public boolean attackerMayBuild(Player p, Colony c) {
        War w = defending(c);
        if (w == null) return false;
        for (Colony pc : plugin.colonies().coloniesOf(p.getUniqueId())) if (w.attackers.contains(pc.id)) return true;
        return false;
    }

    public boolean pvpAllowed(Player a, Player b) {
        for (Colony ca : plugin.colonies().coloniesOf(a.getUniqueId())) {
            for (Colony cb : plugin.colonies().coloniesOf(b.getUniqueId())) {
                if (!atWar(ca, cb)) continue;
                for (War w : wars) {
                    if (w.phase != War.Phase.ACTIVE || !w.enemies(ca.id, cb.id)) continue;
                    Colony def = plugin.colonies().get(w.defender);
                    if (def == null) continue;
                    for (Player p : new Player[]{a, b}) {
                        if (!p.getWorld().getName().equals(def.world)) continue;
                        if (def.territory.distanceTo(p.getX(), p.getZ()) <= 64) return true;
                        if (w.camp != null && w.camp.distSq(p.getLocation()) < 64 * 64) return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean coreDestroyed(Colony c) {
        for (War w : wars) if (w.defender.equals(c.id) && w.coreDestroyed && (w.phase != War.Phase.ENDED || !w.restore.isEmpty())) return true;
        return false;
    }

    /** A war camp stands in or right next to this chunk (it can't be claimed). */
    public boolean campNear(String world, int cx, int cz) {
        for (War w : wars) {
            if (w.phase == War.Phase.ENDED || w.camp == null || !world.equals(w.campWorld)) continue;
            if (Math.abs((w.camp.x() >> 4) - cx) <= 1 && Math.abs((w.camp.z() >> 4) - cz) <= 1) return true;
        }
        return false;
    }

    // ───────────── recording damage ─────────────

    public void record(Colony defender, Block b) {
        War w = defending(defender);
        if (w == null || !plugin.settings().restoreBlocks) return;
        w.restore.putIfAbsent(b.getX() + "," + b.getY() + "," + b.getZ(), b.getBlockData().getAsString());
    }

    public void recordState(Colony defender, BlockState replaced) {
        War w = defending(defender);
        if (w == null || !plugin.settings().restoreBlocks || replaced == null) return;
        w.restore.putIfAbsent(replaced.getX() + "," + replaced.getY() + "," + replaced.getZ(), replaced.getBlockData().getAsString());
    }

    // ───────────── declaring ─────────────

    /** Declares war. Returns why it couldn't be done, or null. */
    public String declare(Player p, Colony atk, Colony target, War.Goal goal) {
        long now = System.currentTimeMillis();
        var s = plugin.settings();
        if (atk == null) return "You don't belong to a colony.";
        if (!plugin.access().can(p, atk, Perm.DIPLOMACY)) return "You may not declare war for " + Text.esc(atk.name) + ".";
        if (atk.core == null) return "Your colony needs a Town Hall first.";
        if (target == null) return "No such colony.";
        if (target == atk) return "You can't declare war on yourself.";
        if (target.core == null) return Text.esc(target.name) + " has no Town Hall.";
        if (target.shielded()) return Text.esc(target.name) + " is protected by a peace shield for " + Text.duration(target.shieldUntil - now) + ".";
        if (warOf(atk) != null) return "Your colony is already at war.";
        if (warOf(target) != null) return Text.esc(target.name) + " is already at war.";
        if (now < atk.warCooldownUntil) return "Your colony must recover for " + Text.duration(atk.warCooldownUntil - now) + " before another war.";
        Relation r = plugin.diplomacy().relation(atk, target);
        long day = plugin.sim().day(atk);
        if (r.allied) return "You are allied with " + Text.esc(target.name) + ". Break the alliance first.";
        if (r.treaties.contains(Relation.Treaty.NON_AGGRESSION)) return "You have a non-aggression pact with " + Text.esc(target.name) + ". Cancel it first (a betrayal they will remember).";
        if (r.truceUntilDay >= day) return "You are in a truce with " + Text.esc(target.name) + " until day " + r.truceUntilDay + ".";
        boolean offlineOk = goal == War.Goal.RAID && s.allowOfflineRaids;
        if (!offlineOk && target.onlineMembers().size() < s.requireOnlineDefenders) {
            return "At least " + s.requireOnlineDefenders + " member(s) of " + Text.esc(target.name) + " must be online.";
        }
        if (s.declareCost > 0 && atk.treasury.debit(s.declareCost, TxnType.MILITARY, "Declared war on " + target.name, now, day) == null) {
            return "Declaring war costs " + Text.value(s.declareCost) + " from the treasury.";
        }
        War w = new War("war" + (++seq) + "_" + Long.toString(now, 36), atk.id, target.id);
        w.goal = goal;
        w.declaredAt = now;
        w.phaseEnds = now + Math.max(1, s.warmupMinutes) * 60_000L;
        w.world = target.world;
        // allies with a defensive pact stand with the defender
        for (Colony ally : plugin.diplomacy().defenders(target)) {
            if (ally == atk || warOf(ally) != null) continue;
            w.defenders.add(ally.id);
            for (Player m : ally.onlineMembers()) Text.send(m, "<aqua>Your defensive pact calls you to defend " + Text.esc(target.name) + " against " + Text.esc(atk.name) + "!");
        }
        wars.add(w);
        if (atk.shielded()) atk.shieldUntil = 0;
        r.atWar = false;
        r.change(-40);
        r.note(day, atk.name + " declared a " + goal.display.toLowerCase() + " on " + target.name);
        String warm = Text.duration(w.phaseEnds - now);
        atk.log(ColonyEvent.Type.MILITARY, day, p.getName() + " declared a " + goal.display.toLowerCase() + " on " + target.name + ".");
        target.log(ColonyEvent.Type.MILITARY, plugin.sim().day(target), atk.name + " declared a " + goal.display.toLowerCase() + " on " + target.name + ".");
        for (Player m : atk.onlineMembers()) {
            Text.title(m, "<dark_red>⚔ WAR DECLARED", "<red>" + goal.display + " on " + Text.esc(target.name) + "</red> <gray>- fighting in " + warm, 10, 80, 20);
            Fx.sound(m, "minecraft:event.raid.horn", 1f, 0.9f);
            if (goal == War.Goal.SIEGE) {
                Text.send(m, "<red>Siege of " + Text.esc(target.name) + "!</red> <gray>Before it begins (" + warm + "), place a <white>War Banner</white> outside every claim, "
                        + s.campMinDistance + "-" + s.campMaxDistance + " blocks from their border, to raise your <red>War Camp</red>.");
            } else {
                Text.send(m, "<red>Raid on " + Text.esc(target.name) + "!</red> <gray>When it begins (" + warm + "), loot their storage (stand by their Town Hall chest or warehouses) "
                        + "and defeat their people to score. The raid lasts " + s.raidMinutes + " minutes.");
            }
        }
        for (Player m : target.onlineMembers()) {
            Text.title(m, "<dark_red>⚠ WAR", "<red>" + Text.esc(atk.name) + "</red> <gray>declared a " + goal.display.toLowerCase() + " - fighting in " + warm, 10, 80, 20);
            Fx.sound(m, "minecraft:event.raid.horn", 1f, 0.7f);
            Text.send(m, "<red>" + Text.esc(atk.name) + " declared a " + goal.display.toLowerCase() + " on " + Text.esc(target.name) + "!</red> <gray>Stock the armoury: guards and soldiers draw arms when it begins. "
                    + (goal == War.Goal.SIEGE ? "Tear down their War Camp to win; protect your Town Hall Core and keep the way to storage open." : "Keep raiders away from your storage."));
        }
        Bukkit.broadcast(Text.mm(Text.PREFIX + "<red>" + Text.esc(atk.name) + " has declared a " + goal.display.toLowerCase() + " on " + Text.esc(target.name) + "!"));
        plugin.requestSave();
        return null;
    }

    /** An ally joins a war on a side (a call to arms accepted). */
    public String join(War w, Colony ally, boolean attacking) {
        if (w.phase == War.Phase.ENDED) return "That war is over.";
        if (warOf(ally) != null) return Text.esc(ally.name) + " is already at war.";
        (attacking ? w.attackers : w.defenders).add(ally.id);
        Colony lead = plugin.colonies().get(attacking ? w.attacker : w.defender);
        for (Colony c : sides(w)) {
            for (Player p : c.onlineMembers()) Text.send(p, "<gold>" + Text.esc(ally.name) + " joined the war on the side of " + (lead == null ? "?" : Text.esc(lead.name)) + ".");
        }
        plugin.requestSave();
        return null;
    }

    public String surrender(Player p, Colony c) {
        if (c == null) return "You don't belong to a colony.";
        if (!plugin.access().can(p, c, Perm.DIPLOMACY)) return "You may not surrender for " + Text.esc(c.name) + ".";
        War w = warOf(c);
        if (w == null) return "Your colony isn't at war.";
        if (!w.attacker.equals(c.id) && !w.defender.equals(c.id)) {
            // an ally simply leaves
            w.attackers.remove(c.id);
            w.defenders.remove(c.id);
            for (Colony o : sides(w)) for (Player m : o.onlineMembers()) Text.send(m, "<gray>" + Text.esc(c.name) + " withdrew from the war.");
            return null;
        }
        boolean attacking = w.attacker.equals(c.id);
        end(w, !attacking, Text.esc(c.name) + " surrendered", w.phase == War.Phase.ACTIVE);
        return null;
    }

    /** A peace treaty: the war ends without a winner and a truce follows. */
    public void makePeace(War w, int truceDays) {
        end(w, null, "a peace was agreed", false);
        Colony a = plugin.colonies().get(w.attacker), d = plugin.colonies().get(w.defender);
        if (a != null && d != null) {
            Relation r = plugin.diplomacy().relation(a, d);
            r.truceUntilDay = plugin.sim().day(a) + Math.max(0, truceDays);
            r.change(15);
            r.note(plugin.sim().day(a), "Made peace (truce for " + truceDays + " days)");
        }
    }

    // ───────────── the War Banner camp ─────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlaceBanner(BlockPlaceEvent e) {
        if (!Items.is(e.getItemInHand(), Items.WAR_BANNER)) return;
        Player p = e.getPlayer();
        War w = null;
        Colony atk = null;
        for (Colony c : plugin.colonies().coloniesOf(p.getUniqueId())) {
            War x = warOf(c);
            if (x != null && x.attacker.equals(c.id) && x.goal == War.Goal.SIEGE) {
                w = x;
                atk = c;
            }
        }
        if (w == null) {
            e.setCancelled(true);
            Text.send(p, "<red>Declare a siege first (/colony declarewar <colony> siege).");
            return;
        }
        if (w.camp != null) {
            e.setCancelled(true);
            Text.send(p, "<red>Your War Camp already stands.");
            return;
        }
        Colony def = plugin.colonies().get(w.defender);
        Block b = e.getBlockPlaced();
        if (def == null || !b.getWorld().getName().equals(def.world)) {
            e.setCancelled(true);
            Text.send(p, "<red>The War Camp must be in the same world as the enemy.");
            return;
        }
        if (plugin.colonies().at(b) != null) {
            e.setCancelled(true);
            Text.send(p, "<red>The War Camp must be outside every colony's land.");
            return;
        }
        double d = def.territory.distanceTo(b.getX() + 0.5, b.getZ() + 0.5);
        var s = plugin.settings();
        if (d < s.campMinDistance || d > s.campMaxDistance) {
            e.setCancelled(true);
            Text.send(p, "<red>The War Camp must be " + s.campMinDistance + "-" + s.campMaxDistance + " blocks from " + Text.esc(def.name) + "'s border (this spot is " + Math.round(d) + ").");
            return;
        }
        w.camp = BlockPos.of(b);
        w.campWorld = b.getWorld().getName();
        for (Player m : atk.onlineMembers()) Text.send(m, "<red>" + Text.esc(p.getName()) + " raised the War Camp</red> <gray>at " + w.camp + ". Defend it: if it falls, the siege is lost.");
        for (Player m : def.onlineMembers()) Text.send(m, "<red>" + Text.esc(atk.name) + " has raised a War Camp</red> <gray>near your border (" + w.camp.x() + ", " + w.camp.z() + "). Break the banner during the siege to win!");
        Fx.sound(b.getLocation(), "minecraft:event.raid.horn", 1.5f, 1.2f);
        plugin.requestSave();
    }

    private War campAt(Block b) {
        for (War w : wars) {
            if (w.phase == War.Phase.ENDED || w.camp == null || !b.getWorld().getName().equals(w.campWorld)) continue;
            if (w.camp.x() == b.getX() && w.camp.y() == b.getY() && w.camp.z() == b.getZ()) return w;
            if (w.camp.x() == b.getX() && w.camp.y() == b.getY() + 1 && w.camp.z() == b.getZ()) return w;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreakCamp(BlockBreakEvent e) {
        War w = campAt(e.getBlock());
        if (w == null) return;
        Player p = e.getPlayer();
        boolean defender = false;
        for (Colony c : plugin.colonies().coloniesOf(p.getUniqueId())) if (w.defenders.contains(c.id)) defender = true;
        if (w.phase == War.Phase.ACTIVE && defender) {
            e.setDropItems(false);
            end(w, true, Text.esc(p.getName()) + " tore down the War Camp", true);
            return;
        }
        e.setCancelled(true);
        Text.send(p, w.phase == War.Phase.WARMUP ? "<red>The War Camp can only be torn down once the siege begins." : "<red>Only the defenders can tear down this War Camp.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> campAt(b) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> campAt(b) != null);
    }

    /** Breaking the core during a siege is the attackers' victory. */
    public boolean onCoreBreak(Player p, Colony c) {
        War w = defending(c);
        if (w == null || w.goal != War.Goal.SIEGE || !attackerMayBuild(p, c) || !w.defender.equals(c.id)) return false;
        record(c, c.core.block(c.world()));
        w.coreDestroyed = true;
        end(w, false, Text.esc(p.getName()) + " destroyed the Town Hall Core of " + Text.esc(c.name), true);
        return true;
    }

    // ───────────── scoring ─────────────

    /** A citizen of a colony at war fell (to an enemy). */
    public void casualty(Colony victim, Colony killer) {
        if (victim == null) return;
        for (War w : wars) {
            if (w.phase != War.Phase.ACTIVE || !w.involves(victim.id)) continue;
            if (killer != null && !w.enemies(killer.id, victim.id)) continue;
            if (w.attackers.contains(victim.id)) w.defenderScore += 2;
            else w.attackerScore += 2;
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        for (Colony c : plugin.colonies().coloniesOf(dead.getUniqueId())) {
            for (War w : wars) {
                if (w.phase != War.Phase.ACTIVE || !w.involves(c.id)) continue;
                if (w.attackers.contains(c.id)) w.defenderScore += 3;
                else w.attackerScore += 3;
            }
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (!plugin.settings().respawnAtCamp) return;
        for (Colony c : plugin.colonies().coloniesOf(e.getPlayer().getUniqueId())) {
            for (War w : wars) {
                if (w.phase != War.Phase.ACTIVE || !w.attackers.contains(c.id) || w.camp == null) continue;
                World world = Bukkit.getWorld(w.campWorld);
                if (world == null) continue;
                Location l = Mover.safeSpot(w.camp.center(world).add(1, 0, 0));
                if (l != null) e.setRespawnLocation(l);
                return;
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, this::bars, 20);
    }

    // ───────────── ticking ─────────────

    /** Every second. */
    public void tick() {
        long now = System.currentTimeMillis();
        var s = plugin.settings();
        for (War w : new ArrayList<>(wars)) {
            Colony atk = plugin.colonies().get(w.attacker), def = plugin.colonies().get(w.defender);
            if (w.phase == War.Phase.ENDED) {
                if (now >= w.restoreAt) restoreSome(w, 200);
                if (w.restore.isEmpty()) wars.remove(w);
                continue;
            }
            if (atk == null || def == null) {
                end(w, atk == null, "the other colony was dissolved", false);
                continue;
            }
            if (w.phase == War.Phase.WARMUP) {
                long left = w.phaseEnds - now;
                for (long mark : new long[]{600_000, 300_000, 60_000}) {
                    if (left <= mark && left > mark - 1000 && w.warned != mark) {
                        w.warned = mark;
                        for (Player p : atk.onlineMembers()) Text.send(p, "<red>The " + w.goal.display.toLowerCase() + " of " + Text.esc(def.name) + " begins in " + Text.duration(mark) + "."
                                + (w.goal == War.Goal.SIEGE && w.camp == null ? " <yellow>Place your War Camp!" : ""));
                        for (Player p : def.onlineMembers()) Text.send(p, "<red>The " + w.goal.display.toLowerCase() + " by " + Text.esc(atk.name) + " begins in " + Text.duration(mark) + ".");
                    }
                }
                if (left <= 0) {
                    if (w.goal == War.Goal.SIEGE && w.camp == null) end(w, true, Text.esc(atk.name) + " never raised a War Camp", false);
                    else start(w, atk, def);
                }
                continue;
            }
            if (now >= w.phaseEnds) {
                if (w.goal == War.Goal.RAID) {
                    boolean defendersWin = w.defenderScore >= w.attackerScore;
                    end(w, defendersWin, "the raid is over (score " + w.attackerScore + " to " + w.defenderScore + ")", false);
                } else {
                    end(w, true, Text.esc(def.name) + " held out until the siege collapsed", true);
                }
                continue;
            }
            if (w.goal == War.Goal.SIEGE) {
                World cw = w.campWorld == null ? null : Bukkit.getWorld(w.campWorld);
                if (cw != null && w.camp != null && w.camp.loaded(cw) && !Tag.BANNERS.isTagged(w.camp.block(cw).getType())) {
                    end(w, true, "the War Camp fell", true);
                    continue;
                }
                if (now - w.lastSupply >= s.supplyCheckSeconds * 1000L) {
                    w.lastSupply = now;
                    supply(w, atk, def);
                    if (def.stability <= 0) {
                        end(w, false, Text.esc(def.name) + " was cut off from its stores until order collapsed", true);
                        continue;
                    }
                }
            } else if (now - w.lastLoot >= 5000) {
                w.lastLoot = now;
                loot(w, atk, def);
            }
        }
        bars();
    }

    private void start(War w, Colony atk, Colony def) {
        w.phase = War.Phase.ACTIVE;
        long now = System.currentTimeMillis();
        var s = plugin.settings();
        w.phaseEnds = now + (w.goal == War.Goal.RAID ? s.raidMinutes : s.siegeMinutes) * 60_000L;
        w.lastSupply = now;
        for (Colony c : sides(w)) {
            for (Colony o : sides(w)) if (w.enemies(c.id, o.id)) plugin.diplomacy().relation(c, o).atWar = true;
            boolean attacking = w.attackers.contains(c.id);
            c.log(ColonyEvent.Type.MILITARY, plugin.sim().day(c), "The " + w.goal.display.toLowerCase() + (attacking ? " of " + def.name : " by " + atk.name) + " has begun.");
            for (Player p : c.onlineMembers()) {
                if (attacking) Text.title(p, "<dark_red>⚔ THE " + w.goal.display.toUpperCase() + " BEGINS",
                        w.goal == War.Goal.SIEGE ? "<gray>Destroy the <red>Town Hall Core</red> or cut them off from their stores" : "<gray>Loot their storage and defeat their people", 10, 80, 20);
                else Text.title(p, "<dark_red>⚠ UNDER ATTACK", "<gray>Defend the colony! Soldiers and guards are drawing arms", 10, 80, 20);
                Fx.sound(p, "minecraft:event.raid.horn", 1f, attacking ? 1f : 0.8f);
            }
            plugin.npcs().refreshColony(c);
        }
        Bukkit.broadcast(Text.mm(Text.PREFIX + "<red>The " + w.goal.display.toLowerCase() + " of " + Text.esc(def.name) + " by " + Text.esc(atk.name) + " has begun!"));
        plugin.requestSave();
    }

    /** Raiders standing by the defenders' storage carry off goods. */
    private void loot(War w, Colony atk, Colony def) {
        World world = def.world();
        if (world == null) return;
        List<Location> points = new ArrayList<>();
        Location chest = def.chestLocation();
        if (chest != null) points.add(chest);
        for (var b : def.buildings.values()) {
            if (b.valid && (b.type == com.colonysmp.model.BuildingType.WAREHOUSE || b.type == com.colonysmp.model.BuildingType.GRANARY) && !b.containers.isEmpty()) {
                points.add(b.containers.get(0).center(world));
            }
        }
        int raiders = 0;
        for (Location pt : points) {
            for (Player p : world.getNearbyPlayers(pt, 6)) {
                for (Colony c : plugin.colonies().coloniesOf(p.getUniqueId())) if (w.attackers.contains(c.id)) raiders++;
            }
            for (Npc n : plugin.npcs().all()) {
                if (!n.valid() || n.c.status != Status.CITIZEN || !w.attackers.contains(n.c.colonyId)) continue;
                if (n.loc().getWorld() == world && n.loc().distanceSquared(pt) < 36) raiders++;
            }
        }
        if (raiders == 0) return;
        double frac = plugin.settings().raidLootFraction * Math.min(4, raiders);
        long moved = 0;
        List<String> what = new ArrayList<>();
        for (Map.Entry<String, Long> e : new ArrayList<>(def.storage.contents().entrySet())) {
            if (!raw(e.getKey())) continue;
            long take = (long) Math.ceil(def.storage.available(e.getKey()) * frac);
            if (take <= 0) continue;
            long room = atk.storage.room(e.getKey());
            take = def.storage.take(e.getKey(), Math.min(take, Math.max(0, room)));
            if (take <= 0) continue;
            atk.storage.add(e.getKey(), take);
            moved += take;
            if (what.size() < 3) what.add(take + " " + Goods.get(e.getKey()).name());
            if (moved >= 128) break;
        }
        if (moved <= 0) return;
        w.looted += moved;
        w.attackerScore += Math.max(1, (int) (moved / 16));
        for (Player p : atk.onlineMembers()) Text.bar(p, "<gold>Looted " + String.join(", ", what) + " from " + Text.esc(def.name));
        for (Player p : def.onlineMembers()) Text.bar(p, "<red>Raiders are looting your storage! (" + String.join(", ", what) + ")");
    }

    /** Can the defenders' citizens still reach their stores? If not, stability drains. */
    private void supply(War w, Colony atk, Colony def) {
        if (!def.active) return;
        boolean ok = chestReachable(def);
        if (ok) {
            if (w.supplyCut) for (Player p : def.onlineMembers()) Text.send(p, "<green>Supply lines restored: your citizens can reach the Town Hall stores again.");
            w.supplyCut = false;
            return;
        }
        w.supplyCut = true;
        w.attackerScore += 1;
        def.addStability(-plugin.settings().supplyCutStability);
        for (Player p : def.onlineMembers()) {
            Text.send(p, "<red>⚠ Your citizens can't reach the Town Hall stores!</red> <gray>Stability " + Math.round(def.stability) + " (-"
                    + Math.round(plugin.settings().supplyCutStability) + ")." + (def.stability > 0 ? " Clear the way before it collapses." : ""));
        }
        for (Player p : atk.onlineMembers()) Text.bar(p, "<gold>Supply lines cut! " + Text.esc(def.name) + " stability: " + Math.round(def.stability));
    }

    /** Walks the ground from the Town Hall chest outwards; true if any of the defenders' citizens can get to it. */
    public boolean chestReachable(Colony def) {
        World w = def.world();
        if (w == null || def.chest == null || !def.chest.loaded(w)) return true;
        Block above = def.chest.add(0, 1, 0).block(w);
        if (above.getType().isOccluding()) return false;
        Set<BlockPos> targets = new HashSet<>();
        for (Npc n : plugin.npcs().of(def)) {
            Citizen c = n.c;
            if (c.status == Status.CITIZEN && !c.downed()) targets.add(BlockPos.of(n.loc()));
        }
        if (targets.isEmpty()) return true;
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos s = def.chest.add(d[0], dy, d[1]);
                if (walkable(w, s) && seen.add(s)) q.add(s);
            }
        }
        while (!q.isEmpty() && seen.size() < 25000) {
            BlockPos p = q.poll();
            if (targets.contains(p)) return true;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                for (int dy = 1; dy >= -3; dy--) {
                    BlockPos n = p.add(d[0], dy, d[1]);
                    if (!def.territory.near(n.x(), n.z(), 8) || seen.contains(n) || !n.loaded(w)) continue;
                    if (dy == 1 && !open(w, p.add(0, 2, 0))) continue;
                    if (dy < 0 && !open(w, p.add(d[0], 0, d[1]))) continue;
                    if (walkable(w, n)) {
                        seen.add(n);
                        q.add(n);
                        break;
                    }
                }
            }
        }
        return false;
    }

    private static boolean open(World w, BlockPos p) {
        Block b = p.block(w);
        if (b.isPassable()) return true;
        Material m = b.getType();
        return Tag.WOODEN_DOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m) || Tag.WOODEN_TRAPDOORS.isTagged(m) || b.getBlockData() instanceof Openable o && o.isOpen();
    }

    private static boolean walkable(World w, BlockPos p) {
        if (!open(w, p) || !open(w, p.add(0, 1, 0))) return false;
        Block below = p.add(0, -1, 0).block(w);
        return below.getType().isSolid() || below.isLiquid() || below.getType() == Material.LADDER;
    }

    // ───────────── ending ─────────────

    /** Every colony on either side. */
    public List<Colony> sides(War w) {
        List<Colony> out = new ArrayList<>();
        for (String id : w.attackers) {
            Colony c = plugin.colonies().get(id);
            if (c != null) out.add(c);
        }
        for (String id : w.defenders) {
            Colony c = plugin.colonies().get(id);
            if (c != null) out.add(c);
        }
        return out;
    }

    /**
     * Ends a war. defendersWin: true, false, or null for no winner (peace, admin). Spoils go from the losing
     * leader to the winning leader.
     */
    public void end(War w, Boolean defendersWin, String reason, boolean spoils) {
        if (w.phase == War.Phase.ENDED) return;
        Colony atk = plugin.colonies().get(w.attacker), def = plugin.colonies().get(w.defender);
        Colony winner = defendersWin == null ? null : defendersWin ? def : atk;
        Colony loser = defendersWin == null ? null : defendersWin ? atk : def;
        List<Colony> all = sides(w);
        w.phase = War.Phase.ENDED;
        w.restoreAt = System.currentTimeMillis() + plugin.settings().restoreDelaySeconds * 1000L;
        hide(w);
        World cw = w.campWorld == null ? null : Bukkit.getWorld(w.campWorld);
        if (cw != null && w.camp != null && w.camp.loaded(cw) && Tag.BANNERS.isTagged(w.camp.block(cw).getType())) w.camp.block(cw).setType(Material.AIR);
        String loot = "";
        if (spoils && winner != null && loser != null) loot = plunder(loser, winner);
        long now = System.currentTimeMillis();
        if (loser != null) {
            loser.shieldUntil = Math.max(loser.shieldUntil, now + (long) (plugin.settings().shieldDefeatHours * 3600_000L));
            loser.addStability(-15);
        }
        if (winner != null) winner.addStability(10);
        if (atk != null) atk.warCooldownUntil = now + plugin.settings().cooldownMinutes * 60_000L;
        for (Colony a : all) {
            for (Colony b : all) {
                if (a.id.compareTo(b.id) >= 0 || !w.enemies(a.id, b.id)) continue;
                Relation r = plugin.diplomacy().relation(a, b);
                r.atWar = false;
                r.truceUntilDay = Math.max(r.truceUntilDay, plugin.sim().day(a) + plugin.settings().truceDays);
                r.note(plugin.sim().day(a), "The war ended: " + reason.replaceAll("<[^>]+>", ""));
            }
        }
        String head = winner == null ? "The war is over" : Text.esc(winner.name) + " wins the war";
        Bukkit.broadcast(Text.mm(Text.PREFIX + "<gold>" + head + "</gold> <gray>- " + reason + "."));
        for (Colony c : all) {
            boolean won = winner != null && (defendersWin ? w.defenders.contains(c.id) : w.attackers.contains(c.id));
            boolean lost = winner != null && !won;
            long day = plugin.sim().day(c);
            c.log(ColonyEvent.Type.MILITARY, day, (won ? "Victory: " : lost ? "Defeat: " : "The war ended: ") + reason.replaceAll("<[^>]+>", "") + "."
                    + (loot.isEmpty() ? "" : (won ? " Spoils: " : " Plundered: ") + loot + "."));
            if (won) c.addModifier(new Modifier("war", "Victory in war", 6, day + 3));
            if (lost) c.addModifier(new Modifier("war", "Defeat in war", -6, day + 3));
            plugin.military().warEnded(c, won, lost);
            for (Player p : c.onlineMembers()) {
                Text.title(p, won ? "<gold>VICTORY" : lost ? "<dark_red>DEFEAT" : "<gray>PEACE", "<gray>" + reason, 10, 80, 30);
                Fx.sound(p, won ? "minecraft:ui.toast.challenge_complete" : lost ? "minecraft:entity.wither.death" : "minecraft:block.bell.use", 0.8f, 1f);
                if (!loot.isEmpty() && (c == winner || c == loser)) {
                    Text.send(p, c == winner ? "<gold>Spoils of war delivered to your storage: <white>" + loot : "<red>The enemy plundered <white>" + loot + "</white> from your storage.");
                }
                if (c == loser) Text.send(p, "<aqua>Your colony has a " + (int) plugin.settings().shieldDefeatHours + "-hour peace shield to recover.");
                if (plugin.settings().restoreBlocks && c == def && !w.restore.isEmpty()) {
                    Text.send(p, "<gray>War damage to your colony will be repaired in " + Text.duration(w.restoreAt - now) + ".");
                }
            }
            plugin.npcs().refreshColony(c);
        }
        if (!plugin.settings().restoreBlocks) w.restore.clear();
        if (w.restore.isEmpty()) wars.remove(w);
        plugin.requestSave();
    }

    /** Moves the configured share of every raw good, and of the treasury, from the loser to the winner. */
    private String plunder(Colony loser, Colony winner) {
        double frac = plugin.settings().spoilsFraction;
        long items = 0;
        int kinds = 0;
        for (Map.Entry<String, Long> e : new ArrayList<>(loser.storage.contents().entrySet())) {
            if (!raw(e.getKey())) continue;
            long take = (long) Math.floor(loser.storage.available(e.getKey()) * frac);
            if (take <= 0) continue;
            take = loser.storage.take(e.getKey(), take);
            long added = winner.storage.add(e.getKey(), take);
            // what the winner can't store is left behind
            if (added < take) loser.storage.force(e.getKey(), take - added);
            if (added > 0) {
                items += added;
                kinds++;
            }
        }
        long money = (long) Math.floor(loser.treasury.balance() * frac * 0.5);
        long dayL = plugin.sim().day(loser), dayW = plugin.sim().day(winner);
        if (money > 0) money = loser.treasury.transfer(winner.treasury, money, TxnType.REPARATIONS, "Spoils taken by " + winner.name, dayL,
                TxnType.SPOILS, "Spoils from " + loser.name, dayW, System.currentTimeMillis());
        if (items == 0 && money == 0) return "";
        return (items > 0 ? items + " goods (" + kinds + " kinds)" : "") + (items > 0 && money > 0 ? " and " : "") + (money > 0 ? Text.value(money) : "");
    }

    /** Raw resources can be looted: plain goods (ores, ingots, logs, crops, food...), not equipment. */
    public static boolean raw(String key) {
        Category c = Goods.get(key).category();
        return c != Category.TOOL && c != Category.WEAPON && c != Category.ARMOR && !key.equals(Goods.WAR_HORSE);
    }

    private void restoreSome(War w, int max) {
        World world = Bukkit.getWorld(w.world == null ? "" : w.world);
        if (world == null) {
            w.restore.clear();
            return;
        }
        Iterator<Map.Entry<String, String>> it = w.restore.entrySet().iterator();
        int n = 0;
        while (it.hasNext() && n < max) {
            Map.Entry<String, String> e = it.next();
            BlockPos p = BlockPos.parse(e.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            if (!p.loaded(world)) continue;
            try {
                BlockData d = Bukkit.createBlockData(e.getValue());
                p.block(world).setBlockData(d, false);
            } catch (IllegalArgumentException ignored) {
                // a block from another version; leave it
            }
            it.remove();
            n++;
        }
        if (w.restore.isEmpty()) {
            Colony def = plugin.colonies().get(w.defender);
            if (def != null) {
                plugin.townHall().verify(def);
                for (Player p : def.onlineMembers()) Text.send(p, "<green>War damage to " + Text.esc(def.name) + " has been repaired.");
            }
        }
    }

    public void colonyRemoved(Colony c) {
        for (War w : new ArrayList<>(wars)) {
            if (w.phase == War.Phase.ENDED || !w.involves(c.id)) continue;
            if (w.attacker.equals(c.id)) end(w, true, Text.esc(c.name) + " was dissolved", false);
            else if (w.defender.equals(c.id)) end(w, false, Text.esc(c.name) + " was dissolved", false);
            else {
                w.attackers.remove(c.id);
                w.defenders.remove(c.id);
            }
        }
    }

    /** Admin: end a war with no winner. */
    public boolean forceEnd(Colony c) {
        War w = warOf(c);
        if (w == null) return false;
        end(w, null, "an administrator ended it", false);
        return true;
    }

    // ───────────── boss bars ─────────────

    private void bars() {
        long now = System.currentTimeMillis();
        for (War w : wars) {
            if (w.phase == War.Phase.ENDED) continue;
            Colony atk = plugin.colonies().get(w.attacker), def = plugin.colonies().get(w.defender);
            if (atk == null || def == null) continue;
            String time = Text.duration(Math.max(0, w.phaseEnds - now));
            for (Colony c : sides(w)) {
                boolean attacking = w.attackers.contains(c.id);
                String text;
                float prog;
                if (w.phase == War.Phase.WARMUP) {
                    long total = Math.max(1, w.phaseEnds - w.declaredAt);
                    prog = (float) Math.max(0, Math.min(1, (w.phaseEnds - now) / (double) total));
                    text = attacking ? "<red>⚔ " + w.goal.display + " on " + Text.esc(def.name) + " <gray>| begins in <white>" + time
                            + (w.goal == War.Goal.SIEGE ? (w.camp == null ? " <yellow>| Place your War Camp!" : " <green>| War Camp raised") : "")
                            : "<red>⚠ " + Text.esc(atk.name) + " declared a " + w.goal.display.toLowerCase() + " <gray>| begins in <white>" + time;
                } else if (w.goal == War.Goal.SIEGE) {
                    prog = (float) Math.max(0, Math.min(1, def.stability / 100.0));
                    String supply = w.supplyCut ? " <red>| SUPPLY CUT" : "";
                    text = (attacking ? "<dark_red>⚔ SIEGE of " + Text.esc(def.name) : "<dark_red>⚠ SIEGE by " + Text.esc(atk.name)) + " <gray>| <white>" + time
                            + " <gray>| stability " + Math.round(def.stability) + supply;
                } else {
                    int total = Math.max(1, w.attackerScore + w.defenderScore);
                    prog = (float) Math.max(0, Math.min(1, (attacking ? w.attackerScore : w.defenderScore) / (double) total));
                    text = (attacking ? "<dark_red>⚔ RAID on " + Text.esc(def.name) : "<dark_red>⚠ RAID by " + Text.esc(atk.name)) + " <gray>| <white>" + time
                            + " <gray>| score <white>" + w.attackerScore + " - " + w.defenderScore;
                }
                String key = w.id + ":" + c.id;
                BossBar bar = bars.get(key);
                if (bar == null) {
                    bar = BossBar.bossBar(Text.mm(text), prog, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
                    bars.put(key, bar);
                } else {
                    bar.name(Text.mm(text));
                    bar.progress(prog);
                }
                show(bar, viewers.computeIfAbsent(key, k -> new HashSet<>()), c);
            }
        }
    }

    private static void show(BossBar bar, Set<UUID> viewers, Colony c) {
        Set<UUID> want = new HashSet<>();
        for (Player p : c.onlineMembers()) want.add(p.getUniqueId());
        for (UUID u : new HashSet<>(viewers)) {
            if (want.contains(u)) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.hideBossBar(bar);
            viewers.remove(u);
        }
        for (UUID u : want) {
            if (viewers.add(u)) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.showBossBar(bar);
            }
        }
    }

    private void hide(War w) {
        for (String key : new ArrayList<>(bars.keySet())) {
            if (!key.startsWith(w.id + ":")) continue;
            BossBar bar = bars.remove(key);
            for (UUID u : viewers.getOrDefault(key, Set.of())) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.hideBossBar(bar);
            }
            viewers.remove(key);
        }
    }

    public void shutdown() {
        for (War w : wars) hide(w);
    }

    // ───────────── persistence ─────────────

    public void snapshot(Snapshot s) {
        for (War w : wars) {
            YamlConfiguration y = new YamlConfiguration();
            w.save(y);
            s.wars.add(new Snapshot.Row("", w.id, y.saveToString()));
        }
    }

    public void load(Snapshot s) {
        wars.clear();
        for (Snapshot.Row row : s.wars) {
            YamlConfiguration y = new YamlConfiguration();
            try {
                y.loadFromString(row.data());
            } catch (InvalidConfigurationException e) {
                plugin.getLogger().warning("War " + row.id() + " is corrupt and was skipped");
                continue;
            }
            War w = War.load(row.id(), y);
            if (w == null || w.phase == War.Phase.ENDED && w.restore.isEmpty()) continue;
            wars.add(w);
            seq++;
        }
    }
}
