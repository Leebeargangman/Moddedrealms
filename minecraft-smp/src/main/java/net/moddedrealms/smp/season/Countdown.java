package net.moddedrealms.smp.season;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * The floating countdown to the season's launch at spawn, and the text behind %mrsmp_season_countdown%. The sign
 * isn't saved with the world: it's put up while its chunk is loaded and any left over from a crash is removed.
 */
public final class Countdown implements Listener {
    private final SeasonManager s;
    private final NamespacedKey tag;
    private TextDisplay sign;

    Countdown(SeasonManager s) {
        this.s = s;
        this.tag = new NamespacedKey(s.plugin(), "season_countdown");
    }

    private boolean enabled() {
        return s.cfg().getBoolean("countdown.enabled", true);
    }

    /** The launch moment in UK time, or null when it isn't set. */
    public ZonedDateTime target() {
        try {
            return LocalDateTime.parse(s.cfg().getString("countdown.target", "2026-12-18T18:00")).atZone(s.zone());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Time left to the launch, or null once it has passed (or none is set). */
    public Duration left() {
        ZonedDateTime t = target();
        if (t == null) return null;
        Duration d = Duration.between(s.now(), t);
        return d.isNegative() ? null : d;
    }

    /** "21d 14h 03m 12s"; "now" once it's passed. */
    public String text(boolean seconds) {
        Duration d = left();
        if (d == null) return "now";
        long days = d.toDays(), h = d.toHoursPart(), m = d.toMinutesPart(), sec = d.toSecondsPart();
        String out = (days > 0 ? days + "d " : "") + (days > 0 || h > 0 ? h + "h " : "") + String.format("%02dm", m);
        return seconds ? out + String.format(" %02ds", sec) : out;
    }

    private Location spot() {
        String[] f = s.cfg().getString("countdown.location", "").split(",");
        if (f.length < 4) return null;
        World w = Bukkit.getWorld(f[0].trim());
        if (w == null) return null;
        try {
            return new Location(w, Double.parseDouble(f[1].trim()), Double.parseDouble(f[2].trim()), Double.parseDouble(f[3].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The sign should be up: enabled, before the launch or within after-hours of it. */
    private boolean showing() {
        if (!enabled()) return false;
        ZonedDateTime t = target();
        if (t == null) return false;
        return s.now().isBefore(t.plusHours(s.cfg().getLong("countdown.after-hours", 72)));
    }

    void second() {
        Location at = spot();
        if (at == null || !showing() || !at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
            remove();
            return;
        }
        if (sign == null || !sign.isValid()) {
            // in the open: up out of any block it was placed in
            Location l = at.clone();
            for (int i = 0; i < 12 && !l.getBlock().isPassable(); i++) l.add(0, 1, 0);
            for (Entity e : l.getWorld().getNearbyEntities(l, 3, 4, 3)) if (e.getPersistentDataContainer().has(tag, PersistentDataType.BYTE)) e.remove();
            sign = l.getWorld().spawn(l, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setShadowed(true);
                d.setDefaultBackground(false);
                d.setBackgroundColor(org.bukkit.Color.fromARGB(120, 10, 20, 40));
                d.setLineWidth(240);
                d.setViewRange(1.2f);
                d.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            });
        }
        var c = s.cfg();
        StringBuilder b = new StringBuilder(c.getString("countdown.title", "<bold>The Frozen Crown")).append('\n');
        if (left() != null) b.append(c.getString("countdown.line", "Season 1 begins in {time}").replace("{time}", text(true)));
        else b.append(c.getString("countdown.after", "Season 1 has begun!"));
        if (s.advent().today() > 0) b.append('\n').append(c.getString("countdown.advent-line", "<gold>Today's advent door is open: /advent"));
        sign.text(SmpPlugin.mm(b.toString()));
    }

    /** After a reload: put the sign up again where the config now says. */
    void reset() {
        remove();
    }

    void remove() {
        if (sign != null && sign.isValid()) sign.remove();
        sign = null;
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en.getPersistentDataContainer().has(tag, PersistentDataType.BYTE) && (sign == null || !en.getUniqueId().equals(sign.getUniqueId()))) en.remove();
        }
    }
}
