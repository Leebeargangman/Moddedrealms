package com.colonysmp.colony;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.TxnType;
import com.colonysmp.model.Building;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Perm;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Territory;
import com.colonysmp.util.Text;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Colony land, in chunks. A colony is founded with the chunks around its Town Hall and grows by claiming
 * neighbouring chunks, paid for from the treasury and limited by the settlement level, so nobody can grab a
 * huge area at once.
 */
public final class TerritoryManager {

    private final ColonySMP plugin;

    public TerritoryManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    /** Why a chunk can't be claimed by this colony (null if it can). */
    public String checkChunk(Colony c, String world, int cx, int cz, boolean founding) {
        if (!plugin.settings().worldAllowed(world)) return "Colonies can't be founded in this world.";
        if (!founding && !world.equals(c.world)) return "Your colony is in another world.";
        Colony owner = plugin.colonies().chunk(world, cx, cz);
        if (owner == c) return "Your colony already holds this land.";
        if (owner != null) return "This land belongs to " + Text.esc(owner.name) + ".";
        String zone = plugin.noFoundZone(world, cx, cz);
        if (zone != null) return "This land belongs to " + Text.esc(zone) + ": colonies are founded out in the wild.";
        int gap = plugin.settings().chunkGap;
        for (Colony o : plugin.colonies().all()) {
            if (o == c || !o.world.equals(world) || o.territory.isEmpty()) continue;
            if (o.territory.chunkGap(cx, cz) <= gap) {
                return "Too close to " + Text.esc(o.name) + " (colonies keep " + gap + " chunk" + (gap == 1 ? "" : "s") + " apart).";
            }
        }
        if (plugin.wars().campNear(world, cx, cz)) return "A war camp stands on that land.";
        return null;
    }

    /** Claims the land around a Town Hall when the colony is founded. Returns an error or null. */
    public String found(Colony c, Block core) {
        int r = plugin.settings().startRadius;
        int ccx = core.getX() >> 4, ccz = core.getZ() >> 4;
        List<int[]> chunks = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                String err = checkChunk(c, core.getWorld().getName(), ccx + dx, ccz + dz, true);
                if (err != null) return err;
                chunks.add(new int[]{ccx + dx, ccz + dz});
            }
        }
        c.world = core.getWorld().getName();
        for (int[] ch : chunks) {
            c.territory.add(ch[0], ch[1]);
            plugin.colonies().index(c, ch[0], ch[1]);
        }
        return null;
    }

    /** What the next chunk costs. */
    public long cost(Colony c) {
        long extra = Math.max(0, c.territory.size() - (2L * plugin.settings().startRadius + 1) * (2L * plugin.settings().startRadius + 1));
        return plugin.settings().claimCost + plugin.settings().claimCostStep * extra;
    }

    public String claim(Player p, Colony c, Chunk ch) {
        if (!plugin.access().can(p, c, Perm.TERRITORY)) return "You may not claim land for " + Text.esc(c.name) + ".";
        int cx = ch.getX(), cz = ch.getZ();
        String err = checkChunk(c, ch.getWorld().getName(), cx, cz, false);
        if (err != null) return err;
        if (!c.territory.adjacent(cx, cz)) return "You can only claim chunks next to your land.";
        int limit = plugin.settings().chunkLimit(c.level());
        if (c.territory.size() >= limit && !plugin.isBypassing(p)) {
            return "At settlement level " + c.level() + " your colony can hold " + limit + " chunks. Upgrade the Town Hall to claim more.";
        }
        long cost = cost(c);
        long day = plugin.sim().day(c);
        if (!plugin.isBypassing(p) && c.treasury.debit(cost, TxnType.TERRITORY, "Claimed chunk " + cx + "," + cz, System.currentTimeMillis(), day) == null) {
            return "Claiming this land costs " + Text.value(cost) + "; the treasury has " + Text.value(c.treasury.balance()) + ".";
        }
        c.territory.add(cx, cz);
        plugin.colonies().index(c, cx, cz);
        c.log(ColonyEvent.Type.COLONY, day, p.getName() + " claimed chunk " + cx + ", " + cz + " (" + c.territory.size() + "/" + limit + ").");
        plugin.requestSave();
        return null;
    }

    public String unclaim(Player p, Colony c, Chunk ch) {
        if (!plugin.access().can(p, c, Perm.TERRITORY)) return "You may not release land of " + Text.esc(c.name) + ".";
        int cx = ch.getX(), cz = ch.getZ();
        if (!ch.getWorld().getName().equals(c.world) || !c.territory.containsChunk(cx, cz)) return "Your colony doesn't hold this chunk.";
        if (c.core != null && c.core.x() >> 4 == cx && c.core.z() >> 4 == cz) return "That's the Town Hall's chunk.";
        if (!c.territory.connectedWithout(cx, cz)) return "Releasing this chunk would split your land in two.";
        for (Building b : c.buildings.values()) {
            BlockPos a = b.anchor;
            if (a != null && a.x() >> 4 == cx && a.z() >> 4 == cz) return b.label() + " stands on this chunk. Remove it first.";
        }
        c.territory.remove(cx, cz);
        plugin.colonies().unindex(c, cx, cz);
        c.log(ColonyEvent.Type.COLONY, plugin.sim().day(c), p.getName() + " released chunk " + cx + ", " + cz + ".");
        plugin.requestSave();
        return null;
    }

    /** A small map of the land around a player, as chat lines. */
    public List<String> map(Player p) {
        List<String> out = new ArrayList<>();
        World w = p.getWorld();
        int pcx = p.getLocation().getBlockX() >> 4, pcz = p.getLocation().getBlockZ() >> 4;
        Colony mine = plugin.colonies().of(p);
        List<Colony> seen = new ArrayList<>();
        for (int dz = -5; dz <= 5; dz++) {
            StringBuilder row = new StringBuilder("<dark_gray>");
            for (int dx = -9; dx <= 9; dx++) {
                Colony c = plugin.colonies().chunk(w.getName(), pcx + dx, pcz + dz);
                String sym;
                if (dx == 0 && dz == 0) sym = "<white>✚";
                else if (c == null) sym = "<dark_gray>▪";
                else {
                    if (!seen.contains(c)) seen.add(c);
                    String color = c == mine ? "<green>" : mine != null && plugin.diplomacy().allied(mine, c) ? "<aqua>"
                            : mine != null && plugin.wars().atWar(mine, c) ? "<red>" : "<gold>";
                    sym = color + (c.core != null && c.core.x() >> 4 == pcx + dx && c.core.z() >> 4 == pcz + dz ? "■" : "▪");
                }
                row.append(sym);
            }
            out.add(row.toString());
        }
        StringBuilder legend = new StringBuilder("<white>✚ <gray>you  <green>▪ <gray>yours  <aqua>▪ <gray>allies  <red>▪ <gray>enemies  <gold>▪ <gray>others");
        out.add(legend.toString());
        for (Colony c : seen) out.add("<dark_gray>  • <white>" + Text.esc(c.name) + " <gray>(" + c.territory.size() + " chunks)");
        return out;
    }

    public static int[] chunkOf(BlockPos p) {
        return new int[]{p.x() >> 4, p.z() >> 4};
    }

    public static long key(int cx, int cz) {
        return Territory.key(cx, cz);
    }
}
