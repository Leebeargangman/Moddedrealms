package net.moddedrealms.minigames;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/** Someone in a match: a real player, or a bot filling an empty spot. */
final class Participant {

    enum Role { NONE, INNOCENT, DETECTIVE, MURDERER }

    final UUID id;
    final String name;
    final Bot bot; // null for a real player

    boolean alive = true;
    boolean gone;   // left the match (quit, /hub)
    long outAt;     // when they were knocked out (for placings)

    // Murder Mystery
    Role role = Role.NONE;
    int gold;
    int kills;
    boolean bow;      // carries a bow (detective, or bought/picked up)
    int arrows;
    long arrowAt;     // detective: next arrow then
    long throwAt;     // murderer: knife throw ready then
    boolean hero;     // killed the murderer

    // TNT Run
    int doubleJumps;

    Participant(Player p) {
        this.id = p.getUniqueId();
        this.name = p.getName();
        this.bot = null;
    }

    Participant(Bot bot) {
        this.id = bot.uuid;
        this.name = bot.name;
        this.bot = bot;
    }

    boolean isBot() {
        return bot != null;
    }

    Player player() {
        return bot == null ? Bukkit.getPlayer(id) : null;
    }

    /** The body in the world: the player, or the bot's mannequin. */
    LivingEntity entity() {
        return bot != null ? bot.body() : player();
    }

    Location loc() {
        LivingEntity e = entity();
        return e == null ? null : e.getLocation();
    }

    /** Name with the grey bot tag after it for bots. */
    Component display(NamedTextColor color) {
        Component n = Component.text(name, color);
        return bot == null ? n : n.append(Component.text(" " + Bot.tag(), NamedTextColor.DARK_GRAY));
    }

    Component display() {
        return display(NamedTextColor.WHITE);
    }

    void resetGame() {
        alive = true;
        role = Role.NONE;
        gold = 0;
        kills = 0;
        bow = false;
        arrows = 0;
        hero = false;
        doubleJumps = 0;
    }
}
