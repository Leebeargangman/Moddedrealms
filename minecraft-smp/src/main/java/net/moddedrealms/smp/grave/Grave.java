package net.moddedrealms.smp.grave;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;

/** One grave: what a player dropped when they died, waiting at a player head where they fell. */
final class Grave {
    /** An item and the inventory slot it came from (-1 = not from the inventory, e.g. another plugin's drop). */
    record Stored(int slot, ItemStack item) {}

    final int id;
    final UUID owner;
    final String ownerName;
    final String world;
    final int x, y, z;
    final long created;
    final List<Stored> items = new ArrayList<>();
    int xp;
    String cause;

    // Live, non-persistent entities (respawned whenever the chunk is loaded).
    transient TextDisplay label;
    transient Interaction hitbox;

    Grave(int id, UUID owner, String ownerName, String world, int x, int y, int z, long created) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.created = created;
    }

    World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    Block block() {
        World w = bukkitWorld();
        return w == null ? null : w.getBlockAt(x, y, z);
    }

    Location center() {
        World w = bukkitWorld();
        return w == null ? null : new Location(w, x + 0.5, y, z + 0.5);
    }

    boolean loaded() {
        World w = bukkitWorld();
        return w != null && w.isChunkLoaded(x >> 4, z >> 4);
    }

    boolean at(Block b) {
        return b.getX() == x && b.getY() == y && b.getZ() == z && b.getWorld().getName().equals(world);
    }

    int itemCount() {
        int n = 0;
        for (Stored s : items) n += s.item().getAmount();
        return n;
    }

    String coords() {
        return x + " " + y + " " + z;
    }
}
