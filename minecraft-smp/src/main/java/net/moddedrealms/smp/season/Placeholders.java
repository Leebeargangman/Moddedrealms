package net.moddedrealms.smp.season;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * %mrsmp_...% for TAB, scoreboards, chat and holograms (only registered when PlaceholderAPI is installed):
 * season_countdown ("21d 14h 03m"), season_countdown_seconds, season_name, advent_day (today's door or 0),
 * advent_open ("yes" while today's door is still shut for this player), title (legacy colours) and title_plain.
 */
public final class Placeholders extends PlaceholderExpansion {
    private final SeasonManager s;

    Placeholders(SeasonManager s) {
        this.s = s;
    }

    @Override
    public String getIdentifier() {
        return "mrsmp";
    }

    @Override
    public String getAuthor() {
        return "ModdedRealms";
    }

    @Override
    public String getVersion() {
        return s.plugin().getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer p, String id) {
        return switch (id.toLowerCase(java.util.Locale.ROOT)) {
            case "season_countdown" -> s.countdown().text(false);
            case "season_countdown_seconds" -> s.countdown().text(true);
            case "season_name" -> s.cfg().getString("season.name", "");
            case "advent_day" -> String.valueOf(s.advent().today());
            case "advent_open" -> p instanceof org.bukkit.entity.Player pl && s.advent().today() > 0 && !s.advent().claimedToday(pl) ? "yes" : "no";
            case "title" -> p == null || !s.on() ? "" : s.cosmetics().legacyTitle(p.getUniqueId());
            case "title_plain" -> p == null || !s.on() ? "" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(net.moddedrealms.smp.SmpPlugin.mm(s.cosmetics().title(p.getUniqueId())));
            default -> null;
        };
    }

    /** Registers with PlaceholderAPI if it's there. */
    static void register(SeasonManager s) {
        new Placeholders(s).register();
    }
}
