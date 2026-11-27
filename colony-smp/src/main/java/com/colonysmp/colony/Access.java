package com.colonysmp.colony;

import com.colonysmp.ColonySMP;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Perm;
import com.colonysmp.model.Role;
import com.colonysmp.util.Text;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Who may do what. Every management action goes through {@link #can}: membership and role first, then
 * diplomacy (allies and enemies), then the colony's own permission settings. Server operators can bypass with
 * /colony admin bypass.
 */
public final class Access {

    private final ColonySMP plugin;

    public Access(ColonySMP plugin) {
        this.plugin = plugin;
    }

    /** A player's role in a colony: their membership, or what diplomacy makes them. */
    public Role role(UUID player, Colony c) {
        Role r = c.role(player);
        if (r != null) return r;
        for (Colony other : plugin.colonies().coloniesOf(player)) {
            if (plugin.wars().atWar(other, c)) return Role.ENEMY;
        }
        for (Colony other : plugin.colonies().coloniesOf(player)) {
            if (plugin.diplomacy().allied(other, c) || plugin.diplomacy().openBorders(other, c)) return Role.ALLY;
        }
        return Role.VISITOR;
    }

    public Role role(Player p, Colony c) {
        return role(p.getUniqueId(), c);
    }

    public boolean can(Player p, Colony c, Perm perm) {
        if (c == null) return false;
        if (plugin.isBypassing(p)) return true;
        return c.can(role(p, c), perm);
    }

    /** Checks and tells the player why not. */
    public boolean check(Player p, Colony c, Perm perm) {
        if (can(p, c, perm)) return true;
        Role r = role(p, c);
        Text.send(p, "<red>As " + (r.member ? "a " : "") + r.display.toLowerCase() + (r.member ? " of " : " in ") + Text.esc(c.name)
                + " you can't do that <gray>(needs: " + perm.description.toLowerCase() + ")</gray>.");
        return false;
    }

    public boolean owner(Player p, Colony c) {
        return plugin.isBypassing(p) || c.owner.equals(p.getUniqueId());
    }

    /** Can this player change another member's role (only to below their own rank)? */
    public boolean outranks(Player p, Colony c, UUID other) {
        if (plugin.isBypassing(p)) return true;
        Role mine = c.role(p.getUniqueId()), theirs = c.role(other);
        return mine != null && theirs != null && mine.rank() > theirs.rank() && c.can(mine, Perm.ROLES);
    }
}
