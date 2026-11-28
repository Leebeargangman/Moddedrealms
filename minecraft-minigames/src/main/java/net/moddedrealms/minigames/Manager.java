package net.moddedrealms.minigames;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/** Which matches are running, who is in which, and finding (or making) a match for someone who wants to play. */
final class Manager {

    private final MinigamesPlugin plugin;
    private final List<Match> matches = new ArrayList<>();
    private final Map<UUID, Match> byPlayer = new HashMap<>();
    private final Map<UUID, GameType> lastGame = new HashMap<>();
    private volatile Map<UUID, java.util.Set<UUID>> hearers = Map.of();
    private long ticks;

    Manager(MinigamesPlugin plugin) {
        this.plugin = plugin;
    }

    List<Match> matches() {
        return matches;
    }

    Match matchOf(Player p) {
        return byPlayer.get(p.getUniqueId());
    }

    void track(Player p, Match m) {
        byPlayer.put(p.getUniqueId(), m);
        lastGame.put(p.getUniqueId(), m.type);
    }

    void untrack(Player p) {
        byPlayer.remove(p.getUniqueId());
    }

    GameType lastGame(Player p) {
        return lastGame.get(p.getUniqueId());
    }

    boolean slotBusy(GameType type, int slot) {
        for (Match m : matches) if (m.type == type && m.slot == slot) return true;
        return false;
    }

    /** Players in matches of this type (real players only), for the hub's signs and menu. */
    int playing(GameType type) {
        int n = 0;
        for (Match m : matches) if (m.type == type) n += m.humans();
        return n;
    }

    int waiting(GameType type) {
        for (Match m : matches) if (m.type == type && m.open()) return m.present();
        return 0;
    }

    void join(Player p, GameType type) {
        if (!type.enabled(plugin)) {
            p.sendMessage(Component.text(type.title + " is closed right now.", NamedTextColor.RED));
            return;
        }
        Match current = matchOf(p);
        if (current != null) {
            if (current.type == type && current.open() && current.of(p) != null) {
                p.sendMessage(Component.text("You're already in the lobby for " + type.title + ".", NamedTextColor.YELLOW));
                return;
            }
            leave(p, false, false);
        }
        Match m = null;
        for (Match x : matches) {
            if (x.type == type && x.open()) {
                m = x;
                break;
            }
        }
        if (m == null) {
            int slot = -1;
            for (int s = 0; s < type.arenas(plugin); s++) {
                if (!slotBusy(type, s)) {
                    slot = s;
                    break;
                }
            }
            if (slot < 0) {
                p.sendMessage(Component.text("All " + type.title + " arenas are busy. Try again in a minute!", NamedTextColor.RED));
                return;
            }
            m = type.create(plugin, slot);
            m.prepareArena();
            matches.add(m);
        }
        m.join(p);
    }

    /** Out of whatever match they're in. toHub: send them back to the hub (not when they quit). */
    void leave(Player p, boolean quit, boolean toHub) {
        Match m = byPlayer.remove(p.getUniqueId());
        if (m != null) {
            Participant pa = m.of(p);
            if (pa != null) m.leave(pa, quit);
        }
        if (toHub && !quit) plugin.hub().send(p);
    }

    void release(Match m) {
        matches.remove(m);
        byPlayer.values().removeIf(x -> x == m);
    }

    /** Who can read this player's chat (for the async chat event), or null for everyone. */
    java.util.Set<UUID> hearers(UUID speaker) {
        return hearers.get(speaker);
    }

    private void updateHearers() {
        Map<UUID, java.util.Set<UUID>> h = new HashMap<>();
        java.util.Set<UUID> hub = new java.util.HashSet<>();
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) if (matchOf(p) == null) hub.add(p.getUniqueId());
        for (UUID u : hub) h.put(u, hub);
        for (Match m : matches) {
            for (Participant pa : m.players) {
                if (pa.isBot() || pa.gone) continue;
                java.util.Set<UUID> to = new java.util.HashSet<>();
                for (Player o : m.audience(pa)) to.add(o.getUniqueId());
                to.add(pa.id);
                h.put(pa.id, to);
            }
        }
        hearers = h;
    }

    void tick() {
        if (ticks++ % 5 == 0) updateHearers();
        for (Match m : new ArrayList<>(matches)) {
            try {
                m.tick();
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Error in " + m.type.title + " match " + m.slot + ": " + e);
                e.printStackTrace();
                try {
                    m.cleanup();
                } catch (RuntimeException e2) {
                    matches.remove(m);
                }
            }
        }
    }

    void shutdown() {
        for (Match m : new ArrayList<>(matches)) {
            try {
                m.cleanup();
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Cleanup failed: " + e);
            }
        }
        matches.clear();
        byPlayer.clear();
    }
}
