package net.moddedrealms.smp;

import java.time.Duration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Title, sound and pointers on join; a longer greeting the first time. */
final class Welcome implements Listener {
    private final SmpPlugin plugin;

    Welcome(SmpPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("welcome.enabled", true)) return;
        Player p = e.getPlayer();
        boolean first = !p.hasPlayedBefore();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            var ph = Placeholder.unparsed("player", p.getName());
            p.showTitle(Title.title(SmpPlugin.MM.deserialize(cfg.getString("welcome.title", "")),
                    SmpPlugin.MM.deserialize(cfg.getString("welcome.subtitle", "")),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3500), Duration.ofMillis(1000))));
            p.playSound(p.getLocation(), first ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.2f);
            for (String line : cfg.getStringList(first ? "welcome.first-join-message" : "welcome.join-message")) {
                p.sendMessage(SmpPlugin.MM.deserialize(line, ph));
            }
        }, 40L);
    }
}
