package com.colonysmp.onboard;

import com.colonysmp.ColonySMP;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Perm;
import com.colonysmp.model.Role;
import com.colonysmp.util.Messages;
import com.colonysmp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Playing with friends (features.friends): a player without a colony asks to join one whose members are online (from
 * Elder Rowan's menu or /colony ask), a member lets them in with one click, and they're taken there. The first time
 * a player spends friends.together-minutes on their colony's land with another member, both are rewarded once.
 */
public final class Friends implements org.bukkit.event.Listener {

    /** A colony someone could ask to join: who's online there, its tier and size. */
    public record Joinable(String id, String name, String tier, int population, List<String> online) {}

    private record Ask(String colony, long at) {}

    private final ColonySMP plugin;
    private final Map<UUID, Ask> asks = new HashMap<>();
    private final Map<UUID, Long> lastAsk = new HashMap<>();
    /** Minutes each player has spent on their colony's land with another member there (this session). */
    private final Map<UUID, Integer> together = new HashMap<>();

    public Friends(ColonySMP plugin) {
        this.plugin = plugin;
    }

    public boolean on() {
        return plugin.settings().feature("friends");
    }

    private int askMinutes() {
        return Math.max(1, plugin.getConfig().getInt("friends.ask-expires-minutes"));
    }

    // ───────────── asking ─────────────

    /** Colonies with members online that this player could ask to join (none if they already have one). */
    public List<Joinable> joinable(Player p) {
        List<Joinable> out = new ArrayList<>();
        if (!on() || plugin.colonies().of(p) != null) return out;
        for (Colony c : plugin.colonies().list()) {
            if (c.core == null) continue;
            List<String> online = new ArrayList<>();
            for (Player m : c.onlineMembers()) if (!m.equals(p)) online.add(m.getName());
            if (online.isEmpty()) continue;
            String tier = plugin.tiers().on() ? plugin.tiers().of(c).name() : "Level " + c.level();
            out.add(new Joinable(c.id, c.name, tier, c.population(), online));
        }
        out.sort((a, b) -> Integer.compare(b.online().size(), a.online().size()));
        return out;
    }

    /** Asks the colony's members to let this player in. Null when asked; otherwise why not. */
    public String ask(Player p, Colony c) {
        if (!on()) return Messages.get("friends.off");
        if (c == null) return Messages.get("friends.no-colony");
        if (c.isMember(p.getUniqueId())) return Messages.get("friends.already-member", "colony", c.name);
        if (plugin.colonies().coloniesOf(p.getUniqueId()).size() >= plugin.settings().maxColoniesPerPlayer) return Messages.get("friends.have-one");
        long now = System.currentTimeMillis();
        long cool = Math.max(0, plugin.getConfig().getLong("friends.ask-cooldown-seconds")) * 1000L;
        Long last = lastAsk.get(p.getUniqueId());
        if (last != null && now - last < cool) return Messages.get("friends.wait", "seconds", (cool - (now - last)) / 1000 + 1);
        List<Player> deciders = new ArrayList<>();
        for (Player m : c.onlineMembers()) if (plugin.access().can(m, c, Perm.MEMBERS)) deciders.add(m);
        if (deciders.isEmpty()) return Messages.get("friends.nobody-to-ask", "colony", c.name);
        asks.put(p.getUniqueId(), new Ask(c.id, now));
        lastAsk.put(p.getUniqueId(), now);
        for (Player m : deciders) {
            Text.send(m, Messages.compose("friends.asked-member", "player", Text.esc(p.getName()), "colony", Text.esc(c.name),
                    "yes", "/colony letin " + p.getName(), "no", "/colony turnaway " + p.getName()));
            m.playSound(m.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.4f);
        }
        Text.send(p, Messages.get("friends.asked", "colony", c.name, "minutes", askMinutes()));
        plugin.funnel().record(p.getUniqueId(), "asked_friend");
        plugin.getLogger().info(p.getName() + " asked to join " + c.name + ".");
        return null;
    }

    private Ask pending(Player asker, Colony c) {
        Ask a = asks.get(asker.getUniqueId());
        if (a == null || !a.colony().equals(c.id)) return null;
        if (System.currentTimeMillis() - a.at() > askMinutes() * 60_000L) {
            asks.remove(asker.getUniqueId());
            return null;
        }
        return a;
    }

    /** /colony letin <player>: a member with the Members permission accepts. */
    public void letIn(Player by, Colony c, String name) {
        letIn(by, c, name, Role.RESIDENT);
    }

    /** The same, with the role they join as (the caller has checked the one letting them in may give it). */
    public void letIn(Player by, Colony c, String name, Role as) {
        Player asker = name == null ? null : Bukkit.getPlayerExact(name);
        if (asker == null || pending(asker, c) == null) {
            Text.send(by, Messages.get("friends.no-ask", "player", name == null ? "?" : name));
            return;
        }
        if (!plugin.access().check(by, c, Perm.MEMBERS)) return;
        asks.remove(asker.getUniqueId());
        if (plugin.colonies().coloniesOf(asker.getUniqueId()).size() >= plugin.settings().maxColoniesPerPlayer) {
            Text.send(by, Messages.get("friends.joined-elsewhere", "player", asker.getName()));
            return;
        }
        c.members.put(asker.getUniqueId(), as == null || !as.member || as == Role.OWNER ? Role.RESIDENT : as);
        c.invites.remove(asker.getUniqueId());
        c.inviteRoles.remove(asker.getUniqueId());
        c.log(ColonyEvent.Type.COLONY, plugin.sim().day(c), asker.getName() + " joined the colony (let in by " + by.getName() + ").");
        for (Player m : c.onlineMembers()) {
            if (!m.equals(asker)) Text.send(m, Messages.get("friends.joined-members", "player", asker.getName(), "colony", c.name, "by", by.getName()));
        }
        plugin.quests().refresh(c);
        plugin.requestSave();
        plugin.getLogger().info(by.getName() + " let " + asker.getName() + " into " + c.name + ".");
        welcome(asker, c, by.getName());
    }

    /** /colony turnaway <player>. */
    public void turnAway(Player by, Colony c, String name) {
        Player asker = name == null ? null : Bukkit.getPlayerExact(name);
        if (asker == null || pending(asker, c) == null) {
            Text.send(by, Messages.get("friends.no-ask", "player", name == null ? "?" : name));
            return;
        }
        if (!plugin.access().check(by, c, Perm.MEMBERS)) return;
        asks.remove(asker.getUniqueId());
        Text.send(by, Messages.get("friends.turned-away-member", "player", asker.getName()));
        Text.send(asker, Messages.get("friends.turned-away", "colony", c.name));
    }

    /**
     * A new member's welcome: the Colony Charter, and a trip to the colony (once, when they join; there's no going
     * back and forth by teleport).
     */
    public void welcome(Player p, Colony c, String by) {
        plugin.funnel().record(p.getUniqueId(), "joined_friend");
        com.colonysmp.command.ColonyCommand.give(p, plugin.items().charter(c.id, c.name));
        Text.title(p, Messages.compose("friends.welcome-title", "colony", Text.esc(c.name)), Messages.get("friends.welcome-subtitle", "player", by), 10, 70, 20);
        Location core = c.coreLocation();
        World w = c.world();
        if (core == null || w == null || !plugin.getConfig().getBoolean("friends.teleport-on-join")) {
            Text.send(p, Messages.get("friends.welcome", "colony", c.name));
            return;
        }
        w.getChunkAtAsync(core).thenAccept(ch -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            Location spot = plugin.npcs().spotNear(core, 3, 7);
            spot.setYaw(p.getLocation().getYaw());
            p.teleportAsync(spot).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
                Text.send(p, Messages.get("friends.welcome", "colony", c.name));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.1f);
            }));
        }));
    }

    // ───────────── playing together ─────────────

    /** Every minute: members on their colony's land with another member there count a minute together. */
    public void tick() {
        if (!on()) return;
        int need = Math.max(1, plugin.getConfig().getInt("friends.together-minutes"));
        List<String> rewards = plugin.getConfig().getStringList("friends.together-rewards");
        for (Colony c : plugin.colonies().list()) {
            List<Player> here = new ArrayList<>();
            for (Player m : c.onlineMembers()) if (plugin.colonies().at(m.getLocation()) == c) here.add(m);
            if (here.size() < 2) continue;
            for (Player m : here) {
                int mins = together.merge(m.getUniqueId(), 1, Integer::sum);
                if (mins < need || rewards.isEmpty() || !plugin.funnel().firstTime(m.getUniqueId(), "reward:together")) continue;
                String with = here.stream().filter(o -> !o.equals(m)).map(Player::getName).findFirst().orElse("a friend");
                String said = plugin.quests().pay(c, m, rewards, "Playing together");
                Text.send(m, Messages.compose("friends.together", "player", Text.esc(with), "colony", Text.esc(c.name), "minutes", need,
                        "rewards", Text.esc(said)));
                m.playSound(m.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
                plugin.funnel().record(m.getUniqueId(), "played_together");
            }
        }
    }

    @org.bukkit.event.EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        together.remove(e.getPlayer().getUniqueId());
        asks.remove(e.getPlayer().getUniqueId());
    }
}
