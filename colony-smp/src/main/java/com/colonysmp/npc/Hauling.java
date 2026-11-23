package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Goods;
import com.colonysmp.model.Building;
import com.colonysmp.model.Colony;
import com.colonysmp.sim.Logistics;
import com.colonysmp.util.Fx;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Carrying goods on foot: couriers (warehouse workers and food distributors) take loads between central storage
 * and every workplace; where there are no couriers in body, workers carry their own workplace's goods when its
 * stock fills up or it runs short of inputs. Each load is reserved at its source before anyone walks.
 */
public final class Hauling {

    private final ColonySMP plugin;
    private final NpcManager m;

    Hauling(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    /** Finishes a load already in hand. True while doing so. */
    boolean busy(Npc n, Colony col, long now) {
        Logistics.Haul h = plugin.sim().logistics().current(n.c);
        if (h == null) return false;
        carry(n, col, h);
        return true;
    }

    /** A courier's round: the next load anywhere in the colony. False if there's nothing to carry. */
    boolean think(Npc n, Colony col, Building only, long now) {
        Logistics.Haul h = plugin.sim().logistics().next(col, n.c, only);
        if (h == null) return false;
        carry(n, col, h);
        return true;
    }

    /** A worker without couriers carries for their own workplace when it needs it. */
    boolean selfHaul(Npc n, Colony col, Building b, long now) {
        if (now < n.noHaulUntil) return false;
        var surplus = plugin.sim().production().surplus(col, b);
        long pile = 0;
        for (long v : surplus.values()) pile += v;
        // the shelves are nearly full, or there's a good load waiting (two stacks: worth the walk)
        boolean full = !surplus.isEmpty() && (b.stock.freeSlots() <= 1 || pile >= 128);
        boolean starved = b.waiting != null && !plugin.sim().production().wants(col, b).isEmpty();
        if (!full && !starved) return false;
        if (!think(n, col, b, now)) {
            n.noHaulUntil = now + 200;
            return false;
        }
        return true;
    }

    private void carry(Npc n, Colony col, Logistics.Haul h) {
        World w = n.body.getWorld();
        Logistics lg = plugin.sim().logistics();
        n.working = true;
        if (!h.picked) {
            Location src = h.from == 0 ? m.storagePoint(col, destination(col, w, h, n)) : place(col, w, h.from);
            if (src == null) {
                lg.abandon(col, n.c);
                return;
            }
            if (!n.mover.near(src, 2.8)) {
                n.mover.moveTo(src, plugin.settings().walkSpeed, 2.2);
                n.doing("Fetching " + h.qty + " " + Goods.get(h.good).name() + " from " + (h.from == 0 ? "storage" : col.building(h.from).label()));
                return;
            }
            n.mover.stop();
            if (lg.pickUp(col, n.c, h)) Fx.sound(src, "minecraft:block.barrel.open", 0.4f, 1.1f);
            return;
        }
        Location dst = destination(col, w, h, n);
        if (dst == null) {
            lg.abandon(col, n.c);
            return;
        }
        if (!n.mover.near(dst, 2.8)) {
            n.mover.moveTo(dst, plugin.settings().walkSpeed, 2.2);
            n.doing("Carrying " + h.qty + " " + Goods.get(h.good).name() + " to " + (h.to == 0 ? "storage" : col.building(h.to).label()));
            return;
        }
        n.mover.stop();
        lg.dropOff(col, n.c, h);
        Fx.sound(dst, "minecraft:block.barrel.close", 0.4f, 1.1f);
        n.doing("Delivered " + Goods.get(h.good).name());
    }

    private Location destination(Colony col, World w, Logistics.Haul h, Npc n) {
        if (h.to != 0) return place(col, w, h.to);
        Location from = h.from == 0 ? n.loc() : place(col, w, h.from);
        return m.storagePoint(col, from != null ? from : n.loc());
    }

    private Location place(Colony col, World w, int building) {
        Building b = col.building(building);
        return b == null ? null : m.place(w, b);
    }
}
