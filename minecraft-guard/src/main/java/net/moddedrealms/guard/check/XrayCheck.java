package net.moddedrealms.guard.check;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.moddedrealms.guard.GuardPlugin;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * X-ray is a pattern, not a single impossible action, so this only ever alerts: many separate veins of rare ore
 * found while mining very little stone. Caving legitimately looks similar, which is why staff get a replay to
 * judge instead of an automatic ban.
 */
public final class XrayCheck implements Listener {
    private final GuardPlugin plugin;
    private final Map<UUID, Data> data = new HashMap<>();

    private static final class Data {
        final ArrayDeque<Long> veins = new ArrayDeque<>();
        final ArrayDeque<Long> stone = new ArrayDeque<>();
        Location lastOre;
        long lastAlert;
    }

    public XrayCheck(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    private static boolean rare(Material m) {
        return switch (m) {
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE, ANCIENT_DEBRIS, EMERALD_ORE, DEEPSLATE_EMERALD_ORE -> true;
            default -> false;
        };
    }

    private static boolean stone(Material m) {
        return switch (m) {
            case STONE, DEEPSLATE, TUFF, NETHERRACK, GRANITE, DIORITE, ANDESITE, BASALT, BLACKSTONE, COBBLED_DEEPSLATE -> true;
            default -> false;
        };
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Check c = Check.XRAY;
        if (!c.enabled) return;
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL) return;
        Material m = e.getBlock().getType();
        boolean isRare = rare(m);
        if (!isRare && !stone(m)) return;
        long now = System.currentTimeMillis();
        Data d = data.computeIfAbsent(p.getUniqueId(), k -> new Data());
        prune(d.veins, now, c.windowMillis);
        prune(d.stone, now, c.windowMillis);
        if (!isRare) {
            d.stone.addLast(now);
            return;
        }
        Location at = e.getBlock().getLocation();
        boolean sameVein = d.lastOre != null && d.lastOre.getWorld() == at.getWorld() && d.lastOre.distanceSquared(at) <= 9;
        d.lastOre = at;
        if (sameVein) return;
        d.veins.addLast(now);
        int need = (int) c.num("hidden-ores", 14);
        if (d.veins.size() >= need && d.stone.size() < d.veins.size() * 25 && now - d.lastAlert > c.windowMillis) {
            d.lastAlert = now;
            plugin.violations().flag(p, c, false, d.veins.size() + " rare ore veins but only " + d.stone.size()
                    + " stone mined in " + (c.windowMillis / 60000) + " minutes");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        data.remove(e.getPlayer().getUniqueId());
    }

    private static void prune(ArrayDeque<Long> q, long now, long window) {
        while (!q.isEmpty() && now - q.peekFirst() > window) q.pollFirst();
    }
}
