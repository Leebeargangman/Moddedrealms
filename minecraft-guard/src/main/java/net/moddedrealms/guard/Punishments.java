package net.moddedrealms.guard;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.ban.BanListType;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.moddedrealms.guard.check.Check;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Escalating, capped temporary bans. Offence history lives in offences.yml. */
public final class Punishments {
    private final GuardPlugin plugin;
    private final File file;
    private final YamlConfiguration data;

    Punishments(GuardPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "offences.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    public int offences(UUID id) {
        return data.getInt(id + ".count", 0);
    }

    public List<String> history(UUID id) {
        return data.getStringList(id + ".history");
    }

    public void ban(Player p, Check check, String replayId) {
        Settings cfg = plugin.settings();
        int count = offences(p.getUniqueId());
        long length = cfg.ladderMillis.get(Math.min(count, cfg.ladderMillis.size() - 1));
        length = Math.min(length, cfg.maxBanMillis);
        Date expires = new Date(System.currentTimeMillis() + length);
        String reason = "Guard: " + check.display + " (replay " + replayId + ")";

        Bukkit.getBanList(BanListType.PROFILE).addBan(p.getPlayerProfile(), reason, expires, "ModdedrealmsGuard");

        String when = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        List<String> h = new ArrayList<>(history(p.getUniqueId()));
        h.add(when + " | " + check.display + " | " + Settings.formatDuration(length) + " | replay " + replayId);
        data.set(p.getUniqueId() + ".name", p.getName());
        data.set(p.getUniqueId() + ".count", count + 1);
        data.set(p.getUniqueId() + ".history", h);
        save();

        Component kick = MiniMessage.miniMessage().deserialize(cfg.banMessage,
                Placeholder.unparsed("check", check.display),
                Placeholder.unparsed("duration", Settings.formatDuration(length)),
                Placeholder.unparsed("replay", String.valueOf(replayId)));
        p.kick(kick);
        plugin.violations().log("BANNED " + p.getName() + " for " + check.display + " for " + Settings.formatDuration(length)
                + " (offence " + (count + 1) + ", replay " + replayId + ")");
        plugin.violations().staff(Component.text("[Guard] ", NamedTextColor.RED)
                .append(Component.text(p.getName() + " was banned for " + Settings.formatDuration(length) + " (" + check.display
                        + ", offence " + (count + 1) + "). Replay " + replayId, NamedTextColor.GRAY)));
    }

    /** Lifts a ban. Returns false if they weren't banned. */
    public boolean unban(OfflinePlayer target) {
        PlayerProfile profile = Bukkit.createProfile(target.getUniqueId(), target.getName());
        var list = Bukkit.getBanList(BanListType.PROFILE);
        if (!list.isBanned(profile)) return false;
        list.pardon(profile);
        return true;
    }

    /** Wipes the offence count (for a wrong ban, so the next one starts at the bottom of the ladder). */
    public void forgive(UUID id) {
        data.set(id + ".count", Math.max(0, offences(id) - 1));
        save();
    }

    public void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save offences.yml: " + e.getMessage());
        }
    }
}
